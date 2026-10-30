package core

// Минимальный клиент S3 (подпись AWS Signature V4) только на стандартной библиотеке:
// PUT, GET, DELETE и ListObjectsV2. Рассчитан на VK Cloud (hb.ru-msk.vkcloud-storage.ru),
// работает и с любым S3-совместимым хранилищем.

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync/atomic"
	"time"
)

type s3Client struct {
	endpoint string // https://host
	host     string
	bucket   string
	region   string
	ak, sk   string
	hc       *http.Client
	now      func() time.Time

	reqs atomic.Int64 // запросов всего (для статистики)
}

var errNotFound = errors.New("s3: not found")

type httpError struct {
	code int
	body string
}

func (e *httpError) Error() string { return fmt.Sprintf("s3: HTTP %d %s", e.code, e.body) }

// Resolve — заранее известные адреса хостов (на Android у Go нет системного DNS:
// приложение само узнаёт IP облака и передаёт их сюда).
var Resolve = map[string][]string{}

func newS3(c BoxCfg) *s3Client {
	d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	tr := &http.Transport{
		Proxy:               nil,
		MaxIdleConns:        256,
		MaxIdleConnsPerHost: 128,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
		TLSClientConfig:     &tls.Config{RootCAs: rootCAs()},
		// HTTP/1.1 с пулом соединений: на мобильной сети одна потеря пакета не
		// тормозит все запросы разом, как в одном соединении HTTP/2.
		TLSNextProto: map[string]func(string, *tls.Conn) http.RoundTripper{},
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err == nil {
				if ips := Resolve[host]; len(ips) > 0 {
					var last error
					for _, i := range rand.Perm(len(ips)) {
						cn, e := d.DialContext(ctx, network, net.JoinHostPort(ips[i], port))
						if e == nil {
							return cn, nil
						}
						last = e
					}
					return nil, last
				}
			}
			return d.DialContext(ctx, network, addr)
		},
	}
	scheme, host := "https://", c.Host
	if strings.HasPrefix(host, "http://") {
		scheme, host = "http://", strings.TrimPrefix(host, "http://")
	}
	return &s3Client{
		endpoint: scheme + host, host: host, bucket: c.Bucket, region: c.Region, ak: c.AK, sk: c.SK,
		hc:  &http.Client{Transport: tr, Timeout: 40 * time.Second},
		now: time.Now,
	}
}

// uriEncode по правилам SigV4: всё, кроме A-Z a-z 0-9 - _ . ~, кодируется; '/' — по флагу
func uriEncode(s string, slash bool) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !slash) {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func hmacSHA(key []byte, s string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(s))
	return m.Sum(nil)
}

