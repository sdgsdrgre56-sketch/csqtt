// -udptest: two speed-related probes, both aimed at getting off TCP.
//
// A) Saby's extdisco only ever advertises ONE service: turns:turn-relay4.saby.ru:443 over
//    TCP. TCP+TLS relaying is the most likely reason a call-based tunnel is slow (head-of-line
//    blocking). But a TURN server that speaks TCP on 443 very often also listens on plain UDP
//    3478 without advertising it. So: take the credentials extdisco hands us and try a real
//    TURN Allocate over UDP on the usual ports.
//
// B) Even better than a UDP relay would be no relay at all. The bridge's own media port
//    (srflx 91.232.93.237:10000 in every run so far) might be reachable directly over UDP from
//    here. An ICE agent answers a STUN Binding request even without valid credentials — with
//    an error response — so ANY reply at all proves the path is open and the relay could be
//    skipped entirely.
package main

import (
	"log"
	"net"
	"time"

	"github.com/pion/logging"
	"github.com/pion/stun"
	"github.com/pion/turn/v2"
)

// udpTurnPorts are the ports to try a UDP TURN Allocate on, beyond whatever extdisco named.
var udpTurnPorts = []string{"3478", "443", "80", "19302", "5349"}

func runUDPTest(servers []ICEServerInfo, bridgeAddr string) {
	log.Println("=== UDPTEST part A: does Saby's TURN server answer over plain UDP? ===")
	host := ""
	var cred ICEServerInfo
	for _, s := range servers {
		if s.Type == "turn" || s.Type == "turns" {
			host, cred = s.Host, s
			break
		}
	}
	udpRelayOK := ""
	if host == "" {
		log.Println("[udptest] no TURN service advertised — skipping part A")
	} else {
		for _, port := range udpTurnPorts {
			if tryUDPAllocate(host, port, cred) {
				udpRelayOK = net.JoinHostPort(host, port)
				break
			}
		}
	}

	log.Println("=== UDPTEST part B: is the bridge's media port reachable directly over UDP? ===")
	directOK := probeDirectUDP(bridgeAddr)

	// C) Decisive control test. If NOTHING answers over UDP anywhere, the problem is this
	// machine's connection (UDP blocked outright), not Saby — and that changes the whole
	// picture, because then TCP is the only transport available at all.
	log.Println("=== UDPTEST part C: control — does plain UDP leave this machine at all? ===")
	controls := []string{"74.125.250.129:19302", "stun.cloudflare.com:3478", "77.88.8.8:53"}
	udpWorksLocally := false
	for _, c := range controls {
		if probeControlUDP(c) {
			udpWorksLocally = true
		}
	}

	log.Println("=== UDPTEST RESULTS ===")
	switch {
	case udpRelayOK != "":
		log.Printf("  A) UDP TURN WORKS on %s — we can drop TCP relaying", udpRelayOK)
	default:
		log.Printf("  A) no UDP TURN on %s (tried ports %v) — relay stays TCP", host, udpTurnPorts)
	}
	if !udpWorksLocally {
		log.Printf("  C) NO UDP left this machine at all (control hosts silent) — UDP is blocked on this connection, so A and B say nothing about Saby")
	} else {
		log.Printf("  C) plain UDP works from this machine (a control host answered) — so A and B really are closed on Saby's side")
	}
	if directOK {
		log.Printf("  B) bridge media port %s ANSWERS over direct UDP — the relay can be skipped entirely", bridgeAddr)
	} else {
		log.Printf("  B) bridge media port %s silent over direct UDP — relay required", bridgeAddr)
	}
}

