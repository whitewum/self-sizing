package estimator

import (
	"math"
	"testing"
)

func TestR1ProbAndStreamingEstimator(t *testing.T) {
	if got := R1Prob(0); got != 1 {
		t.Fatalf("q0=%g, want 1", got)
	}
	var e Estimator
	if err := e.AddBatch(0, []int64{7, 2, -1}); err != nil {
		t.Fatal(err)
	}
	if got, ok := e.C0(); !ok || got != 7 {
		t.Fatalf("C0=(%d,%v), want (7,true)", got, ok)
	}
	want := 0.0
	for i, c := range []int64{2, -1} {
		q := R1Prob(i + 1)
		v := float64(c) - q*7
		want += v * v / (q * (1 - q))
	}
	want /= 2
	if got := e.DHat(); math.Abs(got-want) > 1e-12 {
		t.Fatalf("dhat=%g, want %g", got, want)
	}
	if e.Samples() != 2 {
		t.Fatalf("samples=%d, want 2", e.Samples())
	}
}

func TestEstimatorRejectsOutOfOrder(t *testing.T) {
	var e Estimator
	if err := e.Add(1, 1); err == nil {
		t.Fatal("expected out-of-order error")
	}
	if err := e.Add(0, 1); err != nil {
		t.Fatal(err)
	}
	if err := e.Add(0, 1); err == nil {
		t.Fatal("expected duplicate C0 error")
	}
}
