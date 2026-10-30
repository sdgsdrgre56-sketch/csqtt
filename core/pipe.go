package core

// pipe — надёжный упорядоченный канал сообщений в одну сторону и обратно через облако.
// Отправитель пишет объекты out/0000000000000000, …0001, … (каждый — пачка целых кадров),
// получатель читает in/… по порядку и удаляет прочитанное.
//
// Скорость при задержке облака в сотни мс получается из параллельности:
//   - запись: до maxInflight объектов в полёте; пока они летят, новые кадры копятся
//     и уходят следующим, более крупным объектом (мелко при простое, крупно под нагрузкой);
//   - чтение: «следующий» объект опрашивается внахлёст несколькими запросами,
//     а при очереди объектов они читаются наперёд пачкой.

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

var (
	maxInflight = 16
	maxObj      = 2 << 20  // байт в одном объекте
	maxPending  = 24 << 20 // сколько данных может ждать отправки, дальше — подпор
	prefetchMax = 24

	activeGap    = 20 * time.Millisecond
	activeProbes = 3
	warmGap      = 150 * time.Millisecond
	idleGap      = 700 * time.Millisecond
	quietBytes   = 48

	coalesceAt   = 2         // столько объектов в полёте — уже «под нагрузкой»
	coalesceMin  = 512 << 10 // если накопилось меньше — подождать
	coalesceWait = 10 * time.Millisecond
)

type pipe struct {
	s3      *s3Client
	out, in string
	tx, rx  sealer
	onMsg   func([]byte)
	onFatal func(error)

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu       sync.Mutex
	cond     *sync.Cond
	ctrl     [][]byte
	data     [][]byte
	pending  int
	inflight int
	nextSeq  uint64
	closed   bool

	lastTx, lastRx atomic.Int64 // UnixNano
	txBytes        atomic.Int64
	rxBytes        atomic.Int64
	errs           atomic.Value // последняя ошибка облака (string)
}

func newPipe(parent context.Context, s3 *s3Client, out, in string, tx, rx sealer, onMsg func([]byte), onFatal func(error)) *pipe {
	p := &pipe{s3: s3, out: out, in: in, tx: tx, rx: rx, onMsg: onMsg, onFatal: onFatal}
	p.ctx, p.cancel = context.WithCancel(parent)
	p.cond = sync.NewCond(&p.mu)
	now := time.Now().UnixNano()
	p.lastTx.Store(now)
	p.lastRx.Store(now)
	p.wg.Add(2)
	go p.writeLoop()
	go p.readLoop()
	go func() { <-p.ctx.Done(); p.mu.Lock(); p.closed = true; p.cond.Broadcast(); p.mu.Unlock() }()
	return p
}

func (p *pipe) close() {
	p.cancel()
	p.wg.Wait()
}

func objKey(dir string, seq uint64) string { return fmt.Sprintf("%s%016x", dir, seq) }

// send ставит кадр в очередь. Служебные кадры идут первыми и никогда не ждут;
// данные ждут, если в очереди уже много (подпор до источника).
func (p *pipe) send(frame []byte, ctrl bool) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !ctrl {
		for p.pending > maxPending && !p.closed {
			p.cond.Wait()
		}
	}
	if p.closed {
		return errors.New("сессия закрыта")
	}
	if ctrl {
		p.ctrl = append(p.ctrl, frame)
	} else {
		p.data = append(p.data, frame)
	}
	p.pending += len(frame)
	p.cond.Broadcast()
	return nil
}

func (p *pipe) writeLoop() {
	defer p.wg.Done()
	var puts sync.WaitGroup
	defer puts.Wait()
	for {
		p.mu.Lock()
		for !p.closed && ((len(p.ctrl) == 0 && len(p.data) == 0) || p.inflight >= maxInflight) {
			p.cond.Wait()
		}
		if p.closed {
			p.mu.Unlock()
			return
		}
		// Под нагрузкой (объекты уже летят) чуть ждём, чтобы пачка вышла крупнее:
		// меньше объектов — меньше запросов и быстрее чтение на той стороне.
		if p.inflight >= coalesceAt && p.pending < coalesceMin {
			p.mu.Unlock()
			time.Sleep(coalesceWait)
			p.mu.Lock()
			if p.closed {
				p.mu.Unlock()
				return
			}
		}
		body := make([]byte, 0, 4096)
		take := func(q *[][]byte) {
			n := 0
			for n < len(*q) && (len(body) == 0 || len(body)+len((*q)[n]) <= maxObj) {
				body = append(body, (*q)[n]...)
				n++
			}
			*q = (*q)[n:]
		}
		take(&p.ctrl)
		hadData := len(p.data) > 0
		take(&p.data)
		p.pending -= len(body)
		seq := p.nextSeq
		p.nextSeq++
		p.inflight++
		p.cond.Broadcast()
		p.mu.Unlock()

		if hadData {
			p.lastTx.Store(time.Now().UnixNano())
		}
		obj := p.tx.seal(seq, body)
		puts.Add(1)
		go func() {
			defer puts.Done()
			delay := 100 * time.Millisecond
			for {
				err := p.s3.put(p.ctx, objKey(p.out, seq), obj)
				if err == nil {
					p.txBytes.Add(int64(len(obj)))
					break
				}
				if p.ctx.Err() != nil {
					break
				}
				p.noteErr(err)
				select {
				case <-p.ctx.Done():
				case <-time.After(delay):
				}
				if delay < 3*time.Second {
					delay *= 2
				}
			}
			p.mu.Lock()
			p.inflight--
			p.cond.Broadcast()
			p.mu.Unlock()
		}()
	}
}

