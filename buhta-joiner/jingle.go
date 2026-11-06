// Translation between Jitsi's Jingle XML signaling and standard WebRTC SDP, so that a
// standard WebRTC stack (pion/webrtc) can be used instead of hand-rolling ICE/DTLS/SCTP.
//
// This is NOT a general-purpose Jingle<->SDP converter. It only handles the exact shape of
// session-initiate that meet.saby.ru's Jicofo/JVB actually send (captured live on
// 2026-11-04): one <transport> (ufrag/pwd/fingerprint) shared identically across all
// <content> elements because everything is BUNDLEd onto one ICE/DTLS component, audio+video
// RTP payload types, and a "data" content carrying an old-style <sctpmap> for the
// webrtc-datachannel protocol. Fields outside that shape are best-effort/ignored.
package main

import (
	"encoding/xml"
	"fmt"
	"strings"
)

// ---- Parsed representation --------------------------------------------------------------

type JingleOffer struct {
	SID          string
	Initiator    string
	Contents     []JingleContent
	BundleMids   []string // from the <group semantics='BUNDLE'> content order
	ConferenceID string   // the MUC room JID (local part before @muc.meet.jitsi), i.e. the focus JID we reply to
}

type JingleContent struct {
	Name        string // "audio" | "video" | "data" | ...
	Media       string // "audio" | "video" | "application"
	Senders     string
	PayloadType []PayloadType
	Ufrag       string
	Pwd         string
	FpHash      string
	FpSetup     string
	FpValue     string
	Candidates  []ICECandidate
	IsSCTP      bool
	Sources     []JingleSource // <source> elements: remote SSRCs the bridge will send us
	SSRCGroups  []JingleSSRCGroup
	HdrExts     []JingleHdrExt // <rtp-hdrext> id/uri pairs
	SCTPNumber  string // sctpmap "number" (the SCTP port in old notation), e.g. "5000"
}

type JingleSource struct {
	SSRC string
	Name string
	MSID string
}

type JingleSSRCGroup struct {
	Semantics string
	SSRCs     []string
}

type JingleHdrExt struct {
	ID  string
	URI string
}

type PayloadType struct {
	ID        string
	Name      string
	Clockrate string
	Channels  string
	Params    [][2]string // name/value, e.g. apt=100, minptime=10
	FeedbackT []string    // rtcp-fb "type[/subtype]" strings, e.g. "nack", "nack/pli"
}

type ICECandidate struct {
	Foundation string
	Component  string
	Protocol   string
	Priority   string
	IP         string
	Port       string
	Type       string
	RelAddr    string
	RelPort    string
	Generation string
}

// ---- Raw XML decode structs (encoding/xml) -----------------------------------------------

type xmlIQ struct {
	XMLName xml.Name `xml:"iq"`
	From    string   `xml:"from,attr"`
	Jingle  xmlJingle `xml:"jingle"`
}

type xmlJingle struct {
	Action    string        `xml:"action,attr"`
	Initiator string        `xml:"initiator,attr"`
	SID       string        `xml:"sid,attr"`
	Contents  []xmlContent  `xml:"content"`
	Group     xmlGroup      `xml:"group"`
}

type xmlGroup struct {
	Contents []xmlGroupContent `xml:"content"`
}
type xmlGroupContent struct {
	Name string `xml:"name,attr"`
}

type xmlContent struct {
	Name        string          `xml:"name,attr"`
	Senders     string          `xml:"senders,attr"`
	Description xmlDescription  `xml:"description"`
	Transport   xmlTransport    `xml:"transport"`
}

type xmlDescription struct {
	Media        string            `xml:"media,attr"`
	PayloadTypes []xmlPayloadType  `xml:"payload-type"`
	HdrExts      []xmlHdrExt       `xml:"rtp-hdrext"`
	Sources      []xmlSource       `xml:"source"`
	SSRCGroups   []xmlSSRCGroup    `xml:"ssrc-group"`
}

type xmlHdrExt struct {
	ID  string `xml:"id,attr"`
	URI string `xml:"uri,attr"`
}

type xmlSource struct {
	SSRC      string         `xml:"ssrc,attr"`
	Name      string         `xml:"name,attr"`
	Parameter []xmlParameter `xml:"parameter"`
}

