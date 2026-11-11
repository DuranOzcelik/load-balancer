package loadbalancer;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** One upstream server and what the balancer knows about it. */
final class Backend {
    final String name;
    final URI uri;
    final AtomicInteger inFlight = new AtomicInteger();
    final LongAdder requests = new LongAdder();
    final LongAdder failures = new LongAdder();
    final LongAdder nanos = new LongAdder();

    private static final double SMOOTHING = 0.3;           // weight of the newest sample
    private static final double HALF_LIFE_SECONDS = 5.0;   // how fast an old estimate is forgotten

    private double latencyMs;   // exponentially weighted moving average
    private long measuredAt;    // nanoTime of the last sample, 0 = never measured

    private volatile boolean healthy = true;
    private int streak; // consecutive check results that disagree with the current state

    Backend(String name, URI uri) {
        this.name = name;
        this.uri = uri;
    }

    boolean healthy() {
        return healthy;
    }

    /**
     * Records a health check result. The state flips only after `threshold` results in a row
     * disagree with it, so one slow response does not take a server out of rotation and one
     * lucky response does not put a broken server back in.
     */
    synchronized void report(boolean ok, int threshold) {
        if (ok == healthy) {
            streak = 0;
        } else if (++streak >= threshold) {
            healthy = ok;
            streak = 0;
        }
    }

    synchronized void recordLatency(long nanos, long now) {
        double sample = nanos / 1e6;
        latencyMs = measuredAt == 0 ? sample : latencyMs + SMOOTHING * (sample - latencyMs);
        measuredAt = now;
    }

    /**
     * Latency estimate, fading towards zero while the backend gets no traffic. Without the
     * fade a backend that was slow once would keep its bad score forever: it would never be
     * picked again, so it could never show that it has recovered.
     */
    synchronized double latencyEstimate(long now) {
        if (measuredAt == 0) {
            return 0; // never tried: looks free, so it gets a first request
        }
        double idleSeconds = Math.max(0, now - measuredAt) / 1e9;
        return latencyMs * Math.pow(0.5, idleSeconds / HALF_LIFE_SECONDS);
    }

    /** Expected cost of sending one more request here: latency times the queue it would join. */
    double score(long now) {
        return latencyEstimate(now) * (inFlight.get() + 1);
    }

    /** Used when a real request could not even connect: stop sending traffic immediately. */
    synchronized void markDown() {
        healthy = false;
        streak = 0;
    }
}
