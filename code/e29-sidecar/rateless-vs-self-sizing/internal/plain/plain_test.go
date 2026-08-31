package plain

import (
	"sort"
	"testing"
)

func TestJavaInteropVectors(t *testing.T) {
	if got := Mix64(1); got != 0x5692161D100B05E5 {
		t.Fatalf("Mix64(1)=%x", got)
	}
	fp := uint64(0x206D7A4C58CD2C)
	if got := Checksum(fp); got != 0xF5AA3C0C47B695A8 {
		t.Fatalf("Checksum=%x", got)
	}
	want := []int{3301, 359, 5223}
	got := Positions(fp, 6000, 0)
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("Positions=%v want=%v", got, want)
		}
	}
	// Captured from the exact H100 runtime jar (IbltHash.positions(fp,6000,0x1234)).
	seededWant := []int{1021, 161, 2602}
	seededGot := Positions(fp, 6000, 0x1234)
	for i := range seededWant {
		if seededGot[i] != seededWant[i] {
			t.Fatalf("seeded Positions=%v want=%v", seededGot, seededWant)
		}
	}
}

func TestDecodeSignedDifference(t *testing.T) {
	a := New(256, 0x1234)
	b := New(256, 0x1234)
	for i := uint64(1); i <= 100; i++ {
		fp := Mix64(i) & ((uint64(1) << 56) - 1)
		a.Insert(fp, i)
		b.Insert(fp, i)
	}
	plus := []uint64{1001, 1002, 1003}
	minus := []uint64{2001, 2002}
	for _, id := range plus {
		a.Insert(Mix64(id)&((uint64(1)<<56)-1), id)
	}
	for _, id := range minus {
		b.Insert(Mix64(id)&((uint64(1)<<56)-1), id)
	}
	diff, err := Subtract(a, b)
	if err != nil {
		t.Fatal(err)
	}
	result := diff.Decode()
	if !result.Success || result.Residual != 0 {
		t.Fatalf("decode failed: %+v", result)
	}
	gotPlus := ids(result.Plus)
	gotMinus := ids(result.Minus)
	if !equal(gotPlus, plus) || !equal(gotMinus, minus) {
		t.Fatalf("got plus=%v minus=%v", gotPlus, gotMinus)
	}
}

func ids(records []Record) []uint64 {
	values := make([]uint64, len(records))
	for i, record := range records {
		values[i] = record.ID
	}
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	return values
}

func equal(a, b []uint64) bool {
	if len(a) != len(b) {
		return false
	}
	bb := append([]uint64(nil), b...)
	sort.Slice(bb, func(i, j int) bool { return bb[i] < bb[j] })
	for i := range a {
		if a[i] != bb[i] {
			return false
		}
	}
	return true
}
