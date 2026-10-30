package core

import (
	"sync/atomic"
	"testing"
	"time"
)

// Сколько запросов к облаку в простое (сессия открыта, данных нет).
func TestIdleRequestRate(t *testing.T) {
	r := newRig(t, 0.2)
	if err := get(t, r); err != nil {
		t.Fatal(err)
	}
	time.Sleep(25 * time.Second) // выйти из «тёплого» темпа
	n0 := r.f.reqs.Load()
	reqKinds.Range(func(k, v any) bool { reqKinds.Delete(k); return true })
	time.Sleep(20 * time.Second)
	reqKinds.Range(func(k, v any) bool { t.Logf("  %s: %d", k, v.(*atomic.Int64).Load()); return true })
	rate := float64(r.f.reqs.Load()-n0) / 20
	t.Logf("ПРОСТОЙ: %.1f запросов в секунду на облако (клиент + сервер)", rate)
	if rate > 6 {
		t.Fatalf("слишком много запросов в простое: %.1f/с", rate)
	}
	if err := get(t, r); err != nil {
		t.Fatal("после простоя не открылось:", err)
	}
}
