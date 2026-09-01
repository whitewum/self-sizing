#!/usr/bin/env python3
"""Extend the frozen four-tier E13 lock with an audited M1=64 sweep.

The paper reports the overall two-attempt success rate over M1>=256 and lists
M1=64 separately. The output therefore uses explicit scope names for the
four-tier paper aggregate and the optional five-tier diagnostic aggregate.
"""

from __future__ import annotations

import argparse
import csv
import gzip
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BASE = ROOT / "data" / "paper" / "plain-e13-retry-success-4tier.csv"
DEFAULT_OUTPUT = ROOT / "results" / "generated" / "plain-e13-retry-success.csv"
BASE_MS = {256, 512, 1024, 4096}
GAMMAS = {1.6, 1.8, 2.0}
TRIALS_PER_M_GAMMA = 360_000


def read_base(path: Path):
    all_tot, all_succ = Counter(), Counter()
    fail_tot, fail_succ = Counter(), Counter()
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        if row["scope"] not in {"by_M", "failed_only_by_M"}:
            continue
        gamma, m = float(row["gamma"]), int(row["M"])
        if gamma not in GAMMAS or m not in BASE_MS:
            raise SystemExit(f"unexpected four-tier lock row: {row}")
        key = gamma, m
        totals, successes = (
            (all_tot, all_succ) if row["scope"] == "by_M" else (fail_tot, fail_succ)
        )
        totals[key], successes[key] = int(row["trials"]), int(row["success"])
    expected = {(gamma, m) for gamma in GAMMAS for m in BASE_MS}
    if set(all_tot) != expected or set(fail_tot) != expected:
        raise SystemExit("four-tier lock is incomplete")
    if set(all_tot.values()) != {TRIALS_PER_M_GAMMA}:
        raise SystemExit("unexpected four-tier trial counts")
    return all_tot, all_succ, fail_tot, fail_succ


def add_m64(path: Path, all_tot, all_succ, fail_tot, fail_succ) -> None:
    opener = gzip.open if path.suffix == ".gz" else open
    rows = 0
    with opener(path, "rt", newline="") as handle:
        for row in csv.DictReader(handle):
            rows += 1
            if row["error"] or int(row["M"]) != 64 or int(row["k"]) != 3:
                raise SystemExit(f"invalid M1=64 raw row {rows}")
            gamma = float(row["gamma"])
            if gamma not in GAMMAS:
                raise SystemExit(f"unexpected alpha at M1=64 raw row {rows}: {gamma}")
            key = gamma, 64
            all_tot[key] += 1
            all_succ[key] += row["ok1"] == "1" or row["ok2"] == "1"
            if row["ok1"] != "1":
                fail_tot[key] += 1
                fail_succ[key] += row["ok2"] == "1"
    if rows != 1_080_000:
        raise SystemExit(f"expected 1,080,000 M1=64 rows, found {rows}")
    if {all_tot[(gamma, 64)] for gamma in GAMMAS} != {TRIALS_PER_M_GAMMA}:
        raise SystemExit("M1=64 does not contain 360,000 rows per alpha")


def emit(writer, scope, gamma, m, trials, success):
    writer.writerow([scope, f"{gamma:g}", m, trials, success, f"{success / trials:.6f}"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--m64-raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    all_tot, all_succ, fail_tot, fail_succ = read_base(args.base)
    add_m64(args.m64_raw, all_tot, all_succ, fail_tot, fail_succ)
    five_ms = sorted(BASE_MS | {64})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["scope", "gamma", "M", "trials", "success", "success_rate"])
        for gamma in sorted(GAMMAS):
            for scope, ms in (("all_m_ge_256", sorted(BASE_MS)), ("all_five_tiers", five_ms)):
                trials = sum(all_tot[(gamma, m)] for m in ms)
                success = sum(all_succ[(gamma, m)] for m in ms)
                emit(writer, scope, gamma, "", trials, success)
        for gamma in sorted(GAMMAS):
            for m in five_ms:
                emit(writer, "by_M", gamma, m, all_tot[(gamma, m)], all_succ[(gamma, m)])
        for gamma in sorted(GAMMAS):
            for scope, ms in (("failed_only_all_m_ge_256", sorted(BASE_MS)),
                              ("failed_only_all_five_tiers", five_ms)):
                trials = sum(fail_tot[(gamma, m)] for m in ms)
                success = sum(fail_succ[(gamma, m)] for m in ms)
                emit(writer, scope, gamma, "", trials, success)
        for gamma in sorted(GAMMAS):
            for m in five_ms:
                emit(writer, "failed_only_by_M", gamma, m,
                     fail_tot[(gamma, m)], fail_succ[(gamma, m)])
    print(f"wrote five-tier E13 lock to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
