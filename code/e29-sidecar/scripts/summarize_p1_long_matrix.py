#!/usr/bin/env python3
"""Summarize long-matrix logs and flag two-repeat spread over 15%."""

from __future__ import annotations

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

CASE_RE = re.compile(r"^(?P<network>.+)-(?P<algorithm>iblt|merkle)-w(?P<workers>\d+)-r(?P<repeat>\d+)\.log$")


def parse_result(line: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for token in line.strip().split():
        if "=" in token:
            key, value = token.split("=", 1)
            out[key] = value
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} MATRIX_DIR", file=sys.stderr)
        return 2
    root = Path(sys.argv[1])
    rows: list[dict[str, str]] = []
    for log in sorted(root.glob("*.log")):
        match = CASE_RE.match(log.name)
        if not match:
            continue
        result_lines = [line for line in log.read_text(errors="replace").splitlines()
                        if line.startswith("E29_RESULT ")]
        if not result_lines:
            continue
        fields = parse_result(result_lines[-1])
        if "e2e_ms" not in fields:
            continue
        rows.append({**match.groupdict(), "e2e_ms": fields["e2e_ms"],
                     "status": fields.get("status", fields.get("success", ""))})

    rows.sort(key=lambda row: (row["network"], row["algorithm"], int(row["workers"]), int(row["repeat"])))
    csv_path = root / "summary.csv"
    with csv_path.open("w", newline="") as handle:
        fields = ["network", "algorithm", "workers", "repeat", "e2e_ms", "status"]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    groups: dict[tuple[str, str, str], list[int]] = defaultdict(list)
    for row in rows:
        groups[(row["network"], row["algorithm"], row["workers"])].append(int(row["e2e_ms"]))

    md_path = root / "summary.md"
    with md_path.open("w") as handle:
        profile_match = re.search(r"(p[123])[-_]long-matrix", str(root), re.IGNORECASE)
        title = f"{profile_match.group(1).upper()} long matrix summary" if profile_match else "Long matrix summary"
        handle.write(f"# {title}\n\n")
        handle.write("Acceptance threshold: `max/min - 1 <= 15%` over two repeats.\n\n")
        handle.write("| Network | Algorithm | W | Runs | Min ms | Max ms | Spread | Decision |\n")
        handle.write("|---|---|---:|---:|---:|---:|---:|---|\n")
        for key in sorted(groups):
            values = groups[key]
            spread = (max(values) / min(values) - 1.0) if values else float("nan")
            decision = "PASS" if len(values) == 2 and spread <= 0.15 else ("RERUN" if len(values) == 2 else "PENDING")
            handle.write(f"| {key[0]} | {key[1]} | {key[2]} | {len(values)} | "
                         f"{min(values) if values else ''} | {max(values) if values else ''} | "
                         f"{spread * 100:.1f}% | {decision} |\n")
    print(f"SUMMARY rows={len(rows)} csv={csv_path} markdown={md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
