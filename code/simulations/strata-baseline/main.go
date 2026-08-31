// Strata / out-of-band estimator head-to-head vs in-band dhat (05 plan, Phase 1).
//
// Two experiments in one deterministic run:
//
//	F-A (precision): estimator accuracy vs dedicated out-of-band bytes.
//	  - Strata estimator (Eppstein et al. 2011): L strata x C cells/stratum,
//	    element -> stratum by trailing zeros of an independent hash,
//	    decode top-down, dhat = 2^(i+1) * (recovered above first failing stratum i);
//	    full decode -> exact difference.
//	  - ToW / AMS sketch: m counters, each element adds +/-1 to every counter,
//	    dhat = mean_j C_j^2 (unbiased, RSD = sqrt(2/m)). Simulated exactly via
//	    random-bit popcount (C_j = 2*Binomial(d,1/2) - d).
//	  - in-band dhat: probe IBLT of M cells, pre-peeling counts (zero
//	    out-of-band bytes; the probe doubles as a decode attempt).
//
//	F-B (pipelines): end-to-end bytes / rounds / scans vs true d for
//	  blind-doubling, strata-first, self-sizing (ours), oracle.
//	  scans = number of sketch builds = number of full-table fingerprint passes.
//	  Every pipeline pays >= 1 scan; strata-first pays a second scan whenever
//	  the strata sketch alone cannot enumerate the difference; self-sizing pays
//	  a second scan only when the probe fails to decode.
//
// Byte accounting: IBLT/strata cell = 32 B (count+keySum+checkSum, sidecar
// width: 2 KB probe at M=64); ToW counter = 4 B. Decode success is the
// idealized 2-core criterion (checksum collisions ignored), consistent with
// plain-t1-f2.
//
// Usage: go run . [-trials 1000] [-out results]
package main

import (
	"bufio"
	"flag"
	"fmt"
	"math"
	"math/bits"
	"math/rand/v2"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
)

const (
	cellBytes    = 32
	counterBytes = 4
	probeK       = 3
)

// Overridable via -probe-m / -alpha flags for the deployed-tier regret rerun
// (probeM=512, alpha=1.824).
var (
	alpha  = 1.4
	probeM = 64
)

// ---------- shared IBLT machinery (same idealized model as plain-t1-f2) ----------

type ibltSim struct {
	deg       []int32
	xorID     []int32
	counts    []int64
	elemCells [][]int32
	queue     []int32
}

func newIbltSim(maxM, maxD, k int) *ibltSim {
	s := &ibltSim{
		deg:       make([]int32, maxM),
		xorID:     make([]int32, maxM),
		counts:    make([]int64, maxM),
		elemCells: make([][]int32, maxD),
		queue:     make([]int32, 0, maxM),
	}
	for i := range s.elemCells {
		s.elemCells[i] = make([]int32, 0, k)
	}
	return s
}

// build inserts elements [0,d) with the given signs into an M-cell table.
func (s *ibltSim) build(rng *rand.Rand, M, k, d int, signs []int64) {
	for i := 0; i < M; i++ {
		s.deg[i], s.xorID[i], s.counts[i] = 0, 0, 0
	}
	for e := 0; e < d; e++ {
		cells := s.elemCells[e][:0]
	pick:
		for len(cells) < k {
			c := int32(rng.IntN(M))
			for _, p := range cells {
				if p == c {
					continue pick
				}
			}
			cells = append(cells, c)
		}
		s.elemCells[e] = cells
		id := int32(e + 1)
		for _, c := range cells {
			s.deg[c]++
			s.xorID[c] ^= id
			s.counts[c] += signs[e]
		}
	}
}

// peel returns (#recovered, fully decoded).
func (s *ibltSim) peel(M, d int) (int, bool) {
	q := s.queue[:0]
	for c := 0; c < M; c++ {
		if s.deg[c] == 1 {
			q = append(q, int32(c))
		}
	}
	removed := 0
	for len(q) > 0 {
		c := q[len(q)-1]
		q = q[:len(q)-1]
		if s.deg[c] != 1 {
			continue
		}
		id := s.xorID[c]
		e := int(id - 1)
		for _, c2 := range s.elemCells[e] {
			s.deg[c2]--
			s.xorID[c2] ^= id
			if s.deg[c2] == 1 {
				q = append(q, c2)
			}
		}
		removed++
	}
	return removed, removed == d
}

