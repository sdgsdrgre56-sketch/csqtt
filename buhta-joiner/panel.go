package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

//go:embed panel.html
var panelHTML []byte

// roomID — UUID комнаты Saby, в которой работает сервер (заполняет main).
var roomID string

// ringLog хранит последние строки журнала для вкладки «Логи».
type ringLog struct {
	mu    sync.Mutex
	lines []string
}

func (r *ringLog) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, l := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if len(l) > 300 {
			l = l[:300]
		}
		r.lines = append(r.lines, l)
	}
	if len(r.lines) > 300 {
		r.lines = r.lines[len(r.lines)-300:]
	}
	return len(p), nil
}

func (r *ringLog) tail(n int) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if n > len(r.lines) {
		n = len(r.lines)
	}
	return append([]string(nil), r.lines[len(r.lines)-n:]...)
}

var logRing = &ringLog{}

func setupLogRing() { log.SetOutput(io.MultiWriter(os.Stderr, logRing)) }

type panel struct {
	st    *Store
	sec   *SecServer
	mu    sync.Mutex
	sess  map[string]time.Time
	fails map[string][]time.Time
}

func startPanel(addr string, st *Store, sec *SecServer, dir string) {
	p := &panel{st: st, sec: sec, sess: map[string]time.Time{}, fails: map[string][]time.Time{}}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(panelHTML)
	})
	mux.HandleFunc("POST /api/login", p.login)
	mux.HandleFunc("POST /api/logout", p.auth(p.logout))
	mux.HandleFunc("GET /api/me", p.auth(func(w http.ResponseWriter, r *http.Request) { jsonOut(w, map[string]any{"ok": true}) }))
	mux.HandleFunc("GET /api/stats", p.auth(p.stats))
	mux.HandleFunc("GET /api/clients", p.auth(func(w http.ResponseWriter, r *http.Request) { jsonOut(w, sec.stats()) }))
	mux.HandleFunc("POST /api/clients", p.auth(p.addClient))
	mux.HandleFunc("PUT /api/clients/{id}", p.auth(p.updateClient))
	mux.HandleFunc("DELETE /api/clients/{id}", p.auth(p.deleteClient))
	mux.HandleFunc("GET /api/clients/{id}/link", p.auth(p.link))
	mux.HandleFunc("GET /api/logs", p.auth(func(w http.ResponseWriter, r *http.Request) { jsonOut(w, logRing.tail(200)) }))
	mux.HandleFunc("POST /api/password", p.auth(p.password))

	cert, err := loadOrMakeCert(dir)
	if err != nil {
		log.Printf("[panel] сертификат: %v", err)
		return
	}
	srv := &http.Server{
		Addr: addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
	}
	go func() {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			log.Printf("[panel] порт %s: %v", addr, err)
			return
		}
		log.Printf("[panel] https://<IP-сервера>%s (логин admin)", addr)
		if err := srv.ServeTLS(ln, "", ""); err != nil {
			log.Printf("[panel] остановлена: %v", err)
		}
	}()
}

func loadOrMakeCert(dir string) (tls.Certificate, error) {
	cp, kp := filepath.Join(dir, "panel.crt"), filepath.Join(dir, "panel.key")
	if c, err := tls.LoadX509KeyPair(cp, kp); err == nil {
		return c, nil
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "buhta-panel"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, err
	}
	kb, _ := x509.MarshalECPrivateKey(key)
	_ = os.WriteFile(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600)
	_ = os.WriteFile(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600)
	return tls.LoadX509KeyPair(cp, kp)
}

func jsonOut(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func jsonErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]any{"error": msg})
}

func clientAddr(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

func (p *panel) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie("buhta_sid")
		if err != nil {
			jsonErr(w, 401, "нужен вход")
			return
		}
		p.mu.Lock()
		exp, ok := p.sess[c.Value]
		if ok && time.Now().After(exp) {
			delete(p.sess, c.Value)
			ok = false
		}
		p.mu.Unlock()
		if !ok {
			jsonErr(w, 401, "нужен вход")
			return
		}
		if r.Method != "GET" && r.Header.Get("X-Buhta") != "1" { // простая защита от CSRF
			jsonErr(w, 403, "запрещено")
			return
		}
		next(w, r)
	}
}

// panelUser — логин входа в панель; задаётся при установке через BUHTA_PANEL_USER.
var panelUser = "admin"

