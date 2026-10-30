package core

import (
	"context"
	"io"
	"net"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func get(t *testing.T, r *rig) error {
	resp, err := r.httpc().Get(r.web + "/")
	if err != nil {
		return err
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != "<html>ok</html>" {
		t.Fatalf("ответ %q", b)
	}
	return nil
}

// Сервер перезапустился (потерял сессии) — клиент сам открывает новую.
func TestServerRestart(t *testing.T) {
	cd, sd := clientDead, serverDead
	clientDead, serverDead = 4*time.Second, 10*time.Second
	defer func() { clientDead, serverDead = cd, sd }()
	r := newRig(t, 0.2)
	if err := get(t, r); err != nil {
		t.Fatal(err)
	}
	// «перезапуск»: старый сервер выключаем, новый — с тем же ключом
	r.cancel()
	time.Sleep(300 * time.Millisecond)
	// клиент тоже был в r.cancel — поднимем заново клиент и сервер по отдельности
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go r.cl.Run(ctx)
	time.Sleep(2 * time.Second) // клиент открыл сессию, а сервера нет
	go r.sv.Run(ctx)            // сервер «вернулся» — старая сессия ему неизвестна? (он её примет: hello свежий)
	t0 := time.Now()
	for time.Since(t0) < 20*time.Second {
		if get(t, r) == nil {
			t.Logf("после перезапуска работает через %v", time.Since(t0).Round(time.Millisecond))
			return
		}
	}
	t.Fatal("после перезапуска сервера не восстановилось")
}

// Чужой ключ: сервер не принимает, данных нет.
func TestWrongKey(t *testing.T) {
	r := newRig(t, 0.2)
	f := r.f
	cloud := httptest.NewServer(f)
	defer cloud.Close()
	bad := Link{Box: "http://AK:SK@" + strings.TrimPrefix(cloud.URL, "http://") + "/bkt/pulsar", Key: NewKey()}
	c, _ := NewClient(bad)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go c.Run(ctx)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go ServeSOCKS(ln, c)
	r2 := *r
	r2.socks = ln.Addr().String()
	hc := r2.httpc()
	hc.Timeout = 5 * time.Second
	if _, err := hc.Get(r.web + "/"); err == nil {
		t.Fatal("с чужим ключом открылось")
	}
	if c.Stats().State == "connected" {
		t.Fatal("с чужим ключом «подключено»")
	}
}

// Два устройства через одну папку одновременно.
func TestTwoDevices(t *testing.T) {
	r := newRig(t, 0.2)
	if err := get(t, r); err != nil {
		t.Fatal(err)
	}
	c2, _ := NewClient(r.link)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go c2.Run(ctx)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go ServeSOCKS(ln, c2)
	r2 := *r
	r2.socks = ln.Addr().String()
	for i := 0; i < 3; i++ {
		if err := get(t, r); err != nil {
			t.Fatal("первое устройство:", err)
		}
		if err := get(t, &r2); err != nil {
			t.Fatal("второе устройство:", err)
		}
	}
	if n := r.sv.Sessions(); n != 2 {
		t.Fatalf("сессий на сервере %d, ждали 2", n)
	}
}

// Настоящий перезапуск: сервер «умер» посреди сессии, поднялся новый процесс.
func TestServerProcessRestart(t *testing.T) {
	cd := clientDead
	clientDead = 3 * time.Second
	defer func() { clientDead = cd }()
	pe := pingEvery
	pingEvery = time.Second
	defer func() { pingEvery = pe }()
	f := &fakeVK{objs: map[string][]byte{}, mod: map[string]time.Time{}, slow: 0.2, bwBps: 10e6}
	cloud := httptest.NewServer(f)
	defer cloud.Close()
	r := newRig(t, 0.2)
	if err := get(t, r); err != nil {
		t.Fatal(err)
	}
	// сервер в rig один на ctx — делаем новый сервер с тем же ключом поверх того же облака
	// и «убиваем» старый, отменив только его: для этого в rig отдельный ctx не предусмотрен,
	// поэтому просто запускаем второй экземпляр и проверяем, что клиент после потери
	// старой сессии переподключается к любому живому.
	sv2, _ := NewServer(r.link)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r.sv.mu.Lock()
	for _, ss := range r.sv.sessions {
		ss.cancel() // старая сессия на сервере оборвана, как при падении
	}
	r.sv.mu.Unlock()
	go sv2.Run(ctx)
	t0 := time.Now()
	for time.Since(t0) < 20*time.Second {
		if get(t, r) == nil {
			t.Logf("восстановилось за %v", time.Since(t0).Round(time.Millisecond))
			return
		}
	}
	t.Fatal("не восстановилось")
}
