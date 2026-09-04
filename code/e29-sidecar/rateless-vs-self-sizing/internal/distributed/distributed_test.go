package distributed

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"self-sizing-artifact/rateless-vs-self-sizing/internal/experiment"
	"self-sizing-artifact/rateless-vs-self-sizing/internal/plain"
)

func testEndpointServer(t *testing.T, snapshotDir string) *httptest.Server {
	t.Helper()
	ep := &Endpoint{Snapshot: snapshotDir}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]any{"ok": true, "mapper_version": plain.MapperVersion})
	})
	mux.HandleFunc("/ss/build", ep.handlePlain)
	mux.HandleFunc("/rf/build", ep.handleFixed)
	mux.HandleFunc("/ri/prepare", ep.handlePrepare)
	mux.HandleFunc("/ri/next", ep.handleNext)
	mux.HandleFunc("/ri/stop", ep.handleStop)
	mux.HandleFunc("/sri/prepare", ep.handleShardedPrepare)
	mux.HandleFunc("/sri/next", ep.handleShardedNext)
	mux.HandleFunc("/sri/next-shard/", ep.handleShardedNextShard)
	mux.HandleFunc("/sri/stop", ep.handleShardedStop)
	return httptest.NewServer(mux)
}

func TestMapperHandshakeRejectsVersionMismatch(t *testing.T) {
	newHealthServer := func(version int) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, map[string]any{"ok": true, "mapper_version": version})
		}))
	}
	source := newHealthServer(plain.MapperVersion)
	defer source.Close()
	target := newHealthServer(plain.MapperVersion - 1)
	defer target.Close()

	err := verifyMapperHandshake(&http.Client{}, source.URL, target.URL)
	if err == nil || !strings.Contains(err.Error(), "mapper version mismatch") {
		t.Fatalf("expected mapper version mismatch, got %v", err)
	}
}

func TestDistributedShardedRILifecycle(t *testing.T) {
	root := t.TempDir()
	if err := experiment.GenerateSynthetic(root, 100, 80, 70, 0x5678); err != nil {
		t.Fatal(err)
	}
	source := testEndpointServer(t, filepath.Join(root, "source"))
	defer source.Close()
	target := testEndpointServer(t, filepath.Join(root, "target"))
	defer target.Close()
	result, err := RunControllerWithOptions("sharded-ri", source.URL, target.URL, 0, 0, 0, 0, 512, 64, ControllerOptions{Shards: 8}, 30_000_000_000)
	if err != nil || !result.Success {
		t.Fatalf("sharded RI: success=%v err=%v result=%+v", result.Success, err, result)
	}
	if result.Shards != 8 || result.Plus != 80 || result.Minus != 70 {
		t.Fatalf("unexpected sharded result: %+v", result)
	}
}

func TestDistributedShardedRIStreamLifecycle(t *testing.T) {
	root := t.TempDir()
	if err := experiment.GenerateSynthetic(root, 100, 80, 70, 0x9ABC); err != nil {
		t.Fatal(err)
	}
	source := testEndpointServer(t, filepath.Join(root, "source"))
	defer source.Close()
	target := testEndpointServer(t, filepath.Join(root, "target"))
	defer target.Close()
	result, err := RunControllerWithOptions("sharded-ri-stream", source.URL, target.URL, 0, 0, 0, 0, 512, 64, ControllerOptions{Shards: 8}, 30_000_000_000)
	if err != nil || !result.Success {
		t.Fatalf("sharded RI stream: success=%v err=%v result=%+v", result.Success, err, result)
	}
	if result.Shards != 8 || result.Plus != 80 || result.Minus != 70 || len(result.ShardPrefixes) != 8 {
		t.Fatalf("unexpected sharded stream result: %+v", result)
	}
}

func TestDistributedRIEndpointLifecycleBaselinePrefetchAndHorizon(t *testing.T) {
	root := t.TempDir()
	if err := experiment.GenerateSynthetic(root, 100, 80, 70, 0x1234); err != nil {
		t.Fatal(err)
	}
	source := testEndpointServer(t, filepath.Join(root, "source"))
	defer source.Close()
	target := testEndpointServer(t, filepath.Join(root, "target"))
	defer target.Close()

	const hardCap = 512
	base, err := RunControllerWithOptions("ri", source.URL, target.URL, 0, 0, 0, 0, hardCap, 64, ControllerOptions{}, 30_000_000_000)
	if err != nil || !base.Success {
		t.Fatalf("baseline: success=%v err=%v result=%+v", base.Success, err, base)
	}
	if len(base.RIProgress) == 0 || base.RIProgress[0].Samples == 0 {
		t.Fatalf("baseline did not record online progress: %+v", base.RIProgress)
	}
	if len(base.EndpointSource) < 2 || base.EndpointSource[1].GenerateMillis < 0 {
		t.Fatalf("baseline missing coded generation metrics: %+v", base.EndpointSource)
	}

	prefetched, err := RunControllerWithOptions("ri", source.URL, target.URL, 0, 0, 0, 0, hardCap, 64, ControllerOptions{PrefetchBatches: 2}, 30_000_000_000)
	if err != nil || !prefetched.Success {
		t.Fatalf("prefetch: success=%v err=%v result=%+v", prefetched.Success, err, prefetched)
	}

	horizon, err := RunControllerWithOptions("ri", source.URL, target.URL, 0, 0, 0, 0, hardCap, 64, ControllerOptions{Horizon: true, HorizonFirst: 64, HorizonAlpha: 1.35}, 30_000_000_000)
	if err != nil || !horizon.Success {
		t.Fatalf("horizon: success=%v err=%v result=%+v", horizon.Success, err, horizon)
	}
	if horizon.Horizon == nil || horizon.Horizon.DHat <= 0 || horizon.Horizon.PlannedPrefix < horizon.Horizon.FirstPrefix {
		t.Fatalf("invalid horizon metrics: %+v", horizon.Horizon)
	}
}
