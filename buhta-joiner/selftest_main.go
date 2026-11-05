//go:build selftest

// Offline self-test: feeds a real, previously-captured session-initiate XML file through
// ParseOffer -> ToSDPOffer (and, with pion available, through a full PeerConnection) without
// needing any network access. Not part of the normal build (guarded by the "selftest" build
// tag) and not meant to be shipped — just a way to verify the Jingle<->SDP translation logic
// against real server data from this sandbox, where vcall.saby.ru itself is unreachable.
//
// Usage: go run -tags selftest . -selftest-file /tmp/offer_sample.xml
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
)

func init() {
	selftestFile := flag.String("selftest-file", "", "path to a raw session-initiate <iq> XML file; if set, runs the offline translator self-test and exits")
	// We can't call flag.Parse() twice; register this flag before main's flag.Parse() runs
	// by hooking into main via a package-level var checked right after parsing there would
	// require editing main.go. Simpler: parse our own flag set here, independently, scanning
	// os.Args directly, and exit before main's logic (which also calls flag.Parse but with
	// -room required) ever runs.
	for i, a := range os.Args {
		if a == "-selftest-file" && i+1 < len(os.Args) {
			runSelftest(os.Args[i+1])
			os.Exit(0)
		}
	}
	_ = selftestFile
}

func runSelftest(path string) {
	raw, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("selftest: reading %s: %v", path, err)
	}
	offer, err := ParseOffer(string(raw))
	if err != nil {
		log.Fatalf("selftest: ParseOffer: %v", err)
	}
	fmt.Printf("=== parsed offer ===\nsid=%s\ninitiator=%s\nconferenceID(from)=%s\nbundle=%v\ncontents=%d\n",
		offer.SID, offer.Initiator, offer.ConferenceID, offer.BundleMids, len(offer.Contents))
	for _, c := range offer.Contents {
		fmt.Printf("  content %q media=%q senders=%q ufrag=%q pwd=%q fp=%s/%s isSCTP=%v sctpNum=%q payloadTypes=%d candidates=%d\n",
			c.Name, c.Media, c.Senders, c.Ufrag, c.Pwd, c.FpHash, c.FpSetup, c.IsSCTP, c.SCTPNumber, len(c.PayloadType), len(c.Candidates))
		for _, cand := range c.Candidates {
			fmt.Printf("    candidate: %+v\n", cand)
		}
	}

	sdp, err := offer.ToSDPOffer()
	if err != nil {
		log.Fatalf("selftest: ToSDPOffer: %v", err)
	}
	fmt.Println("\n=== generated SDP offer ===")
	fmt.Println(sdp)

	fmt.Println("=== feeding into pion/webrtc PeerConnection (no network reachable here, but exercises SetRemoteDescription/CreateAnswer parsing+ICE-gather-startup) ===")
	acceptXML, pc, err := AcceptAndConnect(offer, "9f63afb0-ca84-4c89-9ef6-f63c69679ac3@meet.jitsi/2CDXgw_5EiYa", nil, nil)
	if err != nil {
		log.Fatalf("selftest: AcceptAndConnect: %v", err)
	}
	defer pc.Close()
	fmt.Println("\n=== generated session-accept XML ===")
	fmt.Println(acceptXML)
}
