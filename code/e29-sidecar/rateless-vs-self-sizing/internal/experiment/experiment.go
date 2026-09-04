package experiment

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/yangl1996/riblt"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/model"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
)

const WireBytesPerCell = 32

type Config struct {
	Arm       string  `json:"arm"`
	Source    string  `json:"source"`
	Target    string  `json:"target"`
	Truth     string  `json:"truth,omitempty"`
	M1        int     `json:"m1,omitempty"`
	Alpha     float64 `json:"alpha,omitempty"`
	Seed1     uint64  `json:"seed1,omitempty"`
	Seed2     uint64  `json:"seed2,omitempty"`
	HardCap   int     `json:"hard_cap,omitempty"`
	VerifySHA bool    `json:"verify_sha256"`
}

type Stage struct {
	Name     string `json:"name"`
	Millis   int64  `json:"millis"`
	Rows     int64  `json:"rows,omitempty"`
	Bytes    int64  `json:"bytes,omitempty"`
	Passes   int    `json:"passes,omitempty"`
	CellWork uint64 `json:"cell_updates,omitempty"`
}

type Result struct {
	Config          Config    `json:"config"`
	MapperVersion   int       `json:"mapper_version"`
	StartedAt       time.Time `json:"started_at"`
	TotalMillis     int64     `json:"total_millis"`
	SourceRows      int64     `json:"source_rows"`
	TargetRows      int64     `json:"target_rows"`
	Stages          []Stage   `json:"stages"`
	Success         bool      `json:"success"`
	TruthChecked    bool      `json:"truth_checked"`
	TruthMatch      bool      `json:"truth_match"`
	Plus            int       `json:"plus"`
	Minus           int       `json:"minus"`
	Residual        int       `json:"residual,omitempty"`
	DHat            float64   `json:"d_hat,omitempty"`
	FinalCapacity   int       `json:"final_capacity"`
	WireBytesSource int64     `json:"wire_bytes_source"`
	WireBytesTarget int64     `json:"wire_bytes_target"`
	PeakHeapAlloc   uint64    `json:"peak_heap_alloc"`
	PeakHeapSys     uint64    `json:"peak_heap_sys"`
	PeakRSSBytes    uint64    `json:"peak_rss_bytes,omitempty"`
	GoGCCount       uint32    `json:"go_gc_count"`
	Error           string    `json:"error,omitempty"`
}

type Truth struct {
	Plus  []model.Symbol `json:"plus"`
	Minus []model.Symbol `json:"minus"`
}

func Run(config Config) (result Result, err error) {
	result.Config = config
	result.MapperVersion = plain.MapperVersion
	result.StartedAt = time.Now().UTC()
	started := time.Now()
	memory := startMemorySampler()
	defer func() {
		result.TotalMillis = time.Since(started).Milliseconds()
		result.PeakHeapAlloc, result.PeakHeapSys, result.PeakRSSBytes, result.GoGCCount = memory.Stop()
		if err != nil {
			result.Error = err.Error()
		}
	}()

	sourceManifest, err := snapshot.Verify(config.Source, config.VerifySHA)
	if err != nil {
		return result, fmt.Errorf("verify source: %w", err)
	}
	targetManifest, err := snapshot.Verify(config.Target, config.VerifySHA)
	if err != nil {
		return result, fmt.Errorf("verify target: %w", err)
	}
	result.SourceRows = sourceManifest.RowCount
	result.TargetRows = targetManifest.RowCount

	var plus, minus []model.Symbol
	switch config.Arm {
	case "ss":
		plus, minus, err = runSelfSizing(config, &result)
	case "ri":
		plus, minus, err = runRatelessIncremental(config, &result)
	case "rf":
		plus, minus, err = runRatelessFixed(config, &result)
	default:
		err = fmt.Errorf("unknown arm %q; use ss, ri, or rf", config.Arm)
	}
	if err != nil {
		return result, err
	}
	result.Plus = len(plus)
	result.Minus = len(minus)
	if config.Truth != "" {
		result.TruthChecked = true
		truth, loadErr := LoadTruth(config.Truth)
		if loadErr != nil {
			return result, loadErr
		}
		result.TruthMatch = equalSymbols(plus, truth.Plus) && equalSymbols(minus, truth.Minus)
		if result.Success && !result.TruthMatch {
			return result, errors.New("decoder reported success but recovered symbols do not match truth")
		}
	}
	return result, nil
}

