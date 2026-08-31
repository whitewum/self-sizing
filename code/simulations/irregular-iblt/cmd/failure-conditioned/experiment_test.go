package main

import (
	"math"
	"testing"
)

// TestMomentsMatchPublishedDistribution guards the third/fourth moments this
// package adds against the second-moment values already checked in the
// parent package's TestPublishedDistributionMoments.
func TestMomentsMatchPublishedDistribution(t *testing.T) {
	if got := optimizedIrregular.mean(); math.Abs(got-4.725) > 1e-9 {
		t.Fatalf("mean=%g, want 4.725", got)
	}
	if got := optimizedIrregular.secondMoment(); math.Abs(got-47.625) > 1e-9 {
		t.Fatalf("E[D^2]=%g, want 47.625", got)
	}
	if got := optimizedIrregular.thirdMoment(); math.Abs(got-749.775) > 1e-6 {
		t.Fatalf("E[D^3]=%g, want 749.775", got)
	}
	if got := optimizedIrregular.fourthMoment(); math.Abs(got-13183.125) > 1e-4 {
		t.Fatalf("E[D^4]=%g, want 13183.125", got)
	}
}

// TestSigmaOverGammaAtM1024 checks the sigma_E/gamma_irr ~= 1.053 figure
// quoted in Corollary 4.9 for M=1024.
func TestSigmaOverGammaAtM1024(t *testing.T) {
	ratio := optimizedIrregular.sigmaEnergy(1024) / optimizedIrregular.gamma(1024)
	if math.Abs(ratio-1.053) > 0.005 {
		t.Fatalf("sigma_E/gamma at M=1024 = %g, want ~1.053", ratio)
	}
}

// TestIIDSignModeSanity checks that generateSignedSet under signIID produces
// d+ ~ Binomial(d, 1/2): mean fraction near 0.5 and dispersion consistent
// with independent fair coin flips, across many independent trials. This is
// the sanity check requested for the new sign-generation mode: signs must be
// iid uniform +/-1 and independent of the mapping/degree randomness.
func TestIIDSignModeSanity(t *testing.T) {
	cfg := config{D: 1024, Seed: 0x6669342d636f7234}
	const trials = 2000
	fractions := make([]float64, trials)
	for trial := 0; trial < trials; trial++ {
		_, positive, negative, err := generateSignedSet(cfg, 1024, signIID, trial)
		if err != nil {
			t.Fatalf("trial %d: %v", trial, err)
		}
		if len(positive)+len(negative) != cfg.D {
			t.Fatalf("trial %d: |positive|+|negative|=%d, want %d", trial, len(positive)+len(negative), cfg.D)
		}
		fractions[trial] = float64(len(positive)) / float64(cfg.D)
	}
	mean := 0.0
	for _, f := range fractions {
		mean += f
	}
	mean /= float64(trials)
	// Binomial(d,1/2)/d has mean 0.5 and std 1/(2*sqrt(d)) ~= 0.0156 for d=1024.
	// The mean of `trials` such iid fractions has std ~= 0.0156/sqrt(2000) ~= 0.00035;
	// allow a generous 10-sigma window to keep the test far from flaky.
	if math.Abs(mean-0.5) > 0.0035 {
		t.Fatalf("mean plus-fraction over %d trials = %g, want ~0.5", trials, mean)
	}

	variance := 0.0
	for _, f := range fractions {
		variance += (f - mean) * (f - mean)
	}
	variance /= float64(trials - 1)
	expectedVariance := 0.25 / float64(cfg.D)
	if variance < expectedVariance*0.5 || variance > expectedVariance*1.5 {
		t.Fatalf("plus-fraction variance=%g, want close to Binomial(d,1/2)/d variance=%g", variance, expectedVariance)
	}
}

// TestFixedSignModesAreDeterministicSplits checks that the two fixed
// (non-iid) sign modes produce exactly the requested split every trial,
// unlike signIID.
func TestFixedSignModesAreDeterministicSplits(t *testing.T) {
	cfg := config{D: 1024, Seed: 0x6669342d636f7234}
	_, positive, negative, err := generateSignedSet(cfg, 1024, signAllPlus, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(positive) != cfg.D || len(negative) != 0 {
		t.Fatalf("plus1: positive=%d negative=%d, want %d/0", len(positive), len(negative), cfg.D)
	}
	_, positive, negative, err = generateSignedSet(cfg, 1024, signBalanced, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(positive) != cfg.D/2 || len(negative) != cfg.D/2 {
		t.Fatalf("balanced: positive=%d negative=%d, want %d/%d", len(positive), len(negative), cfg.D/2, cfg.D/2)
	}
}

// TestRunCellSmoke exercises the full per-trial pipeline (mapping, energy,
// decode) at a small trial count and low load so decode succeeds, catching
// gross wiring errors before a full grid run.
func TestRunCellSmoke(t *testing.T) {
	cfg := config{D: 64, Seed: 0x6669342d636f7234, Trials: 20}
	for _, mode := range []signMode{signIID, signAllPlus, signBalanced} {
		results, err := runCell(cfg, 512, mode)
		if err != nil {
			t.Fatalf("mode=%s: %v", mode, err)
		}
		if len(results) != cfg.Trials {
			t.Fatalf("mode=%s: got %d results, want %d", mode, len(results), cfg.Trials)
		}
		for i, r := range results {
			if math.IsNaN(r.Ratio) || math.IsInf(r.Ratio, 0) {
				t.Fatalf("mode=%s trial=%d: invalid ratio %g", mode, i, r.Ratio)
			}
		}
	}
}

func TestParseSignModes(t *testing.T) {
	modes, err := parseSignModes([]string{"iid", "plus1", "balanced"})
	if err != nil || len(modes) != 3 {
		t.Fatalf("modes=%v err=%v", modes, err)
	}
	if _, err := parseSignModes([]string{"bogus"}); err == nil {
		t.Fatal("expected error for unknown sign mode")
	}
}
