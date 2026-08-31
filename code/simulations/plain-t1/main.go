// T1 unified validation for the Plain IBLT count-channel estimator.
//
// Produces, in one deterministic run, the evidence behind the experimental
// validation in §3.6:
//   - Theorem 3.1: mean(dhat/d) vs 1, across neg_ratio (sign-pattern invariance)
//   - Corollary 3.3: empirical RSD vs sqrt(2(d-1)/(d(M-1))), across k in {3,4}
//   - Theorem 3.4: unconditional 5% quantile vs chi2_{M-1}/(M-1) and normal approx
//   - Theorem 3.6 (random-sign / fixed-sign): failed-only mean and 5% quantile vs unconditional prediction
//   - E8 directionality: one-sided E[dhat|F]/d across failure probabilities
//
// Failure flag = nonempty 2-core of the mapping hypergraph (idealized decoder,
// sign-independent), matching the failure-measurability model of Fact 3.5.
//
// Usage: go run . [-trials 10000] [-e8trials 20000]
//
//	[-M-list 64,256,1024,4096] [-e8=true] [-out results]
package main

import (
	"bufio"
	"flag"
	"fmt"
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
)

type config struct {
	kind     string // "grid" | "e8"
	M, K, D  int
	negRatio float64
	trials   int
	seed     uint64
}

type trialOut struct {
	ratio  float64
	failed bool
}

func runTrial(rng *rand.Rand, M, K, D int, negRatio float64, counts []int64, deg []int32, xorID []int32, elemCells [][]int32, queue []int32) (float64, bool) {
	for i := 0; i < M; i++ {
		counts[i], deg[i], xorID[i] = 0, 0, 0
	}
	// negRatio >= 0: fixed sign pattern with round(negRatio*D) minus signs (Theorem 3.6 fixed-sign regime).
	// negRatio < 0 (written as -1): iid uniform ±1 signs, independent of the mapping (Theorem 3.6 random-sign regime).
	negCount := int(negRatio*float64(D) + 0.5)
	for e := 0; e < D; e++ {
		sign := int64(1)
		if negRatio < 0 {
			if rng.IntN(2) == 0 {
				sign = -1
			}
		} else if e < negCount {
			sign = -1
		}
		cells := elemCells[e][:0]
	pick:
		for len(cells) < K {
			c := int32(rng.IntN(M))
			for _, prev := range cells {
				if prev == c {
					continue pick
				}
			}
			cells = append(cells, c)
		}
		elemCells[e] = cells
		id := int32(e + 1) // 1-based so XOR of a lone element is its id
		for _, c := range cells {
			counts[c] += sign
			deg[c]++
			xorID[c] ^= id
		}
	}

	// dhat from pre-peeling counts
	var s, s2 float64
	for i := 0; i < M; i++ {
		c := float64(counts[i])
		s += c
		s2 += c * c
	}
	kf, mf := float64(K), float64(M)
	gamma := kf * (1 - kf/mf)
	dhat := (s2 - s*s/mf) / gamma

	// peeling = 2-core of the hypergraph (sign-independent, idealized decoder)
	queue = queue[:0]
	for c := 0; c < M; c++ {
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
		e := int(xorID[c] - 1)
		id := xorID[c]
		for _, c2 := range elemCells[e] {
			deg[c2]--
			xorID[c2] ^= id
			if deg[c2] == 1 {
				queue = append(queue, c2)
			}
		}
		removed++
	}
	return dhat / float64(D), removed != D
}

func runConfig(cfg config) []trialOut {
	rng := rand.New(rand.NewPCG(0x51B7_F2D0, cfg.seed))
	counts := make([]int64, cfg.M)
	deg := make([]int32, cfg.M)
	xorID := make([]int32, cfg.M)
	elemCells := make([][]int32, cfg.D)
	for i := range elemCells {
		elemCells[i] = make([]int32, 0, cfg.K)
	}
	queue := make([]int32, 0, cfg.M)
	out := make([]trialOut, cfg.trials)
	for t := 0; t < cfg.trials; t++ {
		r, f := runTrial(rng, cfg.M, cfg.K, cfg.D, cfg.negRatio, counts, deg, xorID, elemCells, queue)
		out[t] = trialOut{r, f}
	}
	return out
}

