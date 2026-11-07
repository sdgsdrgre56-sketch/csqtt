package main

import (
	"flag"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// KeepMagic — приманка, которой концентратор «будит» каждый линк-процесс, чтобы
// тот узнал обратный UDP-адрес ещё до первого реального пакета. Первый байт 0x00,
// поэтому это никогда не настоящий IP-пакет (версия 0) — линк её не пересылает в Saby.
var KeepMagic = []byte{0x00, 'B', 'U', 'H', 'T', 'A', 'K', 'A'}

// linksFlag — список локальных UDP-портов линк-процессов (режим client),
// каждый из которых обслуживает одну комнату Saby. Концентратор владеет TUN и
// раскладывает пакеты по этим линкам по кругу, собирая скорость всех комнат.
var linksFlag = flag.String("links", "", "режим concentrator: список 127.0.0.1:port линк-процессов через запятую")

// runConcentrator владеет TUN buhta0 и бондит N линков (по одной комнате на линк).
func runConcentrator(tunName string) {
	ports := splitCSV(*linksFlag)
	if len(ports) == 0 {
		log.Printf("[conc] нет -links")
		os.Exit(2)
	}
	var tun *os.File
	for attempt := 0; attempt < 20; attempt++ {
		t, err := OpenTUN(tunName)
		if err == nil {
			tun = t
			break
		}
		log.Printf("[conc] TUN %s занят (попытка %d): %v", tunName, attempt+1, err)
		time.Sleep(500 * time.Millisecond)
	}
	if tun == nil {
		log.Printf("[conc] TUN %s так и не открылся", tunName)
		os.Exit(1)
	}
	log.Printf("[conc] TUN %s открыт, линков: %d", tunName, len(ports))

	conns := make([]*net.UDPConn, len(ports))
	for i, p := range ports {
		ra, err := net.ResolveUDPAddr("udp", p)
		if err != nil {
			log.Printf("[conc] плохой линк %q: %v", p, err)
			os.Exit(2)
		}
		c, err := net.DialUDP("udp", nil, ra)
		if err != nil {
			log.Printf("[conc] линк %s: %v", p, err)
			os.Exit(1)
		}
		conns[i] = c
	}

	var wmu sync.Mutex
	tunWrite := func(pkt []byte) {
		if len(pkt) < 20 {
			return
		}
		if v := pkt[0] >> 4; v != 4 && v != 6 {
			return // не IP — приманка или мусор
		}
		wmu.Lock()
		tun.Write(pkt)
		wmu.Unlock()
	}

	var up, down uint64
	concBytesIn, concBytesOut = &down, &up

	// Будим линки, чтобы они знали обратный адрес (иначе теряем первый пакет потока).
	go func() {
		for {
			for _, c := range conns {
				c.Write(KeepMagic)
			}
			time.Sleep(5 * time.Second)
		}
	}()

	// Линк -> TUN (трафик из Saby к клиенту «вниз»).
	for _, c := range conns {
		go func(c *net.UDPConn) {
			buf := make([]byte, 4096)
			for {
				n, err := c.Read(buf)
				if err != nil {
					return
				}
				atomic.AddUint64(&down, uint64(n))
				tunWrite(append([]byte(nil), buf[:n]...))
			}
		}(c)
	}

	// TUN -> линки по кругу (трафик от клиента «вверх»).
	var rr uint64
	buf := make([]byte, 4096)
	for {
		n, err := tun.Read(buf)
		if err != nil {
			log.Printf("[conc] чтение TUN: %v", err)
			return
		}
		i := int(atomic.AddUint64(&rr, 1)) % len(conns)
		atomic.AddUint64(&up, uint64(n))
		conns[i].Write(buf[:n])
	}
}

var concBytesIn, concBytesOut *uint64

func splitCSV(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
