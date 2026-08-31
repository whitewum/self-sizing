package distributed

// This package is deliberately small and dependency-free on the wire side. An
// endpoint owns one immutable snapshot and never sees the peer's rows. The
// controller requests sketches/coded-symbol batches, subtracts the two sides,
// and performs decoding outside both endpoint cgroups.

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/yangl1996/riblt"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/estimator"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/model"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/sharded"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
)

const (
	codedWireBytes     = 32
	codedHeaderBytes   = 72
	shardedHeaderBytes = 80
)

type Request struct {
	M      int    `json:"m,omitempty"`
	Seed   uint64 `json:"seed,omitempty"`
	Count  int    `json:"count,omitempty"`
	Shards int    `json:"shards,omitempty"`
	Shard  int    `json:"shard,omitempty"`
	// Prefetch asks an RI endpoint to generate Count-sized batches in a
	// bounded producer queue after prepare. It is intentionally opt-in so the
	// original one-request-at-a-time RI path remains the baseline.
	Prefetch int `json:"prefetch,omitempty"`
}

type EndpointMetrics struct {
	Rows            int64  `json:"rows"`
	Bytes           int64  `json:"bytes"`
	Millis          int64  `json:"millis"`
	Passes          int    `json:"passes"`
	GenerateMillis  int64  `json:"generate_millis,omitempty"`
	VmHWMBytes      int64  `json:"vm_hwm_bytes,omitempty"`
	CgroupPeakBytes int64  `json:"cgroup_peak_bytes,omitempty"`
	CgroupFailcnt   uint64 `json:"cgroup_failcnt,omitempty"`
}

type PlainResponse struct {
	M       int             `json:"m"`
	Seed    uint64          `json:"seed"`
	Count   []int64         `json:"count"`
	FPXor   []uint64        `json:"fp_xor"`
	IDXor   []uint64        `json:"id_xor"`
	ChkXor  []uint64        `json:"chk_xor"`
	Metrics EndpointMetrics `json:"metrics"`
}

type PrepareResponse struct {
	Metrics EndpointMetrics `json:"metrics"`
}

// RIProgress is one controller-side observation after a coded batch has been
// received.  DHat is zero for prefix=1 because C0 is the centering term and
// there are no post-C0 samples yet.  Counts are captured before AddCodedSymbol
// so peeling cannot change the estimator input.
type RIProgress struct {
	Prefix         int     `json:"prefix"`
	C0             int64   `json:"c0"`
	Samples        int     `json:"samples"`
	DHat           float64 `json:"d_hat,omitempty"`
	DecodedSymbols int     `json:"decoded_symbols"`
	Decoded        bool    `json:"decoded"`
	WireBytes      int64   `json:"wire_bytes"`
}

type HorizonMetrics struct {
	Enabled       bool    `json:"enabled"`
	FirstPrefix   int     `json:"first_prefix"`
	PlannedPrefix int     `json:"planned_prefix"`
	DHat          float64 `json:"d_hat"`
	Alpha         float64 `json:"alpha"`
}

type Result struct {
	Arm             string            `json:"arm"`
	StartedAt       time.Time         `json:"started_at"`
	TotalMillis     int64             `json:"total_millis"`
	Success         bool              `json:"success"`
	Plus            int               `json:"plus"`
	Minus           int               `json:"minus"`
	Prefix          int               `json:"prefix,omitempty"`
	WireBytesSource int64             `json:"wire_bytes_source"`
	WireBytesTarget int64             `json:"wire_bytes_target"`
	Shards          int               `json:"shards,omitempty"`
	ShardPrefixes   []int             `json:"shard_prefixes,omitempty"`
	EndpointSource  []EndpointMetrics `json:"endpoint_source,omitempty"`
	EndpointTarget  []EndpointMetrics `json:"endpoint_target,omitempty"`
	Batch           int               `json:"batch,omitempty"`
	RIProgress      []RIProgress      `json:"ri_progress,omitempty"`
	Horizon         *HorizonMetrics   `json:"horizon,omitempty"`
	Error           string            `json:"error,omitempty"`
}

type ControllerOptions struct {
	PrefetchBatches int
	Horizon         bool
	HorizonAlpha    float64
	HorizonFirst    int
	Shards          int
}

type Endpoint struct {
	Snapshot     string
	mu           sync.Mutex
	encoder      *riblt.Encoder[model.Symbol]
	sharded      *sharded.Encoder
	shardCount   int
	queue        chan codedBatch
	stop         chan struct{}
	producerDone chan struct{}
}

type codedBatch struct {
	cells          []riblt.CodedSymbol[model.Symbol]
	err            error
	generateMillis int64
}

func (e *Endpoint) Serve(addr string) error {
	if _, err := snapshot.Verify(e.Snapshot, false); err != nil {
		return fmt.Errorf("verify snapshot: %w", err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, map[string]any{"ok": true}) })
	mux.HandleFunc("/ss/build", e.handlePlain)
	mux.HandleFunc("/rf/build", e.handleFixed)
	mux.HandleFunc("/ri/prepare", e.handlePrepare)
	mux.HandleFunc("/ri/next", e.handleNext)
	mux.HandleFunc("/ri/stop", e.handleStop)
	mux.HandleFunc("/sri/prepare", e.handleShardedPrepare)
	mux.HandleFunc("/sri/next", e.handleShardedNext)
	mux.HandleFunc("/sri/next-shard/", e.handleShardedNextShard)
	mux.HandleFunc("/sri/stop", e.handleShardedStop)
	return (&http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 30 * time.Second}).ListenAndServe()
}

