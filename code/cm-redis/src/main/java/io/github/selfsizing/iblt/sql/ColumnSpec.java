package io.github.selfsizing.iblt.sql;

/**
 * A column that participates in the fingerprint: column name plus its
 * engine-independent canonical text type {@link CanonType}.
 */
public final class ColumnSpec {

    private final String name;
    private final CanonType type;

    public ColumnSpec(String name, CanonType type) {
        this.name = name;
        this.type = type;
    }

    public static ColumnSpec of(String name, CanonType type) {
        return new ColumnSpec(name, type);
    }

    public String name() {
        return name;
    }

    public CanonType type() {
        return type;
    }
}
