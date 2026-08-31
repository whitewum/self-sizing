package sharded

import (
	"path/filepath"
	"testing"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/experiment"
)

func TestShardedEncoderDecoder(t *testing.T) {
	root := t.TempDir()
	if err := experiment.GenerateSynthetic(root, 100, 80, 70, 0xBEEF); err != nil {
		t.Fatal(err)
	}
	source, sm, err := Build(filepath.Join(root, "source"), 8)
	if err != nil {
		t.Fatal(err)
	}
	target, tm, err := Build(filepath.Join(root, "target"), 8)
	if err != nil {
		t.Fatal(err)
	}
	if sm.Rows != 180 || tm.Rows != 170 {
		t.Fatalf("rows source/target=%d/%d", sm.Rows, tm.Rows)
	}
	decoder, err := NewDecoder(8)
	if err != nil {
		t.Fatal(err)
	}
	for prefix := 0; prefix < 2048; prefix += 64 {
		sc := source.ProduceBatch(64)
		tc := target.ProduceBatch(64)
		if err := decoder.AddBatch(sc, tc); err != nil {
			t.Fatal(err)
		}
		if prefix >= 64 && decoder.Decoded() {
			break
		}
	}
	if !decoder.Decoded() {
		t.Fatal("sharded decoder did not finish within hard cap")
	}
	if got := len(decoder.Remote()); got != 80 {
		t.Fatalf("remote count=%d, want 80", got)
	}
	if got := len(decoder.Local()); got != 70 {
		t.Fatalf("local count=%d, want 70", got)
	}
}
