#!/usr/bin/env python3
"""Verify ready Artifact data against data/manifest.tsv."""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
from pathlib import Path


ARTIFACT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ARTIFACT_ROOT / "data" / "manifest.tsv"
DEFAULT_NFS_ROOT = Path("/path/to/nfs-root/not-a-waste-paper")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve_ready_path(
    row: dict[str, str], nfs_root: Path, scope: str
) -> Path | None:
    state = row["status"]
    if scope in {"git", "all"} and state.startswith("ready-git"):
        return ARTIFACT_ROOT / row["artifact_path"]
    if scope in {"nfs", "all"} and state.startswith("ready-nfs"):
        return nfs_root / row["storage_path"]
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument(
        "--nfs-root",
        type=Path,
        default=Path(os.environ.get("ARTIFACT_NFS_ROOT", DEFAULT_NFS_ROOT)),
    )
    parser.add_argument(
        "--scope",
        choices=("git", "nfs", "all"),
        default="git",
        help="verify Git data, NFS data, or both (default: git)",
    )
    args = parser.parse_args()

    failures = 0
    checked = 0
    with args.manifest.open(newline="", encoding="utf-8") as stream:
        rows = csv.DictReader(stream, delimiter="\t")
        for row in rows:
            path = resolve_ready_path(row, args.nfs_root, args.scope)
            if path is None:
                continue
            checked += 1
            if not path.is_file():
                print(f"MISSING  {row['artifact_id']}: {path}")
                failures += 1
                continue
            actual_bytes = path.stat().st_size
            actual_hash = sha256(path)
            if actual_bytes != int(row["bytes"]) or actual_hash != row["sha256"]:
                print(
                    f"MISMATCH {row['artifact_id']}: "
                    f"bytes={actual_bytes} sha256={actual_hash}"
                )
                failures += 1
                continue
            print(f"OK       {row['artifact_id']}: {path}")

    print(f"checked={checked} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
