#!/usr/bin/env python3
"""Generate the paper's dirty-block rank heatmap from the public CSV."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import Normalize


plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "font.size": 8.5,
    "axes.titlesize": 9,
    "axes.labelsize": 8.5,
    "xtick.labelsize": 7.5,
    "ytick.labelsize": 7.5,
    "legend.fontsize": 7.5,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.linewidth": 0.8,
    "savefig.facecolor": "white",
})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    with args.input.open(newline="", encoding="utf-8") as handle:
        source_rows = list(csv.DictReader(handle))
    display_order = ["P1", "P2", "P3", "T4", "T5"]
    by_profile = {row["profile"]: row for row in source_rows}
    rows = [by_profile[profile] for profile in display_order]
    buckets = [f"b{index:02d}" for index in range(100)]
    rates = np.array([
        [float(row[bucket]) / float(row["blocks"]) for bucket in buckets]
        for row in rows
    ])

    fig, axis = plt.subplots(figsize=(5.05, 1.8))
    image = axis.imshow(
        rates,
        aspect="auto",
        interpolation="nearest",
        cmap="YlGnBu",
        norm=Normalize(vmin=0, vmax=float(rates.max())),
    )
    axis.set_yticks(np.arange(len(display_order)), display_order)
    axis.set_xticks([0, 19, 39, 59, 79, 99], ["0", "20", "40", "60", "80", "100"])
    axis.grid(False)
    axis.tick_params(length=2.5, width=0.6, labelsize=7.5)
    colorbar = fig.colorbar(image, ax=axis, fraction=0.046, pad=0.04)
    colorbar.set_label("dirty share", fontsize=7)
    colorbar.ax.tick_params(labelsize=6.5)
    fig.subplots_adjust(left=0.07, right=0.86, top=0.94, bottom=0.22)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.suffix.lower() == ".png":
        fig.savefig(args.output, dpi=220, bbox_inches="tight", pad_inches=0.02)
    else:
        fig.savefig(args.output, bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
