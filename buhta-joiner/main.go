// Бухта — Saby Meet signaling joiner (test harness).
//
// What this is: a minimal, real XMPP-over-WebSocket client that performs exactly the guest
// join sequence captured from a live meet.saby.ru session on 2026-11-04 (ANONYMOUS SASL ->
// bind -> session -> stream management -> extdisco -> focus conference -> MUC presence), and
// then waits to print whatever the videobridge sends back, in particular a Jingle
// session-initiate carrying the bridge's real address and (if offered) a TURN relay.
//
// What this is NOT: it does not speak WebRTC. It never sends session-accept, never does
// ICE/DTLS/SRTP, and therefore never actually carries audio/video. It's a probe to confirm
// and inspect the signaling handshake with real server responses, which is the piece that
// had to be guessed at before. Building an actual media tunnel on top of this is the next,
// separate phase (porting the DCTunnel/MultiTrackTunnel pattern from whitelist-bypass), and
// needs this probe's real output as its starting point — especially how many Jingle
// candidates actually show up, whether a "relay" (TURN) type ever appears instead of just
// "srflx", and the exact Colibri WebSocket URL shape.
//
// Usage:
//
//	go run . -room 01a1a7f6-2bd2-7d7f-b921-15125aebf1c1 -name "Бухта"
//
// -room is the UUID from the guest link: https://meet.saby.ru/meet/<ROOM>?video=true&meeting=...
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func main() {
	room := flag.String("room", "", "room UUID from the guest link (…/meet/<ROOM>?video=true&meeting=…)")
	server := flag.String("server", "wss://vcall.saby.ru/xmpp-websocket/", "XMPP-over-WebSocket endpoint")
	domain := flag.String("domain", "meet.jitsi", "XMPP virtual host Prosody answers as")
	name := flag.String("name", "Бухта", "guest display name")
	timeout := flag.Duration("timeout", 45*time.Second, "how long to wait for a Jingle session-initiate after joining")
	hold := flag.Duration("hold", 20*time.Second, "how long to stay in the room after the connection is up")
	turnTest := flag.Bool("turntest", false, "only fetch Saby TURN credentials and test whether the TURN relay reaches external hosts, then exit")
	udpTest := flag.Bool("udptest", false, "probe for a UDP TURN endpoint and for direct UDP reachability of the bridge media port, then exit")
	bridgeAddr := flag.String("bridge", "91.232.93.237:10000", "bridge media address used by -udptest part B")
	relayOnly := flag.Bool("relayonly", false, "force all media through Saby's TCP TURN relay instead of letting ICE try a direct UDP path")
	flag.Parse()

	if *room == "" {
		fmt.Fprintln(os.Stderr, "error: -room is required (the UUID from the guest link)")
		os.Exit(2)
	}

	keepRelayOnly = *relayOnly

	ctx, cancel := context.WithTimeout(context.Background(), *timeout+20*time.Second)
	defer cancel()

	u, err := url.Parse(*server)
	must(err)
	q := u.Query()
	q.Set("room", *room)
	u.RawQuery = q.Encode()

	log.Printf("connecting to %s", u.String())
	dialer := websocket.Dialer{
		Subprotocols:     []string{"xmpp"},
		HandshakeTimeout: 15 * time.Second,
	}
	conn, _, err := dialer.DialContext(ctx, u.String(), nil)
	must(err)
	defer conn.Close()

	in := make(chan string, 64)
	go func() {
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				log.Printf("read loop ended: %v", err)
				close(in)
				return
			}
			in <- string(data)
		}
	}()

	iqSeq := 0
	send := func(frag string) {
		// Run #14: Jicofo rejected our transport-info AND session-accept with
		// "bad-request: Missing required 'id' attribute" — every IQ must carry an id. Add one
		// to any outgoing <iq ...> that lacks it.
		if strings.HasPrefix(frag, "<iq ") {
			head := frag
			if i := strings.Index(frag, ">"); i > 0 {
				head = frag[:i]
			}
			if !strings.Contains(head, " id=") {
				iqSeq++
				frag = fmt.Sprintf(`<iq id="buhta-out-%d"%s`, iqSeq, frag[len("<iq"):])
			}
		}
		log.Printf("--> %s", frag)
		if err := conn.WriteMessage(websocket.TextMessage, []byte(frag)); err != nil {
			log.Fatalf("write failed: %v", err)
		}
	}

	// waitFor blocks until a frame containing every needle in `needles` arrives, printing
	// every frame it sees along the way (nothing is silently dropped), or until ctx expires.
	waitFor := func(label string, needles ...string) string {
		for {
			select {
			case frame, ok := <-in:
				if !ok {
					log.Fatalf("connection closed while waiting for %s", label)
				}
				tag := "<-- "
				matched := true
				for _, n := range needles {
					if !strings.Contains(frame, n) {
						matched = false
						break
					}
				}
				if matched {
					log.Printf("%s%s  [matched: %s]", tag, truncate(frame, 300), label)
					return frame
				}
				log.Printf("%s%s", tag, truncate(frame, 300))
			case <-ctx.Done():
				log.Fatalf("timed out waiting for %s", label)
			}
		}
	}

	domainAttr := *domain

	// 1) Opening handshake, round 1: advertise ourselves, learn the SASL mechanism list.
	send(fmt.Sprintf(`<open to="%s" version="1.0" xmlns="urn:ietf:params:xml:ns:xmpp-framing"/>`, domainAttr))
	waitFor("stream open")
	waitFor("ANONYMOUS mechanism offered", "ANONYMOUS")

	// 2) SASL ANONYMOUS — no credentials. This is the whole reason the guest flow needs
	// nothing but a display name: Prosody's virtual host for Jitsi allows anonymous auth.
	send(`<auth mechanism="ANONYMOUS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"/>`)
	waitFor("SASL success", "success")

	// 3) Opening handshake, round 2 (required after auth per RFC 7395): learn bind/session/SM.
	send(fmt.Sprintf(`<open to="%s" version="1.0" xmlns="urn:ietf:params:xml:ns:xmpp-framing"/>`, domainAttr))
	waitFor("post-auth stream open")
	waitFor("post-auth features", "bind")

	// 4) Resource bind — server assigns both the local part (an opaque UUID) and the
	// resource; we don't request either.
	send(`<iq id="_bind_auth_2" type="set" xmlns="jabber:client"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"/></iq>`)
	bindResult := waitFor("bind result", `id='_bind_auth_2'`)
	jid := extractBetween(bindResult, "<jid>", "</jid>")
	if jid == "" {
		log.Fatalf("could not parse our own JID out of the bind result: %s", bindResult)
	}
	localPart := strings.SplitN(jid, "@", 2)[0]
	endpointID := localPart
	if len(endpointID) > 8 {
		endpointID = endpointID[:8]
	}
	log.Printf("bound as %s (muc endpoint id %s)", jid, endpointID)
	ourEndpointID = endpointID

	// 5) Legacy session establishment — Jitsi's Prosody still expects this even though
	// XEP-0078 marks it obsolete.
	send(`<iq id="_session_auth_2" type="set" xmlns="jabber:client"><session xmlns="urn:ietf:params:xml:ns:xmpp-session"/></iq>`)
	waitFor("session result", `id='_session_auth_2'`)

	// 6) Stream management — not required for a one-shot probe, but costs one line and
	// matches what a real client does.
	send(`<enable resume="true" xmlns="urn:xmpp:sm:3"/>`)
	waitFor("sm enabled", "enabled")

	// 7) Ask what STUN/TURN the server is willing to hand out. This is where
	// turn-relay4.saby.ru showed up in the earlier HAR capture.
	discoID := "buhta-" + randHex(4) + ":sendIQ"
	send(fmt.Sprintf(`<iq id="%s" to="%s" type="get" xmlns="jabber:client"><services xmlns="urn:xmpp:extdisco:2"/></iq>`, discoID, domainAttr))
	services := waitFor("extdisco services", "id='"+discoID+"'")
	var iceServers []ICEServerInfo
	for _, svc := range extractAllTags(services, "service") {
		log.Printf("ICE service offered: %s", svc)
		if info, ok := parseICEServiceTag(svc); ok {
			iceServers = append(iceServers, info)
		}
	}
	if *turnTest {
		runTurnTest(iceServers)
		return
	}
	if *udpTest {
		runUDPTest(iceServers, *bridgeAddr)
		return
	}

	// 8) Ask Jicofo (the conference focus) to create/join the conference for this room.
	machineUID := randHex(16)
	mucRoom := *room + "@muc.meet.jitsi"
	confID := "buhta-" + randHex(4) + ":sendIQ"
	send(fmt.Sprintf(
		`<iq id="%s" to="focus.meet.jitsi" type="set" xmlns="jabber:client"><conference machine-uid="%s" room="%s" xmlns="http://jitsi.org/protocol/focus"><property name="rtcstatsEnabled" value="false"/></conference></iq>`,
		confID, machineUID, mucRoom))
	confResult := waitFor("focus conference result", "id='"+confID+"'")
	if strings.Contains(confResult, `ready='true'`) {
		log.Printf("conference focus is ready")
	}

	// 9) Join the MUC room as a participant — this is the step that makes us visible to
	// everyone else in the room and is what triggers the bridge to negotiate media with us.
	statsID := "buhta-" + randHex(3)
	nick := endpointID + "#" + *name
	send(fmt.Sprintf(
		`<presence to="%s/%s" xmlns="jabber:client"><x xmlns="http://jabber.org/protocol/muc"/><stats-id>%s</stats-id><jitsi_participant_codecList>vp8,vp9,h264</jitsi_participant_codecList><nick xmlns="http://jabber.org/protocol/nick">%s</nick></presence>`,
		mucRoom, endpointID, statsID, nick))
	waitFor("own presence echoed back", mucRoom)

	log.Printf("joined %s as %s — waiting up to %s for a Jingle session-initiate from the bridge", mucRoom, nick, timeout)
	log.Printf("(this only arrives once there is real media to negotiate — a lone muted guest with no camera/mic may never get one; join from a second device if nothing shows up)")

	offerRaw := waitFor("Jingle session-initiate", "session-initiate")
	// Jicofo sends session-initiate as an IQ "set" and expects an IQ "result" ack — every real
	// client (lib-jitsi-meet) sends it immediately. We never did, in any run so far, which is a
	// strong candidate for why the bridge ignores everything afterwards.
	{
		ackTo, ackID := "", ""
		if m := regexp.MustCompile(`<iq[^>]*>`).FindString(offerRaw); m != "" {
			if mm := regexp.MustCompile(`\sid=['"]([^'"]+)['"]`).FindStringSubmatch(m); mm != nil {
				ackID = mm[1]
			}
			if mm := regexp.MustCompile(`\sfrom=['"]([^'"]+)['"]`).FindStringSubmatch(m); mm != nil {
				ackTo = mm[1]
			}
		}
		if ackID != "" && ackTo != "" {
			log.Printf("acking session-initiate (id=%s from=%s)", ackID, ackTo)
			send(fmt.Sprintf(`<iq to="%s" id="%s" type="result" xmlns="jabber:client"/>`, ackTo, ackID))
		} else {
			log.Printf("WARNING: could not extract id/from of session-initiate IQ to ack it")
		}
	}
	// From here on nothing else reads the XMPP stream, so frames from Jicofo (XMPP pings,
	// source-add, errors replying to our accept...) were never logged nor answered — log10-13
	// show an unanswered IQ "get" from focus even before the offer. Drain, log in full, and
	// answer every IQ get/set with an empty result like a real client would.
	go func() {
		iqHead := regexp.MustCompile(`^<iq[^>]*>`)
		for frame := range in {
			log.Printf("<-- (post-offer) %s", truncate(frame, 2500))
			queueSourceUpdate(frame)
			if h := iqHead.FindString(frame); h != "" && (strings.Contains(h, "type='get'") || strings.Contains(h, "type='set'") || strings.Contains(h, `type="get"`) || strings.Contains(h, `type="set"`)) {
				id, from := "", ""
				if mm := regexp.MustCompile(`\sid=['"]([^'"]+)['"]`).FindStringSubmatch(h); mm != nil {
					id = mm[1]
				}
				if mm := regexp.MustCompile(`\sfrom=['"]([^'"]+)['"]`).FindStringSubmatch(h); mm != nil {
					from = mm[1]
				}
				if id != "" && from != "" {
					send(fmt.Sprintf(`<iq to="%s" id="%s" type="result" xmlns="jabber:client"/>`, from, id))
				}
			}
		}
	}()
	fmt.Println()
	fmt.Println("=== FULL Jingle session-initiate ===")
	fmt.Println(offerRaw)
	fmt.Println()
	for _, c := range extractAllTags(offerRaw, "candidate") {
		fmt.Println("candidate:", c)
	}
	for _, ws := range extractAllAttrValues(offerRaw, "web-socket", "url") {
		fmt.Println("colibri websocket:", ws)
	}

	// ---- Phase 2: build a real WebRTC answer and try to actually connect -------------
	log.Println()
	log.Println("=== Phase 2: translating offer -> SDP -> pion/webrtc answer ===")
	jingleOffer, perr := ParseOffer(offerRaw)
	if perr != nil {
		log.Fatalf("Phase 2: could not parse session-initiate: %v", perr)
	}
	log.Printf("parsed offer: sid=%s initiator=%s contents=%d bundle=%v",
		jingleOffer.SID, jingleOffer.Initiator, len(jingleOffer.Contents), jingleOffer.BundleMids)

	log.Printf("Phase 2: using %d ICE server(s) discovered via extdisco", len(iceServers))
	acceptXML, pc, aerr := AcceptAndConnect(jingleOffer, jid, iceServers, send)
	if aerr != nil {
		log.Fatalf("Phase 2: AcceptAndConnect failed: %v", aerr)
	}
	defer pc.Close()
	go runSourceUpdates(pc)
	// Sources already present in the session-initiate (other participants). Only the first
	// one per content made it into the initial SDP; the rest are added via renegotiation.
	{
		initial := srcUpdate{Action: "source-add"}
		for _, c := range jingleOffer.Contents {
			rtx := map[string]bool{}
			for _, g := range c.SSRCGroups {
				if g.Semantics == "FID" && len(g.SSRCs) >= 2 {
					rtx[g.SSRCs[1]] = true
				}
			}
			for _, s := range c.Sources {
				if rtx[s.SSRC] {
					continue
				}
				initial.Items = append(initial.Items, srcItem{Content: c.Name, SSRC: s.SSRC, Name: s.Name, MSID: s.MSID})
			}
		}
		if len(initial.Items) > 0 {
			select {
			case srcUpdates <- initial:
			default:
			}
		}
	}

	if acceptXML != "" {
		log.Println("=== sending Jingle session-accept ===")
		send(acceptXML)
	}

	connectCtx, connectCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer connectCancel()
	if cerr := WaitForConnected(connectCtx, pc); cerr != nil {
		log.Printf("=== Phase 2 FAILED: peer connection did not reach 'connected': %v ===", cerr)
	} else {
		log.Println("=== Phase 2 SUCCESS: ICE connected and DTLS handshake completed ===")
	}

	// Keep the process (and the XMPP read loop, which still needs draining) alive for a
	// bit so we can observe whatever the bridge sends next (source-add, etc.) and so the
	// data channel has a chance to actually open.
	time.Sleep(*hold)
	log.Println("=== done, exiting ===")
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

