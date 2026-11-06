package main

import (
	"net"

	"github.com/pion/transport/v2"
)

// androidNet is a pion transport.Net that never touches netlink. On Android 11+ an app
// cannot call RTM_GETLINK/RTM_GETROUTE, so net.Interfaces() fails with
// "netlinkrib: permission denied" (stdnet.NewNet calls it too, so we can't wrap stdnet).
// It reports one synthetic interface whose address is found by "connecting" a UDP socket.
type androidNet struct{}

func newAndroidNet() (*androidNet, error) { return &androidNet{}, nil }

func (a *androidNet) ListenPacket(network, address string) (net.PacketConn, error) {
	return net.ListenPacket(network, address)
}

func (a *androidNet) ListenUDP(network string, laddr *net.UDPAddr) (transport.UDPConn, error) {
	c, err := net.ListenUDP(network, laddr)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func (a *androidNet) ListenTCP(network string, laddr *net.TCPAddr) (transport.TCPListener, error) {
	l, err := net.ListenTCP(network, laddr)
	if err != nil {
		return nil, err
	}
	return l, nil
}

func (a *androidNet) Dial(network, address string) (net.Conn, error) {
	return net.Dial(network, address)
}

func (a *androidNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (transport.UDPConn, error) {
	c, err := net.DialUDP(network, laddr, raddr)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func (a *androidNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (transport.TCPConn, error) {
	c, err := net.DialTCP(network, laddr, raddr)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func (a *androidNet) ResolveIPAddr(network, address string) (*net.IPAddr, error) {
	return net.ResolveIPAddr(network, address)
}

func (a *androidNet) ResolveUDPAddr(network, address string) (*net.UDPAddr, error) {
	return net.ResolveUDPAddr(network, address)
}

func (a *androidNet) ResolveTCPAddr(network, address string) (*net.TCPAddr, error) {
	return net.ResolveTCPAddr(network, address)
}

func (a *androidNet) CreateDialer(d *net.Dialer) transport.Dialer { return d }

func (a *androidNet) iface() *transport.Interface {
	ni := transport.NewInterface(net.Interface{Index: 1, MTU: 1400, Name: "wlan0", Flags: net.FlagUp | net.FlagBroadcast})
	if c, err := net.Dial("udp4", "8.8.8.8:53"); err == nil {
		if ua, ok := c.LocalAddr().(*net.UDPAddr); ok {
			ni.AddAddress(&net.IPNet{IP: ua.IP, Mask: net.CIDRMask(24, 32)})
		}
		c.Close()
	}
	return ni
}

func (a *androidNet) Interfaces() ([]*transport.Interface, error) {
	return []*transport.Interface{a.iface()}, nil
}

func (a *androidNet) InterfaceByIndex(index int) (*transport.Interface, error) {
	return a.iface(), nil
}

func (a *androidNet) InterfaceByName(name string) (*transport.Interface, error) {
	return a.iface(), nil
}