func (s *ibltSim) dhat(M, k int) float64 {
	var sum, sq float64
	for i := 0; i < M; i++ {
		c := float64(s.counts[i])
		sum += c
		sq += c * c
	}
	kf, mf := float64(k), float64(M)
	return (sq - sum*sum/mf) / (kf * (1 - kf/mf))
}

func makeSigns(rng *rand.Rand, d int, negRatio float64) []int64 {
	signs := make([]int64, d)
	negCount := int(negRatio*float64(d) + 0.5)
	for i := range signs {
		if i < negCount {
			signs[i] = -1
		} else {
			signs[i] = 1
		}
	}
	rng.Shuffle(d, func(i, j int) { signs[i], signs[j] = signs[j], signs[i] })
	return signs
}

// ---------- Strata estimator ----------

type strataSketch struct {
	levels int
	cells  int
	k      int
	sims   []*ibltSim
	occ    []int // elements per stratum this trial
	perm   [][]int
}

func newStrata(levels, cells, k, maxD int) *strataSketch {
	st := &strataSketch{levels: levels, cells: cells, k: k}
	for i := 0; i < levels; i++ {
		st.sims = append(st.sims, newIbltSim(cells, maxD, k))
	}
	st.occ = make([]int, levels)
	st.perm = make([][]int, levels)
	return st
}

// estimate builds the strata for d difference elements and runs the
// Eppstein top-down decode. Returns (dhat, fullyDecoded, recoveredElements).
// recoveredElements are real differences and must be credited to Strata when
// sizing/decoding the follow-up reconciliation table.
func (st *strataSketch) estimate(rng *rand.Rand, d int, signs []int64) (float64, bool, int) {
	for i := range st.occ {
		st.occ[i] = 0
		st.perm[i] = st.perm[i][:0]
	}
	for e := 0; e < d; e++ {
		lv := bits.TrailingZeros64(rng.Uint64())
		if lv >= st.levels {
			lv = st.levels - 1
		}
		st.occ[lv]++
		st.perm[lv] = append(st.perm[lv], e)
	}
	recovered := 0
	for i := st.levels - 1; i >= 0; i-- {
		n := st.occ[i]
		sim := st.sims[i]
		sub := make([]int64, n)
		for j, e := range st.perm[i] {
			sub[j] = signs[e]
		}
		sim.build(rng, st.cells, st.k, n, sub)
		rec, ok := sim.peel(st.cells, n)
		if !ok {
			// Keep the standard estimator based only on fully decoded higher
			// strata, but subtract every valid element peeled before this stratum
			// became stuck when computing the remaining reconciliation load.
			return math.Exp2(float64(i+1)) * float64(recovered), false, recovered + rec
		}
		recovered += n
	}
	return float64(recovered), true, recovered // full enumeration: exact
}

func (st *strataSketch) oobBytes() int { return st.levels * st.cells * cellBytes }

// ---------- ToW / AMS ----------

// towDhat draws m iid counters C_j = sum of d iid +/-1 (exact via popcount)
// and returns mean(C_j^2).
func towDhat(rng *rand.Rand, m, d int) float64 {
	var acc float64
	words := d / 64
	rem := d % 64
	for j := 0; j < m; j++ {
		ones := 0
		for w := 0; w < words; w++ {
			ones += bits.OnesCount64(rng.Uint64())
		}
		if rem > 0 {
			ones += bits.OnesCount64(rng.Uint64() & ((1 << rem) - 1))
		}
		c := float64(2*ones - d)
		acc += c * c
	}
	return acc / float64(m)
}

// ---------- pipelines ----------

func pow2ceil(x float64, floor int) int {
	m := floor
	for float64(m) < x {
		m *= 2
	}
	return m
}

// alignedCeil rounds to a small allocation quantum without imposing the
// legacy power-of-two capacity ladder. Plain IBLT does not require M=2^j.
func alignedCeil(x float64, quantum, floor int) int {
	m := int(math.Ceil(x))
	if m < floor {
		m = floor
	}
	return ((m + quantum - 1) / quantum) * quantum
}

