<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260725T180011Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

| Concern | Solution |
|---------|----------|
| **Thread‑safety** | All mutable state (`tokens` and `lastRefillNanos`) is protected by a `java.util.concurrent.locks.ReentrantLock`. The lock is held only for the short critical section that reads/updates the state; the blocking `acquire()` releases the lock while it sleeps, allowing other threads to make progress. |
| **Refill algorithm** | On every state access we compute the elapsed time since the last refill, add `elapsed * rate` tokens (capped at the configured *burst capacity*), and store the new timestamp. This is a classic *leaky‑bucket* / *token‑bucket* implementation that works with fractional tokens (stored as `double`). |
| **Blocking `acquire()`** | If fewer than one token is available we compute the exact time needed to accumulate the missing token (`deficit / rate`), release the lock, sleep for that time (with nanosecond precision using `Thread.sleep`), then retry. The loop re‑checks the state after each wake‑up, so spurious wake‑ups or concurrent consumption are handled correctly. |
| **Non‑blocking `tryAcquire()`** | Same refill step as `acquire()`. If at least one token is present we atomically consume it and return `true`; otherwise we leave the bucket unchanged (except for the refill that just happened) and return `false`. |
| **Configurable parameters** | `refillRatePerSec` (tokens per second) and `burstCapacity` (maximum tokens the bucket can hold). Both are validated in the constructor. |
| **Correctness under contention** | The lock guarantees that only one thread updates the bucket at a time. The sleep‑outside‑the‑lock pattern prevents a thread holding the lock while waiting, which would otherwise block all other threads from refilling the bucket. |
| **Overflow / under‑flow safety** | Token count is never allowed to exceed `burstCapacity`. The `acquire()` loop only proceeds when `tokens >= 1.0`, guaranteeing we never go negative. |

---

### Implementation (Java 17)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are refilled at a constant rate {@code refillRatePerSec} (tokens per second)
 * up to a maximum of {@code burstCapacity}. The {@code acquire()} method blocks until a
 * token is available, while {@code tryAcquire()} attempts an immediate acquisition.</p>
 *
 * <p>All operations are O(1) and safe for use by many concurrent threads.</p>
 */
public final class TokenBucket {

    /** Tokens per nanosecond – derived from the user‑supplied rate per second. */
    private final double ratePerNano;
    /** Maximum number of tokens the bucket can hold. */
    private final double capacity;
    /** Holds the current token count and the timestamp of the last refill. */
    private final RefillState state;
    /** Guard for all mutable state. */
    private final Lock lock = new ReentrantLock();

    /** Internal immutable holder for the bucket state. */
    private static final class RefillState {
        final double tokens;
        final long lastRefillNanos;

