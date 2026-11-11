package loadbalancer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the proxy against real HTTP servers on loopback. */
class LoadBalancerTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** Upstream stub that answers with its own name and remembers the last request it saw. */
    private static final class Stub {
        final String name;
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        volatile Headers lastHeaders;
        volatile String lastBody;

        Stub(String name) throws IOException {
            this(name, 0);
        }

        Stub(String name, int port) throws IOException {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress(LOOPBACK, port), 0);
            server.createContext("/health", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            server.createContext("/redirect", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "/elsewhere");
                    exchange.sendResponseHeaders(302, -1);
                }
            });
            server.createContext("/", exchange -> {
                try (exchange) {
                    hits.incrementAndGet();
                    lastHeaders = exchange.getRequestHeaders();
                    lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    byte[] body = (name + " " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        Backend backend() {
            return new Backend(name, URI.create("http://127.0.0.1:" + port()));
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Stub> stubs = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private LoadBalancer balancer;
    private List<Backend> backends;

    private void start(RateLimiter limiter, Backend... extra) throws IOException {
        backends = new ArrayList<>();
        for (String name : new String[]{"a", "b"}) {
            Stub stub = new Stub(name);
            stubs.add(stub);
            backends.add(stub.backend());
        }
        backends.addAll(List.of(extra));
        // One hour between health checks: these tests trigger checks by hand when they need them.
        balancer = new LoadBalancer(new InetSocketAddress(LOOPBACK, 0), backends,
                Balancer.Algorithm.ROUND_ROBIN, limiter, Duration.ofHours(1));
        balancer.start();
    }

    private static RateLimiter unlimited() {
        return new RateLimiter(1, 0);
    }

    @AfterEach
    void stop() throws Exception {
        balancer.close();
        stubs.forEach(stub -> stub.server.stop(0));
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + balancer.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Backend that accepts the connection and hangs up without answering. */
    private Backend brokenBackend() throws IOException {
        ServerSocket socket = new ServerSocket(0, 50, LOOPBACK);
        closeables.add(socket);
        Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket connection = socket.accept();
                    connection.getInputStream().read(); // let the request arrive first
                    connection.close();
                } catch (IOException e) {
                    return;
                }
            }
        });
        return new Backend("broken", URI.create("http://127.0.0.1:" + socket.getLocalPort()));
    }

    @Test
    void alternatesBetweenBackendsAndKeepsPathAndQuery() throws Exception {
        start(unlimited());
        assertEquals("a /items?id=1&q=a%20b", send("GET", "/items?id=1&q=a%20b", null).body());
        assertEquals("b /", send("GET", "/", null).body());
        assertEquals("a /", send("GET", "/", null).body());
    }

    @Test
    void forwardsMethodAndBody() throws Exception {
        start(unlimited());
        assertEquals(200, send("POST", "/orders", "{\"qty\":2}").statusCode());
        assertEquals("{\"qty\":2}", stubs.get(0).lastBody);
    }

    @Test
    void failsOverWhenBackendIsDownAndStopsUsingIt() throws Exception {
        start(unlimited());
        stubs.get(0).server.stop(0);
        for (int i = 0; i < 4; i++) {
            assertEquals("b /", send("GET", "/", null).body());
        }
        assertFalse(backends.get(0).healthy());
        assertEquals(1, backends.get(0).failures.sum(), "only the first request should have tried the dead server");
    }

    @Test
    void postIsRetriedWhenTheConnectionWasRefused() throws Exception {
        start(unlimited());
        stubs.get(0).server.stop(0); // nothing was sent, so repeating the POST elsewhere is safe
        assertEquals(200, send("POST", "/", "x").statusCode());
        assertEquals(1, stubs.get(1).hits.get());
    }

    @Test
    void postIsNotRetriedAfterItReachedABackend() throws Exception {
        Backend broken = brokenBackend();
        start(unlimited(), broken);
        send("GET", "/", null); // served by a
        send("GET", "/", null); // served by b, so the next request goes to the broken backend
        int before = stubs.get(0).hits.get() + stubs.get(1).hits.get();

        assertEquals(502, send("POST", "/pay", "amount=10").statusCode());
        assertEquals(before, stubs.get(0).hits.get() + stubs.get(1).hits.get(),
                "the payment must not be sent to a second server");
    }

    @Test
    void getIsRetriedAfterABackendFailsMidRequest() throws Exception {
        Backend broken = brokenBackend();
        start(unlimited(), broken);
        for (int i = 0; i < 6; i++) { // every third pick lands on the broken backend
            assertEquals(200, send("GET", "/", null).statusCode());
        }
        assertTrue(broken.failures.sum() >= 1);
    }

    @Test
    void returns503WhenNoBackendIsHealthy() throws Exception {
        start(unlimited());
        backends.forEach(Backend::markDown);
        assertEquals(503, send("GET", "/", null).statusCode());
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        start(new RateLimiter(3, 0.5)); // burst of 3, then one request every 2 seconds
        for (int i = 0; i < 3; i++) {
            assertEquals(200, send("GET", "/", null).statusCode());
        }
        HttpResponse<String> rejected = send("GET", "/", null);
        assertEquals(429, rejected.statusCode());
        assertEquals("2", rejected.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(3, stubs.get(0).hits.get() + stubs.get(1).hits.get(), "rejected requests never reach a backend");
    }

    @Test
    void redirectsAreRelayedNotFollowed() throws Exception {
        start(unlimited());
        HttpResponse<String> response = send("GET", "/redirect", null);
        assertEquals(302, response.statusCode());
        assertEquals("/elsewhere", response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void stripsHopByHopHeadersAndAppendsForwardedFor() throws Exception {
        start(unlimited());
        // Raw socket: java.net.http refuses to send a Connection header.
        try (Socket socket = new Socket(LOOPBACK, balancer.port())) {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: shop.example\r\n"
                    + "Connection: close, X-Internal-Token\r\nX-Internal-Token: secret\r\n"
                    + "X-Request-Id: 42\r\nX-Forwarded-For: 203.0.113.9\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
        }
        Headers seen = stubs.get(0).lastHeaders;
        assertEquals("42", seen.getFirst("X-Request-Id"));
        assertNull(seen.getFirst("X-Internal-Token"), "headers named in Connection are hop-by-hop");
        assertEquals("203.0.113.9, 127.0.0.1", seen.getFirst("X-Forwarded-For"));
        assertEquals("shop.example", seen.getFirst("X-Forwarded-Host"));
    }

    @Test
    void healthChecksBringABackendBackAfterTwoGoodResults() throws Exception {
        start(unlimited());
        Backend a = backends.get(0);
        a.markDown();
        balancer.checkHealth();
        Thread.sleep(500);
        assertFalse(a.healthy(), "one good check is not enough");
        balancer.checkHealth();
        for (int i = 0; i < 50 && !a.healthy(); i++) {
            Thread.sleep(100);
        }
        assertTrue(a.healthy());
    }
}