type xmlSSRCGroup struct {
	Semantics string `xml:"semantics,attr"`
	Sources   []struct {
		SSRC string `xml:"ssrc,attr"`
	} `xml:"source"`
}

type xmlPayloadType struct {
	ID        string          `xml:"id,attr"`
	Name      string          `xml:"name,attr"`
	Clockrate string          `xml:"clockrate,attr"`
	Channels  string          `xml:"channels,attr"`
	Parameter []xmlParameter  `xml:"parameter"`
	RTCPFb    []xmlRTCPFb     `xml:"rtcp-fb"`
}

type xmlParameter struct {
	Name  string `xml:"name,attr"`
	Value string `xml:"value,attr"`
}

type xmlRTCPFb struct {
	Type    string `xml:"type,attr"`
	Subtype string `xml:"subtype,attr"`
}

type xmlTransport struct {
	Ufrag       string          `xml:"ufrag,attr"`
	Pwd         string          `xml:"pwd,attr"`
	Fingerprint xmlFingerprint  `xml:"fingerprint"`
	Candidates  []xmlCandidate  `xml:"candidate"`
	SCTPMap     xmlSCTPMap      `xml:"sctpmap"`
}

type xmlFingerprint struct {
	Hash  string `xml:"hash,attr"`
	Setup string `xml:"setup,attr"`
	Value string `xml:",chardata"`
}

type xmlCandidate struct {
	Foundation string `xml:"foundation,attr"`
	Component  string `xml:"component,attr"`
	Protocol   string `xml:"protocol,attr"`
	Priority   string `xml:"priority,attr"`
	IP         string `xml:"ip,attr"`
	Port       string `xml:"port,attr"`
	Type       string `xml:"type,attr"`
	RelAddr    string `xml:"rel-addr,attr"`
	RelPort    string `xml:"rel-port,attr"`
	Generation string `xml:"generation,attr"`
}

type xmlSCTPMap struct {
	Number string `xml:"number,attr"`
}

// ParseOffer parses a raw <iq>...<jingle action='session-initiate'>...</jingle></iq> frame
// (exactly what main.go's waitFor("Jingle session-initiate", ...) returns) into a JingleOffer.
func ParseOffer(raw string) (*JingleOffer, error) {
	var iq xmlIQ
	if err := xml.Unmarshal([]byte(raw), &iq); err != nil {
		return nil, fmt.Errorf("decoding session-initiate: %w", err)
	}
	if iq.Jingle.Action != "session-initiate" {
		return nil, fmt.Errorf("expected action='session-initiate', got %q", iq.Jingle.Action)
	}

	out := &JingleOffer{
		SID:          iq.Jingle.SID,
		Initiator:    iq.Jingle.Initiator,
		ConferenceID: iq.From, // "<room-uuid>@muc.meet.jitsi/focus" — reply target
	}
	for _, gc := range iq.Jingle.Group.Contents {
		out.BundleMids = append(out.BundleMids, gc.Name)
	}

	for _, c := range iq.Jingle.Contents {
		jc := JingleContent{
			Name:    c.Name,
			Media:   c.Description.Media,
			Senders: c.Senders,
			Ufrag:   c.Transport.Ufrag,
			Pwd:     c.Transport.Pwd,
			FpHash:  c.Transport.Fingerprint.Hash,
			FpSetup: c.Transport.Fingerprint.Setup,
			FpValue: strings.TrimSpace(c.Transport.Fingerprint.Value),
		}
		if c.Transport.SCTPMap.Number != "" {
			jc.IsSCTP = true
			jc.SCTPNumber = c.Transport.SCTPMap.Number
		}
		for _, he := range c.Description.HdrExts {
			jc.HdrExts = append(jc.HdrExts, JingleHdrExt{ID: he.ID, URI: he.URI})
		}
		for _, src := range c.Description.Sources {
			js := JingleSource{SSRC: src.SSRC, Name: src.Name}
			for _, prm := range src.Parameter {
				if prm.Name == "msid" {
					js.MSID = prm.Value
				}
			}
			jc.Sources = append(jc.Sources, js)
		}
		for _, g := range c.Description.SSRCGroups {
			jg := JingleSSRCGroup{Semantics: g.Semantics}
			for _, gs := range g.Sources {
				jg.SSRCs = append(jg.SSRCs, gs.SSRC)
			}
			jc.SSRCGroups = append(jc.SSRCGroups, jg)
		}
		for _, pt := range c.Description.PayloadTypes {
			p := PayloadType{ID: pt.ID, Name: pt.Name, Clockrate: pt.Clockrate, Channels: pt.Channels}
			for _, param := range pt.Parameter {
				p.Params = append(p.Params, [2]string{param.Name, param.Value})
			}
			for _, fb := range pt.RTCPFb {
				if fb.Subtype != "" {
					p.FeedbackT = append(p.FeedbackT, fb.Type+"/"+fb.Subtype)
				} else {
					p.FeedbackT = append(p.FeedbackT, fb.Type)
				}
			}
			jc.PayloadType = append(jc.PayloadType, p)
		}
		for _, cand := range c.Transport.Candidates {
			jc.Candidates = append(jc.Candidates, ICECandidate{
				Foundation: cand.Foundation, Component: cand.Component, Protocol: cand.Protocol,
				Priority: cand.Priority, IP: cand.IP, Port: cand.Port, Type: cand.Type,
				RelAddr: cand.RelAddr, RelPort: cand.RelPort, Generation: cand.Generation,
			})
		}
		out.Contents = append(out.Contents, jc)
	}
	if len(out.Contents) == 0 {
		return nil, fmt.Errorf("session-initiate had no <content> elements")
	}
	return out, nil
}

