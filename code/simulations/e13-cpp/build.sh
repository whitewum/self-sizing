#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-"$SCRIPT_DIR/build/tier1_failed_only_sweep"}
OPENSSL_PREFIX=${OPENSSL_PREFIX:-}

mkdir -p "$(dirname "$OUT")"

openssl_cflags=()
openssl_libs=()
if [ -n "$OPENSSL_PREFIX" ]; then
  openssl_cflags=(-I"$OPENSSL_PREFIX/include")
  openssl_libs=(-L"$OPENSSL_PREFIX/lib" -lcrypto)
elif command -v pkg-config >/dev/null 2>&1 && pkg-config --exists openssl; then
  while IFS= read -r token; do
    [ -n "$token" ] && openssl_cflags+=("$token")
  done < <(pkg-config --cflags-only-I openssl | tr ' ' '\n')
  while IFS= read -r token; do
    [ -n "$token" ] && openssl_libs+=("$token")
  done < <(pkg-config --libs openssl | tr ' ' '\n')
else
  openssl_libs=(-lcrypto)
fi

${CXX:-c++} -std=c++17 -O2 -pthread \
  -I"$SCRIPT_DIR" \
  "${openssl_cflags[@]}" \
  "$SCRIPT_DIR/tier1_failed_only_sweep.cpp" \
  "$SCRIPT_DIR/core/estimators/plain_f2_estimator.cpp" \
  "$SCRIPT_DIR/core/iblt/hash_family.cpp" \
  "$SCRIPT_DIR/core/iblt/standard_iblt.cpp" \
  "${openssl_libs[@]}" \
  -o "$OUT"

echo "built $OUT"
