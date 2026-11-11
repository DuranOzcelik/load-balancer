package loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Dashboard and control API. Listens on the loopback interface only. */
final class Admin {
    private static final int MAX_BURST = 2000;

    private final LoadBalancer balancer;
    private final List<DemoBackend> demos;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    Admin(LoadBalancer balancer, List<DemoBackend> demos) {
        this.balancer = balancer;
        this.demos = demos;
    }

    HttpServer start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                send(exchange, 404, "text/plain", "not found");
                return;
            }
            try (InputStream page = Admin.class.getResourceAsStream("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
            }
        });
        http.createContext("/api/state", exchange -> send(exchange, 200, "application/json", state()));
        http.createContext("/api/algorithm", exchange -> control(exchange, value -> {
            balancer.balancer.algorithm = Balancer.Algorithm.valueOf(value);
            return "{}";
        }));
        http.createContext("/api/demo", exchange -> control(exchange, value -> {
            String[] parts = value.split(":", 2); // "<index>:start" or "<index>:stop"
            DemoBackend demo = demos.get(Integer.parseInt(parts[0]));
            if (parts[1].equals("start")) {
                demo.start();
            } else {
                demo.stop();
            }
            return "{}";
        }));
        http.createContext("/api/burst", exchange -> control(exchange, value -> burst(Integer.parseInt(value))));
        http.createContext("/api/compare", exchange -> control(exchange, value -> compare(Integer.parseInt(value))));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return http;
    }

    private interface Action {
        String run(String value) throws Exception;
    }

    private void control(HttpExchange exchange, Action action) throws IOException {
        // The custom header forces a CORS preflight for cross-origin callers, which this server
        // never approves. Other websites therefore cannot flip switches through the browser.
        if (!exchange.getRequestMethod().equals("POST") || exchange.getRequestHeaders().getFirst("X-Admin") == null) {
            send(exchange, 403, "text/plain", "forbidden");
            return;
        }
        String value = new String(exchange.getRequestBody().readNBytes(256), StandardCharsets.UTF_8).trim();
        try {
            send(exchange, 200, "application/json", action.run(value));
        } catch (Exception e) {
            send(exchange, 400, "text/plain", "bad request");
        }
    }

    /** Fires `count` GET requests at the proxy and reports how they ended. */
    private String burst(int count) throws InterruptedException {
        int total = Math.max(1, Math.min(count, MAX_BURST));
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Semaphore concurrency = new Semaphore(64);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + balancer.port() + "/")).build();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                concurrency.acquire();
                pool.submit(() -> {
                    try {
                        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                        (status == 429 ? limited : status < 400 ? ok : failed).incrementAndGet();
                    } catch (IOException | InterruptedException e) {
                        failed.incrementAndGet();
                    } finally {
                        concurrency.release();
                    }
                });
            }
        }
        return "{\"ok\":" + ok + ",\"limited\":" + limited + ",\"failed\":" + failed + "}";
    }

    /**
     * Runs the same workload once per algorithm and reports latency percentiles and how the
     * requests were spread. Only one comparison runs at a time.
     */
    private synchronized String compare(int count) throws InterruptedException {
        int total = Math.max(30, Math.min(count, 1000));
        List<Backend> backends = balancer.balancer.backends;
        Balancer.Algorithm original = balancer.balancer.algorithm;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + balancer.port() + "/"))
                .header("X-LB-Benchmark", balancer.benchmarkToken).build();
        StringBuilder results = new StringBuilder();
        try {
            for (Balancer.Algorithm algorithm : Balancer.Algorithm.values()) {
                balancer.balancer.algorithm = algorithm;
                long[] before = backends.stream().mapToLong(b -> b.requests.sum()).toArray();
                long[] millis = new long[total];
                AtomicInteger failed = new AtomicInteger();
                Semaphore concurrency = new Semaphore(12);
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < total; i++) {
                        int slot = i;
                        concurrency.acquire();
                        pool.submit(() -> {
                            long started = System.nanoTime();
                            try {
                                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() >= 400) {
                                    failed.incrementAndGet();
                                }
                            } catch (IOException | InterruptedException e) {
                                failed.incrementAndGet();
                            } finally {
                                millis[slot] = (System.nanoTime() - started) / 1_000_000;
                                concurrency.release();
                            }
                        });
                    }
                }
                Arrays.sort(millis);
                String shares = java.util.stream.IntStream.range(0, backends.size())
                        .mapToObj(i -> Long.toString(backends.get(i).requests.sum() - before[i]))
                        .collect(Collectors.joining(","));
                results.append(results.isEmpty() ? "" : ",")
                        .append("{\"algorithm\":\"").append(algorithm).append('"')
                        .append(",\"p50\":").append(millis[total / 2])
                        .append(",\"p95\":").append(millis[(int) (total * 0.95)])
                        .append(",\"mean\":").append(Math.round(Arrays.stream(millis).average().orElse(0)))
                        .append(",\"failed\":").append(failed.get())
                        .append(",\"served\":[").append(shares).append("]}");
            }
        } finally {
            balancer.balancer.algorithm = original;
        }
        return "{\"requests\":" + total + ",\"results\":[" + results + "]}";
    }

    private String state() {
        List<Backend> backends = balancer.balancer.backends;
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < backends.size(); i++) {
            Backend backend = backends.get(i);
            long requests = backend.requests.sum();
            double averageMs = requests == 0 ? 0 : backend.nanos.sum() / 1e6 / requests;
            DemoBackend demo = i < demos.size() ? demos.get(i) : null;
            rows.append(i == 0 ? "" : ",")
                    .append("{\"name\":\"").append(escape(backend.name)).append('"')
                    .append(",\"url\":\"").append(escape(backend.uri.toString())).append('"')
                    .append(",\"healthy\":").append(backend.healthy())
                    .append(",\"inFlight\":").append(backend.inFlight.get())
                    .append(",\"requests\":").append(requests)
                    .append(",\"failures\":").append(backend.failures.sum())
                    .append(",\"avgMs\":").append(String.format(Locale.ROOT, "%.1f", averageMs))
                    .append(",\"estimateMs\":")
                    .append(String.format(Locale.ROOT, "%.1f", backend.latencyEstimate(System.nanoTime())))
                    .append(",\"demo\":").append(demo != null)
                    .append(",\"running\":").append(demo != null && demo.running())
                    .append('}');
        }
        String history = Arrays.stream(balancer.history())
                .map(row -> Arrays.stream(row).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]")))
                .collect(Collectors.joining(",", "[", "]"));
        RateLimiter limiter = balancer.limiter;
        return "{\"algorithm\":\"" + balancer.balancer.algorithm + "\""
                + ",\"port\":" + balancer.port()
                + ",\"rateEnabled\":" + limiter.enabled()
                + ",\"burst\":" + (long) limiter.capacity()
                + ",\"perSecond\":" + String.format(Locale.ROOT, "%.1f", limiter.perSecond())
                + ",\"trackedClients\":" + limiter.trackedClients()
                + ",\"limited\":" + balancer.limited.sum()
                + ",\"errors\":" + balancer.errors.sum()
                + ",\"backends\":[" + rows + "]"
                + ",\"history\":" + history + "}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\x00-\\x1f]", "?");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
