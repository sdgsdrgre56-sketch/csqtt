// Phase 2: the actual media-tunnel layer. Takes a parsed Jingle session-initiate, drives a
// real pion/webrtc PeerConnection through it (ICE + DTLS + SCTP), and reports whether the
// connection actually came up — this is the "ICE connects + DTLS handshake succeeds"
// milestone. The DataChannel-based tunnel framing itself (porting DCTunnel/MultiTrackTunnel
// from whitelist-bypass) is the next step after this is confirmed working against the real
// server; AcceptAndConnect here already opens one named DataChannel ("buhta-tunnel") as the
// anchor point for that.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	crand "crypto/rand"
	"fmt"
	"log"
	"math/rand"
	"net"
	"regexp"
	"strings"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

// keepRelayOnly, when set via -relayonly, restores the old behaviour of forcing all traffic
// through Saby's TCP TURN relay. Default is off: gather every candidate type and let ICE
// prefer a direct UDP path to the bridge.
var keepRelayOnly bool

// AcceptAndConnect builds a pion PeerConnection from the parsed offer, generates a real
// SDP answer (ICE gathered, DTLS cert created), and returns:
//   - the Jingle session-accept XML to send back over XMPP
//   - the *webrtc.PeerConnection, already wired to log ICE/DTLS state transitions, so the
//     caller can wait and see whether it actually reaches "connected".
func AcceptAndConnect(offer *JingleOffer, responderJID string, iceServerInfos []ICEServerInfo, send func(string)) (acceptXML string, pc *webrtc.PeerConnection, err error) {
	offerSDP, err := offer.ToSDPOffer()
	if err != nil {
		return "", nil, fmt.Errorf("offer->SDP: %w", err)
	}
	log.Printf("translated Jingle offer into SDP:\n%s", offerSDP)

	m := webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return "", nil, fmt.Errorf("register codecs: %w", err)
	}

	// The last live run proved ICE can nominate a "succeeded" pair using one of our private
	// host candidates (172.19.0.1 / 192.168.0.179) against the bridge's public address — small
	// STUN binding checks apparently got through on some NAT fluke — but the DTLS handshake
	// that followed over that same pair never progressed past Flight 0, forever retransmitting
	// with no response, for 45+ seconds. A private RFC1918 address was never going to carry
	// real two-way traffic with a server on the public internet; the TURN relay candidate (the
	// one that has repeatedly, reliably completed a TURN channel-bind to the bridge in every
	// run so far) is the only candidate with a real chance of carrying the DTLS handshake. So
	// filter private-range local candidates out of ICE gathering entirely, forcing the agent
	// to pair (and therefore nominate and run DTLS) only over the relay.
	//
	// We also now generate our OWN ICE ufrag/pwd/DTLS certificate up front, instead of letting
	// pion generate them inside CreateAnswer/SetLocalDescription. Why: every run so far (#7,
	// #8) proved a hard race — pion starts sending *authenticated* ICE connectivity checks the
	// instant SetLocalDescription() returns, but only gives each candidate pair ~1.6s (7
	// retries) before giving up on it *permanently*. The bridge rejects those early checks with
	// a STUN error because it doesn't know our credentials yet — and it can't know them until
	// our XMPP message naming them has made the full round trip through Prosody *and* Jicofo
	// *and* down to the actual videobridge process, which has repeatedly taken longer than
	// 1.6s. Sending transport-info "early" (run #8) wasn't early enough, because it was still
	// sent *after* SetLocalDescription had already started the checking clock. By generating
	// credentials ourselves beforehand, we can send them and then deliberately wait several
	// seconds — with the checking clock not yet running at all — before ever calling
	// SetLocalDescription, guaranteeing the bridge has long since registered them before the
	// first check arrives.
	ufrag := randICEString(8)
	pwd := randICEString(26) // ICE requires ice-pwd to be at least 22 characters
	settingEngine := webrtc.SettingEngine{}
	// The private-address filter above was put in to chase a DTLS failure that turned out to
	// have a completely different cause (outgoing <iq> stanzas were missing the mandatory id
	// attribute, so Jicofo rejected our fingerprint outright). With that fixed, the filter is
	// not only unnecessary, it is harmful: it forces every byte through Saby's TCP+TLS relay,
	// which is the main suspect for the slow throughput. Earlier runs showed a candidate pair
	// from one of our LOCAL addresses reaching "succeeded" against the bridge's public media
	// address, i.e. direct UDP to the bridge does work (the bridge learns our mapped address
	// as a peer-reflexive candidate from our own authenticated checks — the same way a normal
	// browser connects). So let ICE gather everything and pick: host/srflx pairs outrank relay
	// on priority, so a working direct UDP path wins, and the TCP relay stays as a fallback.
	if keepRelayOnly {
		log.Printf("[webrtc] relay-only mode: filtering out private/loopback/link-local candidates")
		settingEngine.SetIPFilter(func(ip net.IP) bool {
			return !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast()
		})
	} else {
		log.Printf("[webrtc] gathering ALL candidate types — a direct UDP pair beats the TCP relay if it works")
	}
	settingEngine.SetICECredentials(ufrag, pwd)
	// Run #9 proved ICE itself now works end to end (clean nomination over the TURN relay, no
	// auth errors) — but DTLS stayed stuck forever at "[handshake:server] Flight 0:
	// Sending/Waiting", i.e. pion sat passively waiting for a ClientHello that never arrived,
	// DESPITE our session-accept explicitly telling the bridge "setup=passive" (meaning: bridge,
	// you be active, you send the ClientHello). Since the real Jitsi videobridge conventionally
	// always acts as the DTLS *server* itself (waits for the client/browser to initiate) and
	// does not appear to actually honor our declared "setup" value, both sides end up waiting.
	// Force pion to be the DTLS client instead, so WE send the ClientHello.
	if derr := settingEngine.SetAnsweringDTLSRole(webrtc.DTLSRoleClient); derr != nil {
		log.Printf("[webrtc] WARNING: SetAnsweringDTLSRole failed: %v", derr)
	}
	api := webrtc.NewAPI(webrtc.WithMediaEngine(&m), webrtc.WithSettingEngine(settingEngine))

	key, err := ecdsa.GenerateKey(elliptic.P256(), crand.Reader)
	if err != nil {
		return "", nil, fmt.Errorf("generate DTLS key: %w", err)
	}
	cert, err := webrtc.GenerateCertificate(key)
	if err != nil {
		return "", nil, fmt.Errorf("generate DTLS certificate: %w", err)
	}
	fps, err := cert.GetFingerprints()
	if err != nil || len(fps) == 0 {
		return "", nil, fmt.Errorf("get certificate fingerprint: %w", err)
	}
	fpHash, fpVal := fps[0].Algorithm, strings.ToUpper(fps[0].Value)

	iceServers := buildICEServers(iceServerInfos)
	for _, s := range iceServers {
		log.Printf("[webrtc] configured ICE server: %v", s.URLs)
	}

	// Send our credentials now — before the PeerConnection even exists, let alone before any
	// ICE check could possibly be sent — and give the bridge a real head start.
	if send != nil && len(offer.Contents) > 0 {
		tiXML := buildEarlyTransportInfoXML(offer, responderJID, ufrag, pwd, fpHash, fpVal)
		log.Println("=== sending early Jingle transport-info (ufrag/pwd/fingerprint, well before any ICE check) ===")
		send(tiXML)
		const headStart = 3 * time.Second
		log.Printf("[webrtc] waiting %s for the bridge to register our credentials before starting ICE", headStart)
		time.Sleep(headStart)
	}

	pc, err = api.NewPeerConnection(webrtc.Configuration{
		ICEServers:   iceServers,
		Certificates: []webrtc.Certificate{*cert},
	})
	if err != nil {
		return "", nil, fmt.Errorf("new peer connection: %w", err)
	}

	pc.OnICEConnectionStateChange(func(s webrtc.ICEConnectionState) {
		log.Printf("[webrtc] ICE connection state -> %s", s)
	})
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		log.Printf("[webrtc] peer connection state -> %s", s)
	})
	pc.OnICEGatheringStateChange(func(s webrtc.ICEGathererState) {
		log.Printf("[webrtc] ICE gathering state -> %s", s)
	})
	gotCandidate := make(chan struct{}, 1)
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			log.Printf("[webrtc] local candidate gathered: %s", c.String())
			select {
			case gotCandidate <- struct{}{}:
			default:
			}
		}
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		log.Printf("[webrtc] remote opened data channel %q", dc.Label())
	})

	// Receive path: count every RTP packet the bridge forwards to us.
	pc.OnTrack(func(tr *webrtc.TrackRemote, rcv *webrtc.RTPReceiver) {
		log.Printf("[rtp] OnTrack ssrc=%d pt=%d codec=%s", tr.SSRC(), tr.PayloadType(), tr.Codec().MimeType)
		go func() {
			buf := make([]byte, 1500)
			var n, total int
			for {
				k, _, rerr := tr.Read(buf)
				if rerr != nil {
					log.Printf("[rtp] ssrc=%d read ended after %d pkts / %d bytes: %v", tr.SSRC(), n, total, rerr)
					return
				}
				n++
				total += k
				if n == 1 || n%50 == 0 {
					log.Printf("[rtp] ssrc=%d got %d pkts / %d bytes (last %d bytes)", tr.SSRC(), n, total, k)
				}
			}
		}()
	})

	// Open our own DataChannel now, before SetLocalDescription, so pion's SCTP negotiation
	// (and the m=application line it writes into the answer) actually has a channel behind
	// it — this is the anchor point Phase 3 will build the tunnel framing on top of.
	tunnelDC, err := pc.CreateDataChannel("buhta-tunnel", nil)
	if err != nil {
		return "", nil, fmt.Errorf("create data channel: %w", err)
	}
	tunnelDC.OnOpen(func() {
		log.Printf("[webrtc] buhta-tunnel data channel is OPEN")
	})
	tunnelDC.OnClose(func() {
		log.Printf("[webrtc] buhta-tunnel data channel closed")
	})
	tunnelDC.OnError(func(e error) {
		log.Printf("[webrtc] buhta-tunnel data channel error: %v", e)
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  offerSDP,
	}); err != nil {
		return "", nil, fmt.Errorf("SetRemoteDescription: %w", err)
	}

	// Send path: our own outgoing audio track; once connected we push test packets so a
	// second participant (e.g. the server copy) can prove it receives them via the bridge.
	outTrack, terr := webrtc.NewTrackLocalStaticRTP(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2},
		"buhta-audio", "buhta")
	if terr != nil {
		log.Printf("[rtp] WARNING: NewTrackLocalStaticRTP: %v", terr)
	} else if sender, aerr := pc.AddTrack(outTrack); aerr != nil {
		log.Printf("[rtp] WARNING: AddTrack: %v", aerr)
	} else {
		go func() {
			buf := make([]byte, 1500)
			for {
				if _, _, e := sender.Read(buf); e != nil {
					return
				}
			}
		}()
		go func() {
			for i := 0; i < 600 && pc.ConnectionState() != webrtc.PeerConnectionStateConnected; i++ {
				time.Sleep(100 * time.Millisecond)
			}
			if pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
				return
			}
			log.Printf("[rtp] connected; sending 150 test packets on our track")
			ssrc := uint32(0)
			for _, p := range sender.GetParameters().Encodings {
				ssrc = uint32(p.SSRC)
			}
			log.Printf("[rtp] our outgoing ssrc (pion) = %d", ssrc)
			for i := 0; i < 150; i++ {
				payload := []byte(fmt.Sprintf("BUHTA-TEST-%04d-----------------------------", i))
				pkt := &rtp.Packet{
					Header: rtp.Header{Version: 2, PayloadType: 111, SequenceNumber: uint16(i), Timestamp: uint32(i * 960)},
					Payload: payload,
				}
				if werr := outTrack.WriteRTP(pkt); werr != nil {
					log.Printf("[rtp] WriteRTP: %v", werr)
					return
				}
				time.Sleep(20 * time.Millisecond)
			}
			log.Printf("[rtp] finished sending 150 test packets")
		}()
	}

	// Safety net: explicitly (re-)add each remote ICE candidate from the parsed Jingle offer
	// via AddICECandidate, instead of relying solely on pion having parsed them back out of
	// the SDP text we generated. The live trace from the first TURN-enabled run showed ICE
	// gathering and TURN channel-binding succeed, but then NOT ONE connectivity check (ping)
	// of any kind before the 25s failure timeout — which only makes sense if pion never
	// actually registered a remote candidate to pair against in the first place (a candidate
	// pair requires both a local AND a remote candidate). The most likely culprit is the
	// srflx candidate's rel-addr='0.0.0.0' (which is exactly what the JVB itself sends) not
	// surviving our SDP round-trip cleanly. Adding it directly from the already-parsed Jingle
	// data sidesteps that translation entirely.
	if len(offer.Contents) > 0 {
		mid := "audio"
		var idx uint16
		for _, c := range offer.Contents[0].Candidates {
			line := fmt.Sprintf("candidate:%s %s %s %s %s %s typ %s",
				c.Foundation, c.Component, c.Protocol, c.Priority, c.IP, c.Port, c.Type)
			if c.RelAddr != "" {
				line += fmt.Sprintf(" raddr %s rport %s", c.RelAddr, c.RelPort)
			}
			if c.Generation != "" {
				line += " generation " + c.Generation
			}
			if err := pc.AddICECandidate(webrtc.ICECandidateInit{
				Candidate:     line,
				SDPMid:        &mid,
				SDPMLineIndex: &idx,
			}); err != nil {
				log.Printf("[webrtc] WARNING: explicit AddICECandidate failed for %q: %v", line, err)
			} else {
				log.Printf("[webrtc] explicitly added remote candidate: %s", line)
			}
		}
	}

	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", nil, fmt.Errorf("CreateAnswer: %w", err)
	}

	// Run #11 showed sending session-accept at the same instant ICE checks start breaks ICE
	// entirely (no pair ever succeeded), while run #10 (accept 3s AFTER ICE connected) got ICE
	// up but DTLS got no answer. So send the session-accept FIRST — built from the answer
	// SDP, which already has our fixed ufrag/pwd/fingerprint (candidates aren't needed: the
	// bridge is ice-lite and learns our address from our checks) — wait for the signaling
	// chain to digest it, and only THEN call SetLocalDescription, which starts gathering and
	// ICE checks.
	// Announce the SSRC pion will actually use for our outgoing track, otherwise the bridge
	// drops our RTP as coming from an unknown source.
	ourAudioSSRC := answerAudioSSRC(answer.SDP)
	if ourAudioSSRC == 0 {
		ourAudioSSRC = randSSRC()
		log.Printf("[rtp] WARNING: no a=ssrc in answer SDP, announcing random %d", ourAudioSSRC)
	} else {
		log.Printf("[rtp] announcing our audio ssrc %d in session-accept", ourAudioSSRC)
	}
	earlyAccept, aerr := offer.BuildSessionAccept(answer.SDP, offer.ConferenceID, responderJID,
		ourAudioSSRC, randSSRC())
	if aerr != nil {
		return "", nil, fmt.Errorf("build early session-accept: %w", aerr)
	}
	if send != nil {
		log.Println("=== sending Jingle session-accept BEFORE starting ICE ===")
		send(earlyAccept)
		time.Sleep(3 * time.Second)
	}

	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		return "", nil, fmt.Errorf("SetLocalDescription: %w", err)
	}
	if send != nil {
		return "", pc, nil
	}

	// log10 proved the actual bottleneck: pion's ICE-gathering-"complete" event lagged ~3-4s
	// behind the single relay candidate we actually got (it keeps internally trying other
	// candidate types that our SetIPFilter guarantees will never arrive, e.g. host/srflx, before
	// giving up and declaring "complete"). Our session-accept was built only after "complete",
	// so it left the machine *after* ICE had already connected and we'd already started sending
	// DTLS ClientHello for seconds — a very plausible reason the bridge never answered it: the
	// signaling chain (Prosody -> Jicofo -> videobridge) that arms the JVB's DTLS listener for
	// our fingerprint/SSRCs almost certainly hadn't finished processing session-accept yet.
	// We only ever expect exactly one usable candidate (the relay), so don't wait for the full
	// "complete" event at all — send as soon as that one candidate shows up. LocalDescription()
	// already reflects whatever's been gathered so far, complete or not.
	select {
	case <-gotCandidate:
		log.Printf("[webrtc] got first local candidate - sending session-accept now instead of waiting for full gathering-complete")
	case <-gatherComplete:
	case <-time.After(15 * time.Second):
		log.Printf("[webrtc] WARNING: ICE gathering did not signal complete within 15s, sending answer with whatever was gathered so far")
	}

	finalAnswer := pc.LocalDescription()
	if finalAnswer == nil {
		return "", nil, fmt.Errorf("no local description after gathering")
	}
	log.Printf("final SDP answer after ICE gathering:\n%s", finalAnswer.SDP)

	acceptXML, err = offer.BuildSessionAccept(finalAnswer.SDP, offer.ConferenceID, responderJID,
		randSSRC(), randSSRC())
	if err != nil {
		return "", nil, fmt.Errorf("build session-accept: %w", err)
	}
	return acceptXML, pc, nil
}

