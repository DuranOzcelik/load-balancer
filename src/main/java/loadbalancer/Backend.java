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

    /** Used when a real request could not even connect: stop sending traffic immediately. */
    synchronized void markDown() {
        healthy = false;
        streak = 0;
    }
}
