#!/usr/bin/env bash
# Build the relational (Oracle/MySQL composite-key) IBLT sidecar.
#
# It reuses the IBLT core, SQL dialects, and controller from the KV sidecar
# (../../cm-redis), so that module is compiled first and put on the classpath.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
kv="$here/../../cm-redis"
out="$here/build/classes"
kv_out="$here/build/kv-classes"

rm -rf "$out" "$kv_out"
mkdir -p "$out" "$kv_out"

echo "[build] compiling KV sidecar core/sql/controller/sidecar ..."
find "$kv/src/main/java" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d "$kv_out"

echo "[build] compiling relational sidecar ..."
find "$here/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -cp "$kv_out" -d "$out"

echo "[build] running database-free self-tests ..."
for t in CompositeIbltPatchSelfTest MysqlSampleFenceSelfTest MerkleDirtyDescentSqlSelfTest \
         ParallelSketchMergeSelfTest HttpSidecarClientTimeoutSelfTest; do
  java -cp "$kv_out:$out" "io.github.selfsizing.iblt.relational.$t" >/dev/null \
    && echo "  ok  $t" || { echo "  FAIL $t"; exit 1; }
done

echo "[build] done. classes in $out (KV classes in $kv_out)"
