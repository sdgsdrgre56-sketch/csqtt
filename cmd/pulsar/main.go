// Пульсар — свой туннель через облачное хранилище VK.
//
//	pulsar server --box KEY:SECRET@host/bucket/папка [--key-file /etc/pulsar/key]
//	pulsar link   --box ... [--key-file ...] [--name ...]      — напечатать ссылку подключения
//	pulsar client --link pulsar://... [--socks 127.0.0.1:10808] [--resolve host=ip,ip]
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"pulsar/core"
)

var Version = "dev"

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	cmd, args := os.Args[1], os.Args[2:]
	switch cmd {
	case "server":
		server(args)
	case "link":
		link(args)
	case "client":
		client(args)
	case "check":
		check(args)
	case "version":
		fmt.Println(Version)
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "pulsar server|link|client|version — см. --help у команды")
	os.Exit(2)
}

func loadKey(path string, create bool) string {
	if b, err := os.ReadFile(path); err == nil {
		return strings.TrimSpace(string(b))
	}
	if !create {
		fail("нет файла ключа %s", path)
	}
	k := core.NewKey()
	os.MkdirAll(filepath.Dir(path), 0700)
	if err := os.WriteFile(path, []byte(k+"\n"), 0600); err != nil {
		fail("не записать ключ: %v", err)
	}
	return k
}

func fail(f string, a ...any) {
	fmt.Fprintf(os.Stderr, "ОШИБКА: "+f+"\n", a...)
	os.Exit(1)
}

func serverLink(fs *flag.FlagSet, args []string) (core.Link, *string) {
	box := fs.String("box", os.Getenv("PULSAR_BOX"), "облако: KEY:SECRET@host/bucket/папка")
	keyFile := fs.String("key-file", "/etc/pulsar/key", "файл ключа шифрования (создаётся сам)")
	name := fs.String("name", "", "имя подключения")
	fs.Parse(args)
	b, err := core.ParseBox(*box)
	if err != nil {
		fail("облако: %v", err)
	}
	l := core.Link{Box: b.String(), Key: loadKey(*keyFile, true), Name: *name}
	return l, name
}

func link(args []string) {
	l, _ := serverLink(flag.NewFlagSet("link", flag.ExitOnError), args)
	fmt.Println(l.String())
}

func check(args []string) {
	l, _ := serverLink(flag.NewFlagSet("check", flag.ExitOnError), args)
	s, err := core.NewServer(l)
	if err != nil {
		fail("%v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := s.Check(ctx); err != nil {
		fail("облако недоступно: %v", err)
	}
	fmt.Println("облако отвечает: запись, чтение и удаление работают")
}

func server(args []string) {
	l, _ := serverLink(flag.NewFlagSet("server", flag.ExitOnError), args)
	s, err := core.NewServer(l)
	if err != nil {
		fail("%v", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	if err := s.Check(cctx); err != nil {
		cancel()
		fail("облако недоступно: %v", err)
	}
	cancel()
	core.LogHook = func(s string) { fmt.Fprintln(os.Stderr, s) }
	fmt.Fprintf(os.Stderr, "Пульсар %s: сервер запущен, облако отвечает\n", Version)
	s.Run(ctx)
}

func client(args []string) {
	fs := flag.NewFlagSet("client", flag.ExitOnError)
	lk := fs.String("link", os.Getenv("PULSAR_LINK"), "ссылка pulsar://")
	socks := fs.String("socks", "127.0.0.1:10808", "адрес локального SOCKS5")
	resolve := fs.String("resolve", "", "host=ip,ip;host2=ip — адреса облака (для Android)")
	jsonStat := fs.Bool("json", false, "печатать состояние строкой JSON раз в секунду (stdout)")
	stdinWatch := fs.Bool("stdin-watch", false, "выйти, когда закроется stdin (приложение-родитель завершилось)")
	fs.Parse(args)
	if *stdinWatch {
		go func() {
			io.Copy(io.Discard, os.Stdin)
			os.Exit(0)
		}()
	}
	l, err := core.ParseLink(*lk)
	if err != nil {
		fail("%v", err)
	}
	for _, part := range strings.Split(*resolve, ";") {
		if h, ips, ok := strings.Cut(strings.TrimSpace(part), "="); ok {
			core.Resolve[h] = strings.Split(ips, ",")
		}
	}
	c, err := core.NewClient(l)
	if err != nil {
		fail("%v", err)
	}
	ln, err := net.Listen("tcp", *socks)
	if err != nil {
		fail("порт %s занят: %v", *socks, err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	core.LogHook = func(s string) { fmt.Fprintln(os.Stderr, time.Now().Format("15:04:05 ")+s) }
	go c.Run(ctx)
	go core.ServeSOCKS(ln, c)
	fmt.Fprintf(os.Stderr, "Пульсар %s: SOCKS5 на %s\n", Version, ln.Addr())
	go func() { <-ctx.Done(); ln.Close() }()
	t := time.NewTicker(time.Second)
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if *jsonStat {
				b, _ := json.Marshal(c.Stats())
				fmt.Println(string(b))
			}
		}
	}
}
