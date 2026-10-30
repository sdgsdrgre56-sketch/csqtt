package core

// Локальный SOCKS5 (только CONNECT): в него ходят браузер и tun2socks на телефоне.
// Ответ «успех» отдаётся сразу, не дожидаясь сервера: приложение тут же шлёт свои
// первые данные (TLS ClientHello), и они уходят в облако вместе с открытием потока —
// экономится целый круг через облако на каждое соединение.

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"time"
)

func ServeSOCKS(ln net.Listener, c *Client) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go func() {
			addr, err := socksHandshake(conn)
			if err != nil {
				conn.Close()
				return
			}
			if tc, ok := conn.(*net.TCPConn); ok {
				tc.SetNoDelay(true)
			}
			c.Handle(conn, addr)
		}()
	}
}

func socksHandshake(c net.Conn) (string, error) {
	c.SetDeadline(time.Now().Add(15 * time.Second))
	defer c.SetDeadline(time.Time{})
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil {
		return "", err
	}
	if h[0] != 5 {
		return "", errors.New("не SOCKS5")
	}
	methods := make([]byte, h[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", err
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return "", err
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil {
		return "", err
	}
	if req[1] != 1 { // только CONNECT
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", errors.New("команда не поддерживается")
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return "", err
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	default:
		c.Write([]byte{5, 8, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", errors.New("тип адреса не поддерживается")
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(c, pb); err != nil {
		return "", err
	}
	port := binary.BigEndian.Uint16(pb)
	if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
}
