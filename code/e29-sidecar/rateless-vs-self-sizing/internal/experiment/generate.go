package experiment

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/model"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
)

func GenerateSynthetic(root string, common, plus, minus int, seed uint64) error {
	if common < 0 || plus < 0 || minus < 0 {
		return fmt.Errorf("synthetic counts must be non-negative")
	}
	sourceDir := filepath.Join(root, "source")
	targetDir := filepath.Join(root, "target")
	source, err := snapshot.Create(sourceDir, snapshot.Manifest{
		Profile: "synthetic", Endpoint: "source", Source: "generated",
		FingerprintSpec: "mix64(id xor class xor seed) masked to 56 bits", Generator: "rateless-vs-self-sizing generate",
	})
	if err != nil {
		return err
	}
	target, err := snapshot.Create(targetDir, snapshot.Manifest{
		Profile: "synthetic", Endpoint: "target", Source: "generated",
		FingerprintSpec: "mix64(id xor class xor seed) masked to 56 bits", Generator: "rateless-vs-self-sizing generate",
	})
	if err != nil {
		_ = source.Abort()
		return err
	}
	abort := true
	defer func() {
		if abort {
			_ = source.Abort()
			_ = target.Abort()
		}
	}()
	truth := Truth{}
	for i := 0; i < common; i++ {
		symbol := generatedSymbol(uint64(i+1), 0xC0110, seed)
		record := snapshot.Record{FP: symbol.FP, ID: symbol.ID}
		if err := source.Write(record); err != nil {
			return err
		}
		if err := target.Write(record); err != nil {
			return err
		}
	}
	for i := 0; i < plus; i++ {
		symbol := generatedSymbol((uint64(1)<<61)+uint64(i+1), 0xA11CE, seed)
		if err := source.Write(snapshot.Record{FP: symbol.FP, ID: symbol.ID}); err != nil {
			return err
		}
		truth.Plus = append(truth.Plus, symbol)
	}
	for i := 0; i < minus; i++ {
		symbol := generatedSymbol((uint64(2)<<61)+uint64(i+1), 0xB0B, seed)
		if err := target.Write(snapshot.Record{FP: symbol.FP, ID: symbol.ID}); err != nil {
			return err
		}
		truth.Minus = append(truth.Minus, symbol)
	}
	if _, err := source.Close(); err != nil {
		return err
	}
	if _, err := target.Close(); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(truth, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	if err := os.WriteFile(filepath.Join(root, "truth.json"), raw, 0o644); err != nil {
		return fmt.Errorf("write synthetic truth: %w", err)
	}
	abort = false
	return nil
}

func generatedSymbol(id, class, seed uint64) model.Symbol {
	fp := plain.Mix64(id^class^seed) & ((uint64(1) << 56) - 1)
	return model.Symbol{FP: fp, ID: id}
}
