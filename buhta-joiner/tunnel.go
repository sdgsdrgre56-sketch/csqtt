package main

import (
	"flag"
	"log"
	"net"
	"os"
	"sort"
	"strings"
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
	tunnelMode = flag.String("tunnel", "", "режим туннеля: server | ping | client (пусто = старый тест из 150 пакетов)")
	pingTarget = flag.String("ping-target", "8.8.8.8", "куда пинговать через туннель (режим ping)")
	pingSize   = flag.Int("ping-size", 900, "размер данных в ping (больше ~990 проверяет фрагментацию)")
	pingCount  = flag.Int("ping-count", 20, "сколько пингов отправить")
	clientIP   = flag.String("client-ip", "10.77.0.2", "адрес клиента внутри туннеля")
	dataDir    = flag.String("data", "/etc/buhta", "папка данных сервера: ключ, клиенты, пароль панели")
	panelAddr  = flag.String("panel", ":8787", "адрес веб-панели (HTTPS); пусто = панель выключена")
)

const rtpPT = 111

type Tunnel struct {
	track *webrtc.TrackLocalStaticRTP
	frag  Fragmenter
	reMu  sync.Mutex
	res   map[uint32]*Reassembler // по одному сборщику на источник (SSRC)

	mu  sync.Mutex
	seq uint16
	ts  uint32

	OnPacket func(pkt []byte)
	// OnDatagram получает собранную датаграмму как есть (защищённый режим).
	// Если не задан, действует старый открытый режим: ждём голый IPv4-пакет в OnPacket.
	OnDatagram func(dg []byte)

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

// reassembler возвращает сборщик фрагментов для данного источника.
func (t *Tunnel) reassembler(ssrc uint32) *Reassembler {
	t.reMu.Lock()
	defer t.reMu.Unlock()
	if t.res == nil {
		t.res = map[uint32]*Reassembler{}
	}
	r := t.res[ssrc]
	if r == nil {
		if len(t.res) >= 64 { // чужих источников не должно быть много
			for k := range t.res {
				delete(t.res, k)
				break
			}
		}
		r = NewReassembler()
		t.res[ssrc] = r
	}
	return r
}

// rx принимает RTP-нагрузку, пришедшую от моста.
func (t *Tunnel) rx(payload []byte, ssrc uint32) {
	if len(payload) < headerLen || (payload[0] != frameData && payload[0] != framePing) {
		return // обычный звук других участников
	}
	pkt, err := t.reassembler(ssrc).Push(payload, time.Now())
	if err != nil || pkt == nil {
		return
	}
	t.mu.Lock()
	t.rxPackets++
	t.mu.Unlock()
	if t.OnDatagram != nil {
		t.OnDatagram(pkt)
		return
	}
	if len(pkt) < 20 || pkt[0]>>4 != 4 { // не IPv4 — случайное совпадение
		return
	}
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
	t.rx(p.Payload, p.SSRC)
}

// tunnelAttach вызывается, когда PeerConnection поднялся. Возвращает true,
// если туннельный режим включён (тогда старый тест не нужен).
func tunnelAttach(track *webrtc.TrackLocalStaticRTP) bool {
	if *tunnelMode == "" {
		return false
	}
	t := &Tunnel{track: track}
	activeTunnel = t
	log.Printf("[tun] режим %q запущен", *tunnelMode)
	switch *tunnelMode {
	case "server":
		go runServer(t)
	case "ping":
		go runPing(t)
	case "client":
		go runClient(t)
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
	tunWrite := func(pkt []byte) {
		wmu.Lock()
		defer wmu.Unlock()
		if _, err := tun.Write(pkt); err != nil {
			log.Printf("[tun] запись в TUN: %v", err)
		}
	}
	sec := earlyServerInit()
	sec.t = t
	sec.tunWrite = tunWrite
	t.OnDatagram = sec.onDatagram
	st := sec.st
	log.Printf("[sec] ключ сервера: %s, клиентов: %d", st.PublicKeyHex(), st.Count())
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
		sec.fromTun(append([]byte(nil), buf[:n]...))
	}
}

func runPing(t *Tunnel) {
	src := net.ParseIP(*clientIP)
	if src == nil {
		log.Printf("[ping] неверный client-ip")
		return
	}
	targets := strings.Split(*pingTarget, ",")
	var mu sync.Mutex
	sent := map[uint16]time.Time{}
	rtts := map[uint16]time.Duration{}
	t.OnPacket = func(pkt []byte) {
		_, _, seq, ok := ParseICMPEchoReply(pkt)
		if !ok {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if st, known := sent[seq]; known && rtts[seq] == 0 {
			rtts[seq] = time.Since(st)
		}
	}
	time.Sleep(2 * time.Second)
	data := make([]byte, *pingSize)
	for i := range data {
		data[i] = byte(i)
	}
	var seq uint16
	total, totalGot := 0, 0
	for _, tg := range targets {
		dst := net.ParseIP(strings.TrimSpace(tg))
		if dst == nil {
			continue
		}
		var mine []uint16
		for i := 0; i < *pingCount; i++ {
			seq++
			mu.Lock()
			sent[seq] = time.Now()
			mu.Unlock()
			mine = append(mine, seq)
			t.SendPacket(BuildICMPEcho(src, dst, 0xB7B7, seq, data))
			time.Sleep(500 * time.Millisecond)
		}
		time.Sleep(1200 * time.Millisecond)
		mu.Lock()
		var got []time.Duration
		for _, q := range mine {
			if d := rtts[q]; d > 0 {
				got = append(got, d)
			}
		}
		mu.Unlock()
		total += len(mine)
		totalGot += len(got)
		if len(got) == 0 {
			log.Printf("[ping] %-16s нет ответов (0/%d)", dst, len(mine))
			continue
		}
		sort.Slice(got, func(i, j int) bool { return got[i] < got[j] })
		log.Printf("[ping] %-16s ответов %d/%d  мин=%v  мед=%v  макс=%v", dst, len(got), len(mine),
			got[0].Round(time.Millisecond), got[len(got)/2].Round(time.Millisecond), got[len(got)-1].Round(time.Millisecond))
	}
	log.Printf("[ping] ИТОГ: отправлено %d, ответов %d", total, totalGot)
}

// tunnelOnState вызывается из обработчика состояния PeerConnection. В туннельном
// режиме при обрыве выходим с ошибкой: серверный скрипт buhta-run.sh сам
// перезапустит процесс и заново зайдёт в комнату (то же и для ICE-сбоя при старте).
func tunnelOnState(pc *webrtc.PeerConnection, s webrtc.PeerConnectionState) {
	if *tunnelMode == "" {
		return
	}
	switch s {
	case webrtc.PeerConnectionStateFailed:
		log.Printf("[tun] соединение упало (failed), выхожу для перезапуска")
		os.Exit(3)
	case webrtc.PeerConnectionStateDisconnected:
		go func() {
			time.Sleep(15 * time.Second)
			if pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
				log.Printf("[tun] соединение не восстановилось за 15с, выхожу для перезапуска")
				os.Exit(3)
			}
		}()
	}
}

var (
	earlyOnce sync.Once
	earlySec  *SecServer
)

// earlyServerInit поднимает хранилище клиентов и веб-панель сразу при старте сервера,
// ещё до того как в комнату зайдёт клиент: мост Saby начинает сессию только при двух
// участниках, а панель и ключ нужны раньше.
func earlyServerInit() *SecServer {
	earlyOnce.Do(func() {
		setupLogRing()
		st, firstPass, err := openStore(*dataDir)
		if err != nil {
			log.Printf("[tun] хранилище %s: %v", *dataDir, err)
			os.Exit(1)
		}
		if u := os.Getenv("BUHTA_PANEL_USER"); u != "" {
			panelUser = u
		}
		if pw := os.Getenv("BUHTA_PANEL_PASS"); pw != "" {
			st.SetPassword(pw)
			firstPass = ""
			log.Printf("[panel] логин %s, пароль задан при установке", panelUser)
		}
		if firstPass != "" {
			log.Printf("[panel] ПЕРВЫЙ ЗАПУСК: логин admin, пароль %s (смени в Настройках)", firstPass)
		}
		earlySec = newSecServer(st, nil, nil)
		if *panelAddr != "" {
			startPanel(*panelAddr, st, earlySec, *dataDir)
		}
	})
	return earlySec
}
