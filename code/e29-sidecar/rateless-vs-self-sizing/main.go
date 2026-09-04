package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/distributed"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/experiment"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/snapshot"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/truthaudit"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "generate":
		generate(os.Args[2:])
	case "verify":
		verify(os.Args[2:])
	case "derive-truth":
		deriveTruth(os.Args[2:])
	case "run":
		run(os.Args[2:])
	case "endpoint":
		endpoint(os.Args[2:])
	case "controller":
		controller(os.Args[2:])
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: rateless-vs-self-sizing <generate|verify|derive-truth|run|endpoint|controller> [flags]")
}

func deriveTruth(args []string) {
	flags := flag.NewFlagSet("derive-truth", flag.ExitOnError)
	source := flags.String("source", "", "source snapshot directory")
	target := flags.String("target", "", "target snapshot directory")
	out := flags.String("out", "", "truth JSON output path")
	workDir := flags.String("work-dir", "", "directory for temporary partitions")
	partitions := flags.Int("partitions", 256, "number of bounded-memory hash partitions")
	verifySHA := flags.Bool("verify-sha256", true, "verify every snapshot shard digest")
	_ = flags.Parse(args)
	if *source == "" || *target == "" || *out == "" {
		fatalf("derive-truth requires -source, -target, and -out")
	}
	truth, err := truthaudit.Derive(*source, *target, *workDir, *partitions, *verifySHA)
	if err != nil {
		fatalf("derive truth: %v", err)
	}
	if err := truthaudit.Write(*out, truth); err != nil {
		fatalf("write truth: %v", err)
	}
	digest, err := truthaudit.Digest(*out)
	if err != nil {
		fatalf("hash truth: %v", err)
	}
	fmt.Printf("truth=%s sha256=%s plus=%d minus=%d mapper_version=%d\n",
		*out, digest, len(truth.Plus), len(truth.Minus), truth.Metadata.MapperVersion)
}

func endpoint(args []string) {
	flags := flag.NewFlagSet("endpoint", flag.ExitOnError)
	snapshotDir := flags.String("snapshot", "", "snapshot directory")
	listen := flags.String("listen", ":19400", "HTTP listen address")
	_ = flags.Parse(args)
	if *snapshotDir == "" {
		fatalf("endpoint requires -snapshot")
	}
	if err := (&distributed.Endpoint{Snapshot: *snapshotDir}).Serve(*listen); err != nil {
		fatalf("endpoint: %v", err)
	}
}

func controller(args []string) {
	flags := flag.NewFlagSet("controller", flag.ExitOnError)
	arm := flags.String("arm", "", "ss, ri, rf, sharded-ri, or sharded-ri-stream")
	source := flags.String("source", "", "source endpoint URL")
	target := flags.String("target", "", "target endpoint URL")
	m1 := flags.Int("m1", 512, "SS first capacity")
	alpha := flags.Float64("alpha", 1.824, "SS multiplier")
	seed1 := flags.Uint64("seed1", 0x9E3779B97F4A7C15, "SS first-round mapping seed")
	seed2 := flags.Uint64("seed2", 0xD1B54A32D192ED03, "SS second-round fresh mapping seed")
	hardCap := flags.Int("hard-cap", 4096, "RI/RF capacity")
	batch := flags.Int("batch", 1024, "RI coded symbols per request")
	prefetch := flags.Int("ri-prefetch-batches", 0, "RI endpoint producer queue depth; 0 keeps the baseline synchronous path")
	horizon := flags.Bool("ri-horizon", false, "RI: use first-batch d_hat to request a predicted decode horizon")
	horizonAlpha := flags.Float64("ri-horizon-alpha", 1.35, "RI horizon multiplier applied to streaming d_hat")
	horizonFirst := flags.Int("ri-horizon-first", 1024, "RI first prefix used to estimate d_hat before horizon request")
	shards := flags.Int("ri-shards", 8, "sharded-RI encoder/decoder shard count")
	timeout := flags.Duration("timeout", 2*time.Hour, "HTTP request timeout")
	out := flags.String("out", "-", "result JSON path")
	_ = flags.Parse(args)
	if *arm == "" || *source == "" || *target == "" {
		fatalf("controller requires -arm, -source, and -target")
	}
	result, err := distributed.RunControllerWithOptions(*arm, *source, *target, *m1, *alpha, *seed1, *seed2, *hardCap, *batch,
		distributed.ControllerOptions{PrefetchBatches: *prefetch, Horizon: *horizon, HorizonAlpha: *horizonAlpha, HorizonFirst: *horizonFirst, Shards: *shards}, *timeout)
	if writeErr := distributed.WriteResult(*out, result); writeErr != nil {
		fatalf("write controller result: %v", writeErr)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "controller failed: %v\n", err)
		os.Exit(1)
	}
}

