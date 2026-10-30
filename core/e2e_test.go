package core

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/xml"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Поддельное облако с задержками настоящего VK (замер 30.10 с сети пользователя):
// PUT ~170 мс, GET ~85 мс, «нет такого» ~70 мс, LIST ~120 мс, DELETE ~72 мс,
// плюс время на передачу: ~10 МБ/с на один запрос (1 МБ: PUT 260, GET 220 мс).
type fakeVK struct {
	mu    sync.Mutex
	objs  map[string][]byte
	mod   map[string]time.Time
	reqs  atomic.Int64
	slow  float64 // множитель задержек
	bwBps float64
}

var reqKinds sync.Map

func (f *fakeVK) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.reqs.Add(1)
	{
		k := r.Method
		if r.URL.RawQuery != "" {
			k += " LIST " + r.URL.Query().Get("prefix")
		} else if i := strings.LastIndex(r.URL.Path, "/"); i > 0 {
			k += " " + r.URL.Path[:i]
		}
		v, _ := reqKinds.LoadOrStore(k, new(atomic.Int64))
		v.(*atomic.Int64).Add(1)
	}
	p := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 2)
	key := ""
	if len(p) == 2 {
		key = p[1]
	}
	var body []byte
	if r.Method == "PUT" {
		body, _ = io.ReadAll(r.Body)
	}
	f.mu.Lock()
	obj, ok := f.objs[key]
	f.mu.Unlock()
	d := 85 * time.Millisecond
	size := 0
	switch {
	case r.Method == "PUT":
		d, size = 170*time.Millisecond, len(body)
	case r.Method == "DELETE":
		d = 72 * time.Millisecond
	case key == "":
		d = 120 * time.Millisecond
	case !ok:
		d = 70 * time.Millisecond
	default:
		size = len(obj)
	}
	d = time.Duration(float64(d)*f.slow) + time.Duration(float64(size)/f.bwBps*float64(time.Second))
	time.Sleep(d)
	if !strings.HasPrefix(r.Header.Get("Authorization"), "AWS4-HMAC-SHA256 Credential=AK/") {
		w.WriteHeader(403)
		return
	}
	w.Header().Set("Date", time.Now().UTC().Format(http.TimeFormat))
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case r.Method == "PUT":
		f.objs[key], f.mod[key] = body, time.Now()
	case r.Method == "DELETE":
		delete(f.objs, key)
	case r.Method == "GET" && key == "":
		q := r.URL.Query()
		var ks []string
		for k := range f.objs {
			if strings.HasPrefix(k, q.Get("prefix")) && k > q.Get("start-after") {
				ks = append(ks, k)
			}
		}
		sort.Strings(ks)
		m, _ := strconv.Atoi(q.Get("max-keys"))
		trunc := false
		if m > 0 && len(ks) > m {
			ks, trunc = ks[:m], true
		}
		type c struct {
			Key          string
			LastModified string
		}
		var out struct {
			XMLName     xml.Name `xml:"ListBucketResult"`
			Contents    []c
			IsTruncated bool
		}
		out.IsTruncated = trunc
		for _, k := range ks {
			out.Contents = append(out.Contents, c{k, f.mod[k].UTC().Format(time.RFC3339)})
		}
		xml.NewEncoder(w).Encode(out)
	case r.Method == "GET":
		b, ok := f.objs[key]
		if !ok {
			w.WriteHeader(404)
			return
		}
		w.Write(b)
	}
}

type rig struct {
	f      *fakeVK
	socks  string
	web    string
	cancel context.CancelFunc
	cl     *Client
	link   Link
	sv     *Server
}

func newRig(t *testing.T, slow float64) *rig {
	f := &fakeVK{objs: map[string][]byte{}, mod: map[string]time.Time{}, slow: slow, bwBps: 10e6}
	cloud := httptest.NewServer(f)
	t.Cleanup(cloud.Close)
	// «сайт»: /size/N отдаёт N байт, /echo — эхо тела, / — мелкая страница
	blob := make([]byte, 256<<20)
	rand.Read(blob[:1<<20])
	for i := 1 << 20; i < len(blob); i += 1 << 20 {
		copy(blob[i:], blob[:1<<20])
	}
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/size/") {
			n, _ := strconv.Atoi(strings.TrimPrefix(r.URL.Path, "/size/"))
			w.Header().Set("Content-Length", strconv.Itoa(n))
			w.Write(blob[:n])
			return
		}
		if r.URL.Path == "/upload" {
			n, _ := io.Copy(io.Discard, r.Body)
			fmt.Fprint(w, n)
			return
		}
		fmt.Fprint(w, "<html>ok</html>")
	}))
	t.Cleanup(web.Close)
	l := Link{Box: "http://AK:SK@" + strings.TrimPrefix(cloud.URL, "http://") + "/bkt/pulsar", Key: NewKey()}
	l2, err := ParseLink(l.String())
	if err != nil {
		t.Fatal(err)
	}
	LogHook = func(s string) { t.Log(s) }
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	sv, _ := NewServer(l2)
	go sv.Run(ctx)
	cl, _ := NewClient(l2)
	go cl.Run(ctx)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	go ServeSOCKS(ln, cl)
	t.Cleanup(func() { ln.Close() })
	return &rig{f: f, socks: ln.Addr().String(), web: web.URL, cancel: cancel, cl: cl, sv: sv, link: l2}
}

