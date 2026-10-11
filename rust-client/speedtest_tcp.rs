// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a tiny download-only TCP endpoint for the in-tunnel speed test.
//
// The app is kept outside its own VPN, so it cannot open an ordinary socket
// through the tunnel. Instead the client speaks just enough TCP by hand:
// it opens a connection, sends one HTTP request and acknowledges whatever
// arrives, counting the bytes. Nothing received is stored.
//
// This file uses only the standard library so it can be tested on its own.

use std::time::{Duration, Instant};

pub const FLAG_FIN: u8 = 0x01;
pub const FLAG_SYN: u8 = 0x02;
pub const FLAG_RST: u8 = 0x04;
pub const FLAG_PSH: u8 = 0x08;
pub const FLAG_ACK: u8 = 0x10;

const WINDOW_SCALE_SHIFT: u8 = 6;
const MAX_OUT_OF_ORDER_RANGES: usize = 64;
const MAX_SEQUENCE_JUMP: u32 = 64 * 1024 * 1024;
const RETRANSMIT_AFTER: Duration = Duration::from_millis(1000);
const MAX_SYN_ATTEMPTS: u8 = 4;
const MAX_REQUEST_ATTEMPTS: u8 = 5;
const PACKET_SCRATCH: usize = 1024;

#[inline]
fn seq_lt(a: u32, b: u32) -> bool {
    (a.wrapping_sub(b) as i32) < 0
}

#[inline]
fn seq_le(a: u32, b: u32) -> bool {
    !seq_lt(b, a)
}

fn checksum_add(mut sum: u32, data: &[u8]) -> u32 {
    let mut chunks = data.chunks_exact(2);
    for chunk in &mut chunks {
        sum = sum.wrapping_add(u32::from(u16::from_be_bytes([chunk[0], chunk[1]])));
    }
    if let Some(&last) = chunks.remainder().first() {
        sum = sum.wrapping_add(u32::from(last) << 8);
    }
    sum
}

fn checksum_finish(mut sum: u32) -> u16 {
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

/// One TCP segment taken out of an IPv4 packet.
pub struct Segment<'a> {
    pub source_ip: u32,
    pub source_port: u16,
    pub destination_port: u16,
    pub seq: u32,
    pub ack: u32,
    pub flags: u8,
    pub options: &'a [u8],
    pub payload: &'a [u8],
}

/// Cheap test used on the hot path: source address, source port and the
/// destination port of an unfragmented IPv4 TCP packet.
#[inline]
pub fn peek_ipv4_tcp(packet: &[u8]) -> Option<(u32, u16, u16)> {
    if packet.len() < 40 || packet[0] >> 4 != 4 || packet[9] != 6 {
        return None;
    }
    let header_len = usize::from(packet[0] & 0x0f) * 4;
    if header_len < 20 || packet.len() < header_len + 20 {
        return None;
    }
    let source_ip = u32::from_be_bytes([packet[12], packet[13], packet[14], packet[15]]);
    let tcp = &packet[header_len..];
    Some((
        source_ip,
        u16::from_be_bytes([tcp[0], tcp[1]]),
        u16::from_be_bytes([tcp[2], tcp[3]]),
    ))
}

pub fn parse_ipv4_tcp(packet: &[u8]) -> Option<Segment<'_>> {
    let (source_ip, source_port, destination_port) = peek_ipv4_tcp(packet)?;
    // Fragments cannot be handled here; real servers do not send them.
    if u16::from_be_bytes([packet[6], packet[7]]) & 0x3fff != 0 {
        return None;
    }
    let header_len = usize::from(packet[0] & 0x0f) * 4;
    let total_len = usize::from(u16::from_be_bytes([packet[2], packet[3]]));
    if total_len < header_len + 20 || total_len > packet.len() {
        return None;
    }
    let tcp = &packet[header_len..total_len];
    let data_offset = usize::from(tcp[12] >> 4) * 4;
    if data_offset < 20 || data_offset > tcp.len() {
        return None;
    }
    Some(Segment {
        source_ip,
        source_port,
        destination_port,
        seq: u32::from_be_bytes([tcp[4], tcp[5], tcp[6], tcp[7]]),
        ack: u32::from_be_bytes([tcp[8], tcp[9], tcp[10], tcp[11]]),
        flags: tcp[13],
        options: &tcp[20..data_offset],
        payload: &tcp[data_offset..],
    })
}

