#!/usr/bin/env python3
"""Regenerate fig_crossover_heatmap.png from head_to_head_quick.csv.

Reproduces the crossover heatmap of ours/Strata mean-byte ratio over the
first_m x d grid. Title uses "ours" (the paper avoids the in-band/out-of-band
wording). Data source: results/head_to_head_quick.csv.
"""
import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

HERE = Path(__file__).resolve().parent

_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--input", type=Path, default=HERE / "results" / "head_to_head_quick.csv",
                 help="head-to-head sweep CSV (produced by the Go baseline; not shipped)")
_ap.add_argument("--outdir", type=Path, default=HERE / "results" / "figs")
_args, _ = _ap.parse_known_args()
CSV = _args.input
OUT = _args.outdir

rows = list(csv.DictReader(CSV.open()))
first_ms = sorted({int(r["first_m"]) for r in rows})
ds = sorted({int(r["d"]) for r in rows})
ratio = {(int(r["first_m"]), int(r["d"])): float(r["ours_over_strata"]) for r in rows}

grid = np.array([[ratio[(m, d)] for d in ds] for m in first_ms])

fig, ax = plt.subplots(figsize=(14.25, 7.2))
im = ax.imshow(grid, cmap="RdBu_r", vmin=grid.min(), vmax=1.9, aspect="auto")

ax.set_xticks(range(len(ds)))
ax.set_xticklabels(ds, rotation=45, ha="right")
ax.set_yticks(range(len(first_ms)))
ax.set_yticklabels(first_ms)
ax.set_xlabel("Symmetric difference d")
ax.set_ylabel("first_m (initial IBLT size)")
ax.set_title("Crossover: ours / Strata byte ratio (< 1 favors ours, > 1 favors Strata)")

for i in range(len(first_ms)):
    for j in range(len(ds)):
        v = grid[i, j]
        ax.text(j, i, f"{v:.2f}", ha="center", va="center",
                color="white" if v < 0.55 else "black", fontsize=9)

cbar = fig.colorbar(im, ax=ax)
cbar.set_label("ours_mean_bytes / strata_mean_bytes")

fig.tight_layout()
for ext in ("png", "svg"):
    fig.savefig(OUT / f"fig_crossover_heatmap.{ext}", dpi=200)
print("wrote", OUT / "fig_crossover_heatmap.png")
