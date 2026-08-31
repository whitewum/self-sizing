package io.github.selfsizing.iblt.relational;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cross-engine canonical types for non-key columns used in row fingerprints. */
public enum ValueCanonType {
    STRING,
    INT64,
    DECIMAL4,
    DATETIME_SEC;

    public static ValueCanonType parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("int") || value.equals("int64") || value.contains("serial")) {
            return INT64;
        }
        if (value.equals("decimal4") || value.equals("decimal(4)")
                || value.equals("numeric4") || value.equals("numeric(4)")) {
            return DECIMAL4;
        }
        if (value.equals("datetime_sec") || value.equals("datetime-sec")
                || value.equals("datetime") || value.equals("date") || value.equals("timestamp")) {
            return DATETIME_SEC;
        }
        if (value.equals("string") || value.equals("varchar") || value.equals("char")) {
            return STRING;
        }
        throw new IllegalArgumentException("unsupported value canonical type: " + raw
                + " (expected string, int, decimal4, or datetime_sec)");
    }

    public static List<ValueCanonType> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<ValueCanonType> out = new ArrayList<>();
        for (String item : raw.split(",")) out.add(parse(item));
        return out;
    }
}
