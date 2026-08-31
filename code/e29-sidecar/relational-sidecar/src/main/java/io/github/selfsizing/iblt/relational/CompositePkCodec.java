package io.github.selfsizing.iblt.relational;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Unambiguously packs the raw composite-primary-key values into the existing sidecar's single-string wire protocol. */
public final class CompositePkCodec {
    private static final String PREFIX = "CK1:";

    private CompositePkCodec() {
    }

    public static String encode(List<String> values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        StringBuilder out = new StringBuilder(PREFIX);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append('.');
            String value = values.get(i);
            if (value == null) value = "";
            out.append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }

    public static List<String> decode(String wire, int arity) {
        if (arity == 1 && !wire.startsWith(PREFIX)) {
            return List.of(wire);
        }
        if (!wire.startsWith(PREFIX)) {
            throw new IllegalArgumentException("bad composite pk wire value: " + wire);
        }
        String body = wire.substring(PREFIX.length());
        String[] tokens = body.split("\\.", -1);
        if (tokens.length != arity) {
            throw new IllegalArgumentException("pk arity mismatch: expected=" + arity
                    + ", actual=" + tokens.length);
        }
        List<String> values = new ArrayList<>(arity);
        for (String token : tokens) {
            values.add(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
        }
        return values;
    }
}
