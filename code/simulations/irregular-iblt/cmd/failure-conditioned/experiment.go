package main

import (
	"fmt"
	"math"
)

// signSalt is XORed with the key and the experiment seed to derive an iid
// coin flip for the "iid" sign mode. It is disjoint from the salts used by
// degreeDistribution.degree and mapper.positions, so the sign is generated
// independently of the degree/mapping randomness, as Corollary 4.9 requires.
const signSalt = 0x2545f4914f6cdd1d

// signMode controls how the d differing keys are split into positive and
// negative sides.
//
//   - signIID:      each of the d keys gets an independent, uniform +/-1 sign
//     (d+ ~ Binomial(d, 1/2)), the population Corollary 4.9 analyzes.
//   - signAllPlus:  every key is positive (the paper's original one-sided
//     construction, kept as a directional-bias control, cf. Plain's E8).
//   - signBalanced: exactly d/2 positive and d/2 negative keys by
//     construction (fixed, not iid) -- a second non-iid control.
type signMode string

const (
	signIID       signMode = "iid"
	signAllPlus   signMode = "plus1"
	signBalanced  signMode = "balanced"
	signModeCount          = 3
)

func parseSignModes(raw []string) ([]signMode, error) {
	modes := make([]signMode, 0, len(raw))
	for _, r := range raw {
		switch signMode(r) {
		case signIID, signAllPlus, signBalanced:
			modes = append(modes, signMode(r))
		default:
			return nil, fmt.Errorf("unknown sign mode %q (want iid, plus1, or balanced)", r)
		}
	}
	if len(modes) == 0 {
		return nil, fmt.Errorf("empty sign mode list")
	}
	return modes, nil
}

type config struct {
	D       int
	MValues []int
	Modes   []signMode
	Trials  int
	Seed    uint64
	OutDir  string
}

// trialResult is the per-trial record needed for failure-conditioned
// aggregation: the estimator ratio and whether exact peeling decode
// succeeded (decode failure is event F).
type trialResult struct {
	Ratio   float64
	Decoded bool
}

// runCell runs cfg.Trials independent trials at a fixed (M, mode) and returns
// one trialResult per trial.
func runCell(cfg config, m int, mode signMode) ([]trialResult, error) {
	results := make([]trialResult, cfg.Trials)
	gammaVal := optimizedIrregular.gamma(m)
	for trial := 0; trial < cfg.Trials; trial++ {
		items, expectPositive, expectNegative, err := generateSignedSet(cfg, m, mode, trial)
		if err != nil {
			return nil, err
		}
		mp := mapper{Cells: m, Seed: cfg.Seed, Distribution: optimizedIrregular}
		table := make([]cell, m)
		for _, it := range items {
			mp.apply(table, it.Key, it.Sign)
		}
		energy := centeredEnergy(table)
		dHat := energy / gammaVal
		decoded, err := verifyDecode(table, mp, expectPositive, expectNegative)
		if err != nil {
			return nil, fmt.Errorf("m=%d mode=%s trial=%d: %w", m, mode, trial, err)
		}
		results[trial] = trialResult{Ratio: dHat / float64(cfg.D), Decoded: decoded}
	}
	return results, nil
}

type signedItem struct {
	Key  uint64
	Sign int64
}

// generateSignedSet builds the d differing keys for one trial under the
// requested sign mode. The mode only changes the sign assignment; the key
// namespace (per side, per M) still guarantees no cross-sign collisions.
func generateSignedSet(cfg config, m int, mode signMode, trial int) ([]signedItem, map[uint64]struct{}, map[uint64]struct{}, error) {
	items := make([]signedItem, 0, cfg.D)
	expectPositive := make(map[uint64]struct{})
	expectNegative := make(map[uint64]struct{})

	switch mode {
	case signIID:
		// Single key stream; sign is an independent coin flip per key using a
		// salt disjoint from degree/mapping randomness.
		for i := 0; i < cfg.D; i++ {
			key := makeItem(cfg.Seed, m, trial, 1, i)
			bit := mix64(key^cfg.Seed^signSalt) & 1
			if bit == 0 {
				if _, dup := expectPositive[key]; dup {
					return nil, nil, nil, fmt.Errorf("duplicate key %x", key)
				}
				expectPositive[key] = struct{}{}
				items = append(items, signedItem{Key: key, Sign: 1})
			} else {
				if _, dup := expectNegative[key]; dup {
					return nil, nil, nil, fmt.Errorf("duplicate key %x", key)
				}
				expectNegative[key] = struct{}{}
				items = append(items, signedItem{Key: key, Sign: -1})
			}
		}
	case signAllPlus, signBalanced:
		plus := cfg.D
		if mode == signBalanced {
			plus = cfg.D / 2
		}
		minus := cfg.D - plus
		for i := 0; i < plus; i++ {
			key := makeItem(cfg.Seed, m, trial, 1, i)
			expectPositive[key] = struct{}{}
			items = append(items, signedItem{Key: key, Sign: 1})
		}
		for i := 0; i < minus; i++ {
			key := makeItem(cfg.Seed, m, trial, 2, i)
			if _, dup := expectPositive[key]; dup {
				return nil, nil, nil, fmt.Errorf("cross-side key collision %x", key)
			}
			expectNegative[key] = struct{}{}
			items = append(items, signedItem{Key: key, Sign: -1})
		}
	default:
		return nil, nil, nil, fmt.Errorf("unknown sign mode %q", mode)
	}
	return items, expectPositive, expectNegative, nil
}

