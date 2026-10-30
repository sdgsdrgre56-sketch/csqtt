package core

// Сессии поверх облачного ящика.
//
//   <папка>/h/<sid>             — клиент объявляет новую сессию (зашифровано общим ключом)
//   <папка>/s/<sid>/c/<номер>   — клиент → сервер
//   <папка>/s/<sid>/e/<номер>   — сервер → клиент
//
// Каждое подключение (телефон, ПК, человек по ссылке) — своя сессия со своим sid,
// поэтому много устройств работают через одну папку и не мешают друг другу.

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// LogHook — куда писать журнал (по умолчанию stderr).
var LogHook = func(s string) { log.Print(s) }

func logf(f string, a ...any) { LogHook(fmt.Sprintf(f, a...)) }

var (
	pingEvery   = 15 * time.Second
	clientDead  = 45 * time.Second  // нет ответа сервера столько — новая сессия
	serverDead  = 150 * time.Second // нет ничего от клиента — сессия закрыта
	helloMaxAge = 2 * time.Minute
	helloPoll   = time.Second
	cloudDead   = 3 * time.Minute // облако недоступно столько подряд — сервер выходит, systemd перезапустит
)

func newSID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// ---------- клиент ----------

type Stats struct {
	State   string `json:"state"` // connecting | connected | error
	RTTms   int64  `json:"rtt"`
	Rx      int64  `json:"rx"`
	Tx      int64  `json:"tx"`
	Streams int    `json:"streams"`
	Err     string `json:"err,omitempty"`
}

type Client struct {
	box BoxCfg
	s3  *s3Client
	psk []byte

	mu    sync.Mutex
	cur   *csession
	ready chan struct{}

	rxBase, txBase atomic.Int64
	connected      atomic.Bool
	lastErr        atomic.Value
}

type csession struct {
	sid string
	p   *pipe
	m   *mux
}

func NewClient(l Link) (*Client, error) {
	box, err := ParseBox(l.Box)
	if err != nil {
		return nil, err
	}
	return &Client{box: box, s3: newS3(box), psk: l.psk(), ready: make(chan struct{})}, nil
}

func (c *Client) Run(ctx context.Context) {
	backoff := time.Second
	for ctx.Err() == nil {
		t0 := time.Now()
		c.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if time.Since(t0) > time.Minute {
			backoff = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 10*time.Second {
			backoff *= 2
		}
	}
}

func (c *Client) session(parent context.Context) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	sid := newSID()
	base := c.box.Prefix + "/s/" + sid + "/"
	m := newMux(true)
	var fatal atomic.Value
	p := newPipe(ctx, c.s3, base+"c/", base+"e/", newSealer(c.psk, sid, "c"), newSealer(c.psk, sid, "e"), m.handle,
		func(err error) { fatal.Store(err.Error()); cancel() })
	m.p = p
	m.onBye = cancel
	logf("новая сессия %s", sid)

	// объявляем сессию (с повторами, пока облако не примет)
	delay := 300 * time.Millisecond
	for {
		err := c.s3.put(ctx, c.box.Prefix+"/h/"+sid, sealHello(c.psk, sid, time.Now()))
		if err == nil {
			break
		}
		if ctx.Err() != nil {
			p.close()
			return
		}
		c.lastErr.Store(err.Error())
		logf("облако не отвечает: %v", err)
		select {
		case <-ctx.Done():
			p.close()
			return
		case <-time.After(delay):
		}
		if delay < 5*time.Second {
			delay *= 2
		}
	}
	c.lastErr.Store("")

	cs := &csession{sid: sid, p: p, m: m}
	c.mu.Lock()
	c.cur = cs
	close(c.ready)
	c.mu.Unlock()

	ping := func() {
		p.send(frame(fPing, 0, binary.BigEndian.AppendUint64(nil, uint64(time.Now().UnixNano()))), true)
	}
	ping()
	t := time.NewTicker(pingEvery)
	started := time.Now()
	for ctx.Err() == nil {
		select {
		case <-ctx.Done():
		case <-t.C:
			ping()
			heard := time.Unix(0, m.alive.Load())
			c.connected.Store(m.rtt.Load() > 0 && time.Since(heard) < clientDead)
			if time.Since(heard) > clientDead && time.Since(started) > clientDead {
				logf("сервер молчит %v — переподключаюсь", time.Since(heard).Round(time.Second))
				cancel()
			}
		}
	}
	t.Stop()
	if e, _ := fatal.Load().(string); e != "" {
		logf("сессия оборвана: %s", e)
	}
	c.mu.Lock()
	c.cur = nil
	c.ready = make(chan struct{})
	c.mu.Unlock()
	c.connected.Store(false)
	p.send(frame(fBye, 0, nil), true)
	m.shutdown()
	p.close()
	c.rxBase.Add(p.rxBytes.Load())
	c.txBase.Add(p.txBytes.Load())
	go cleanupPrefix(c.s3, base)
}

