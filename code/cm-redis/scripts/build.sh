#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
KIT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
OUT=${1:-"$KIT_ROOT/build/classes"}

rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  $(find "$KIT_ROOT/src/main/java" -name '*.java' ! -name '._*')

echo "compiled $(find "$KIT_ROOT/src/main/java" -name '*.java' ! -name '._*' | wc -l | tr -d ' ') Java files -> $OUT"
