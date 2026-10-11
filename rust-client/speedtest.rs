// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a download speed test that runs inside the tunnel.
//
// The app cannot open a socket through its own VPN, so on the SPEEDTEST
// control command this module downloads a large file over hand-made TCP
// connections (see speedtest_tcp.rs). Their packets enter the tunnel the same
// way packets read from the TUN device do, and the answers are taken out of
// the return path before they would be written to the TUN device.

use crate::{
    dispatcher::Dispatcher,
    dns,
    events::Events,
    packet::{PacketBuf, PacketPool},
    speedtest_tcp::{self, Conn, ConnState},
    stats::Stats,
};
use std::{
    net::{IpAddr, Ipv4Addr},
    sync::{
        Arc, OnceLock,
        atomic::{AtomicBool, AtomicU32, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

const REMOTE_PORT: u16 = 80;
const CONNECTIONS: u16 = 4;
const TUNNEL_MSS: u16 = 1260;
const INBOUND_CAPACITY: usize = 1024;
const OUTBOUND_CAPACITY: usize = 512;
const TICK: Duration = Duration::from_millis(250);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(7);
const MEASURE_TIME: Duration = Duration::from_secs(10);
const WARM_UP: Duration = Duration::from_secs(2);
const STALL_TIMEOUT: Duration = Duration::from_secs(5);
const DNS_TIMEOUT: Duration = Duration::from_secs(4);

struct Target {
    host: &'static str,
    path: &'static str,
    fallback: Option<Ipv4Addr>,
}

// Plain-HTTP files of at least 1 GB. The first that answers is used.
const TARGETS: [Target; 3] = [
    Target {
        host: "speedtest.tele2.net",
        path: "/1GB.zip",
        fallback: Some(Ipv4Addr::new(90, 130, 70, 73)),
    },
    Target {
        host: "hel1-speed.hetzner.com",
        path: "/1GB.bin",
        fallback: None,
    },
    Target {
        host: "proof.ovh.net",
        path: "/files/1Gb.dat",
        fallback: None,
    },
];

/// The part of the speed test that lives inside the dispatcher.
pub struct SpeedHook {
    active: AtomicBool,
    remote_ip: AtomicU32,
    port_base: AtomicU32,
    inbound_tx: mpsc::Sender<PacketBuf>,
    inbound_rx: tokio::sync::Mutex<mpsc::Receiver<PacketBuf>>,
    outbound_tx: mpsc::Sender<PacketBuf>,
    outbound_rx: tokio::sync::Mutex<mpsc::Receiver<PacketBuf>>,
}

impl SpeedHook {
    pub fn new() -> Self {
        let (inbound_tx, inbound_rx) = mpsc::channel(INBOUND_CAPACITY);
        let (outbound_tx, outbound_rx) = mpsc::channel(OUTBOUND_CAPACITY);
        Self {
            active: AtomicBool::new(false),
            remote_ip: AtomicU32::new(0),
            port_base: AtomicU32::new(0),
            inbound_tx,
            inbound_rx: tokio::sync::Mutex::new(inbound_rx),
            outbound_tx,
            outbound_rx: tokio::sync::Mutex::new(outbound_rx),
        }
    }

    /// Packets made by the speed test, to be sent like packets read from the TUN device.
    pub fn outbound(&self) -> &tokio::sync::Mutex<mpsc::Receiver<PacketBuf>> {
        &self.outbound_rx
    }

    /// Takes a returning packet that belongs to a running speed test.
    /// Everything else is handed back untouched.
    #[inline(always)]
    pub fn divert(&self, packet: PacketBuf) -> Option<PacketBuf> {
        if !self.active.load(Ordering::Relaxed) {
            return Some(packet);
        }
        self.divert_active(packet)
    }

    #[cold]
    fn divert_active(&self, packet: PacketBuf) -> Option<PacketBuf> {
        let Some((source_ip, source_port, destination_port)) =
            speedtest_tcp::peek_ipv4_tcp(packet.as_slice())
        else {
            return Some(packet);
        };
        let port_base = self.port_base.load(Ordering::Relaxed);
        let destination_port = u32::from(destination_port);
        if source_port != REMOTE_PORT
            || source_ip != self.remote_ip.load(Ordering::Relaxed)
            || destination_port < port_base
            || destination_port >= port_base + u32::from(CONNECTIONS)
        {
            return Some(packet);
        }
        // A full queue drops the packet; the sender simply sends it again.
        let _ = self.inbound_tx.try_send(packet);
        None
    }
}

impl Default for SpeedHook {
    fn default() -> Self {
        Self::new()
    }
}

struct Runtime {
    dispatcher: Arc<Dispatcher>,
    pool: Arc<PacketPool>,
    stats: Arc<Stats>,
    events: Events,
    cancel: CancellationToken,
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static TUNNEL_IP: AtomicU32 = AtomicU32::new(0);
static RUNNING: AtomicBool = AtomicBool::new(false);

/// Makes the speed test available; called once the dispatcher exists.
pub fn install(
    dispatcher: Arc<Dispatcher>,
    pool: Arc<PacketPool>,
    stats: Arc<Stats>,
    events: Events,
    cancel: CancellationToken,
) {
    let _ = RUNTIME.set(Runtime {
        dispatcher,
        pool,
        stats,
        events,
        cancel,
    });
}

/// Remembers the address this client has inside the tunnel.
pub fn set_tunnel_ip(address: &str) {
    if let Ok(address) = address.trim().parse::<Ipv4Addr>() {
        TUNNEL_IP.store(u32::from(address), Ordering::Release);
    }
}

/// Starts a speed test unless one is already running.
pub fn request() {
    let Some(runtime) = RUNTIME.get() else {
        return;
    };
    if RUNNING.swap(true, Ordering::AcqRel) {
        return;
    }
    tokio::spawn(async move {
        runtime
            .events
            .speed_test(&serde_json::json!({"state": "starting"}));
        match run(runtime).await {
            Ok(summary) => {
                crate::log_error!(
                    "[ЗАМЕР] Скорость: {:.1} Мбит/с · максимум {:.1} · отклик {} мс · сервер {}",
                    summary.mbps,
                    summary.peak_mbps,
                    summary.ping_ms,
                    summary.host
                );
                runtime.events.speed_test(&serde_json::json!({
                    "state": "done",
                    "mbps": summary.mbps,
                    "peak": summary.peak_mbps,
                    "ping": summary.ping_ms,
                    "bytes": summary.bytes,
                    "server": summary.host,
                }));
            }
            Err(message) => {
                crate::log_error!("[ЗАМЕР] Не удалось измерить скорость: {message}");
                runtime
                    .events
                    .speed_test(&serde_json::json!({"state": "error", "message": message}));
            }
        }
        RUNNING.store(false, Ordering::Release);
    });
}

struct Summary {
    mbps: f64,
    peak_mbps: f64,
    ping_ms: u64,
    bytes: u64,
    host: &'static str,
}

struct ActiveGuard<'a>(&'a SpeedHook);

impl Drop for ActiveGuard<'_> {
    fn drop(&mut self) {
        self.0.active.store(false, Ordering::Release);
    }
}

async fn resolve(target: &Target) -> Option<Ipv4Addr> {
    let resolver = dns::global();
    let found = match tokio::time::timeout(DNS_TIMEOUT, resolver.lookup(target.host)).await {
        Ok(Ok(addresses)) => addresses.into_iter().find_map(|address| match address {
            IpAddr::V4(address) => Some(address),
            IpAddr::V6(_) => None,
        }),
        _ => None,
    };
    found.or(target.fallback)
}

async fn run(runtime: &'static Runtime) -> Result<Summary, String> {
    let local_ip = TUNNEL_IP.load(Ordering::Acquire);
    if local_ip == 0 {
        return Err("туннель ещё не готов".to_owned());
    }
    if runtime.stats.active_connections.load(Ordering::Relaxed) <= 0 {
        return Err("нет активных потоков".to_owned());
    }
    let hook = runtime.dispatcher.speed();
    let Ok(mut inbound) = hook.inbound_rx.try_lock() else {
        return Err("замер уже идёт".to_owned());
    };
    let mut last_error = "сервер замера недоступен".to_owned();
    for target in &TARGETS {
        if runtime.cancel.is_cancelled() {
            return Err("туннель остановлен".to_owned());
        }
        let Some(remote) = resolve(target).await else {
            last_error = format!("не удалось узнать адрес {}", target.host);
            continue;
        };
        while inbound.try_recv().is_ok() {}
        match run_target(runtime, hook, &mut inbound, local_ip, remote, target).await {
            Ok(summary) => return Ok(summary),
            Err(message) => last_error = message,
        }
    }
    Err(last_error)
}

fn megabits(bytes: u64, time: Duration) -> f64 {
    let seconds = time.as_secs_f64();
    if seconds <= 0.0 {
        return 0.0;
    }
    bytes as f64 * 8.0 / seconds / 1_000_000.0
}

async fn run_target(
    runtime: &'static Runtime,
    hook: &SpeedHook,
    inbound: &mut mpsc::Receiver<PacketBuf>,
    local_ip: u32,
    remote: Ipv4Addr,
    target: &Target,
) -> Result<Summary, String> {
    let remote_ip = u32::from(remote);
    let request = speedtest_tcp::http_request(target.host, target.path);
    let seed = rand::random::<u64>();
    // Local ports 20000..59999, a block of CONNECTIONS in a row.
    let port_base = 20_000 + ((seed >> 32) as u32 % 40_000) / u32::from(CONNECTIONS)
        * u32::from(CONNECTIONS);

    hook.remote_ip.store(remote_ip, Ordering::Release);
    hook.port_base.store(port_base, Ordering::Release);
    hook.active.store(true, Ordering::Release);
    let _active = ActiveGuard(hook);

    let pool = &runtime.pool;
    let outbound = &hook.outbound_tx;
    let mut send = |bytes: &[u8]| {
        let Some(mut packet) = pool.try_acquire() else {
            return;
        };
        let area = packet.read_area();
        if bytes.len() > area.len() {
            return;
        }
        area[..bytes.len()].copy_from_slice(bytes);
        if packet.set_read_len(bytes.len()).is_ok() {
            let _ = outbound.try_send(packet);
        }
    };

    let started = Instant::now();
    let mut connections: Vec<Conn> = (0..CONNECTIONS)
        .map(|index| {
            let iss = (seed as u32).wrapping_add(u32::from(index).wrapping_mul(0x9e37_79b9));
            Conn::new(
                local_ip,
                remote_ip,
                (port_base + u32::from(index)) as u16,
                REMOTE_PORT,
                iss,
                TUNNEL_MSS,
                started,
            )
        })
        .collect();
    for connection in &mut connections {
        connection.start(started, &mut send);
    }

    let mut ticker = tokio::time::interval(TICK);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut total: u64 = 0;
    let mut first_data: Option<Instant> = None;
    let mut last_data = started;
    // Running totals, one per tick, taken after the first byte arrived.
    let mut samples: Vec<(Instant, u64)> = Vec::with_capacity(64);
    let mut peak_mbps: f64 = 0.0;
    let mut ticks: u32 = 0;

    let outcome: Result<(), String> = loop {
        tokio::select! {
            _ = runtime.cancel.cancelled() => break Err("туннель остановлен".to_owned()),
            received = inbound.recv() => {
                let Some(first) = received else {
                    break Err("замер прерван".to_owned());
                };
                let now = Instant::now();
                let mut next = Some(first);
                let mut handled = 0usize;
                while let Some(packet) = next.take() {
                    runtime
                        .stats
                        .total_bytes_down
                        .fetch_add(packet.len() as i64, Ordering::Relaxed);
                    if let Some(segment) = speedtest_tcp::parse_ipv4_tcp(packet.as_slice())
                        && let Some(connection) = connections
                            .iter_mut()
                            .find(|connection| connection.local_port() == segment.destination_port)
                    {
                        let gained = connection.on_segment(&segment, now, &request, &mut send);
                        if gained > 0 {
                            total += gained;
                            last_data = now;
                            if first_data.is_none() {
                                first_data = Some(now);
                                samples.push((now, 0));
                            }
                        }
                    }
                    drop(packet);
                    handled += 1;
                    if handled < 64 {
                        next = inbound.try_recv().ok();
                    }
                }
                for connection in &mut connections {
                    connection.flush_ack(&mut send);
                }
            }
            _ = ticker.tick() => {
                let now = Instant::now();
                ticks = ticks.wrapping_add(1);
                for connection in &mut connections {
                    connection.on_tick(now, &request, &mut send);
                }
                match first_data {
                    None => {
                        if connections.iter().all(|connection| !connection.is_open())
                            || now.saturating_duration_since(started) >= CONNECT_TIMEOUT
                        {
                            let reason = connections
                                .iter()
                                .find_map(|connection| connection.failure().map(str::to_owned))
                                .unwrap_or_else(|| "сервер замера не отвечает".to_owned());
                            break Err(reason);
                        }
                    }
                    Some(first) => {
                        samples.push((now, total));
                        let count = samples.len();
                        // Speed over the last second, for the peak and the live figure.
                        let window_start = samples[count.saturating_sub(5)];
                        let window_time = now.saturating_duration_since(window_start.0);
                        let current = megabits(total - window_start.1, window_time);
                        if window_time >= Duration::from_millis(900) && current > peak_mbps {
                            peak_mbps = current;
                        }
                        let elapsed = now.saturating_duration_since(first);
                        if ticks % 2 == 0 {
                            let percent = (elapsed.as_millis() * 100 / MEASURE_TIME.as_millis()).min(100) as u64;
                            runtime.events.speed_test(&serde_json::json!({
                                "state": "running",
                                "mbps": current,
                                "percent": percent,
                            }));
                        }
                        if elapsed >= MEASURE_TIME
                            || connections.iter().all(|connection| !connection.is_open())
                            || now.saturating_duration_since(last_data) >= STALL_TIMEOUT
                        {
                            break Ok(());
                        }
                    }
                }
            }
        }
    };

    // Tell the server to stop, and keep refusing what is still on its way for a moment.
    for connection in &mut connections {
        connection.abort(&mut send);
    }
    let linger_until = tokio::time::Instant::now() + Duration::from_millis(400);
    loop {
        tokio::select! {
            _ = tokio::time::sleep_until(linger_until) => break,
            received = inbound.recv() => {
                let Some(packet) = received else {
                    break;
                };
                if let Some(segment) = speedtest_tcp::parse_ipv4_tcp(packet.as_slice())
                    && let Some(connection) = connections
                        .iter_mut()
                        .find(|connection| connection.local_port() == segment.destination_port)
                {
                    connection.refuse(&segment, &mut send);
                }
            }
        }
    }

    outcome?;
    let Some(first) = first_data else {
        return Err("сервер замера не отвечает".to_owned());
    };
    let Some(&(end_time, end_total)) = samples.last() else {
        return Err("замер не дал данных".to_owned());
    };
    // The average leaves out the first seconds, while the connections speed up.
    let warm = samples
        .iter()
        .copied()
        .find(|sample| sample.0.saturating_duration_since(first) >= WARM_UP)
        .filter(|sample| end_time.saturating_duration_since(sample.0) >= Duration::from_secs(2))
        .unwrap_or((first, 0));
    let mbps = megabits(end_total - warm.1, end_time.saturating_duration_since(warm.0));
    if end_total == 0 || mbps <= 0.0 {
        return Err("данные не дошли".to_owned());
    }
    let ping_ms = connections
        .iter()
        .filter_map(|connection| connection.rtt())
        .min()
        .map_or(0, |rtt| rtt.as_millis() as u64);
    let finished = connections
        .iter()
        .filter(|connection| connection.state() == ConnState::Finished)
        .count();
    crate::log_error!(
        "[ЗАМЕР] Получено {:.1} МБ за {:.1} с, соединений: {finished}",
        end_total as f64 / 1_048_576.0,
        end_time.saturating_duration_since(first).as_secs_f64()
    );
    Ok(Summary {
        mbps,
        peak_mbps: peak_mbps.max(mbps),
        ping_ms,
        bytes: end_total,
        host: target.host,
    })
}