// ---- regularized lower incomplete gamma + chi2 inverse (for chi2 quantiles) ----

func gammaP(a, x float64) float64 {
	if x <= 0 {
		return 0
	}
	lg, _ := math.Lgamma(a)
	if x < a+1 { // series
		sum, term, ap := 1.0/a, 1.0/a, a
		for i := 0; i < 500; i++ {
			ap++
			term *= x / ap
			sum += term
			if math.Abs(term) < math.Abs(sum)*1e-15 {
				break
			}
		}
		return sum * math.Exp(-x+a*math.Log(x)-lg)
	}
	// continued fraction (Lentz)
	b, c, d := x+1-a, 1/1e-300, 0.0
	d = 1 / b
	h := d
	for i := 1; i < 500; i++ {
		an := -float64(i) * (float64(i) - a)
		b += 2
		d = an*d + b
		if math.Abs(d) < 1e-300 {
			d = 1e-300
		}
		c = b + an/c
		if math.Abs(c) < 1e-300 {
			c = 1e-300
		}
		d = 1 / d
		del := d * c
		h *= del
		if math.Abs(del-1) < 1e-15 {
			break
		}
	}
	return 1 - math.Exp(-x+a*math.Log(x)-lg)*h
}

// orderStatLCB returns a one-sided lower confidence bound at level 1-rho for the
// population p-quantile, read off the sorted sample via the normal approximation
// to the binomial order-statistic rank. Accurate in the large-n regime this run
// targets (failed-only n_F on the order of 1e4-1e5). rho is fixed at 0.05, so the
// normal quantile z_{0.95}=1.6448536 is used directly.
func orderStatLCB(sorted []float64, p float64) float64 {
	n := len(sorted)
	if n == 0 {
		return math.NaN()
	}
	nf := float64(n)
	rank := int(math.Floor(nf*p - 1.6448536*math.Sqrt(nf*p*(1-p))))
	if rank < 1 {
		rank = 1
	}
	if rank > n {
		rank = n
	}
	return sorted[rank-1]
}

func chi2Inv(p float64, dof int) float64 {
	a := float64(dof) / 2
	lo, hi := 0.0, float64(dof)*4+200
	for i := 0; i < 200; i++ {
		mid := (lo + hi) / 2
		if gammaP(a, mid/2) < p {
			lo = mid
		} else {
			hi = mid
		}
	}
	return (lo + hi) / 2
}

// ---- stats helpers ----

func quantile(sorted []float64, q float64) float64 {
	if len(sorted) == 0 {
		return math.NaN()
	}
	pos := q * float64(len(sorted)-1)
	i := int(pos)
	if i >= len(sorted)-1 {
		return sorted[len(sorted)-1]
	}
	frac := pos - float64(i)
	return sorted[i]*(1-frac) + sorted[i+1]*frac
}

func meanStd(xs []float64) (float64, float64) {
	if len(xs) == 0 {
		return math.NaN(), math.NaN()
	}
	var s float64
	for _, x := range xs {
		s += x
	}
	m := s / float64(len(xs))
	var v float64
	for _, x := range xs {
		v += (x - m) * (x - m)
	}
	if len(xs) > 1 {
		v /= float64(len(xs) - 1)
	}
	return m, math.Sqrt(v)
}

func parseIntList(text string) ([]int, error) {
	var out []int
	for _, item := range strings.Split(text, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		value, err := strconv.Atoi(item)
		if err != nil || value <= 0 {
			return nil, fmt.Errorf("invalid positive integer %q in list %q", item, text)
		}
		out = append(out, value)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("empty integer list %q", text)
	}
	return out, nil
}

func parseFloatList(text string) ([]float64, error) {
	var out []float64
	for _, item := range strings.Split(text, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		value, err := strconv.ParseFloat(item, 64)
		if err != nil || value <= 0 {
			return nil, fmt.Errorf("invalid positive number %q in list %q", item, text)
		}
		out = append(out, value)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("empty number list %q", text)
	}
	return out, nil
}

