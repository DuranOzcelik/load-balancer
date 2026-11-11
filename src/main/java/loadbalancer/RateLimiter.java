package loadbalancer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket per client. A bucket holds up to `capacity` tokens and refills at `perSecond`.
 * Each request costs one token, so short bursts pass and the long-run rate is capped.
 */
final class RateLimiter {
    private record Bucket(double tokens, long updatedAt) {
    }

    private static final double EPSILON = 1e-6;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double perSecond;
    private final LongSupplier nanoClock;

    RateLimiter(double capacity, double perSecond) {
        this(capacity, perSecond, System::nanoTime);
    }

    RateLimiter(double capacity, double perSecond, LongSupplier nanoClock) { // fake clock in tests
        this.capacity = capacity;
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
    }

    double capacity() {
        return capacity;
    }

    double perSecond() {
        return perSecond;
    }

    boolean enabled() {
        return perSecond > 0 && capacity >= 1;
    }

    private double refilled(Bucket bucket, long now) {
        // Tokens are computed from elapsed time on demand. No timer thread tops buckets up.
        return Math.min(capacity, bucket.tokens() + (now - bucket.updatedAt()) / 1e9 * perSecond);
    }

    /** Takes one token. Returns 0 if the request may pass, else milliseconds until it could. */
    long acquire(String client) {
        if (!enabled()) {
            return 0;
        }
        long now = nanoClock.getAsLong();
        long[] waitMillis = {0};
        buckets.compute(client, (key, bucket) -> { // compute() makes check-and-take atomic per client
            double tokens = bucket == null ? capacity : refilled(bucket, now);
            if (tokens >= 1 - EPSILON) { // tolerance: 0.6 + 0.4 is not always exactly 1.0 in floating point
                return new Bucket(Math.max(0, tokens - 1), now);
            }
            waitMillis[0] = Math.max(1, (long) Math.ceil((1 - tokens) / perSecond * 1000 - EPSILON));
            return new Bucket(tokens, now);
        });
        return waitMillis[0];
    }

    /**
     * Drops buckets that have refilled completely. A full bucket behaves exactly like a missing
     * one, so this loses nothing and stops the map from growing with every client ever seen.
     */
    void evictIdle() {
        long now = nanoClock.getAsLong();
        buckets.values().removeIf(bucket -> refilled(bucket, now) >= capacity);
    }

    int trackedClients() {
        return buckets.size();
    }
}
