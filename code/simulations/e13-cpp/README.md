# E13 C++ provenance generator

This directory contains the historical C++ generator associated with the E13
retry-success calibration. The frozen paper input remains
`data/paper/plain-e13-retry-success.csv`; this source is supplied for
provenance and fresh statistical replay, not as a byte-for-byte regeneration
guarantee.

## Requirements

- a C++17 compiler;
- POSIX threads; and
- OpenSSL `libcrypto` headers and library. `core/iblt/hash_family.cpp` calls
  `SHA256`, so both the include path and link path must be discoverable.

The build script checks, in order:

1. an explicit `OPENSSL_PREFIX`;
2. `pkg-config openssl`; and
3. the compiler's default include/library paths.

On macOS with Homebrew OpenSSL 3, use an explicit prefix because Homebrew keeps
OpenSSL keg-only:

```bash
cd code/simulations/e13-cpp
OPENSSL_PREFIX=$(brew --prefix openssl@3) ./build.sh
```

The equivalent direct compiler invocation is:

```bash
g++ -std=c++17 -O2 -pthread \
  -I. -I"$(brew --prefix openssl@3)/include" \
  tier1_failed_only_sweep.cpp \
  core/estimators/plain_f2_estimator.cpp \
  core/iblt/hash_family.cpp \
  core/iblt/standard_iblt.cpp \
  -L"$(brew --prefix openssl@3)/lib" -lcrypto \
  -o build/tier1_failed_only_sweep
```

On Debian/Ubuntu, install `g++`, `pkg-config`, and `libssl-dev`; then
`./build.sh` should discover OpenSSL without extra flags.

## Quick replay

The base seed defaults to `2026`; a retry uses `seed + 7919`. A small execution
that checks the binary and CSV schema is:

```bash
./build/tier1_failed_only_sweep \
  --trials 2 --threads 2 \
  --M-list 256 --d-over-m-list 0.8 \
  --neg-ratios 0.5 --gamma-list 1.2 \
  --out /tmp/e13-cpp-smoke.csv
```

The M1=64 extension used by the paper can be replayed with the exact grid:

```bash
./build/tier1_failed_only_sweep \
  --trials 10000 --threads 32 --M-list 64 --k-list 3 \
  --d-over-m-list 0.3,0.5,0.7,0.8,0.9,1.0,1.2,1.5,2.0,3.0,5.0,10.0 \
  --neg-ratios 0.1,0.5,0.9 --gamma-list 1.6,1.8,2.0 \
  --out /tmp/plain-e13-m64-raw.csv
python3 audit_retry_grid.py /tmp/plain-e13-m64-raw.csv
```

This produces 1,080,000 data rows (36 grid points, 10,000 trials per point,
and three alpha values). The frozen run has gzip SHA-256
`3d256e3fe8b7856f8ebecfbc5e792528d9bf404e27a7c27aafb82b7298b0ea0a`.
CSV row order can vary with thread scheduling, so statistical aggregation and
the auditor are the reproducibility contract rather than byte identity.
