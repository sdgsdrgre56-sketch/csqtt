// DION Mayak: local SOCKS5 balancer over several call channels.
//
// Each DION channel is its own joiner process with its own local SOCKS5 port. This program
// listens on one SOCKS5 port (the one tun2socks talks to) and spreads connections over the
// channels that are up: every new TCP connection or UDP association goes to the live channel
// with the fewest active connections. A single download stays on one channel; many parallel
// connections (browsers, speed tests, video) use all channels at once.
//
// stdin commands from the app: "UP <n>", "DOWN <n>" (n is 1-based).
// stdout: "STAT <n>:<state>:<active>:<rxBytes>:<txBytes> ..." every 2 s, other lines are logs.
package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type upstream struct {
	n      int
	addr   string
	up     atomic.Bool
	active atomic.Int64
	rx     atomic.Int64 // bytes towards the phone
	tx     atomic.Int64 // bytes towards the internet
	fails  atomic.Int64 // consecutive failed handshakes
	failAt atomic.Int64 // unix nanos of the last failure
}

func (u *upstream) fail() {
	u.fails.Add(1)
	u.failAt.Store(time.Now().UnixNano())
}

// usable: marked up by the app and not failing; a failing channel gets another try after 20 s.
func (u *upstream) usable() bool {
	if !u.up.Load() {
		return false
	}
	return u.fails.Load() < 2 || time.Since(time.Unix(0, u.failAt.Load())) > 20*time.Second
}

var (
	ups          []*upstream
	upUser       string
	upPass       string
	listenUser   string
	listenPass   string
	rr           atomic.Uint64
	outMu        sync.Mutex
	handshakeTTL = 12 * time.Second
)

func say(format string, args ...any) {
	outMu.Lock()
	fmt.Fprintf(os.Stdout, format+"\n", args...)
	outMu.Unlock()
}

func main() {
	listen := flag.String("listen", "127.0.0.1:10818", "SOCKS5 listen address")
	upList := flag.String("up", "", "comma separated upstream SOCKS5 addresses (channel 1, 2, ...)")
	flag.StringVar(&listenUser, "user", "", "username required from local clients")
	flag.StringVar(&listenPass, "pass", "", "password required from local clients")
	flag.StringVar(&upUser, "up-user", "", "username for upstream channels")
	flag.StringVar(&upPass, "up-pass", "", "password for upstream channels")
	flag.Parse()

	for i, a := range strings.Split(*upList, ",") {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		ups = append(ups, &upstream{n: i + 1, addr: a})
	}
	if len(ups) == 0 {
		say("ERROR: no channels")
		os.Exit(2)
	}

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		say("ERROR: listen %s: %v", *listen, err)
		os.Exit(1)
	}
	say("mux: %d channels, SOCKS5 on %s", len(ups), *listen)

	go readCommands()
	go reportStats()

	for {
		c, err := ln.Accept()
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		go handle(c)
	}
}

func readCommands() {
	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) != 2 {
			continue
		}
		n, err := strconv.Atoi(f[1])
		if err != nil || n < 1 || n > len(ups) {
			continue
		}
		u := ups[n-1]
		switch f[0] {
		case "UP":
			u.fails.Store(0)
			u.up.Store(true)
		case "DOWN":
			u.up.Store(false)
		}
	}
	// The app closed our stdin: it is gone, so are we.
	os.Exit(0)
}

func reportStats() {
	for {
		time.Sleep(2 * time.Second)
		var b strings.Builder
		b.WriteString("STAT")
		for _, u := range ups {
			state := "down"
			if u.up.Load() {
				state = "up"
			}
			fmt.Fprintf(&b, " %d:%s:%d:%d:%d", u.n, state, u.active.Load(), u.rx.Load(), u.tx.Load())
		}
		say("%s", b.String())
	}
}

// candidates returns channels to try, best first: live ones by fewest active connections,
// then (only if none is live) the rest, so traffic still flows while statuses catch up.
func candidates() []*upstream {
	start := int(rr.Add(1))
	var live, rest []*upstream
	for i := range ups {
		u := ups[(start+i)%len(ups)]
		if u.usable() {
			live = append(live, u)
		} else {
			rest = append(rest, u)
		}
	}
	// stable insertion sort by active count
	for i := 1; i < len(live); i++ {
		for j := i; j > 0 && live[j].active.Load() < live[j-1].active.Load(); j-- {
			live[j], live[j-1] = live[j-1], live[j]
		}
	}
	if len(live) > 0 {
		return live
	}
	return rest
}

func readFull(c net.Conn, n int) ([]byte, error) {
	b := make([]byte, n)
	_, err := io.ReadFull(c, b)
	return b, err
}