func (p *pipe) noteErr(err error) {
	if prev, _ := p.errs.Load().(string); prev != err.Error() {
		p.errs.Store(err.Error())
		logf("облако: %v", err)
	}
}

type getRes struct {
	seq   uint64
	probe bool
	data  []byte
	err   error
}

func (p *pipe) readLoop() {
	defer p.wg.Done()
	results := make(chan getRes, 256)
	var next uint64
	got := map[uint64][]byte{}
	probes := map[uint64]int{}       // запросов «следующего» в полёте
	prefetching := map[uint64]bool{} // запросов наперёд в полёте
	missed := map[uint64]bool{}      // наперёд уже спрашивали — не было
	window := 0                      // читать наперёд столько объектов
	lastLaunch := time.Time{}
	lastData := time.Now()
	errUntil := time.Time{}
	launch := func(seq uint64, probe bool) {
		go func() {
			d, err := p.s3.get(p.ctx, objKey(p.in, seq))
			select {
			case results <- getRes{seq, probe, d, err}:
			case <-p.ctx.Done():
			}
		}()
	}
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	for {
		now := time.Now()
		sinceData := now.Sub(lastData)
		sinceTx := now.Sub(time.Unix(0, p.lastTx.Load()))
		// Темп опроса облака: часто — только пока идут данные; в простое — редко,
		// чтобы не забрасывать облако запросами (служебные пинги активностью не считаются).
		gap, maxProbe := idleGap, 1
		switch {
		case sinceData < 3*time.Second || sinceTx < 3*time.Second:
			gap, maxProbe = activeGap, activeProbes
		case sinceData < 20*time.Second || sinceTx < 20*time.Second:
			gap, maxProbe = warmGap, 1
		}
		if now.After(errUntil) {
			for s := next + 1; s <= next+uint64(window); s++ {
				if got[s] == nil && !prefetching[s] && !missed[s] {
					prefetching[s] = true
					launch(s, false)
				}
			}
			if got[next] == nil && probes[next] < maxProbe && now.Sub(lastLaunch) >= gap {
				probes[next]++
				lastLaunch = now
				launch(next, true)
			}
		}
		wait := gap - time.Since(lastLaunch)
		if wait < time.Millisecond {
			wait = time.Millisecond
		}
		if !errUntil.IsZero() && time.Until(errUntil) > wait {
			wait = time.Until(errUntil)
		}
		timer.Reset(wait)
		select {
		case <-p.ctx.Done():
			return
		case <-timer.C:
			continue
		case r := <-results:
			if r.probe {
				if probes[r.seq]--; probes[r.seq] <= 0 {
					delete(probes, r.seq)
				}
			} else {
				delete(prefetching, r.seq)
			}
			switch {
			case r.err == nil:
				if r.seq >= next && got[r.seq] == nil {
					got[r.seq] = r.data
				}
			case errors.Is(r.err, errNotFound):
				if r.seq == next && r.probe {
					window = 0 // догнали отправителя: наперёд не читаем
				} else if !r.probe {
					missed[r.seq] = true
				}
			default:
				if p.ctx.Err() != nil {
					return
				}
				p.noteErr(r.err)
				errUntil = time.Now().Add(500 * time.Millisecond)
			}
		}
		for got[next] != nil {
			obj := got[next]
			delete(got, next)
			delete(missed, next)
			plain, err := p.rx.open(next, obj)
			if err != nil {
				p.onFatal(fmt.Errorf("объект %d не расшифровался: %w", next, err))
				return
			}
			p.rxBytes.Add(int64(len(obj)))
			k := objKey(p.in, next)
			go p.s3.del(context.Background(), k)
			next++
			p.lastRx.Store(time.Now().UnixNano())
			// служебные мелочи (пинг, подтверждения) не будят частый опрос
			if len(plain) > quietBytes {
				lastData = time.Now()
			}
			if window == 0 {
				window = 1
			} else if window < prefetchMax {
				window *= 2
			}
			p.onMsg(plain)
		}
		for s := range missed {
			if s < next {
				delete(missed, s)
			}
		}
	}
}
