package loadbalancer;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
