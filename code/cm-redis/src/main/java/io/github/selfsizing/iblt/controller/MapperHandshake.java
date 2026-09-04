package io.github.selfsizing.iblt.controller;

import io.github.selfsizing.iblt.core.IbltConstants;

/** Validates the mapper version before two sidecars exchange sketches. */
public final class MapperHandshake {
    private MapperHandshake() { }

    public static void verify(SidecarClient a, SidecarClient b) throws Exception {
        int versionA = version(a, a.health());
        int versionB = version(b, b.health());
        if (versionA != versionB) {
            throw new IllegalStateException("mapper version mismatch between " + a.label()
                    + " and " + b.label() + ": " + versionA + " vs " + versionB);
        }
        if (versionA != IbltConstants.MAPPER_VERSION) {
            throw new IllegalStateException("unsupported mapper version " + versionA
                    + "; local=" + IbltConstants.MAPPER_VERSION);
        }
    }

    private static int version(SidecarClient sidecar, String health) {
        for (String token : health.trim().split("\\s+")) {
            if (token.startsWith("mapperVersion=")) {
                try {
                    return Integer.parseInt(token.substring("mapperVersion=".length()));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(sidecar.label()
                            + " returned invalid mapperVersion: " + health, e);
                }
            }
        }
        throw new IllegalStateException(sidecar.label()
                + " health response missing mapperVersion: " + health);
    }
}