type pipeOut struct {
	bytes, rounds, scans int
	ok                   bool
}

// decodeAt builds a fresh IBLT of capacity M and reports full decode.
func decodeAt(rng *rand.Rand, sim *ibltSim, M, d int, signs []int64) bool {
	sim.build(rng, M, probeK, d, signs)
	_, ok := sim.peel(M, d)
	return ok
}

// jointPeel runs peeling on the union hypergraph of all transmitted tables:
// every element carries its cells from each table (offsets applied); a pure
// cell in ANY table resolves the element and removes it from all tables.
// Adding a table never hurts: original cells keep their degrees, so the
// combined graph peels whenever any single table would.
func jointPeel(elemAll [][]int32, totalM, d int) bool {
	deg := make([]int32, totalM)
	xor := make([]int32, totalM)
	for e := 0; e < d; e++ {
		id := int32(e + 1)
		for _, c := range elemAll[e] {
			deg[c]++
			xor[c] ^= id
		}
	}
	queue := make([]int32, 0, totalM)
	for c := 0; c < totalM; c++ {
		if deg[c] == 1 {
			queue = append(queue, int32(c))
		}
	}
	removed := 0
	for len(queue) > 0 {
		c := queue[len(queue)-1]
		queue = queue[:len(queue)-1]
		if deg[c] != 1 {
			continue
		}
		id := xor[c]
		e := int(id - 1)
		for _, c2 := range elemAll[e] {
			deg[c2]--
			xor[c2] ^= id
			if deg[c2] == 1 {
				queue = append(queue, c2)
			}
		}
		removed++
	}
	return removed == d
}

// appendFreshTable extends every element's combined cell list with k fresh
// distinct cells in [offset, offset+M).
func appendFreshTable(rng *rand.Rand, elemAll [][]int32, d, offset, M, k int) {
	for e := 0; e < d; e++ {
		start := len(elemAll[e])
	pick:
		for len(elemAll[e])-start < k {
			c := int32(offset + rng.IntN(M))
			for _, p := range elemAll[e][start:] {
				if p == c {
					continue pick
				}
			}
			elemAll[e] = append(elemAll[e], c)
		}
	}
}

