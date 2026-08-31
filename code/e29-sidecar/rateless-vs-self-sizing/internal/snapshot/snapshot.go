package snapshot

import (
	"bufio"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	Format       = "e29-fpid-be-v1"
	ManifestName = "manifest.json"
	RowsName     = "rows.fpid"
	RecordSize   = 16
	readBuffer   = 4 << 20
	writeBuffer  = 1 << 20
	manifestPerm = 0o644
	snapshotPerm = 0o644
)

type Record struct {
	FP uint64
	ID uint64
}

type Manifest struct {
	Format          string    `json:"format"`
	Version         int       `json:"version"`
	Profile         string    `json:"profile"`
	Endpoint        string    `json:"endpoint"`
	RowsFile        string    `json:"rows_file"`
	ByteOrder       string    `json:"byte_order"`
	Fields          []string  `json:"fields"`
	RecordSize      int       `json:"record_size"`
	RowCount        int64     `json:"row_count"`
	FileBytes       int64     `json:"file_bytes"`
	SHA256          string    `json:"sha256"`
	Files           []File    `json:"files,omitempty"`
	FingerprintSpec string    `json:"fingerprint_spec,omitempty"`
	Source          string    `json:"source,omitempty"`
	CreatedAt       time.Time `json:"created_at"`
	Generator       string    `json:"generator,omitempty"`
}

// File describes one immutable shard in a snapshot. Legacy single-file
// snapshots continue to use RowsFile/FileBytes/SHA256 at the manifest root.
type File struct {
	Path      string `json:"path"`
	RowCount  int64  `json:"row_count"`
	FileBytes int64  `json:"file_bytes"`
	SHA256    string `json:"sha256"`
}

type Writer struct {
	dir      string
	file     *os.File
	buf      *bufio.Writer
	hash     hash.Hash
	rows     int64
	manifest Manifest
	closed   bool
}

func Create(dir string, manifest Manifest) (*Writer, error) {
	if manifest.Profile == "" || manifest.Endpoint == "" {
		return nil, errors.New("profile and endpoint are required")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("create snapshot directory: %w", err)
	}
	path := filepath.Join(dir, RowsName)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, snapshotPerm)
	if err != nil {
		return nil, fmt.Errorf("create %s: %w", path, err)
	}
	digest := sha256.New()
	w := &Writer{
		dir:      dir,
		file:     f,
		buf:      bufio.NewWriterSize(io.MultiWriter(f, digest), writeBuffer),
		hash:     digest,
		manifest: manifest,
	}
	return w, nil
}

func (w *Writer) Write(record Record) error {
	if w.closed {
		return errors.New("snapshot writer is closed")
	}
	var raw [RecordSize]byte
	binary.BigEndian.PutUint64(raw[0:8], record.FP)
	binary.BigEndian.PutUint64(raw[8:16], record.ID)
	if _, err := w.buf.Write(raw[:]); err != nil {
		return fmt.Errorf("write snapshot row %d: %w", w.rows, err)
	}
	w.rows++
	return nil
}

func (w *Writer) Close() (Manifest, error) {
	if w.closed {
		return w.manifest, errors.New("snapshot writer already closed")
	}
	w.closed = true
	if err := w.buf.Flush(); err != nil {
		_ = w.file.Close()
		return w.manifest, fmt.Errorf("flush snapshot: %w", err)
	}
	if err := w.file.Sync(); err != nil {
		_ = w.file.Close()
		return w.manifest, fmt.Errorf("sync snapshot: %w", err)
	}
	if err := w.file.Close(); err != nil {
		return w.manifest, fmt.Errorf("close snapshot: %w", err)
	}
	w.manifest.Format = Format
	w.manifest.Version = 1
	w.manifest.RowsFile = RowsName
	w.manifest.ByteOrder = "big-endian"
	w.manifest.Fields = []string{"fp_uint64", "id_uint64"}
	w.manifest.RecordSize = RecordSize
	w.manifest.RowCount = w.rows
	w.manifest.FileBytes = w.rows * RecordSize
	w.manifest.SHA256 = hex.EncodeToString(w.hash.Sum(nil))
	if w.manifest.CreatedAt.IsZero() {
		w.manifest.CreatedAt = time.Now().UTC()
	}
	if err := WriteManifest(w.dir, w.manifest); err != nil {
		return w.manifest, err
	}
	return w.manifest, nil
}

func (w *Writer) Abort() error {
	w.closed = true
	_ = w.buf.Flush()
	_ = w.file.Close()
	rowsErr := os.Remove(filepath.Join(w.dir, RowsName))
	manifestErr := os.Remove(filepath.Join(w.dir, ManifestName))
	if rowsErr != nil && !errors.Is(rowsErr, os.ErrNotExist) {
		return rowsErr
	}
	if manifestErr != nil && !errors.Is(manifestErr, os.ErrNotExist) {
		return manifestErr
	}
	return nil
}

func WriteManifest(dir string, manifest Manifest) error {
	raw, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal manifest: %w", err)
	}
	raw = append(raw, '\n')
	path := filepath.Join(dir, ManifestName)
	if err := os.WriteFile(path, raw, manifestPerm); err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	return nil
}

