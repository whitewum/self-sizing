package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer;
import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.util.ArrayList;
import java.util.List;

/** Prints one configured checksum statement for DBMS_XPLAN/EXPLAIN diagnostics. */
public final class MerkleChecksumSqlProbeMain {
    private MerkleChecksumSqlProbeMain() { }

    public static void main(String[] args) {
        if (args.length != 10) {
            throw new IllegalArgumentException("usage: dialect table pkCols pkTypes valueCols valueTypes "
                    + "orderCols orderTypes lower upper");
        }
        System.out.println(CompositeMerkleSql.checksumSql(args[0], args[1], csv(args[2]),
                pkTypes(args[3]), csv(args[4]), ValueCanonType.parseCsv(args[5]),
                csv(args[6]), pkTypes(args[7]), csv(args[8]), csv(args[9])));
    }

    private static List<String> csv(String raw) {
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) values.add(value.trim());
        return values;
    }

    private static List<PkType> pkTypes(String raw) {
        List<PkType> values = new ArrayList<>();
        for (String value : raw.split(",")) values.add(PkTupleCanonicalizer.resolve(value.trim()));
        return values;
    }
}
