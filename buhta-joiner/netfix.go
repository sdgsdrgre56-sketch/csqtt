package main

import (
	"net"

	"github.com/pion/transport/v2"
	"github.com/pion/transport/v2/stdnet"
)

// androidNet is a pion transport.Net that never touches netlink. On Android 11+ an app
// cannot call RTM_GETLINK/RTM_GETROUTE, so net.Interfaces() fails with
// "netlinkrib: permission denied" and ICE gathering dies. We report a single synthetic
// interface whose address is found by "connecting" a UDP socket (no packets are sent).
type androidNet struct {
	*stdnet.Net
}

func newAndroidNet() (*androidNet, error) {
	n, err := stdnet.NewNet()
	if err != nil {
		return nil, err
	}
	return &androidNet{n}, nil
}

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
