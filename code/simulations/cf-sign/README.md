# Sign of c_F: exact enumeration and CPU Monte Carlo (2026-09-23)

Supports the claim in Section 3.3 of the paper that every measured `c_F`
is positive for the deployed k=3, and the corresponding corollary and
`c_F` scaling table in the extended version.

Run on an 8-core laptop (`xargs -P 8`).

## Model

d elements, each mapped to an independent uniform k-subset of [M]
(duplicates across elements allowed). F = idealized peeling leaves a
non-empty 2-core. `c_F = gamma^{-1} E[W_12 | F]`, `W_12 = H_12 - k^2/M`,
`gamma = k(1-k/M)`.

Per mapping, `sum_{x<y} H_xy = sum_cells C(deg, 2)`, so the pair-averaged
`W` costs O(M). Two estimators:

- direct: `mean_F(Wbar) / gamma`, standard error in column 10;
- success-based: `-(n_S/n_F) mean_S(Wbar) / gamma`, valid because
  `E Wbar = 0` exactly; column 9 is its z-score (z > 0 means c_F > 0).
  Lower variance when p_F is close to 1; the direct estimator is better
  when p_F is small.

## Files

| file | content |
|---|---|
| `cf_sign_exact.py` | exact enumeration over multisets of k-subsets with multinomial weights, integer sign test |
| `cf_exact.txt` | exact output, M=3..10, all (k,d) with at most 1.5e6 multisets |
| `cf_mc.c` | Monte Carlo (xoshiro-style RNG, Floyd subset sampling, XOR peeling) |
| `jobs.txt` → `k3_sweep.txt` | k=3, M in {8..64} all d up to M-2; M=256, 1024 transition to overload |
| `jobs2.txt` → `frontier.txt` | M in {10..32}, k in {3..30}, d up to M-k+1 |
| `jobs3.txt` → `large_k.txt` | k in {4,5,6}, M in {256,1024} |
| `jobs4.txt` → `scaling.txt` | k=3, d/M in {0.25,0.4,0.6}, M in {32..512}; trials sized for about 15,000 failures |
| `SHA256SUMS` | hashes |

Output columns: `M k d trials p_F n_S cF_direct cF_success z [seF_direct]`.
`k3_sweep.txt`, `frontier.txt`, `large_k.txt` were produced before the
10th column was added; the RNG stream is unchanged, so rerunning the current
binary reproduces columns 1 to 9.

## Commands

```sh
cc -O3 -march=native -o cf_mc cf_mc.c -lm
python3 cf_sign_exact.py 1500000 > cf_exact.txt          # ~77 s
cat jobs.txt  | xargs -P 8 -L1 ./cf_mc | sort -n -k1 -k3 > k3_sweep.txt          # ~9 min
cat jobs2.txt | xargs -P 8 -L1 ./cf_mc | sort -n -k1 -k2 -k3 > frontier.txt      # ~3.5 min
cat jobs3.txt | xargs -P 8 -L1 ./cf_mc | sort -n -k1 -k2 -k3 > large_k.txt       # ~3.5 min
cat jobs4.txt | xargs -P 8 -L1 ./cf_mc | sort -n -k3 > scaling.txt               # ~5.5 min
```

## Validation

MC against exact enumeration (20M trials each): (10,8,3) -0.02999 vs
-4/133 = -0.030075; (9,7,3) 0.01003 vs 0.01; (7,3,4) 0.14647 vs 0.14643;
(8,5,4) 0.00718 vs 0.00716. The d=3, k=M-2 closed form was additionally
checked in exact rational arithmetic for M=6..199.

## Findings

1. c_F < 0 occurs: exact (10,8,3) gives -4/133. For k>=4 negative values
   are common near and inside the transition.
2. k=3: every significant estimate is positive (M from 8 to 1024, including
   the last decodable load d=M-2).
3. At M=256 the sign within the transition depends on k: k=3 positive,
   k=4 positive in the transition and negative (about -2e-7) at p_F>0.98,
   k=5 and k=6 negative.
4. Below the k=3 threshold, `c_F * C(d,2) -> 1` as M grows and
   `p_F / (C(d,2)/C(M,3)) -> 1` (duplicate pairs dominate). Convergence is
   slow near the threshold: at d/M=0.6 the product rises to 2.25 at M=64
   before reaching 1.04 +- 0.08 at M=512.
5. d >= M-k+2 gives p_F = 1 exactly; no successes were observed there.
