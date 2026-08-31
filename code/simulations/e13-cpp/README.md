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

Full replay is classified as `provenance-only` until the historical fast-baseline
input and acceptance comparison have been independently verified. The frozen CSV
used by the paper is authoritative.
