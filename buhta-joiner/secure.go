package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/mlkem"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// Сквозное шифрование между клиентом и сервером Бухты.
//
// Мост Saby сам расшифровывает защиту звонка, поэтому наши пакеты он видит открытым
// текстом. Поверх звонка мы делаем собственный слой: гибридное рукопожатие
// X25519 + ML-KEM-768 (идея Antohin из Квазара) и AES-256-GCM на каждый пакет
// со счётчиком и окном защиты от повторов. Только стандартная библиотека Go.
//
// Датаграммы (после сборки фрагментов):
//
//	HELLO   0x01 | epk(32) | ek(1184) | AEAD(k1, ts(8) | token(16))
//	WELCOME 0x02 | eps(32) | ct(1088) | AEAD(k2, kid(2) | ip(4))
//	DATA    0x03 | kid(2) | ctr(8)    | AEAD(key, IP-пакет)
const (
	msgHello   byte = 0x01
	msgWelcome byte = 0x02
	msgData    byte = 0x03

	mlkemEKLen  = 1184
	mlkemCTLen  = 1088
	helloBody   = 8 + 16
	helloLen    = 1 + 32 + mlkemEKLen + helloBody + 16
	welcomeBody = 2 + 4
	welcomeLen  = 1 + 32 + mlkemCTLen + welcomeBody + 16
	dataHdr     = 1 + 2 + 8
	maxSkew     = 120 * time.Second
)

var (
	ErrSecBad    = errors.New("secure: bad message")
	ErrSecAuth   = errors.New("secure: auth failed")
	ErrSecReplay = errors.New("secure: replay")
	x25519       = ecdh.X25519()
)

func hkdf(secret, salt, info []byte, n int) []byte {
	if salt == nil {
		salt = make([]byte, sha256.Size)
	}
	m := hmac.New(sha256.New, salt)
	m.Write(secret)
	prk := m.Sum(nil)
	var out, prev []byte
	for i := byte(1); len(out) < n; i++ {
		h := hmac.New(sha256.New, prk)
		h.Write(prev)
		h.Write(info)
		h.Write([]byte{i})
		prev = h.Sum(nil)
		out = append(out, prev...)
	}
	return out[:n]
}

func newGCM(key []byte) cipher.AEAD {
	b, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	g, err := cipher.NewGCM(b)
	if err != nil {
		panic(err)
	}
	return g
}

func ctrNonce(ctr uint64) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint64(n[4:], ctr)
	return n
}

// replayWindow — скользящее окно принятых счётчиков (2048 пакетов).
type replayWindow struct {
	mu   sync.Mutex
	max  uint64
	seen [32]uint64
	init bool
}

func (w *replayWindow) check(ctr uint64) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.init {
		return true
	}
	if ctr > w.max {
		return true
	}
	d := w.max - ctr
	if d >= 2048 {
		return false
	}
	return w.seen[d/64]&(1<<(d%64)) == 0
}

func (w *replayWindow) mark(ctr uint64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.init || ctr > w.max {
		shift := uint64(2048)
		if w.init {
			shift = ctr - w.max
		}
		if shift >= 2048 {
			w.seen = [32]uint64{}
		} else {
			// сдвигаем окно вверх на shift бит
			for i := 31; i >= 0; i-- {
				var v uint64
				src := i - int(shift/64)
				if src >= 0 {
					v = w.seen[src] << (shift % 64)
					if shift%64 != 0 && src-1 >= 0 {
						v |= w.seen[src-1] >> (64 - shift%64)
					}
				}
				w.seen[i] = v
			}
		}
		w.max = ctr
		w.init = true
		w.seen[0] |= 1
		return
	}
	d := w.max - ctr
	w.seen[d/64] |= 1 << (d % 64)
}

// secSession — готовая сессия одного клиента (общая для клиента и сервера).
type secSession struct {
	kid  uint16
	ip   [4]byte
	send cipher.AEAD
	recv cipher.AEAD
	ctr  uint64
	win  replayWindow
}

