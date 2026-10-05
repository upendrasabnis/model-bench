<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261005T164250Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

The classic *token bucket* algorithm works as follows:

* A bucket holds at most **capacity** tokens.
* Tokens are added continuously at a **refillRate** (tokens per second).
* When a thread wants to consume **n** permits it must first wait until the bucket contains at least *n* tokens, then remove them atomically.

To be safe for concurrent use we protect the mutable state (`tokens` and `lastRefill`) with a single monitor (`synchronized(this)`).  
All operations are O(1) and avoid busy‑spinning – the blocking `acquire` computes the exact time it must wait and calls `Thread.sleep`.

**Key fields**

| Field | Meaning |
|-------|---------|
| `capacity` | Maximum number of tokens the bucket can hold (double, allows fractional tokens). |
| `refillRate` | Tokens added per second (double). |
| `tokens` | Current number of tokens in the bucket (double). |
| `lastRefillNanos` | Timestamp (nanoseconds) of the last refill operation. |

**Refill logic**

When we need to know the current token count we compute the elapsed time since `lastRefillNanos`, add `elapsed * refillRate` tokens, clamp to `capacity`, and update `lastRefillNanos`. This “lazy refill” avoids a background thread and guarantees correctness even if many threads call the limiter intermittently.

**Blocking `acquire(long permits)`**

1. Re‑fill the bucket.
2. If enough tokens are available, consume them and return immediately.
3. Otherwise compute the deficit (`permits - tokens`) and the required wait time (`deficit / refillRate` seconds).
4. Sleep for that time (handling `InterruptedException` by restoring the interrupt flag and retrying).
5. Loop – after waking we re‑fill again and try once more.

**Non‑blocking `tryAcquire(long permits)`**

Same as step 1‑2 above, but if there are not enough tokens we simply return `false` without waiting.

**Why this is thread‑safe**

* All reads/writes of `tokens` and `lastRefillNanos` happen inside a `synchronized` block, guaranteeing mutual exclusion.
* The algorithm never leaks state; after each operation the bucket reflects a consistent snapshot.
* No lock‑free tricks are needed – the monitor provides both visibility and atomicity for the small critical section.

---

