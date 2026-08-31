package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.core.PkTupleCanonicalizer.PkType;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Emits key, canonical UTF-8, full MD5 and 56-bit fp for an alignment gate. */
public final class CanonicalProbeMain {
    private CanonicalProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = pairs(args);
        String dialect = required(a, "--dialect");
        String table = required(a, "--table");
        List<String> pks = csv(required(a, "--pk-columns"));
        List<PkType> pkTypes = pkTypes(required(a, "--pk-types"));
        List<String> values = csv(required(a, "--value-columns"));
        List<ValueCanonType> valueTypes = ValueCanonType.parseCsv(required(a, "--value-types"));
        String canonical = CompositeIbltSql.canonicalExpression(
                dialect, pks, pkTypes, values, valueTypes);
        String md5 = CompositeIbltSql.fullMd5Expression(dialect, canonical);
        String fp = CompositeIbltSql.fingerprintExpression(dialect, pks, pkTypes, values, valueTypes);

        List<String> quotedPks = new ArrayList<>();
        for (String pk : pks) quotedPks.add(quote(dialect, pk));
        String sql = "SELECT " + String.join(",", quotedPks) + "," + canonical
                + " AS canonical_text," + md5 + " AS full_md5," + fp + " AS fp FROM "
                + table + " ORDER BY " + String.join(",", quotedPks);

        try (Connection conn = DriverManager.getConnection(required(a, "--jdbc"));
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            while (rs.next()) {
                List<String> key = new ArrayList<>();
                for (int i = 0; i < pks.size(); i++) key.add(rs.getString(i + 1));
                String text = rs.getString(pks.size() + 1);
                String encoded = base64.encodeToString(text.getBytes(StandardCharsets.UTF_8));
                System.out.println(CompositePkCodec.encode(key) + "\t" + encoded + "\t"
                        + rs.getString(pks.size() + 2) + "\t" + rs.getString(pks.size() + 3));
            }
        }
    }

    private static Map<String, String> pairs(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("arguments must be key/value pairs");
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) out.put(args[i], args[i + 1]);
        return out;
    }

    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) out.add(item.trim());
        return out;
    }

    private static List<PkType> pkTypes(String raw) {
        List<PkType> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String type = item.trim().toLowerCase();
            if (type.contains("int") || type.contains("serial")) out.add(PkType.INT64);
            else if (type.contains("decimal") || type.contains("numeric")) out.add(PkType.DECIMAL);
            else out.add(PkType.STRING);
        }
        return out;
    }

    private static String quote(String dialect, String identifier) {
        if ("mysql".equalsIgnoreCase(dialect)) return "`" + identifier.replace("`", "``") + "`";
        return "\"" + identifier.toUpperCase().replace("\"", "\"\"") + "\"";
    }
}
