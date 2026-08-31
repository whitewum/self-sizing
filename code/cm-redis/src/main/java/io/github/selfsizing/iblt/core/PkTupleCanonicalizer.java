package io.github.selfsizing.iblt.core;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * "Serious encoding" of a primary-key tuple: an <b>injective + cross-engine
 * normalized</b> TLV encoding of the (composite) primary-key values, whose
 * output is then fed to {@link IbltHash#cheap8} to get the IBLT's 56-bit proxy id.
 *
 * <p>Moved in from the main project. To drop private dependencies, the original
 * lombok {@code @Slf4j} is replaced with {@link java.util.logging.Logger} and the
 * original {@code commons-lang3 StringUtils.isBlank} is inlined as
 * {@link #isBlank}. The normalization logic is byte-for-byte unchanged (it must
 * match the main project / the other side, otherwise ids diverge and diffs are missed).</p>
 *
 * <p><b>Why it matters (more than the hash itself):</b> the id only needs "the
 * same Java on both sides to produce the same id for the same logical primary
 * key". But MySQL-JDBC and PG-JDBC may give different representations of the same
 * logical value ({@code 100} vs {@code 100.00}, trailing-space CHAR PAD, case,
 * etc.); hashing without normalizing first makes the two sides' ids diverge, so a
 * value-changed row is misread as sourceOnly + targetOnly and missed. So even a
 * single-column primary key must be normalized by its logical type before hashing.</p>
 *
 * <p>{@code concat_ws(chr(31), pk1, pk2)} is <b>not</b> usable: NULL is skipped /
 * a value may contain the separator / {@code (a,bc)} and {@code (ab,c)} concatenate
 * ambiguously. This class disambiguates with explicit length + arity + null_flag
 * TLV framing:</p>
 * <pre>
 * [u8 version=0x01][u8 arity]
 * per column: [u8 type_tag][u8 null_flag]  if non-null, then [u32 BE length][value_bytes]
 * </pre>
 *
 * <p><b>Scope:</b> the current IBLT uses single-column primary keys only, so this
 * pass only uses arity=1; the API takes a tuple ({@code PkType[]} / {@code Object[]})
 * to leave room for composite primary keys later. Per-type {@code value_bytes}
 * normalization currently covers the common primary-key types
 * {@link PkType#INT64} / {@link PkType#DECIMAL} / {@link PkType#STRING};
 * DATE/TS/BYTES/BOOL/UINT64 etc. fall back to STRING(text) for now.</p>
 *
 * <p><b>type_tag comes from the comparison config's column logical type; do not
 * trust {@code java.sql.Types} directly</b> (driver mappings are inconsistent).</p>
 */
public final class PkTupleCanonicalizer {

    private static final Logger LOG = Logger.getLogger(PkTupleCanonicalizer.class.getName());

    private static final byte VERSION = 0x01;

    /** Primary-key logical type (decides how value_bytes is normalized). type_tag uses its {@link #ordinal()}. */
    public enum PkType {
        /** TINYINT...BIGINT signed integer: fixed 8B big-endian two's complement. */
        INT64,
        /** DECIMAL/NUMERIC: {@code stripTrailingZeros().toPlainString()}, {@code -0}&rarr;{@code 0}, UTF-8 (fixes 100 vs 100.00). */
        DECIMAL,
        /** CHAR/VARCHAR/TEXT and other fallback types: NFC + rtrim trailing spaces + optional lower, UTF-8. */
        STRING
    }

    private PkTupleCanonicalizer() {
    }

    /**
     * Resolves {@link PkType} from a column logical type name. Anything not
     * clearly an integer / decimal family becomes {@link PkType#STRING} (safe for any primary key).
     */
    public static PkType resolve(String typeName) {
        if (isBlank(typeName)) {
            return PkType.STRING;
        }
        String t = typeName.toLowerCase(Locale.ROOT);
        boolean integerFamily = (t.contains("int") && !t.contains("interval")) || t.contains("serial");
        if (integerFamily) {
            return PkType.INT64;
        }
        if (t.contains("decimal") || t.contains("numeric")) {
            return PkType.DECIMAL;
        }
        // DATE/TIMESTAMP/BINARY/BOOL/UNSIGNED BIGINT etc. are not yet normalized separately; fall back to STRING(text).
        if (!t.contains("char") && !t.contains("text") && !t.contains("varchar")) {
            LOG.info(() -> "[iblt] pk type '" + typeName + "' not specially canonicalized, fallback to STRING(text).");
        }
        return PkType.STRING;
    }

    /** arity=1 convenience overload (the main path for this pass). */
    public static byte[] encode(PkType type, Object value, boolean lowerForString) {
        return encode(new PkType[]{type}, new Object[]{value}, lowerForString);
    }

    /**
     * Injectively TLV-encodes the whole set of primary-key values.
     *
     * @param types          per-column logical types (fixed schema column order, not result-set order)
     * @param values         per-column raw values (may be null)
     * @param lowerForString whether STRING columns get {@code lower()} (must match the fp {@code lower()} policy)
     */
    public static byte[] encode(PkType[] types, Object[] values, boolean lowerForString) {
        if (types == null || values == null || types.length != values.length) {
            throw new IllegalArgumentException("pk types/values length mismatch");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        out.write(VERSION);
        out.write(types.length & 0xFF);
        for (int i = 0; i < types.length; i++) {
            PkType type = types[i];
            Object v = values[i];
            out.write(type.ordinal() & 0xFF);
            if (v == null) {
                out.write(0x01); // null_flag=NULL, no len/value follows
                continue;
            }
            out.write(0x00); // null_flag=non-null
            byte[] vb = valueBytes(type, v, lowerForString);
            writeU32Be(out, vb.length);
            out.write(vb, 0, vb.length);
        }
        return out.toByteArray();
    }

    private static byte[] valueBytes(PkType type, Object v, boolean lowerForString) {
        switch (type) {
            case INT64: {
                long n = new BigInteger(String.valueOf(v).trim()).longValueExact();
                return new byte[]{
                        (byte) (n >>> 56), (byte) (n >>> 48), (byte) (n >>> 40), (byte) (n >>> 32),
                        (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n
                };
            }
            case DECIMAL: {
                String s = new BigDecimal(String.valueOf(v).trim()).stripTrailingZeros().toPlainString();
                if ("-0".equals(s)) {
                    s = "0";
                }
                return s.getBytes(StandardCharsets.UTF_8);
            }
            case STRING:
            default: {
                String s = Normalizer.normalize(String.valueOf(v), Normalizer.Form.NFC);
                s = rtrim(s); // CHAR PAD: trailing spaces are unstable across databases
                if (lowerForString) {
                    s = s.toLowerCase(Locale.ROOT);
                }
                return s.getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    /** Strips trailing spaces (0x20 only); leaves leading / interior whitespace alone. */
    private static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static void writeU32Be(ByteArrayOutputStream out, int len) {
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    /** Inline equivalent of commons-lang3 {@code StringUtils.isBlank} (null / empty / all whitespace). */
    private static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
