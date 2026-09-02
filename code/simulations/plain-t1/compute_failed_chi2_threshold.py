#!/usr/bin/env python3
"""Evaluate the unconditional chi-square 1% cutoff on failed-only samples.

The formal inputs are the original 160M-trial raw plus the supplemental 40M
M=512 raw. Chunked input keeps memory use independent of the multi-gigabyte
files.
"""

import argparse

from pathlib import Path
from math import sqrt

import pandas as pd
from scipy.stats import chi2


_ap = argparse.ArgumentParser(description=__doc__)
_ap.add_argument("--raw", type=Path, default=Path("results/t1_raw.csv"),
                 help="original 160M-trial raw CSV")
_ap.add_argument("--m512-raw", type=Path, required=True,
                 help="supplemental 40M-trial M=512 raw CSV")
_args = _ap.parse_args()

MS = (64, 256, 512, 1024, 4096)
CONFIG = {
    m: (round(0.8 * m), chi2.ppf(0.01, m - 1) / (m - 1)) for m in MS
}
COUNTS = {m: [0, 0] for m in MS}
COLS = ["kind", "M", "k", "d", "neg_ratio", "dhat_over_d", "failed"]

for raw_path in (_args.raw, _args.m512_raw):
    for chunk in pd.read_csv(raw_path, usecols=COLS, chunksize=2_000_000):
        for m, (d, threshold) in CONFIG.items():
            failed = chunk.loc[
                (chunk["kind"] == "grid")
                & (chunk["M"] == m)
                & (chunk["k"] == 3)
                & (chunk["d"] == d)
                & (chunk["neg_ratio"] == -1)
                & (chunk["failed"] == 1),
                "dhat_over_d",
            ]
            COUNTS[m][0] += len(failed)
            COUNTS[m][1] += int((failed <= threshold).sum())

print(
    "M,d,chi2_q01,n_failed,n_below,conditional_rate,"
    "binomial_se,abs_error_from_0.01"
)
for m, (d, threshold) in CONFIG.items():
    n_failed, n_below = COUNTS[m]
    rate = n_below / n_failed
    standard_error = sqrt(rate * (1.0 - rate) / n_failed)
    print(
        f"{m},{d},{threshold:.9f},{n_failed},{n_below},"
        f"{rate:.8f},{standard_error:.8f},{abs(rate - 0.01):.8f}"
    )