func (r *rig) httpc() *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			c, err := net.Dial("tcp", r.socks)
			if err != nil {
				return nil, err
			}
			host, port, _ := net.SplitHostPort(addr)
			p, _ := strconv.Atoi(port)
			req := []byte{5, 1, 0, 5, 1, 0, 3, byte(len(host))}
			req = append(req, host...)
			req = append(req, byte(p>>8), byte(p))
			c.Write(req)
			resp := make([]byte, 12)
			if _, err := io.ReadFull(c, resp); err != nil {
				return nil, err
			}
			return c, nil
		},
		DisableKeepAlives: true,
	}, Timeout: 3 * time.Minute}
}

func TestPulsarEndToEnd(t *testing.T) {
	r := newRig(t, 1)
	hc := r.httpc()
	// первая страница (включая открытие сессии)
	t0 := time.Now()
	resp, err := hc.Get(r.web + "/")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != "<html>ok</html>" {
		t.Fatalf("страница: %q", b)
	}
	t.Logf("ПЕРВАЯ СТРАНИЦА (с открытием сессии): %v", time.Since(t0).Round(time.Millisecond))

	// мелкие запросы — задержка «как пинг»
	var lat []time.Duration
	for i := 0; i < 5; i++ {
		t0 := time.Now()
		resp, err := hc.Get(r.web + "/")
		if err != nil {
			t.Fatal(err)
		}
		io.ReadAll(resp.Body)
		resp.Body.Close()
		lat = append(lat, time.Since(t0).Round(time.Millisecond))
	}
	t.Logf("ОТКРЫТИЕ СТРАНИЦЫ (новое соединение + запрос): %v; пинг ядра %d мс", lat, r.cl.Stats().RTTms)

	// одна большая загрузка
	for _, mb := range []int{8, 64} {
		t0 = time.Now()
		resp, err = hc.Get(fmt.Sprintf("%s/size/%d", r.web, mb<<20))
		if err != nil {
			t.Fatal(err)
		}
		n, err := io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		d := time.Since(t0)
		if err != nil || n != int64(mb<<20) {
			t.Fatalf("загрузка %d МБ: %d байт, %v", mb, n, err)
		}
		t.Logf("ЗАГРУЗКА %d МБ одним потоком: %v → %.1f Мбит/с", mb, d.Round(time.Millisecond), float64(n*8)/d.Seconds()/1e6)
	}

	// 4 параллельные (как браузер / видео)
	t0 = time.Now()
	var wg sync.WaitGroup
	var tot atomic.Int64
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			resp, err := hc.Get(fmt.Sprintf("%s/size/%d", r.web, 32<<20))
			if err != nil {
				t.Error(err)
				return
			}
			n, _ := io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			tot.Add(n)
		}()
	}
	wg.Wait()
	d := time.Since(t0)
	t.Logf("4 ПОТОКА по 32 МБ: %v → %.1f Мбит/с", d.Round(time.Millisecond), float64(tot.Load()*8)/d.Seconds()/1e6)

	// отдача (загрузка файла с телефона)
	up := bytes.Repeat([]byte("x"), 16<<20)
	t0 = time.Now()
	resp, err = hc.Post(r.web+"/upload", "application/octet-stream", bytes.NewReader(up))
	if err != nil {
		t.Fatal(err)
	}
	b, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	d = time.Since(t0)
	if string(b) != strconv.Itoa(len(up)) {
		t.Fatalf("отдача: сервер получил %s", b)
	}
	t.Logf("ОТДАЧА 16 МБ: %v → %.1f Мбит/с", d.Round(time.Millisecond), float64(len(up)*8)/d.Seconds()/1e6)
	t.Logf("запросов к облаку всего: %d", r.f.reqs.Load())
}
