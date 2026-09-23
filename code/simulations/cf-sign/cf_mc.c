/* Monte Carlo estimate of c_F = gamma^{-1} E[W_xy | F] for the Plain IBLT.
 *
 * Per configuration, the pair-averaged overlap is
 *   Obar = sum_c C(deg_c, 2) / C(d, 2),   Wbar = Obar - k^2/M,
 * and E[W_xy | F] = E[Wbar | F] by exchangeability.  Because E[Wbar] = 0
 * exactly, c_F = -(1-p_F)/p_F * E[Wbar | S] / gamma; we report both the
 * direct estimate and this success-based one, plus a z-score for
 * sign(c_F) = -sign(E[Wbar | S]).
 *
 * usage: cf_mc M k d trials seed
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <string.h>

static uint64_t s[2];
static inline uint64_t rotl(uint64_t x, int r) { return (x << r) | (x >> (64 - r)); }
static inline uint64_t next(void) {
    uint64_t s0 = s[0], s1 = s[1], r = s0 + s1;
    s1 ^= s0; s[0] = rotl(s0, 55) ^ s1 ^ (s1 << 14); s[1] = rotl(s1, 36);
    return r;
}
static inline uint32_t rnd(uint32_t n) { return (uint32_t)(((next() >> 32) * (uint64_t)n) >> 32); }

int main(int argc, char **argv) {
    if (argc < 6) { fprintf(stderr, "usage: M k d trials seed\n"); return 1; }
    int M = atoi(argv[1]), k = atoi(argv[2]), d = atoi(argv[3]);
    long long T = atoll(argv[4]);
    s[0] = 0x9E3779B97F4A7C15ULL ^ strtoull(argv[5], 0, 10); s[1] = 0xD1B54A32D192ED03ULL;
    for (int i = 0; i < 20; i++) next();

    int *E = malloc(sizeof(int) * d * k);
    int *deg = malloc(sizeof(int) * M), *xr = malloc(sizeof(int) * M);
    int *alive = malloc(sizeof(int) * d), *stk = malloc(sizeof(int) * (M + d * k));
    char *mark = malloc(M);
    double pairs = d * (d - 1) / 2.0, mu = (double)k * k / M, gamma = k * (1.0 - (double)k / M);

    long long nF = 0, nS = 0;
    double sF = 0, sF2 = 0, sS = 0, sS2 = 0;
    for (long long t = 0; t < T; t++) {
        memset(deg, 0, sizeof(int) * M);
        memset(xr, 0, sizeof(int) * M);
        for (int x = 0; x < d; x++) {           /* uniform k-subset (Floyd) */
            memset(mark, 0, M);
            int *e = E + x * k, n = 0;
            for (int j = M - k; j < M; j++) {
                int r = rnd(j + 1);
                if (mark[r]) r = j;
                mark[r] = 1; e[n++] = r;
            }
            for (int j = 0; j < k; j++) { deg[e[j]]++; xr[e[j]] ^= x; }
            alive[x] = 1;
        }
        double po = 0;
        for (int c = 0; c < M; c++) po += deg[c] * (deg[c] - 1) / 2.0;
        double w = po / pairs - mu;

        int top = 0, left = d;                  /* peel; xr[c] = id of lone element */
        for (int c = 0; c < M; c++) if (deg[c] == 1) stk[top++] = c;
        while (top) {
            int c = stk[--top];
            if (deg[c] != 1) continue;
            int x = xr[c];
            alive[x] = 0; left--;
            int *e = E + x * k;
            for (int j = 0; j < k; j++) {
                deg[e[j]]--; xr[e[j]] ^= x;
                if (deg[e[j]] == 1) stk[top++] = e[j];
            }
        }
        if (left) { nF++; sF += w; sF2 += w * w; } else { nS++; sS += w; sS2 += w * w; }
    }
    double pF = (double)nF / T;
    double cF_direct = nF ? sF / nF / gamma : NAN;
    double mS = nS ? sS / nS : NAN;
    double seS = nS > 1 ? sqrt((sS2 / nS - mS * mS) / (nS - 1)) : NAN;
    double cF_succ = nF && nS ? -(double)nS / nF * mS / gamma : NAN;
    double z = nS > 1 ? -mS / seS : NAN;        /* z > 0  => c_F > 0 */
    double mF = nF ? sF / nF : NAN;
    double seF = nF > 1 ? sqrt((sF2 / nF - mF * mF) / (nF - 1)) / gamma : NAN;
    printf("%d %d %d %lld %.6g %lld %.6e %.6e %.3f %.3e\n",
           M, k, d, T, pF, nS, cF_direct, cF_succ, z, seF);
    return 0;
}
