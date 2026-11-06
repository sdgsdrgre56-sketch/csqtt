package main

import (
	"flag"
	"log"
	"net"
	"os"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

// Режимы туннеля. Флаги регистрируются здесь, main.go их не знает:
//
//	-tunnel server   сервер: TUN buhta0 <-> RTP (нужен server-setup.sh)
//	-tunnel ping     клиент-проверка: шлёт ICMP echo через туннель и пишет ответы
var (
	tunnelMode = flag.String("tunnel", "", "режим туннеля: server | ping (пусто = старый тест из 150 пакетов)")
	pingTarget = flag.String("ping-target", "8.8.8.8", "куда пинговать через туннель (режим ping)")
	pingSize   = flag.Int("ping-size", 900, "размер данных в ping (больше ~990 проверяет фрагментацию)")
	pingCount  = flag.Int("ping-count", 20, "сколько пингов отправить")
	clientIP   = flag.String("client-ip", "10.77.0.2", "адрес клиента внутри туннеля")
)

const rtpPT = 111

type Tunnel struct {
	track *webrtc.TrackLocalStaticRTP
	frag  Fragmenter
	re    *Reassembler

	mu  sync.Mutex
	seq uint16
	ts  uint32

	OnPacket func(pkt []byte)

	rxPackets, txPackets uint64
}

var activeTunnel *Tunnel

func (t *Tunnel) writeFrame(fr []byte) error {
	t.mu.Lock()
	t.seq++
	t.ts += 960
	h := rtp.Header{Version: 2, PayloadType: rtpPT, SequenceNumber: t.seq, Timestamp: t.ts}
	t.mu.Unlock()
	return t.track.WriteRTP(&rtp.Packet{Header: h, Payload: fr})
}

// SendPacket отправляет один IP-пакет (режет на кадры).
func (t *Tunnel) SendPacket(pkt []byte) {
	for _, fr := range t.frag.Split(pkt) {
		if err := t.writeFrame(fr); err != nil {
			log.Printf("[tun] WriteRTP: %v", err)
			return
		}
	}
	t.mu.Lock()
	t.txPackets++
	t.mu.Unlock()
}

// rx принимает RTP-нагрузку, пришедшую от моста.
func (t *Tunnel) rx(payload []byte) {
	if len(payload) < headerLen || (payload[0] != frameData && payload[0] != framePing) {
		return // обычный звук других участников
	}
	pkt, err := t.re.Push(payload, time.Now())
	if err != nil || pkt == nil {
		return
	}
	if len(pkt) < 20 || pkt[0]>>4 != 4 { // не IPv4 — случайное совпадение
		return
	}
	t.mu.Lock()
	t.rxPackets++
	t.mu.Unlock()
	if t.OnPacket != nil {
		t.OnPacket(pkt)
	}
}

// tunnelRX вызывается из OnTrack для каждого принятого RTP-пакета.
func tunnelRX(raw []byte) {
	t := activeTunnel
	if t == nil {
		return
	}
	var p rtp.Packet
	if err := p.Unmarshal(raw); err != nil {
		return
	}
	t.rx(p.Payload)
}

// tunnelAttach вызывается, когда PeerConnection поднялся. Возвращает true,
// если туннельный режим включён (тогда старый тест не нужен).
func tunnelAttach(track *webrtc.TrackLocalStaticRTP) bool {
	if *tunnelMode == "" {
		return false
	}
	t := &Tunnel{track: track, re: NewReassembler()}
	activeTunnel = t
	log.Printf("[tun] режим %q запущен", *tunnelMode)
	switch *tunnelMode {
	case "server":
		go runServer(t)
	case "ping":
		go runPing(t)
	default:
		log.Printf("[tun] неизвестный режим %q", *tunnelMode)
	}
	go func() { // keepalive, чтобы мост не считал источник мёртвым
		for {
			time.Sleep(5 * time.Second)
			if err := t.writeFrame(PingFrame()); err != nil {
				return
			}
		}
	}()
	return true
}

func runServer(t *Tunnel) {
	tun, err := OpenTUN("buhta0")
	if err != nil {
		log.Printf("[tun] ОШИБКА открытия TUN: %v (запусти server-setup.sh от root)", err)
		os.Exit(1)
	}
	log.Printf("[tun] TUN buhta0 открыт")
	var wmu sync.Mutex
	t.OnPacket = func(pkt []byte) {
		wmu.Lock()
		defer wmu.Unlock()
		if _, err := tun.Write(pkt); err != nil {
			log.Printf("[tun] запись в TUN: %v", err)
		}
	}
	go func() {
		for {
			time.Sleep(10 * time.Second)
			t.mu.Lock()
			log.Printf("[tun] stats: from-client %d pkts, to-client %d pkts", t.rxPackets, t.txPackets)
			t.mu.Unlock()
		}
	}()
	buf := make([]byte, 4096)
	for {
		n, err := tun.Read(buf)
		if err != nil {
			log.Printf("[tun] чтение TUN: %v", err)
			return
		}
		t.SendPacket(append([]byte(nil), buf[:n]...))
	}
}

func runPing(t *Tunnel) {
	src := net.ParseIP(*clientIP)
	dst := net.ParseIP(*pingTarget)
	if src == nil || dst == nil {
		log.Printf("[ping] неверный адрес")
		return
	}
	sent := map[uint16]time.Time{}
	var mu sync.Mutex
	got := 0
	t.OnPacket = func(pkt []byte) {
		from, _, seq, ok := ParseICMPEchoReply(pkt)
		if !ok {
			return
		}
		mu.Lock()
		st, known := sent[seq]
		got++
		mu.Unlock()
		if known {
			log.Printf("[ping] ответ от %s seq=%d время=%v размер=%d", from, seq, time.Since(st).Round(time.Millisecond), len(pkt))
		}
	}
	time.Sleep(2 * time.Second)
	data := make([]byte, *pingSize)
	for i := range data {
		data[i] = byte(i)
	}
	for i := 1; i <= *pingCount; i++ {
		mu.Lock()
		sent[uint16(i)] = time.Now()
		mu.Unlock()
		t.SendPacket(BuildICMPEcho(src, dst, 0xB7B7, uint16(i), data))
		time.Sleep(700 * time.Millisecond)
	}
	time.Sleep(3 * time.Second)
	mu.Lock()
	log.Printf("[ping] ИТОГ: отправлено %d, ответов %d", *pingCount, got)
	mu.Unlock()
}
