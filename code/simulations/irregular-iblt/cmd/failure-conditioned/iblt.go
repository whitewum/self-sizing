package main

// This file duplicates the minimal irregular-IBLT primitives (degree
// distribution, without-replacement mapper, checksum, exact signed peeling
// decoder) from ../../iblt.go so that this experiment can add moments (E[D^3],
// E[D^4]) and an iid-sign generation mode without touching the already
// validated moment-adapter harness in the parent package. Salts and constants
// are deliberately identical in spirit but the two experiments use disjoint
// seeds, so there is no cross-contamination between the two key populations.

import (
	"fmt"
	"math"
	"math/bits"
)

type degreeTerm struct {
	Degree      int
	Probability float64
}

type degreeDistribution struct {
	Name  string
	Terms []degreeTerm
}

// optimizedIrregular is the simulated-annealing design reported by Lázaro
// and Matuz, Irregular Invertible Bloom Look-Up Tables, arXiv:2107.02573v1.
var optimizedIrregular = degreeDistribution{
	Name: "irregular_2_3_18",
	Terms: []degreeTerm{
		{Degree: 2, Probability: 0.15},
		{Degree: 3, Probability: 0.725},
		{Degree: 18, Probability: 0.125},
	},
}

func (d degreeDistribution) validate(cells int) error {
	if cells <= 0 || len(d.Terms) == 0 {
		return fmt.Errorf("invalid cell count or empty degree distribution")
	}
	probability := 0.0
	for _, term := range d.Terms {
		if term.Degree <= 0 || term.Degree > cells || term.Probability < 0 {
			return fmt.Errorf("invalid degree term %+v for %d cells", term, cells)
		}
		probability += term.Probability
	}
	if math.Abs(probability-1) > 1e-12 {
		return fmt.Errorf("degree probabilities sum to %.17g, not 1", probability)
	}
	return nil
}

func (d degreeDistribution) mean() float64 {
	mean := 0.0
	for _, term := range d.Terms {
		mean += term.Probability * float64(term.Degree)
	}
	return mean
}

func (d degreeDistribution) secondMoment() float64 {
	second := 0.0
	for _, term := range d.Terms {
		second += term.Probability * math.Pow(float64(term.Degree), 2)
	}
	return second
}

func (d degreeDistribution) thirdMoment() float64 {
	third := 0.0
	for _, term := range d.Terms {
		third += term.Probability * math.Pow(float64(term.Degree), 3)
	}
	return third
}

func (d degreeDistribution) fourthMoment() float64 {
	fourth := 0.0
	for _, term := range d.Terms {
		fourth += term.Probability * math.Pow(float64(term.Degree), 4)
	}
	return fourth
}

// gamma is E[D-D^2/M], the exact centered-energy contribution of one
// randomly mapped item under the configured degree distribution.
func (d degreeDistribution) gamma(cells int) float64 {
	return d.mean() - d.secondMoment()/float64(cells)
}

// sigmaEnergy is sigma_E = sqrt(Var(D - D^2/M)), the standard deviation of
// the per-item self-energy e_x = D_x - D_x^2/M used in Corollary 4.9's
// failure-conditioned bound.
func (d degreeDistribution) sigmaEnergy(cells int) float64 {
	m := float64(cells)
	g := d.gamma(cells)
	variance := d.secondMoment() - 2*d.thirdMoment()/m + d.fourthMoment()/(m*m) - g*g
	if variance < 0 {
		variance = 0
	}
	return math.Sqrt(variance)
}

func (d degreeDistribution) degree(key, seed uint64) int {
	u := float64(mix64(key^seed^0xa4093822299f31d0)>>11) * (1.0 / (1 << 53))
	cumulative := 0.0
	for _, term := range d.Terms {
		cumulative += term.Probability
		if u < cumulative {
			return term.Degree
		}
	}
	return d.Terms[len(d.Terms)-1].Degree
}

type cell struct {
	Count   int64
	KeyXor  uint64
	HashXor uint64
}

type mapper struct {
	Cells        int
	Seed         uint64
	Distribution degreeDistribution
}

func (m mapper) degree(key uint64) int {
	return m.Distribution.degree(key, m.Seed)
}

// positions implements the paper's uniform sampling of D distinct cells
// without replacement. The experiment's maximum published degree is 18.
func (m mapper) positions(key uint64, visit func(int)) {
	degree := m.degree(key)
	var chosen [64]int
	used := 0
	for slot := 0; slot < degree; slot++ {
		for retry := 0; ; retry++ {
			x := mix64(key ^ m.Seed ^
				(uint64(slot+1) * 0x9e3779b97f4a7c15) ^
				(uint64(retry+1) * 0xbf58476d1ce4e5b9))
			hi, _ := bits.Mul64(x, uint64(m.Cells))
			position := int(hi)
			duplicate := false
			for i := 0; i < used; i++ {
				if chosen[i] == position {
					duplicate = true
					break
				}
			}
			if duplicate {
				continue
			}
			chosen[used] = position
			used++
			visit(position)
			break
		}
	}
}

func (m mapper) apply(table []cell, key uint64, sign int64) {
	h := checksum(key)
	m.positions(key, func(index int) {
		table[index].Count += sign
		table[index].KeyXor ^= key
		table[index].HashXor ^= h
	})
}

func checksum(key uint64) uint64 { return mix64(key ^ 0x243f6a8885a308d3) }

func isPure(c cell) bool {
	return (c.Count == 1 || c.Count == -1) && c.KeyXor != 0 && c.HashXor == checksum(c.KeyXor)
}

type decodedDifference struct {
	Positive map[uint64]struct{}
	Negative map[uint64]struct{}
}

// decodeTable is the standard signed reconciliation extension of the paper's
// peeling decoder. A checksum prevents a multi-item cell whose signed count is
// accidentally +/-1 from being mistaken for a singleton.
func decodeTable(raw []cell, m mapper) (decodedDifference, bool, error) {
	work := append([]cell(nil), raw...)
	queue := make([]int, 0, len(work))
	for i, c := range work {
		if isPure(c) {
			queue = append(queue, i)
		}
	}
	decoded := decodedDifference{Positive: make(map[uint64]struct{}), Negative: make(map[uint64]struct{})}
	seen := make(map[uint64]int64)
	for head := 0; head < len(queue); head++ {
		index := queue[head]
		c := work[index]
		if !isPure(c) {
			continue
		}
		key, sign := c.KeyXor, c.Count
		if previous, ok := seen[key]; ok {
			if previous != sign {
				return decoded, false, fmt.Errorf("key %x decoded with conflicting signs %d and %d", key, previous, sign)
			}
			continue
		}
		seen[key] = sign
		if sign == 1 {
			decoded.Positive[key] = struct{}{}
		} else {
			decoded.Negative[key] = struct{}{}
		}
		h := checksum(key)
		m.positions(key, func(neighbor int) {
			work[neighbor].Count -= sign
			work[neighbor].KeyXor ^= key
			work[neighbor].HashXor ^= h
			if isPure(work[neighbor]) {
				queue = append(queue, neighbor)
			}
		})
	}
	for _, c := range work {
		if c != (cell{}) {
			return decoded, false, nil
		}
	}
	return decoded, true, nil
}

func mix64(x uint64) uint64 {
	x ^= x >> 30
	x *= 0xbf58476d1ce4e5b9
	x ^= x >> 27
	x *= 0x94d049bb133111eb
	x ^= x >> 31
	return x
}