// ---- Offer -> SDP ---------------------------------------------------------------------

// ToSDPOffer renders the parsed Jingle offer as a standard WebRTC SDP offer string, suitable
// for pion/webrtc's PeerConnection.SetRemoteDescription. One shared ICE ufrag/pwd/fingerprint
// is assumed (true for every saby.ru capture seen: everything is BUNDLEd onto content[0]'s
// transport), and every content's candidates are unioned onto every m-line, matching how a
// bundled SDP is normally written (candidates only truly need to appear once, but repeating
// them on every m-line is harmless and matches real browser answers more closely).
func (o *JingleOffer) ToSDPOffer() (string, error) {
	if len(o.Contents) == 0 {
		return "", fmt.Errorf("no contents")
	}
	ufrag, pwd, fpHash, fpVal, fpSetup := "", "", "", "", "actpass"
	var allCands []ICECandidate
	for _, c := range o.Contents {
		if c.Ufrag != "" {
			ufrag, pwd, fpHash, fpVal = c.Ufrag, c.Pwd, c.FpHash, c.FpValue
			if c.FpSetup != "" {
				fpSetup = c.FpSetup
			}
		}
		allCands = append(allCands, c.Candidates...)
	}
	if ufrag == "" || pwd == "" {
		return "", fmt.Errorf("no ICE ufrag/pwd found in offer")
	}

	var b strings.Builder
	fmt.Fprintf(&b, "v=0\r\n")
	fmt.Fprintf(&b, "o=- 0 0 IN IP4 0.0.0.0\r\n")
	fmt.Fprintf(&b, "s=-\r\n")
	fmt.Fprintf(&b, "t=0 0\r\n")
	// The live trace from our first real TURN-enabled run proved the bridge behaves like an
	// ICE-lite agent: our side's connectivity checks against it succeed (we see "state:
	// succeeded" candidate pairs, including the TURN relay one), but it never sends a single
	// connectivity check back to us and never nominates a pair — so ICE sits at
	// "succeeded but not nominated" until pion's internal failure timeout fires. Per RFC 8445
	// §6.1.1, when the remote is lite, the full agent (us) must take the controlling role so
	// *we* perform the nomination. Pion decides this from whether the remote SDP carries
	// "a=ice-lite" (see pion/webrtc's isIceLiteSet + the offerer/lite role table in
	// peerconnection.go) — the real session-initiate never says so explicitly, but since we
	// now know empirically that's exactly how the bridge behaves, we assert it ourselves here
	// so pion flips itself into the controlling role and actually nominates once it has a
	// succeeded pair.
	fmt.Fprintf(&b, "a=ice-lite\r\n")
	if len(o.BundleMids) > 0 {
		fmt.Fprintf(&b, "a=group:BUNDLE %s\r\n", strings.Join(o.BundleMids, " "))
	}

	for _, c := range o.Contents {
		proto := "UDP/TLS/RTP/SAVPF"
		if c.IsSCTP {
			proto = "UDP/DTLS/SCTP"
		}
		media := c.Media
		if media == "" {
			media = "application"
		}
		var pts []string
		for _, pt := range c.PayloadType {
			pts = append(pts, pt.ID)
		}
		payloadList := strings.Join(pts, " ")
		if c.IsSCTP {
			payloadList = "webrtc-datachannel"
		}
		fmt.Fprintf(&b, "m=%s 9 %s %s\r\n", media, proto, payloadList)
		fmt.Fprintf(&b, "c=IN IP4 0.0.0.0\r\n")
		fmt.Fprintf(&b, "a=mid:%s\r\n", c.Name)
		fmt.Fprintf(&b, "a=ice-ufrag:%s\r\n", ufrag)
		fmt.Fprintf(&b, "a=ice-pwd:%s\r\n", pwd)
		fmt.Fprintf(&b, "a=ice-options:trickle\r\n")
		if fpHash != "" {
			fmt.Fprintf(&b, "a=fingerprint:%s %s\r\n", fpHash, fpVal)
		}
		fmt.Fprintf(&b, "a=setup:%s\r\n", fpSetup)

		if c.IsSCTP {
			fmt.Fprintf(&b, "a=sctp-port:%s\r\n", c.SCTPNumber)
		} else {
			switch c.Senders {
			case "initiator":
				fmt.Fprintf(&b, "a=sendonly\r\n")
			case "responder":
				fmt.Fprintf(&b, "a=recvonly\r\n")
			default:
				fmt.Fprintf(&b, "a=sendrecv\r\n")
			}
			fmt.Fprintf(&b, "a=rtcp-mux\r\n")
			// Header extensions announced by the bridge (e.g. ssrc-audio-level). Skip ones pion
			// would not understand anyway only by letting pion ignore them; emit all.
			for _, he := range c.HdrExts {
				fmt.Fprintf(&b, "a=extmap:%s %s\r\n", he.ID, he.URI)
			}
			for _, pt := range c.PayloadType {
				if pt.Clockrate != "" {
					if pt.Channels != "" && pt.Channels != "1" {
						fmt.Fprintf(&b, "a=rtpmap:%s %s/%s/%s\r\n", pt.ID, pt.Name, pt.Clockrate, pt.Channels)
					} else {
						fmt.Fprintf(&b, "a=rtpmap:%s %s/%s\r\n", pt.ID, pt.Name, pt.Clockrate)
					}
				}
				if len(pt.Params) > 0 {
					var kv []string
					for _, p := range pt.Params {
						kv = append(kv, p[0]+"="+p[1])
					}
					fmt.Fprintf(&b, "a=fmtp:%s %s\r\n", pt.ID, strings.Join(kv, ";"))
				}
				for _, fb := range pt.FeedbackT {
					fmt.Fprintf(&b, "a=rtcp-fb:%s %s\r\n", pt.ID, strings.ReplaceAll(fb, "/", " "))
				}
			}
		}

		// Remote SSRCs. The bridge sends RTP without a MID header extension, so pion can only
		// route packets to a receiver if the SDP names the SSRCs (a=ssrc / a=ssrc-group).
		// Without these lines pion logs "Incoming unhandled RTP ... OnTrack will not be fired".
		if !c.IsSCTP {
			for _, g := range c.SSRCGroups {
				if len(g.SSRCs) > 0 {
					fmt.Fprintf(&b, "a=ssrc-group:%s %s\r\n", g.Semantics, strings.Join(g.SSRCs, " "))
				}
			}
			for _, src := range c.Sources {
				if src.SSRC == "" {
					continue
				}
				fmt.Fprintf(&b, "a=ssrc:%s cname:%s\r\n", src.SSRC, src.SSRC)
				if src.MSID != "" {
					fmt.Fprintf(&b, "a=ssrc:%s msid:%s\r\n", src.SSRC, src.MSID)
				}
			}
		}

		for _, cand := range allCands {
			line := candidateToSDPLine(cand)
			if line != "" {
				fmt.Fprintf(&b, "a=%s\r\n", line)
			}
		}
		fmt.Fprintf(&b, "a=end-of-candidates\r\n")
	}

	return b.String(), nil
}

