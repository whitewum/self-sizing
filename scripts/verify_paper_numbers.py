#!/usr/bin/env python3
"""Verify the seven empirical tables and key quantitative paper claims.

All checks use the public Artifact CSVs and their frozen paper-facing values.
"""

from __future__ import annotations

import argparse
import csv
import math
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP
from pathlib import Path


ARTIFACT_ROOT = Path(__file__).resolve().parents[1]
DATA = ARTIFACT_ROOT / "data" / "paper"


class CheckFailure(RuntimeError):
    pass


def rows(name: str) -> list[dict[str, str]]:
    with (DATA / name).open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def t1_rows() -> list[dict[str, str]]:
    """Return the complete five-tier T1 grid from its two public summaries."""
    return rows("plain-t1-summary-1m.csv") + rows("plain-t1-m512-summary-1m.csv")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailure(message)


def close(actual: float, expected: float, tolerance: float = 5e-7) -> None:
    require(math.isclose(actual, expected, abs_tol=tolerance, rel_tol=0),
            f"expected {expected}, got {actual}")


def keyed(items: list[dict[str, str]], *fields: str) -> dict[tuple[str, ...], dict[str, str]]:
    return {tuple(row[field] for field in fields): row for row in items}


def percent3(rate: str) -> str:
    """Preserve the historical display rule based on the locked 6-digit rate."""
    return str((Decimal(rate) * 100).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP))


def percent3_counts(row: dict[str, str]) -> str:
    """Format a newly added row directly from its authoritative counts."""
    rate = Decimal(row["success"]) / Decimal(row["trials"])
    return str((rate * 100).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP))


def capacity_alpha2(q01: float) -> float:
    """Round beta/q01 upward so the displayed multiplier remains safe."""
    value = Decimal("1.56") / Decimal(str(q01))
    return float(value.quantize(Decimal("0.01"), rounding=ROUND_CEILING))


def check_t1a() -> None:
    source = t1_rows()
    selected = {
        int(row["M"]): row for row in source
        if row["kind"] == "grid" and row["k"] == "3" and row["neg_ratio"] == "0.5"
        and abs(int(row["d"]) / int(row["M"]) - 1.6) < 0.01
    }
    expected = {
        64: (102, "0.9996", "0.1774", "0.1773", "0.6309", "0.6326"),
        256: (410, "1.0000", "0.0885", "0.0885", "0.8062", "0.8056"),
        512: (819, "1.0000", "0.0625", "0.0625", "0.8601", "0.8602"),
        1024: (1638, "0.9999", "0.0442", "0.0442", "0.9001", "0.9000"),
        4096: (6554, "1.0000", "0.0221", "0.0221", "0.9493", "0.9493"),
    }
    require(set(selected) == set(expected), "tab:t1a representative rows missing")
    for m, (d, mean, rsd, theory, q01, qchi) in expected.items():
        row = selected[m]
        actual = (
            int(row["d"]), f'{float(row["mean_ratio"]):.4f}',
            f'{float(row["rsd_emp"]):.4f}', f'{float(row["rsd_theory"]):.4f}',
            f'{float(row["q01_emp"]):.4f}', f'{float(row["q01_chi2"]):.4f}',
        )
        require(actual == (d, mean, rsd, theory, q01, qchi), f"tab:t1a M={m}: {actual}")


def check_t1b() -> None:
    source = t1_rows()
    selected = {
        int(row["M"]): row for row in source
        if row["kind"] == "grid" and row["k"] == "3" and row["neg_ratio"] == "-1"
        and abs(int(row["d"]) / int(row["M"]) - 0.8) < 0.01
    }
    expected = {
        64: (51, "0.68", "0.9998", "0.6308", "0.6326"),
        256: (205, "0.54", "1.0001", "0.8050", "0.8056"),
        512: (410, "0.42", "1.0001", "0.8598", "0.8602"),
        1024: (819, "0.26", "1.0001", "0.8998", "0.9000"),
        4096: (3277, "0.03", "0.9999", "0.9490", "0.9493"),
    }
    require(set(selected) == set(expected), "tab:t1b representative rows missing")
    for m, (d, pf, mean, q01, qchi) in expected.items():
        row = selected[m]
        actual = (
            int(row["d"]), f'{float(row["fail_rate"]):.2f}',
            f'{float(row["failed_mean_ratio"]):.4f}',
            f'{float(row["failed_q01"]):.4f}', f'{float(row["q01_chi2"]):.4f}',
        )
        require(actual == (d, pf, mean, q01, qchi), f"tab:t1b M={m}: {actual}")


