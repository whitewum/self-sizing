#!/usr/bin/env python3
"""Differentially compare the Java and Go mapper-v2 implementations."""

from __future__ import annotations

import argparse
import random
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "code" / "cm-redis"
GO_ROOT = ROOT / "code" / "e29-sidecar" / "rateless-vs-self-sizing"


def run(command: list[str], *, cwd: Path, payload: bytes | None = None) -> bytes:
    completed = subprocess.run(
        command, cwd=cwd, input=payload, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )
    if completed.returncode:
        raise SystemExit(
            f"command failed ({completed.returncode}): {' '.join(command)}\n"
            + completed.stderr.decode(errors="replace")
        )
    return completed.stdout


def vectors(count: int, seed: int) -> bytes:
    rng = random.Random(seed)
    rows = [
        (0x20D, 6000, 0),  # known retry on stream B
        (0x206D7A4C58CD2C, 6000, 0),
        (0x206D7A4C58CD2C, 6000, 0x1234),
    ]
    sizes = (4, 5, 7, 16, 64, 256, 512, 1024, 6000, 65537, 1 << 20)
    for _ in range(count):
        rows.append((rng.getrandbits(56), rng.choice(sizes), rng.getrandbits(64)))
    return "".join(f"{fp} {m} {mapper_seed}\n" for fp, m, mapper_seed in rows).encode()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=int, default=50_000)
    parser.add_argument("--seed", type=lambda value: int(value, 0), default=0x49424C5432)
    args = parser.parse_args()
    if args.cases < 1:
        parser.error("--cases must be positive")

    payload = vectors(args.cases, args.seed)
    with tempfile.TemporaryDirectory(prefix="mapper-v2-java-") as temp:
        classes = Path(temp) / "classes"
        run(["bash", "scripts/build.sh", str(classes)], cwd=JAVA_ROOT)
        java = run(
            ["java", "-cp", str(classes),
             "io.github.selfsizing.iblt.core.MapperVectorMain"],
            cwd=JAVA_ROOT, payload=payload,
        )
    go = run(["go", "run", "./cmd/mapper-vectors"], cwd=GO_ROOT, payload=payload)
    if java != go:
        java_lines = java.splitlines()
        go_lines = go.splitlines()
        input_lines = payload.splitlines()
        for index, (jline, gline) in enumerate(zip(java_lines, go_lines)):
            if jline != gline:
                raise SystemExit(
                    f"mapper mismatch at case {index}: input={input_lines[index].decode()} "
                    f"java={jline.decode()} go={gline.decode()}"
                )
        raise SystemExit(
            f"mapper output length mismatch: java={len(java_lines)} go={len(go_lines)}"
        )
    for index, line in enumerate(java.splitlines()):
        positions = line.split()
        if len(positions) != 3 or len(set(positions)) != 3:
            raise SystemExit(f"non-distinct mapper output at case {index}: {line.decode()}")
    print(f"mapper_version=2 cases={args.cases + 3} java_go_mismatches=0 non_distinct=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
