# Rateless versus self-sizing replay harness

This Go module is the source used for the Rateless comparison. It reads the same
immutable fingerprint stream for three arms:

- `ss`: plain IBLT `M1 -> dHat -> fresh M2`;
- `ri`: the pinned `riblt.Encoder`, extended until decode or a hard cap; and
- `rf`: the pinned fixed-prefix `riblt.Sketch(M)` baseline.

The snapshot record is two unsigned 64-bit values `(fp,id)`, big-endian, 16 bytes
per row. A JSON manifest records format version, row count, bytes, and SHA-256 for
each immutable shard. Formal fingerprint snapshots are too large for Git and are
represented by checksums in the Artifact data manifest.

## Local smoke test

From this directory:

```bash
go test ./...

fixture_dir=$(mktemp -d)
go run . generate -out "$fixture_dir/pair" -common 10000 -plus 100 -minus 80

for arm in ss ri rf; do
  go run . run \
    -arm "$arm" \
    -source "$fixture_dir/pair/source" \
    -target "$fixture_dir/pair/target" \
    -truth "$fixture_dir/pair/truth.json" \
    -m1 128 -alpha 3 -hard-cap 1024 \
    -out "$fixture_dir/$arm.json"
done
```

The pinned external dependency and module path are recorded in `go.mod` and
`go.sum`.

For an immutable production snapshot pair, derive an exact multiset truth file
with bounded memory and verify every input shard before replaying:

```bash
go run . derive-truth \
  -source /path/to/source -target /path/to/target \
  -work-dir /path/to/scratch -partitions 256 \
  -out /path/to/truth.json
go run . run -arm ss \
  -source /path/to/source -target /path/to/target \
  -truth /path/to/truth.json -verify-sha256 \
  -m1 512 -alpha 1.824 -out result.json
```

After all corrected runs finish, `scripts/finalize_corrected_sweep.py` checks
that every result used the verified truth and records the mapper version, code
commit/diff digest, replay-binary digest, input-manifest digests, result
digests, and aggregate statistics in one sweep manifest.

For the formal 12-seed sweep, `scripts/rerun_truth_audit.sh` runs four seeds in
parallel, writes each result through a temporary file, and replaces the prior
JSON only after the replay exits successfully with an exact truth match.

## Distributed mode

The `endpoint` and `controller` commands exercise the networked path. Each
endpoint reads only its local immutable snapshot; the controller subtracts and
decodes the returned sketches. A minimal local run starts both endpoints on
loopback and then invokes the controller:

```bash
go run . endpoint -snapshot "$fixture_dir/pair/source" -listen 127.0.0.1:19401
go run . endpoint -snapshot "$fixture_dir/pair/target" -listen 127.0.0.1:19402
go run . controller -arm ss \
  -source http://127.0.0.1:19401 \
  -target http://127.0.0.1:19402 \
  -m1 128 -alpha 3
```

The implementation also contains bounded-prefetch, estimator-horizon, and
sharded Rateless variants. These are separate experimental arms; their queue
depth, shard count, hard cap, wire bytes, and endpoint memory must be reported
when used. Frozen paper CSVs remain the authoritative result inputs even when a
fresh replay differs slightly because of machine or database conditions.
