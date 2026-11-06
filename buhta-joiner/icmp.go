package main

import (
	"encoding/binary"
	"net"
)

func ipChecksum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i:]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = s&0xffff + s>>16
	}
	return ^uint16(s)
}

// BuildICMPEcho собирает IPv4-пакет с ICMP echo request — для проверки
// туннеля без TUN на стороне клиента (на ПК).
func BuildICMPEcho(src, dst net.IP, id, seq uint16, payload []byte) []byte {
	icmp := make([]byte, 8+len(payload))
	icmp[0] = 8
	binary.BigEndian.PutUint16(icmp[4:], id)
	binary.BigEndian.PutUint16(icmp[6:], seq)
	copy(icmp[8:], payload)
	binary.BigEndian.PutUint16(icmp[2:], ipChecksum(icmp))
	ip := make([]byte, 20+len(icmp))
	ip[0] = 0x45
	binary.BigEndian.PutUint16(ip[2:], uint16(len(ip)))
	ip[8] = 64
	ip[9] = 1
	copy(ip[12:16], src.To4())
	copy(ip[16:20], dst.To4())
	binary.BigEndian.PutUint16(ip[10:], ipChecksum(ip[:20]))
	copy(ip[20:], icmp)
	return ip
}

// ParseICMPEchoReply: id, seq, ok для эхо-ответа (type 0).
func ParseICMPEchoReply(pkt []byte) (src net.IP, id, seq uint16, ok bool) {
	if len(pkt) < 28 || pkt[0]>>4 != 4 || pkt[9] != 1 {
		return
	}
	ihl := int(pkt[0]&0xf) * 4
	if len(pkt) < ihl+8 || pkt[ihl] != 0 {
		return
	}
	return net.IP(append([]byte(nil), pkt[12:16]...)), binary.BigEndian.Uint16(pkt[ihl+4:]), binary.BigEndian.Uint16(pkt[ihl+6:]), true
}