var ssrcLineRe = regexp.MustCompile(`(?m)^a=ssrc:(\d+) `)

// answerAudioSSRC returns the first a=ssrc found in the audio m-section of an SDP, or 0.
func answerAudioSSRC(sdp string) uint32 {
	i := strings.Index(sdp, "m=audio")
	if i < 0 {
		return 0
	}
	sec := sdp[i:]
	if j := strings.Index(sec[1:], "\nm="); j >= 0 {
		sec = sec[:j+1]
	}
	m := ssrcLineRe.FindStringSubmatch(sec)
	if m == nil {
		return 0
	}
	var v uint64
	fmt.Sscanf(m[1], "%d", &v)
	return uint32(v)
}

const iceStringAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

// randICEString generates a random alphanumeric string suitable for an ICE ufrag or pwd.
func randICEString(n int) string {
	b := make([]byte, n)
	for i := range b {
		b[i] = iceStringAlphabet[rand.Intn(len(iceStringAlphabet))]
	}
	return string(b)
}

// buildEarlyTransportInfoXML renders a minimal Jingle transport-info <iq> carrying just our
// pre-generated ICE ufrag/pwd/DTLS fingerprint for each bundled content — no candidates, no
// payload-types, no SSRCs. See the long comment at its call site in AcceptAndConnect for why
// this is sent, and sent this early (before the PeerConnection even exists).
func buildEarlyTransportInfoXML(offer *JingleOffer, responderJID, ufrag, pwd, fpHash, fpVal string) string {
	var b strings.Builder
	fmt.Fprintf(&b, `<iq to="%s" type="set" xmlns="jabber:client"><jingle action="transport-info" initiator="%s" responder="%s" sid="%s" xmlns="urn:xmpp:jingle:1">`,
		xmlEscape(offer.ConferenceID), xmlEscape(offer.Initiator), xmlEscape(responderJID), xmlEscape(offer.SID))
	for _, c := range offer.Contents {
		fmt.Fprintf(&b, `<content creator="responder" name="%s">`, xmlEscape(c.Name))
		fmt.Fprintf(&b, `<transport pwd="%s" ufrag="%s" xmlns="urn:xmpp:jingle:transports:ice-udp:1">`, xmlEscape(pwd), xmlEscape(ufrag))
		fmt.Fprintf(&b, `<fingerprint hash="%s" setup="active" xmlns="urn:xmpp:jingle:apps:dtls:0">%s</fingerprint>`, xmlEscape(fpHash), fpVal)
		fmt.Fprintf(&b, `</transport></content>`)
	}
	fmt.Fprintf(&b, `</jingle></iq>`)
	return b.String()
}