func runSelfSizing(config Config, result *Result) ([]model.Symbol, []model.Symbol, error) {
	if config.M1 <= plain.K {
		return nil, nil, fmt.Errorf("m1 must exceed %d", plain.K)
	}
	if config.Alpha <= 0 || !isFinite(config.Alpha) {
		return nil, nil, errors.New("alpha must be finite and positive")
	}
	build := func(name, dir string, capacity int, seed uint64) (*plain.Sketch, error) {
		started := time.Now()
		sketch := plain.New(capacity, seed)
		rows, err := snapshot.ForEach(dir, func(record snapshot.Record) error {
			sketch.Insert(record.FP, record.ID)
			return nil
		})
		result.Stages = append(result.Stages, Stage{
			Name: name, Millis: time.Since(started).Milliseconds(), Rows: rows,
			Bytes: rows * snapshot.RecordSize, Passes: 1, CellWork: sketch.Updates,
		})
		return sketch, err
	}

	source1, err := build("ss_build_source_m1", config.Source, config.M1, config.Seed1)
	if err != nil {
		return nil, nil, err
	}
	target1, err := build("ss_build_target_m1", config.Target, config.M1, config.Seed1)
	if err != nil {
		return nil, nil, err
	}
	decodeStarted := time.Now()
	diff1, err := plain.Subtract(source1, target1)
	if err != nil {
		return nil, nil, err
	}
	decoded := diff1.Decode()
	result.Stages = append(result.Stages, Stage{Name: "ss_decode_m1", Millis: time.Since(decodeStarted).Milliseconds()})
	result.DHat = decoded.DHat
	result.FinalCapacity = config.M1
	result.WireBytesSource = int64(config.M1 * WireBytesPerCell)
	result.WireBytesTarget = result.WireBytesSource
	if decoded.Success {
		result.Success = true
		return plainSymbols(decoded.Plus), plainSymbols(decoded.Minus), nil
	}

	m2Value := math.Ceil(config.Alpha * decoded.DHat)
	if !isFinite(m2Value) || m2Value <= plain.K || m2Value > float64(math.MaxInt) {
		result.Residual = decoded.Residual
		return nil, nil, fmt.Errorf("invalid self-sizing m2 from d_hat=%g alpha=%g", decoded.DHat, config.Alpha)
	}
	m2 := int(m2Value)
	source2, err := build("ss_build_source_m2", config.Source, m2, config.Seed2)
	if err != nil {
		return nil, nil, err
	}
	target2, err := build("ss_build_target_m2", config.Target, m2, config.Seed2)
	if err != nil {
		return nil, nil, err
	}
	decodeStarted = time.Now()
	diff2, err := plain.Subtract(source2, target2)
	if err != nil {
		return nil, nil, err
	}
	decoded = diff2.Decode()
	result.Stages = append(result.Stages, Stage{Name: "ss_decode_m2", Millis: time.Since(decodeStarted).Milliseconds()})
	result.Success = decoded.Success
	result.Residual = decoded.Residual
	result.FinalCapacity = m2
	result.WireBytesSource += int64(m2 * WireBytesPerCell)
	result.WireBytesTarget += int64(m2 * WireBytesPerCell)
	return plainSymbols(decoded.Plus), plainSymbols(decoded.Minus), nil
}

func runRatelessIncremental(config Config, result *Result) ([]model.Symbol, []model.Symbol, error) {
	if config.HardCap < 1 {
		return nil, nil, errors.New("ri hard-cap must be positive")
	}
	var source riblt.Encoder[model.Symbol]
	var target riblt.Encoder[model.Symbol]
	ingest := func(name, dir string, encoder *riblt.Encoder[model.Symbol]) error {
		started := time.Now()
		rows, err := snapshot.ForEach(dir, func(record snapshot.Record) error {
			encoder.AddSymbol(model.Symbol{FP: record.FP, ID: record.ID})
			return nil
		})
		result.Stages = append(result.Stages, Stage{
			Name: name, Millis: time.Since(started).Milliseconds(), Rows: rows,
			Bytes: rows * snapshot.RecordSize, Passes: 1,
		})
		return err
	}
	if err := ingest("ri_ingest_source", config.Source, &source); err != nil {
		return nil, nil, err
	}
	if err := ingest("ri_ingest_target", config.Target, &target); err != nil {
		return nil, nil, err
	}
	started := time.Now()
	decoder := riblt.Decoder[model.Symbol]{}
	for prefix := 1; prefix <= config.HardCap; prefix++ {
		a := source.ProduceNextCodedSymbol()
		b := target.ProduceNextCodedSymbol()
		diff := riblt.CodedSymbol[model.Symbol]{
			HashedSymbol: riblt.HashedSymbol[model.Symbol]{
				Symbol: a.Symbol.XOR(b.Symbol),
				Hash:   a.Hash ^ b.Hash,
			},
			Count: a.Count - b.Count,
		}
		decoder.AddCodedSymbol(diff)
		decoder.TryDecode()
		if decoder.Decoded() {
			result.Stages = append(result.Stages, Stage{Name: "ri_generate_decode", Millis: time.Since(started).Milliseconds()})
			result.Success = true
			result.FinalCapacity = prefix
			result.WireBytesSource = int64(prefix * WireBytesPerCell)
			result.WireBytesTarget = result.WireBytesSource
			return hashedSymbols(decoder.Remote()), hashedSymbols(decoder.Local()), nil
		}
	}
	result.Stages = append(result.Stages, Stage{Name: "ri_generate_decode", Millis: time.Since(started).Milliseconds()})
	result.FinalCapacity = config.HardCap
	result.WireBytesSource = int64(config.HardCap * WireBytesPerCell)
	result.WireBytesTarget = result.WireBytesSource
	return hashedSymbols(decoder.Remote()), hashedSymbols(decoder.Local()), nil
}

