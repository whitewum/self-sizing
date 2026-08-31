package model

import (
	"math/bits"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
)

// Symbol preserves the same recoverable pair as the production Plain IBLT:
// the row fingerprint and the stable row identifier.
type Symbol struct {
	FP uint64 `json:"fp"`
	ID uint64 `json:"id"`
}

func (s Symbol) XOR(other Symbol) Symbol {
	return Symbol{FP: s.FP ^ other.FP, ID: s.ID ^ other.ID}
}

func (s Symbol) Hash() uint64 {
	return plain.Mix64(s.FP ^ bits.RotateLeft64(s.ID, 23) ^ 0xD6E8FEB86659FD93)
}
