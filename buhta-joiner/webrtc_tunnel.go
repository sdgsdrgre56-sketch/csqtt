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
	"fmt"
	"log"
	"math/rand"
	"time"

	"github.com/pion/webrtc/v3"
)

// AcceptAndConnect builds a pion PeerConnection from the parsed offer, generates a real
// SDP answer (ICE gathered, DTLS cert created), and returns:
//   - the Jingle session-accept XML to send back over XMPP
//   - the *webrtc.PeerConnection, already wired to log ICE/DTLS state transitions, so the
//     caller can wait and see whether it actually reaches "connected".
func AcceptAndConnect(offer *JingleOffer, responderJID string, iceServerInfos []ICEServerInfo) (acceptXML string, pc *webrtc.PeerConnection, err error) {
	offerSDP, err := offer.ToSDPOffer()
	if err != nil {
		return "", nil, fmt.Errorf("offer->SDP: %w", err)
	}
	log.Printf("translated Jingle offer into SDP:\n%s", offerSDP)

	m := webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return "", nil, fmt.Errorf("register codecs: %w", err)
	}
	api := webrtc.NewAPI(webrtc.WithMediaEngine(&m))

	iceServers := buildICEServers(iceServerInfos)
	for _, s := range iceServers {
		log.Printf("[webrtc] configured ICE server: %v", s.URLs)
	}
	pc, err = api.NewPeerConnection(webrtc.Configuration{
		ICEServers: iceServers,
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
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c != nil {
			log.Printf("[webrtc] local candidate gathered: %s", c.String())
		}
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		log.Printf("[webrtc] remote opened data channel %q", dc.Label())
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

	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return "", nil, fmt.Errorf("CreateAnswer: %w", err)
	}

	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		return "", nil, fmt.Errorf("SetLocalDescription: %w", err)
	}
	select {
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