// clientHandshake reads greeting/auth/request from tun2socks. Returns the raw request bytes.
func clientHandshake(c net.Conn) ([]byte, error) {
	h, err := readFull(c, 2)
	if err != nil || h[0] != 5 {
		return nil, errors.New("bad greeting")
	}
	methods, err := readFull(c, int(h[1]))
	if err != nil {
		return nil, err
	}
	want := byte(0x00)
	if listenUser != "" {
		want = 0x02
	}
	has := false
	for _, m := range methods {
		if m == want {
			has = true
		}
	}
	if !has {
		c.Write([]byte{5, 0xff})
		return nil, errors.New("no acceptable auth")
	}
	c.Write([]byte{5, want})
	if want == 0x02 {
		v, err := readFull(c, 2)
		if err != nil || v[0] != 1 {
			return nil, errors.New("bad auth")
		}
		user, err := readFull(c, int(v[1]))
		if err != nil {
			return nil, err
		}
		pl, err := readFull(c, 1)
		if err != nil {
			return nil, err
		}
		pass, err := readFull(c, int(pl[0]))
		if err != nil {
			return nil, err
		}
		if string(user) != listenUser || string(pass) != listenPass {
			c.Write([]byte{1, 1})
			return nil, errors.New("wrong credentials")
		}
		c.Write([]byte{1, 0})
	}
	hdr, err := readFull(c, 4)
	if err != nil || hdr[0] != 5 {
		return nil, errors.New("bad request")
	}
	var rest []byte
	switch hdr[3] {
	case 1:
		rest, err = readFull(c, 4+2)
	case 4:
		rest, err = readFull(c, 16+2)
	case 3:
		l, e := readFull(c, 1)
		if e != nil {
			return nil, e
		}
		r, e := readFull(c, int(l[0])+2)
		rest, err = append(l, r...), e
	default:
		return nil, errors.New("bad address type")
	}
	if err != nil {
		return nil, err
	}
	return append(hdr, rest...), nil
}

// upstreamOpen connects to a channel and sends the request. Each SOCKS step goes out in one
// write: the joiner reads every step with a single Read.
func upstreamOpen(u *upstream, req []byte) (net.Conn, []byte, error) {
	c, err := net.DialTimeout("tcp", u.addr, 3*time.Second)
	if err != nil {
		return nil, nil, err
	}
	c.SetDeadline(time.Now().Add(handshakeTTL))
	fail := func(e error) (net.Conn, []byte, error) { c.Close(); return nil, nil, e }

	if upUser != "" {
		c.Write([]byte{5, 1, 2})
	} else {
		c.Write([]byte{5, 1, 0})
	}
	g, err := readFull(c, 2)
	if err != nil {
		return fail(err)
	}
	if g[1] == 0x02 {
		a := []byte{1, byte(len(upUser))}
		a = append(a, upUser...)
		a = append(a, byte(len(upPass)))
		a = append(a, upPass...)
		c.Write(a)
		r, err := readFull(c, 2)
		if err != nil || r[1] != 0 {
			return fail(errors.New("channel auth failed"))
		}
	} else if g[1] != 0x00 {
		return fail(errors.New("channel refused auth"))
	}
	c.Write(req)
	rh, err := readFull(c, 4)
	if err != nil {
		return fail(err)
	}
	var tail []byte
	switch rh[3] {
	case 1:
		tail, err = readFull(c, 6)
	case 4:
		tail, err = readFull(c, 18)
	case 3:
		l, e := readFull(c, 1)
		if e != nil {
			return fail(e)
		}
		r, e := readFull(c, int(l[0])+2)
		tail, err = append(l, r...), e
	default:
		return fail(errors.New("bad reply"))
	}
	if err != nil {
		return fail(err)
	}
	c.SetDeadline(time.Time{})
	return c, append(rh, tail...), nil
}

func handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	req, err := clientHandshake(c)
	if err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	switch req[1] {
	case 1:
		handleConnect(c, req)
	case 3:
		handleUDP(c, req)
	default:
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
	}
}

type attempt struct {
	u     *upstream
	conn  net.Conn
	reply []byte
	err   error
}

// handleConnect asks the best channel first; if it has not answered within hedgeDelay
// (a stuck call), the next channel is asked in parallel. The first success wins.
const hedgeDelay = 3 * time.Second