func candidateToSDPLine(c ICECandidate) string {
	if c.Foundation == "" || c.Component == "" || c.Protocol == "" || c.IP == "" || c.Port == "" {
		return ""
	}
	priority := c.Priority
	if priority == "" {
		priority = "1"
	}
	gen := c.Generation
	if gen == "" {
		gen = "0"
	}
	line := fmt.Sprintf("candidate:%s %s %s %s %s %s typ %s generation %s",
		c.Foundation, c.Component, c.Protocol, priority, c.IP, c.Port, c.Type, gen)
	if c.RelAddr != "" && c.RelPort != "" {
		line = fmt.Sprintf("candidate:%s %s %s %s %s %s typ %s raddr %s rport %s generation %s",
			c.Foundation, c.Component, c.Protocol, priority, c.IP, c.Port, c.Type, c.RelAddr, c.RelPort, gen)
	}
	return line
}

// ---- SDP answer -> Jingle session-accept -------------------------------------------------

// parsedAnswer holds just what we need out of pion's local SDP answer to build session-accept.
type parsedAnswer struct {
	ufrag, pwd       string
	fpHash, fpVal    string
	setup            string
	candidatesByMid  map[string][]string // mid -> raw "a=candidate:..." lines (without "a=")
}

// parseSDPAnswer does a minimal, line-oriented scan of pion's generated answer SDP. It does
// not aim to be a general SDP parser — only to pull out the handful of attributes session-
// accept needs (ICE credentials, DTLS fingerprint/setup, and gathered local candidates).
func parseSDPAnswer(sdp string) parsedAnswer {
	pa := parsedAnswer{candidatesByMid: map[string][]string{}}
	lines := strings.Split(strings.ReplaceAll(sdp, "\r\n", "\n"), "\n")
	currentMid := ""
	for _, line := range lines {
		line = strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(line, "a=mid:"):
			currentMid = strings.TrimPrefix(line, "a=mid:")
		case strings.HasPrefix(line, "a=ice-ufrag:") && pa.ufrag == "":
			pa.ufrag = strings.TrimPrefix(line, "a=ice-ufrag:")
		case strings.HasPrefix(line, "a=ice-pwd:") && pa.pwd == "":
			pa.pwd = strings.TrimPrefix(line, "a=ice-pwd:")
		case strings.HasPrefix(line, "a=fingerprint:") && pa.fpHash == "":
			f := strings.TrimPrefix(line, "a=fingerprint:")
			parts := strings.SplitN(f, " ", 2)
			if len(parts) == 2 {
				pa.fpHash, pa.fpVal = parts[0], parts[1]
			}
		case strings.HasPrefix(line, "a=setup:") && pa.setup == "":
			pa.setup = strings.TrimPrefix(line, "a=setup:")
		case strings.HasPrefix(line, "a=candidate:"):
			if currentMid != "" {
				pa.candidatesByMid[currentMid] = append(pa.candidatesByMid[currentMid], strings.TrimPrefix(line, "a="))
			}
		}
	}
	return pa
}