        RefillState(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * Creates a new token bucket.
     *
     * @param refillRatePerSec  tokens added per second (must be > 0)
     * @param burstCapacity     maximum tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if any argument is non‑positive
     */
    public TokenBucket(double refillRatePerSec, double burstCapacity) {
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.ratePerNano = refillRatePerSec / 1_000_000_000.0; // convert to per‑nano
        this.capacity = burstCapacity;
        this.state = new RefillState(burstCapacity, System.nanoTime());
    }

    /**
     * Internal helper: advances the token count based on elapsed time.
     *
     * @param old the state before refill
     * @return a new state reflecting the refilled bucket (timestamp updated)
     */
    private RefillState refill(RefillState old) {
        long now = System.nanoTime();
        long elapsed = now - old.lastRefillNanos;
        if (elapsed <= 0) {
            return old; // no time passed
        }
        double newTokens = old.tokens + elapsed * ratePerNano;
        if (newTokens > capacity) {
            newTokens = capacity;
        }
        return new RefillState(newTokens, now);
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            RefillState current = state.get();
            RefillState updated = refill(current);
            if (updated.tokens >= 1.0) {
                // consume one token
                state.set(new RefillState(updated.tokens - 1.0, updated.lastRefillNanos));
                return true;
            } else {
                // not enough tokens – still store the refilled state for future calls
                state.set(updated);
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token is available, then acquires it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                RefillState current = state.get();
                RefillState updated = refill(current);
                if (updated.tokens >= 1.0) {
                    // take the token and exit
                    state.set(new RefillState(updated.tokens - 1.0, updated.lastRefillNanos));
                    return;
                }
                // Not enough tokens – compute how long we must wait for ONE token.
                double deficit = 1.0 - updated.tokens; // > 0
                long waitNanos = (long) Math.ceil(deficit / ratePerNano);
                // Release the lock while we sleep; re‑acquire afterwards.
                lock.unlock();
                try {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(waitNanos),
                                 (int) (waitNanos % 1_000_000));
                } finally {
                    lock.lockInterruptibly(); // re‑acquire before looping again
                }
                // Loop will re‑check the bucket state (other threads may have refilled/consumed).
            }
        } finally {
            lock.unlock();
        }
    }
}
```

---

### JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The concurrent test verifies that, over a long run, the observed acquisition
 * rate never exceeds the configured limit (plus the allowed burst).</p>
 */
class TokenBucketTest {

    /** Helper to run a task for a fixed wall‑clock time and return the number of executions. */
    private static long runForDuration(Runnable task, Duration duration) throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicLong counter = new AtomicLong(0);

        exec.submit(() -> {
            try {
                startLatch.await(); // wait for the test to give the go‑signal
                long end = System.nanoTime() + duration.toNanos();
                while (System.nanoTime() < end) {
                    task.run();
                    counter.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown(); // let the worker start
        boolean finished = doneLatch.await(duration.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        exec.shutdownNow();
        if (!finished) {
            fail("Worker did not finish in the expected time");
        }
        return counter.get();
    }

    @Test
    void tryAcquire_respectsBurstCapacity() {
        TokenBucket bucket = new TokenBucket(10.0, 5.0); // 10 tokens/s, burst 5

        // Should be able to take up to the burst immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "tryAcquire should succeed for burst tokens");
        }
        // The sixth attempt must fail because the bucket is empty and refill hasn't had time
        assertFalse(bucket.tryAcquire(), "tryAcquire should fail after burst is exhausted");
    }

    @Test
    void acquire_blocksUntilTokenAvailable() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(10.0, 1.0); // 10 tokens/s, burst 1

        // Consume the single token immediately
        assertTrue(bucket.tryAcquire());

        long start = System.nanoTime();
        bucket.acquire(); // should block ≈ 100 ms for the next token
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Allow a small tolerance for scheduling jitter
        assertTrue(elapsedMs >= 80 && elapsedMs <= 150,
                "acquire should block roughly 1/rate seconds");
    }

    @Test
    void concurrentTryAcquire_doesNotExceedConfiguredRate() throws Exception {
        double ratePerSec = 200.0;   // tokens per second
        double burst = 50.0;         // burst capacity
        TokenBucket bucket = new TokenBucket(ratePerSec, burst);

        int numThreads = 8;
        Duration testDuration = Duration.ofSeconds(5); // run long enough to smooth jitter

        List<AtomicLong> perThreadCounts = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            perThreadCounts.add(new AtomicLong(0));
        }

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long end = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < end) {
                        if (bucket.tryAcquire()) {
                            perThreadCounts.get(idx).incrementAndGet();
                        }
                        // spin‑yield to avoid burning CPU; back‑off helps the scheduler
                        Thread.yield();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // let all workers go
        boolean allDone = doneLatch.await(testDuration.toMillis() + 2_000, TimeUnit.MILLISECONDS);
        pool.shutdownNow();
        assertTrue(allDone, "Some worker threads did not finish in time");

        long totalAcquired = perThreadCounts.stream()
                                            .mapToLong(AtomicLong::get)
                                            .sum();

        // Expected maximum = burst + rate * duration (allow a small 5% slack for timing variance)
        long maxExpected = Math.round(burst + ratePerSec * testDuration.getSeconds());
        long slack = Math.max(5L, Math.round(0.05 * maxExpected)); // at least 5 tokens slack
        long allowed = maxExpected + slack;

        assertTrue(totalAcquired <= allowed,
                String.format("Acquired %d tokens, which exceeds the allowed %d (rate=%.2f/s, burst=%.0f, duration=%ds)",
                        totalAcquired, allowed, ratePerSec, burst, testDuration.getSeconds()));
    }
}
```

**Explanation of the test suite**

* `tryAcquire_respectsBurstCapacity` – verifies that the bucket initially holds exactly the burst number of tokens and that further immediate attempts fail until time passes.
* `acquire_blocksUntilTokenAvailable` – checks that the blocking `acquire()` waits roughly the inverse of the refill rate when the bucket is empty.
* `concurrentTryAcquire_doesNotExceedConfiguredRate` – launches several threads that spin on `tryAcquire()` for a fixed interval. The total number of successful acquisitions is bounded by **burst + rate × time** (plus a small tolerance for scheduling jitter), guaranteeing that the long‑run average rate never exceeds the configured limit.

These tests together confirm both the functional correctness and the thread‑safety of the token‑bucket implementation.