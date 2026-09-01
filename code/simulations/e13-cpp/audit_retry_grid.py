#!/usr/bin/env python3
"""Audit an E13 retry raw CSV before it enters the frozen aggregation."""

from __future__ import annotations

import argparse
import csv
import gzip
from collections import Counter
from pathlib import Path


EXPECTED_HEADER = (
    "point_id", "trial", "M", "k", "d", "d_over_m", "neg_ratio",
    "gamma", "seed", "ok1", "recovered1", "S_net", "S_sq", "E_global",
    "C_theory", "d_hat", "dhat_over_d", "M2", "ok2", "error",
)
EXPECTED_LOADS = (0.3, 0.5, 0.7, 0.8, 0.9, 1.0, 1.2, 1.5, 2.0, 3.0, 5.0, 10.0)
EXPECTED_NEG_RATIOS = (0.1, 0.5, 0.9)
EXPECTED_GAMMAS = (1.6, 1.8, 2.0)


def numbers(text: str, cast):
    return tuple(cast(item) for item in text.split(",") if item)


def open_csv(path: Path):
    return gzip.open(path, "rt", newline="") if path.suffix == ".gz" else path.open(newline="")


def check_task(key, rows, expected_gammas):
    if key is None:
        return
    if {float(row["gamma"]) for row in rows} != expected_gammas or len(rows) != len(expected_gammas):
        raise SystemExit(f"task {key} has incomplete alpha rows")
    invariant = ("point_id", "trial", "M", "k", "d", "d_over_m", "neg_ratio",
                 "seed", "ok1", "recovered1", "S_net", "S_sq", "E_global",
                 "C_theory", "d_hat", "dhat_over_d", "error")
    baseline = tuple(rows[0][field] for field in invariant)
    if any(tuple(row[field] for field in invariant) != baseline for row in rows[1:]):
        raise SystemExit(f"round-1 fields vary across alpha rows for task {key}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--expected-ms", default="64")
    parser.add_argument("--expected-trials", type=int, default=10_000)
    parser.add_argument("--loads", default=",".join(map(str, EXPECTED_LOADS)))
    parser.add_argument("--neg-ratios", default=",".join(map(str, EXPECTED_NEG_RATIOS)))
    parser.add_argument("--gammas", default=",".join(map(str, EXPECTED_GAMMAS)))
    args = parser.parse_args()

    expected_ms = set(numbers(args.expected_ms, int))
    loads, negs, gammas = set(numbers(args.loads, float)), set(numbers(args.neg_ratios, float)), set(numbers(args.gammas, float))
    expected_points = len(expected_ms) * len(loads) * len(negs)
    expected_rows = expected_points * args.expected_trials * len(gammas)
    row_count, current_key, current_rows = 0, None, []
    points, task_counts = set(), Counter()
    success, failed, failed_success = Counter(), Counter(), Counter()

    with open_csv(args.path) as handle:
        reader = csv.DictReader(handle)
        if tuple(reader.fieldnames or ()) != EXPECTED_HEADER:
            raise SystemExit(f"unexpected header: {reader.fieldnames}")
        for row in reader:
            row_count += 1
            m, point_id, trial = int(row["M"]), int(row["point_id"]), int(row["trial"])
            load, neg, gamma = float(row["d_over_m"]), float(row["neg_ratio"]), float(row["gamma"])
            if row["error"] or m not in expected_ms or load not in loads or neg not in negs or gamma not in gammas or int(row["k"]) != 3:
                raise SystemExit(f"invalid grid row {row_count}")
            if not 0 <= trial < args.expected_trials or int(row["seed"]) != 2026 + point_id * 1_000_003 + trial:
                raise SystemExit(f"trial/seed mismatch at row {row_count}")
            if row["ok1"] == "1":
                if row["M2"] or row["ok2"]:
                    raise SystemExit(f"round-2 fields populated after success at row {row_count}")
            elif not row["M2"] or row["ok2"] not in {"0", "1"}:
                raise SystemExit(f"round-2 fields missing after failure at row {row_count}")
            task_key = m, point_id, trial
            if task_key != current_key:
                check_task(current_key, current_rows, gammas)
                current_key, current_rows = task_key, []
                task_counts[(m, point_id)] += 1
            current_rows.append(row)
            points.add((m, point_id, load, neg))
            success[(m, gamma)] += row["ok1"] == "1" or row["ok2"] == "1"
            if row["ok1"] != "1":
                failed[(m, gamma)] += 1
                failed_success[(m, gamma)] += row["ok2"] == "1"
        check_task(current_key, current_rows, gammas)

    if row_count != expected_rows or len(points) != expected_points or set(task_counts.values()) != {args.expected_trials}:
        raise SystemExit("row, point, or per-point trial count mismatch")
    print(f"PASS: {args.path}: {row_count} rows, {len(points)} points, M={sorted(expected_ms)}")
    per_m_rows = len(loads) * len(negs) * args.expected_trials
    for m in sorted(expected_ms):
        for gamma in sorted(gammas):
            ft, fs = failed[(m, gamma)], failed_success[(m, gamma)]
            print(f"M={m} alpha={gamma:g}: overall={success[(m, gamma)] / per_m_rows:.6%} "
                  f"failed_only={fs / ft:.6%} ({fs}/{ft})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