func (e *Endpoint) handlePlain(w http.ResponseWriter, r *http.Request) {
	var req Request
	if err := readJSON(r, &req); err != nil {
		writeError(w, err)
		return
	}
	if req.M <= plain.K {
		writeError(w, errors.New("m must exceed plain k"))
		return
	}
	started := time.Now()
	sketch := plain.New(req.M, req.Seed)
	rows, err := snapshot.ForEach(e.Snapshot, func(record snapshot.Record) error {
		sketch.Insert(record.FP, record.ID)
		return nil
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, PlainResponse{M: sketch.M, Seed: sketch.Seed, Count: sketch.Count,
		FPXor: sketch.FPXor, IDXor: sketch.IDXor, ChkXor: sketch.ChkXor,
		Metrics: endpointMetrics(EndpointMetrics{Rows: rows, Bytes: rows * snapshot.RecordSize,
			Millis: time.Since(started).Milliseconds(), Passes: 1})})
}

func (e *Endpoint) handleFixed(w http.ResponseWriter, r *http.Request) {
	var req Request
	if err := readJSON(r, &req); err != nil {
		writeError(w, err)
		return
	}
	if req.M < 1 {
		writeError(w, errors.New("m must be positive"))
		return
	}
	started := time.Now()
	sketch := make(riblt.Sketch[model.Symbol], req.M)
	rows, err := snapshot.ForEach(e.Snapshot, func(record snapshot.Record) error {
		sketch.AddSymbol(model.Symbol{FP: record.FP, ID: record.ID})
		return nil
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeCoded(w, sketch, endpointMetrics(EndpointMetrics{Rows: rows, Bytes: rows * snapshot.RecordSize,
		Millis: time.Since(started).Milliseconds(), GenerateMillis: time.Since(started).Milliseconds(), Passes: 1}))
}

func (e *Endpoint) handlePrepare(w http.ResponseWriter, r *http.Request) {
	var req Request
	if err := readJSON(r, &req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, err)
		return
	}
	// Re-prepare is also an alternating-arm boundary.  Wait for any old
	// producer to finish before allocating the next O(N) encoder.
	e.stopRI()
	e.stopSharded()
	started := time.Now()
	var encoder riblt.Encoder[model.Symbol]
	rows, err := snapshot.ForEach(e.Snapshot, func(record snapshot.Record) error {
		encoder.AddSymbol(model.Symbol{FP: record.FP, ID: record.ID})
		return nil
	})
	if err != nil {
		writeError(w, err)
		return
	}
	e.mu.Lock()
	e.encoder = &encoder
	e.queue = nil
	e.stop = nil
	e.producerDone = nil
	if req.Prefetch > 0 && req.Count > 0 {
		if req.Count > 1<<20 || req.Prefetch > 1024 {
			e.mu.Unlock()
			writeError(w, errors.New("invalid RI prefetch/count"))
			return
		}
		e.queue = make(chan codedBatch, req.Prefetch)
		e.stop = make(chan struct{})
		e.producerDone = make(chan struct{})
		go e.produceBatches(&encoder, req.Count, e.queue, e.stop, e.producerDone)
	}
	e.mu.Unlock()
	writeJSON(w, PrepareResponse{Metrics: endpointMetrics(EndpointMetrics{Rows: rows, Bytes: rows * snapshot.RecordSize,
		Millis: time.Since(started).Milliseconds(), Passes: 1})})
}

func (e *Endpoint) handleNext(w http.ResponseWriter, r *http.Request) {
	var req Request
	if err := readJSON(r, &req); err != nil {
		writeError(w, err)
		return
	}
	if req.Count < 1 || req.Count > 1<<20 {
		writeError(w, errors.New("count must be 1..1048576"))
		return
	}
	e.mu.Lock()
	queue := e.queue
	encoder := e.encoder
	e.mu.Unlock()
	if queue != nil {
		batch, ok := <-queue
		if !ok {
			writeError(w, errors.New("RI prefetch producer stopped"))
			return
		}
		if batch.err != nil {
			writeError(w, batch.err)
			return
		}
		if len(batch.cells) != req.Count {
			writeError(w, fmt.Errorf("prefetch batch length=%d, requested=%d", len(batch.cells), req.Count))
			return
		}
		writeCoded(w, batch.cells, endpointMetrics(EndpointMetrics{GenerateMillis: batch.generateMillis}))
		return
	}
	if encoder == nil {
		writeError(w, errors.New("ri endpoint is not prepared"))
		return
	}
	started := time.Now()
	e.mu.Lock()
	defer e.mu.Unlock()
	cells := make([]riblt.CodedSymbol[model.Symbol], req.Count)
	for i := range cells {
		cells[i] = encoder.ProduceNextCodedSymbol()
	}
	writeCoded(w, cells, endpointMetrics(EndpointMetrics{Millis: time.Since(started).Milliseconds(), GenerateMillis: time.Since(started).Milliseconds()}))
}

func (e *Endpoint) handleStop(w http.ResponseWriter, _ *http.Request) {
	e.stopRI()
	e.stopSharded()
	writeJSON(w, map[string]any{"ok": true, "metrics": endpointMetrics(EndpointMetrics{})})
}

func (e *Endpoint) handleShardedPrepare(w http.ResponseWriter, r *http.Request) {
	var req Request
	if err := readJSON(r, &req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, err)
		return
	}
	if req.Shards < 1 || req.Shards > 128 {
		writeError(w, errors.New("shards must be 1..128"))
		return
	}
	e.stopRI()
	e.stopSharded()
	started := time.Now()
	encoder, metrics, err := sharded.Build(e.Snapshot, req.Shards)
	if err != nil {
		writeError(w, err)
		return
	}
	e.mu.Lock()
	e.sharded = encoder
	e.shardCount = req.Shards
	e.mu.Unlock()
	writeJSON(w, PrepareResponse{Metrics: endpointMetrics(EndpointMetrics{
		Rows: metrics.Rows, Bytes: metrics.Rows * snapshot.RecordSize,
		Millis: time.Since(started).Milliseconds(), Passes: 1,
	})})
}

func (e *Endpoint) handleShardedNext(w http.ResponseWriter, r *http.Request) {
	e.handleShardedNextFor(w, r, -1)
}

func (e *Endpoint) handleShardedNextShard(w http.ResponseWriter, r *http.Request) {
	shard, err := strconv.Atoi(strings.TrimPrefix(r.URL.Path, "/sri/next-shard/"))
	if err != nil {
		writeError(w, errors.New("invalid shard"))
		return
	}
	e.handleShardedNextFor(w, r, shard)
}

func (e *Endpoint) handleShardedNextFor(w http.ResponseWriter, r *http.Request, requestedShard int) {
	var req Request
	if err := readJSON(r, &req); err != nil {
		writeError(w, err)
		return
	}
	if req.Count < 1 || req.Count > 1<<20 {
		writeError(w, errors.New("count must be 1..1048576"))
		return
	}
	e.mu.Lock()
	encoder, shards := e.sharded, e.shardCount
	e.mu.Unlock()
	if encoder == nil || shards < 1 {
		writeError(w, errors.New("sharded RI endpoint is not prepared"))
		return
	}
	if requestedShard >= shards {
		writeError(w, errors.New("shard out of range"))
		return
	}
	started := time.Now()
	if requestedShard < 0 {
		cells := encoder.ProduceBatch(req.Count)
		millis := time.Since(started).Milliseconds()
		writeSharded(w, cells, endpointMetrics(EndpointMetrics{Millis: millis, GenerateMillis: millis}))
		return
	}
	cells := encoder.ProduceShardBatch(requestedShard, req.Count)
	millis := time.Since(started).Milliseconds()
	writeCoded(w, cells, endpointMetrics(EndpointMetrics{Millis: millis, GenerateMillis: millis}))
}

func (e *Endpoint) handleShardedStop(w http.ResponseWriter, _ *http.Request) {
	e.stopSharded()
	writeJSON(w, map[string]any{"ok": true, "metrics": endpointMetrics(EndpointMetrics{})})
}

func (e *Endpoint) stopSharded() {
	e.mu.Lock()
	e.sharded = nil
	e.shardCount = 0
	e.mu.Unlock()
	runtime.GC()
	debug.FreeOSMemory()
}

func (e *Endpoint) stopRI() {
	e.mu.Lock()
	stop := e.stop
	done := e.producerDone
	if e.stop != nil {
		close(stop)
	}
	e.stop = nil
	e.queue = nil
	e.producerDone = nil
	e.encoder = nil
	e.mu.Unlock()
	if done != nil {
		<-done
	}
	// The endpoint is deliberately reusable across alternating arms.  Force a
	// collection here so the next arm does not inherit the previous RI heap in
	// its RSS measurement.  This is only a lifecycle boundary, not a timed path.
	runtime.GC()
	debug.FreeOSMemory()
}

func (e *Endpoint) produceBatches(encoder *riblt.Encoder[model.Symbol], count int, queue chan<- codedBatch, stop <-chan struct{}, done chan<- struct{}) {
	defer close(done)
	defer close(queue)
	for {
		started := time.Now()
		cells := make([]riblt.CodedSymbol[model.Symbol], count)
		for i := range cells {
			cells[i] = encoder.ProduceNextCodedSymbol()
		}
		select {
		case queue <- codedBatch{cells: cells, generateMillis: time.Since(started).Milliseconds()}:
		case <-stop:
			return
		}
	}
}

func endpointMetrics(metrics EndpointMetrics) EndpointMetrics {
	metrics.VmHWMBytes, metrics.CgroupPeakBytes, metrics.CgroupFailcnt = memoryMetrics()
	return metrics
}

func memoryMetrics() (vmhwm, cgroupPeak int64, failcnt uint64) {
	if raw, err := os.ReadFile("/proc/self/status"); err == nil {
		for _, line := range strings.Split(string(raw), "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 2 && fields[0] == "VmHWM:" {
				if value, err := strconv.ParseInt(fields[1], 10, 64); err == nil {
					vmhwm = value * 1024
				}
			}
		}
	}
	for _, dir := range cgroupMemoryDirs() {
		if cgroupPeak == 0 {
			cgroupPeak = readInt64(filepath.Join(dir, "memory.max_usage_in_bytes"))
			if cgroupPeak == 0 {
				cgroupPeak = readInt64(filepath.Join(dir, "memory.peak"))
			}
		}
		if failcnt == 0 {
			failcnt = uint64(readInt64(filepath.Join(dir, "memory.failcnt")))
		}
		if cgroupPeak != 0 || failcnt != 0 {
			break
		}
	}
	return vmhwm, cgroupPeak, failcnt
}

