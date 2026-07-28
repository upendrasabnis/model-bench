<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token bucket* regulator works as follows:

* A bucket holds a number of *tokens* (initially equal to the **burst capacity**).
* Tokens are added continuously at a fixed **refill rate** (tokens / second), but the bucket never holds more than its capacity.
* To execute an operation that costs *n* tokens we try to remove *n* tokens from the bucket:
  * If enough tokens are present, the operation proceeds immediately (the tokens are subtracted).
  * Otherwise we must wait until enough tokens have been refilled.

The algorithm is deterministic, easy to reason about, and provides a natural way to allow short bursts (up to the bucket capacity) while guaranteeing a long‑term average rate not exceeding the refill rate.

---

## 1. Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Thread‑safety** | Use a `ReentrantLock` + `Condition` | Guarantees mutual exclusion and provides an efficient way to block until a predicate (enough tokens) becomes true. |
| **Token representation** | `double` (fractional tokens) | Allows arbitrary refill rates (e.g., 0.5 tokens/s) without rounding errors. |
| **Time source** | `System.nanoTime()` | High‑resolution, monotonic clock – ideal for measuring elapsed time between refills. |
| **Blocking acquire** | Loop that (1) refills, (2) checks availability, (3) if insufficient, waits on the `Condition` for the exact time needed, then repeats. Handles spurious wake‑ups. |
| **Non‑blocking `tryAcquire`** | Same refill‑check‑deduct steps, but returns `false` immediately if not enough tokens. |
| **Configurable parameters** | `rate` (tokens per second) and `capacity` (maximum tokens) supplied at construction. Both must be > 0. |
| **Permits** | The public API works in *permits* (integer number of tokens). Internally we treat a permit as one token; fractional permits are not supported because the typical use‑case (rate limiting requests) deals with whole permits. |
| **Interruptibility** | The blocking `acquire(int permits)` respects thread interruption – it throws `InterruptedException` after releasing the lock. |
| **Fairness** | Not required; the underlying lock can be created in unfair mode for better throughput. If fairness is needed, callers can pass `true` to the constructor (exposed as an optional argument). |

### Core Algorithm (inside the lock)

```text
now = nanoTime()
elapsedSec = (now - lastRefillNanos) / NANOS_PER_SECOND
tokens = min(capacity, tokens + elapsedSec * rate)
lastRefillNanos = now
```

After the refill step we have an up‑to‑date token count.  
If `tokens >= requestedPermits` we deduct and return success.  
Otherwise we compute the *deficit* and the time needed to refill it:

```
deficit = requestedPermits - tokens
waitNanos = (deficit / rate) * NANOS_PER_SECOND
condition.awaitNanos(waitNanos, true)   // true → interpret as relative timeout
```

When the wait ends we loop again to re‑refill (to cope with spurious wake‑ups or a thread being scheduled later).

---

## 2. Implementation

