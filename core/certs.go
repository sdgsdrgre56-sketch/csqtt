package core

import (
	"crypto/x509"
	"os"
	"path/filepath"
	"sync"
)

// rootCAs: системные сертификаты. Ядро для телефона собрано как обычная программа Linux,
// а Android хранит сертификаты в своих папках — добавляем их сами, иначе HTTPS к облаку
// падает с «certificate signed by unknown authority».
var rootCAs = sync.OnceValue(func() *x509.CertPool {
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	for _, dir := range []string{
		"/apex/com.android.conscrypt/cacerts", // Android 14+
		"/system/etc/security/cacerts",
		"/data/misc/user/0/cacerts-added",
	} {
		files, _ := filepath.Glob(filepath.Join(dir, "*"))
		for _, f := range files {
			if b, err := os.ReadFile(f); err == nil {
				pool.AppendCertsFromPEM(b)
			}
		}
	}
	return pool
})