func cgroupMemoryDirs() []string {
	return []string{
		"/sys/fs/cgroup/memory",
		"/sys/fs/cgroup",
	}
}

func readInt64(path string) int64 {
	raw, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	value, err := strconv.ParseInt(strings.TrimSpace(string(raw)), 10, 64)
	if err != nil || value < 0 {
		return 0
	}
	return value
}

func RunController(arm, sourceURL, targetURL string, m1 int, alpha float64, seed1, seed2 uint64, hardCap, batch int, timeout time.Duration) (result Result, err error) {
	return RunControllerWithOptions(arm, sourceURL, targetURL, m1, alpha, seed1, seed2, hardCap, batch, ControllerOptions{}, timeout)
}

// RunControllerWithPrefetch is the opt-in controller entry point for the
// bounded RI producer.  prefetch is the number of Count-sized batches buffered
// at each endpoint; zero selects the historical baseline path.
func RunControllerWithPrefetch(arm, sourceURL, targetURL string, m1 int, alpha float64, seed1, seed2 uint64, hardCap, batch, prefetch int, timeout time.Duration) (result Result, err error) {
	return RunControllerWithOptions(arm, sourceURL, targetURL, m1, alpha, seed1, seed2, hardCap, batch, ControllerOptions{PrefetchBatches: prefetch}, timeout)
}

