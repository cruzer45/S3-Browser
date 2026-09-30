package com.s3explorer.util;

/**
 * A simple shared token bucket used to cap total transfer bandwidth. A limit of zero
 * disables throttling.
 */
public final class RateLimiter {

    private volatile long bytesPerSecond;
    private double available;
    private long lastRefillNanos = System.nanoTime();

    public RateLimiter(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }

    public void setBytesPerSecond(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }

    public long getBytesPerSecond() {
        return bytesPerSecond;
    }

    /** Blocks until {@code bytes} may be transferred under the current limit. */
    public void acquire(long bytes) throws InterruptedException {
        long limit = bytesPerSecond;
        if (limit <= 0 || bytes <= 0) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            refill(limit);
            available -= bytes;
            waitNanos = available >= 0 ? 0 : (long) (-available / limit * 1_000_000_000L);
        }
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
        }
    }

    private void refill(long limit) {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        // Allow at most one second of burst.
        available = Math.min(limit, available + elapsed * limit);
    }
}