// buildICEServers turns the server's extdisco offerings into pion ICEServer entries, so
// pion actually gathers srflx/relay candidates instead of only local host candidates (which
// is what was happening before this: an empty webrtc.Configuration{} gathers host-only
// candidates, which a bridge sitting behind NAT on the public internet can never reach —
// that's why the first live Phase 2 run's ICE connection failed after only host candidates
// were offered in the answer).
func buildICEServers(infos []ICEServerInfo) []webrtc.ICEServer {
	var out []webrtc.ICEServer
	for _, info := range infos {
		scheme := "stun"
		switch info.Type {
		case "stun":
			scheme = "stun"
		case "turn":
			scheme = "turn"
		case "turns":
			scheme = "turns"
		default:
			continue
		}
		url := fmt.Sprintf("%s:%s:%s", scheme, info.Host, info.Port)
		if info.Transport != "" && scheme != "stun" {
			url += "?transport=" + info.Transport
		}
		s := webrtc.ICEServer{URLs: []string{url}}
		if info.Username != "" {
			s.Username = info.Username
			s.Credential = info.Password
			s.CredentialType = webrtc.ICECredentialTypePassword
		}
		out = append(out, s)
	}
	return out
}

func randSSRC() uint32 {
	return rand.Uint32()&0x7fffffff | 1
}

// WaitForConnected blocks until the PeerConnection reaches "connected" or ctx is done,
// returning nil on success. This is how main.go will report the Phase-2 milestone.
func WaitForConnected(ctx context.Context, pc *webrtc.PeerConnection) error {
	done := make(chan webrtc.PeerConnectionState, 1)
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		log.Printf("[webrtc] peer connection state -> %s", s)
		if s == webrtc.PeerConnectionStateConnected || s == webrtc.PeerConnectionStateFailed {
			select {
			case done <- s:
			default:
			}
		}
	})
	select {
	case s := <-done:
		if s == webrtc.PeerConnectionStateConnected {
			return nil
		}
		return fmt.Errorf("peer connection reached state %s", s)
	case <-ctx.Done():
		return ctx.Err()
	}
}
