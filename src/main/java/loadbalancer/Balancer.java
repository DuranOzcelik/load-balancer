package loadbalancer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Chooses which backend gets the next request. */
final class Balancer {
    enum Algorithm { ROUND_ROBIN, LEAST_CONNECTIONS }

    final List<Backend> backends;
    volatile Algorithm algorithm;
    private final AtomicInteger cursor = new AtomicInteger();

    Balancer(List<Backend> backends, Algorithm algorithm) {
        this.backends = List.copyOf(backends);
        this.algorithm = algorithm;
    }

    /** Returns a healthy backend that is not in `excluded`, or null if there is none. */
    Backend pick(Set<Backend> excluded) {
        int count = backends.size();
        // The scan starts one position further on every call. For round robin that is the whole
        // algorithm; for least connections it breaks ties fairly instead of always favouring
        // the first server in the list.
        int start = Math.floorMod(cursor.getAndIncrement(), count);
        Backend best = null;
        for (int i = 0; i < count; i++) {
            Backend candidate = backends.get((start + i) % count);
            if (!candidate.healthy() || excluded.contains(candidate)) {
                continue;
            }
            if (algorithm == Algorithm.ROUND_ROBIN) {
                return candidate;
            }
            if (best == null || candidate.inFlight.get() < best.inFlight.get()) {
                best = candidate;
            }
        }
        return best;
    }
}