// tryUDPAllocate attempts a full TURN Allocate against host:port over plain UDP.
func tryUDPAllocate(host, port string, cred ICEServerInfo) bool {
	addr := net.JoinHostPort(host, port)
	pc, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		log.Printf("[udptest] A %-28s local UDP socket failed: %v", addr, err)
		return false
	}
	defer pc.Close()

	c, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: addr,
		TURNServerAddr: addr,
		Conn:           pc,
		Username:       cred.Username,
		Password:       cred.Password,
		RTO:            2 * time.Second,
		LoggerFactory:  logging.NewDefaultLoggerFactory(),
	})
	if err != nil {
		log.Printf("[udptest] A %-28s NewClient failed: %v", addr, err)
		return false
	}
	defer c.Close()
	if err := c.Listen(); err != nil {
		log.Printf("[udptest] A %-28s Listen failed: %v", addr, err)
		return false
	}

	// Before the full Allocate, a plain STUN Binding tells us whether ANYTHING listens on
	// this UDP port — cheaper to interpret than an Allocate timeout.
	if ra, err := net.ResolveUDPAddr("udp4", addr); err == nil {
		req := stun.MustBuild(stun.TransactionID, stun.BindingRequest)
		if _, werr := pc.WriteTo(req.Raw, ra); werr == nil {
			buf := make([]byte, 1500)
			_ = pc.SetReadDeadline(time.Now().Add(3 * time.Second))
			if n, from, rerr := pc.ReadFrom(buf); rerr == nil {
				log.Printf("[udptest] A %-28s STUN Binding answered: %d bytes from %s", addr, n, from)
			} else {
				log.Printf("[udptest] A %-28s STUN Binding: no reply (%v)", addr, rerr)
			}
			_ = pc.SetReadDeadline(time.Time{})
		}
	}

	relay, err := c.Allocate()
	if err != nil {
		log.Printf("[udptest] A %-28s Allocate failed: %v", addr, err)
		return false
	}
	defer relay.Close()
	log.Printf("[udptest] A %-28s ALLOCATE OK over UDP, relayed address %s", addr, relay.LocalAddr())
	return true
}

// probeDirectUDP sends plain STUN Binding requests straight at the bridge's media port.
// Any reply — success or error — means the path is open.
func probeDirectUDP(addr string) bool {
	ra, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		log.Printf("[udptest] B resolve %s failed: %v", addr, err)
		return false
	}
	pc, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		log.Printf("[udptest] B local UDP socket failed: %v", err)
		return false
	}
	defer pc.Close()

	buf := make([]byte, 1500)
	for attempt := 1; attempt <= 4; attempt++ {
		req := stun.MustBuild(stun.TransactionID, stun.BindingRequest)
		if _, err := pc.WriteTo(req.Raw, ra); err != nil {
			log.Printf("[udptest] B attempt #%d send failed: %v", attempt, err)
			continue
		}
		_ = pc.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			log.Printf("[udptest] B attempt #%d: no reply (%v)", attempt, err)
			continue
		}
		kind := "non-STUN data"
		m := &stun.Message{Raw: append([]byte(nil), buf[:n]...)}
		if m.Decode() == nil {
			kind = m.Type.String()
		}
		log.Printf("[udptest] B REPLY from %s: %d bytes, %s — path is OPEN", from, n, kind)
		return true
	}
	return false
}

// probeControlUDP sends a STUN Binding request to a well-known public STUN/DNS host over
// plain UDP, to establish whether UDP egress works from this machine at all.
func probeControlUDP(addr string) bool {
	ra, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		log.Printf("[udptest] C %-28s resolve failed: %v", addr, err)
		return false
	}
	pc, err := net.ListenPacket("udp4", "0.0.0.0:0")
	if err != nil {
		log.Printf("[udptest] C %-28s local socket failed: %v", addr, err)
		return false
	}
	defer pc.Close()
	var req []byte
	if ra.Port == 53 {
		req = dnsQuery("ya.ru")
	} else {
		req = stun.MustBuild(stun.TransactionID, stun.BindingRequest).Raw
	}
	buf := make([]byte, 1500)
	for attempt := 1; attempt <= 2; attempt++ {
		if _, err := pc.WriteTo(req, ra); err != nil {
			log.Printf("[udptest] C %-28s send failed: %v", addr, err)
			continue
		}
		_ = pc.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			log.Printf("[udptest] C %-28s attempt #%d: no reply (%v)", addr, attempt, err)
			continue
		}
		log.Printf("[udptest] C %-28s REPLY: %d bytes from %s — UDP egress works", addr, n, from)
		return true
	}
	return false
}