func runPipelines(rng *rand.Rand, sim *ibltSim, st *strataSketch, d int, signs []int64) map[string]pipeOut {
	out := map[string]pipeOut{}

	// blind doubling
	{
		bytes, rounds, M := 0, 0, probeM
		for {
			rounds++
			bytes += M * cellBytes
			if d == 0 || decodeAt(rng, sim, M, d, signs) {
				break
			}
			M *= 2
		}
		out["blind_doubling"] = pipeOut{bytes, rounds, rounds, true}
	}

	// shared probe: both self-sizing variants condition on the same round 1
	sim.build(rng, probeM, probeK, d, signs)
	probeCells := make([][]int32, d)
	for e := 0; e < d; e++ {
		probeCells[e] = append([]int32(nil), sim.elemCells[e]...)
	}
	_, probeOK := sim.peel(probeM, d)
	probeDh := 0.0
	if !probeOK {
		probeDh = sim.dhat(probeM, probeK)
	}

	// self-sizing, independent round 2 (the Proposition 5.1 capacity guarantee
	// does not rely on joint decoding)
	{
		bytes, rounds := probeM*cellBytes, 1
		if !probeOK {
			M := alignedCeil(alpha*probeDh, 64, probeM)
			for {
				rounds++
				bytes += M * cellBytes
				if decodeAt(rng, sim, M, d, signs) {
					break
				}
				M = alignedCeil(2*float64(M), 64, probeM)
			}
		}
		out["self_sizing"] = pipeOut{bytes, rounds, rounds, true}
	}

	// self-sizing + joint peeling (draft appendix C byte-optimization overlay):
	// round 2 keeps the same nominal size, but decodes jointly with the failed
	// probe (strict success-probability improvement); escalations transmit an
	// incremental top-up table (total doubles) and joint-peel ALL tables,
	// instead of rebuilding a 2x table from scratch.
	{
		bytes, rounds := probeM*cellBytes, 1
		if !probeOK {
			elemAll := probeCells // reuse the exact failed probe mapping
			total := probeM
			M := alignedCeil(alpha*probeDh, 64, probeM)
			for {
				rounds++
				bytes += M * cellBytes
				appendFreshTable(rng, elemAll, d, total, M, probeK)
				total += M
				if jointPeel(elemAll, total, d) {
					break
				}
				M = alignedCeil(float64(total), 64, probeM) // top-up: double the running total
			}
		}
		out["self_sizing_joint"] = pipeOut{bytes, rounds, rounds, true}
	}

	// strata-first (standard config): round 1 = strata estimate; if the strata
	// fully enumerate the difference, done; else round 2 = IBLT at alpha*dhat.
	{
		bytes, rounds := st.oobBytes(), 1
		dh, full, recovered := st.estimate(rng, d, signs)
		if !full {
			remaining := d - recovered
			if remaining > 0 {
				remEst := math.Max(dh-float64(recovered), 1)
				M := alignedCeil(alpha*remEst, 64, probeM)
				for {
					rounds++
					bytes += M * cellBytes
					if decodeAt(rng, sim, M, remaining, signs[:remaining]) {
						break
					}
					M = alignedCeil(2*float64(M), 64, probeM)
				}
			}
		}
		out["strata_first"] = pipeOut{bytes, rounds, rounds, true}
	}

	// oracle = hindsight lower bound: the smallest capacity on a 10%-granularity
	// aligned ladder that decodes this trial's difference, paid once (1 round,
	// 1 scan). d=0 still needs a 1-round equality certificate = probe.
	{
		M := probeM
		if d > 0 {
			M = alignedCeil(1.05*float64(d), 64, probeM)
			for !decodeAt(rng, sim, M, d, signs) {
				M = alignedCeil(1.1*float64(M)+1, 64, probeM)
			}
		}
		out["oracle"] = pipeOut{M * cellBytes, 1, 1, true}
	}
	return out
}

// ---------- experiment drivers ----------

type faRow struct {
	estimator string
	config    string
	oobBytes  int
	d         int
	negRatio  float64
	ratios    []float64
}

type fbKey struct {
	pipeline string
	d        int
	negRatio float64
}

type fbAgg struct {
	n                   int
	bytesSum, roundsSum float64
	scansSum            float64
	bytesVals           []float64
	roundsHist          map[int]int
}

type headTrial struct {
	bytes  int
	rounds int
}

// oursScratch holds per-grid-cell reusable buffers for runJointOurs so the
// hot loop performs no per-trial slice-forest allocation.
type oursScratch struct {
	probe   *ibltSim
	elemAll [][]int32
}

func newOursScratch(firstM, maxD int) *oursScratch {
	sc := &oursScratch{probe: newIbltSim(firstM, maxD, probeK), elemAll: make([][]int32, maxD)}
	for i := range sc.elemAll {
		sc.elemAll[i] = make([]int32, 0, 2*probeK)
	}
	return sc
}

// runJointOurs executes the final in-band policy being compared:
// probe -> aligned alpha*dhat fresh table -> joint peel all transmitted tables.
// A rare miss appends a fresh table roughly equal to the running total and
// continues joint peeling; no transmitted table is discarded.
func runJointOurs(rng *rand.Rand, sc *oursScratch, d, firstM int, signs []int64) headTrial {
	sc.probe.build(rng, firstM, probeK, d, signs)
	elemAll := sc.elemAll
	for e := 0; e < d; e++ {
		elemAll[e] = append(elemAll[e][:0], sc.probe.elemCells[e]...)
	}
	_, ok := sc.probe.peel(firstM, d)
	out := headTrial{bytes: firstM * cellBytes, rounds: 1}
	if ok {
		return out
	}

	dh := sc.probe.dhat(firstM, probeK) // counts remain pre-peeling
	total := firstM
	next := alignedCeil(alpha*dh, 64, 64)
	for {
		out.bytes += next * cellBytes
		out.rounds++
		appendFreshTable(rng, elemAll, d, total, next, probeK)
		total += next
		if jointPeel(elemAll, total, d) {
			return out
		}
		if out.rounds >= 12 {
			panic("ours joint peeling did not converge within 12 rounds")
		}
		next = alignedCeil(float64(total), 64, 64)
	}
}

