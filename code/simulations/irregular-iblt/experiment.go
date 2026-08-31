package main

import (
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

type config struct {
	Trials        int
	DValues       []int
	PlusFractions []float64
	MValues       []int
	OutDir        string
	Seed          uint64
}

type observation struct {
	CaseID           int
	Trial            int
	D                int
	Plus             int
	Minus            int
	PlusFraction     float64
	M                int
	Degree2          int
	Degree3          int
	Degree18         int
	IrregularEnergy  float64
	GammaCorrect     float64
	GammaMeanDegree  float64
	DHatCorrect      float64
	RatioCorrect     float64
	DHatMeanDegree   float64
	RatioMeanDegree  float64
	RegularEnergy    float64
	GammaRegular     float64
	DHatRegular      float64
	RatioRegular     float64
	IrregularDecoded bool
	RegularDecoded   bool
}

type signedItem struct {
	Key  uint64
	Sign int64
}

func runExperiment(cfg config) ([]observation, error) {
	for _, m := range cfg.MValues {
		if err := optimizedIrregular.validate(m); err != nil {
			return nil, err
		}
		if err := regularThree.validate(m); err != nil {
			return nil, err
		}
	}
	observations := make([]observation, 0, len(cfg.DValues)*len(cfg.PlusFractions)*cfg.Trials*len(cfg.MValues))
	caseID := 0
	for _, d := range cfg.DValues {
		for _, plusFraction := range cfg.PlusFractions {
			plus := int(math.Round(float64(d) * plusFraction))
			minus := d - plus
			actualFraction := float64(plus) / float64(d)
			for trial := 0; trial < cfg.Trials; trial++ {
				rows, err := runTrial(cfg, caseID, trial, d, plus, minus, actualFraction)
				if err != nil {
					return nil, err
				}
				observations = append(observations, rows...)
			}
			caseID++
		}
	}
	return observations, nil
}

func runTrial(cfg config, caseID, trial, d, plus, minus int, plusFraction float64) ([]observation, error) {
	items := make([]signedItem, 0, d)
	expectPositive := make(map[uint64]struct{}, plus)
	expectNegative := make(map[uint64]struct{}, minus)
	degreeCounts := map[int]int{2: 0, 3: 0, 18: 0}

	add := func(side, count int, sign int64, expected map[uint64]struct{}) error {
		for i := 0; i < count; i++ {
			key := makeItem(cfg.Seed, caseID, trial, side, i)
			if _, duplicate := expected[key]; duplicate {
				return fmt.Errorf("duplicate generated key %x", key)
			}
			if sign > 0 {
				if _, collision := expectNegative[key]; collision {
					return fmt.Errorf("generated cross-sign key collision %x", key)
				}
			} else if _, collision := expectPositive[key]; collision {
				return fmt.Errorf("generated cross-sign key collision %x", key)
			}
			expected[key] = struct{}{}
			items = append(items, signedItem{Key: key, Sign: sign})
			degreeCounts[optimizedIrregular.degree(key, cfg.Seed)]++
		}
		return nil
	}
	if err := add(1, plus, 1, expectPositive); err != nil {
		return nil, err
	}
	if err := add(2, minus, -1, expectNegative); err != nil {
		return nil, err
	}

	rows := make([]observation, 0, len(cfg.MValues))
	for _, cells := range cfg.MValues {
		irregularMapper := mapper{Cells: cells, Seed: cfg.Seed, Distribution: optimizedIrregular}
		regularMapper := mapper{Cells: cells, Seed: cfg.Seed ^ 0x6a09e667f3bcc909, Distribution: regularThree}
		irregularTable := make([]cell, cells)
		regularTable := make([]cell, cells)
		for _, item := range items {
			irregularMapper.apply(irregularTable, item.Key, item.Sign)
			regularMapper.apply(regularTable, item.Key, item.Sign)
		}

		irregularEnergy := centeredEnergy(irregularTable)
		gammaCorrect := optimizedIrregular.gamma(cells)
		gammaMeanDegree := optimizedIrregular.meanDegreeGamma(cells)
		dHatCorrect := irregularEnergy / gammaCorrect
		dHatMeanDegree := irregularEnergy / gammaMeanDegree

		regularEnergy := centeredEnergy(regularTable)
		gammaRegular := regularThree.gamma(cells)
		dHatRegular := regularEnergy / gammaRegular

		irregularDecoded, err := verifyDecode(irregularTable, irregularMapper, expectPositive, expectNegative)
		if err != nil {
			return nil, fmt.Errorf("case=%d trial=%d m=%d irregular decode: %w", caseID, trial, cells, err)
		}
		regularDecoded, err := verifyDecode(regularTable, regularMapper, expectPositive, expectNegative)
		if err != nil {
			return nil, fmt.Errorf("case=%d trial=%d m=%d regular decode: %w", caseID, trial, cells, err)
		}

		rows = append(rows, observation{
			CaseID: caseID, Trial: trial, D: d, Plus: plus, Minus: minus, PlusFraction: plusFraction, M: cells,
			Degree2: degreeCounts[2], Degree3: degreeCounts[3], Degree18: degreeCounts[18],
			IrregularEnergy: irregularEnergy, GammaCorrect: gammaCorrect, GammaMeanDegree: gammaMeanDegree,
			DHatCorrect: dHatCorrect, RatioCorrect: dHatCorrect / float64(d),
			DHatMeanDegree: dHatMeanDegree, RatioMeanDegree: dHatMeanDegree / float64(d),
			RegularEnergy: regularEnergy, GammaRegular: gammaRegular,
			DHatRegular: dHatRegular, RatioRegular: dHatRegular / float64(d),
			IrregularDecoded: irregularDecoded, RegularDecoded: regularDecoded,
		})
	}
	return rows, nil
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

func makeItem(seed uint64, caseID, trial, side, index int) uint64 {
	packed := (uint64(caseID+1) << 52) |
		(uint64(trial+1) << 32) |
		(uint64(side) << 28) |
		uint64(index+1)
	key := mix64(packed ^ seed)
	if key == 0 {
		key = 1
	}
	return key
}

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

func parseFractions(raw string) ([]float64, error) {
	parts := strings.Split(raw, ",")
	values := make([]float64, 0, len(parts))
	for _, part := range parts {
		value, err := strconv.ParseFloat(strings.TrimSpace(part), 64)
		if err != nil || value < 0 || value > 1 {
			return nil, fmt.Errorf("%q is not a fraction in [0,1]", part)
		}
		values = append(values, value)
	}
	if len(values) == 0 {
		return nil, fmt.Errorf("empty list")
	}
	return values, nil
}
