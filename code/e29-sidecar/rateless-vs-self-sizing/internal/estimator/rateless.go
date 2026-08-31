// Package estimator contains streaming estimators for the Rateless count
// prefix.  It deliberately consumes only the Count field of a coded-symbol
// difference, before decoder peeling mutates that state.
package estimator

import (
	"fmt"
	"math"
)

// Estimator implements the corrected Rateless-f2 R3 statistic:
//
//	d_hat(m) = 1/r * sum_{i=1..r} (C_i-q_i*C_0)^2/(q_i*(1-q_i))
//
// where C_i is the signed count difference at coded-symbol index i and
// r=m-1.  The update is O(1), so a controller can update it while batches are
// arriving.  q_i is the closed-form calibrated marginal from R1.
type Estimator struct {
	c0     int64
	haveC0 bool
	sum    float64
	r      int
}

// Add records one signed count at its zero-based coded-symbol index.
func (e *Estimator) Add(index int, count int64) error {
	if index < 0 {
		return fmt.Errorf("negative coded-symbol index %d", index)
	}
	if index == 0 {
		if e.haveC0 {
			return fmt.Errorf("coded-symbol index 0 added twice")
		}
		e.c0 = count
		e.haveC0 = true
		return nil
	}
	if !e.haveC0 {
		return fmt.Errorf("coded-symbol index %d arrived before index 0", index)
	}
	q := R1Prob(index)
	denom := q * (1 - q)
	if !(denom > 0) || math.IsNaN(denom) {
		return fmt.Errorf("invalid marginal q[%d]=%g", index, q)
	}
	centered := float64(count) - q*float64(e.c0)
	e.sum += centered * centered / denom
	e.r++
	return nil
}

// AddBatch adds consecutive counts beginning at startIndex.
func (e *Estimator) AddBatch(startIndex int, counts []int64) error {
	for i, count := range counts {
		if err := e.Add(startIndex+i, count); err != nil {
			return err
		}
	}
	return nil
}

// C0 returns the signed count of the first coded symbol.
func (e Estimator) C0() (int64, bool) { return e.c0, e.haveC0 }

// Samples returns r=m-1, the number of usable observations after C0.
func (e Estimator) Samples() int { return e.r }

// DHat returns zero until at least one post-C0 observation is available.
func (e Estimator) DHat() float64 {
	if e.r == 0 {
		return 0
	}
	return e.sum / float64(e.r)
}

// R1Prob is the calibrated probability that a source symbol appears in
// coded-symbol index i under the pinned official sampler.  q_0=1 and
// q_i=8(i+1)/(2i+3)^2 for i>=1.
func R1Prob(i int) float64 {
	if i < 0 {
		return math.NaN()
	}
	if i == 0 {
		return 1
	}
	v := 2*float64(i) + 3
	return 8 * float64(i+1) / (v * v)
}