func (s *secSession) seal(pkt []byte) []byte {
	c := atomic.AddUint64(&s.ctr, 1)
	out := make([]byte, dataHdr, dataHdr+len(pkt)+16)
	out[0] = msgData
	binary.BigEndian.PutUint16(out[1:], s.kid)
	binary.BigEndian.PutUint64(out[3:], c)
	return s.send.Seal(out, ctrNonce(c), pkt, out[:3])
}

func (s *secSession) open(dg []byte) ([]byte, error) {
	if len(dg) < dataHdr+16 || dg[0] != msgData {
		return nil, ErrSecBad
	}
	c := binary.BigEndian.Uint64(dg[3:11])
	if !s.win.check(c) {
		return nil, ErrSecReplay
	}
	pt, err := s.recv.Open(nil, ctrNonce(c), dg[dataHdr:], dg[:3])
	if err != nil {
		return nil, ErrSecAuth
	}
	s.win.mark(c)
	return pt, nil
}

func dgKid(dg []byte) (uint16, bool) {
	if len(dg) < dataHdr+16 || dg[0] != msgData {
		return 0, false
	}
	return binary.BigEndian.Uint16(dg[1:3]), true
}

// ---------- клиентская сторона ----------

type clientHS struct {
	esk   *ecdh.PrivateKey
	dk    *mlkem.DecapsulationKey768
	spk   *ecdh.PublicKey
	epk   []byte
	ek    []byte
	token [16]byte
}

func newClientHello(spk *ecdh.PublicKey, token [16]byte, now time.Time) ([]byte, *clientHS, error) {
	esk, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	dk, err := mlkem.GenerateKey768()
	if err != nil {
		return nil, nil, err
	}
	hs := &clientHS{esk: esk, dk: dk, spk: spk, epk: esk.PublicKey().Bytes(), ek: dk.EncapsulationKey().Bytes(), token: token}
	dh2, err := esk.ECDH(spk)
	if err != nil {
		return nil, nil, err
	}
	k1 := hkdf(dh2, nil, []byte("buhta/1 hello"), 32)
	body := make([]byte, helloBody)
	binary.BigEndian.PutUint64(body, uint64(now.Unix()))
	copy(body[8:], token[:])
	out := make([]byte, 0, helloLen)
	out = append(out, msgHello)
	out = append(out, hs.epk...)
	out = append(out, hs.ek...)
	aad := append(append([]byte{}, hs.epk...), hs.ek...)
	out = newGCM(k1).Seal(out, ctrNonce(0), body, aad)
	return out, hs, nil
}

func (hs *clientHS) finish(welcome []byte) (*secSession, error) {
	if len(welcome) != welcomeLen || welcome[0] != msgWelcome {
		return nil, ErrSecBad
	}
	eps := welcome[1:33]
	ct := welcome[33 : 33+mlkemCTLen]
	box := welcome[33+mlkemCTLen:]
	epsKey, err := x25519.NewPublicKey(eps)
	if err != nil {
		return nil, ErrSecBad
	}
	dh1, err := hs.esk.ECDH(epsKey)
	if err != nil {
		return nil, ErrSecBad
	}
	dh2, err := hs.esk.ECDH(hs.spk)
	if err != nil {
		return nil, ErrSecBad
	}
	k2 := hkdf(append(append([]byte{}, dh1...), dh2...), nil, []byte("buhta/1 welcome"), 32)
	aad := append(append(append([]byte{}, hs.epk...), eps...), ct...)
	pt, err := newGCM(k2).Open(nil, ctrNonce(0), box, aad)
	if err != nil || len(pt) != welcomeBody {
		return nil, ErrSecAuth
	}
	ss, err := hs.dk.Decapsulate(ct)
	if err != nil {
		return nil, ErrSecAuth
	}
	c2s, s2c := deriveKeys(dh1, dh2, ss, hs.epk, eps, hs.ek, ct)
	s := &secSession{kid: binary.BigEndian.Uint16(pt[:2]), send: newGCM(c2s), recv: newGCM(s2c)}
	copy(s.ip[:], pt[2:6])
	return s, nil
}

