package io.github.selfsizing.iblt.relational;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Proves the controller starts source and target requests before waiting for either one. */
public final class MerkleDualEndpointConcurrencySelfTest {
    private MerkleDualEndpointConcurrencySelfTest() { }

    public static void main(String[] args) throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        HttpServer left = server("left", bothEntered);
        HttpServer right = server("right", bothEntered);
        left.start();
        right.start();
        try {
            String leftUrl = "http://127.0.0.1:" + left.getAddress().getPort() + "/work";
            String rightUrl = "http://127.0.0.1:" + right.getAddress().getPort() + "/work";
            CompositeMerkleCompareMain.ResponsePair pair =
                    CompositeMerkleCompareMain.requestPair("POST", leftUrl, rightUrl, "payload");
            require("left".equals(pair.left().body()), "left response");
            require("right".equals(pair.right().body()), "right response");
        } finally {
            left.stop(0);
            right.stop(0);
        }
        System.out.println("MERKLE_DUAL_ENDPOINT_CONCURRENCY_SELF_TEST PASS");
    }

    private static HttpServer server(String response, CountDownLatch bothEntered) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/work", exchange -> respondAfterPeerEntered(exchange, response, bothEntered));
        return server;
    }

    private static void respondAfterPeerEntered(HttpExchange exchange, String response,
                                                 CountDownLatch bothEntered) throws java.io.IOException {
        bothEntered.countDown();
        try {
            if (!bothEntered.await(2, TimeUnit.SECONDS)) {
                send(exchange, 500, "peer request did not start concurrently");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(exchange, 500, "interrupted");
            return;
        }
        exchange.getResponseHeaders().set("X-Merkle-Workers", "30");
        send(exchange, 200, response);
    }

    private static void send(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
