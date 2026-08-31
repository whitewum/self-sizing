package io.github.selfsizing.iblt.relational;

import java.time.Duration;

/** No-network test for the full-scale request timeout override. */
public final class HttpSidecarClientTimeoutSelfTest {
    private HttpSidecarClientTimeoutSelfTest() { }

    public static void main(String[] args) {
        String key = HttpSidecarClient.TIMEOUT_PROPERTY;
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "3600");
            require(HttpSidecarClient.configuredRequestTimeout().equals(Duration.ofHours(1)), "one-hour override");
            System.setProperty(key, "0");
            try {
                HttpSidecarClient.configuredRequestTimeout();
                throw new AssertionError("non-positive timeout accepted");
            } catch (IllegalArgumentException expected) { }
        } finally {
            if (original == null) System.clearProperty(key); else System.setProperty(key, original);
        }
        System.out.println("HTTP_TIMEOUT_CONFIG_SELF_TEST PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
