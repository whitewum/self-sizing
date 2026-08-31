package snapshot

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestRoundTripAndVerify(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "P2", "source")
	w, err := Create(dir, Manifest{Profile: "P2", Endpoint: "source", Generator: "test"})
	if err != nil {
		t.Fatal(err)
	}
	want := []Record{{FP: 1, ID: 2}, {FP: ^uint64(0), ID: 9}, {FP: 7, ID: 11}}
	for _, record := range want {
		if err := w.Write(record); err != nil {
			t.Fatal(err)
		}
	}
	manifest, err := w.Close()
	if err != nil {
		t.Fatal(err)
	}
	if manifest.RowCount != int64(len(want)) || manifest.FileBytes != int64(len(want)*RecordSize) {
		t.Fatalf("unexpected manifest: %+v", manifest)
	}
	if _, err := Verify(dir, true); err != nil {
		t.Fatal(err)
	}
	var got []Record
	if _, err := ForEach(dir, func(record Record) error {
		got = append(got, record)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("records mismatch: got=%v want=%v", got, want)
	}
}

func TestShardedSnapshotVerifyAndRead(t *testing.T) {
	dir := t.TempDir()
	shards := [][]byte{
		{
			0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2,
			0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 4,
		},
		{0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 6},
	}
	manifest := Manifest{
		Format: Format, Version: 1, Profile: "P1", Endpoint: "source",
		ByteOrder: "big-endian", Fields: []string{"fp_uint64", "id_uint64"},
		RecordSize: RecordSize,
	}
	for i, raw := range shards {
		name := fmt.Sprintf("shard-%02d.fpid", i)
		if err := os.WriteFile(filepath.Join(dir, name), raw, 0o644); err != nil {
			t.Fatal(err)
		}
		digest := sha256.Sum256(raw)
		file := File{Path: name, RowCount: int64(len(raw) / RecordSize), FileBytes: int64(len(raw)), SHA256: hex.EncodeToString(digest[:])}
		manifest.Files = append(manifest.Files, file)
		manifest.RowCount += file.RowCount
		manifest.FileBytes += file.FileBytes
	}
	if err := WriteManifest(dir, manifest); err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(dir, true); err != nil {
		t.Fatal(err)
	}
	var got []Record
	rows, err := ForEach(dir, func(record Record) error {
		got = append(got, record)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []Record{{FP: 1, ID: 2}, {FP: 3, ID: 4}, {FP: 5, ID: 6}}
	if rows != int64(len(want)) || !reflect.DeepEqual(got, want) {
		t.Fatalf("rows=%d records=%v want=%v", rows, got, want)
	}
}