func RunControllerWithOptions(arm, sourceURL, targetURL string, m1 int, alpha float64, seed1, seed2 uint64, hardCap, batch int, options ControllerOptions, timeout time.Duration) (result Result, err error) {
	result = Result{Arm: arm, StartedAt: time.Now().UTC(), Batch: batch}
	started := time.Now()
	defer func() { result.TotalMillis = time.Since(started).Milliseconds() }()
	if batch < 1 {
		batch = 1
		result.Batch = batch
	}
	client := &http.Client{Timeout: timeout}
	switch arm {
	case "ss":
		return runDistributedSS(client, sourceURL, targetURL, m1, alpha, seed1, seed2, &result)
	case "rf":
		return runDistributedRF(client, sourceURL, targetURL, hardCap, &result)
	case "ri":
		return runDistributedRI(client, sourceURL, targetURL, hardCap, batch, options, &result)
	case "sharded-ri":
		return runDistributedShardedRI(client, sourceURL, targetURL, hardCap, batch, options, &result)
	case "sharded-ri-stream":
		return runDistributedShardedRIStream(client, sourceURL, targetURL, hardCap, batch, options, &result)
	default:
		return result, fmt.Errorf("unknown arm %q", arm)
	}
}

func runDistributedShardedRI(client *http.Client, source, target string, hardCap, batch int, options ControllerOptions, result *Result) (Result, error) {
	shards := options.Shards
	if shards < 1 {
		shards = 8
	}
	if hardCap < 1 || batch < 1 {
		return *result, errors.New("sharded RI hard-cap and batch must be positive")
	}
	if batch > hardCap {
		batch = hardCap
	}
	defer stopParallel(client, source+"/sri/stop", target+"/sri/stop")
	a, b, err := parallelPrepare(client, source+"/sri/prepare", target+"/sri/prepare", Request{Shards: shards})
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, a)
	result.EndpointTarget = append(result.EndpointTarget, b)
	result.Shards = shards
	dec, err := sharded.NewDecoder(shards)
	if err != nil {
		return *result, err
	}
	prefix := 0
	for prefix < hardCap {
		count := batch
		if remaining := hardCap - prefix; count > remaining {
			count = remaining
		}
		sourceCells, targetCells, sm, tm, err := parallelShardedCoded(client, source+"/sri/next", target+"/sri/next", Request{Count: count})
		if err != nil {
			return *result, err
		}
		result.EndpointSource = append(result.EndpointSource, sm)
		result.EndpointTarget = append(result.EndpointTarget, tm)
		if err := dec.AddBatch(sourceCells, targetCells); err != nil {
			return *result, err
		}
		prefix += count
		result.Prefix = prefix
		result.WireBytesSource = int64(prefix * shards * codedWireBytes)
		result.WireBytesTarget = result.WireBytesSource
		if dec.Decoded() {
			result.Success = true
			result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
			return *result, nil
		}
	}
	result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
	return *result, errors.New("sharded RI hard cap exhausted")
}

