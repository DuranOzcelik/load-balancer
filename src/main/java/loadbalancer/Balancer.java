package loadbalancer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Chooses which backend gets the next request. */
final class Balancer {
    enum Algorithm { ROUND_ROBIN, LEAST_CONNECTIONS, LEAST_LATENCY }

    final List<Backend> backends;
    volatile Algorithm algorithm;
    private final AtomicInteger cursor = new AtomicInteger();
    private final Random random;
    private final LongSupplier nanoClock;

    Balancer(List<Backend> backends, Algorithm algorithm) {
        this(backends, algorithm, new Random(), System::nanoTime);
    }

    Balancer(List<Backend> backends, Algorithm algorithm, Random random, LongSupplier nanoClock) { // for tests
        this.backends = List.copyOf(backends);
        this.algorithm = algorithm;
        this.random = random;
        this.nanoClock = nanoClock;
    }

    /** Returns a healthy backend that is not in `excluded`, or null if there is none. */
    Backend pick(Set<Backend> excluded) {
        int count = backends.size();
        // The scan starts one position further on every call. For round robin that is the whole
        // algorithm; for least connections it breaks ties fairly instead of always favouring
        // the first server in the list.
        int start = Math.floorMod(cursor.getAndIncrement(), count);
        Algorithm current = algorithm;
        Backend best = null;
        List<Backend> usable = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Backend candidate = backends.get((start + i) % count);
            if (!candidate.healthy() || excluded.contains(candidate)) {
                continue;
            }
            if (current == Algorithm.ROUND_ROBIN) {
                return candidate;
            }
            usable.add(candidate);
            if (best == null || candidate.inFlight.get() < best.inFlight.get()) {
                best = candidate;
            }
        }
        return current == Algorithm.LEAST_LATENCY ? fasterOfTwo(usable) : best;
    }

    /**
     * "Power of two choices": compare two random backends and take the one with the lower
     * score. Always taking the single best looks better on paper, but every request that
     * arrives in the same instant sees the same winner and piles onto it. Two random
     * candidates spread that herd while still avoiding slow servers.
     */
    private Backend fasterOfTwo(List<Backend> usable) {
        if (usable.size() < 2) {
            return usable.isEmpty() ? null : usable.get(0);
        }
        int first;
        int second;
        synchronized (random) {
            first = random.nextInt(usable.size());
            second = random.nextInt(usable.size() - 1);
        }
        if (second >= first) {
            second++; // skip over `first`, so the two picks are always different
        }
        long now = nanoClock.getAsLong();
        Backend a = usable.get(first);
        Backend b = usable.get(second);
        return b.score(now) < a.score(now) ? b : a;
    }
}
