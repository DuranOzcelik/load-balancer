package loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** HTTP reverse proxy: rate limits clients, picks a backend, forwards, retries when safe. */
final class LoadBalancer implements AutoCloseable {
    static final int HISTORY = 60;
    private static final int MAX_BODY = 10 * 1024 * 1024;
    private static final int HEALTH_THRESHOLD = 2;
    // Retrying is only safe if repeating the request cannot change the result (RFC 9110 9.2.2).
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE");
    // Hop-by-hop headers describe one connection, not the message, and must not be forwarded
    // (RFC 9110 7.6.1). host/content-length/expect are set by the HTTP client itself.
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "expect", "x-lb-benchmark");

    final Balancer balancer;
    final RateLimiter limiter;
    final LongAdder limited = new LongAdder();
    final LongAdder errors = new LongAdder();
    /**
     * Random secret that lets the built-in benchmark skip the rate limiter. It never leaves
     * this process: the header is stripped before a request is forwarded.
     */
    final String benchmarkToken = java.util.UUID.randomUUID().toString();

    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER) // a proxy relays redirects, it does not follow them
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lb-housekeeping");
        thread.setDaemon(true);
        return thread;
    });
    private final Duration healthInterval;
    private final long[][] history; // [second][backend..., rate limited]
    private final long[] lastTotals;
    private int nextSample;

    LoadBalancer(InetSocketAddress address, List<Backend> backends, Balancer.Algorithm algorithm,
                 RateLimiter limiter, Duration healthInterval) throws IOException {
        this.balancer = new Balancer(backends, algorithm);
        this.limiter = limiter;
        this.healthInterval = healthInterval;
        this.history = new long[HISTORY][backends.size() + 1];
        this.lastTotals = new long[backends.size() + 1];
        this.server = HttpServer.create(address, 1024);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    void start() {
        server.start();
        long millis = healthInterval.toMillis();
        scheduler.scheduleAtFixedRate(this::checkHealth, millis, millis, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Keyed by the socket's address. X-Forwarded-For is client-controlled and trivial to
            // spoof, so trusting it here would let anyone dodge the limit.
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            boolean benchmark = benchmarkToken.equals(exchange.getRequestHeaders().getFirst("X-LB-Benchmark"));
            long waitMillis = benchmark ? 0 : limiter.acquire(clientIp);
            if (waitMillis > 0) {
                limited.increment();
                exchange.getResponseHeaders().set("Retry-After", Long.toString((waitMillis + 999) / 1000));
                reply(exchange, 429, "rate limit exceeded\n");
                return;
            }
            // shortcut: bodies are buffered in memory (max 10 MB), which is what makes a retry
            // possible. Stream them instead if large uploads matter.
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                reply(exchange, 413, "request body too large\n");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            Set<Backend> tried = new HashSet<>();
            while (true) {
                Backend backend = balancer.pick(tried);
                if (backend == null) {
                    errors.increment();
                    reply(exchange, tried.isEmpty() ? 503 : 502,
                            tried.isEmpty() ? "no healthy backend\n" : "bad gateway\n");
                    return;
                }
                tried.add(backend);
                HttpResponse<byte[]> response = null;
                boolean retry;
                backend.inFlight.incrementAndGet();
                long startedAt = System.nanoTime();
                try {
                    response = client.send(forward(exchange, backend, method, body, clientIp),
                            HttpResponse.BodyHandlers.ofByteArray());
                    backend.requests.increment();
                    long finishedAt = System.nanoTime();
                    backend.nanos.add(finishedAt - startedAt);
                    backend.recordLatency(finishedAt - startedAt, finishedAt);
                    retry = false;
                } catch (IllegalArgumentException e) { // e.g. a method the client library refuses
                    reply(exchange, 400, "bad request\n");
                    return;
                } catch (IOException e) {
                    backend.failures.increment();
                    // If the connection was never established, the backend saw nothing, so even
                    // a POST can go to another server. After that point only idempotent methods
                    // may be retried: the first backend might already have done the work.
                    boolean neverSent = e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
                    if (neverSent) {
                        backend.markDown();
                    }
                    retry = neverSent || IDEMPOTENT.contains(method);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    retry = false;
                } finally {
                    backend.inFlight.decrementAndGet();
                }
                if (response != null) {
                    relay(exchange, response, method);
                    return;
                }
                if (!retry) {
                    errors.increment();
                    reply(exchange, 502, "bad gateway\n");
                    return;
                }
            }
        }
    }

    private HttpRequest forward(HttpExchange exchange, Backend backend, String method, byte[] body, String clientIp) {
        // Only path and query are taken from the request line. An absolute-form target such as
        // "GET http://internal-host/" must not be able to redirect the proxy somewhere else.
        URI requested = exchange.getRequestURI();
        String base = backend.uri.toString().replaceAll("/+$", "");
        String path = requested.getRawPath() == null || requested.getRawPath().isEmpty() ? "/" : requested.getRawPath();
        String query = requested.getRawQuery() == null ? "" : "?" + requested.getRawQuery();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path + query))
                .timeout(Duration.ofSeconds(30))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        Set<String> skip = new HashSet<>(NOT_FORWARDED);
        // "Connection: X-Foo" marks X-Foo as hop-by-hop for this request, so it is dropped too.
        for (String value : exchange.getRequestHeaders().getOrDefault("Connection", List.of())) {
            for (String token : value.split(",")) {
                skip.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> forwardedFor = new ArrayList<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("x-forwarded-for")) {
                forwardedFor.addAll(values);
            } else if (!skip.contains(lower)) {
                for (String value : values) {
                    try {
                        request.header(name, value);
                    } catch (IllegalArgumentException ignored) {
                        // header the client library does not allow callers to set
                    }
                }
            }
        });
        forwardedFor.add(clientIp);
        request.header("X-Forwarded-For", String.join(", ", forwardedFor));
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null) {
            request.header("X-Forwarded-Host", host);
        }
        return request.build();
    }

    private static void relay(HttpExchange exchange, HttpResponse<byte[]> response, String method) throws IOException {
        boolean head = method.equals("HEAD");
        response.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean keepLength = head && lower.equals("content-length");
            if (!lower.startsWith(":") && (keepLength || !NOT_FORWARDED.contains(lower))) {
                exchange.getResponseHeaders().put(name, new ArrayList<>(values));
            }
        });
        int status = response.statusCode();
        byte[] body = response.body();
        boolean bodyless = head || status == 204 || status == 304 || status < 200 || body.length == 0;
        exchange.sendResponseHeaders(status, bodyless ? -1 : body.length);
        if (!bodyless) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /** Active health checks: GET /health on every backend, each on its own virtual thread. */
    void checkHealth() {
        limiter.evictIdle();
        for (Backend backend : balancer.backends) {
            Thread.ofVirtual().start(() -> backend.report(probe(backend), HEALTH_THRESHOLD));
        }
    }

    private boolean probe(Backend backend) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(backend.uri.toString().replaceAll("/+$", "") + "/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void sample() {
        long[] row = history[nextSample];
        for (int i = 0; i < row.length; i++) {
            long total = i < balancer.backends.size() ? balancer.backends.get(i).requests.sum() : limited.sum();
            row[i] = total - lastTotals[i];
            lastTotals[i] = total;
        }
        nextSample = (nextSample + 1) % HISTORY;
    }

    /** Requests per second for the last minute, oldest first. Column i is backend i; the last is 429s. */
    synchronized long[][] history() {
        long[][] ordered = new long[HISTORY][];
        for (int i = 0; i < HISTORY; i++) {
            ordered[i] = history[(nextSample + i) % HISTORY].clone();
        }
        return ordered;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        server.stop(0);
        client.close();
    }
}
