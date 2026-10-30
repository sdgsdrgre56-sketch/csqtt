package core

// Мультиплексор: много TCP-соединений в одном канале. Сервер принимает данные сайта
// сам, на полной скорости, и сразу отправляет в облако — телефону не нужно
// подтверждать каждый кусок через облако (как делает TCP поверх туннеля), поэтому
// задержка облака почти не режет скорость. Ограничение — только окно потока.

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	fOpen     = 1 // адрес host:port
	fOpenFail = 2 // текст ошибки
	fData     = 3
	fFin      = 4 // отправитель больше не пишет
	fRst      = 5 // поток оборван
	fCredit   = 6 // uvarint: можно прислать ещё столько байт
	fPing     = 7 // 8 байт времени
	fPong     = 8
	fBye      = 9 // сессия завершается
)

var (
	streamWindow = 16 << 20 // окно потока: столько данных может быть «в пути» без подтверждения
	chunk        = 64 << 10 // кусок чтения из сокета
)

func frame(typ byte, id uint32, payload []byte) []byte {
	b := make([]byte, 0, len(payload)+12)
	b = append(b, typ)
	b = binary.AppendUvarint(b, uint64(id))
	b = binary.AppendUvarint(b, uint64(len(payload)))
	return append(b, payload...)
}

type mux struct {
	p      carrier
	client bool
	dial   func(addr string) (net.Conn, error) // у сервера

	mu      sync.Mutex
	streams map[uint32]*stream
	nextID  uint32
	dead    bool

	rtt   atomic.Int64 // нс, последний замер
	alive atomic.Int64 // UnixNano последнего кадра от собеседника
	onBye func()
}

func newMux(client bool) *mux {
	m := &mux{client: client, streams: map[uint32]*stream{}, nextID: 1}
	m.alive.Store(time.Now().UnixNano())
	return m
}

// handle разбирает одно сообщение (содержимое объекта) на кадры.
func (m *mux) handle(b []byte) {
	m.alive.Store(time.Now().UnixNano())
	for len(b) > 0 {
		typ := b[0]
		id, n1 := binary.Uvarint(b[1:])
		if n1 <= 0 {
			return
		}
		ln, n2 := binary.Uvarint(b[1+n1:])
		if n2 <= 0 || uint64(len(b)-1-n1-n2) < ln {
			return
		}
		pl := b[1+n1+n2 : 1+n1+n2+int(ln)]
		b = b[1+n1+n2+int(ln):]
		m.frame(typ, uint32(id), pl)
	}
}

func (m *mux) frame(typ byte, id uint32, pl []byte) {
	switch typ {
	case fPing:
		m.p.send(frame(fPong, 0, pl), true)
		return
	case fPong:
		if len(pl) == 8 {
			m.rtt.Store(time.Now().UnixNano() - int64(binary.BigEndian.Uint64(pl)))
		}
		return
	case fBye:
		if m.onBye != nil {
			go m.onBye()
		}
		return
	case fOpen:
		if m.client {
			return
		}
		s := m.newStream(id, nil)
		addr := string(pl)
		go func() {
			c, err := m.dial(addr)
			if err != nil {
				m.p.send(frame(fOpenFail, id, []byte(err.Error())), true)
				s.kill(false)
				return
			}
			s.attach(c)
		}()
		return
	}
	m.mu.Lock()
	s := m.streams[id]
	m.mu.Unlock()
	if s == nil {
		if typ == fData || typ == fFin {
			m.p.send(frame(fRst, id, nil), true)
		}
		return
	}
	switch typ {
	case fData:
		s.push(append([]byte(nil), pl...))
	case fFin:
		s.push(nil)
	case fRst, fOpenFail:
		if typ == fOpenFail {
			logf("не открылось %s: %s", s.addr, pl)
		}
		s.kill(false)
	case fCredit:
		v, n := binary.Uvarint(pl)
		if n > 0 {
			s.addCredit(int64(v))
		}
	}
}

func (m *mux) newStream(id uint32, c net.Conn) *stream {
	s := &stream{m: m, id: id, credit: int64(streamWindow)}
	s.cond = sync.NewCond(&s.mu)
	m.mu.Lock()
	m.streams[id] = s
	m.mu.Unlock()
	if c != nil {
		s.attach(c)
	}
	return s
}

// open (клиент): новый поток к addr поверх соединения c приложения.
func (m *mux) open(c net.Conn, addr string) {
	m.mu.Lock()
	id := m.nextID
	m.nextID += 2
	m.mu.Unlock()
	if err := m.p.send(frame(fOpen, id, []byte(addr)), true); err != nil {
		c.Close()
		return
	}
	s := m.newStream(id, nil)
	s.addr = addr
	s.attach(c)
}