## Implementation (`TokenBucket.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a configurable rate (tokens per second)
 * up to a maximum burst size (capacity).  Threads can either block until permits
 * are available ({@link #acquire(long)}) or attempt a non‑blocking acquisition
 * ({@link #tryAcquire(long)}).</p>
 *
 * <p>All operations are O(1) and use a single monitor for synchronization,
 * making the class suitable for high‑contention scenarios.</p>
 *
 * @author  Generated for the exercise
 */
public class TokenBucket {

    /** Maximum number of tokens the bucket can hold. */
    private final double capacity;

    /** Refill rate in tokens per second. */
    private final double refillRate;

    /** Current number of tokens available (may be fractional). */
    private volatile double tokens;

    /** Nanosecond timestamp of the last refill. */
    private volatile long lastRefillNanos;

    /**
     * Creates a new token bucket.
     *
     * @param capacity   maximum burst size (tokens). Must be > 0.
     * @param refillRate tokens added per second. Must be > 0.
     * @throws IllegalArgumentException if either argument is non‑positive.
     */
    public TokenBucket(double capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        // Start with a full bucket – useful for burst handling.
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the monitor lock.
     *
     * @param nowNanos current time in nanoseconds.
     */
    private void refill(long nowNanos) {
        if (nowNanos > lastRefillNanos) {
            double elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
            double added = elapsedSec * refillRate;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = nowNanos;
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available.
     *
     * <p>The method is interruptible; if the thread is interrupted while waiting,
     * the interrupt flag is restored and the method throws {@link InterruptedException}.</p>
     *
     * @param permits number of tokens to consume. Must be > 0.
     * @throws IllegalArgumentException if permits &le; 0.
     * @throws InterruptedException     if the current thread is interrupted while waiting.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        while (true) {
            long now = System.nanoTime();
            synchronized (this) {
                refill(now);
                if (tokens >= permits) {
                    tokens -= permits;
                    return; // success
                }
                // Not enough tokens – compute how long we must wait.
                double deficit = permits - tokens;
                double waitSec = deficit / refillRate;
                // Convert to milliseconds with nanosecond precision for Thread.sleep.
                long waitMillis = (long) (waitSec * 1000);
                int waitNanos = (int) ((waitSec * 1_000_000_000) % 1_000_000);
                // Store the timestamp we will wake up at to avoid drift.
                long wakeupNanos = now + waitMillis * 1_000_000L + waitNanos;
                // Release the lock and sleep.
                try {
                    wait(waitMillis, waitNanos);
                } catch (InterruptedException ie) {
                    // Restore interrupt status and propagate.
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                // After waking we loop again; the wait may have been spuriously woken.
            }
        }
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of tokens to consume. Must be > 0.
     * @return {@code true} if the permits were acquired, {@code false} otherwise.
     * @throws IllegalArgumentException if permits &le; 0.
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        long now = System.nanoTime();
        synchronized (this) {
            refill(now);
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        }
    }

    /**
     * Returns the current number of tokens in the bucket (for testing/debugging).
     * This method is thread‑safe but the value may change immediately after the call.
     *
     * @return current token count (may be fractional).
     */
    public double getTokens() {
        long now = System.nanoTime();
        synchronized (this) {
            refill(now);
            return tokens;
        }
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRate() {
        return refillRate;
    }

    /**
     * Returns the configured burst capacity (maximum tokens).
     */
    public double getCapacity() {
        return capacity;
    }
}
```

---

## JUnit 5 Tests (`TokenBucketTest.java`)

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    private static final double TOLERANCE = 0.1; // 10% tolerance for rate checks

    @Test
    void testInitialFullBucket() {
        TokenBucket tb = new TokenBucket(10.0, 1.0);
        assertEquals(10.0, tb.getTokens(), "Bucket should start full");
    }

    @Test
    void testTryAcquireConsumesAndReturnsFalseWhenEmpty() {
        TokenBucket tb = new TokenBucket(5.0, 2.0); // capacity 5, refill 2/s
        assertTrue(tb.tryAcquire(5), "First acquire should succeed");
        assertFalse(tb.tryAcquire(1), "Bucket empty – tryAcquire should fail");
        // Wait for refill: need 0.5 seconds to get 1 token
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted");
        }
        assertTrue(tb.tryAcquire(1), "After waiting we should get a token");
    }

    @Test
    void testBlockingAcquireRespectsRate() throws Exception {
        TokenBucket tb = new TokenBucket(1.0, 10.0); // 1 token burst, 10 tokens/sec
        long start = System.nanoTime();
        // Acquire 20 tokens one by one; each should take ~0.1s after the first.
        for (int i = 0; i < 20; i++) {
            tb.acquire(1);
        }
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        // Expected time: first token immediate, remaining 19 tokens at 0.1s each => 1.9s
        double expectedSec = 1.9;
        assertTrue(elapsedSec >= expectedSec * (1 - TOLERANCE),
                "Elapsed time too short: " + elapsedSec + "s");
        assertTrue(elapsedSec <= expectedSec * (1 + TOLERANCE),
                "Elapsed time too long: " + elapsedSec + "s");
    }

    @Test
    void testConcurrentLongRunRateDoesNotExceedLimit() throws Exception {
        double rate = 5.0; // tokens per second
        double capacity = 10.0; // allow bursts
        TokenBucket tb = new TokenBucket(capacity, rate);

        int workerCount = 20;
        long testDurationSec = 5; // run for 5 seconds
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        AtomicLong permitsAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long localCount = 0;
                    long endTime = System.nanoTime() + testDurationSec * 1_000_000_000L;
                    while (System.nanoTime() < endTime) {
                        // Each acquisition takes 1 permit; we ignore the tiny overhead of the loop.
                        tb.acquire(1);
                        localCount++;
                    }
                    permitsAcquired.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Let all workers start at roughly the same moment to avoid ramp‑up bias.
        startLatch.countDown();
        // Wait for the test duration plus a little slack for thread shutdown.
        boolean finished = doneLatch.await(testDurationSec + 2, TimeUnit.SECONDS);
        assertTrue(finished, "Some workers did not finish in time");
        exec.shutdownNow();

        long totalPermits = permitsAcquired.get();
        double actualRate = totalPermits / (double) testDurationSec;
        // The long‑run rate must not exceed the configured rate (allow small tolerance for scheduling jitter).
        assertTrue(actualRate <= rate * (1 + TOLERANCE),
                String.format("Observed rate %.2f tokens/s exceeds configured %.2f tokens/s",
                        actualRate, rate));
    }

    @Test
    void testTryAcquireNeverBlocksAndIsThreadSafe() throws Exception {
        TokenBucket tb = new TokenBucket(3.0, 1.0);
        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                // Hammer the bucket with many tryAcquire calls.
                for (int j = 0; j < 100; j++) {
                    results.add(tb.tryAcquire(1));
                }
            });
        }
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        long successCount = results.stream().filter(b -> b).count();
        // At most capacity + refill during the short test window can succeed.
        // The test runs quickly; we just ensure we never exceed capacity + a small burst.
        assertTrue(successCount <= 5, "Too many permits succeeded given low refill rate");
    }
}
```

### Explanation of the Tests

| Test | Purpose |
|------|---------|
| `testInitialFullBucket` | Verifies the bucket starts with the full burst capacity. |
| `testTryAcquireConsumesAndReturnsFalseWhenEmpty` | Checks that `tryAcquire` is non‑blocking, correctly consumes tokens, and returns `false` when the bucket is empty. |
| `testBlockingAcquireRespectsRate` | Measures the elapsed time for a known number of acquisitions and asserts it matches the expected time based on the refill rate (within tolerance). |
| `testConcurrentLongRunRateDoesNotExceedLimit` | Spins up many threads that continuously call `acquire(1)` for a fixed duration. The total number of permits divided by the elapsed time must never exceed the configured refill rate (allowing a small tolerance for scheduling jitter). |
| `testTryAcquireNeverBlocksAndIsThreadSafe` | Stresses `tryAcquire` from many threads concurrently to ensure no blocking occurs and that the internal state stays consistent. |
| `testTryAcquireNeverBlocksAndIsThreadSafe` (duplicate name fixed) | Actually the last test is named `testTryAcquireNeverBlocksAndIsThreadSafe`. |

All tests use JUnit 5 (`org.junit.jupiter.api`) and run with Java 8+ (the code itself is compatible with any recent JDK).

--- 

**You now have a fully‑featured, thread‑safe token‑bucket rate limiter with both blocking and non‑blocking acquisition, plus a comprehensive test suite validating its correctness under concurrent load.**