// sdpCandidateToJingleAttrs converts one raw "candidate:..." SDP line back into the XML
// attributes session-accept needs. Returns "" if the line can't be parsed.
func sdpCandidateToJingleXML(raw string, idSeed int) string {
	// candidate:<foundation> <component> <protocol> <priority> <ip> <port> typ <type> [raddr <addr> rport <port>] generation <gen>
	fields := strings.Fields(strings.TrimPrefix(raw, "candidate:"))
	if len(fields) < 7 {
		return ""
	}
	foundation, component, protocol, priority, ip, port := fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]
	typ := ""
	relAddr, relPort, gen := "", "", "0"
	for i := 6; i < len(fields); i++ {
		switch fields[i] {
		case "typ":
			if i+1 < len(fields) {
				typ = fields[i+1]
			}
		case "raddr":
			if i+1 < len(fields) {
				relAddr = fields[i+1]
			}
		case "rport":
			if i+1 < len(fields) {
				relPort = fields[i+1]
			}
		case "generation":
			if i+1 < len(fields) {
				gen = fields[i+1]
			}
		}
	}
	if typ == "" {
		return ""
	}
	id := fmt.Sprintf("buhta%d", idSeed)
	extra := ""
	if relAddr != "" {
		extra += fmt.Sprintf(" rel-addr='%s'", relAddr)
	}
	if relPort != "" {
		extra += fmt.Sprintf(" rel-port='%s'", relPort)
	}
	return fmt.Sprintf(
		`<candidate component='%s' foundation='%s' generation='%s' id='%s' ip='%s' network='0' port='%s' priority='%s' protocol='%s' type='%s'%s/>`,
		component, foundation, gen, id, ip, port, priority, protocol, typ, extra)
}

