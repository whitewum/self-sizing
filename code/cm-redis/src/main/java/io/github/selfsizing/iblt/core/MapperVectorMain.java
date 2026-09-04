package io.github.selfsizing.iblt.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Emits mapper positions for cross-language differential testing. */
public final class MapperVectorMain {
    private MapperVectorMain() { }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = reader.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\s+");
            if (fields.length != 3) {
                throw new IllegalArgumentException("expected: fp m seed; got: " + line);
            }
            long fp = Long.parseUnsignedLong(fields[0]);
            long m = Long.parseLong(fields[1]);
            long seed = Long.parseUnsignedLong(fields[2]);
            int[] p = IbltHash.positions(fp, m, seed);
            System.out.println(p[0] + " " + p[1] + " " + p[2]);
        }
    }
}
