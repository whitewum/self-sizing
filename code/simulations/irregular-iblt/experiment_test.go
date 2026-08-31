package main

import (
	"math"
	"testing"
)

func TestPublishedDistributionMoments(t *testing.T) {
	if got := optimizedIrregular.mean(); math.Abs(got-4.725) > 1e-12 {
		t.Fatalf("mean=%g, want 4.725", got)
	}
	if got := optimizedIrregular.secondMoment(); math.Abs(got-47.625) > 1e-12 {
		t.Fatalf("second moment=%g, want 47.625", got)
	}
	if got := optimizedIrregular.variance(); math.Abs(got-25.299375) > 1e-12 {
		t.Fatalf("variance=%g, want 25.299375", got)
	}
	if got := optimizedIrregular.gamma(24); math.Abs(got-2.740625) > 1e-12 {
		t.Fatalf("gamma(24)=%g, want 2.740625", got)
	}
	if optimizedIrregular.meanDegreeGamma(24) <= optimizedIrregular.gamma(24) {
		t.Fatal("mean-degree plug-in should exceed correct gamma for nonzero degree variance")
	}
}

func TestPositionsAreDistinctAndMatchDegree(t *testing.T) {
	m := mapper{Cells: 24, Seed: 7, Distribution: optimizedIrregular}
	for key := uint64(1); key <= 1000; key++ {
		positions := make(map[int]struct{})
		m.positions(key, func(position int) {
			if position < 0 || position >= m.Cells {
				t.Fatalf("position %d outside table", position)
			}
			if _, duplicate := positions[position]; duplicate {
				t.Fatalf("key %d repeats position %d", key, position)
			}
			positions[position] = struct{}{}
		})
		if len(positions) != m.degree(key) {
			t.Fatalf("key %d has %d positions, degree %d", key, len(positions), m.degree(key))
		}
	}
}

func TestCommonElementCancels(t *testing.T) {
	m := mapper{Cells: 50, Seed: 7, Distribution: optimizedIrregular}
	table := make([]cell, m.Cells)
	key := uint64(0x123456789abcdef)
	m.apply(table, key, 1)
	m.apply(table, key, -1)
	for i, c := range table {
		if c != (cell{}) {
			t.Fatalf("cell %d did not cancel: %+v", i, c)
		}
	}
}

func TestDecodeAndMomentSmoke(t *testing.T) {
	cfg := config{Trials: 1, DValues: []int{64}, PlusFractions: []float64{0.5}, MValues: []int{24, 512}, Seed: 0x6972722d66322d31}
	observations, err := runExperiment(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if len(observations) != 2 {
		t.Fatalf("observations=%d, want 2", len(observations))
	}
	for _, o := range observations {
		if math.IsNaN(o.RatioCorrect) || math.IsInf(o.RatioCorrect, 0) {
			t.Fatalf("invalid correct ratio at M=%d: %g", o.M, o.RatioCorrect)
		}
	}
	if !observations[1].IrregularDecoded || !observations[1].RegularDecoded {
		t.Fatalf("low-load exact decoders did not both finish: irregular=%v regular=%v", observations[1].IrregularDecoded, observations[1].RegularDecoded)
	}
}

func TestParseLists(t *testing.T) {
	ints, err := parsePositiveInts("64, 18,64,24")
	if err != nil || len(ints) != 3 || ints[0] != 18 || ints[2] != 64 {
		t.Fatalf("ints=%v err=%v", ints, err)
	}
	fractions, err := parseFractions("0,0.5,1")
	if err != nil || len(fractions) != 3 {
		t.Fatalf("fractions=%v err=%v", fractions, err)
	}
}
