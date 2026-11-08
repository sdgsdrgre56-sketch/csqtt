package main

import (
	"encoding/hex"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// bucket — ограничитель скорости (отбрасывает лишнее, TCP внутри туннеля сам притормозит).
type bucket struct {
	mu     sync.Mutex
	rate   float64 // байт/с, 0 = без ограничения
	burst  float64
	tokens float64
	last   time.Time
}

func (b *bucket) setRate(mbps float64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rate = mbps * 125000
	b.burst = b.rate * 0.25
	if b.burst < 64*1024 {
		b.burst = 64 * 1024
	}
	b.tokens = b.burst
	b.last = time.Now()
}

func (b *bucket) allow(n int) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.rate <= 0 {
		return true
	}
	now := time.Now()
	b.tokens += now.Sub(b.last).Seconds() * b.rate
	if b.tokens > b.burst {
		b.tokens = b.burst
	}
	b.last = now
	if b.tokens < float64(n) {
		return false
	}
	b.tokens -= float64(n)
	return true
}

type liveClient struct {
	cfg      Client
	token    [16]byte
	ip       [4]byte
	sess     atomic.Pointer[secSession]
	bytesIn  uint64 // от клиента (его отправка)
	bytesOut uint64 // к клиенту (его загрузка)
	lastSeen int64  // unix nano
	inLim    bucket
	outLim   bucket
}

type helloEntry struct {
	lc      *liveClient
	welcome []byte
	at      time.Time
}

// SecServer — серверная часть защищённого туннеля: рукопожатия, сессии, лимиты, счётчики.
type SecServer struct {
	st    *Store
	t     *Tunnel
	mu    sync.RWMutex
	byIP  map[[4]byte]*liveClient
	byKid map[uint16]*liveClient
	byTok map[[16]byte]*liveClient
	seen  map[string]*helloEntry

	tunWrite func([]byte)
	start    time.Time
	lastWarn int64
}

func newSecServer(st *Store, t *Tunnel, tunWrite func([]byte)) *SecServer {
	s := &SecServer{st: st, t: t, tunWrite: tunWrite, start: time.Now(), seen: map[string]*helloEntry{}}
	s.rebuild()
	st.onChange = s.rebuild
	return s
}

// rebuild применяет список клиентов из хранилища, не теряя живых сессий и счётчиков.
func (s *SecServer) rebuild() {
	list := s.st.List()
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.byTok
	s.byIP = map[[4]byte]*liveClient{}
	s.byKid = map[uint16]*liveClient{}
	s.byTok = map[[16]byte]*liveClient{}
	for _, c := range list {
		var tok [16]byte
		raw, err := hex.DecodeString(c.Token)
		if err != nil || len(raw) != 16 {
			continue
		}
		copy(tok[:], raw)
		ip := net.ParseIP(c.IP).To4()
		if ip == nil {
			continue
		}
		lc := old[tok]
		if lc == nil {
			lc = &liveClient{token: tok}
		}
		lc.cfg = c
		copy(lc.ip[:], ip)
		lc.inLim.setRate(c.LimitMbps)
		lc.outLim.setRate(c.LimitMbps)
		if !c.Enabled {
			lc.sess.Store(nil)
		}
		s.byTok[tok] = lc
		s.byIP[lc.ip] = lc
		s.byKid[uint16(ip[3])] = lc
	}
}

func (s *SecServer) secureMode() bool { return s.st.Count() > 0 }

func (s *SecServer) warn(format string, a ...any) {
	now := time.Now().Unix()
	if atomic.SwapInt64(&s.lastWarn, now) == now {
		return
	}
	log.Printf(format, a...)
}

// onDatagram принимает собранную датаграмму от моста.
func (s *SecServer) onDatagram(dg []byte) {
	if len(dg) == 0 {
		return
	}
	if !s.secureMode() { // пока клиентов нет — старый открытый режим (один клиент 10.77.0.2)
		if len(dg) >= 20 && dg[0]>>4 == 4 {
			s.tunWrite(dg)
		}
		return
	}
	switch dg[0] {
	case msgHello:
		s.onHello(dg)
	case msgData:
		s.onData(dg)
	}
}