// strataScratch holds reusable strata + follow-up-table buffers.
type strataScratch struct {
	st  *strataSketch
	sim *ibltSim
}

func newStrataScratch(maxD int) *strataScratch {
	maxNext := alignedCeil(alpha*float64(max(maxD, 64))*8, 64, 64)
	return &strataScratch{st: newStrata(16, 80, probeK, maxD), sim: newIbltSim(maxNext, maxD, probeK)}
}

// runCreditedStrata gives the baseline all recoverable value from its first
// message. If upper strata recover r real differences, the follow-up IBLT is
// sized for dhat-r and decoded after those r known elements are removed.
func runCreditedStrata(rng *rand.Rand, sc *strataScratch, d int, signs []int64) headTrial {
	dh, full, recovered := sc.st.estimate(rng, d, signs)
	out := headTrial{bytes: sc.st.oobBytes(), rounds: 1}
	if full {
		return out
	}

	remaining := d - recovered
	if remaining <= 0 {
		return out
	}
	remainingEstimate := math.Max(dh-float64(recovered), 1)
	next := alignedCeil(alpha*remainingEstimate, 64, 64)
	for {
		out.bytes += next * cellBytes
		out.rounds++
		if decodeAt(rng, sc.sim, next, remaining, signs[:remaining]) {
			return out
		}
		if out.rounds >= 12 {
			panic("credited Strata did not converge within 12 rounds")
		}
		next = alignedCeil(2*float64(next), 64, 64)
	}
}

func runQuickHeadToHead(trials int, outDir string) {
	firstMs := []int{64, 128, 256, 512, 1024, 1280}
	ds := []int{0, 10, 50, 100, 200, 400, 600, 1000, 2000, 5000, 10000, 100000}
	f, err := os.Create(filepath.Join(outDir, "head_to_head_quick.csv"))
	if err != nil {
		panic(err)
	}
	defer f.Close()
	w := bufio.NewWriter(f)
	defer w.Flush()
	fmt.Fprintln(w, "first_m,d,trials,ours_mean_bytes,strata_mean_bytes,ours_over_strata,ours_mean_rounds,strata_mean_rounds,ours_p3plus,strata_p3plus")
	fmt.Println("M1,d,ours/strata,ours_bytes,strata_bytes,ours_rounds,strata_rounds,ours_p3+,strata_p3+")

	seed := uint64(0xC20550)
	maxD := ds[len(ds)-1]
	strataSc := newStrataScratch(maxD)
	for _, firstM := range firstMs {
		oursSc := newOursScratch(firstM, maxD)
		for _, d := range ds {
			seed++
			rng := rand.New(rand.NewPCG(0x57A7A, seed))
			effTrials := trials
			if d >= 50000 {
				effTrials = max(trials/4, 100)
			}
			var ob, sb, orounds, srounds float64
			var op3, sp3 int
			for t := 0; t < effTrials; t++ {
				signs := makeSigns(rng, d, 0)
				o := runJointOurs(rng, oursSc, d, firstM, signs)
				s := runCreditedStrata(rng, strataSc, d, signs)
				ob += float64(o.bytes)
				sb += float64(s.bytes)
				orounds += float64(o.rounds)
				srounds += float64(s.rounds)
				if o.rounds >= 3 {
					op3++
				}
				if s.rounds >= 3 {
					sp3++
				}
			}
			n := float64(effTrials)
			omb, smb := ob/n, sb/n
			fmt.Fprintf(w, "%d,%d,%d,%.0f,%.0f,%.5f,%.4f,%.4f,%.5f,%.5f\n",
				firstM, d, effTrials, omb, smb, omb/smb, orounds/n, srounds/n,
				float64(op3)/n, float64(sp3)/n)
			fmt.Printf("%d,%d,%.3f,%.0f,%.0f,%.3f,%.3f,%.3f,%.3f\n",
				firstM, d, omb/smb, omb, smb, orounds/n, srounds/n,
				float64(op3)/n, float64(sp3)/n)
		}
	}
}

