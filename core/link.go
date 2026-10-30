package core

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// BoxCfg — облачное хранилище, через которое идёт обмен.
type BoxCfg struct {
	Host, Region, Bucket, Prefix, AK, SK string
}

// ParseBox разбирает строку KEY:SECRET@host/bucket/папка[?region=...].
func ParseBox(s string) (BoxCfg, error) {
	s = strings.TrimSpace(s)
	plain := strings.HasPrefix(strings.ToLower(s), "http://")
	s = regexp.MustCompile(`(?i)^(https?|s3)://`).ReplaceAllString(s, "")
	var c BoxCfg
	if i := strings.IndexByte(s, '?'); i >= 0 {
		q, _ := url.ParseQuery(s[i+1:])
		c.Region = q.Get("region")
		s = s[:i]
	}
	at := strings.LastIndexByte(s, '@')
	if at < 0 {
		return c, errors.New("нет ключей: нужно KEY:SECRET@хост/бакет/папка")
	}
	cred, rest := s[:at], s[at+1:]
	col := strings.IndexByte(cred, ':')
	if col <= 0 || col == len(cred)-1 {
		return c, errors.New("ключи нужно записать как KEY:SECRET")
	}
	c.AK, c.SK = cred[:col], cred[col+1:]
	if u, err := url.PathUnescape(c.SK); err == nil {
		c.SK = u
	}
	parts := strings.Split(strings.Trim(rest, "/"), "/")
	if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
		return c, errors.New("нужно хост/бакет/папка")
	}
	c.Host, c.Bucket = parts[0], parts[1]
	// без шифрования TLS — только для проверок на своём компьютере
	if h, _, _ := strings.Cut(c.Host, ":"); plain && (h == "127.0.0.1" || h == "localhost") {
		c.Host = "http://" + c.Host
	}
	c.Prefix = strings.Join(parts[2:], "/")
	if c.Prefix == "" {
		c.Prefix = "pulsar"
	}
	if c.Region == "" {
		switch {
		case strings.Contains(c.Host, "yandexcloud"):
			c.Region = "ru-central1"
		default:
			c.Region = "ru-msk"
		}
	}
	return c, nil
}

func (c BoxCfg) String() string {
	return c.AK + ":" + url.PathEscape(c.SK) + "@" + c.Host + "/" + c.Bucket + "/" + c.Prefix
}

// Link — всё, что нужно клиенту: хранилище, ключ шифрования и имя.
type Link struct {
	Box  string `json:"b"`
	Key  string `json:"k"`
	Name string `json:"n,omitempty"`
}

const linkScheme = "pulsar://"

func (l Link) String() string {
	j, _ := json.Marshal(l)
	return linkScheme + base64.RawURLEncoding.EncodeToString(j)
}

func ParseLink(s string) (Link, error) {
	var l Link
	s = strings.TrimSpace(s)
	if i := strings.Index(s, linkScheme); i >= 0 {
		s = s[i+len(linkScheme):]
	} else {
		return l, errors.New("это не ссылка Пульсара (должна начинаться с pulsar://)")
	}
	s = strings.TrimRight(strings.Fields(s + " ")[0], "=")
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return l, errors.New("ссылка повреждена — скопируйте её целиком")
	}
	if err := json.Unmarshal(b, &l); err != nil {
		return l, errors.New("ссылка повреждена — скопируйте её целиком")
	}
	if _, err := ParseBox(l.Box); err != nil {
		return l, err
	}
	if k, err := base64.RawURLEncoding.DecodeString(l.Key); err != nil || len(k) != 32 {
		return l, errors.New("в ссылке нет ключа шифрования")
	}
	return l, nil
}

func (l Link) psk() []byte { k, _ := base64.RawURLEncoding.DecodeString(l.Key); return k }

func NewKey() string {
	k := make([]byte, 32)
	rand.Read(k)
	return base64.RawURLEncoding.EncodeToString(k)
}

// ---------- шифрование ----------

func derive(psk []byte, label string) []byte {
	m := hmac.New(sha256.New, psk)
	m.Write([]byte("pulsar/v1/" + label))
	return m.Sum(nil)
}

func newAEAD(key []byte) cipher.AEAD {
	b, _ := aes.NewCipher(key)
	a, _ := cipher.NewGCM(b)
	return a
}

// sealer шифрует объекты одного направления одной сессии. Номер объекта — часть nonce,
// поэтому подменить или переставить объекты нельзя.
type sealer struct{ a cipher.AEAD }

func newSealer(psk []byte, sid, dir string) sealer {
	return sealer{newAEAD(derive(psk, "obj/"+sid+"/"+dir))}
}

const objVersion = 1

func (s sealer) seal(seq uint64, plain []byte) []byte {
	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], seq)
	return s.a.Seal([]byte{objVersion}, nonce[:], plain, nil)
}

func (s sealer) open(seq uint64, obj []byte) ([]byte, error) {
	if len(obj) < 1 || obj[0] != objVersion {
		return nil, errors.New("неизвестный формат объекта")
	}
	var nonce [12]byte
	binary.BigEndian.PutUint64(nonce[4:], seq)
	return s.a.Open(nil, nonce[:], obj[1:], nil)
}

// hello — объявление новой сессии клиентом: время, зашифрованное общим ключом.
func sealHello(psk []byte, sid string, t time.Time) []byte {
	a := newAEAD(derive(psk, "hello"))
	nonce := make([]byte, 12)
	rand.Read(nonce)
	pt := binary.BigEndian.AppendUint64(nil, uint64(t.Unix()))
	return a.Seal(nonce, nonce, pt, []byte(sid))
}

func openHello(psk []byte, sid string, b []byte) (time.Time, error) {
	a := newAEAD(derive(psk, "hello"))
	if len(b) < 12+8 {
		return time.Time{}, errors.New("короткий hello")
	}
	pt, err := a.Open(nil, b[:12], b[12:], []byte(sid))
	if err != nil {
		return time.Time{}, fmt.Errorf("чужой ключ: %w", err)
	}
	return time.Unix(int64(binary.BigEndian.Uint64(pt)), 0), nil
}

func encodeKey(k []byte) string { return base64.RawURLEncoding.EncodeToString(k) }