// Handle проводит соединение приложения к addr (host:port) через текущую сессию.
func (c *Client) Handle(conn net.Conn, addr string) {
	deadline := time.After(20 * time.Second)
	for {
		c.mu.Lock()
		cs, ready := c.cur, c.ready
		c.mu.Unlock()
		if cs != nil {
			cs.m.open(conn, addr)
			return
		}
		select {
		case <-ready:
		case <-deadline:
			conn.Close()
			return
		}
	}
}

func (c *Client) Stats() Stats {
	st := Stats{State: "connecting", Rx: c.rxBase.Load(), Tx: c.txBase.Load()}
	c.mu.Lock()
	cs := c.cur
	c.mu.Unlock()
	if cs != nil {
		st.Rx += cs.p.rxBytes.Load()
		st.Tx += cs.p.txBytes.Load()
		st.RTTms = cs.m.rtt.Load() / 1e6
		st.Streams = cs.m.count()
	}
	if c.connected.Load() {
		st.State = "connected"
	}
	if e, _ := c.lastErr.Load().(string); e != "" {
		st.State, st.Err = "error", e
	}
	return st
}

func cleanupPrefix(s3 *s3Client, prefix string) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	items, err := s3.list(ctx, prefix, 5000)
	if err != nil {
		return
	}
	sem := make(chan struct{}, 16)
	var wg sync.WaitGroup
	for _, it := range items {
		sem <- struct{}{}
		wg.Add(1)
		go func(k string) { defer wg.Done(); s3.del(ctx, k); <-sem }(it.Key)
	}
	wg.Wait()
}

// ---------- сервер ----------

type Server struct {
	box  BoxCfg
	s3   *s3Client
	psk  []byte
	Dial func(addr string) (net.Conn, error)

	mu       sync.Mutex
	sessions map[string]*ssession
}

type ssession struct {
	p      *pipe
	m      *mux
	cancel context.CancelFunc
}

func NewServer(l Link) (*Server, error) {
	box, err := ParseBox(l.Box)
	if err != nil {
		return nil, err
	}
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return &Server{box: box, s3: newS3(box), psk: l.psk(), sessions: map[string]*ssession{},
		Dial: func(addr string) (net.Conn, error) {
			c, err := d.Dial("tcp", addr)
			if tc, ok := c.(*net.TCPConn); ok {
				tc.SetNoDelay(true)
			}
			return c, err
		}}, nil
}

// Check проверяет доступ к облаку (запись, чтение, удаление).
func (s *Server) Check(ctx context.Context) error {
	k := s.box.Prefix + "/check-" + newSID()
	if err := s.s3.put(ctx, k, []byte("ok")); err != nil {
		return fmt.Errorf("запись в облако: %w", err)
	}
	if _, err := s.s3.get(ctx, k); err != nil {
		return fmt.Errorf("чтение из облака: %w", err)
	}
	return s.s3.del(ctx, k)
}

