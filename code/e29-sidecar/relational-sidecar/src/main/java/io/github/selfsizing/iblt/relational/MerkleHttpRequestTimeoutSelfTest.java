package io.github.selfsizing.iblt.relational;

import io.github.selfsizing.iblt.relational.HttpSidecarClient;

import java.net.http.HttpRequest;
import java.time.Duration;

/** Proves Merkle requests use the shared configurable HTTP request timeout. */
public final class MerkleHttpRequestTimeoutSelfTest {
    private MerkleHttpRequestTimeoutSelfTest() { }

    public static void main(String[] args) {
        String key = HttpSidecarClient.TIMEOUT_PROPERTY;
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "3600");
            HttpRequest request = CompositeMerkleCompareMain.buildRequest(
                    "POST", "http://127.0.0.1:1/merkle/checksums", "");
            require(request.timeout().orElseThrow().equals(Duration.ofHours(1)),
                    "Merkle request did not use one-hour override");
        } finally {
            if (original == null) System.clearProperty(key); else System.setProperty(key, original);
        }
        System.out.println("MERKLE_HTTP_TIMEOUT_CONFIG_SELF_TEST PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
