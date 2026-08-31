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

case "$ROLE" in
  a)
    REDIS_HOST=${A_REDIS_HOST:?missing A_REDIS_HOST}
    REDIS_PORT=${A_REDIS_PORT:-6379}
    REDIS_DB=${A_REDIS_DB:-0}
    REDIS_PASSWORD=${A_REDIS_PASSWORD:-}
    SCAN_COUNT=${A_REDIS_SCAN_COUNT:-1000}
    SIDECAR_BIND=${A_SIDECAR_BIND:-127.0.0.1}
    SIDECAR_PORT=${A_SIDECAR_PORT:-9090}
    ;;
  b)
    REDIS_HOST=${B_REDIS_HOST:?missing B_REDIS_HOST}
    REDIS_PORT=${B_REDIS_PORT:-6379}
    REDIS_DB=${B_REDIS_DB:-0}
    REDIS_PASSWORD=${B_REDIS_PASSWORD:-}
    SCAN_COUNT=${B_REDIS_SCAN_COUNT:-1000}
    SIDECAR_BIND=${B_SIDECAR_BIND:-127.0.0.1}
    SIDECAR_PORT=${B_SIDECAR_PORT:-9090}
    ;;
esac

CLASS_DIR=${CLASS_DIR:-build/classes}
RUN_DIR=${RUN_DIR:-run}
[[ "$CLASS_DIR" = /* ]] || CLASS_DIR="$KIT_ROOT/$CLASS_DIR"
[[ "$RUN_DIR" = /* ]] || RUN_DIR="$KIT_ROOT/$RUN_DIR"
ROLE_DIR="$RUN_DIR/sidecar-$ROLE"
PID_FILE="$ROLE_DIR/sidecar.pid"
LOG_FILE="$ROLE_DIR/sidecar.log"
FP_DIR="$ROLE_DIR/fingerprint-cache"
SERVER_CLASS=io.github.selfsizing.iblt.sidecar.IbltSidecarServer

if [ ! -f "$CLASS_DIR/io/github/selfsizing/iblt/sidecar/IbltSidecarServer.class" ]; then
  "$SCRIPT_DIR/build.sh" "$CLASS_DIR"
fi

mkdir -p "$ROLE_DIR"
if [ -s "$PID_FILE" ]; then
  old=$(cat "$PID_FILE")
  if kill -0 "$old" 2>/dev/null; then
    args=$(ps -p "$old" -o args= 2>/dev/null || true)
    case "$args" in
      *"$SERVER_CLASS"*"--port $SIDECAR_PORT"*)
        echo "sidecar $ROLE is already running: pid=$old"
        exit 0
        ;;
      *)
        echo "refusing to replace unrelated pid $old from $PID_FILE" >&2
        exit 1
        ;;
    esac
  fi
  rm -f "$PID_FILE"
fi

rm -rf "$FP_DIR"
mkdir -p "$FP_DIR"
args=(
  --bind "$SIDECAR_BIND"
  --port "$SIDECAR_PORT"
  --dialect redis
  --redis-host "$REDIS_HOST"
  --redis-port "$REDIS_PORT"
  --redis-db "$REDIS_DB"
  --redis-password "$REDIS_PASSWORD"
  --redis-scan-count "$SCAN_COUNT"
  --table "db$REDIS_DB"
)

FP_STORE=file FP_STORE_DIR="$FP_DIR" \
  nohup java -cp "$CLASS_DIR" "$SERVER_CLASS" "${args[@]}" \
  >"$LOG_FILE" 2>&1 </dev/null &
pid=$!
echo "$pid" > "$PID_FILE"

health_host=$SIDECAR_BIND
[ "$health_host" = "0.0.0.0" ] && health_host=127.0.0.1
health_url="http://$health_host:$SIDECAR_PORT/health"
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if command -v curl >/dev/null 2>&1; then
    health=$(curl -fsS --max-time 2 "$health_url" 2>/dev/null || true)
  else
    health=$(wget -qO- --timeout=2 "$health_url" 2>/dev/null || true)
  fi
  if [ -n "$health" ]; then
    echo "sidecar $ROLE started: pid=$pid $health"
    exit 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "sidecar $ROLE exited during startup; log follows:" >&2
    tail -n 40 "$LOG_FILE" >&2 || true
    rm -f "$PID_FILE"
    exit 1
  fi
  sleep 1
done

echo "sidecar $ROLE health check timed out: $health_url" >&2
tail -n 40 "$LOG_FILE" >&2 || true
exit 1