func (m *mux) shutdown() {
	m.mu.Lock()
	m.dead = true
	ss := make([]*stream, 0, len(m.streams))
	for _, s := range m.streams {
		ss = append(ss, s)
	}
	m.mu.Unlock()
	for _, s := range ss {
		s.kill(false)
	}
}

func (m *mux) count() int { m.mu.Lock(); defer m.mu.Unlock(); return len(m.streams) }

type stream struct {
	m    *mux
	id   uint32
	addr string

	mu       sync.Mutex
	cond     *sync.Cond
	conn     net.Conn
	q        [][]byte // входящие данные для сокета (nil — FIN)
	credit   int64    // сколько ещё можно отправить собеседнику
	consumed int64    // записано в сокет с прошлого CREDIT
	dead     bool
	upDone   bool
	downDone bool
}

func (s *stream) attach(c net.Conn) {
	s.mu.Lock()
	if s.dead {
		s.mu.Unlock()
		c.Close()
		return
	}
	s.conn = c
	s.cond.Broadcast()
	s.mu.Unlock()
	go s.uplink()
	go s.downlink()
}

func (s *stream) push(b []byte) {
	s.mu.Lock()
	if !s.dead {
		s.q = append(s.q, b)
		s.cond.Broadcast()
	}
	s.mu.Unlock()
}

func (s *stream) addCredit(n int64) {
	s.mu.Lock()
	s.credit += n
	s.cond.Broadcast()
	s.mu.Unlock()
}

// kill обрывает поток; notify — сообщить собеседнику.
func (s *stream) kill(notify bool) {
	s.mu.Lock()
	if s.dead {
		s.mu.Unlock()
		return
	}
	s.dead = true
	c := s.conn
	s.q = nil
	s.cond.Broadcast()
	s.mu.Unlock()
	if c != nil {
		c.Close()
	}
	if notify {
		s.m.p.send(frame(fRst, s.id, nil), true)
	}
	s.m.mu.Lock()
	delete(s.m.streams, s.id)
	s.m.mu.Unlock()
}

func (s *stream) finish(up bool) {
	s.mu.Lock()
	if up {
		s.upDone = true
	} else {
		s.downDone = true
	}
	both := s.upDone && s.downDone
	s.mu.Unlock()
	if both {
		s.mu.Lock()
		s.dead = true
		c := s.conn
		s.mu.Unlock()
		c.Close()
		s.m.mu.Lock()
		delete(s.m.streams, s.id)
		s.m.mu.Unlock()
	}
}

// uplink: сокет → собеседник, в пределах окна.
func (s *stream) uplink() {
	buf := make([]byte, chunk)
	for {
		s.mu.Lock()
		for s.credit <= 0 && !s.dead {
			s.cond.Wait()
		}
		if s.dead {
			s.mu.Unlock()
			return
		}
		lim := int64(len(buf))
		if s.credit < lim {
			lim = s.credit
		}
		s.mu.Unlock()
		n, err := s.conn.Read(buf[:lim])
		if n > 0 {
			s.mu.Lock()
			s.credit -= int64(n)
			s.mu.Unlock()
			if s.m.p.send(frame(fData, s.id, append([]byte(nil), buf[:n]...)), false) != nil {
				s.kill(false)
				return
			}
		}
		if err != nil {
			s.mu.Lock()
			dead := s.dead
			s.mu.Unlock()
			if dead {
				return
			}
			if errors.Is(err, io.EOF) {
				s.m.p.send(frame(fFin, s.id, nil), false) // FIN — в общей очереди с данными, чтобы не обогнать их
				s.finish(true)
			} else {
				s.kill(true)
			}
			return
		}
	}
}

// downlink: собеседник → сокет; освобождённое окно возвращается кадром CREDIT.
func (s *stream) downlink() {
	for {
		s.mu.Lock()
		for len(s.q) == 0 && !s.dead {
			s.cond.Wait()
		}
		if s.dead {
			s.mu.Unlock()
			return
		}
		b := s.q[0]
		s.q = s.q[1:]
		s.mu.Unlock()
		if b == nil { // FIN
			if tc, ok := s.conn.(interface{ CloseWrite() error }); ok {
				tc.CloseWrite()
			}
			s.finish(false)
			return
		}
		if _, err := s.conn.Write(b); err != nil {
			s.kill(true)
			return
		}
		s.mu.Lock()
		s.consumed += int64(len(b))
		var give int64
		if s.consumed >= int64(streamWindow/4) || len(s.q) == 0 && s.consumed >= 256<<10 {
			give, s.consumed = s.consumed, 0
		}
		s.mu.Unlock()
		if give > 0 {
			s.m.p.send(frame(fCredit, s.id, binary.AppendUvarint(nil, uint64(give))), true)
		}
	}
}

// carrier — то, по чему идут кадры: облачный ящик (pipe) или прямое соединение.
type carrier interface {
	send(frame []byte, ctrl bool) error
}
