package plain

import (
	"fmt"
	"math"
)

const (
	MapperVersion        = 2
	K                    = 3
	seedA         uint64 = 0xA1B2C3D4E5F60718
	seedB         uint64 = 0x1234567890ABCDEF
	seedC         uint64 = 0xFEDCBA9876543210
	checksumSeed         = 0x9E3779B97F4A7C15
	// retryStep advances the hash stream on a collision during the rejection
	// sampling of the k=3 distinct cell positions. It is unrelated to
	// checksumSeed; the shared value is only the golden-ratio constant.
	retryStep uint64 = 0x9E3779B97F4A7C15
)

type Record struct {
	FP uint64
	ID uint64
}

type Sketch struct {
	M       int
	Seed    uint64
	Count   []int64
	FPXor   []uint64
	IDXor   []uint64
	ChkXor  []uint64
	Updates uint64
}

type DecodeResult struct {
	Success  bool
	Plus     []Record
	Minus    []Record
	Residual int
	DHat     float64
}

func New(m int, seed uint64) *Sketch {
	if m <= K {
		panic("Plain IBLT bucket count must exceed k=3")
	}
	return &Sketch{
		M: m, Seed: seed,
		Count: make([]int64, m), FPXor: make([]uint64, m),
		IDXor: make([]uint64, m), ChkXor: make([]uint64, m),
	}
}

func (s *Sketch) Insert(fp, id uint64) {
	checksum := Checksum(fp)
	for _, position := range Positions(fp, s.M, s.Seed) {
		s.Count[position]++
		s.FPXor[position] ^= fp
		s.IDXor[position] ^= id
		s.ChkXor[position] ^= checksum
		s.Updates++
	}
}

func Subtract(a, b *Sketch) (*Sketch, error) {
	if a.M != b.M || a.Seed != b.Seed {
		return nil, fmt.Errorf("plain sketch mismatch: m=%d/%d seed=%d/%d", a.M, b.M, a.Seed, b.Seed)
	}
	diff := New(a.M, a.Seed)
	for i := 0; i < a.M; i++ {
		diff.Count[i] = a.Count[i] - b.Count[i]
		diff.FPXor[i] = a.FPXor[i] ^ b.FPXor[i]
		diff.IDXor[i] = a.IDXor[i] ^ b.IDXor[i]
		diff.ChkXor[i] = a.ChkXor[i] ^ b.ChkXor[i]
	}
	return diff, nil
}

func (s *Sketch) Decode() DecodeResult {
	count := append([]int64(nil), s.Count...)
	fpXor := append([]uint64(nil), s.FPXor...)
	idXor := append([]uint64(nil), s.IDXor...)
	chkXor := append([]uint64(nil), s.ChkXor...)
	dHat := Estimate(count)
	queue := make([]int, 0, s.M)
	for i := 0; i < s.M; i++ {
		if pure(count, fpXor, chkXor, i) {
			queue = append(queue, i)
		}
	}
	plus := make([]Record, 0)
	minus := make([]Record, 0)
	for head := 0; head < len(queue); head++ {
		i := queue[head]
		if !pure(count, fpXor, chkXor, i) {
			continue
		}
		record := Record{FP: fpXor[i], ID: idXor[i]}
		side := count[i]
		if side == 1 {
			plus = append(plus, record)
		} else {
			minus = append(minus, record)
		}
		checksum := Checksum(record.FP)
		for _, position := range Positions(record.FP, s.M, s.Seed) {
			count[position] -= side
			fpXor[position] ^= record.FP
			idXor[position] ^= record.ID
			chkXor[position] ^= checksum
			if pure(count, fpXor, chkXor, position) {
				queue = append(queue, position)
			}
		}
	}
	residual := 0
	for i := 0; i < s.M; i++ {
		if count[i] != 0 || fpXor[i] != 0 || idXor[i] != 0 || chkXor[i] != 0 {
			residual++
		}
	}
	return DecodeResult{Success: residual == 0, Plus: plus, Minus: minus, Residual: residual, DHat: dHat}
}

func Estimate(count []int64) float64 {
	if len(count) <= K {
		return math.NaN()
	}
	var sum int64
	var sumSquares float64
	for _, value := range count {
		sum += value
		sumSquares += float64(value) * float64(value)
	}
	m := float64(len(count))
	energy := sumSquares - float64(sum)*float64(sum)/m
	normalizer := K * (1 - K/m)
	return energy / normalizer
}

func pure(count []int64, fpXor, chkXor []uint64, i int) bool {
	return (count[i] == 1 || count[i] == -1) && chkXor[i] == Checksum(fpXor[i])
}

// Positions returns the three distinct cell indices of fp under plain
// mapping: each slot draws from an independent hash stream and is
// re-sampled until it avoids all earlier cells, matching the paper's
// uniform k-subset-without-replacement model.
func Positions(fp uint64, m int, seed uint64) []int {
	if m <= 0 {
		panic("bucket count must be positive")
	}
	a, b, c := seedA, seedB, seedC
	if seed != 0 {
		a = Mix64(a ^ seed)
		b = Mix64(b ^ seed)
		c = Mix64(c ^ seed)
	}
	p0 := firstDistinct(fp, a, m)
	p1 := firstDistinct(fp, b, m, p0)
	p2 := firstDistinct(fp, c, m, p0, p1)
	return []int{p0, p1, p2}
}

// firstDistinct returns the first value of the hash stream
// h_k = Mix64(base+fp+k*retryStep) mod m that avoids every position in avoid.
func firstDistinct(fp, base uint64, m int, avoid ...int) int {
	for k := uint64(0); ; k++ {
		p := int(Mix64(base+fp+k*retryStep) % uint64(m))
		duplicate := false
		for _, q := range avoid {
			if p == q {
				duplicate = true
				break
			}
		}
		if !duplicate {
			return p
		}
	}
}

func Checksum(fp uint64) uint64 {
	return Mix64(checksumSeed + fp)
}

func Mix64(value uint64) uint64 {
	value ^= value >> 30
	value *= 0xBF58476D1CE4E5B9
	value ^= value >> 27
	value *= 0x94D049BB133111EB
	value ^= value >> 31
	return value
}
