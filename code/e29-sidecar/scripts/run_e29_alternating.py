#!/usr/bin/env python3
"""Run the E29 warm-up plus alternating-three-round timing protocol.

This runner does not start databases, mutate fixtures, or change network
shaping.  Those setup steps happen before it.  It invokes each complete
algorithm pipeline, captures the machine-readable E29_RESULT line, and writes
raw stdout/stderr beside the CSV as evidence.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path


RESULT_RE = re.compile(r"^E29_RESULT\s+(.*)$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", help="common source URL (legacy shortcut)")
    parser.add_argument("--target", help="common target URL (legacy shortcut)")
    parser.add_argument("--iblt-source", help="IBLT source sidecar URL")
    parser.add_argument("--iblt-target", help="IBLT target sidecar URL")
    parser.add_argument("--merkle-source", help="Merkle source sidecar URL")
    parser.add_argument("--merkle-target", help="Merkle target sidecar URL")
    parser.add_argument("--iblt-command", required=True,
                        help="command template; use {source} and {target}")
    parser.add_argument("--merkle-command", required=True,
                        help="command template; use {source}, {target}, {chunk_size}")
    parser.add_argument("--chunk-size", type=int, default=10000)
    parser.add_argument("--table-pair", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--network-profile", required=True,
                        help="e.g. baseline, production, high-rtt")
    parser.add_argument("--rtt-ms", type=float, required=True)
    parser.add_argument("--bandwidth-mbps", type=float, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--log-dir", type=Path)
    parser.add_argument("--summary-output", type=Path)
    parser.add_argument("--keep-going", action="store_true",
                        help="record a failed invocation and continue")
    return parser.parse_args()


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")


def endpoint(args: argparse.Namespace, algorithm: str, side: str) -> str:
    specific = getattr(args, f"{algorithm.lower()}_{side}")
    common = getattr(args, side)
    value = specific or common
    if not value:
        raise SystemExit(f"missing --{algorithm.lower()}-{side} or --{side}")
    return value


def command_for(template: str, algorithm: str, args: argparse.Namespace) -> list[str]:
    rendered = template.format(
        source=endpoint(args, algorithm, "source"),
        target=endpoint(args, algorithm, "target"),
        chunk_size=args.chunk_size)
    return shlex.split(rendered)


def run_one(algorithm: str, phase: str, formal_round: int, sequence: int,
            args: argparse.Namespace, log_dir: Path) -> dict[str, str]:
    template = args.iblt_command if algorithm == "IBLT" else args.merkle_command
    command = command_for(template, algorithm, args)
    run_id = f"{phase}-r{formal_round}-{sequence}-{algorithm.lower()}"
    stdout_path = log_dir / f"{run_id}.stdout.log"
    stderr_path = log_dir / f"{run_id}.stderr.log"

    started_at = utc_now()
    wrapper_started = time.monotonic()
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    wrapper_ms = round((time.monotonic() - wrapper_started) * 1000, 3)
    stdout_path.write_text(completed.stdout, encoding="utf-8")
    stderr_path.write_text(completed.stderr, encoding="utf-8")

    result_fields: dict[str, str] = {}
    result_line = ""
    for line in completed.stdout.splitlines():
        match = RESULT_RE.match(line.strip())
        if not match:
            continue
        result_line = line.strip()
        for token in match.group(1).split():
            if "=" in token:
                key, value = token.split("=", 1)
                result_fields[key] = value
        break

    status = "PASS" if completed.returncode == 0 and result_line else "FAIL"
    return {
        "run_id": run_id,
        "started_at_utc": started_at,
        "phase": phase,
        "formal_round": str(formal_round),
        "sequence": str(sequence),
        "algorithm": algorithm,
        "table_pair": args.table_pair,
        "scenario": args.scenario,
        "network_profile": args.network_profile,
        "rtt_ms": str(args.rtt_ms),
        "bandwidth_mbps": str(args.bandwidth_mbps),
        "status": status,
        "exit_code": str(completed.returncode),
        "e2e_ms": result_fields.get("e2e_ms", ""),
        "build_ms_a": result_fields.get("buildMsA", ""),
        "build_ms_b": result_fields.get("buildMsB", ""),
        "partition_ms_a": result_fields.get("partitionMsA", ""),
        "partition_ms_b": result_fields.get("partitionMsB", ""),
        "rebucket_ms_a": result_fields.get("rebucketMsA", ""),
        "rebucket_ms_b": result_fields.get("rebucketMsB", ""),
        "resolve_ms_a": result_fields.get("resolveMsA", ""),
        "resolve_ms_b": result_fields.get("resolveMsB", ""),
        "resolve_ms_total": result_fields.get("resolveMsTotal", ""),
        "recheck_http_ms_a": result_fields.get("recheckHttpMsA", ""),
        "recheck_http_ms_b": result_fields.get("recheckHttpMsB", ""),
        "recheck_http_ms_wall": result_fields.get("recheckHttpMsWall", ""),
        "recheck_db_ms_a": result_fields.get("recheckDbMsA", ""),
        "recheck_db_ms_b": result_fields.get("recheckDbMsB", ""),
        "recheck_hit_a": result_fields.get("recheckHitA", ""),
        "recheck_hit_b": result_fields.get("recheckHitB", ""),
        "boundaries_ms": result_fields.get("boundariesMs", ""),
        "checksums_ms": result_fields.get("checksumsMs", ""),
        "drill_ms": result_fields.get("drillMs", ""),
        "compare_ms": result_fields.get("compareMs", ""),
        "merkle_workers_a": result_fields.get("merkleWorkersA", ""),
        "merkle_workers_b": result_fields.get("merkleWorkersB", ""),
        "checksum_workers_a": result_fields.get("checksumWorkersA", ""),
        "checksum_workers_b": result_fields.get("checksumWorkersB", ""),
        "drill_workers_a": result_fields.get("drillWorkersA", ""),
        "drill_workers_b": result_fields.get("drillWorkersB", ""),
        "wrapper_ms": str(wrapper_ms),
        "result_line": result_line,
        "stdout_log": str(stdout_path),
        "stderr_log": str(stderr_path),
    }


def write_csv(path: Path, rows: list[dict[str, str]], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_summary(path: Path, rows: list[dict[str, str]]) -> None:
    groups: dict[tuple[str, str, str, str], list[float]] = {}
    for row in rows:
        if row["phase"] != "measure" or row["status"] != "PASS" or not row["e2e_ms"]:
            continue
        key = (row["table_pair"], row["scenario"], row["network_profile"], row["algorithm"])
        groups.setdefault(key, []).append(float(row["e2e_ms"]))
    summary_rows: list[dict[str, str]] = []
    for (table_pair, scenario, profile, algorithm), values in sorted(groups.items()):
        summary_rows.append({
            "table_pair": table_pair,
            "scenario": scenario,
            "network_profile": profile,
            "algorithm": algorithm,
            "n": str(len(values)),
            "median_e2e_ms": str(statistics.median(values)),
            "min_e2e_ms": str(min(values)),
            "max_e2e_ms": str(max(values)),
        })
    write_csv(path, summary_rows, [
        "table_pair", "scenario", "network_profile", "algorithm", "n",
        "median_e2e_ms", "min_e2e_ms", "max_e2e_ms",
    ])


def main() -> int:
    args = parse_args()
    if args.chunk_size <= 0:
        raise SystemExit("--chunk-size must be positive")
    log_dir = args.log_dir or args.output.with_suffix("").with_name(args.output.stem + "-logs")
    log_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict[str, str]] = []
    # Complete normal pipeline calls only; no artificial cache priming.
    for sequence, algorithm in enumerate(("IBLT", "MERKLE"), start=1):
        print(f"[warmup] {algorithm}", flush=True)
        row = run_one(algorithm, "warmup", 0, sequence, args, log_dir)
        rows.append(row)
        if row["status"] == "FAIL" and not args.keep_going:
            break

    if rows and (rows[-1]["status"] == "PASS" or args.keep_going):
        orders = (("IBLT", "MERKLE"), ("MERKLE", "IBLT"), ("IBLT", "MERKLE"))
        for formal_round, order in enumerate(orders, start=1):
            for sequence, algorithm in enumerate(order, start=1):
                print(f"[measure] round={formal_round} {algorithm}", flush=True)
                row = run_one(algorithm, "measure", formal_round, sequence, args, log_dir)
                rows.append(row)
                if row["status"] == "FAIL" and not args.keep_going:
                    break
            if rows[-1]["status"] == "FAIL" and not args.keep_going:
                break

    fields = [
        "run_id", "started_at_utc", "phase", "formal_round", "sequence",
        "algorithm", "table_pair", "scenario", "network_profile", "rtt_ms",
        "bandwidth_mbps", "status", "exit_code", "e2e_ms",
        "build_ms_a", "build_ms_b", "rebucket_ms_a", "rebucket_ms_b",
        "partition_ms_a", "partition_ms_b",
        "resolve_ms_a", "resolve_ms_b", "resolve_ms_total",
        "recheck_http_ms_a", "recheck_http_ms_b", "recheck_http_ms_wall",
        "recheck_db_ms_a", "recheck_db_ms_b", "recheck_hit_a", "recheck_hit_b",
        "boundaries_ms", "checksums_ms", "drill_ms", "compare_ms",
        "merkle_workers_a", "merkle_workers_b", "checksum_workers_a",
        "checksum_workers_b", "drill_workers_a", "drill_workers_b", "wrapper_ms",
        "result_line", "stdout_log", "stderr_log",
    ]
    write_csv(args.output, rows, fields)
    summary_path = args.summary_output or args.output.with_suffix(".summary.csv")
    write_summary(summary_path, rows)
    failed = [row for row in rows if row["status"] != "PASS"]
    print(f"wrote {args.output} ({len(rows)} invocations)", flush=True)
    print(f"wrote {summary_path}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