func deriveKeys(dh1, dh2, ss, epk, eps, ek, ct []byte) (c2s, s2c []byte) {
	ikm := append(append(append([]byte{}, dh1...), dh2...), ss...)
	h := sha256.New()
	h.Write(epk)
	h.Write(eps)
	h.Write(ek)
	h.Write(ct)
	salt := h.Sum(nil)
	return hkdf(ikm, salt, []byte("buhta/1 c2s"), 32), hkdf(ikm, salt, []byte("buhta/1 s2c"), 32)
}

// ---------- серверная сторона ----------

// parseHello расшифровывает приветствие и возвращает токен клиента.
// Сессию создаёт acceptHello после проверки токена по базе клиентов.
type helloInfo struct {
	epk, ek []byte
	dh2     []byte
	token   [16]byte
}

func parseHello(spriv *ecdh.PrivateKey, hello []byte, now time.Time) (*helloInfo, error) {
	if len(hello) != helloLen || hello[0] != msgHello {
		return nil, ErrSecBad
	}
	epk := hello[1:33]
	ek := hello[33 : 33+mlkemEKLen]
	box := hello[33+mlkemEKLen:]
	pk, err := x25519.NewPublicKey(epk)
	if err != nil {
		return nil, ErrSecBad
	}
	dh2, err := spriv.ECDH(pk)
	if err != nil {
		return nil, ErrSecBad
	}
	k1 := hkdf(dh2, nil, []byte("buhta/1 hello"), 32)
	aad := append(append([]byte{}, epk...), ek...)
	pt, err := newGCM(k1).Open(nil, ctrNonce(0), box, aad)
	if err != nil || len(pt) != helloBody {
		return nil, ErrSecAuth
	}
	ts := time.Unix(int64(binary.BigEndian.Uint64(pt)), 0)
	if d := now.Sub(ts); d > maxSkew || d < -maxSkew {
		return nil, ErrSecAuth
	}
	hi := &helloInfo{epk: append([]byte(nil), epk...), ek: append([]byte(nil), ek...), dh2: dh2}
	copy(hi.token[:], pt[8:])
	return hi, nil
}

func acceptHello(hi *helloInfo, kid uint16, ip [4]byte) (*secSession, []byte, error) {
	eks, err := mlkem.NewEncapsulationKey768(hi.ek)
	if err != nil {
		return nil, nil, ErrSecBad
	}
	ss, ct := eks.Encapsulate()
	esk, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	pk, err := x25519.NewPublicKey(hi.epk)
	if err != nil {
		return nil, nil, ErrSecBad
	}
	dh1, err := esk.ECDH(pk)
	if err != nil {
		return nil, nil, ErrSecBad
	}
	eps := esk.PublicKey().Bytes()
	k2 := hkdf(append(append([]byte{}, dh1...), hi.dh2...), nil, []byte("buhta/1 welcome"), 32)
	body := make([]byte, welcomeBody)
	binary.BigEndian.PutUint16(body, kid)
	copy(body[2:], ip[:])
	aad := append(append(append([]byte{}, hi.epk...), eps...), ct...)
	out := make([]byte, 0, welcomeLen)
	out = append(out, msgWelcome)
	out = append(out, eps...)
	out = append(out, ct...)
	out = newGCM(k2).Seal(out, ctrNonce(0), body, aad)
	c2s, s2c := deriveKeys(dh1, hi.dh2, ss, hi.epk, eps, hi.ek, ct)
	s := &secSession{kid: kid, ip: ip, send: newGCM(s2c), recv: newGCM(c2s)}
	return s, out, nil
}
