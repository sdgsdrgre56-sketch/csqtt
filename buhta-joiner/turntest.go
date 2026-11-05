// -turntest: checks whether Saby's TURN server (credentials obtained via extdisco, exactly
// as in the normal flow) will relay traffic to ARBITRARY internet hosts, not just Saby's own
// videobridges. If it does, Бухта can work the csqtt way: Saby TURN -> our own server,
// no WebRTC/DTLS with the bridge at all.
package main

import (
	"crypto/tls"
	"encoding/binary"
	"log"
	"net"
	"time"

	"github.com/pion/logging"
	"github.com/pion/stun"
	"github.com/pion/turn/v2"
)

type turnTarget struct {
	name string
	addr string
	kind string // "stun" or "dns"
}

var turnTargets = []turnTarget{
	{"Google STUN (IP)", "74.125.250.129:19302", "stun"},
	{"Google STUN (DNS name)", "stun.l.google.com:19302", "stun"},
	{"Cloudflare STUN", "stun.cloudflare.com:3478", "stun"},
	{"Yandex DNS 77.88.8.8", "77.88.8.8:53", "dns"},
	{"Google DNS 8.8.8.8", "8.8.8.8:53", "dns"},
	{"Own server UDP echo", "177.3.201.185:40000", "echo"},
	{"Own server 2 UDP echo", "91.149.233.205:40000", "echo"},
	{"Cloudflare DNS 1.1.1.1", "1.1.1.1:53", "dns"},
}

func runTurnTest(servers []ICEServerInfo) {
	anyOK := false
	tried := false
	for _, s := range servers {
		if s.Type != "turn" && s.Type != "turns" {
			continue
		}
		tried = true
		if testTurnServer(s) {
			anyOK = true
		}
	}
	switch {
	case !tried:
		log.Println("=== TURNTEST RESULT: server offered no TURN service at all ===")
	case anyOK:
		log.Println("=== TURNTEST RESULT: Saby TURN DOES relay to external hosts — csqtt-style path is OPEN ===")
	default:
		log.Println("=== TURNTEST RESULT: no external host answered through Saby TURN — relay looks RESTRICTED ===")
	}
}

func testTurnServer(s ICEServerInfo) bool {
	addr := net.JoinHostPort(s.Host, s.Port)
	log.Printf("[turntest] === %s %s transport=%s user=%s ===", s.Type, addr, s.Transport, s.Username)

	var pc net.PacketConn
	switch {
	case s.Type == "turns":
		c, err := tls.DialWithDialer(&net.Dialer{Timeout: 10 * time.Second}, "tcp", addr, &tls.Config{ServerName: s.Host})
		if err != nil {
			log.Printf("[turntest] TLS connect failed: %v", err)
			return false
		}
		pc = turn.NewSTUNConn(c)
	case s.Transport == "tcp":
		c, err := net.DialTimeout("tcp", addr, 10*time.Second)
		if err != nil {
			log.Printf("[turntest] TCP connect failed: %v", err)
			return false
		}
		pc = turn.NewSTUNConn(c)
	default:
		c, err := net.ListenPacket("udp4", "0.0.0.0:0")
		if err != nil {
			log.Printf("[turntest] UDP listen failed: %v", err)
			return false
		}
		pc = c
	}
	defer pc.Close()

	client, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: addr,
		TURNServerAddr: addr,
		Conn:           pc,
		Username:       s.Username,
		Password:       s.Password,
		LoggerFactory:  logging.NewDefaultLoggerFactory(),
	})
	if err != nil {
		log.Printf("[turntest] NewClient failed: %v", err)
		return false
	}
	defer client.Close()
	if err := client.Listen(); err != nil {
		log.Printf("[turntest] Listen failed: %v", err)
		return false
	}
	relay, err := client.Allocate()
	if err != nil {
		log.Printf("[turntest] Allocate failed: %v", err)
		return false
	}
	defer relay.Close()
	log.Printf("[turntest] Allocate OK, relayed address: %s", relay.LocalAddr())

	ok := false
	for _, t := range turnTargets {
		dst, err := net.ResolveUDPAddr("udp4", t.addr)
		if err != nil {
			log.Printf("[turntest] %-24s resolve failed: %v", t.name, err)
			continue
		}
		if probeTarget(relay, t, dst) {
			ok = true
		}
	}
	return ok
}

func probeTarget(relay net.PacketConn, t turnTarget, dst *net.UDPAddr) bool {
	var req []byte
	if t.kind == "stun" {
		req = stun.MustBuild(stun.TransactionID, stun.BindingRequest).Raw
	} else if t.kind == "echo" {
		req = []byte("buhta-echo-test")
	} else {
		req = dnsQuery("ya.ru")
	}
	buf := make([]byte, 2048)
	for attempt := 1; attempt <= 3; attempt++ {
		if _, err := relay.WriteTo(req, dst); err != nil {
			log.Printf("[turntest] %-24s (%s) send #%d failed: %v", t.name, dst, attempt, err)
			continue
		}
		_ = relay.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, from, err := relay.ReadFrom(buf)
		if err != nil {
			log.Printf("[turntest] %-24s (%s) attempt #%d: no reply (%v)", t.name, dst, attempt, err)
			continue
		}
		detail := ""
		if t.kind == "stun" {
			m := &stun.Message{Raw: append([]byte(nil), buf[:n]...)}
			if m.Decode() == nil {
				var xa stun.XORMappedAddress
				if xa.GetFrom(m) == nil {
					detail = " — our public address as seen by target: " + xa.String()
				}
			}
		} else if t.kind == "echo" {
			detail = " — echo payload: " + string(buf[:n])
		} else {
			detail = " — DNS answer received"
		}
		log.Printf("[turntest] %-24s (%s) REPLY OK: %d bytes from %s%s", t.name, dst, n, from, detail)
		return true
	}
	return false
}

// dnsQuery builds a minimal DNS A query for name.
func dnsQuery(name string) []byte {
	b := make([]byte, 12)
	binary.BigEndian.PutUint16(b[0:], 0x4242) // id
	binary.BigEndian.PutUint16(b[2:], 0x0100) // recursion desired
	binary.BigEndian.PutUint16(b[4:], 1)      // qdcount
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			b = append(b, byte(i-start))
			b = append(b, name[start:i]...)
			start = i + 1
		}
	}
	b = append(b, 0, 0, 1, 0, 1) // root, type A, class IN
	return b
}
