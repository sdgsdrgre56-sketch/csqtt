//go:build linux

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

// OpenTUN открывает TUN-интерфейс (нужен root). Адрес и маршруты задаёт
// скрипт server-setup.sh, чтобы не трогать чужие настройки сервера.
func OpenTUN(name string) (*os.File, error) {
	fd, err := syscall.Open("/dev/net/tun", syscall.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	var ifr [40]byte
	copy(ifr[:16], name)
	const iffTun, iffNoPi, tunSetIff = 0x0001, 0x1000, 0x400454ca
	var flags uint16 = iffTun | iffNoPi
	if *tunMQ {
		flags |= 0x0100 // IFF_MULTI_QUEUE: ядро раскладывает ответы по процессам-комнатам по потокам
	}
	*(*uint16)(unsafe.Pointer(&ifr[16])) = flags
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), tunSetIff, uintptr(unsafe.Pointer(&ifr[0]))); e != 0 {
		syscall.Close(fd)
		return nil, fmt.Errorf("TUNSETIFF: %v", e)
	}
	return os.NewFile(uintptr(fd), "/dev/net/tun"), nil
}