func (s *SecServer) onHello(dg []byte) {
	hi, err := parseHello(s.st.priv, dg, time.Now())
	if err != nil {
		s.warn("[sec] приветствие отклонено: %v", err)
		return
	}
	s.mu.Lock()
	lc := s.byTok[hi.token]
	// чистим старые записи
	for k, e := range s.seen {
		if time.Since(e.at) > 3*time.Minute {
			delete(s.seen, k)
		}
	}
	if lc == nil || !lc.cfg.Enabled {
		s.mu.Unlock()
		s.warn("[sec] неизвестный или выключенный токен")
		return
	}
	if e := s.seen[string(hi.epk)]; e != nil && e.lc == lc {
		w := e.welcome
		s.mu.Unlock()
		s.t.SendPacket(w) // повтор приветствия: просто отправляем тот же ответ
		return
	}
	sess, welcome, err := acceptHello(hi, uint16(lc.ip[3]), lc.ip)
	if err != nil {
		s.mu.Unlock()
		s.warn("[sec] рукопожатие: %v", err)
		return
	}
	lc.sess.Store(sess)
	atomic.StoreInt64(&lc.lastSeen, time.Now().UnixNano())
	s.seen[string(hi.epk)] = &helloEntry{lc: lc, welcome: welcome, at: time.Now()}
	name := lc.cfg.Name
	s.mu.Unlock()
	log.Printf("[sec] клиент %q подключился (%s)", name, net.IP(lc.ip[:]))
	s.t.SendPacket(welcome)
}

func (s *SecServer) onData(dg []byte) {
	kid, ok := dgKid(dg)
	if !ok {
		return
	}
	s.mu.RLock()
	lc := s.byKid[kid]
	s.mu.RUnlock()
	if lc == nil {
		return
	}
	sess := lc.sess.Load()
	if sess == nil {
		return
	}
	pkt, err := sess.open(dg)
	if err != nil || len(pkt) < 20 || pkt[0]>>4 != 4 {
		return
	}
	var src [4]byte
	copy(src[:], pkt[12:16])
	if src != lc.ip { // клиент не может подделывать чужой адрес
		return
	}
	atomic.StoreInt64(&lc.lastSeen, time.Now().UnixNano())
	if !lc.inLim.allow(len(pkt)) {
		return
	}
	atomic.AddUint64(&lc.bytesIn, uint64(len(pkt)))
	s.tunWrite(pkt)
}

// fromTun обрабатывает пакет, прочитанный из TUN (ответ из интернета).
func (s *SecServer) fromTun(pkt []byte) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return
	}
	if !s.secureMode() {
		s.t.SendPacket(pkt)
		return
	}
	var dst [4]byte
	copy(dst[:], pkt[16:20])
	s.mu.RLock()
	lc := s.byIP[dst]
	s.mu.RUnlock()
	if lc == nil {
		return
	}
	sess := lc.sess.Load()
	if sess == nil {
		return
	}
	if !lc.outLim.allow(len(pkt)) {
		return
	}
	atomic.AddUint64(&lc.bytesOut, uint64(len(pkt)))
	k := flowKey(pkt)
	s.t.SendPacketFlow(sess.seal(pkt), k)
}

type clientStat struct {
	Client
	Online   bool   `json:"online"`
	BytesIn  uint64 `json:"bytes_in"`
	BytesOut uint64 `json:"bytes_out"`
	LastSeen int64  `json:"last_seen"` // unix секунды, 0 = не был
}

func (s *SecServer) stats() []clientStat {
	list := s.st.List()
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]clientStat, 0, len(list))
	for _, c := range list {
		cs := clientStat{Client: c}
		var tok [16]byte
		if raw, err := hex.DecodeString(c.Token); err == nil && len(raw) == 16 {
			copy(tok[:], raw)
			if lc := s.byTok[tok]; lc != nil {
				ls := atomic.LoadInt64(&lc.lastSeen)
				cs.BytesIn = atomic.LoadUint64(&lc.bytesIn)
				cs.BytesOut = atomic.LoadUint64(&lc.bytesOut)
				if ls > 0 {
					cs.LastSeen = ls / 1e9
					cs.Online = c.Enabled && time.Since(time.Unix(0, ls)) < 30*time.Second
				}
			}
		}
		out = append(out, cs)
	}
	return out
}