func centeredEnergy(table []cell) float64 {
	var squares, sum float64
	for _, c := range table {
		v := float64(c.Count)
		squares += v * v
		sum += v
	}
	return squares - sum*sum/float64(len(table))
}

func verifyDecode(table []cell, m mapper, positive, negative map[uint64]struct{}) (bool, error) {
	decoded, ok, err := decodeTable(table, m)
	if err != nil || !ok {
		return ok, err
	}
	if len(decoded.Positive) != len(positive) || len(decoded.Negative) != len(negative) {
		return false, fmt.Errorf("decoded positive/negative=%d/%d, want %d/%d", len(decoded.Positive), len(decoded.Negative), len(positive), len(negative))
	}
	for key := range positive {
		if _, found := decoded.Positive[key]; !found {
			return false, fmt.Errorf("missing positive key %x", key)
		}
	}
	for key := range negative {
		if _, found := decoded.Negative[key]; !found {
			return false, fmt.Errorf("missing negative key %x", key)
		}
	}
	return true, nil
}

// makeItem derives a deterministic per-(m, trial, side, index) key. Table
// size m is folded in so the same trial index reuses independent key
// populations across the M sweep (matching the parent experiment's approach
// of re-generating items per cell count).
func makeItem(seed uint64, m, trial, side, index int) uint64 {
	packed := (uint64(m) << 40) |
		(uint64(trial+1) << 20) |
		(uint64(side) << 16) |
		uint64(index+1)
	key := mix64(packed ^ seed)
	if key == 0 {
		key = 1
	}
	return key
}

// cellSummary is the aggregated failure-conditioned statistic for one
// (M, sign mode) grid point.
type cellSummary struct {
	M           int
	Mode        signMode
	Trials      int
	PF          float64
	NFail       int
	MeanFailed  float64
	MeanAll     float64
	StdFailed   float64
	MCSE        float64
	Bound       float64
	BiasAbs     float64
	WithinBound bool
	Sufficient  bool
}

func summarize(cfg config, m int, mode signMode, results []trialResult) cellSummary {
	n := len(results)
	var failedRatios []float64
	sumAll := 0.0
	for _, r := range results {
		sumAll += r.Ratio
		if !r.Decoded {
			failedRatios = append(failedRatios, r.Ratio)
		}
	}
	meanAll := sumAll / float64(n)
	nFail := len(failedRatios)
	pF := float64(nFail) / float64(n)

	meanFailed := math.NaN()
	stdFailed := math.NaN()
	mcSE := math.NaN()
	if nFail > 0 {
		sum := 0.0
		for _, v := range failedRatios {
			sum += v
		}
		meanFailed = sum / float64(nFail)
		if nFail > 1 {
			ss := 0.0
			for _, v := range failedRatios {
				ss += (v - meanFailed) * (v - meanFailed)
			}
			stdFailed = math.Sqrt(ss / float64(nFail-1))
			mcSE = stdFailed / math.Sqrt(float64(nFail))
		}
	}

	bound := math.NaN()
	if pF > 0 && pF < 1 {
		sigmaOverGamma := optimizedIrregular.sigmaEnergy(m) / optimizedIrregular.gamma(m)
		bound = (sigmaOverGamma / math.Sqrt(float64(cfg.D))) * math.Sqrt((1-pF)/pF)
	}

	biasAbs := math.NaN()
	withinBound := false
	if !math.IsNaN(meanFailed) {
		biasAbs = math.Abs(meanFailed - 1)
		withinBound = !math.IsNaN(bound) && biasAbs <= bound
	}

	sufficient := pF >= 0.02 && nFail >= 200

	return cellSummary{
		M: m, Mode: mode, Trials: n, PF: pF, NFail: nFail,
		MeanFailed: meanFailed, MeanAll: meanAll, StdFailed: stdFailed, MCSE: mcSE,
		Bound: bound, BiasAbs: biasAbs, WithinBound: withinBound, Sufficient: sufficient,
	}
}