func extractBetween(s, start, end string) string {
	i := strings.Index(s, start)
	if i < 0 {
		return ""
	}
	i += len(start)
	j := strings.Index(s[i:], end)
	if j < 0 {
		return ""
	}
	return s[i : i+j]
}

// extractAllTags returns each self-closing or opening occurrence of <tag .../> or <tag ...>
// as a raw string, for simple inspection (service entries, ICE candidates). Not a general
// XML parser — good enough for printing what the server sent without committing to a schema.
func extractAllTags(s, tag string) []string {
	var out []string
	needle := "<" + tag + " "
	for {
		i := strings.Index(s, needle)
		if i < 0 {
			break
		}
		j := strings.Index(s[i:], "/>")
		var end int
		if j < 0 {
			j2 := strings.Index(s[i:], ">")
			if j2 < 0 {
				break
			}
			end = i + j2 + 1
		} else {
			end = i + j + 2
		}
		out = append(out, s[i:end])
		s = s[end:]
	}
	return out
}

// ICEServerInfo is one STUN/TURN service as offered by the server's extdisco response.
type ICEServerInfo struct {
	Host      string
	Port      string
	Transport string // "udp", "tcp"
	Type      string // "stun", "turn", "turns"
	Username  string
	Password  string
}

// parseICEServiceTag extracts one <service .../> tag's attributes into an ICEServerInfo.
// Returns ok=false for anything we don't recognize (missing host/type).
func parseICEServiceTag(tag string) (ICEServerInfo, bool) {
	info := ICEServerInfo{
		Host:      attrFromSingleTag(tag, "host"),
		Port:      attrFromSingleTag(tag, "port"),
		Transport: attrFromSingleTag(tag, "transport"),
		Type:      attrFromSingleTag(tag, "type"),
		Username:  attrFromSingleTag(tag, "username"),
		Password:  attrFromSingleTag(tag, "password"),
	}
	if info.Host == "" || info.Type == "" {
		return info, false
	}
	return info, true
}

// attrFromSingleTag extracts one attribute's value from a single raw "<tag .../>" string
// (as opposed to extractAllAttrValues, which re-scans for occurrences of a named tag).
func attrFromSingleTag(tag, attr string) string {
	needle := attr + "='"
	i := strings.Index(tag, needle)
	quote := byte('\'')
	if i < 0 {
		needle = attr + `="`
		i = strings.Index(tag, needle)
		quote = '"'
	}
	if i < 0 {
		return ""
	}
	i += len(needle)
	j := strings.IndexByte(tag[i:], quote)
	if j < 0 {
		return ""
	}
	return tag[i : i+j]
}

func extractAllAttrValues(s, tag, attr string) []string {
	var out []string
	for _, t := range extractAllTags(s, tag) {
		needle := attr + "='"
		i := strings.Index(t, needle)
		quote := byte('\'')
		if i < 0 {
			needle = attr + `="`
			i = strings.Index(t, needle)
			quote = '"'
		}
		if i < 0 {
			continue
		}
		i += len(needle)
		j := strings.IndexByte(t[i:], quote)
		if j < 0 {
			continue
		}
		out = append(out, t[i:i+j])
	}
	return out
}
