// Command failure-conditioned validates §4.4 Corollary 4.9: once
// an irregular IBLT's peeling decode fails (event F), the sign pattern of the
// symmetric-difference elements does not bias d_hat/d beyond an explicit
// sigma_E/gamma-driven bound, for iid uniform +/-1 signs. It also runs two
// fixed (non-iid) sign controls -- all-positive and exactly balanced -- which
// are expected to show a directional bias analogous to Plain's E8 and are
// reported without a pass/fail judgment.
//
// See ../../RESULTS.md, section "Failure-conditioned validation" for the
// full write-up, grid, and reproduction commands.
package main

import (
	"encoding/csv"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
)

type job struct {
	m    int
	mode signMode
}

func main() {
	var (
		d        = flag.Int("d", 1024, "symmetric-difference size")
		mRaw     = flag.String("m", "1024,1050,1075,1100,1125,1150,1175,1200,1250,1300", "comma-separated table sizes")
		modesRaw = flag.String("sign-modes", "iid,plus1,balanced", "comma-separated sign modes: iid, plus1 (all-positive), balanced (fixed 50/50)")
		trials   = flag.Int("trials", 20000, "trials per (M, sign mode) grid point")
		seed     = flag.Uint64("seed", 0x6669342d636f7234, "deterministic experiment seed (distinct from the moment-adapter harness)")
		outDir   = flag.String("out", "results/failure-conditioned", "output directory")
		workers  = flag.Int("workers", runtime.NumCPU(), "parallel worker count across (M, sign mode) grid cells")
	)
	flag.Parse()

	mValues, err := parsePositiveInts(*mRaw)
	if err != nil {
		fatalf("invalid -m: %v", err)
	}
	modes, err := parseSignModes(strings.Split(*modesRaw, ","))
	if err != nil {
		fatalf("invalid -sign-modes: %v", err)
	}
	if *trials <= 0 {
		fatalf("trials must be positive")
	}
	if *workers <= 0 {
		*workers = 1
	}

	cfg := config{D: *d, MValues: mValues, Modes: modes, Trials: *trials, Seed: *seed, OutDir: *outDir}
	for _, m := range cfg.MValues {
		if err := optimizedIrregular.validate(m); err != nil {
			fatalf("invalid grid point m=%d: %v", m, err)
		}
	}

	total := len(cfg.MValues) * len(cfg.Modes)
	fmt.Printf("failure-conditioned: d=%d, %d table sizes x %d sign modes x %d trials = %d decode attempts, workers=%d\n",
		cfg.D, len(cfg.MValues), len(cfg.Modes), cfg.Trials, total*cfg.Trials, *workers)

	jobs := make([]job, 0, total)
	for _, m := range cfg.MValues {
		for _, mode := range cfg.Modes {
			jobs = append(jobs, job{m: m, mode: mode})
		}
	}

	summaries := make([]cellSummary, len(jobs))
	rawResults := make([][]trialResult, len(jobs))

	jobCh := make(chan int, len(jobs))
	for i := range jobs {
		jobCh <- i
	}
	close(jobCh)

	var wg sync.WaitGroup
	var firstErr error
	var errMu sync.Mutex
	for w := 0; w < *workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for idx := range jobCh {
				j := jobs[idx]
				results, err := runCell(cfg, j.m, j.mode)
				if err != nil {
					errMu.Lock()
					if firstErr == nil {
						firstErr = fmt.Errorf("m=%d mode=%s: %w", j.m, j.mode, err)
					}
					errMu.Unlock()
					return
				}
				rawResults[idx] = results
				summaries[idx] = summarize(cfg, j.m, j.mode, results)
			}
		}()
	}
	wg.Wait()
	if firstErr != nil {
		fatalf("experiment failed: %v", firstErr)
	}

	if err := os.MkdirAll(cfg.OutDir, 0o755); err != nil {
		fatalf("mkdir: %v", err)
	}
	if err := writeRaw(filepath.Join(cfg.OutDir, "raw_trials.csv"), cfg, jobs, rawResults); err != nil {
		fatalf("write raw: %v", err)
	}
	if err := writeSummary(filepath.Join(cfg.OutDir, "summary.csv"), summaries); err != nil {
		fatalf("write summary: %v", err)
	}
	if err := writeMoments(filepath.Join(cfg.OutDir, "moments.csv")); err != nil {
		fatalf("write moments: %v", err)
	}

	fmt.Printf("wrote %d grid-cell summaries and %d raw trial rows to %s\n", len(summaries), total*cfg.Trials, cfg.OutDir)
}