// runDistributedShardedRIStream removes the global shard batch barrier. Each
// shard has its own source/target request pair and decoder; a completed shard
// stops requesting symbols while slower shards continue independently.
func runDistributedShardedRIStream(client *http.Client, source, target string, hardCap, batch int, options ControllerOptions, result *Result) (Result, error) {
	shards := options.Shards
	if shards < 1 {
		shards = 8
	}
	if hardCap < 1 || batch < 1 {
		return *result, errors.New("sharded RI stream hard-cap and batch must be positive")
	}
	if batch > hardCap {
		batch = hardCap
	}
	defer stopParallel(client, source+"/sri/stop", target+"/sri/stop")
	a, b, err := parallelPrepare(client, source+"/sri/prepare", target+"/sri/prepare", Request{Shards: shards})
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, a)
	result.EndpointTarget = append(result.EndpointTarget, b)
	result.Shards = shards
	result.ShardPrefixes = make([]int, shards)

	type shardRun struct {
		index     int
		prefix    int
		wireBytes int64
		sourceMet []EndpointMetrics
		targetMet []EndpointMetrics
		remote    int
		local     int
		success   bool
		err       error
	}
	runs := make([]shardRun, shards)
	var wg sync.WaitGroup
	for shard := 0; shard < shards; shard++ {
		runs[shard].index = shard
		wg.Add(1)
		go func(shard int) {
			defer wg.Done()
			dec := riblt.Decoder[model.Symbol]{}
			for prefix := 0; prefix < hardCap; {
				count := batch
				if remaining := hardCap - prefix; count > remaining {
					count = remaining
				}
				srcURL := fmt.Sprintf("%s/sri/next-shard/%d", source, shard)
				tgtURL := fmt.Sprintf("%s/sri/next-shard/%d", target, shard)
				aCells, bCells, sm, tm, reqErr := parallelCoded(client, srcURL, tgtURL, Request{Count: count})
				if reqErr != nil {
					runs[shard].err = reqErr
					return
				}
				runs[shard].sourceMet = append(runs[shard].sourceMet, sm)
				runs[shard].targetMet = append(runs[shard].targetMet, tm)
				for i := range aCells {
					dec.AddCodedSymbol(xorCoded(aCells[i], bCells[i]))
				}
				dec.TryDecode()
				prefix += count
				if dec.Decoded() {
					runs[shard].success = true
					runs[shard].prefix = prefix
					runs[shard].remote = len(dec.Remote())
					runs[shard].local = len(dec.Local())
					runs[shard].wireBytes = int64(prefix * codedWireBytes)
					return
				}
			}
			runs[shard].prefix = hardCap
			runs[shard].wireBytes = int64(hardCap * codedWireBytes)
			runs[shard].remote = len(dec.Remote())
			runs[shard].local = len(dec.Local())
			runs[shard].err = errors.New("sharded RI stream hard cap exhausted")
		}(shard)
	}
	wg.Wait()
	for shard := range runs {
		result.EndpointSource = append(result.EndpointSource, runs[shard].sourceMet...)
		result.EndpointTarget = append(result.EndpointTarget, runs[shard].targetMet...)
		result.ShardPrefixes[shard] = runs[shard].prefix
		result.Prefix += runs[shard].prefix
		result.WireBytesSource += runs[shard].wireBytes
		result.WireBytesTarget += runs[shard].wireBytes
		result.Plus += runs[shard].remote
		result.Minus += runs[shard].local
		if runs[shard].err != nil {
			return *result, fmt.Errorf("shard %d: %w", shard, runs[shard].err)
		}
	}
	result.Success = true
	return *result, nil
}

func runDistributedSS(client *http.Client, source, target string, m1 int, alpha float64, seed1, seed2 uint64, result *Result) (Result, error) {
	a, b, err := parallelPlain(client, source, target, m1, seed1)
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, a.Metrics)
	result.EndpointTarget = append(result.EndpointTarget, b.Metrics)
	diff, err := subtractPlain(a, b)
	if err != nil {
		return *result, err
	}
	decoded := diff.Decode()
	result.Prefix = m1
	if decoded.Success {
		result.Success, result.Plus, result.Minus = true, len(decoded.Plus), len(decoded.Minus)
		result.WireBytesSource, result.WireBytesTarget = int64(m1*codedWireBytes), int64(m1*codedWireBytes)
		return *result, nil
	}
	m2f := math.Ceil(alpha * decoded.DHat)
	if !isFinite(m2f) || m2f <= plain.K || m2f > math.MaxInt {
		return *result, fmt.Errorf("invalid m2 from d_hat=%g", decoded.DHat)
	}
	m2 := int(m2f)
	a, b, err = parallelPlain(client, source, target, m2, seed2)
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, a.Metrics)
	result.EndpointTarget = append(result.EndpointTarget, b.Metrics)
	diff, err = subtractPlain(a, b)
	if err != nil {
		return *result, err
	}
	decoded = diff.Decode()
	result.Prefix = m2
	result.Success, result.Plus, result.Minus = decoded.Success, len(decoded.Plus), len(decoded.Minus)
	result.WireBytesSource = int64((m1 + m2) * codedWireBytes)
	result.WireBytesTarget = result.WireBytesSource
	if !decoded.Success {
		return *result, errors.New("ss distributed decode failed")
	}
	return *result, nil
}

func runDistributedRF(client *http.Client, source, target string, m int, result *Result) (Result, error) {
	a, b, am, bm, err := parallelCoded(client, source+"/rf/build", target+"/rf/build", Request{M: m})
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, am)
	result.EndpointTarget = append(result.EndpointTarget, bm)
	dec := riblt.Decoder[model.Symbol]{}
	for i := range a {
		dec.AddCodedSymbol(xorCoded(a[i], b[i]))
	}
	dec.TryDecode()
	result.Prefix, result.Success = m, dec.Decoded()
	result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
	result.WireBytesSource, result.WireBytesTarget = int64(m*codedWireBytes), int64(m*codedWireBytes)
	if !result.Success {
		return *result, errors.New("rf distributed decode failed")
	}
	return *result, nil
}

