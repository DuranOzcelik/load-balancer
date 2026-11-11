package loadbalancer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Main {
    private static final String USAGE = """
            Usage: java -jar load-balancer.jar [options]
              --backend <url>      upstream server, repeat for each one (default: 3 built-in demo servers)
              --port <n>           proxy port (default 8000)
              --admin <n>          dashboard port, loopback only (default 8001)
              --bind <address>     proxy bind address (default 127.0.0.1)
              --algorithm <name>   round-robin, least-connections or least-latency (default round-robin)
              --rate <n>           requests per second allowed per client IP, 0 disables (default 50)
              --burst <n>          bucket size, the burst a client may send at once (default 100)
              --health <seconds>   interval between health checks (default 2)
            """;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        List<URI> urls = new ArrayList<>();
        int port = 8000;
        int adminPort = 8001;
        String bind = "127.0.0.1";
        Balancer.Algorithm algorithm = Balancer.Algorithm.ROUND_ROBIN;
        double rate = 50;
        double burst = 100;
        double health = 2;
        try {
            for (int i = 0; i < args.length; i += 2) {
                String value = args[i + 1];
                switch (args[i]) {
                    case "--backend" -> {
                        URI uri = URI.create(value);
                        if (uri.getHost() == null || !"http".equals(uri.getScheme())) {
                            throw new IllegalArgumentException("backend must be an http:// URL: " + value);
                        }
                        urls.add(uri);
                    }
                    case "--port" -> port = Integer.parseInt(value);
                    case "--admin" -> adminPort = Integer.parseInt(value);
                    case "--bind" -> bind = value;
                    case "--algorithm" -> algorithm = Balancer.Algorithm.valueOf(
                            value.toUpperCase(Locale.ROOT).replace('-', '_'));
                    case "--rate" -> rate = Double.parseDouble(value);
                    case "--burst" -> burst = Double.parseDouble(value);
                    case "--health" -> health = Double.parseDouble(value);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (health <= 0) {
                throw new IllegalArgumentException("--health must be positive");
            }
        } catch (RuntimeException e) { // includes a missing value for the last option
            System.err.println("error: " + e.getMessage() + "\n\n" + USAGE);
            System.exit(2);
        }

        List<DemoBackend> demos = new ArrayList<>();
        List<Backend> backends = new ArrayList<>();
        if (urls.isEmpty()) {
            long[] delays = {10, 10, 120}; // the third server is deliberately slow
            for (int i = 0; i < delays.length; i++) {
                DemoBackend demo = new DemoBackend("backend-" + (i + 1), 9001 + i, delays[i]);
                demo.start();
                demos.add(demo);
                backends.add(new Backend(demo.name, URI.create("http://127.0.0.1:" + demo.port)));
            }
        } else {
            for (int i = 0; i < urls.size(); i++) {
                backends.add(new Backend("backend-" + (i + 1), urls.get(i)));
            }
        }

        LoadBalancer balancer = new LoadBalancer(new InetSocketAddress(InetAddress.getByName(bind), port), backends,
                algorithm, new RateLimiter(burst, rate), Duration.ofMillis((long) (health * 1000)));
        balancer.start();
        new Admin(balancer, demos).start(adminPort);
        System.out.println("load balancer: http://" + bind + ":" + balancer.port()
                + "  ->  " + backends.size() + " backend(s)" + (demos.isEmpty() ? "" : " (demo)"));
        System.out.println("dashboard:     http://127.0.0.1:" + adminPort);
    }
}