func (s *Server) Run(ctx context.Context) error {
	go s.janitor(ctx)
	seen := map[string]time.Time{}
	failing := false
	lastOK := time.Now()
	for ctx.Err() == nil {
		items, err := s.s3.list(ctx, s.box.Prefix+"/h/", 1000)
		if err != nil {
			if !failing {
				logf("облако: %v", err)
			}
			failing = true
			// зависшие соединения не переиспользуем
			s.s3.hc.CloseIdleConnections()
			if time.Since(lastOK) > cloudDead {
				return fmt.Errorf("облако недоступно %v подряд: %v", time.Since(lastOK).Round(time.Second), err)
			}
			sleepCtx(ctx, 2*time.Second)
			continue
		}
		lastOK = time.Now()
		if failing {
			logf("облако снова отвечает")
			failing = false
		}
		for _, it := range items {
			sid := it.Key[strings.LastIndexByte(it.Key, '/')+1:]
			if _, ok := seen[it.Key]; ok {
				continue
			}
			seen[it.Key] = time.Now()
			go s.accept(ctx, it.Key, sid, it.Age)
		}
		for k, t := range seen {
			if time.Since(t) > 10*time.Minute {
				delete(seen, k)
			}
		}
		sleepCtx(ctx, helloPoll)
	}
	return nil
}

func (s *Server) accept(ctx context.Context, key, sid string, age time.Duration) {
	if age > helloMaxAge || len(sid) != 16 {
		s.s3.del(context.Background(), key)
		return
	}
	b, err := s.s3.get(ctx, key)
	s.s3.del(context.Background(), key)
	if err != nil {
		return
	}
	t, err := openHello(s.psk, sid, b)
	if err != nil {
		logf("чужая сессия %s: %v", sid, err)
		return
	}
	if d := time.Since(t); d > 10*time.Minute || d < -10*time.Minute {
		logf("сессия %s: часы клиента сбиты на %v — отклонена", sid, d.Round(time.Second))
		return
	}
	s.mu.Lock()
	if s.sessions[sid] != nil {
		s.mu.Unlock()
		return
	}
	sctx, cancel := context.WithCancel(ctx)
	base := s.box.Prefix + "/s/" + sid + "/"
	m := newMux(false)
	m.dial = s.Dial
	p := newPipe(sctx, s.s3, base+"e/", base+"c/", newSealer(s.psk, sid, "e"), newSealer(s.psk, sid, "c"), m.handle,
		func(err error) { logf("сессия %s: %v", sid, err); cancel() })
	m.p = p
	m.onBye = cancel
	ss := &ssession{p: p, m: m, cancel: cancel}
	s.sessions[sid] = ss
	n := len(s.sessions)
	s.mu.Unlock()
	logf("сессия %s открыта (всего %d)", sid, n)

	tk := time.NewTicker(5 * time.Second)
	for sctx.Err() == nil {
		select {
		case <-sctx.Done():
		case <-tk.C:
			if time.Since(time.Unix(0, m.alive.Load())) > serverDead {
				cancel()
			}
		}
	}
	tk.Stop()
	m.shutdown()
	p.close()
	s.mu.Lock()
	delete(s.sessions, sid)
	n = len(s.sessions)
	s.mu.Unlock()
	logf("сессия %s закрыта (принято %d МБ, отдано %d МБ; осталось %d)", sid, p.rxBytes.Load()>>20, p.txBytes.Load()>>20, n)
	cleanupPrefix(s.s3, base)
}

// janitor убирает хвосты оборванных сессий.
func (s *Server) janitor(ctx context.Context) {
	for sleepCtx(ctx, 5*time.Minute) {
		items, err := s.s3.list(ctx, s.box.Prefix+"/s/", 20000)
		if err != nil {
			continue
		}
		s.mu.Lock()
		active := map[string]bool{}
		for sid := range s.sessions {
			active[sid] = true
		}
		s.mu.Unlock()
		for _, it := range items {
			rest := strings.TrimPrefix(it.Key, s.box.Prefix+"/s/")
			sid, _, _ := strings.Cut(rest, "/")
			if !active[sid] && it.Age > 15*time.Minute {
				s.s3.del(ctx, it.Key)
			}
		}
	}
}

func (s *Server) Sessions() int { s.mu.Lock(); defer s.mu.Unlock(); return len(s.sessions) }

func sleepCtx(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}

var ErrClosed = errors.New("закрыто")
