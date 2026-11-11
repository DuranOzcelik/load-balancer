package loadbalancer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    private static final long MILLI = 1_000_000;
    private final AtomicLong nanos = new AtomicLong();
    private final RateLimiter limiter = new RateLimiter(5, 10, nanos::get); // burst 5, 10 per second

    @Test
    void allowsBurstThenReportsExactWait() {
        for (int i = 0; i < 5; i++) {
            assertEquals(0, limiter.acquire("a"));
        }
        assertEquals(100, limiter.acquire("a")); // one token takes 100 ms at 10/s
        nanos.addAndGet(60 * MILLI);
        assertEquals(40, limiter.acquire("a")); // a rejected request does not reset the wait
        nanos.addAndGet(40 * MILLI);
        assertEquals(0, limiter.acquire("a"));
        assertTrue(limiter.acquire("a") > 0);
    }

    @Test
    void refillStopsAtCapacity() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire("a");
        }
        nanos.addAndGet(3_600_000 * MILLI); // an hour idle must not bank 36,000 tokens
        for (int i = 0; i < 5; i++) {
            assertEquals(0, limiter.acquire("a"));
        }
        assertTrue(limiter.acquire("a") > 0);
    }

    @Test
    void clientsDoNotShareABucket() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire("a");
        }
        assertTrue(limiter.acquire("a") > 0);
        assertEquals(0, limiter.acquire("b"));
    }

    @Test
    void evictsOnlyBucketsThatAreFullAgain() {
        limiter.acquire("idle");
        nanos.addAndGet(50 * MILLI);
        for (int i = 0; i < 5; i++) {
            limiter.acquire("busy");
        }
        nanos.addAndGet(100 * MILLI); // "idle" has refilled its one token, "busy" has not
        limiter.evictIdle();
        assertEquals(1, limiter.trackedClients());
        assertEquals(0, limiter.acquire("busy"));
        assertTrue(limiter.acquire("busy") > 0, "eviction must not hand a drained client a fresh bucket");
    }

    @Test
    void zeroRateDisablesLimiting() {
        RateLimiter off = new RateLimiter(5, 0, nanos::get);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, off.acquire("a"));
        }
        assertEquals(0, off.trackedClients());
    }
}