func runDistributedRI(client *http.Client, source, target string, hardCap, batch int, options ControllerOptions, result *Result) (Result, error) {
	prefetch := options.PrefetchBatches
	if options.Horizon && prefetch > 0 {
		return *result, errors.New("RI horizon and prefetch are mutually exclusive; horizon already removes serial RTTs")
	}
	if prefetch > 0 && hardCap%batch != 0 {
		return *result, fmt.Errorf("RI prefetch requires hard-cap (%d) divisible by batch (%d)", hardCap, batch)
	}
	if options.HorizonAlpha <= 0 {
		options.HorizonAlpha = 1.35
	}
	if options.HorizonFirst <= 0 {
		options.HorizonFirst = 1024
	}
	defer stopParallel(client, source+"/ri/stop", target+"/ri/stop")
	prepareReq := Request{}
	if prefetch > 0 {
		prepareReq = Request{Count: batch, Prefetch: prefetch}
	}
	a, b, err := parallelPrepare(client, source+"/ri/prepare", target+"/ri/prepare", prepareReq)
	if err != nil {
		return *result, err
	}
	result.EndpointSource = append(result.EndpointSource, a)
	result.EndpointTarget = append(result.EndpointTarget, b)
	dec := riblt.Decoder[model.Symbol]{}
	var dhat estimator.Estimator
	prefix := 0
	consume := func(count int) error {
		aCells, bCells, am, bm, err := parallelCoded(client, source+"/ri/next", target+"/ri/next", Request{Count: count})
		if err != nil {
			return err
		}
		result.EndpointSource = append(result.EndpointSource, am)
		result.EndpointTarget = append(result.EndpointTarget, bm)
		counts := make([]int64, len(aCells))
		for i := range aCells {
			counts[i] = aCells[i].Count - bCells[i].Count
		}
		if err := dhat.AddBatch(prefix, counts); err != nil {
			return fmt.Errorf("streaming d-hat: %w", err)
		}
		for i := range aCells {
			dec.AddCodedSymbol(xorCoded(aCells[i], bCells[i]))
		}
		dec.TryDecode()
		prefix += count
		c0, _ := dhat.C0()
		result.RIProgress = append(result.RIProgress, RIProgress{Prefix: prefix, C0: c0, Samples: dhat.Samples(), DHat: dhat.DHat(), DecodedSymbols: len(dec.Remote()) + len(dec.Local()), Decoded: dec.Decoded(), WireBytes: int64(prefix * codedWireBytes)})
		return nil
	}
	firstCount := batch
	if options.Horizon {
		firstCount = options.HorizonFirst
	}
	if firstCount > hardCap {
		firstCount = hardCap
	}
	if firstCount < 1 {
		return *result, errors.New("RI hard-cap must be positive")
	}
	if err := consume(firstCount); err != nil {
		return *result, err
	}
	if dec.Decoded() {
		result.Prefix, result.Success = prefix, true
		result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
		result.WireBytesSource, result.WireBytesTarget = int64(prefix*codedWireBytes), int64(prefix*codedWireBytes)
		return *result, nil
	}
	if options.Horizon {
		planned := int(math.Ceil(options.HorizonAlpha * dhat.DHat()))
		if planned <= prefix {
			planned = prefix + batch
		}
		if planned > hardCap {
			planned = hardCap
		}
		result.Horizon = &HorizonMetrics{Enabled: true, FirstPrefix: prefix, PlannedPrefix: planned, DHat: dhat.DHat(), Alpha: options.HorizonAlpha}
		if planned > prefix {
			if err := consume(planned - prefix); err != nil {
				return *result, err
			}
		}
		if dec.Decoded() {
			result.Prefix, result.Success = prefix, true
			result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
			result.WireBytesSource, result.WireBytesTarget = int64(prefix*codedWireBytes), int64(prefix*codedWireBytes)
			return *result, nil
		}
	}
	for prefix < hardCap {
		count := batch
		if remaining := hardCap - prefix; count > remaining {
			count = remaining
		}
		if err := consume(count); err != nil {
			return *result, err
		}
		if dec.Decoded() {
			result.Prefix, result.Success = prefix, true
			result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
			result.WireBytesSource, result.WireBytesTarget = int64(prefix*codedWireBytes), int64(prefix*codedWireBytes)
			return *result, nil
		}
	}
	result.Prefix, result.WireBytesSource, result.WireBytesTarget = hardCap, int64(hardCap*codedWireBytes), int64(hardCap*codedWireBytes)
	result.Plus, result.Minus = len(dec.Remote()), len(dec.Local())
	return *result, errors.New("ri hard cap exhausted")
}

func stopParallel(client *http.Client, source, target string) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); _ = requestJSON(client, source, nil, &map[string]any{}) }()
	go func() { defer wg.Done(); _ = requestJSON(client, target, nil, &map[string]any{}) }()
	wg.Wait()
}

func parallelPlain(client *http.Client, source, target string, m int, seed uint64) (PlainResponse, PlainResponse, error) {
	var a, b PlainResponse
	var ae, be error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a, ae = requestPlain(client, source+"/ss/build", Request{M: m, Seed: seed}) }()
	go func() { defer wg.Done(); b, be = requestPlain(client, target+"/ss/build", Request{M: m, Seed: seed}) }()
	wg.Wait()
	if ae != nil {
		return a, b, ae
	}
	if be != nil {
		return a, b, be
	}
	return a, b, nil
}

func parallelPrepare(client *http.Client, source, target string, req Request) (EndpointMetrics, EndpointMetrics, error) {
	var a, b PrepareResponse
	var ae, be error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); ae = requestJSON(client, source, req, &a) }()
	go func() { defer wg.Done(); be = requestJSON(client, target, req, &b) }()
	wg.Wait()
	if ae != nil {
		return a.Metrics, b.Metrics, ae
	}
	if be != nil {
		return a.Metrics, b.Metrics, be
	}
	return a.Metrics, b.Metrics, nil
}

