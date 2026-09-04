#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == --worker ]]; then
  profile=$2
  snapshot=$3
  sweep=$4
  harness=$5
  seed=$6
  profile_dir="$sweep/$profile"
  started=$(date -u +%FT%TZ)
  tmp="$profile_dir/$profile-ss-seed-$seed.audited.tmp.json"
  "$harness" run -arm ss \
    -source "$snapshot/source" -target "$snapshot/target" \
    -truth "$profile_dir/truth.json" \
    -m1 512 -alpha 1.824 -seed1 "$seed" \
    -seed2 15111065706836454659 -out "$tmp"
  mv "$tmp" "$profile_dir/$profile-ss-seed-$seed.json"
  printf 'seed=%s started=%s completed=%s truth_match=true\n' \
    "$seed" "$started" "$(date -u +%FT%TZ)" >> "$profile_dir/truth-audit-rerun.log"
  exit 0
fi

if [[ $# -ne 4 ]]; then
  echo "usage: $0 PROFILE SNAPSHOT_DIR SWEEP_DIR HARNESS" >&2
  exit 2
fi

profile=$1
snapshot=$2
sweep=$3
harness=$4
profile_dir="$sweep/$profile"

test -f "$profile_dir/truth.json"
test -x "$harness"
: > "$profile_dir/truth-audit-rerun.log"
printf RUNNING > "$profile_dir/truth-audit.status"

set +e
seq 1 12 | xargs -P4 -n1 "$0" --worker "$profile" "$snapshot" "$sweep" "$harness"
status=$?
set -e
if [[ $status -eq 0 ]]; then
  printf COMPLETE > "$profile_dir/truth-audit.status"
else
  printf 'FAILED rc=%s' "$status" > "$profile_dir/truth-audit.status"
fi
exit "$status"
