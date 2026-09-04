package truthaudit

import (
	"bufio"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/model"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
)

const partitionRecordSize = 17

type Metadata struct {
	GeneratedAt          time.Time `json:"generated_at"`
	MapperVersion        int       `json:"mapper_version"`
	Source               string    `json:"source"`
	Target               string    `json:"target"`
	SourceManifestSHA256 string    `json:"source_manifest_sha256"`
	TargetManifestSHA256 string    `json:"target_manifest_sha256"`
	SourceRows           int64     `json:"source_rows"`
	TargetRows           int64     `json:"target_rows"`
	Partitions           int       `json:"partitions"`
	InputSHA256Verified  bool      `json:"input_sha256_verified"`
}

type Truth struct {
	Plus     []model.Symbol `json:"plus"`
	Minus    []model.Symbol `json:"minus"`
	Metadata Metadata       `json:"audit"`
}

// Derive computes the exact multiset difference of two immutable snapshots.
// It partitions the input on disk so memory is bounded by one partition.
func Derive(source, target, workDir string, partitions int, verifySHA bool) (Truth, error) {
	if partitions < 1 || partitions > 4096 {
		return Truth{}, fmt.Errorf("partitions must be in [1,4096], got %d", partitions)
	}
	sourceManifest, err := snapshot.Verify(source, verifySHA)
	if err != nil {
		return Truth{}, fmt.Errorf("verify source: %w", err)
	}
	targetManifest, err := snapshot.Verify(target, verifySHA)
	if err != nil {
		return Truth{}, fmt.Errorf("verify target: %w", err)
	}
	sourceDigest, err := snapshot.FileSHA256(filepath.Join(source, snapshot.ManifestName))
	if err != nil {
		return Truth{}, err
	}
	targetDigest, err := snapshot.FileSHA256(filepath.Join(target, snapshot.ManifestName))
	if err != nil {
		return Truth{}, err
	}
	if workDir == "" {
		workDir = os.TempDir()
	}
	tmp, err := os.MkdirTemp(workDir, "iblt-truth-audit-")
	if err != nil {
		return Truth{}, fmt.Errorf("create audit work directory: %w", err)
	}
	defer os.RemoveAll(tmp)

	paths := make([]string, partitions)
	files := make([]*os.File, partitions)
	writers := make([]*bufio.Writer, partitions)
	for i := range paths {
		paths[i] = filepath.Join(tmp, fmt.Sprintf("partition-%04d.bin", i))
		files[i], err = os.Create(paths[i])
		if err != nil {
			closePartitionWriters(files, writers)
			return Truth{}, fmt.Errorf("create partition %d: %w", i, err)
		}
		writers[i] = bufio.NewWriterSize(files[i], 64<<10)
	}
	writeSide := func(dir string, side byte) error {
		_, err := snapshot.ForEach(dir, func(record snapshot.Record) error {
			symbol := model.Symbol{FP: record.FP, ID: record.ID}
			partition := int(symbol.Hash() % uint64(partitions))
			var raw [partitionRecordSize]byte
			raw[0] = side
			binary.BigEndian.PutUint64(raw[1:9], record.FP)
			binary.BigEndian.PutUint64(raw[9:17], record.ID)
			_, writeErr := writers[partition].Write(raw[:])
			return writeErr
		})
		return err
	}
	if err := writeSide(source, 1); err != nil {
		closePartitionWriters(files, writers)
		return Truth{}, fmt.Errorf("partition source: %w", err)
	}
	if err := writeSide(target, 0); err != nil {
		closePartitionWriters(files, writers)
		return Truth{}, fmt.Errorf("partition target: %w", err)
	}
	if err := closePartitionWriters(files, writers); err != nil {
		return Truth{}, err
	}

	truth := Truth{Metadata: Metadata{
		GeneratedAt: time.Now().UTC(), MapperVersion: plain.MapperVersion,
		Source: source, Target: target,
		SourceManifestSHA256: sourceDigest, TargetManifestSHA256: targetDigest,
		SourceRows: sourceManifest.RowCount, TargetRows: targetManifest.RowCount,
		Partitions: partitions, InputSHA256Verified: verifySHA,
	}}
	for _, path := range paths {
		counts := make(map[model.Symbol]int32)
		f, err := os.Open(path)
		if err != nil {
			return Truth{}, err
		}
		reader := bufio.NewReaderSize(f, 4<<20)
		var raw [partitionRecordSize]byte
		for {
			_, err = io.ReadFull(reader, raw[:])
			if err == io.EOF {
				break
			}
			if err != nil {
				_ = f.Close()
				return Truth{}, fmt.Errorf("read %s: %w", path, err)
			}
			symbol := model.Symbol{FP: binary.BigEndian.Uint64(raw[1:9]), ID: binary.BigEndian.Uint64(raw[9:17])}
			delta := int32(-1)
			if raw[0] == 1 {
				delta = 1
			}
			next := counts[symbol] + delta
			if next == 0 {
				delete(counts, symbol)
			} else {
				counts[symbol] = next
			}
		}
		if err := f.Close(); err != nil {
			return Truth{}, err
		}
		for symbol, count := range counts {
			if count > 0 {
				for ; count > 0; count-- {
					truth.Plus = append(truth.Plus, symbol)
				}
			} else {
				for ; count < 0; count++ {
					truth.Minus = append(truth.Minus, symbol)
				}
			}
		}
	}
	sortSymbols(truth.Plus)
	sortSymbols(truth.Minus)
	return truth, nil
}

func Write(path string, truth Truth) error {
	raw, err := json.MarshalIndent(truth, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	return os.WriteFile(path, raw, 0o644)
}

func Digest(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func sortSymbols(values []model.Symbol) {
	sort.Slice(values, func(i, j int) bool {
		if values[i].ID != values[j].ID {
			return values[i].ID < values[j].ID
		}
		return values[i].FP < values[j].FP
	})
}

func closePartitionWriters(files []*os.File, writers []*bufio.Writer) error {
	var first error
	for i := range files {
		if writers[i] != nil {
			if err := writers[i].Flush(); err != nil && first == nil {
				first = err
			}
		}
		if files[i] != nil {
			if err := files[i].Close(); err != nil && first == nil {
				first = err
			}
		}
	}
	return first
}
