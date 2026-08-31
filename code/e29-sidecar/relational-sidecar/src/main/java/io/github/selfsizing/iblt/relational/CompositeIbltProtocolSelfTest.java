package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.IbltDecoder;
import io.github.selfsizing.iblt.core.IbltDecodeResult;
import io.github.selfsizing.iblt.core.IbltHash;
import io.github.selfsizing.iblt.core.IbltSketch;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composite-key-only protocol gate: force M1 failure, then verify round-2 joint peeling
 * recovers the exact encoded tuple keys.
 */
public final class CompositeIbltProtocolSelfTest {
    private static final long MASK_56 = 0x00FFFFFFFFFFFFFFL;
    private static final PkType[] PK_TYPES = {PkType.STRING, PkType.INT64, PkType.STRING};

    private CompositeIbltProtocolSelfTest() { }

    public static void main(String[] args) {
        int common = 20_000;
        int changed = 900;
        IbltSketch source1 = new IbltSketch(IbltSizing.FIRST_ROUND_BUCKETS, 0x9E3779B9L);
        IbltSketch target1 = new IbltSketch(IbltSizing.FIRST_ROUND_BUCKETS, 0x9E3779B9L);
        Map<Long, String> wireById = new HashMap<>();
        Set<String> expectedPlus = new HashSet<>();
        Set<String> expectedMinus = new HashSet<>();

        for (long i = 1; i <= common; i++) {
            addSame(source1, target1, i, i, wireById);
        }
        for (long i = 1_000_001; i <= 1_000_000L + changed; i++) {
            long id = addTupleId(i, wireById);
            String wire = wireById.get(id);
            expectedPlus.add(wire);
            expectedMinus.add(wire);
            source1.insert(fp(i, 1), id);
            target1.insert(fp(i, 2), id);
        }
        addPresenceDifference(source1, target1, 2_000_001L, true, wireById, expectedPlus);
        addPresenceDifference(source1, target1, 2_000_002L, false, wireById, expectedMinus);

        IbltDecodeResult first = IbltDecoder.decode(source1, target1);
        if (first.isSuccess()) {
            throw new AssertionError("M1=512 unexpectedly decoded the forced composite-key workload");
        }
        double dHat = first.getdHat();
        int m2 = IbltSizing.secondRoundBuckets(dHat, 1 << 20);
        IbltSketch source2 = new IbltSketch(m2, 0xDEADBEEFCAFEL);
        IbltSketch target2 = new IbltSketch(m2, 0xDEADBEEFCAFEL);
        for (long i = 1; i <= common; i++) {
            addSame(source2, target2, i, i, wireById);
        }
        for (long i = 1_000_001; i <= 1_000_000L + changed; i++) {
            long id = addTupleId(i, wireById);
            source2.insert(fp(i, 1), id);
            target2.insert(fp(i, 2), id);
        }
        addPresenceDifference(source2, target2, 2_000_001L, true, wireById, null);
        addPresenceDifference(source2, target2, 2_000_002L, false, wireById, null);
        IbltDecodeResult second = IbltDecoder.decodeJoint(
                List.of(source1, source2), List.of(target1, target2));
        if (!second.isSuccess() || second.getResidualBuckets() != 0) {
            throw new AssertionError("composite-key round-2 decode failed: residual="
                    + second.getResidualBuckets() + " M2=" + m2);
        }
        Set<String> plus = wires(second.getPlusIds(), wireById);
        Set<String> minus = wires(second.getMinusIds(), wireById);
        if (!expectedPlus.equals(plus) || !expectedMinus.equals(minus)) {
            throw new AssertionError("decoded tuple keys differ from truth: expectedPlus=" + expectedPlus.size()
                    + " expectedMinus=" + expectedMinus.size()
                    + " plus=" + plus.size() + " minus=" + minus.size());
        }
        System.out.println("PASS composite IBLT round-2 joint peel");
        System.out.println("M1=" + IbltSizing.FIRST_ROUND_BUCKETS + " dHat=" + dHat
                + " M2=" + m2 + " powerOfTwo=" + ((m2 & (m2 - 1)) == 0)
                + " changedTuples=" + changed + " presencePlus=1 presenceMinus=1");
    }

    private static void addSame(IbltSketch source, IbltSketch target, long i, long fpSeed,
                                Map<Long, String> wireById) {
        long id = addTupleId(i, wireById);
        long fp = fp(fpSeed, 0);
        source.insert(fp, id);
        target.insert(fp, id);
    }

    private static long addTupleId(long i, Map<Long, String> wireById) {
        List<String> values = List.of("SKU-" + i, Long.toString(i), "CN");
        String wire = CompositePkCodec.encode(values);
        Object[] objects = {values.get(0), i, values.get(2)};
        long id = IbltHash.cheap8(PkTupleCanonicalizer.encode(PK_TYPES, objects, true));
        String previous = wireById.put(id, wire);
        if (previous != null && !previous.equals(wire)) {
            throw new AssertionError("unexpected composite id collision: " + previous + " / " + wire);
        }
        return id;
    }

    private static void addPresenceDifference(IbltSketch source, IbltSketch target, long i,
                                              boolean sourceOnly, Map<Long, String> wireById,
                                              Set<String> expected) {
        long id = addTupleId(i, wireById);
        if (sourceOnly) source.insert(fp(i, 3), id);
        else target.insert(fp(i, 4), id);
        if (expected != null) expected.add(wireById.get(id));
    }

    private static long fp(long i, long variant) {
        return IbltHash.sm64(i * 0x9E3779B97F4A7C15L + variant * 0xD1B54A32D192ED03L)
                & MASK_56;
    }

    private static Set<String> wires(long[] ids, Map<Long, String> wireById) {
        Set<String> out = new HashSet<>();
        for (long id : ids) {
            String wire = wireById.get(id);
            if (wire == null) throw new AssertionError("decoded unknown tuple id=" + id);
            out.add(wire);
        }
        return out;
    }
}
