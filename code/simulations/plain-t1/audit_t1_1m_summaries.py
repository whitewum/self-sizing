#!/usr/bin/env python3
"""Audit the two public T1 summaries as one five-tier 1M-trial grid."""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path


SCRIPT = Path(__file__).resolve()
ROOT = SCRIPT.parents[3] if len(SCRIPT.parents) > 3 else Path.cwd()
MS = (64, 256, 512, 1024, 4096)
BASE_MS = (64, 256, 1024, 4096)
KS = (3, 4)
LOADS = (0.4, 0.8, 1.6, 8.0)
SIGNS = (-1.0, 0.0, 0.1, 0.5, 0.9)
KEY = ("kind", "M", "k", "d", "neg_ratio")


def read(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def expected(ms):
    return {
        ("grid", m, k, round(load * m), sign)
        for m in ms for k in KS for load in LOADS for sign in SIGNS
    }


def key(row):
    return row["kind"], int(row["M"]), int(row["k"]), int(row["d"]), float(row["neg_ratio"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path,
                        default=ROOT / "data" / "paper" / "plain-t1-summary-1m.csv")
    parser.add_argument("--m512", type=Path,
                        default=ROOT / "data" / "paper" / "plain-t1-m512-summary-1m.csv")
    parser.add_argument("--guidance", type=Path,
                        default=ROOT / "data" / "paper" / "plain-t1-q01-guidance.csv")
    args = parser.parse_args()

    base, m512 = read(args.base), read(args.m512)
    for name, rows, ms in (("base", base, BASE_MS), ("M512", m512, (512,))):
        keys = [key(row) for row in rows]
        if set(keys) != expected(ms) or len(keys) != len(set(keys)):
            raise SystemExit(f"{name}: incomplete or duplicate configuration grid")
        if any(int(row["trials"]) != 1_000_000 for row in rows):
            raise SystemExit(f"{name}: historical non-1M input detected")
        for row in rows:
            quantiles = [float(row[name]) for name in
                         ("q01_emp", "q05_emp", "q50_emp", "q95_emp")]
            if not all(math.isfinite(x) for x in quantiles) or quantiles != sorted(quantiles):
                raise SystemExit(f"{name}: invalid quantiles at {key(row)}")

    combined = base + m512
    if len(combined) != 200 or {int(row["M"]) for row in combined} != set(MS):
        raise SystemExit("combined summary is not the expected 200-cell five-tier grid")
    guidance = read(args.guidance)
    per_m = {m: [row for row in guidance if int(row["M"]) == m] for m in MS}
    if any(len(rows) != 15 for rows in per_m.values()):
        raise SystemExit("q01 guidance is not the expected 75-row five-tier selection")
    if "alpha_beta_1p56" not in guidance[0]:
        raise SystemExit("q01 guidance does not use beta=1.56")
    expected_worst = {64: 0.6308, 256: 0.8045, 512: 0.8598,
                      1024: 0.8997, 4096: 0.9482}
    actual_worst = {m: min(float(row["q01_emp"]) for row in rows)
                    for m, rows in per_m.items()}
    if actual_worst != expected_worst:
        raise SystemExit(f"unexpected worst q01 values: {actual_worst}")
    print("PASS: five-tier T1 summary audit")
    print(f"configs={len(combined)}; per_M={dict.fromkeys(MS, 40)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
