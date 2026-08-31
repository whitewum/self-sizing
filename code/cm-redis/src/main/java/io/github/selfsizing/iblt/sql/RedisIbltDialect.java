package io.github.selfsizing.iblt.sql;

import java.util.List;

/**
 * Marker dialect for the non-JDBC Redis sidecar.
 *
 * <p>Redis fingerprinting and candidate recheck are implemented through RESP in the sidecar
 * service. SQL methods intentionally fail if a caller accidentally routes Redis through a JDBC or
 * Merkle endpoint.</p>
 */
public final class RedisIbltDialect implements IbltDialect {

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public String fingerprintSql(FingerprintQuery query) {
        throw unsupported();
    }

    @Override
    public String fingerprintExpression(FingerprintQuery query) {
        throw unsupported();
    }

    @Override
    public String recheckSql(RecheckQuery query, List<String> candidatePks) {
        throw unsupported();
    }

    @Override
    public String quoteIdentifier(String identifier) {
        throw unsupported();
    }

    @Override
    public String pkLiteral(String value, boolean pkIsString) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Redis sidecar does not use SQL");
    }
}
