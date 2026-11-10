package main

import (
	"errors"
	"sync"
	"time"
)

// Кадры туннеля: один IP-пакет режется на фрагменты, каждый фрагмент едет
// как полезная нагрузка одного RTP-пакета через мост Saby.
//
//	byte 0   : frameData (0xB7) или framePing (0xB8)
//	byte 1-2 : id пакета (big endian, циклический)
//	byte 3   : номер фрагмента
//	byte 4   : число фрагментов
//	byte 5.. : данные
const (
	frameData  byte = 0xB7
	framePing  byte = 0xB8
	headerLen       = 5
	MaxPayload      = 1000 // значение по умолчанию для старых сборок; реальное — флаг -maxpayload
)

// fragSize — сколько байт IP-пакета едет в одном кадре. Приёмник собирает любые размеры
// (число фрагментов лежит в заголовке кадра), поэтому сторонам не нужно совпадать.
func fragSize() int {
	p := *maxPayload
	if p < 300 {
		p = 300
	}
	if p > 1400 {
		p = 1400
	}
	return p - headerLen
}

var ErrBadFrame = errors.New("bad frame")

// Fragmenter режет IP-пакеты на кадры.
type Fragmenter struct {
	mu   sync.Mutex
	next uint16
}

func (f *Fragmenter) Split(pkt []byte) [][]byte {
	if len(pkt) == 0 {
		return nil
	}
	maxFrag := fragSize()
	n := (len(pkt) + maxFrag - 1) / maxFrag
	if n > 255 {
		return nil
	}
	f.mu.Lock()
	id := f.next
	f.next++
	f.mu.Unlock()
	out := make([][]byte, 0, n)
	for i := 0; i < n; i++ {
		lo := i * maxFrag
		hi := lo + maxFrag
		if hi > len(pkt) {
			hi = len(pkt)
		}
		fr := make([]byte, headerLen+hi-lo)
		fr[0] = frameData
		fr[1], fr[2] = byte(id>>8), byte(id)
		fr[3], fr[4] = byte(i), byte(n)
		copy(fr[headerLen:], pkt[lo:hi])
		out = append(out, fr)
	}
	return out
}

func PingFrame() []byte { return []byte{framePing, 0, 0, 0, 1} }

// Флаги пинга (байт 1): старые версии его не читают.
const pingFlagSeesVideo byte = 0x01

func PingFrameFlags(f byte) []byte { return []byte{framePing, f, 0, 0, 1} }

type partial struct {
	parts [][]byte
	got   int
	seen  time.Time
}

// Reassembler собирает пакеты обратно. Потерянные фрагменты — потерянный
// пакет (TCP внутри туннеля перепошлёт сам).
type Reassembler struct {
	mu      sync.Mutex
	pending map[uint16]*partial
	TTL     time.Duration
	MaxPend int
}

func NewReassembler() *Reassembler {
	return &Reassembler{pending: map[uint16]*partial{}, TTL: 2 * time.Second, MaxPend: 64}
}

// Push принимает один кадр. Возвращает готовый IP-пакет либо nil.
func (r *Reassembler) Push(fr []byte, now time.Time) ([]byte, error) {
	if len(fr) < headerLen {
		return nil, ErrBadFrame
	}
	if fr[0] == framePing {
		return nil, nil
	}
	if fr[0] != frameData {
		return nil, ErrBadFrame
	}
	id := uint16(fr[1])<<8 | uint16(fr[2])
	idx, cnt := int(fr[3]), int(fr[4])
	if cnt == 0 || idx >= cnt {
		return nil, ErrBadFrame
	}
	body := fr[headerLen:]
	if cnt == 1 {
		return append([]byte(nil), body...), nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gc(now)
	p := r.pending[id]
	if p == nil || len(p.parts) != cnt {
		if len(r.pending) >= r.MaxPend {
			r.dropOldest()
		}
		p = &partial{parts: make([][]byte, cnt)}
		r.pending[id] = p
	}
	p.seen = now
	if p.parts[idx] == nil {
		p.parts[idx] = append([]byte(nil), body...)
		p.got++
	}
	if p.got < cnt {
		return nil, nil
	}
	delete(r.pending, id)
	var out []byte
	for _, b := range p.parts {
		out = append(out, b...)
	}
	return out, nil
}

func (r *Reassembler) gc(now time.Time) {
	for id, p := range r.pending {
		if now.Sub(p.seen) > r.TTL {
			delete(r.pending, id)
		}
	}
}

func (r *Reassembler) dropOldest() {
	var oid uint16
	var ot time.Time
	first := true
	for id, p := range r.pending {
		if first || p.seen.Before(ot) {
			oid, ot, first = id, p.seen, false
		}
	}
	delete(r.pending, oid)
}
