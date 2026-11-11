package loadbalancer;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalancerTest {
    private final Backend a = new Backend("a", URI.create("http://127.0.0.1:1"));
    private final Backend b = new Backend("b", URI.create("http://127.0.0.1:2"));
    private final Backend c = new Backend("c", URI.create("http://127.0.0.1:3"));

    private List<String> picks(Balancer balancer, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(balancer.pick(Set.of()).name);
        }
        return names;
    }

    @Test
    void roundRobinCycles() {
        Balancer balancer = new Balancer(List.of(a, b, c), Balancer.Algorithm.ROUND_ROBIN);
        assertEquals(List.of("a", "b", "c", "a", "b", "c"), picks(balancer, 6));
    }

    @Test
    void skipsUnhealthyAndExcludedBackends() {
        Balancer balancer = new Balancer(List.of(a, b, c), Balancer.Algorithm.ROUND_ROBIN);
        b.markDown();
        assertFalse(picks(balancer, 6).contains("b"));
        assertEquals("c", balancer.pick(Set.of(a)).name);
        assertNull(balancer.pick(Set.of(a, c)));
    }

    @Test
    void leastConnectionsPrefersIdleBackend() {
        Balancer balancer = new Balancer(List.of(a, b, c), Balancer.Algorithm.LEAST_CONNECTIONS);
        a.inFlight.set(5);
        b.inFlight.set(1);
        c.inFlight.set(3);
        assertEquals(List.of("b", "b", "b"), picks(balancer, 3));
    }

    @Test
    void leastConnectionsBreaksTiesInRotation() {
        Balancer balancer = new Balancer(List.of(a, b, c), Balancer.Algorithm.LEAST_CONNECTIONS);
        assertEquals(List.of("a", "b", "c"), picks(balancer, 3)); // not "a" three times
    }

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    @Test
    void leastLatencyAvoidsTheSlowBackend() {
        AtomicLong now = new AtomicLong(SECOND);
        Balancer balancer = new Balancer(List.of(a, b, c), Balancer.Algorithm.LEAST_LATENCY, new Random(7), now::get);
        a.recordLatency(10 * MILLI, now.get());
        b.recordLatency(12 * MILLI, now.get());
        c.recordLatency(120 * MILLI, now.get());
        List<String> names = picks(balancer, 300);
        assertFalse(names.contains("c"), "whichever backend c is compared with is faster");
        assertTrue(names.contains("a") && names.contains("b"));
    }

    @Test
    void leastLatencyTriesAnUnmeasuredBackendAndWeighsTheQueue() {
        AtomicLong now = new AtomicLong(SECOND);
        Balancer balancer = new Balancer(List.of(a, b), Balancer.Algorithm.LEAST_LATENCY, new Random(7), now::get);
        a.recordLatency(10 * MILLI, now.get());
        assertEquals("b", balancer.pick(Set.of()).name, "b has never been measured, so it gets a first request");
        b.recordLatency(10 * MILLI, now.get());
        a.inFlight.set(4); // same speed, but a already has four requests waiting
        assertEquals("b", balancer.pick(Set.of()).name);
    }

    @Test
    void latencyEstimateSmoothsSamplesAndFadesWhenIdle() {
        a.recordLatency(100 * MILLI, SECOND);
        assertEquals(100.0, a.latencyEstimate(SECOND), 0.001);
        a.recordLatency(200 * MILLI, SECOND);
        assertEquals(130.0, a.latencyEstimate(SECOND), 0.001, "one slow sample moves the average only part of the way");
        assertEquals(65.0, a.latencyEstimate(6 * SECOND), 0.001, "half-life of five seconds");
        assertTrue(a.latencyEstimate(120 * SECOND) < 0.01, "a long-idle backend looks cheap again and gets re-tried");
    }

    @Test
    void healthStateNeedsConsecutiveResultsToFlip() {
        a.report(false, 2);
        assertTrue(a.healthy(), "one failed check is not enough");
        a.report(true, 2);
        a.report(false, 2);
        assertTrue(a.healthy(), "a success in between resets the count");
        a.report(false, 2);
        assertFalse(a.healthy());
        a.report(true, 2);
        assertFalse(a.healthy(), "one good check is not enough to come back");
        a.report(true, 2);
        assertTrue(a.healthy());
    }
}
