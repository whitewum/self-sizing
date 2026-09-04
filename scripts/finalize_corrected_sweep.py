#!/usr/bin/env python3
"""Validate corrected replay outputs and write a content-addressed sweep manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def git(repo: Path, *args: str, binary: bool = False) -> str | bytes:
    completed = subprocess.run(
        ["git", *args], cwd=repo, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True,
    )
    return completed.stdout if binary else completed.stdout.decode().strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sweep", type=Path, required=True)
    parser.add_argument("--code-repo", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--go-bin", default="go")
    parser.add_argument("--expected-runs", type=int, default=12)
    parser.add_argument("--mapper-version", type=int, default=2)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    sweep = args.sweep.resolve()
    output = (args.out or sweep / "audit-manifest.json").resolve()

    profiles: dict[str, object] = {}
    for profile_dir in sorted(path for path in sweep.iterdir() if path.is_dir()):
        truth_path = profile_dir / "truth.json"
        if not truth_path.is_file():
            raise SystemExit(f"missing truth: {truth_path}")
        truth = json.loads(truth_path.read_text())
        audit = truth.get("audit", {})
        if audit.get("mapper_version") != args.mapper_version:
            raise SystemExit(f"bad truth mapper version: {truth_path}")
        if not audit.get("input_sha256_verified"):
            raise SystemExit(f"truth input SHA-256 was not verified: {truth_path}")

        result_paths = sorted(
            path for path in profile_dir.glob("*.json")
            if path.name != "truth.json"
        )
        if len(result_paths) != args.expected_runs:
            raise SystemExit(
                f"{profile_dir.name}: expected {args.expected_runs} results, "
                f"found {len(result_paths)}"
            )
        results = []
        for path in result_paths:
            result = json.loads(path.read_text())
            if result.get("mapper_version") != args.mapper_version:
                raise SystemExit(f"bad result mapper version: {path}")
            if not result.get("success") or not result.get("truth_checked") or not result.get("truth_match"):
                raise SystemExit(f"result did not pass exact truth: {path}")
            configured_truth = Path(result["config"].get("truth", "")).resolve()
            if configured_truth != truth_path.resolve():
                raise SystemExit(f"result used a different truth file: {path}")
            results.append({
                "path": str(path.relative_to(sweep)),
                "sha256": sha256(path),
                "d_hat": result.get("d_hat"),
                "final_capacity": result.get("final_capacity"),
                "plus": result.get("plus"),
                "minus": result.get("minus"),
            })
        estimates = [entry["d_hat"] for entry in results]
        capacities = [entry["final_capacity"] for entry in results]
        profiles[profile_dir.name] = {
            "truth": {
                "path": str(truth_path.relative_to(sweep)),
                "sha256": sha256(truth_path),
                "plus": len(truth["plus"]),
                "minus": len(truth["minus"]),
                "source_manifest_sha256": audit["source_manifest_sha256"],
                "target_manifest_sha256": audit["target_manifest_sha256"],
            },
            "runs": results,
            "summary": {
                "d_hat_min": min(estimates),
                "d_hat_max": max(estimates),
                "d_hat_mean": statistics.mean(estimates),
                "d_hat_sample_rsd": statistics.stdev(estimates) / statistics.mean(estimates),
                "final_capacity_min": min(capacities),
                "final_capacity_max": max(capacities),
                "success_runs": len(results),
                "truth_match_runs": len(results),
            },
        }

    diff = git(args.code_repo, "diff", "HEAD", "--binary", binary=True)
    build_info = subprocess.run(
        [args.go_bin, "version", "-m", str(args.harness.resolve())],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, text=True,
    ).stdout
    manifest = {
        "schema": "corrected-mapper-sweep-audit-v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "mapper_version": args.mapper_version,
        "code": {
            "repository": str(args.code_repo.resolve()),
            "git_commit": git(args.code_repo, "rev-parse", "HEAD"),
            "git_dirty": bool(git(args.code_repo, "status", "--porcelain")),
            "git_diff_sha256": hashlib.sha256(diff).hexdigest(),
            "harness_path": str(args.harness.resolve()),
            "harness_sha256": sha256(args.harness.resolve()),
            "harness_build_info": build_info.splitlines(),
        },
        "sweep": str(sweep),
        "profiles": profiles,
    }
    raw = json.dumps(manifest, indent=2, sort_keys=True).encode() + b"\n"
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_bytes(raw)
    temporary.replace(output)
    print(
        f"manifest={output} profiles={len(profiles)} "
        f"runs={sum(len(value['runs']) for value in profiles.values())} "
        "truth_failures=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