func LoadManifest(dir string) (Manifest, error) {
	path := filepath.Join(dir, ManifestName)
	raw, err := os.ReadFile(path)
	if err != nil {
		return Manifest{}, fmt.Errorf("read %s: %w", path, err)
	}
	var manifest Manifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return Manifest{}, fmt.Errorf("parse %s: %w", path, err)
	}
	return manifest, nil
}

func Verify(dir string, withDigest bool) (Manifest, error) {
	manifest, err := LoadManifest(dir)
	if err != nil {
		return Manifest{}, err
	}
	if manifest.Format != Format || manifest.Version != 1 {
		return Manifest{}, fmt.Errorf("unsupported snapshot format %q version %d", manifest.Format, manifest.Version)
	}
	if manifest.RecordSize != RecordSize || manifest.ByteOrder != "big-endian" {
		return Manifest{}, fmt.Errorf("invalid record contract: size=%d byte_order=%q", manifest.RecordSize, manifest.ByteOrder)
	}
	files, err := manifestFiles(manifest)
	if err != nil {
		return Manifest{}, err
	}
	var totalRows, totalBytes int64
	for _, file := range files {
		path, err := snapshotPath(dir, file.Path)
		if err != nil {
			return Manifest{}, err
		}
		info, err := os.Stat(path)
		if err != nil {
			return Manifest{}, fmt.Errorf("stat %s: %w", path, err)
		}
		if file.RowCount < 0 || file.FileBytes != file.RowCount*RecordSize || info.Size() != file.FileBytes {
			return Manifest{}, fmt.Errorf("snapshot shard size mismatch: path=%s file=%d manifest=%d rows=%d", file.Path, info.Size(), file.FileBytes, file.RowCount)
		}
		if withDigest {
			got, err := FileSHA256(path)
			if err != nil {
				return Manifest{}, err
			}
			if got != file.SHA256 {
				return Manifest{}, fmt.Errorf("snapshot SHA-256 mismatch: path=%s got %s want %s", file.Path, got, file.SHA256)
			}
		}
		totalRows += file.RowCount
		totalBytes += file.FileBytes
	}
	if totalRows != manifest.RowCount || totalBytes != manifest.FileBytes {
		return Manifest{}, fmt.Errorf("snapshot total mismatch: files rows=%d bytes=%d manifest rows=%d bytes=%d", totalRows, totalBytes, manifest.RowCount, manifest.FileBytes)
	}
	return manifest, nil
}

func manifestFiles(manifest Manifest) ([]File, error) {
	if len(manifest.Files) > 0 {
		if manifest.RowsFile != "" {
			return nil, errors.New("snapshot manifest cannot contain both rows_file and files")
		}
		return manifest.Files, nil
	}
	if manifest.RowsFile == "" {
		return nil, errors.New("snapshot manifest has neither rows_file nor files")
	}
	return []File{{
		Path: manifest.RowsFile, RowCount: manifest.RowCount,
		FileBytes: manifest.FileBytes, SHA256: manifest.SHA256,
	}}, nil
}

func snapshotPath(dir, name string) (string, error) {
	if name == "" || filepath.IsAbs(name) {
		return "", fmt.Errorf("invalid snapshot shard path %q", name)
	}
	clean := filepath.Clean(name)
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("snapshot shard path escapes directory: %q", name)
	}
	return filepath.Join(dir, clean), nil
}

func FileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("open %s: %w", path, err)
	}
	defer f.Close()
	digest := sha256.New()
	if _, err := io.CopyBuffer(digest, f, make([]byte, readBuffer)); err != nil {
		return "", fmt.Errorf("hash %s: %w", path, err)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func ForEach(dir string, fn func(Record) error) (int64, error) {
	manifest, err := Verify(dir, false)
	if err != nil {
		return 0, err
	}
	files, err := manifestFiles(manifest)
	if err != nil {
		return 0, err
	}
	var rows int64
	for _, shard := range files {
		path, err := snapshotPath(dir, shard.Path)
		if err != nil {
			return rows, err
		}
		f, err := os.Open(path)
		if err != nil {
			return rows, fmt.Errorf("open %s: %w", path, err)
		}
		reader := bufio.NewReaderSize(f, readBuffer)
		var raw [RecordSize]byte
		for row := int64(0); row < shard.RowCount; row++ {
			if _, err := io.ReadFull(reader, raw[:]); err != nil {
				_ = f.Close()
				return rows, fmt.Errorf("read snapshot shard %s row %d: %w", shard.Path, row, err)
			}
			record := Record{FP: binary.BigEndian.Uint64(raw[0:8]), ID: binary.BigEndian.Uint64(raw[8:16])}
			if err := fn(record); err != nil {
				_ = f.Close()
				return rows, err
			}
			rows++
		}
		if err := f.Close(); err != nil {
			return rows, fmt.Errorf("close %s: %w", path, err)
		}
	}
	return rows, nil
}
