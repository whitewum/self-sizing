package truthaudit

import (
	"path/filepath"
	"testing"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/experiment"
)

func TestDeriveExactMultisetTruth(t *testing.T) {
	root := t.TempDir()
	pair := filepath.Join(root, "pair")
	if err := experiment.GenerateSynthetic(pair, 1000, 17, 13, 0x1234); err != nil {
		t.Fatal(err)
	}
	truth, err := Derive(filepath.Join(pair, "source"), filepath.Join(pair, "target"), root, 7, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(truth.Plus) != 17 || len(truth.Minus) != 13 {
		t.Fatalf("plus=%d minus=%d", len(truth.Plus), len(truth.Minus))
	}
	if !truth.Metadata.InputSHA256Verified || truth.Metadata.MapperVersion != 2 {
		t.Fatalf("bad metadata: %+v", truth.Metadata)
	}
}