/// Writes one IPv4 TCP packet into `out`; returns its length.
#[allow(clippy::too_many_arguments)]
pub fn build_packet(
    out: &mut [u8],
    local_ip: u32,
    remote_ip: u32,
    local_port: u16,
    remote_port: u16,
    seq: u32,
    ack: u32,
    flags: u8,
    window: u16,
    options: &[u8],
    payload: &[u8],
    ip_id: u16,
) -> Option<usize> {
    if options.len() % 4 != 0 || options.len() > 40 {
        return None;
    }
    let tcp_len = 20 + options.len() + payload.len();
    let total = 20 + tcp_len;
    if total > out.len() || total > usize::from(u16::MAX) {
        return None;
    }
    let (ip, tcp) = out[..total].split_at_mut(20);
    ip[0] = 0x45;
    ip[1] = 0;
    ip[2..4].copy_from_slice(&(total as u16).to_be_bytes());
    ip[4..6].copy_from_slice(&ip_id.to_be_bytes());
    ip[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
    ip[8] = 64;
    ip[9] = 6;
    ip[10..12].copy_from_slice(&[0, 0]);
    ip[12..16].copy_from_slice(&local_ip.to_be_bytes());
    ip[16..20].copy_from_slice(&remote_ip.to_be_bytes());
    let ip_checksum = checksum_finish(checksum_add(0, ip));
    ip[10..12].copy_from_slice(&ip_checksum.to_be_bytes());

    tcp[0..2].copy_from_slice(&local_port.to_be_bytes());
    tcp[2..4].copy_from_slice(&remote_port.to_be_bytes());
    tcp[4..8].copy_from_slice(&seq.to_be_bytes());
    tcp[8..12].copy_from_slice(&ack.to_be_bytes());
    tcp[12] = (((20 + options.len()) / 4) as u8) << 4;
    tcp[13] = flags;
    tcp[14..16].copy_from_slice(&window.to_be_bytes());
    tcp[16..18].copy_from_slice(&[0, 0]);
    tcp[18..20].copy_from_slice(&[0, 0]);
    tcp[20..20 + options.len()].copy_from_slice(options);
    tcp[20 + options.len()..].copy_from_slice(payload);

    let mut pseudo = [0u8; 12];
    pseudo[0..4].copy_from_slice(&local_ip.to_be_bytes());
    pseudo[4..8].copy_from_slice(&remote_ip.to_be_bytes());
    pseudo[9] = 6;
    pseudo[10..12].copy_from_slice(&(tcp_len as u16).to_be_bytes());
    let tcp_checksum = checksum_finish(checksum_add(checksum_add(0, &pseudo), tcp));
    tcp[16..18].copy_from_slice(&tcp_checksum.to_be_bytes());
    Some(total)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConnState {
    SynSent,
    Established,
    /// The server sent everything it had.
    Finished,
    Failed,
}

/// One download connection.
pub struct Conn {
    local_ip: u32,
    remote_ip: u32,
    local_port: u16,
    remote_port: u16,
    mss: u16,
    iss: u32,
    snd_nxt: u32,
    rcv_nxt: u32,
    state: ConnState,
    syn_at: Instant,
    syn_attempts: u8,
    request_at: Instant,
    request_attempts: u8,
    request_acked: bool,
    header_seen: bool,
    sack_permitted: bool,
    out_of_order: Vec<(u32, u32)>,
    recent_range_start: Option<u32>,
    need_ack: bool,
    bytes: u64,
    rtt: Option<Duration>,
    ip_id: u16,
    failure: Option<String>,
}

impl Conn {
    pub fn new(
        local_ip: u32,
        remote_ip: u32,
        local_port: u16,
        remote_port: u16,
        iss: u32,
        mss: u16,
        now: Instant,
    ) -> Self {
        Self {
            local_ip,
            remote_ip,
            local_port,
            remote_port,
            mss,
            iss,
            snd_nxt: iss.wrapping_add(1),
            rcv_nxt: 0,
            state: ConnState::SynSent,
            syn_at: now,
            syn_attempts: 0,
            request_at: now,
            request_attempts: 0,
            request_acked: false,
            header_seen: false,
            sack_permitted: false,
            out_of_order: Vec::new(),
            recent_range_start: None,
            need_ack: false,
            bytes: 0,
            rtt: None,
            ip_id: (iss >> 8) as u16,
            failure: None,
        }
    }

    pub fn local_port(&self) -> u16 {
        self.local_port
    }

    pub fn state(&self) -> ConnState {
        self.state
    }

    pub fn is_open(&self) -> bool {
        matches!(self.state, ConnState::SynSent | ConnState::Established)
    }

    pub fn bytes(&self) -> u64 {
        self.bytes
    }

    /// Time between the first SYN and its answer: one trip to the server and back.
    pub fn rtt(&self) -> Option<Duration> {
        self.rtt
    }

    pub fn failure(&self) -> Option<&str> {
        self.failure.as_deref()
    }

    fn emit(
        &mut self,
        seq: u32,
        flags: u8,
        options: &[u8],
        payload: &[u8],
        tx: &mut dyn FnMut(&[u8]),
    ) {
        let mut scratch = [0u8; PACKET_SCRATCH];
        self.ip_id = self.ip_id.wrapping_add(1);
        let ack = if flags & FLAG_ACK != 0 { self.rcv_nxt } else { 0 };
        if let Some(length) = build_packet(
            &mut scratch,
            self.local_ip,
            self.remote_ip,
            self.local_port,
            self.remote_port,
            seq,
            ack,
            flags,
            u16::MAX,
            options,
            payload,
            self.ip_id,
        ) {
            tx(&scratch[..length]);
        }
    }

    fn send_syn(&mut self, now: Instant, tx: &mut dyn FnMut(&[u8])) {
        self.syn_at = now;
        self.syn_attempts = self.syn_attempts.saturating_add(1);
        let mss = self.mss.to_be_bytes();
        let options = [
            2,
            4,
            mss[0],
            mss[1], // maximum segment size
            1,
            3,
            3,
            WINDOW_SCALE_SHIFT, // window scale
            1,
            1,
            4,
            2, // selective acknowledgements permitted
        ];
        self.emit(self.iss, FLAG_SYN, &options, &[], tx);
    }

    fn send_request(&mut self, now: Instant, request: &[u8], tx: &mut dyn FnMut(&[u8])) {
        self.request_at = now;
        self.request_attempts = self.request_attempts.saturating_add(1);
        self.snd_nxt = self.iss.wrapping_add(1).wrapping_add(request.len() as u32);
        self.emit(
            self.iss.wrapping_add(1),
            FLAG_PSH | FLAG_ACK,
            &[],
            request,
            tx,
        );
        self.need_ack = false;
    }

    fn send_ack(&mut self, tx: &mut dyn FnMut(&[u8])) {
        let mut options = [0u8; 28];
        let mut options_len = 0;
        if self.sack_permitted && !self.out_of_order.is_empty() {
            // The block holding the newest segment goes first, as senders expect.
            let mut blocks = [(0u32, 0u32); 3];
            let mut count = 0;
            if let Some(start) = self.recent_range_start
                && let Some(&range) = self
                    .out_of_order
                    .iter()
                    .find(|range| seq_le(range.0, start) && seq_lt(start, range.1))
            {
                blocks[count] = range;
                count += 1;
            }
            for &range in &self.out_of_order {
                if count == blocks.len() {
                    break;
                }
                if blocks[..count].contains(&range) {
                    continue;
                }
                blocks[count] = range;
                count += 1;
            }
            options[0] = 1;
            options[1] = 1;
            options[2] = 5;
            options[3] = (2 + 8 * count) as u8;
            for (index, block) in blocks[..count].iter().enumerate() {
                let at = 4 + index * 8;
                options[at..at + 4].copy_from_slice(&block.0.to_be_bytes());
                options[at + 4..at + 8].copy_from_slice(&block.1.to_be_bytes());
            }
            options_len = 4 + 8 * count;
        }
        let seq = self.snd_nxt;
        let options = options;
        self.emit(seq, FLAG_ACK, &options[..options_len], &[], tx);
        self.need_ack = false;
    }

    /// Opens the connection.
    pub fn start(&mut self, now: Instant, tx: &mut dyn FnMut(&[u8])) {
        self.send_syn(now, tx);
    }

    /// Sends an acknowledgement if one is owed. Call after a batch of segments.
    pub fn flush_ack(&mut self, tx: &mut dyn FnMut(&[u8])) {
        if self.need_ack && self.state != ConnState::SynSent {
            self.send_ack(tx);
        }
    }

    /// Retransmits the SYN or the request when they went unanswered.
    pub fn on_tick(&mut self, now: Instant, request: &[u8], tx: &mut dyn FnMut(&[u8])) {
        match self.state {
            ConnState::SynSent => {
                if now.saturating_duration_since(self.syn_at) >= RETRANSMIT_AFTER {
                    if self.syn_attempts >= MAX_SYN_ATTEMPTS {
                        self.fail("сервер замера не отвечает");
                    } else {
                        self.send_syn(now, tx);
                    }
                }
            }
            ConnState::Established => {
                if !self.request_acked
                    && !self.header_seen
                    && now.saturating_duration_since(self.request_at) >= RETRANSMIT_AFTER
                {
                    if self.request_attempts >= MAX_REQUEST_ATTEMPTS {
                        self.fail("сервер замера не ответил на запрос");
                        self.send_reset(tx);
                    } else {
                        self.send_request(now, request, tx);
                    }
                }
                self.flush_ack(tx);
            }
            ConnState::Finished | ConnState::Failed => {}
        }
    }

    fn send_reset(&mut self, tx: &mut dyn FnMut(&[u8])) {
        let seq = self.snd_nxt;
        self.emit(seq, FLAG_RST | FLAG_ACK, &[], &[], tx);
    }

    /// Ends the download: tells the server to stop sending.
    pub fn abort(&mut self, tx: &mut dyn FnMut(&[u8])) {
        if self.state == ConnState::Established {
            self.send_reset(tx);
            self.state = ConnState::Finished;
        } else if self.state == ConnState::SynSent {
            self.state = ConnState::Failed;
        }
    }

    /// Answers a segment that arrives after the connection is over.
    pub fn refuse(&mut self, segment: &Segment<'_>, tx: &mut dyn FnMut(&[u8])) {
        if segment.flags & FLAG_RST == 0 {
            self.send_reset(tx);
        }
    }

    fn fail(&mut self, reason: &str) {
        self.state = ConnState::Failed;
        if self.failure.is_none() {
            self.failure = Some(reason.to_owned());
        }
    }

    fn insert_out_of_order(&mut self, start: u32, end: u32) {
        let mut merged = (start, end);
        let mut kept = Vec::with_capacity(self.out_of_order.len() + 1);
        let mut placed = false;
        for &range in &self.out_of_order {
            if seq_lt(range.1, merged.0) {
                kept.push(range);
            } else if seq_lt(merged.1, range.0) {
                if !placed {
                    kept.push(merged);
                    placed = true;
                }
                kept.push(range);
            } else {
                if seq_lt(range.0, merged.0) {
                    merged.0 = range.0;
                }
                if seq_lt(merged.1, range.1) {
                    merged.1 = range.1;
                }
            }
        }
        if !placed {
            kept.push(merged);
        }
        if kept.len() <= MAX_OUT_OF_ORDER_RANGES {
            self.out_of_order = kept;
            self.recent_range_start = Some(start);
        }
    }

    fn absorb_out_of_order(&mut self) -> u64 {
        let mut gained = 0u64;
        while let Some(&(start, end)) = self.out_of_order.first() {
            if !seq_le(start, self.rcv_nxt) {
                break;
            }
            if seq_lt(self.rcv_nxt, end) {
                gained += u64::from(end.wrapping_sub(self.rcv_nxt));
                self.rcv_nxt = end;
            }
            self.out_of_order.remove(0);
        }
        if self.out_of_order.is_empty() {
            self.recent_range_start = None;
        }
        gained
    }

    fn check_response_header(&mut self, payload: &[u8]) -> bool {
        self.header_seen = true;
        if payload.len() < 12 || !payload.starts_with(b"HTTP/1.") {
            self.fail("сервер замера прислал непонятный ответ");
            return false;
        }
        let status = &payload[9..12];
        if status != b"200" {
            let code = String::from_utf8_lossy(status).into_owned();
            self.fail(&format!("сервер замера ответил кодом {code}"));
            return false;
        }
        true
    }

    /// Handles one segment addressed to this connection; returns how many new
    /// bytes of the download it delivered.
    pub fn on_segment(
        &mut self,
        segment: &Segment<'_>,
        now: Instant,
        request: &[u8],
        tx: &mut dyn FnMut(&[u8]),
    ) -> u64 {
        match self.state {
            ConnState::SynSent => {
                if segment.ack != self.iss.wrapping_add(1) {
                    return 0;
                }
                if segment.flags & FLAG_RST != 0 {
                    self.fail("сервер замера отклонил соединение");
                    return 0;
                }
                if segment.flags & (FLAG_SYN | FLAG_ACK) != (FLAG_SYN | FLAG_ACK) {
                    return 0;
                }
                self.rcv_nxt = segment.seq.wrapping_add(1);
                self.sack_permitted = has_sack_permitted(segment.options);
                if self.syn_attempts == 1 {
                    self.rtt = Some(now.saturating_duration_since(self.syn_at));
                }
                self.state = ConnState::Established;
                self.send_request(now, request, tx);
                0
            }
            ConnState::Established => self.on_established_segment(segment, tx),
            ConnState::Finished | ConnState::Failed => {
                self.refuse(segment, tx);
                0
            }
        }
    }

    fn on_established_segment(
        &mut self,
        segment: &Segment<'_>,
        tx: &mut dyn FnMut(&[u8]),
    ) -> u64 {
        if segment.flags & FLAG_RST != 0 {
            if self.header_seen && self.bytes > 0 {
                self.state = ConnState::Finished;
            } else {
                self.fail("сервер замера сбросил соединение");
            }
            return 0;
        }
        if segment.flags & FLAG_SYN != 0 {
            // A repeated SYN-ACK: our ACK was lost on the way.
            self.send_ack(tx);
            return 0;
        }
        if segment.flags & FLAG_ACK != 0 && seq_le(self.snd_nxt, segment.ack) {
            self.request_acked = true;
        }

        let length = segment.payload.len() as u32;
        let start = segment.seq;
        let end = start.wrapping_add(length);
        let mut gained = 0u64;
        if length > 0 {
            if start == self.rcv_nxt {
                if !self.header_seen && !self.check_response_header(segment.payload) {
                    self.send_reset(tx);
                    return 0;
                }
                self.rcv_nxt = end;
                gained += u64::from(length);
                gained += self.absorb_out_of_order();
                self.need_ack = true;
            } else if seq_lt(self.rcv_nxt, start) {
                if start.wrapping_sub(self.rcv_nxt) <= MAX_SEQUENCE_JUMP {
                    self.insert_out_of_order(start, end);
                    // Tell the sender about the hole right away.
                    self.send_ack(tx);
                }
            } else if seq_lt(self.rcv_nxt, end) && self.header_seen {
                gained += u64::from(end.wrapping_sub(self.rcv_nxt));
                self.rcv_nxt = end;
                gained += self.absorb_out_of_order();
                self.need_ack = true;
            } else {
                // Something we already have: acknowledge again.
                self.need_ack = true;
            }
        }
        if segment.flags & FLAG_FIN != 0 && end == self.rcv_nxt {
            self.rcv_nxt = self.rcv_nxt.wrapping_add(1);
            self.state = ConnState::Finished;
            self.send_ack(tx);
            // Close our side too so the server can forget the connection.
            let seq = self.snd_nxt;
            self.emit(seq, FLAG_FIN | FLAG_ACK, &[], &[], tx);
            self.snd_nxt = self.snd_nxt.wrapping_add(1);
        }
        self.bytes += gained;
        gained
    }
}

fn has_sack_permitted(mut options: &[u8]) -> bool {
    while let Some(&kind) = options.first() {
        match kind {
            0 => return false,
            1 => options = &options[1..],
            _ => {
                let Some(&length) = options.get(1) else {
                    return false;
                };
                let length = usize::from(length);
                if length < 2 || length > options.len() {
                    return false;
                }
                if kind == 4 {
                    return true;
                }
                options = &options[length..];
            }
        }
    }
    false
}

/// The HTTP request that starts the download.
pub fn http_request(host: &str, path: &str) -> Vec<u8> {
    format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mayak-SpeedTest\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
    )
    .into_bytes()
}