func parseSignList(text string) ([]float64, error) {
	var out []float64
	for _, item := range strings.Split(text, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if item == "rand" {
			out = append(out, -1)
			continue
		}
		value, err := strconv.ParseFloat(item, 64)
		if err != nil || value < 0 || value > 1 {
			return nil, fmt.Errorf("invalid sign mode %q in list %q (use rand or a ratio in [0,1])", item, text)
		}
		out = append(out, value)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("empty sign-mode list %q", text)
	}
	return out, nil
}

func main() {
	trials := flag.Int("trials", 10000, "trials per grid config")
	e8trials := flag.Int("e8trials", 20000, "trials per E8 directionality config")
	mListText := flag.String("M-list", "64,256,1024,4096", "comma-separated M values for the main grid")
	kListText := flag.String("k-list", "3,4", "comma-separated k values for the main grid")
	loadListText := flag.String("load-list", "0.4,0.8,1.6,8.0", "comma-separated d/M values for the main grid")
	signListText := flag.String("sign-list", "0,0.1,0.5,0.9,rand", "comma-separated fixed negative ratios and/or rand")
	fixedD := flag.Int("fixed-d", 0, "if positive, run only k=3, neg_ratio=0.5 at this fixed d for every M in M-list")
	runE8 := flag.Bool("e8", true, "include the fixed E8 directionality grid")
	outDir := flag.String("out", "results", "output directory")
	writeRaw := flag.Bool("raw", true, "write per-trial raw CSV (set false for large-trial runs to avoid multi-GB output)")
	jobs := flag.Int("jobs", runtime.NumCPU(), "max concurrent config workers (default = detected CPUs)")
	flag.Parse()
	if *jobs < 1 {
		*jobs = 1
	}
	mList, err := parseIntList(*mListText)
	if err != nil {
		panic(err)
	}
	kList, err := parseIntList(*kListText)
	if err != nil {
		panic(err)
	}
	loads, err := parseFloatList(*loadListText)
	if err != nil {
		panic(err)
	}
	signModes, err := parseSignList(*signListText)
	if err != nil {
		panic(err)
	}
	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		panic(err)
	}

	var cfgs []config
	if *fixedD > 0 {
		for _, M := range mList {
			cfgs = append(cfgs, config{
				kind: "fixed_d", M: M, K: 3, D: *fixedD,
				negRatio: 0.5, trials: *trials,
			})
		}
	} else {
		// main T1 grid
		for _, M := range mList {
			for _, K := range kList {
				if K >= M {
					panic(fmt.Errorf("require k < M, got k=%d M=%d", K, M))
				}
				for _, neg := range signModes { // -1 = iid random signs (Theorem 3.6 random-sign)
					for _, r := range loads {
						d := int(r*float64(M) + 0.5)
						cfgs = append(cfgs, config{kind: "grid", M: M, K: K, D: d, negRatio: neg, trials: *trials})
					}
				}
			}
		}
		// E8 directionality curve: one-sided, k=3, around the peeling transition
		if *runE8 {
			for _, md := range [][2]int{{128, 88}, {128, 92}, {128, 96}, {128, 100}, {128, 104}, {128, 110}, {128, 120},
				{256, 190}, {256, 200}, {256, 210}, {256, 220}, {256, 230}, {256, 250}} {
				cfgs = append(cfgs, config{kind: "e8", M: md[0], K: 3, D: md[1], negRatio: 0, trials: *e8trials})
			}
		}
	}
	for i := range cfgs {
		cfgs[i].seed = uint64(i + 1)
	}

	results := make([][]trialOut, len(cfgs))
	var wg sync.WaitGroup
	sem := make(chan struct{}, *jobs)
	done := 0
	var mu sync.Mutex
	for i := range cfgs {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			results[i] = runConfig(cfgs[i])
			mu.Lock()
			done++
			if done%16 == 0 || done == len(cfgs) {
				fmt.Fprintf(os.Stderr, "progress: %d/%d configs\n", done, len(cfgs))
			}
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	// raw per-trial output (suppressed for large-trial runs via -raw=false)
	rawPath := filepath.Join(*outDir, "t1_raw.csv")
	if *writeRaw {
		rawF, err := os.Create(rawPath)
		if err != nil {
			panic(err)
		}
		rw := bufio.NewWriterSize(rawF, 1<<20)
		fmt.Fprintln(rw, "kind,M,k,d,neg_ratio,trial,dhat_over_d,failed")
		for i, cfg := range cfgs {
			for t, o := range results[i] {
				f := 0
				if o.failed {
					f = 1
				}
				fmt.Fprintf(rw, "%s,%d,%d,%d,%g,%d,%.8f,%d\n", cfg.kind, cfg.M, cfg.K, cfg.D, cfg.negRatio, t, o.ratio, f)
			}
		}
		rw.Flush()
		rawF.Close()
	}

	// summary with theory columns
	sumPath := filepath.Join(*outDir, "t1_summary.csv")
	sumF, err := os.Create(sumPath)
	if err != nil {
		panic(err)
	}
	sw := bufio.NewWriter(sumF)
	fmt.Fprintln(sw, "kind,M,k,d,neg_ratio,trials,mean_ratio,rsd_emp,rsd_theory,rsd_emp_over_theory,"+
		"q05_emp,q05_chi2,q05_normal,q50_emp,q95_emp,q95_chi2,"+
		"fail_rate,failed_n,failed_mean_ratio,failed_q05,lemma5_rel_bound,"+
		"q01_emp,q01_chi2,failed_q01,failed_q05_oslcb95,failed_q01_oslcb95,"+
		"q99_emp,q99_chi2")
	chi2cache := map[int][2]float64{}
	for i, cfg := range cfgs {
		rs := make([]float64, 0, len(results[i]))
		fs := make([]float64, 0, len(results[i]))
		for _, o := range results[i] {
			rs = append(rs, o.ratio)
			if o.failed {
				fs = append(fs, o.ratio)
			}
		}
		mean, std := meanStd(rs)
		sorted := append([]float64(nil), rs...)
		sort.Float64s(sorted)
		fsorted := append([]float64(nil), fs...)
		sort.Float64s(fsorted)
		fMean, _ := meanStd(fs)

		mf, df := float64(cfg.M), float64(cfg.D)
		rsdTh := math.Sqrt(2 * (df - 1) / (df * (mf - 1)))
		qc, ok := chi2cache[cfg.M]
		if !ok {
			qc = [2]float64{chi2Inv(0.05, cfg.M-1) / (mf - 1), chi2Inv(0.95, cfg.M-1) / (mf - 1)}
			chi2cache[cfg.M] = qc
		}
		q05n := 1 - 1.6448536*rsdTh
		pFail := float64(len(fs)) / float64(len(rs))
		bound := math.NaN()
		if pFail > 0 && pFail < 1 {
			bound = rsdTh * math.Sqrt((1-pFail)/pFail)
		}
		q01chi2 := chi2Inv(0.01, cfg.M-1) / (mf - 1)
		q99chi2 := chi2Inv(0.99, cfg.M-1) / (mf - 1)
		fmt.Fprintf(sw, "%s,%d,%d,%d,%g,%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.5f,%d,%.6f,%.4f,%.5f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
			cfg.kind, cfg.M, cfg.K, cfg.D, cfg.negRatio, len(rs),
			mean, std, rsdTh, std/rsdTh,
			quantile(sorted, 0.05), qc[0], q05n, quantile(sorted, 0.50), quantile(sorted, 0.95), qc[1],
			pFail, len(fs), fMean, quantile(fsorted, 0.05), bound,
			quantile(sorted, 0.01), q01chi2, quantile(fsorted, 0.01),
			orderStatLCB(fsorted, 0.05), orderStatLCB(fsorted, 0.01),
			quantile(sorted, 0.99), q99chi2)
	}
	sw.Flush()
	sumF.Close()
	if *writeRaw {
		fmt.Printf("wrote %s and %s\n", rawPath, sumPath)
	} else {
		fmt.Printf("wrote %s (raw suppressed)\n", sumPath)
	}
}