func sha256hex(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

// sign вычисляет заголовок Authorization (SigV4).
func sign(method, path string, query url.Values, headers map[string]string, payloadHash, region, ak, sk string, t time.Time) string {
	amzDate := t.UTC().Format("20060102T150405Z")
	day := amzDate[:8]
	keys := make([]string, 0, len(query))
	for k := range query {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var cq []string
	for _, k := range keys {
		vs := append([]string{}, query[k]...)
		sort.Strings(vs)
		for _, v := range vs {
			cq = append(cq, uriEncode(k, true)+"="+uriEncode(v, true))
		}
	}
	hk := make([]string, 0, len(headers))
	lower := map[string]string{}
	for k, v := range headers {
		hk = append(hk, strings.ToLower(k))
		lower[strings.ToLower(k)] = strings.TrimSpace(v)
	}
	sort.Strings(hk)
	var ch strings.Builder
	for _, k := range hk {
		ch.WriteString(k + ":" + lower[k] + "\n")
	}
	signed := strings.Join(hk, ";")
	creq := method + "\n" + uriEncode(path, false) + "\n" + strings.Join(cq, "&") + "\n" + ch.String() + "\n" + signed + "\n" + payloadHash
	scope := day + "/" + region + "/s3/aws4_request"
	sts := "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256hex([]byte(creq))
	k := hmacSHA([]byte("AWS4"+sk), day)
	k = hmacSHA(k, region)
	k = hmacSHA(k, "s3")
	k = hmacSHA(k, "aws4_request")
	sig := hex.EncodeToString(hmacSHA(k, sts))
	return "AWS4-HMAC-SHA256 Credential=" + ak + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + sig
}

func (c *s3Client) do(ctx context.Context, method, key string, query url.Values, body []byte) (*http.Response, []byte, error) {
	c.reqs.Add(1)
	path := "/" + c.bucket
	if key != "" {
		path += "/" + key
	}
	if query == nil {
		query = url.Values{}
	}
	ph := sha256hex(body)
	t := c.now()
	h := map[string]string{
		"host":                 c.host,
		"x-amz-content-sha256": ph,
		"x-amz-date":           t.UTC().Format("20060102T150405Z"),
	}
	u := c.endpoint + uriEncode(path, false)
	if len(query) > 0 {
		var parts []string
		for k, vs := range query {
			for _, v := range vs {
				parts = append(parts, uriEncode(k, true)+"="+uriEncode(v, true))
			}
		}
		sort.Strings(parts)
		u += "?" + strings.Join(parts, "&")
	}
	req, err := http.NewRequestWithContext(ctx, method, u, bytes.NewReader(body))
	if err != nil {
		return nil, nil, err
	}
	req.ContentLength = int64(len(body))
	for k, v := range h {
		if k != "host" {
			req.Header.Set(k, v)
		}
	}
	req.Header.Set("Authorization", sign(method, path, query, h, ph, c.region, c.ak, c.sk, t))
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	rb, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return resp, nil, err
	}
	return resp, rb, nil
}

func (c *s3Client) put(ctx context.Context, key string, body []byte) error {
	resp, rb, err := c.do(ctx, http.MethodPut, key, nil, body)
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return &httpError{resp.StatusCode, short(rb)}
	}
	return nil
}

func (c *s3Client) get(ctx context.Context, key string) ([]byte, error) {
	resp, rb, err := c.do(ctx, http.MethodGet, key, nil, nil)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusNotFound {
		return nil, errNotFound
	}
	if resp.StatusCode/100 != 2 {
		return nil, &httpError{resp.StatusCode, short(rb)}
	}
	return rb, nil
}

func (c *s3Client) del(ctx context.Context, key string) error {
	resp, rb, err := c.do(ctx, http.MethodDelete, key, nil, nil)
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 && resp.StatusCode != http.StatusNotFound {
		return &httpError{resp.StatusCode, short(rb)}
	}
	return nil
}

type listed struct {
	Key string
	Age time.Duration // насколько объект старше ответа сервера
}

type listResult struct {
	Contents []struct {
		Key          string `xml:"Key"`
		LastModified string `xml:"LastModified"`
	} `xml:"Contents"`
	IsTruncated bool `xml:"IsTruncated"`
}

// list возвращает все ключи с префиксом (по возрастанию), не больше max.
func (c *s3Client) list(ctx context.Context, prefix string, max int) ([]listed, error) {
	var out []listed
	after := ""
	for {
		q := url.Values{"list-type": {"2"}, "prefix": {prefix}, "max-keys": {"1000"}}
		if after != "" {
			q.Set("start-after", after)
		}
		resp, rb, err := c.do(ctx, http.MethodGet, "", q, nil)
		if err != nil {
			return nil, err
		}
		if resp.StatusCode/100 != 2 {
			return nil, &httpError{resp.StatusCode, short(rb)}
		}
		var lr listResult
		if err := xml.Unmarshal(rb, &lr); err != nil {
			return nil, err
		}
		now := time.Now()
		if d, err := http.ParseTime(resp.Header.Get("Date")); err == nil {
			now = d
		}
		for _, o := range lr.Contents {
			age := time.Duration(0)
			if t, err := time.Parse(time.RFC3339, o.LastModified); err == nil {
				age = now.Sub(t)
			}
			out = append(out, listed{Key: o.Key, Age: age})
		}
		if !lr.IsTruncated || len(lr.Contents) == 0 || len(out) >= max {
			return out, nil
		}
		after = lr.Contents[len(lr.Contents)-1].Key
	}
}

func short(b []byte) string {
	s := strings.TrimSpace(string(b))
	if i := strings.Index(s, "<Code>"); i >= 0 {
		if j := strings.Index(s[i:], "</Code>"); j > 0 {
			return s[i+6 : i+j]
		}
	}
	if len(s) > 120 {
		s = s[:120]
	}
	return s
}
