package loadbalancer;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** A tiny upstream server for trying the balancer without setting anything up. */
final class DemoBackend {
    final String name;
    final int port;
    private final long delayMillis;
    private HttpServer server;

    DemoBackend(String name, int port, long delayMillis) {
        this.name = name;
        this.port = port;
        this.delayMillis = delayMillis;
    }

    synchronized boolean running() {
        return server != null;
    }

    synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1024);
        server.createContext("/health", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.createContext("/", exchange -> {
            try (exchange) {
                Thread.sleep(delayMillis); // simulated work, so backends differ in speed
                byte[] body = ("Hello from " + name + "\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** Closes the listening socket, so new connections are refused like a crashed process. */
    synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
