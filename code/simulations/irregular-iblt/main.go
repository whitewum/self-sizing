package main

import (
	"flag"
	"fmt"
	"os"
)

func main() {
	var (
		trials     = flag.Int("trials", 30, "trials per (d, plus-fraction) case")
		dValuesRaw = flag.String("d", "256,1024,4096", "comma-separated symmetric-difference sizes")
		plusRaw    = flag.String("plus-fractions", "0,0.5,1", "comma-separated fractions of positive differences")
		mValuesRaw = flag.String("m", "18,24,32,50,100,150,750,1100,1300,5000", "comma-separated table sizes")
		outDir     = flag.String("out", "results/quick", "output directory")
		seed       = flag.Uint64("seed", 0x6972722d66322d31, "deterministic experiment seed")
	)
	flag.Parse()

	dValues, err := parsePositiveInts(*dValuesRaw)
	if err != nil {
		fatalf("invalid -d: %v", err)
	}
	mValues, err := parsePositiveInts(*mValuesRaw)
	if err != nil {
		fatalf("invalid -m: %v", err)
	}
	plusFractions, err := parseFractions(*plusRaw)
	if err != nil {
		fatalf("invalid -plus-fractions: %v", err)
	}
	if *trials <= 0 {
		fatalf("trials must be positive")
	}

	cfg := config{Trials: *trials, DValues: dValues, PlusFractions: plusFractions, MValues: mValues, OutDir: *outDir, Seed: *seed}
	baseTrials := len(cfg.DValues) * len(cfg.PlusFractions) * cfg.Trials
	fmt.Printf("irregular-iblt-f2: %d base trials x %d table sizes; distribution=0.15x^2+0.725x^3+0.125x^18\n", baseTrials, len(cfg.MValues))
	observations, err := runExperiment(cfg)
	if err != nil {
		fatalf("experiment failed: %v", err)
	}
	if err := writeReports(cfg, observations); err != nil {
		fatalf("write reports: %v", err)
	}
	fmt.Printf("wrote %d observations from %d exact-gated base trials to %s\n", len(observations), baseTrials, cfg.OutDir)
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "error: "+format+"\n", args...)
	os.Exit(1)
}
