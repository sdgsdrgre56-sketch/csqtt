package main

import (
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Client — один человек, которому админ выдал доступ.
type Client struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Token     string  `json:"token"` // 16 байт, hex
	IP        string  `json:"ip"`    // адрес внутри туннеля, 10.77.0.N
	Enabled   bool    `json:"enabled"`
	LimitMbps float64 `json:"limit_mbps"` // 0 = без ограничения
	Created   int64   `json:"created"`
}

type panelAuth struct {
	Salt string `json:"salt"`
	Hash string `json:"hash"`
}

// Store хранит всё, что переживает перезапуск: ключ сервера, клиентов, пароль панели.
type Store struct {
	mu      sync.Mutex
	dir     string
	clients []*Client
	auth    panelAuth
	priv    *ecdh.PrivateKey
	// onChange вызывается после любого изменения списка клиентов.
	onChange func()
	clMod    time.Time // время изменения clients.json, которое мы видели
}

// ReloadClients перечитывает clients.json, если его поменял другой процесс
// (панель живёт только в процессе первой комнаты). true — список обновлён.
func (s *Store) ReloadClients() bool {
	p := filepath.Join(s.dir, "clients.json")
	fi, err := os.Stat(p)
	if err != nil {
		return false
	}
	s.mu.Lock()
	same := fi.ModTime().Equal(s.clMod)
	s.mu.Unlock()
	if same {
		return false
	}
	b, err := os.ReadFile(p)
	if err != nil {
		return false
	}
	var cl []*Client
	if err := json.Unmarshal(b, &cl); err != nil {
		return false
	}
	s.mu.Lock()
	s.clients = cl
	s.clMod = fi.ModTime()
	s.mu.Unlock()
	return true
}

func openStore(dir string) (*Store, string, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, "", err
	}
	s := &Store{dir: dir}
	// ключ сервера
	kp := filepath.Join(dir, "server.key")
	if b, err := os.ReadFile(kp); err == nil {
		raw, err := hex.DecodeString(string(trimNL(b)))
		if err == nil {
			s.priv, err = x25519.NewPrivateKey(raw)
		}
		if err != nil {
			return nil, "", fmt.Errorf("server.key: %w", err)
		}
	} else {
		k, err := x25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, "", err
		}
		s.priv = k
		if err := os.WriteFile(kp, []byte(hex.EncodeToString(k.Bytes())+"\n"), 0o600); err != nil {
			return nil, "", err
		}
	}
	// клиенты
	if b, err := os.ReadFile(filepath.Join(dir, "clients.json")); err == nil {
		if err := json.Unmarshal(b, &s.clients); err != nil {
			return nil, "", fmt.Errorf("clients.json: %w", err)
		}
		if fi, err := os.Stat(filepath.Join(dir, "clients.json")); err == nil {
			s.clMod = fi.ModTime()
		}
	}
	// пароль панели
	firstPass := ""
	ap := filepath.Join(dir, "panel.json")
	if b, err := os.ReadFile(ap); err == nil {
		_ = json.Unmarshal(b, &s.auth)
	}
	if s.auth.Hash == "" {
		firstPass = randWord(12)
		s.setPasswordLocked(firstPass)
	}
	return s, firstPass, nil
}

func trimNL(b []byte) []byte {
	for len(b) > 0 && (b[len(b)-1] == '\n' || b[len(b)-1] == '\r' || b[len(b)-1] == ' ') {
		b = b[:len(b)-1]
	}
	return b
}

const wordChars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func randWord(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	for i := range b {
		b[i] = wordChars[int(b[i])%len(wordChars)]
	}
	return string(b)
}

func hashPass(salt []byte, pw string) []byte {
	h := hmac.New(sha256.New, salt)
	h.Write([]byte(pw))
	sum := h.Sum(nil)
	for i := 0; i < 100000; i++ {
		h.Reset()
		h.Write(sum)
		sum = h.Sum(nil)
	}
	return sum
}

func (s *Store) setPasswordLocked(pw string) {
	salt := make([]byte, 16)
	rand.Read(salt)
	s.auth = panelAuth{Salt: hex.EncodeToString(salt), Hash: hex.EncodeToString(hashPass(salt, pw))}
	b, _ := json.Marshal(s.auth)
	_ = os.WriteFile(filepath.Join(s.dir, "panel.json"), b, 0o600)
}

func (s *Store) SetPassword(pw string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.setPasswordLocked(pw)
}

func (s *Store) CheckPassword(pw string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	salt, err1 := hex.DecodeString(s.auth.Salt)
	want, err2 := hex.DecodeString(s.auth.Hash)
	if err1 != nil || err2 != nil {
		return false
	}
	return subtle.ConstantTimeCompare(hashPass(salt, pw), want) == 1
}

func (s *Store) saveLocked() {
	b, _ := json.MarshalIndent(s.clients, "", "  ")
	tmp := filepath.Join(s.dir, "clients.json.tmp")
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("[store] запись: %v", err)
		return
	}
	_ = os.Rename(tmp, filepath.Join(s.dir, "clients.json"))
}

func (s *Store) notify() {
	if s.onChange != nil {
		s.onChange()
	}
}

func (s *Store) PublicKeyHex() string { return hex.EncodeToString(s.priv.PublicKey().Bytes()) }

func (s *Store) List() []Client {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Client, 0, len(s.clients))
	for _, c := range s.clients {
		out = append(out, *c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Created < out[j].Created })
	return out
}

func (s *Store) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.clients)
}

func (s *Store) Get(id string) (Client, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.clients {
		if c.ID == id {
			return *c, true
		}
	}
	return Client{}, false
}

var ErrNoFreeIP = errors.New("нет свободных адресов (максимум 250 клиентов)")

func (s *Store) Add(name string, limit float64) (Client, error) {
	s.mu.Lock()
	used := map[int]bool{}
	for _, c := range s.clients {
		var a, b, cc, d int
		fmt.Sscanf(c.IP, "%d.%d.%d.%d", &a, &b, &cc, &d)
		used[d] = true
	}
	n := 0
	for i := 2; i < 252; i++ {
		if !used[i] {
			n = i
			break
		}
	}
	if n == 0 {
		s.mu.Unlock()
		return Client{}, ErrNoFreeIP
	}
	tok := make([]byte, 16)
	rand.Read(tok)
	id := make([]byte, 4)
	rand.Read(id)
	c := &Client{
		ID: hex.EncodeToString(id), Name: name, Token: hex.EncodeToString(tok),
		IP: fmt.Sprintf("10.77.0.%d", n), Enabled: true, LimitMbps: limit, Created: time.Now().UnixNano(),
	}
	s.clients = append(s.clients, c)
	s.saveLocked()
	s.mu.Unlock()
	s.notify()
	return *c, nil
}

func (s *Store) Update(id string, f func(c *Client)) (Client, bool) {
	s.mu.Lock()
	var out Client
	found := false
	for _, c := range s.clients {
		if c.ID == id {
			f(c)
			out = *c
			found = true
			break
		}
	}
	if found {
		s.saveLocked()
	}
	s.mu.Unlock()
	if found {
		s.notify()
	}
	return out, found
}

func (s *Store) Delete(id string) bool {
	s.mu.Lock()
	idx := -1
	for i, c := range s.clients {
		if c.ID == id {
			idx = i
		}
	}
	if idx >= 0 {
		s.clients = append(s.clients[:idx], s.clients[idx+1:]...)
		s.saveLocked()
	}
	s.mu.Unlock()
	if idx >= 0 {
		s.notify()
	}
	return idx >= 0
}
