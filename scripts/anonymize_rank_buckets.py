#!/usr/bin/env python3
"""Create the public, de-identified input for the production rank figure."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


PROFILE_MAP = {"T1": "P1", "T7": "P2", "T6": "P3", "T4": "T4", "T5": "T5"}
OUTPUT_ORDER = tuple(PROFILE_MAP.values())
BUCKETS = tuple(f"b{i:02d}" for i in range(100))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    with args.source.open(newline="", encoding="utf-8") as handle:
        source_rows = {row["tag"]: row for row in csv.DictReader(handle)}

    missing = set(PROFILE_MAP) - set(source_rows)
    if missing:
        raise SystemExit(f"missing required source tags: {sorted(missing)}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    fields = ("profile", "blocks", "dirty_blocks", *BUCKETS)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for source_tag, public_profile in PROFILE_MAP.items():
            row = source_rows[source_tag]
            public_row = {
                "profile": public_profile,
                "blocks": int(row["blocks"]),
                "dirty_blocks": int(row["dirty_blocks"]),
            }
            public_row.update({bucket: int(row[bucket]) for bucket in BUCKETS})
            if sum(public_row[bucket] for bucket in BUCKETS) != public_row["dirty_blocks"]:
                raise SystemExit(f"bucket sum mismatch for {public_profile}")
            writer.writerow(public_row)

    print(f"wrote {len(OUTPUT_ORDER)} anonymized profiles to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