def check_capacity_tiers() -> None:
    source = rows("plain-t1-q01-guidance.csv")
    worst = {}
    for row in source:
        m = int(row["M"])
        worst[m] = min(worst.get(m, float("inf")), float(row["q01_emp"]))
    expected = {64: (0.6308, 2.48), 256: (0.8045, 1.94),
                512: (0.8598, 1.82), 1024: (0.8997, 1.74),
                4096: (0.9482, 1.65)}
    require(set(worst) == set(expected), f"tab:capacity-tiers tiers: {sorted(worst)}")
    for m, (q01, alpha) in expected.items():
        close(round(worst[m], 4), q01)
        close(capacity_alpha2(worst[m]), alpha)


def check_protocol_calibration() -> None:
    source = keyed(rows("plain-e13-retry-success.csv"), "scope", "gamma", "M")
    overall = {"1.6": "99.854", "1.8": "99.957", "2": "99.967"}
    by_m = {
        "64": ("86.589", "95.122", "98.168"),
        "256": ("99.336", "99.855", "99.896"),
        "512": ("99.901", "99.935", "99.948"),
        "1024": ("99.955", "99.969", "99.978"),
        "4096": ("99.988", "99.995", "99.992"),
    }
    for gamma, display in overall.items():
        row = source[("all_m_ge_256", gamma, "")]
        actual = percent3(row["success_rate"])
        require(actual == display, f"tab:protocol-calibration overall gamma={gamma}: {actual}")
    for m, displays in by_m.items():
        for gamma, display in zip(("1.6", "1.8", "2"), displays):
            row = source[("failed_only_by_M", gamma, m)]
            actual = percent3_counts(row) if m == "64" else percent3(row["success_rate"])
            require(actual == display, f"tab:protocol-calibration M={m}, gamma={gamma}: {actual}")


def check_profiles_e2e() -> None:
    timing = keyed(rows("paper_profiles_e2e.csv"), "network", "profile")
    evidence = keyed(rows("paper_profiles_protocol_evidence.csv"), "profile")
    expected_times = {
        ("22.5ms/100Mbps", "P1"): (636.6, 652.4),
        ("22.5ms/100Mbps", "P2"): (37.3, 38.9),
        ("22.5ms/100Mbps", "P3"): (155.8, 155.6),
        ("22.5ms/10Mbps", "P1"): (657.4, 1020.3),
        ("22.5ms/10Mbps", "P2"): (37.3, 46.8),
        ("22.5ms/10Mbps", "P3"): (149.5, 137.5),
        ("100ms/10Mbps", "P1"): (654.4, 1011.2),
        ("100ms/10Mbps", "P2"): (37.0, 46.6),
        ("100ms/10Mbps", "P3"): (151.7, 137.6),
    }
    for key, expected in expected_times.items():
        row = timing[key]
        actual = (
            min(float(row["iblt_w16_s"]), float(row["iblt_w32_s"])),
            min(float(row["merkle_w16_s"]), float(row["merkle_w32_s"])),
        )
        require(actual == expected, f"tab:profiles-e2e {key}: {actual}")
    expected_protocol = {
        "P1": (93130, 83664, 100694, "second_round"),
        "P2": (1392, 1248, 1464, "second_round"),
        "P3": (18, 16.7, 19.4, "first_round"),
    }
    for profile, expected in expected_protocol.items():
        row = evidence[(profile,)]
        actual = (int(row["d_ms"]), float(row["dhat_display_min"]),
                  float(row["dhat_display_max"]), row["decode_path"])
        require(actual == expected, f"tab:profiles-e2e protocol {profile}: {actual}")
        require((row["iblt_runs"], row["success_runs"], row["zero_residual_runs"],
                 row["recovered_count_match_runs"]) == ("12", "12", "12", "12"),
                f"profile correctness incomplete: {profile}")