func handleConnect(c net.Conn, req []byte) {
	cands := candidates()
	if len(cands) > 3 {
		cands = cands[:3]
	}
	results := make(chan attempt, len(cands))
	launched, finished := 0, 0
	launch := func() {
		u := cands[launched]
		launched++
		go func() {
			conn, reply, err := upstreamOpen(u, req)
			results <- attempt{u, conn, reply, err}
		}()
	}
	launch()
	var lastReply []byte
	timer := time.NewTimer(hedgeDelay)
	defer timer.Stop()
	for finished < launched || launched < len(cands) {
		select {
		case <-timer.C:
			if launched < len(cands) {
				launch()
				timer.Reset(hedgeDelay)
			}
			continue
		case r := <-results:
			finished++
			ok := r.err == nil && r.reply[1] == 0
			if !ok {
				if r.err != nil || r.reply[1] == 0x01 {
					// No answer or general failure: the channel may be broken.
					r.u.fail()
				} else {
					lastReply = r.reply // the target itself refused; no point in retrying
				}
				if r.conn != nil {
					r.conn.Close()
				}
				if lastReply != nil {
					c.Write(lastReply)
					go drain(results, launched-finished)
					return
				}
				if launched < len(cands) {
					launch()
					timer.Reset(hedgeDelay)
				}
				continue
			}
			r.u.fails.Store(0)
			go drain(results, launched-finished)
			c.Write(r.reply)
			pipe(r.u, c, r.conn)
			return
		}
	}
	c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
}

// drain closes the connections of attempts that lost the race.
func drain(results chan attempt, n int) {
	for i := 0; i < n; i++ {
		r := <-results
		if r.conn != nil {
			r.conn.Close()
		}
	}
}

func pipe(u *upstream, c, uc net.Conn) {
	u.active.Add(1)
	defer u.active.Add(-1)
	defer uc.Close()
	done := make(chan struct{})
	go func() {
		n, _ := io.Copy(uc, c)
		u.tx.Add(n)
		if tc, ok := uc.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
		close(done)
	}()
	n, _ := io.Copy(c, uc)
	u.rx.Add(n)
	if tc, ok := c.(*net.TCPConn); ok {
		tc.CloseWrite()
	}
	<-done
}

// handleUDP: one association = one channel. Datagrams already carry the SOCKS UDP header,
// which is the same on both sides, so they are passed through unchanged.
func handleUDP(c net.Conn, req []byte) {
	var uc net.Conn
	var reply []byte
	var u *upstream
	tried := 0
	for _, cand := range candidates() {
		if tried == 3 {
			break
		}
		tried++
		conn, rep, err := upstreamOpen(cand, req)
		if err != nil || rep[1] != 0 {
			if conn != nil {
				conn.Close()
			}
			cand.fail()
			continue
		}
		cand.fails.Store(0)
		uc, reply, u = conn, rep, cand
		break
	}
	if uc == nil {
		c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer uc.Close()

	relay, err := relayAddr(reply, uc)
	if err != nil {
		c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer local.Close()
	toRelay, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer toRelay.Close()

	lp := local.LocalAddr().(*net.UDPAddr).Port
	rep := []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(rep[8:], uint16(lp))
	c.Write(rep)

	u.active.Add(1)
	defer u.active.Add(-1)

	var client atomic.Pointer[net.UDPAddr]
	stop := make(chan struct{})
	var once sync.Once
	end := func() { once.Do(func() { close(stop); local.Close(); toRelay.Close() }) }

	go func() { // phone -> channel
		buf := make([]byte, 65535)
		for {
			n, from, err := local.ReadFromUDP(buf)
			if err != nil {
				end()
				return
			}
			client.Store(from)
			if _, err := toRelay.Write(buf[:n]); err == nil {
				u.tx.Add(int64(n))
			}
		}
	}()
	go func() { // channel -> phone
		buf := make([]byte, 65535)
		for {
			n, err := toRelay.Read(buf)
			if err != nil {
				end()
				return
			}
			if to := client.Load(); to != nil {
				local.WriteToUDP(buf[:n], to)
				u.rx.Add(int64(n))
			}
		}
	}()
	go func() { // control connections: either side closing ends the association
		b := make([]byte, 64)
		for {
			if _, err := c.Read(b); err != nil {
				end()
				return
			}
		}
	}()
	go func() {
		b := make([]byte, 64)
		for {
			if _, err := uc.Read(b); err != nil {
				end()
				return
			}
		}
	}()
	<-stop
}

func relayAddr(reply []byte, uc net.Conn) (*net.UDPAddr, error) {
	var ip net.IP
	var port int
	switch reply[3] {
	case 1:
		ip = net.IP(reply[4:8])
		port = int(binary.BigEndian.Uint16(reply[8:10]))
	case 4:
		ip = net.IP(reply[4:20])
		port = int(binary.BigEndian.Uint16(reply[20:22]))
	case 3:
		l := int(reply[4])
		host := string(reply[5 : 5+l])
		port = int(binary.BigEndian.Uint16(reply[5+l : 7+l]))
		a, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
		if err != nil {
			return nil, err
		}
		return a, nil
	}
	if ip == nil || ip.IsUnspecified() {
		ip = uc.RemoteAddr().(*net.TCPAddr).IP
	}
	return &net.UDPAddr{IP: ip, Port: port}, nil
}
