#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <a|b> [config.env]" >&2
  exit 2
}

ROLE=${1:-}
[ "$ROLE" = "a" ] || [ "$ROLE" = "b" ] || usage
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
KIT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
CONFIG=${2:-"$KIT_ROOT/config.env"}
[ -f "$CONFIG" ] || { echo "config not found: $CONFIG" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
. "$CONFIG"
set +a

RUN_DIR=${RUN_DIR:-run}
[[ "$RUN_DIR" = /* ]] || RUN_DIR="$KIT_ROOT/$RUN_DIR"
ROLE_DIR="$RUN_DIR/sidecar-$ROLE"
PID_FILE="$ROLE_DIR/sidecar.pid"
FP_DIR="$ROLE_DIR/fingerprint-cache"
SERVER_CLASS=io.github.selfsizing.iblt.sidecar.IbltSidecarServer

if [ -s "$PID_FILE" ]; then
  pid=$(cat "$PID_FILE")
  if kill -0 "$pid" 2>/dev/null; then
    args=$(ps -p "$pid" -o args= 2>/dev/null || true)
    case "$args" in
      *"$SERVER_CLASS"*) kill "$pid" ;;
      *)
        echo "refusing to kill unrelated pid $pid from $PID_FILE" >&2
        exit 1
        ;;
    esac
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "sidecar $ROLE did not stop within 10 seconds" >&2
      exit 1
    fi
  fi
  rm -f "$PID_FILE"
fi

rm -rf "$FP_DIR"
echo "sidecar $ROLE stopped; fingerprint cache removed"