func writeRaw(path string, cfg config, jobs []job, rawResults [][]trialResult) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	w := csv.NewWriter(file)
	if err := w.Write([]string{"m", "sign_mode", "trial", "d", "ratio_correct", "decoded"}); err != nil {
		return err
	}
	for i, j := range jobs {
		for trial, r := range rawResults[i] {
			row := []string{
				strconv.Itoa(j.m), string(j.mode), strconv.Itoa(trial), strconv.Itoa(cfg.D),
				f64(r.Ratio), strconv.FormatBool(r.Decoded),
			}
			if err := w.Write(row); err != nil {
				return err
			}
		}
	}
	w.Flush()
	return w.Error()
}

func writeSummary(path string, summaries []cellSummary) error {
	sort.Slice(summaries, func(i, j int) bool {
		if summaries[i].Mode != summaries[j].Mode {
			return summaries[i].Mode < summaries[j].Mode
		}
		return summaries[i].M < summaries[j].M
	})
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	w := csv.NewWriter(file)
	header := []string{
		"sign_mode", "m", "trials", "p_f", "n_fail", "mean_ratio_failed", "mean_ratio_all",
		"std_failed", "mc_se_failed", "theoretical_bound", "abs_bias_failed", "within_bound", "sufficient_sample",
	}
	if err := w.Write(header); err != nil {
		return err
	}
	for _, s := range summaries {
		row := []string{
			string(s.Mode), strconv.Itoa(s.M), strconv.Itoa(s.Trials), f64(s.PF), strconv.Itoa(s.NFail),
			f64(s.MeanFailed), f64(s.MeanAll), f64(s.StdFailed), f64(s.MCSE), f64(s.Bound), f64(s.BiasAbs),
			strconv.FormatBool(s.WithinBound), strconv.FormatBool(s.Sufficient),
		}
		if err := w.Write(row); err != nil {
			return err
		}
	}
	w.Flush()
	return w.Error()
}

func writeMoments(path string) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	w := csv.NewWriter(file)
	if err := w.Write([]string{"e_d", "e_d2", "e_d3", "e_d4"}); err != nil {
		return err
	}
	row := []string{
		f64(optimizedIrregular.mean()), f64(optimizedIrregular.secondMoment()),
		f64(optimizedIrregular.thirdMoment()), f64(optimizedIrregular.fourthMoment()),
	}
	if err := w.Write(row); err != nil {
		return err
	}
	w.Flush()
	return w.Error()
}

func f64(v float64) string { return strconv.FormatFloat(v, 'g', 10, 64) }

func parsePositiveInts(raw string) ([]int, error) {
	parts := strings.Split(raw, ",")
	values := make([]int, 0, len(parts))
	seen := make(map[int]struct{}, len(parts))
	for _, part := range parts {
		value, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || value <= 0 {
			return nil, fmt.Errorf("%q is not a positive integer", part)
		}
		if _, exists := seen[value]; exists {
			continue
		}
		seen[value] = struct{}{}
		values = append(values, value)
	}
	if len(values) == 0 {
		return nil, fmt.Errorf("empty list")
	}
	sort.Ints(values)
	return values, nil
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "error: "+format+"\n", args...)
	os.Exit(1)
}