func mainImpl() {
	trials := flag.Int("trials", 1000, "trials per point")
	outDir := flag.String("out", "results", "output dir")
	headQuick := flag.Bool("head-to-head-quick", false, "run only the aligned-capacity Strata-vs-joint crossover grid")
	probeMFlag := flag.Int("probe-m", 0, "override probeM (0 = keep default 64)")
	alphaFlag := flag.Float64("alpha", 0, "override alpha (0 = keep default 1.4)")
	flag.Parse()
	if *probeMFlag > 0 {
		probeM = *probeMFlag
	}
	if *alphaFlag > 0 {
		alpha = *alphaFlag
	}
	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		panic(err)
	}
	if *headQuick {
		runQuickHeadToHead(*trials, *outDir)
		return
	}

	ds := []int{10, 100, 1000, 10000, 100000}
	negs := []float64{0, 0.5}
	maxD := 100000

	// ----- F-A precision -----
	type faJob struct {
		estimator string
		config    string
		oobBytes  int
		build     func(rng *rand.Rand, d int, signs []int64) float64
	}
	var jobs []faJob
	for _, M := range []int{64, 256, 1024, 4096} {
		M := M
		jobs = append(jobs, faJob{"inband_dhat", fmt.Sprintf("M=%d", M), 0,
			func(rng *rand.Rand, d int, signs []int64) float64 {
				sim := newIbltSim(M, d, probeK)
				sim.build(rng, M, probeK, d, signs)
				return sim.dhat(M, probeK)
			}})
	}
	for _, L := range []int{16, 32} {
		for _, C := range []int{40, 80, 160} {
			L, C := L, C
			jobs = append(jobs, faJob{"strata", fmt.Sprintf("L%d-C%d", L, C), L * C * cellBytes,
				func(rng *rand.Rand, d int, signs []int64) float64 {
					st := newStrata(L, C, probeK, d)
					dh, _, _ := st.estimate(rng, d, signs)
					return dh
				}})
		}
	}
	for _, m := range []int{64, 256, 1024, 4096} {
		m := m
		jobs = append(jobs, faJob{"tow", fmt.Sprintf("m=%d", m), m * counterBytes,
			func(rng *rand.Rand, d int, signs []int64) float64 {
				return towDhat(rng, m, d)
			}})
	}

	var faRows []faRow
	var faMu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, runtime.NumCPU())
	seed := uint64(0)
	for _, job := range jobs {
		for _, d := range ds {
			for _, neg := range negs {
				seed++
				wg.Add(1)
				go func(job faJob, d int, neg float64, seed uint64) {
					defer wg.Done()
					sem <- struct{}{}
					defer func() { <-sem }()
					rng := rand.New(rand.NewPCG(0x57A7A, seed))
					n := *trials
					// ToW at large d*m is the only heavy point; exact popcount keeps it feasible
					ratios := make([]float64, n)
					for t := 0; t < n; t++ {
						signs := makeSigns(rng, d, neg)
						ratios[t] = job.build(rng, d, signs) / float64(d)
					}
					faMu.Lock()
					faRows = append(faRows, faRow{job.estimator, job.config, job.oobBytes, d, neg, ratios})
					faMu.Unlock()
				}(job, d, neg, seed)
			}
		}
	}
	wg.Wait()

	sort.Slice(faRows, func(i, j int) bool {
		a, b := faRows[i], faRows[j]
		if a.estimator != b.estimator {
			return a.estimator < b.estimator
		}
		if a.config != b.config {
			return a.config < b.config
		}
		if a.d != b.d {
			return a.d < b.d
		}
		return a.negRatio < b.negRatio
	})
	fa, err := os.Create(filepath.Join(*outDir, "fa_precision.csv"))
	if err != nil {
		panic(err)
	}
	fw := bufio.NewWriter(fa)
	fmt.Fprintln(fw, "estimator,config,oob_bytes,d,neg_ratio,trials,mean_ratio,rsd,q05,q50,q95")
	for _, r := range faRows {
		sortd := append([]float64(nil), r.ratios...)
		sort.Float64s(sortd)
		var s, s2 float64
		for _, x := range r.ratios {
			s += x
		}
		mean := s / float64(len(r.ratios))
		for _, x := range r.ratios {
			s2 += (x - mean) * (x - mean)
		}
		std := math.Sqrt(s2 / float64(len(r.ratios)-1))
		q := func(p float64) float64 {
			pos := p * float64(len(sortd)-1)
			i := int(pos)
			if i >= len(sortd)-1 {
				return sortd[len(sortd)-1]
			}
			f := pos - float64(i)
			return sortd[i]*(1-f) + sortd[i+1]*f
		}
		fmt.Fprintf(fw, "%s,%s,%d,%d,%g,%d,%.5f,%.5f,%.4f,%.4f,%.4f\n",
			r.estimator, r.config, r.oobBytes, r.d, r.negRatio, len(r.ratios),
			mean, std/mean, q(0.05), q(0.50), q(0.95))
	}
	fw.Flush()
	fa.Close()
	fmt.Fprintln(os.Stderr, "F-A done")

	// ----- F-B pipelines -----
	fbDs := append([]int{0}, ds...)
	agg := map[fbKey]*fbAgg{}
	var fbMu sync.Mutex
	seed = 1 << 32
	for _, d := range fbDs {
		for _, neg := range negs {
			seed++
			wg.Add(1)
			go func(d int, neg float64, seed uint64) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				rng := rand.New(rand.NewPCG(0xF1B_57A7A, seed))
				maxM := pow2ceil(alpha*float64(maxD)*4, probeM)
				sim := newIbltSim(maxM, max(d, 1), probeK)
				st := newStrata(16, 80, probeK, max(d, 1))
				local := map[string]*fbAgg{}
				for t := 0; t < *trials; t++ {
					signs := makeSigns(rng, d, neg)
					for p, o := range runPipelines(rng, sim, st, d, signs) {
						a := local[p]
						if a == nil {
							a = &fbAgg{roundsHist: map[int]int{}}
							local[p] = a
						}
						a.n++
						a.bytesSum += float64(o.bytes)
						a.roundsSum += float64(o.rounds)
						a.scansSum += float64(o.scans)
						a.bytesVals = append(a.bytesVals, float64(o.bytes))
						a.roundsHist[o.rounds]++
					}
				}
				fbMu.Lock()
				for p, a := range local {
					agg[fbKey{p, d, neg}] = a
				}
				fbMu.Unlock()
			}(d, neg, seed)
		}
	}
	wg.Wait()

	fb, err := os.Create(filepath.Join(*outDir, "fb_pipeline_summary.csv"))
	if err != nil {
		panic(err)
	}
	fw = bufio.NewWriter(fb)
	fmt.Fprintln(fw, "pipeline,d,neg_ratio,trials,mean_bytes,p95_bytes,mean_rounds,max_rounds,mean_scans,rounds_histogram")
	keys := make([]fbKey, 0, len(agg))
	for k := range agg {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		a, b := keys[i], keys[j]
		if a.pipeline != b.pipeline {
			return a.pipeline < b.pipeline
		}
		if a.d != b.d {
			return a.d < b.d
		}
		return a.negRatio < b.negRatio
	})
	for _, k := range keys {
		a := agg[k]
		sort.Float64s(a.bytesVals)
		p95 := a.bytesVals[int(0.95*float64(len(a.bytesVals)-1))]
		maxR := 0
		hist := ""
		rks := make([]int, 0, len(a.roundsHist))
		for r := range a.roundsHist {
			rks = append(rks, r)
			if r > maxR {
				maxR = r
			}
		}
		sort.Ints(rks)
		for _, r := range rks {
			hist += fmt.Sprintf("%d:%d ", r, a.roundsHist[r])
		}
		fmt.Fprintf(fw, "%s,%d,%g,%d,%.0f,%.0f,%.3f,%d,%.3f,%s\n",
			k.pipeline, k.d, k.negRatio, a.n, a.bytesSum/float64(a.n), p95,
			a.roundsSum/float64(a.n), maxR, a.scansSum/float64(a.n), hist)
	}
	fw.Flush()
	fb.Close()
	fmt.Println("wrote fa_precision.csv and fb_pipeline_summary.csv")
}

func main() { mainImpl() }
