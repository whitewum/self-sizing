#!/usr/bin/env python3
"""Fail on obvious private-path, host, credential, or rank-data leakage."""

from __future__ import annotations

import csv
import ipaddress
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCAN_ROOTS = (
    ROOT / "code",
    ROOT / "data",
    ROOT / "config",
    ROOT / "scripts",
    ROOT / "figures",
)
TOP_LEVEL_FILES = ("README.md", "REPRODUCE.md", "DATA_AVAILABILITY.md")
SKIP_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".pdf", ".svg", ".zst", ".gz")
PRIVATE_PATHS = ("/public/home/", "/Users/")
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
CJK = re.compile(r"[㐀-鿿豈-﫿]")
ENV_SECRET = re.compile(r"^(?:export\s+)?[A-Z0-9_]*(?:PASSWORD|PASSWD|SECRET|TOKEN|PRIVATE_KEY)[A-Z0-9_]*=(.*)$")
BANNED_SOURCE_MARKERS = (
    "h100-oracle-4docker",
    "E29_SIDECAR",
    "CMX_ARIF_HISTORY",
    "ITEM_SUPP_COUNTRY",
    "SHIPSKU_P3",
    "mom_item_supp_country",
    "mom_shipsku_p3",
    "mom_cmx_arif_history",
)
# Collaborator identity and internal-module references that must not ship.
BANNED_IDENTIFIERS = (
    "jzcloud",
    "ninedata",
    "cm_redis",
    "com.jzcloud",
    "process.iblt",
    "ref-code/",
    "lagecy/",
)


def scan_text(path: Path) -> list[str]:
    failures: list[str] = []
    if path.suffix.lower() in SKIP_SUFFIXES:
        return failures
    if path.resolve() == Path(__file__).resolve():
        return failures
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return failures
    for line_number, line in enumerate(text.splitlines(), 1):
        if any(marker in line for marker in PRIVATE_PATHS):
            failures.append(f"{path}:{line_number}: private absolute path")
        if CJK.search(line):
            failures.append(f"{path}:{line_number}: CJK text (translate before release)")
        lowered = line.lower()
        for marker in BANNED_IDENTIFIERS:
            if marker in lowered:
                failures.append(f"{path}:{line_number}: internal identifier {marker}")
        for marker in BANNED_SOURCE_MARKERS:
            if marker in line:
                failures.append(f"{path}:{line_number}: internal source marker {marker}")
        for match in IPV4.finditer(line):
            address = ipaddress.ip_address(match.group())
            if not address.is_loopback and not address.is_unspecified:
                failures.append(f"{path}:{line_number}: non-loopback IPv4 address {address}")
        secret = ENV_SECRET.match(line.strip())
        if secret:
            value = secret.group(1).strip().strip("'\"")
            if value and "<" not in value and value not in {"${A_REDIS_PASSWORD:-}", "${B_REDIS_PASSWORD:-}"}:
                failures.append(f"{path}:{line_number}: non-placeholder secret assignment")
    return failures


def check_rank_derivative() -> list[str]:
    path = ROOT / "data" / "paper" / "production-rank-buckets-anonymized.csv"
    buckets = [f"b{i:02d}" for i in range(100)]
    expected_header = ["profile", "blocks", "dirty_blocks", *buckets]
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    failures: list[str] = []
    if list(rows[0]) != expected_header:
        failures.append(f"{path}: unexpected public fields")
    if [row["profile"] for row in rows] != ["P1", "P2", "P3", "T4", "T5"]:
        failures.append(f"{path}: unexpected profile order")
    for row in rows:
        if sum(int(row[bucket]) for bucket in buckets) != int(row["dirty_blocks"]):
            failures.append(f"{path}: bucket sum mismatch for {row['profile']}")
    return failures


def main() -> int:
    failures: list[str] = []
    checked = 0
    paths: list[Path] = [ROOT / name for name in TOP_LEVEL_FILES]
    for scan_root in SCAN_ROOTS:
        paths.extend(item for item in scan_root.rglob("*") if item.is_file())
    for path in sorted(set(paths)):
        if not path.is_file():
            continue
        failures.extend(scan_text(path))
        checked += 1
    failures.extend(check_rank_derivative())
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"checked_files={checked} failures={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
