#!/usr/bin/env bash
set -uo pipefail

usage() {
  cat >&2 <<'EOF'
usage: compare-existing.sh <iblt|merkle|both> [config.env]
       [--truth <file>] [--expect-equal] [--buckets <n>]
       [--value-sample-limit <n>]

This command never starts, stops, flushes, or writes Redis.
EOF
  exit 2
}

MODE=${1:-}
case "$MODE" in iblt|merkle|both) ;; *) usage ;; esac
shift

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
KIT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
CONFIG="$KIT_ROOT/config.env"
if [ $# -gt 0 ] && [[ "$1" != --* ]]; then
  CONFIG=$1
  shift
fi
[ -f "$CONFIG" ] || { echo "config not found: $CONFIG" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
. "$CONFIG"
set +a

TRUTH=
EXPECT_EQUAL=0
BUCKETS=${MERKLE_BUCKETS:-128}
SAMPLE_LIMIT=${VALUE_SAMPLE_LIMIT:-0}
while [ $# -gt 0 ]; do
  case "$1" in
    --truth) [ $# -ge 2 ] || usage; TRUTH=$2; shift 2 ;;
    --expect-equal) EXPECT_EQUAL=1; shift ;;
    --buckets) [ $# -ge 2 ] || usage; BUCKETS=$2; shift 2 ;;
    --value-sample-limit) [ $# -ge 2 ] || usage; SAMPLE_LIMIT=$2; shift 2 ;;
    *) usage ;;
  esac
done

A_SIDECAR_URL=${A_SIDECAR_URL:?missing A_SIDECAR_URL}
B_SIDECAR_URL=${B_SIDECAR_URL:?missing B_SIDECAR_URL}
CLASS_DIR=${CLASS_DIR:-build/classes}
[[ "$CLASS_DIR" = /* ]] || CLASS_DIR="$KIT_ROOT/$CLASS_DIR"
if [ ! -f "$CLASS_DIR/io/github/selfsizing/iblt/sidecar/IbltSidecarServer.class" ]; then
  "$SCRIPT_DIR/build.sh" "$CLASS_DIR" || exit $?
fi

MERKLE_CLASS=io.github.selfsizing.iblt.smoke.DistributedRedisMerkleSmoke
IBLT_CLASS=io.github.selfsizing.iblt.smoke.DistributedCompareSmoke
common=()
[ -n "$TRUTH" ] && common+=(--truth "$TRUTH")
[ "$EXPECT_EQUAL" -eq 1 ] && common+=(--expect-equal)

rc=0
if [ "$MODE" = "merkle" ] || [ "$MODE" = "both" ]; then
  echo "=== Redis optimized-Merkle ==="
  java -cp "$CLASS_DIR" "$MERKLE_CLASS" \
    "$A_SIDECAR_URL" "$B_SIDECAR_URL" "$BUCKETS" "${common[@]}" || rc=$?
fi
if [ "$MODE" = "iblt" ] || [ "$MODE" = "both" ]; then
  echo "=== Redis IBLT ==="
  iblt_args=("${common[@]}" --value-sample-limit "$SAMPLE_LIMIT")
  java -cp "$CLASS_DIR" "$IBLT_CLASS" \
    "$A_SIDECAR_URL" "$B_SIDECAR_URL" "${iblt_args[@]}" || rc=$?
fi
exit "$rc"