def check_phase_breakdown() -> None:
    source = keyed(rows("paper_p1_phase_breakdown.csv"), "method", "network", "workers")
    iblt = source[("IBLT", "22.5ms/100Mbps", "32")]
    merkle = source[("Merkle", "22.5ms/100Mbps", "32")]
    iblt_values = (max(float(iblt["build_a_s"]), float(iblt["build_b_s"])),
                   max(float(iblt["rebucket_a_s"]), float(iblt["rebucket_b_s"])),
                   float(iblt["resolve_wall_s"]), float(iblt["recheck_http_wall_s"]),
                   float(iblt["e2e_s"]))
    merkle_values = (float(merkle["boundary_s"]), float(merkle["checksum_s"]),
                     float(merkle["drill_s"]), float(merkle["recheck_s"]),
                     float(merkle["e2e_s"]))
    require(iblt_values == (595.5, 23.0, 15.6, 10.1, 636.6), f"phase IBLT: {iblt_values}")
    require(merkle_values == (17.2, 593.5, 39.6, 2.0, 652.4), f"phase Merkle: {merkle_values}")


def check_g1g6() -> None:
    source = rows("paper_g1g6.csv")
    require(len(source) == 13, f"tab:g1g6 expected 13 scenario rows, got {len(source)}")
    require(all(row["correctness"] == "PASS" for row in source), "tab:g1g6 correctness failure")
    lookup = {(row["group"], row["differences"]): row for row in source}
    require(lookup[("G2", "937")]["merkle_wall_s"] == "121.0", "G2/937 Merkle drift")
    require(lookup[("G3", "39594")]["self_sizing_wall_s"] == "80.1", "G3 self-sizing drift")
    require(lookup[("G5", "2000")]["self_sizing_wall_s"] == "30.6", "G5 drift")
    require(lookup[("G6", "1163")]["self_sizing_wall_s"] == "34.5", "G6 drift")


def check_additional_claims() -> None:
    buckets = rows("production-size-buckets.csv")
    total_runs = sum(int(row["runs"]) for row in buckets)
    large = [row for row in buckets if int(row["min_rows_inclusive"]) >= 10_000_000]
    large_runs = sum(int(row["runs"]) for row in large)
    large_time = sum(float(row["table_time_pct"]) for row in large) / 100
    summary = {row["metric"]: float(row["value"]) for row in rows("paper_production_summary.csv")
               if row["metric"] not in {"observation_window", "deployable_runs"}}
    close(total_runs, 41603)
    close(round(large_runs / total_runs, 4), 0.0582)
    close(round(large_time, 3), 0.774)
    close(summary["runs_ge_10m_rows_share"], 0.0582)
    close(summary["runs_ge_10m_rows_duration_share"], 0.774)

    rank = rows("production-rank-buckets-anonymized.csv")
    nonempty = [sum(int(row[f"b{index:02d}"]) > 0 for index in range(100)) for row in rank]
    require((min(nonempty), max(nonempty)) == (3, 27), f"rank nonempty range: {nonempty}")

    extra = rows("e29-p1-extra-paper-results.csv")
    completed = [float(row["change_pct"]) for row in extra if row["change_pct"]]
    require(min(completed) == 2.1 and max(completed) == 1189.3,
            f"P1-extra change range drift: {min(completed)}..{max(completed)}")


def main() -> int:
    argparse.ArgumentParser().parse_args()

    checks = (
        ("tab:t1a", check_t1a),
        ("tab:t1b", check_t1b),
        ("tab:capacity-tiers", check_capacity_tiers),
        ("tab:protocol-calibration", check_protocol_calibration),
        ("tab:profiles-e2e", check_profiles_e2e),
        ("tab:phase-breakdown", check_phase_breakdown),
        ("tab:g1g6", check_g1g6),
        ("additional claims", check_additional_claims),
    )
    try:
        for name, check in checks:
            check()
            print(f"OK  {name}")
    except (CheckFailure, KeyError, ValueError) as error:
        print(f"FAIL  {error}")
        return 1
    print(f"checked_tables=7 additional_claim_groups=1 failures=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
