"""Exact sign of c_F = gamma^{-1} E[W_xy | F] for the Plain IBLT under an
idealized peeling decoder, by exhaustive enumeration.

Each of d elements maps to a uniform k-subset of [M] (independently).
W_xy = |S_x & S_y| - k^2/M.  F = peeling leaves a non-empty 2-core.

Elements are exchangeable, so we enumerate multisets of k-subsets with
multinomial weights and use exact integer arithmetic:
    sign(c_F) = sign( M * sum_F w * pairO  -  k^2 * C(d,2) * sum_F w )
where pairO is the sum of overlaps over all unordered element pairs.
"""
import itertools, math, sys
from collections import Counter


def peel_fails(edges, M):
    deg = [0] * M
    for e in edges:
        for c in e:
            deg[c] += 1
    alive = [True] * len(edges)
    inc = [[] for _ in range(M)]
    for i, e in enumerate(edges):
        for c in e:
            inc[c].append(i)
    stack = [c for c in range(M) if deg[c] == 1]
    left = len(edges)
    while stack:
        c = stack.pop()
        if deg[c] != 1:
            continue
        i = next(j for j in inc[c] if alive[j])
        alive[i] = False
        left -= 1
        for c2 in edges[i]:
            deg[c2] -= 1
            if deg[c2] == 1:
                stack.append(c2)
    return left > 0


def exact(M, k, d):
    subsets = list(itertools.combinations(range(M), k))
    sets = [frozenset(s) for s in subsets]
    n = len(subsets)
    dfact = math.factorial(d)
    wF = 0          # total weight of failing sequences
    oF = 0          # weighted pair-overlap sum over failing sequences
    wAll = n ** d
    for combo in itertools.combinations_with_replacement(range(n), d):
        cnt = Counter(combo)
        w = dfact
        for m in cnt.values():
            w //= math.factorial(m)
        edges = [subsets[i] for i in combo]
        if not peel_fails(edges, M):
            continue
        po = 0
        for a in range(d):
            for b in range(a + 1, d):
                po += len(sets[combo[a]] & sets[combo[b]])
        wF += w
        oF += w * po
    pairs = d * (d - 1) // 2
    if wF == 0:
        return None
    num = M * oF - k * k * pairs * wF          # sign of c_F
    gamma = k * (M - k) / M
    cF = (oF / (wF * pairs) - k * k / M) / gamma
    return wF / wAll, num, cF


if __name__ == "__main__":
    LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 3_000_000
    print(f"{'M':>3} {'k':>2} {'d':>2} {'p_F':>12} {'c_F':>14}  sign")
    for M in range(3, 11):
        for k in range(1, M):
            n = math.comb(M, k)
            for d in range(2, M + 2):
                if math.comb(n + d - 1, d) > LIMIT:
                    break
                r = exact(M, k, d)
                if r is None:
                    continue
                pF, num, cF = r
                s = "+" if num > 0 else ("0" if num == 0 else "NEG")
                print(f"{M:>3} {k:>2} {d:>2} {pF:>12.8f} {cF:>14.6e}  {s}")
        sys.stdout.flush()
