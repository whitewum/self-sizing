package io.github.selfsizing.iblt.relational;

import java.util.List;
import java.math.BigInteger;

/**
 * Database-free gate for {@code --mysql-fence sample}: checks base-36 probe
 * generation, the shape of the LATERAL / UNION ALL probe-snapshot SQL, and that
 * the shard predicates built from the snapshotted fence values satisfy the
 * disjoint-cover invariant ("shard 0 has an open lower bound, the last shard an
 * open upper bound, adjacent shards share their boundary value"), reusing the
 * same {@link CompositeIbltSql#stringRangePredicate} as the existing keyrange mode.
 */
public final class MysqlSampleFenceSelfTest {
    public static void main(String[] args) {
        // 1) Probe generation is distribution-agnostic: derived only from MIN/MAX's shared literal
        // prefix, fixed body width, and base-36 charset -- never from the row distribution itself.
        List<String> probes = CompositeIbltSql.base36FenceProbes("IT000000", "IT00000F", 8);
        if (probes.size() != 8
                || !"IT000000".equals(probes.get(0))
                || !"IT00000F".equals(probes.get(probes.size() - 1))) {
            throw new AssertionError("base36 probe endpoints wrong: " + probes);
        }
        for (int i = 1; i < probes.size(); i++) {
            if (probes.get(i).compareTo(probes.get(i - 1)) < 0) {
                throw new AssertionError("base36 probes not monotonic: " + probes);
            }
        }

        // Realistic jump36 domain: prefix "IT", 6-char base-36 body, ~2000 probes (the production
        // default). Every probe must keep the observed prefix/width and stay within [min, max].
        List<String> wideProbes = CompositeIbltSql.base36FenceProbes("IT000000", "ITZZZZZZ", 2000);
        if (wideProbes.size() != 2000) {
            throw new AssertionError("expected 2000 probes, got " + wideProbes.size());
        }
        for (String probe : wideProbes) {
            if (probe.length() != 8 || !probe.startsWith("IT")) {
                throw new AssertionError("probe outside jump36 shape: " + probe);
            }
        }
        if (!"IT000000".equals(wideProbes.get(0)) || !"ITZZZZZZ".equals(wideProbes.get(1999))) {
            throw new AssertionError("wide probe endpoints wrong: " + wideProbes.get(0)
                    + " / " + wideProbes.get(1999));
        }

        // 2) Production snap probes. The correlated JOIN LATERAL form (mysqlFenceSnapLateralSql) is
        // @Deprecated -- its `>= s.probe` degrades to a full index scan per probe (EXPLAIN-verified),
        // so it is intentionally NOT exercised here. Production uses the constant UNION-ALL form below.
        List<String> smallProbes = List.of("IT000000", "IT000008", "IT00000F");

        // 3) Constant-literal batched UNION ALL of scalar subqueries: each probe is an index range dive.
        List<String> unionSql = CompositeIbltSql.mysqlFenceSnapUnionAllSql("artifact_replay.profile_p2", "item", smallProbes, 2);
        if (unionSql.size() != 2
                || !unionSql.get(0).contains("UNION ALL")
                || unionSql.get(1).contains("UNION ALL")
                || !unionSql.get(0).contains("WHERE `item` >= 'IT000000' ORDER BY `item` LIMIT 1")
                || !unionSql.get(0).contains("WHERE `item` >= 'IT000008' ORDER BY `item` LIMIT 1")
                || !unionSql.get(1).contains("WHERE `item` >= 'IT00000F' ORDER BY `item` LIMIT 1")) {
            throw new AssertionError("union-all fallback sql shape wrong: " + unionSql);
        }

        // 4) Disjoint-coverage invariant of the shard predicates built from (simulated) snapped
        // fence keys, reusing the existing keyrange stringRangePredicate contract: shard 0 has no
        // lower bound, the last shard has no upper bound, and adjacent shards share the boundary
        // value (one side "<=", the next side "> " on the very same literal).
        List<String> fences = List.of("IT000010", "IT000020", "IT000030");
        int shards = fences.size() + 1;
        String[] predicates = new String[shards];
        for (int i = 0; i < shards; i++) {
            predicates[i] = CompositeIbltSql.stringRangePredicate("mysql", "item",
                    i == 0 ? null : fences.get(i - 1),
                    i == shards - 1 ? null : fences.get(i));
        }
        if (!"`item` <= 'IT000010'".equals(predicates[0])
                || !"`item` > 'IT000010' AND `item` <= 'IT000020'".equals(predicates[1])
                || !"`item` > 'IT000020' AND `item` <= 'IT000030'".equals(predicates[2])
                || !"`item` > 'IT000030'".equals(predicates[3])) {
            throw new AssertionError("fence predicate disjoint-coverage invariant broken: "
                    + String.join(" | ", predicates));
        }

        // 5) Non-base-36 or ragged bodies must fail fast rather than silently mis-shard.
        boolean rejectedRaggedBody = false;
        try {
            CompositeIbltSql.base36FenceProbes("IT00", "IT0000", 4);
        } catch (IllegalArgumentException expected) {
            rejectedRaggedBody = true;
        }
        if (!rejectedRaggedBody) throw new AssertionError("ragged-width key body accepted");

        // 6) Numeric probes support sparse, variable-width integer keys (P1 TRAN_ID shape).
        List<BigInteger> numericProbes = List.of(BigInteger.valueOf(8), BigInteger.valueOf(9754745221L));
        List<String> numericSql = CompositeIbltSql.mysqlNumericFenceSnapUnionAllSql(
                "artifact_replay.profile_p1_next", "tran_id", numericProbes, 2);
        if (numericSql.size() != 1
                || !numericSql.get(0).contains("WHERE `tran_id` >= 8 ORDER BY `tran_id` LIMIT 1")
                || !numericSql.get(0).contains("WHERE `tran_id` >= 9754745221 ORDER BY `tran_id` LIMIT 1")) {
            throw new AssertionError("numeric probe SQL shape wrong: " + numericSql);
        }
        String numericRange = CompositeIbltSql.numericRangePredicate(
                "mysql", "tran_id", "8", "9754745221");
        if (!"`tran_id` > 8 AND `tran_id` <= 9754745221".equals(numericRange)) {
            throw new AssertionError("numeric range predicate wrong: " + numericRange);
        }

        System.out.println("PASS mysql-fence sample: base36 probes + UNION-ALL snap SQL "
                + "+ numeric probes + disjoint-coverage fence predicates");
        System.out.println(String.join("\n", unionSql));
    }
}