func parallelCoded(client *http.Client, source, target string, req Request) ([]riblt.CodedSymbol[model.Symbol], []riblt.CodedSymbol[model.Symbol], EndpointMetrics, EndpointMetrics, error) {
	var a, b []riblt.CodedSymbol[model.Symbol]
	var am, bm EndpointMetrics
	var ae, be error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a, am, ae = requestCoded(client, source, req) }()
	go func() { defer wg.Done(); b, bm, be = requestCoded(client, target, req) }()
	wg.Wait()
	if ae != nil {
		return a, b, am, bm, ae
	}
	if be != nil {
		return a, b, am, bm, be
	}
	if len(a) != len(b) {
		return a, b, am, bm, errors.New("coded response length mismatch")
	}
	return a, b, am, bm, nil
}

func parallelShardedCoded(client *http.Client, source, target string, req Request) ([][]riblt.CodedSymbol[model.Symbol], [][]riblt.CodedSymbol[model.Symbol], EndpointMetrics, EndpointMetrics, error) {
	var a, b [][]riblt.CodedSymbol[model.Symbol]
	var am, bm EndpointMetrics
	var ae, be error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a, am, ae = requestShardedCoded(client, source, req) }()
	go func() { defer wg.Done(); b, bm, be = requestShardedCoded(client, target, req) }()
	wg.Wait()
	if ae != nil {
		return a, b, am, bm, ae
	}
	if be != nil {
		return a, b, am, bm, be
	}
	if len(a) != len(b) {
		return a, b, am, bm, errors.New("sharded response shard count mismatch")
	}
	for i := range a {
		if len(a[i]) != len(b[i]) {
			return a, b, am, bm, errors.New("sharded response batch length mismatch")
		}
	}
	return a, b, am, bm, nil
}

func requestPlain(client *http.Client, url string, req Request) (PlainResponse, error) {
	var out PlainResponse
	return out, requestJSON(client, url, req, &out)
}