func runRatelessFixed(config Config, result *Result) ([]model.Symbol, []model.Symbol, error) {
	if config.HardCap < 1 {
		return nil, nil, errors.New("rf hard-cap must be positive")
	}
	source := make(riblt.Sketch[model.Symbol], config.HardCap)
	target := make(riblt.Sketch[model.Symbol], config.HardCap)
	build := func(name, dir string, sketch riblt.Sketch[model.Symbol]) error {
		started := time.Now()
		rows, err := snapshot.ForEach(dir, func(record snapshot.Record) error {
			sketch.AddSymbol(model.Symbol{FP: record.FP, ID: record.ID})
			return nil
		})
		result.Stages = append(result.Stages, Stage{
			Name: name, Millis: time.Since(started).Milliseconds(), Rows: rows,
			Bytes: rows * snapshot.RecordSize, Passes: 1,
		})
		return err
	}
	if err := build("rf_build_source", config.Source, source); err != nil {
		return nil, nil, err
	}
	if err := build("rf_build_target", config.Target, target); err != nil {
		return nil, nil, err
	}
	started := time.Now()
	source.Subtract(target)
	plus, minus, success := source.Decode()
	result.Stages = append(result.Stages, Stage{Name: "rf_decode", Millis: time.Since(started).Milliseconds()})
	result.Success = success
	result.FinalCapacity = config.HardCap
	result.WireBytesSource = int64(config.HardCap * WireBytesPerCell)
	result.WireBytesTarget = result.WireBytesSource
	return hashedSymbols(plus), hashedSymbols(minus), nil
}

func LoadTruth(path string) (Truth, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Truth{}, fmt.Errorf("read truth %s: %w", path, err)
	}
	var truth Truth
	if err := json.Unmarshal(raw, &truth); err != nil {
		return Truth{}, fmt.Errorf("parse truth %s: %w", path, err)
	}
	return truth, nil
}

func plainSymbols(records []plain.Record) []model.Symbol {
	result := make([]model.Symbol, len(records))
	for i, record := range records {
		result[i] = model.Symbol{FP: record.FP, ID: record.ID}
	}
	return result
}

func hashedSymbols(records []riblt.HashedSymbol[model.Symbol]) []model.Symbol {
	result := make([]model.Symbol, len(records))
	for i, record := range records {
		result[i] = record.Symbol
	}
	return result
}

func equalSymbols(a, b []model.Symbol) bool {
	if len(a) != len(b) {
		return false
	}
	left := append([]model.Symbol(nil), a...)
	right := append([]model.Symbol(nil), b...)
	less := func(values []model.Symbol) func(i, j int) bool {
		return func(i, j int) bool {
			if values[i].ID != values[j].ID {
				return values[i].ID < values[j].ID
			}
			return values[i].FP < values[j].FP
		}
	}
	sort.Slice(left, less(left))
	sort.Slice(right, less(right))
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}

func isFinite(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0)
}

type memorySampler struct {
	stop      chan struct{}
	done      chan struct{}
	peakAlloc atomic.Uint64
	peakSys   atomic.Uint64
	gcStart   uint32
}

func startMemorySampler() *memorySampler {
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	sampler := &memorySampler{stop: make(chan struct{}), done: make(chan struct{}), gcStart: stats.NumGC}
	sampler.peakAlloc.Store(stats.HeapAlloc)
	sampler.peakSys.Store(stats.HeapSys)
	go func() {
		defer close(sampler.done)
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				runtime.ReadMemStats(&stats)
				updateMax(&sampler.peakAlloc, stats.HeapAlloc)
				updateMax(&sampler.peakSys, stats.HeapSys)
			case <-sampler.stop:
				return
			}
		}
	}()
	return sampler
}

func (s *memorySampler) Stop() (uint64, uint64, uint64, uint32) {
	close(s.stop)
	<-s.done
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	updateMax(&s.peakAlloc, stats.HeapAlloc)
	updateMax(&s.peakSys, stats.HeapSys)
	return s.peakAlloc.Load(), s.peakSys.Load(), linuxPeakRSS(), stats.NumGC - s.gcStart
}

func updateMax(target *atomic.Uint64, value uint64) {
	for old := target.Load(); value > old && !target.CompareAndSwap(old, value); old = target.Load() {
	}
}

// Linux exposes process high-water RSS in /proc. Other platforms return zero;
// formal H100 runs are Linux and additionally record cgroup memory.peak.
func linuxPeakRSS() uint64 {
	raw, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(raw), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "VmHWM:" {
			kib, err := strconv.ParseUint(fields[1], 10, 64)
			if err == nil {
				return kib * 1024
			}
		}
	}
	return 0
}
