package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// -key: ключ подключения buhta://… из веб-панели. Без него клиент работает в старом
// открытом режиме (один клиент 10.77.0.2), пока на сервере нет ни одного клиента.
var clientKey = flag.String("key", "", "ключ подключения buhta://… (режим client)")

type connKey struct {
	Room string `json:"room"`
	SPK  string `json:"spk"`
	Tok  string `json:"tok"`
	Name string `json:"n"`
}

func parseConnKey(s string) (*ecdh.PublicKey, [16]byte, *connKey, error) {
	var tok [16]byte
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "buhta://")
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
	if err != nil {
		return nil, tok, nil, errors.New("ключ: неверная кодировка")
	}
	var ck connKey
	if err := json.Unmarshal(raw, &ck); err != nil {
		return nil, tok, nil, errors.New("ключ: неверный формат")
	}
	spk, err := hex.DecodeString(ck.SPK)
	if err != nil {
		return nil, tok, nil, errors.New("ключ: неверный ключ сервера")
	}
	pub, err := x25519.NewPublicKey(spk)
	if err != nil {
		return nil, tok, nil, errors.New("ключ: неверный ключ сервера")
	}
	tb, err := hex.DecodeString(ck.Tok)
	if err != nil || len(tb) != 16 {
		return nil, tok, nil, errors.New("ключ: неверный токен")
	}
	copy(tok[:], tb)
	return pub, tok, &ck, nil
}

// runClientSecure: рукопожатие с сервером, затем шифрованный обмен пакетами.
func runClientSecure(t *Tunnel, conn *net.UDPConn, peer *atomic.Value) {
	pub, tok, ck, err := parseConnKey(*clientKey)
	if err != nil {
		log.Printf("[status] error %v", err)
		os.Exit(2)
	}
	log.Printf("[sec] ключ для %q принят, рукопожатие…", ck.Name)

	var (
		sess      atomic.Pointer[secSession]
		hsMu      sync.Mutex
		hs        *clientHS
		lastRx    int64 // unix nano последнего принятого пакета данных
		lastTx    int64
		announced int32
	)
	t.OnDatagram = func(dg []byte) {
		if len(dg) == 0 {
			return
		}
		switch dg[0] {
		case msgWelcome:
			hsMu.Lock()
			cur := hs
			hsMu.Unlock()
			if cur == nil {
				return
			}
			s, err := cur.finish(dg)
			if err != nil {
				return // чужой или устаревший ответ
			}
			sess.Store(s)
			hsMu.Lock()
			hs = nil // повторный WELCOME на то же приветствие не должен сбросить счётчики
			hsMu.Unlock()
			atomic.StoreInt64(&lastRx, time.Now().UnixNano())
			ip := net.IP(s.ip[:]).String()
			if atomic.CompareAndSwapInt32(&announced, 0, 1) {
				log.Printf("[status] connected ip=%s", ip)
			} else {
				log.Printf("[sec] сессия обновлена")
			}
		case msgData:
			s := sess.Load()
			if s == nil {
				return
			}
			if kid, ok := dgKid(dg); !ok || kid != s.kid {
				return // пакет чужого клиента (мост рассылает всем)
			}
			pkt, err := s.open(dg)
			if err != nil || len(pkt) < 20 || pkt[0]>>4 != 4 {
				return
			}
			atomic.StoreInt64(&lastRx, time.Now().UnixNano())
			if p, ok := peer.Load().(*net.UDPAddr); ok {
				conn.WriteToUDP(pkt, p)
			}
		}
	}

	// Рукопожатие: пока нет сессии — шлём приветствие раз в 1 с (новое каждые 8 с);
	// если сессия есть, но данные перестали приходить (сервер перезапущен) — начинаем заново.
	go func() {
		var hello []byte
		var made, sent time.Time
		for {
			time.Sleep(500 * time.Millisecond)
			now := time.Now()
			need := sess.Load() == nil
			if !need {
				rx := time.Unix(0, atomic.LoadInt64(&lastRx))
				tx := time.Unix(0, atomic.LoadInt64(&lastTx))
				if now.Sub(rx) > 15*time.Second && now.Sub(tx) < 10*time.Second && now.Sub(made) > 15*time.Second {
					log.Printf("[sec] данные не приходят, повторяю рукопожатие")
					need = true
					hello = nil
				}
			}
			if !need {
				continue
			}
			if hello == nil || now.Sub(made) > 8*time.Second {
				h, st, err := newClientHello(pub, tok, now)
				if err != nil {
					log.Printf("[sec] приветствие: %v", err)
					continue
				}
				hsMu.Lock()
				hs = st
				hsMu.Unlock()
				hello, made, sent = h, now, time.Time{}
			}
			if now.Sub(sent) >= 1*time.Second {
				t.SendPacket(hello)
				sent = now
			}
		}
	}()

	buf := make([]byte, 4096)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		peer.Store(from)
		if n >= len(KeepMagic) && bytes.Equal(buf[:len(KeepMagic)], KeepMagic) {
			continue // приманка насоса: только запоминаем обратный адрес
		}
		s := sess.Load()
		if s == nil {
			continue // туннель ещё не готов
		}
		atomic.StoreInt64(&lastTx, time.Now().UnixNano())
		pl := append([]byte(nil), buf[:n]...)
		k := flowKey(pl)
		t.SendPacketFlow(s.seal(pl), k)
	}
}