func generate(args []string) {
	flags := flag.NewFlagSet("generate", flag.ExitOnError)
	out := flags.String("out", "", "output directory for source/, target/, and truth.json")
	common := flags.Int("common", 10000, "number of symbols common to both endpoints")
	plus := flags.Int("plus", 100, "number of source-only symbols")
	minus := flags.Int("minus", 100, "number of target-only symbols")
	seed := flags.Uint64("seed", 0x726174656c657373, "deterministic generator seed")
	_ = flags.Parse(args)
	if *out == "" {
		fatalf("generate requires -out")
	}
	if err := experiment.GenerateSynthetic(*out, *common, *plus, *minus, *seed); err != nil {
		fatalf("generate: %v", err)
	}
	fmt.Printf("generated source=%d target=%d truth=%s\n", *common+*plus, *common+*minus, filepath.Join(*out, "truth.json"))
}

func verify(args []string) {
	flags := flag.NewFlagSet("verify", flag.ExitOnError)
	dir := flags.String("snapshot", "", "snapshot directory containing manifest.json and rows.fpid")
	digest := flags.Bool("sha256", true, "verify the full file digest")
	_ = flags.Parse(args)
	if *dir == "" {
		fatalf("verify requires -snapshot")
	}
	manifest, err := snapshot.Verify(*dir, *digest)
	if err != nil {
		fatalf("verify: %v", err)
	}
	raw, _ := json.MarshalIndent(manifest, "", "  ")
	fmt.Println(string(raw))
}

func run(args []string) {
	flags := flag.NewFlagSet("run", flag.ExitOnError)
	arm := flags.String("arm", "", "algorithm arm: ss, ri, or rf")
	source := flags.String("source", "", "source snapshot directory")
	target := flags.String("target", "", "target snapshot directory")
	truth := flags.String("truth", "", "optional truth.json for exact result checking")
	out := flags.String("out", "-", "result JSON path, or - for stdout")
	m1 := flags.Int("m1", 512, "self-sizing first-round capacity")
	alpha := flags.Float64("alpha", 1.824, "self-sizing second-round capacity multiplier")
	seed1 := flags.Uint64("seed1", 0x9E3779B97F4A7C15, "self-sizing first-round mapping seed")
	seed2 := flags.Uint64("seed2", 0xD1B54A32D192ED03, "self-sizing second-round fresh mapping seed")
	hardCap := flags.Int("hard-cap", 4096, "Rateless RI/RF prefix limit")
	verifySHA := flags.Bool("verify-sha256", false, "hash entire snapshots before the timed run")
	_ = flags.Parse(args)
	if *arm == "" || *source == "" || *target == "" {
		fatalf("run requires -arm, -source, and -target")
	}
	config := experiment.Config{
		Arm: *arm, Source: *source, Target: *target, Truth: *truth,
		M1: *m1, Alpha: *alpha, Seed1: *seed1, Seed2: *seed2,
		HardCap: *hardCap, VerifySHA: *verifySHA,
	}
	result, runErr := experiment.Run(config)
	raw, marshalErr := json.MarshalIndent(result, "", "  ")
	if marshalErr != nil {
		fatalf("marshal result: %v", marshalErr)
	}
	raw = append(raw, '\n')
	if *out == "-" {
		_, _ = os.Stdout.Write(raw)
	} else if err := os.WriteFile(*out, raw, 0o644); err != nil {
		fatalf("write result: %v", err)
	}
	if runErr != nil {
		fmt.Fprintf(os.Stderr, "run failed: %v\n", runErr)
		os.Exit(1)
	}
	if result.TruthChecked && !result.TruthMatch {
		fmt.Fprintln(os.Stderr, "run failed: recovered symbols do not match truth")
		os.Exit(1)
	}
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "error: "+format+"\n", args...)
	os.Exit(1)
}