// BuildTransportInfo renders a minimal Jingle transport-info <iq> carrying just our ICE
// ufrag/pwd/DTLS fingerprint for each content — no payload-types, no SSRCs, no candidates.
// Why this exists: the live trace from run #7 proved a race in the normal session-accept flow:
// pion starts sending real ICE connectivity checks (authenticated with our local ufrag/pwd)
// the instant SetLocalDescription() returns, but we only build+send the session-accept XML
// (the only place the bridge actually learns our ufrag/pwd) *after* waiting for ICE gathering
// to finish — and ICE gathering, now that it only has to gather the TURN relay, finishes in
// well under 2 seconds. The bridge, not yet knowing our credentials, correctly rejected our
// early checks with a STUN error response, and pion's ICE agent only retries a candidate pair
// 7 times (~1.6s) before permanently marking it failed — well before our session-accept (sent
// 4+ seconds later, once gathering actually completed) ever reached it. So: send this the
// moment local ufrag/pwd/fingerprint are fixed (right after SetLocalDescription, before
// waiting on ICE gathering) so the bridge can authenticate our checks from the very first one,
// then send the real session-accept (with candidates) afterwards as before.
func (o *JingleOffer) BuildTransportInfo(answerSDP, toJID, responderJID string) (string, error) {
	pa := parseSDPAnswer(answerSDP)
	if pa.ufrag == "" || pa.pwd == "" {
		return "", fmt.Errorf("could not find local ice-ufrag/ice-pwd in generated answer SDP")
	}
	pa.setup = "passive" // see BuildSessionAccept for why

	var b strings.Builder
	fmt.Fprintf(&b, `<iq to="%s" type="set" xmlns="jabber:client"><jingle action="transport-info" initiator="%s" responder="%s" sid="%s" xmlns="urn:xmpp:jingle:1">`,
		xmlEscape(toJID), xmlEscape(o.Initiator), xmlEscape(responderJID), xmlEscape(o.SID))
	for _, c := range o.Contents {
		fmt.Fprintf(&b, `<content creator="responder" name="%s">`, xmlEscape(c.Name))
		fmt.Fprintf(&b, `<transport pwd="%s" ufrag="%s" xmlns="urn:xmpp:jingle:transports:ice-udp:1">`,
			xmlEscape(pa.pwd), xmlEscape(pa.ufrag))
		if pa.fpHash != "" {
			fmt.Fprintf(&b, `<fingerprint hash="%s" setup="%s" xmlns="urn:xmpp:jingle:apps:dtls:0">%s</fingerprint>`,
				xmlEscape(pa.fpHash), xmlEscape(pa.setup), pa.fpVal)
		}
		fmt.Fprintf(&b, `</transport></content>`)
	}
	fmt.Fprintf(&b, `</jingle></iq>`)
	return b.String(), nil
}

