package main

import (
	"context"
	"flag"
	"log"
	"net"
	"os"
	"runtime"
	"sync/atomic"
	"time"
)

// Режим клиента для Android-приложения «Бухта»:
//
//	-tunnel client -udp 127.0.0.1:17777
//
// Приложение читает IP-пакеты из TUN (VpnService) и шлёт их UDP-датаграммами
// на этот локальный порт; ответы возвращаются на тот же адрес. Сам процесс
// принадлежит приложению, которое исключено из VPN (addDisallowedApplication),
// поэтому его соединение с мостом Saby не заворачивается обратно в туннель.
var clientUDP = flag.String("udp", "127.0.0.1:17777", "локальный UDP-адрес для приложения (режим client)")

func init() {
	// На Android нет /etc/resolv.conf, и чистый Go-резолвер ничего не находит.
	if runtime.GOOS == "android" {
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				d := net.Dialer{Timeout: 3 * time.Second}
				return d.DialContext(ctx, "udp", "8.8.8.8:53")
			},
		}
	}
}

func runClient(t *Tunnel) {
	addr, err := net.ResolveUDPAddr("udp", *clientUDP)
	if err != nil {
		log.Printf("[status] error bad -udp: %v", err)
		os.Exit(2)
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		log.Printf("[status] error listen: %v", err)
		os.Exit(2)
	}
	var peer atomic.Value // *net.UDPAddr
	t.OnPacket = func(pkt []byte) {
		if p, ok := peer.Load().(*net.UDPAddr); ok {
			conn.WriteToUDP(pkt, p)
		}
	}
	if *clientKey != "" {
		runClientSecure(t, conn, &peer)
		return
	}
	log.Printf("[status] connected")
	buf := make([]byte, 4096)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		peer.Store(from)
		t.SendPacket(append([]byte(nil), buf[:n]...))
	}
}