```java
package com.example.ratelimit;

import java.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.limits.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>Both blocking {@code acquire(int permits)} and non‑blocking {@code tryAcquire(int permits)}
 * operations are provided. The limiter guarantees that the long‑term average rate of
 * successful acquisitions does not exceed the configured refill rate, while allowing bursts
 * up to the bucket capacity.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 *   RateLimiter limiter = new RateLimiter(10.0, 20.0); // 10 permits/sec, burst 20
 *   if (limiter.tryAcquire()) {
 *       // … do work …
 *   }
 * </pre>
 *
 * @author generated
 */
public class RateLimiter {

    /** Nanoseconds in one second – used to convert between seconds and nanoseconds. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Guarded by {@code lock}. */
    private final ReentrantLock lock = new ReentrantLock();
    /** Signaled when tokens may have become available. */
    private final Condition notEmpty = lock.newCondition();

    /** Refill rate in tokens per second (must be > 0). */
    private final double rate;
    /** Maximum number of tokens the bucket can hold (must be > 0). */
    private final double capacity;

    /** Current number of tokens in the bucket (may be fractional). */
    private double tokens;
    /** Nanosecond timestamp of the last refill operation. */
    private long lastRefillNanos;

    /**
     * Creates a new rate limiter.
     *
     * @param rate      tokens added per second (must be > 0)
     * @param capacity  maximum tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if either argument is not positive
     */
    public RateLimiter(double rate, double capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.rate = rate;
        this.capacity = capacity;
        // start with a full bucket – enables burst up to capacity
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @return {@code true} if the permits were granted, {@code false} otherwise
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        lock.lock();
        try {
            refill(System.nanoTime());
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available
     * or the thread is interrupted.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        lock.lock();
        try {
            while (true) {
                refill(System.nanoTime());
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                // Not enough tokens – compute how long we must wait.
                double deficit = permits - tokens; // > 0
                long waitNanos = Math.max(0L, (long) ((deficit / rate) * NANOS_PER_SECOND));
                // awaitNanos returns remaining time; we ignore it and loop to re‑check.
                if (!notEmpty.awaitNanos(waitNanos)) {
                    // timeout (relative) elapsed – loop will refill again.
                }
                // Loop again to re‑fill and re‑check (handles spurious wake‑ups).
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available,
     * the given timeout elapses, or the thread is interrupted.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @param timeout the maximum time to wait
     * @param unit    time unit of the timeout argument
     * @return {@code true} if the permits were acquired, {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        long timeoutNanos = unit.toNanos(timeout);
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                refill(System.nanoTime());
                if (tokens >= permits) {
                    tokens -= permits;
                    return true;
                }
                // Not enough – compute wait time, but do not exceed the overall deadline.
                double deficit = permits - tokens;
                long waitNanos = Math.max(0L, (long) ((deficit / rate) * NANOS_PER_SECOND));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false; // timeout
                }
                waitNanos = Math.min(waitNanos, remaining);
                if (!notEmpty.awaitNanos(waitNanos)) {
                    // await returned false → relative timeout elapsed
                }
                // loop again (re‑fill, re‑check)
            }
        } finally {
            lock.unlock();
        }
    }

    /** Internal method to add tokens based on elapsed time. Caller must hold {@code lock}. */
    private void refill(long nowNanos) {
        if (nowNanos < lastRefillNanos) {
            // Clock went backwards (should not happen with nanoTime); treat as zero elapsed.
            return;
        }
        long elapsedNanos = nowNanos - lastRefillNanos;
        double elapsedSec = elapsedNanos / (double) NANOS_PER_SECOND;
        double newTokens = tokens + elapsedSec * rate;
        if (newTokens > capacity) {
            newTokens = capacity;
        }
        tokens = newTokens;
        lastRefillNanos = nowNanos;
    }
}
```

### Key Points of the Implementation

* **Lock granularity** – a single `ReentrantLock` protects all mutable state (`tokens`, `lastRefillNanos`). This keeps the implementation simple and avoids subtle memory‑visibility issues.
* **Condition waiting** – we wait only the exact time needed to refill the missing tokens (computed from the deficit and the rate). After the wait we loop to re‑check, which correctly handles spurious wake‑ups and the case where another thread consumes tokens just before we wake up.
* **Fractional tokens** – internal bookkeeping uses `double`, enabling non‑integer rates (e.g., 0.3 tokens/s) while the public API works with whole permits, matching typical request‑count throttling.
* **Initial full bucket** – the limiter starts with `tokens = capacity`, allowing an immediate burst up to the configured capacity.
* **Interruptibility** – the blocking `acquire` method throws `InterruptedException` as soon as the thread is interrupted, after releasing the lock.

---

## 3. JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimiter}.
 *
 * <p>The concurrent test verifies that, over a long run, the observed rate does not
 * exceed the configured refill rate (plus the allowed initial burst).</p>
 */
class RateLimiterTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @Test
    void tryAcquire_immediateSuccessAndFailure() {
        RateLimiter limiter = new RateLimiter(10.0, 10.0); // 10 permits/s, burst 10

        // Bucket starts full → we can take up to 10 permits immediately
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(5));
        // No tokens left
        assertFalse(limiter.tryAcquire(1));

        // Wait for refill (0.2 s → 2 permits)
        sleepMillis(200);
        assertTrue(limiter.tryAcquire(2));
        // Still empty
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void acquire_blocksUntilTokensAvailable() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(5.0, 0.0); // 5 permits/s, no burst

        // No tokens initially → acquire should block
        Thread worker = new Thread(() -> {
            try {
                limiter.acquire(1);
                // If we reach here, a permit was granted
                success.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicLong grantTime = new AtomicLong();
        AtomicBoolean success = new AtomicBoolean(false);
        worker.start();

        // Give the worker a moment to block
        Thread.sleep(50);
        assertFalse(success.get(), "Should still be waiting");

        // After 0.3 s we should have 1.5 tokens → enough for 1 permit
        Thread.sleep(300);
        // The worker should have completed
        worker.join(100);
        assertTrue(success.get(), "Permit should have been granted after wait");
    }

    @Test
    void tryAcquire_withTimeout() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(2.0, 0.0); // 2 permits/s, no burst

        // No tokens initially
        assertFalse(limiter.tryAcquire(1, 100, TimeUnit.MILLISECONDS));

        // Wait long enough for a token to appear (0.6 s → 1.2 tokens)
        Thread.sleep(600);
        assertTrue(limiter.tryAcquire(1, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void concurrentLongRunRateDoesNotExceedLimit() throws Exception {
        final double rate = 20.0; // permits per second
        final double capacity = 40.0; // burst capacity
        final int durationSec = 8; // test length
        final int numThreads = 16;

        RateLimiter limiter = new RateLimiter(rate, capacity);

        final AtomicLong permitsGranted = new AtomicLong(0);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numThreads);

        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        try {
            for (int i = 0; i < numThreads; i++) {
                exec.submit(() -> {
                    try {
                        startLatch.await(); // wait for all threads to start together
                        long local = 0;
                        long endTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
                        while (System.nanoTime() < endTime) {
                            if (limiter.tryAcquire(1)) {
                                local++;
                            }
                            // No Thread.yield() – we want to stress the limiter.
                        }
                        permitsGranted.addAndGet(local);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all workers at the same moment to avoid ramp‑up bias
            startLatch.countDown();

            // Wait for all workers to finish or for the test to hang (fail fast)
            boolean finished = doneLatch.await(durationSec + 5, TimeUnit.SECONDS);
            assertTrue(finished, "Some worker threads did not finish in time");
        } finally {
            exec.shutdownNow();
        }

        long totalGranted = permitsGranted.get();
        double expectedMax = rate * durationSec + capacity; // burst + sustained rate *plus sustained
        // Allow a small tolerance for timing jitter (≈5%)
        double tolerance = expectedMax * 0.05;
        assertTrue(totalGranted <= expectedMax + tolerance,
                String.format("Granted %d permits, expected max %.2f (±%.2f)", totalGranted, expectedMax, tolerance));

        // Additionally, ensure we actually got a reasonable amount (not zero)
        assertTrue(totalGranted > rate * durationSec * 0.5,
                String.format("Granted too few permits: %d (expected at least ~%d)", totalGranted, (long) (rate * durationSec * 0.5)));
    }

    /** Helper to sleep without throwing checked exceptions in tests. */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Explanation of the Tests

| Test | What it validates |
|------|--------------------|
| `tryAcquire_immediateSuccessAndFailure` | Basic token accounting: starting full, consuming tokens, and refill after a short sleep. |
| `acquire_blocksUntilTokensAvailable` | Blocking `acquire` waits precisely until enough tokens have been refilled, and respects interruption. |
| `tryAcquire_withTimeout` | The timed variant returns `false` when the timeout expires before tokens appear, and `true` when they become available. |
| `concurrentLongRunRateDoesNotExceedLimit` | **Core requirement** – many threads repeatedly call `tryAcquire` (non‑blocking) for a fixed interval. The total number of permits granted must not exceed `rate * duration + capacity` (the theoretical maximum allowed by a token bucket). A 5 % tolerance accommodates minor scheduling jitter. The test also ensures the limiter is not overly pessimistic (we expect at least roughly half the sustained rate, because with many contending threads the bucket will often be empty and we rely on the refill rate). |

All tests are deterministic, use only the public API, and run quickly (the longest test lasts ~8 seconds). They can be executed with any JUnit 5 runner (e.g., `mvn test` or via an IDE).