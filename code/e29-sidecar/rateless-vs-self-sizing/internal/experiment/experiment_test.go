package experiment

import (
	"path/filepath"
	"testing"
)

func TestAllArmsRecoverSameDifference(t *testing.T) {
	root := filepath.Join(t.TempDir(), "pair")
	if err := GenerateSynthetic(root, 2000, 50, 40, 0x123456); err != nil {
		t.Fatal(err)
	}
	base := Config{
		Source: filepath.Join(root, "source"), Target: filepath.Join(root, "target"),
		Truth: filepath.Join(root, "truth.json"), M1: 64, Alpha: 4,
		Seed1: 0x1111, Seed2: 0x2222, HardCap: 512, VerifySHA: true,
	}
	for _, arm := range []string{"ss", "ri", "rf"} {
		t.Run(arm, func(t *testing.T) {
			config := base
			config.Arm = arm
			result, err := Run(config)
			if err != nil {
				t.Fatal(err)
			}
			if !result.Success || !result.TruthChecked || !result.TruthMatch {
				t.Fatalf("arm %s failed: %+v", arm, result)
			}
			if result.Plus != 50 || result.Minus != 40 {
				t.Fatalf("arm %s counts plus=%d minus=%d", arm, result.Plus, result.Minus)
			}
		})
	}
}

func TestRatelessFixedHardCapFailureIsRecorded(t *testing.T) {
	root := filepath.Join(t.TempDir(), "pair")
	if err := GenerateSynthetic(root, 100, 50, 50, 0x999); err != nil {
		t.Fatal(err)
	}
	result, err := Run(Config{
		Arm: "rf", Source: filepath.Join(root, "source"), Target: filepath.Join(root, "target"),
		Truth: filepath.Join(root, "truth.json"), HardCap: 2,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Success {
		t.Fatalf("unexpected success at hard cap 2: %+v", result)
	}
}

func TestAllArmsCertifyEqualSnapshots(t *testing.T) {
	root := filepath.Join(t.TempDir(), "equal")
	if err := GenerateSynthetic(root, 1000, 0, 0, 0xABC); err != nil {
		t.Fatal(err)
	}
	for _, arm := range []string{"ss", "ri", "rf"} {
		result, err := Run(Config{
			Arm: arm, Source: filepath.Join(root, "source"), Target: filepath.Join(root, "target"),
			Truth: filepath.Join(root, "truth.json"), M1: 64, Alpha: 2,
			Seed1: 1, Seed2: 2, HardCap: 64,
		})
		if err != nil {
			t.Fatalf("arm %s: %v", arm, err)
		}
		if !result.Success || !result.TruthMatch || result.Plus != 0 || result.Minus != 0 {
			t.Fatalf("arm %s equal-set result: %+v", arm, result)
		}
	}
}
