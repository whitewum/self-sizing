package io.github.selfsizing.iblt.sql;

/**
 * The <b>engine-independent canonical text</b> type of a column inside the
 * fingerprint concat. It tells each dialect how to render the column as
 * normalized text, so the same logical value yields a byte-identical
 * fingerprint across engines (verified here by a full-table ClickHouse&harr;SQL Server check).
 *
 * <ul>
 *   <li>{@link #STRING}: text as-is (the whole concat is {@code lower()}ed afterwards).</li>
 *   <li>{@link #INT}: decimal integer string.</li>
 *   <li>{@link #MONEY2}: <b>integer cents</b> (a 2-decimal amount &times; 100, rounded to an integer).
 *       This avoids the Float64&harr;Decimal precision trap (in ClickHouse {@code amount}
 *       is Float64, so {@code 2.05} is actually stored as {@code 2.0499998} and a direct
 *       {@code toDecimal64(.,2)} truncates it to 2.04).</li>
 *   <li>{@link #DATETIME_SEC}: {@code yyyy-MM-dd HH:mm:ss} (second precision, milliseconds dropped).</li>
 * </ul>
 */
public enum CanonType {
    STRING,
    INT,
    MONEY2,
    DATETIME_SEC
}