func requestJSON(client *http.Client, url string, req any, out any) error {
	var body io.Reader
	if req != nil {
		raw, err := json.Marshal(req)
		if err != nil {
			return err
		}
		body = bytes.NewReader(raw)
	}
	hreq, err := http.NewRequest(http.MethodPost, url, body)
	if err != nil {
		return err
	}
	hreq.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(hreq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("%s: http %d: %s", url, resp.StatusCode, raw)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func requestCoded(client *http.Client, url string, req Request) ([]riblt.CodedSymbol[model.Symbol], EndpointMetrics, error) {
	raw, err := json.Marshal(req)
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	hreq, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(hreq)
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, EndpointMetrics{}, fmt.Errorf("%s: http %d: %s", url, resp.StatusCode, msg)
	}
	header := make([]byte, codedHeaderBytes)
	if _, err := io.ReadFull(resp.Body, header); err != nil {
		return nil, EndpointMetrics{}, err
	}
	count := int(binary.BigEndian.Uint64(header[0:8]))
	if count < 0 || count > 1<<20 {
		return nil, EndpointMetrics{}, errors.New("invalid coded count")
	}
	metrics := EndpointMetrics{
		Rows:            int64(binary.BigEndian.Uint64(header[8:16])),
		Bytes:           int64(binary.BigEndian.Uint64(header[16:24])),
		Millis:          int64(binary.BigEndian.Uint64(header[24:32])),
		Passes:          int(binary.BigEndian.Uint64(header[32:40])),
		GenerateMillis:  int64(binary.BigEndian.Uint64(header[40:48])),
		VmHWMBytes:      int64(binary.BigEndian.Uint64(header[48:56])),
		CgroupPeakBytes: int64(binary.BigEndian.Uint64(header[56:64])),
		CgroupFailcnt:   binary.BigEndian.Uint64(header[64:72]),
	}
	cells := make([]riblt.CodedSymbol[model.Symbol], count)
	for i := range cells {
		if err := readCoded(resp.Body, &cells[i]); err != nil {
			return nil, metrics, err
		}
	}
	return cells, metrics, nil
}

func requestShardedCoded(client *http.Client, url string, req Request) ([][]riblt.CodedSymbol[model.Symbol], EndpointMetrics, error) {
	raw, err := json.Marshal(req)
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	hreq, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(hreq)
	if err != nil {
		return nil, EndpointMetrics{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, EndpointMetrics{}, fmt.Errorf("%s: http %d: %s", url, resp.StatusCode, msg)
	}
	header := make([]byte, shardedHeaderBytes)
	if _, err := io.ReadFull(resp.Body, header); err != nil {
		return nil, EndpointMetrics{}, err
	}
	shards := int(binary.BigEndian.Uint64(header[0:8]))
	count := int(binary.BigEndian.Uint64(header[8:16]))
	if shards < 1 || shards > 128 || count < 1 || count > 1<<20 {
		return nil, EndpointMetrics{}, errors.New("invalid sharded coded dimensions")
	}
	metrics := EndpointMetrics{
		Rows:            int64(binary.BigEndian.Uint64(header[16:24])),
		Bytes:           int64(binary.BigEndian.Uint64(header[24:32])),
		Millis:          int64(binary.BigEndian.Uint64(header[32:40])),
		Passes:          int(binary.BigEndian.Uint64(header[40:48])),
		GenerateMillis:  int64(binary.BigEndian.Uint64(header[48:56])),
		VmHWMBytes:      int64(binary.BigEndian.Uint64(header[56:64])),
		CgroupPeakBytes: int64(binary.BigEndian.Uint64(header[64:72])),
		CgroupFailcnt:   binary.BigEndian.Uint64(header[72:80]),
	}
	out := make([][]riblt.CodedSymbol[model.Symbol], shards)
	for shard := range out {
		out[shard] = make([]riblt.CodedSymbol[model.Symbol], count)
		for i := range out[shard] {
			if err := readCoded(resp.Body, &out[shard][i]); err != nil {
				return nil, metrics, err
			}
		}
	}
	return out, metrics, nil
}

func writeCoded(w http.ResponseWriter, cells []riblt.CodedSymbol[model.Symbol], metrics EndpointMetrics) {
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	var header [codedHeaderBytes]byte
	binary.BigEndian.PutUint64(header[0:8], uint64(len(cells)))
	binary.BigEndian.PutUint64(header[8:16], uint64(metrics.Rows))
	binary.BigEndian.PutUint64(header[16:24], uint64(metrics.Bytes))
	binary.BigEndian.PutUint64(header[24:32], uint64(metrics.Millis))
	binary.BigEndian.PutUint64(header[32:40], uint64(metrics.Passes))
	binary.BigEndian.PutUint64(header[40:48], uint64(metrics.GenerateMillis))
	binary.BigEndian.PutUint64(header[48:56], uint64(metrics.VmHWMBytes))
	binary.BigEndian.PutUint64(header[56:64], uint64(metrics.CgroupPeakBytes))
	binary.BigEndian.PutUint64(header[64:72], metrics.CgroupFailcnt)
	_, _ = w.Write(header[:])
	for i := range cells {
		writeOneCoded(w, cells[i])
	}
}

func writeSharded(w http.ResponseWriter, shards [][]riblt.CodedSymbol[model.Symbol], metrics EndpointMetrics) {
	if len(shards) == 0 {
		writeError(w, errors.New("empty sharded response"))
		return
	}
	count := len(shards[0])
	for i := range shards {
		if len(shards[i]) != count {
			writeError(w, errors.New("inconsistent sharded response"))
			return
		}
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	var header [shardedHeaderBytes]byte
	binary.BigEndian.PutUint64(header[0:8], uint64(len(shards)))
	binary.BigEndian.PutUint64(header[8:16], uint64(count))
	binary.BigEndian.PutUint64(header[16:24], uint64(metrics.Rows))
	binary.BigEndian.PutUint64(header[24:32], uint64(metrics.Bytes))
	binary.BigEndian.PutUint64(header[32:40], uint64(metrics.Millis))
	binary.BigEndian.PutUint64(header[40:48], uint64(metrics.Passes))
	binary.BigEndian.PutUint64(header[48:56], uint64(metrics.GenerateMillis))
	binary.BigEndian.PutUint64(header[56:64], uint64(metrics.VmHWMBytes))
	binary.BigEndian.PutUint64(header[64:72], uint64(metrics.CgroupPeakBytes))
	binary.BigEndian.PutUint64(header[72:80], metrics.CgroupFailcnt)
	_, _ = w.Write(header[:])
	for shard := range shards {
		for i := range shards[shard] {
			writeOneCoded(w, shards[shard][i])
		}
	}
}

func writeOneCoded(w io.Writer, c riblt.CodedSymbol[model.Symbol]) {
	var raw [codedWireBytes]byte
	binary.BigEndian.PutUint64(raw[0:8], c.Symbol.FP)
	binary.BigEndian.PutUint64(raw[8:16], c.Symbol.ID)
	binary.BigEndian.PutUint64(raw[16:24], c.Hash)
	binary.BigEndian.PutUint64(raw[24:32], uint64(c.Count))
	_, _ = w.Write(raw[:])
}

func readCoded(r io.Reader, c *riblt.CodedSymbol[model.Symbol]) error {
	var raw [codedWireBytes]byte
	if _, err := io.ReadFull(r, raw[:]); err != nil {
		return err
	}
	c.Symbol = model.Symbol{FP: binary.BigEndian.Uint64(raw[0:8]), ID: binary.BigEndian.Uint64(raw[8:16])}
	c.Hash = binary.BigEndian.Uint64(raw[16:24])
	c.Count = int64(binary.BigEndian.Uint64(raw[24:32]))
	return nil
}

func xorCoded(a, b riblt.CodedSymbol[model.Symbol]) riblt.CodedSymbol[model.Symbol] {
	return riblt.CodedSymbol[model.Symbol]{HashedSymbol: riblt.HashedSymbol[model.Symbol]{Symbol: a.Symbol.XOR(b.Symbol), Hash: a.Hash ^ b.Hash}, Count: a.Count - b.Count}
}

func subtractPlain(a, b PlainResponse) (*plain.Sketch, error) {
	if a.M != b.M || a.Seed != b.Seed || len(a.Count) != len(b.Count) {
		return nil, errors.New("plain response mismatch")
	}
	diff := plain.New(a.M, a.Seed)
	copy(diff.Count, a.Count)
	copy(diff.FPXor, a.FPXor)
	copy(diff.IDXor, a.IDXor)
	copy(diff.ChkXor, a.ChkXor)
	for i := range diff.Count {
		diff.Count[i] -= b.Count[i]
		diff.FPXor[i] ^= b.FPXor[i]
		diff.IDXor[i] ^= b.IDXor[i]
		diff.ChkXor[i] ^= b.ChkXor[i]
	}
	return diff, nil
}

func readJSON(r *http.Request, out any) error {
	return json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(out)
}
func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, err error) {
	http.Error(w, err.Error(), http.StatusInternalServerError)
}
func isFinite(v float64) bool { return !math.IsNaN(v) && !math.IsInf(v, 0) }

func WriteResult(path string, result Result) error {
	raw, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	if path == "-" {
		_, err = os.Stdout.Write(raw)
		return err
	}
	return os.WriteFile(path, raw, 0o644)
}
