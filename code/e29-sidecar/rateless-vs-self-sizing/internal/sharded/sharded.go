// Package sharded implements a deliberate Rateless variant: the set is
// partitioned by the stable symbol hash, each partition owns an independent
// official riblt Encoder, and the corresponding decoders are run in parallel.
// It is not byte-for-byte equivalent to one Encoder over the union; reports
// must label it sharded-RI rather than official RI.
package sharded

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/yangl1996/riblt"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/model"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
)

type Encoder struct {
	Shards []riblt.Encoder[model.Symbol]
}

type Decoder struct {
	Shards []riblt.Decoder[model.Symbol]
}

type BuildMetrics struct {
	Rows   int64
	Millis int64
}

func Build(snapshotDir string, shardCount int) (*Encoder, BuildMetrics, error) {
	if shardCount < 1 {
		return nil, BuildMetrics{}, errors.New("shard count must be positive")
	}
	started := time.Now()
	enc := &Encoder{Shards: make([]riblt.Encoder[model.Symbol], shardCount)}
	queues := make([]chan model.Symbol, shardCount)
	var wg sync.WaitGroup
	for i := range queues {
		queues[i] = make(chan model.Symbol, 1024)
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for symbol := range queues[i] {
				enc.Shards[i].AddSymbol(symbol)
			}
		}(i)
	}
	rows, err := snapshot.ForEach(snapshotDir, func(record snapshot.Record) error {
		symbol := model.Symbol{FP: record.FP, ID: record.ID}
		queues[int(symbol.Hash()%uint64(shardCount))] <- symbol
		return nil
	})
	for _, queue := range queues {
		close(queue)
	}
	wg.Wait()
	if err != nil {
		return nil, BuildMetrics{}, err
	}
	return enc, BuildMetrics{Rows: rows, Millis: time.Since(started).Milliseconds()}, nil
}

func (e *Encoder) ProduceBatch(count int) [][]riblt.CodedSymbol[model.Symbol] {
	if count < 1 {
		panic("sharded encoder batch count must be positive")
	}
	out := make([][]riblt.CodedSymbol[model.Symbol], len(e.Shards))
	var wg sync.WaitGroup
	for i := range e.Shards {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			out[i] = make([]riblt.CodedSymbol[model.Symbol], count)
			for j := range out[i] {
				out[i][j] = e.Shards[i].ProduceNextCodedSymbol()
			}
		}(i)
	}
	wg.Wait()
	return out
}

// ProduceShardBatch advances only one shard.  It is intentionally separate
// from ProduceBatch: independent shard pipelines must not wait at a global
// batch barrier.
func (e *Encoder) ProduceShardBatch(shard, count int) []riblt.CodedSymbol[model.Symbol] {
	if shard < 0 || shard >= len(e.Shards) {
		panic("sharded encoder shard out of range")
	}
	if count < 1 {
		panic("sharded encoder batch count must be positive")
	}
	out := make([]riblt.CodedSymbol[model.Symbol], count)
	for i := range out {
		out[i] = e.Shards[shard].ProduceNextCodedSymbol()
	}
	return out
}

func NewDecoder(shardCount int) (*Decoder, error) {
	if shardCount < 1 {
		return nil, errors.New("shard count must be positive")
	}
	return &Decoder{Shards: make([]riblt.Decoder[model.Symbol], shardCount)}, nil
}

func (d *Decoder) AddBatch(source, target [][]riblt.CodedSymbol[model.Symbol]) error {
	if len(source) != len(d.Shards) || len(target) != len(d.Shards) {
		return fmt.Errorf("shard response mismatch: source=%d target=%d want=%d", len(source), len(target), len(d.Shards))
	}
	for shard := range d.Shards {
		if len(source[shard]) != len(target[shard]) {
			return fmt.Errorf("shard %d batch mismatch: source=%d target=%d", shard, len(source[shard]), len(target[shard]))
		}
		for i := range source[shard] {
			a, b := source[shard][i], target[shard][i]
			d.Shards[shard].AddCodedSymbol(riblt.CodedSymbol[model.Symbol]{
				HashedSymbol: riblt.HashedSymbol[model.Symbol]{
					Symbol: a.Symbol.XOR(b.Symbol), Hash: a.Hash ^ b.Hash,
				},
				Count: a.Count - b.Count,
			})
		}
		d.Shards[shard].TryDecode()
	}
	return nil
}

func (d *Decoder) Decoded() bool {
	for i := range d.Shards {
		if !d.Shards[i].Decoded() {
			return false
		}
	}
	return true
}

func (d *Decoder) Remote() (out []model.Symbol) {
	for i := range d.Shards {
		for _, symbol := range d.Shards[i].Remote() {
			out = append(out, symbol.Symbol)
		}
	}
	return out
}

func (d *Decoder) Local() (out []model.Symbol) {
	for i := range d.Shards {
		for _, symbol := range d.Shards[i].Local() {
			out = append(out, symbol.Symbol)
		}
	}
	return out
}