// BuildSessionAccept renders a Jingle session-accept <iq> that mirrors the shape Jicofo
// expects (same per-content structure as the offer: audio/video get an SSRC + <source>, data
// gets an <sctpmap>), using the ICE/DTLS parameters pion actually negotiated in answerSDP.
func (o *JingleOffer) BuildSessionAccept(answerSDP, toJID, responderJID string, audioSSRC, videoSSRC uint32) (string, error) {
	pa := parseSDPAnswer(answerSDP)
	if pa.ufrag == "" || pa.pwd == "" {
		return "", fmt.Errorf("could not find local ice-ufrag/ice-pwd in generated answer SDP")
	}
	// Run #9 proved that declaring "passive" here (so the bridge would be told to go active and
	// send the ClientHello) does NOT work — ICE itself connected cleanly over the relay, but
	// DTLS still sat forever at "[handshake:server] Flight 0: Sending/Waiting", because the
	// real bridge apparently always acts as the DTLS server itself regardless of what our
	// Jingle fingerprint's "setup" attribute says. AcceptAndConnect now forces pion itself to
	// be the DTLS client (SetAnsweringDTLSRole(DTLSRoleClient)), so we assert "active" here to
	// match what pion is actually doing internally.
	pa.setup = "active"

	var b strings.Builder
	fmt.Fprintf(&b, `<iq to="%s" type="set" xmlns="jabber:client"><jingle action="session-accept" initiator="%s" responder="%s" sid="%s" xmlns="urn:xmpp:jingle:1">`,
		xmlEscape(toJID), xmlEscape(o.Initiator), xmlEscape(responderJID), xmlEscape(o.SID))

	if len(o.BundleMids) > 0 {
		fmt.Fprintf(&b, `<group semantics="BUNDLE" xmlns="urn:xmpp:jingle:apps:grouping:0">`)
		for _, m := range o.BundleMids {
			fmt.Fprintf(&b, `<content name="%s"/>`, xmlEscape(m))
		}
		fmt.Fprintf(&b, `</group>`)
	}

	candID := 1
	for _, c := range o.Contents {
		fmt.Fprintf(&b, `<content creator="responder" name="%s"`, xmlEscape(c.Name))
		if c.Name == "video" {
			fmt.Fprintf(&b, ` senders="responder"`)
		} else if c.Name != "data" {
			fmt.Fprintf(&b, ` senders="both"`)
		}
		fmt.Fprintf(&b, `>`)

		if !c.IsSCTP {
			ssrc := audioSSRC
			label := "audio-0"
			if c.Name == "video" {
				ssrc = videoSSRC
				label = "video-0"
			}
			fmt.Fprintf(&b, `<description media="%s"`, xmlEscape(c.Media))
			if ssrc != 0 {
				fmt.Fprintf(&b, ` ssrc="%d"`, ssrc)
			}
			fmt.Fprintf(&b, ` xmlns="urn:xmpp:jingle:apps:rtp:1">`)
			for _, pt := range c.PayloadType {
				fmt.Fprintf(&b, `<payload-type id="%s" name="%s"`, xmlEscape(pt.ID), xmlEscape(pt.Name))
				if pt.Clockrate != "" {
					fmt.Fprintf(&b, ` clockrate="%s"`, xmlEscape(pt.Clockrate))
				}
				if pt.Channels != "" {
					fmt.Fprintf(&b, ` channels="%s"`, xmlEscape(pt.Channels))
				}
				fmt.Fprintf(&b, `>`)
				for _, p := range pt.Params {
					fmt.Fprintf(&b, `<parameter name="%s" value="%s"/>`, xmlEscape(p[0]), xmlEscape(p[1]))
				}
				fmt.Fprintf(&b, `</payload-type>`)
			}
			if ssrc != 0 {
				fmt.Fprintf(&b, `<source name="buhta-%s" ssrc="%d" xmlns="urn:xmpp:jingle:apps:rtp:ssma:0"><parameter name="msid" value="buhta-%s buhta-track-%s"/></source>`,
					label, ssrc, label, label)
			}
			fmt.Fprintf(&b, `<rtcp-mux/></description>`)
		}

		fmt.Fprintf(&b, `<transport pwd="%s" ufrag="%s" xmlns="urn:xmpp:jingle:transports:ice-udp:1">`,
			xmlEscape(pa.pwd), xmlEscape(pa.ufrag))
		if c.IsSCTP {
			fmt.Fprintf(&b, `<sctpmap number="%s" protocol="webrtc-datachannel" streams="1024" xmlns="urn:xmpp:jingle:transports:dtls-sctp:1"/>`,
				xmlEscape(c.SCTPNumber))
		}
		if pa.fpHash != "" {
			fmt.Fprintf(&b, `<fingerprint hash="%s" setup="%s" xmlns="urn:xmpp:jingle:apps:dtls:0">%s</fingerprint>`,
				xmlEscape(pa.fpHash), xmlEscape(pa.setup), pa.fpVal)
		}
		for _, raw := range pa.candidatesByMid[c.Name] {
			if x := sdpCandidateToJingleXML(raw, candID); x != "" {
				b.WriteString(x)
				candID++
			}
		}
		fmt.Fprintf(&b, `</transport></content>`)
	}

	fmt.Fprintf(&b, `</jingle></iq>`)
	return b.String(), nil
}

func xmlEscape(s string) string {
	var b strings.Builder
	if err := xml.EscapeText(&b, []byte(s)); err != nil {
		return s
	}
	return b.String()
}