func (p *panel) login(w http.ResponseWriter, r *http.Request) {
	ip := clientAddr(r)
	p.mu.Lock()
	var recent []time.Time
	for _, t := range p.fails[ip] {
		if time.Since(t) < 3*time.Minute {
			recent = append(recent, t)
		}
	}
	p.fails[ip] = recent
	blocked := len(recent) >= 5
	p.mu.Unlock()
	if blocked {
		jsonErr(w, 429, "Подождите 3 минуты.")
		return
	}
	var in struct{ Login, Password string }
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil ||
		in.Login != panelUser || !p.st.CheckPassword(in.Password) {
		p.mu.Lock()
		p.fails[ip] = append(p.fails[ip], time.Now())
		p.mu.Unlock()
		jsonErr(w, 401, "Неверный логин или пароль")
		return
	}
	b := make([]byte, 32)
	rand.Read(b)
	sid := hex.EncodeToString(b)
	p.mu.Lock()
	p.sess[sid] = time.Now().Add(7 * 24 * time.Hour)
	p.mu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: "buhta_sid", Value: sid, Path: "/", HttpOnly: true, Secure: true,
		SameSite: http.SameSiteStrictMode, MaxAge: 7 * 24 * 3600})
	jsonOut(w, map[string]any{"ok": true})
}

func (p *panel) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie("buhta_sid"); err == nil {
		p.mu.Lock()
		delete(p.sess, c.Value)
		p.mu.Unlock()
	}
	jsonOut(w, map[string]any{"ok": true})
}

func (p *panel) stats(w http.ResponseWriter, r *http.Request) {
	list := p.sec.stats()
	var online int
	var in, out uint64
	for _, c := range list {
		if c.Online {
			online++
		}
		in += c.BytesIn
		out += c.BytesOut
	}
	jsonOut(w, map[string]any{
		"uptime_sec": int(time.Since(p.sec.start).Seconds()),
		"clients":    len(list), "online": online,
		"bytes_in": in, "bytes_out": out,
		"room": roomID, "server_key": p.st.PublicKeyHex(),
	})
}

func (p *panel) addClient(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name      string  `json:"name"`
		LimitMbps float64 `json:"limit_mbps"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil {
		jsonErr(w, 400, "неверный запрос")
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	if in.Name == "" || len(in.Name) > 40 {
		jsonErr(w, 400, "имя от 1 до 40 символов")
		return
	}
	if in.LimitMbps < 0 || in.LimitMbps > 10000 {
		jsonErr(w, 400, "неверный лимит скорости")
		return
	}
	c, err := p.st.Add(in.Name, in.LimitMbps)
	if err != nil {
		jsonErr(w, 400, err.Error())
		return
	}
	log.Printf("[panel] добавлен клиент %q (%s)", c.Name, c.IP)
	jsonOut(w, c)
}

func (p *panel) updateClient(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name      *string  `json:"name"`
		Enabled   *bool    `json:"enabled"`
		LimitMbps *float64 `json:"limit_mbps"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil {
		jsonErr(w, 400, "неверный запрос")
		return
	}
	if in.Name != nil {
		n := strings.TrimSpace(*in.Name)
		if n == "" || len(n) > 40 {
			jsonErr(w, 400, "имя от 1 до 40 символов")
			return
		}
		in.Name = &n
	}
	if in.LimitMbps != nil && (*in.LimitMbps < 0 || *in.LimitMbps > 10000) {
		jsonErr(w, 400, "неверный лимит скорости")
		return
	}
	c, ok := p.st.Update(r.PathValue("id"), func(c *Client) {
		if in.Name != nil {
			c.Name = *in.Name
		}
		if in.Enabled != nil {
			c.Enabled = *in.Enabled
		}
		if in.LimitMbps != nil {
			c.LimitMbps = *in.LimitMbps
		}
	})
	if !ok {
		jsonErr(w, 404, "клиент не найден")
		return
	}
	jsonOut(w, c)
}

func (p *panel) deleteClient(w http.ResponseWriter, r *http.Request) {
	if !p.st.Delete(r.PathValue("id")) {
		jsonErr(w, 404, "клиент не найден")
		return
	}
	jsonOut(w, map[string]any{"ok": true})
}

// connLink собирает ключ подключения buhta://… для приложения.
func connLink(c Client, spk string) string {
	b, _ := json.Marshal(map[string]any{"v": 1, "room": roomID, "spk": spk, "tok": c.Token, "n": c.Name})
	return "buhta://" + base64.RawURLEncoding.EncodeToString(b)
}

func (p *panel) link(w http.ResponseWriter, r *http.Request) {
	c, ok := p.st.Get(r.PathValue("id"))
	if !ok {
		jsonErr(w, 404, "клиент не найден")
		return
	}
	jsonOut(w, map[string]any{"link": connLink(c, p.st.PublicKeyHex())})
}

func (p *panel) password(w http.ResponseWriter, r *http.Request) {
	var in struct{ Old, New string }
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil || !p.st.CheckPassword(in.Old) {
		jsonErr(w, 400, "старый пароль неверный")
		return
	}
	if len(in.New) < 8 {
		jsonErr(w, 400, "новый пароль короче 8 символов")
		return
	}
	p.st.SetPassword(in.New)
	jsonOut(w, map[string]any{"ok": true})
}
