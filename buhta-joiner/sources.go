// Dynamic remote sources. The bridge announces other participants' media with Jingle
// "source-add" IQs after the initial session-initiate. pion can only route incoming RTP to a
// receiver whose SSRC is named in the remote SDP, so for every new source we append a fresh
// m-section (own mid, own a=ssrc) to the remote description and renegotiate locally. Nothing
// has to be sent back to the bridge: the new sections are receive-only on our side.
package main

import (
	"encoding/xml"
	"fmt"
	"log"
	"regexp"
	"strings"
	"time"

	"github.com/pion/webrtc/v3"
)

type srcItem struct {
	Content string // Jingle content name: "audio" | "video"
	SSRC    string
	Name    string
	MSID    string
}

type srcUpdate struct {
	Action string // "source-add" | "source-remove"
	Items  []srcItem
}

var srcUpdates = make(chan srcUpdate, 64)

type xmlSourceUpdateIQ struct {
	Jingle struct {
		Action   string `xml:"action,attr"`
		Contents []struct {
			Name string `xml:"name,attr"`
			Desc struct {
				Sources []struct {
					SSRC   string `xml:"ssrc,attr"`
					Name   string `xml:"name,attr"`
					Params []struct {
						Name  string `xml:"name,attr"`
						Value string `xml:"value,attr"`
					} `xml:"parameter"`
				} `xml:"source"`
			} `xml:"description"`
		} `xml:"content"`
	} `xml:"jingle"`
}

// queueSourceUpdate inspects an XMPP frame and, if it is a Jingle source-add/remove, queues
// it for runSourceUpdates. Safe to call for any frame.
func queueSourceUpdate(frame string) {
	if !strings.Contains(frame, "source-add") && !strings.Contains(frame, "source-remove") {
		return
	}
	var iq xmlSourceUpdateIQ
	if err := xml.Unmarshal([]byte(frame), &iq); err != nil {
		log.Printf("[src] cannot parse source update: %v", err)
		return
	}
	u := srcUpdate{Action: iq.Jingle.Action}
	for _, c := range iq.Jingle.Contents {
		for _, s := range c.Desc.Sources {
			it := srcItem{Content: c.Name, SSRC: s.SSRC, Name: s.Name}
			for _, p := range s.Params {
				if p.Name == "msid" {
					it.MSID = p.Value
				}
			}
			u.Items = append(u.Items, it)
		}
	}
	if u.Action != "source-add" && u.Action != "source-remove" {
		return
	}
	select {
	case srcUpdates <- u:
	default:
		log.Printf("[src] update queue full, dropping %s", u.Action)
	}
}

var ssrcAttrRe = regexp.MustCompile(`(?m)^a=ssrc:(\d+) `)

// runSourceUpdates applies queued source-add updates to the PeerConnection, one by one.
func runSourceUpdates(pc *webrtc.PeerConnection) {
	known := map[string]bool{}
	if rd := pc.RemoteDescription(); rd != nil {
		for _, m := range ssrcAttrRe.FindAllStringSubmatch(rd.SDP, -1) {
			known[m[1]] = true
		}
	}
	for u := range srcUpdates {
		if u.Action != "source-add" {
			log.Printf("[src] %s ignored (%d sources)", u.Action, len(u.Items))
			continue
		}
		var add []srcItem
		for _, it := range u.Items {
			if it.SSRC == "" || strings.HasPrefix(it.Name, "jvb-") || known[it.SSRC] {
				continue
			}
			add = append(add, it)
		}
		if len(add) == 0 {
			continue
		}
		for i := 0; i < 100 && pc.SignalingState() != webrtc.SignalingStateStable; i++ {
			time.Sleep(100 * time.Millisecond)
		}
		rd := pc.RemoteDescription()
		if rd == nil {
			continue
		}
		newSDP, err := appendSourceSections(rd.SDP, add)
		if err != nil {
			log.Printf("[src] cannot extend remote SDP: %v", err)
			continue
		}
		if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: newSDP}); err != nil {
			log.Printf("[src] SetRemoteDescription failed: %v", err)
			continue
		}
		ans, err := pc.CreateAnswer(nil)
		if err != nil {
			log.Printf("[src] CreateAnswer failed: %v", err)
			continue
		}
		if err := pc.SetLocalDescription(ans); err != nil {
			log.Printf("[src] SetLocalDescription failed: %v", err)
			continue
		}
		for _, it := range add {
			known[it.SSRC] = true
			log.Printf("[src] added remote source ssrc=%s content=%s name=%s", it.SSRC, it.Content, it.Name)
		}
	}
}

var (
	midLineRe   = regexp.MustCompile(`^a=mid:`)
	dirLineRe   = regexp.MustCompile(`^a=(sendrecv|recvonly|sendonly|inactive)$`)
	bundleLineR = regexp.MustCompile(`^a=group:BUNDLE `)
)

// appendSourceSections returns sdp with one extra receive-only m-section per source, cloned
// from the existing section of the same media type.
func appendSourceSections(sdp string, items []srcItem) (string, error) {
	lines := strings.Split(strings.TrimRight(sdp, "\r\n"), "\r\n")
	// Split into session header + m-sections.
	var header []string
	var sections [][]string
	for _, l := range lines {
		if strings.HasPrefix(l, "m=") {
			sections = append(sections, []string{l})
		} else if len(sections) == 0 {
			header = append(header, l)
		} else {
			sections[len(sections)-1] = append(sections[len(sections)-1], l)
		}
	}
	var newMids []string
	for _, it := range items {
		media := it.Content
		if media != "audio" && media != "video" {
			continue
		}
		var tmpl []string
		for _, s := range sections {
			if strings.HasPrefix(s[0], "m="+media+" ") {
				tmpl = s
				break
			}
		}
		if tmpl == nil {
			return "", fmt.Errorf("no m=%s template section", media)
		}
		mid := fmt.Sprintf("src-%s", it.SSRC)
		var sec []string
		for _, l := range tmpl {
			switch {
			case midLineRe.MatchString(l):
				sec = append(sec, "a=mid:"+mid)
			case dirLineRe.MatchString(l):
				sec = append(sec, "a=sendonly")
			case strings.HasPrefix(l, "a=ssrc:"), strings.HasPrefix(l, "a=ssrc-group:"),
				strings.HasPrefix(l, "a=candidate:"), strings.HasPrefix(l, "a=end-of-candidates"),
				strings.HasPrefix(l, "a=msid:"):
				// dropped: replaced below / not needed for bundled sections
			default:
				sec = append(sec, l)
			}
		}
		sec = append(sec, fmt.Sprintf("a=ssrc:%s cname:%s", it.SSRC, it.SSRC))
		if it.MSID != "" {
			sec = append(sec, fmt.Sprintf("a=ssrc:%s msid:%s", it.SSRC, it.MSID))
		}
		sections = append(sections, sec)
		newMids = append(newMids, mid)
	}
	if len(newMids) == 0 {
		return "", fmt.Errorf("nothing to add")
	}
	for i, l := range header {
		if bundleLineR.MatchString(l) {
			header[i] = l + " " + strings.Join(newMids, " ")
		}
	}
	out := append([]string{}, header...)
	for _, s := range sections {
		out = append(out, s...)
	}
	return strings.Join(out, "\r\n") + "\r\n", nil
}
