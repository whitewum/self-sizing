#!/usr/bin/env python3
"""Build five-tier q01 guidance from the two 1M-trial summaries.

The base summary contains M in {64, 256, 1024, 4096}.  M=512 is supplied by
its separate supplemental run.  Both inputs must contain exactly 1,000,000
trials per selected configuration; mixing the historical 10k run into the
paper-facing table is rejected explicitly. Alpha uses the paper's finite-size
decoder multiplier including its engineering margin: beta=1.3*1.2=1.56.
"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_CEILING
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BASE = ROOT / "data" / "paper" / "plain-t1-summary-1m.csv"
DEFAULT_M512 = ROOT / "data" / "paper" / "plain-t1-m512-summary-1m.csv"
DEFAULT_OUTPUT = ROOT / "results" / "generated" / "plain-t1-q01-guidance.csv"
TARGET_MS = (64, 256, 512, 1024, 4096)
TARGET_LOADS = (0.8, 1.6, 8.0)
TARGET_SIGNS = (-1.0, 0.0, 0.1, 0.5, 0.9)
FIELDS = (
    "M", "k", "d", "load_d_over_M", "neg_ratio", "n_failed", "q01_emp",
    "alpha_beta_1p56",
)


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def selected_load(m: int, d: int) -> float | None:
    actual = d / m
    for load in TARGET_LOADS:
        if abs(actual - load) < 0.01:
            return actual
    return None


def recommended_alpha(q01: str) -> str:
    value = Decimal("1.56") / Decimal(q01)
    return str(value.quantize(Decimal("0.01"), rounding=ROUND_CEILING))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-summary", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--m512-summary", type=Path, default=DEFAULT_M512)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--expected-trials", type=int, default=1_000_000)
    args = parser.parse_args()

    base = read_rows(args.base_summary)
    supplemental = read_rows(args.m512_summary)
    if any(int(row["M"]) == 512 for row in base):
        raise SystemExit("base summary unexpectedly contains M=512")
    if not supplemental or any(int(row["M"]) != 512 for row in supplemental):
        raise SystemExit("supplemental summary must contain only M=512 rows")

    output_rows: list[dict[str, str | int]] = []
    seen: set[tuple[int, int, float]] = set()
    for row in (*base, *supplemental):
        m = int(row["M"])
        d = int(row["d"])
        sign = float(row["neg_ratio"])
        if m not in TARGET_MS or row["kind"] != "grid" or int(row["k"]) != 3:
            continue
        load = selected_load(m, d)
        if load is None or sign not in TARGET_SIGNS:
            continue
        trials = int(row["trials"])
        if trials != args.expected_trials:
            raise SystemExit(
                f"{m=}, {d=}, sign={sign:g}: expected {args.expected_trials} "
                f"trials, found {trials}"
            )
        key = (m, d, sign)
        if key in seen:
            raise SystemExit(f"duplicate selected configuration: {key}")
        seen.add(key)
        q01 = float(row["failed_q01"])
        output_rows.append({
            "M": m,
            "k": 3,
            "d": d,
            "load_d_over_M": f"{load:.6f}",
            "neg_ratio": f"{sign:g}",
            "n_failed": int(row["failed_n"]),
            "q01_emp": f"{q01:.6f}",
            "alpha_beta_1p56": f"{1.56 / q01:.6f}",
        })

    output_rows.sort(key=lambda row: (int(row["M"]), int(row["d"]), float(row["neg_ratio"])))
    expected_rows = len(TARGET_MS) * len(TARGET_LOADS) * len(TARGET_SIGNS)
    if len(output_rows) != expected_rows:
        counts = {
            m: sum(int(row["M"]) == m for row in output_rows)
            for m in TARGET_MS
        }
        raise SystemExit(f"expected {expected_rows} selected rows, found {len(output_rows)}: {counts}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(output_rows)

    for m in TARGET_MS:
        rows_m = [row for row in output_rows if int(row["M"]) == m]
        worst = min(rows_m, key=lambda row: float(row["q01_emp"]))
        print(
            f"M={m}: q01_min={worst['q01_emp']} "
            f"alpha_exact={worst['alpha_beta_1p56']} "
            f"alpha_recommended={recommended_alpha(str(worst['q01_emp']))} "
            f"n_failed={worst['n_failed']}"
        )
    print(f"wrote {len(output_rows)} rows to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
