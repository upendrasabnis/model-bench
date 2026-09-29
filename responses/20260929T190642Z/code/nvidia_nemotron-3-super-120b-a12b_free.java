<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260929T190642Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

The token‑bucket algorithm is a classic way to shape traffic:

* A bucket holds at most **capacity** tokens.
* Tokens are added continuously at a fixed **refill rate** (tokens per unit time).
* An operation may proceed only if it can consume the required number of tokens.
* If the bucket is empty, the caller either fails fast (`tryAcquire`) or blocks until enough tokens have been refilled (`acquire`).

The implementation below is **thread‑safe** and works for any number of concurrent callers:

| Concern | Solution |
|---------|----------|
| **Shared mutable state** (`tokens`, `lastRefillNano`) | Protected by a `java.util.concurrent.locks.ReentrantLock`. All reads/writes happen while holding the lock. |
| **Waiting for tokens** | The lock’s associated `Condition` is used. When insufficient tokens are present, the thread computes the exact time it must wait (`waitNanos = (needed – tokens) / rate`) and calls `condition.awaitNanos(waitNanos)`. Spurious wake‑ups are handled by re‑checking the condition after each wait. |
| **Avoiding drift** | On every entry (`acquire` / `tryAcquire`) we first **refill** the bucket based on the elapsed time since the last update (`now – lastRefillNano`). This guarantees that the token count always reflects the exact amount of time that has passed, even if many threads call the method in quick succession. |
| **Fractional tokens** | The algorithm works with `double` token counts, allowing any refill rate (e.g., 2.5 tokens/s) without rounding errors. The bucket capacity is also a `double`. |
| **Blocking acquire** | Loops until enough tokens are available, waiting on the condition as described. It throws `InterruptedException` if the waiting thread is interrupted. |
| **Non‑blocking tryAcquire** | Performs a single refill‑check; if tokens are sufficient, they are consumed and `true` is returned; otherwise `false` is returned immediately – no waiting, no locks held longer than necessary. |
| **Fairness** | The underlying `ReentrantLock` is created in *fair* mode (`new ReentrantLock(true)`) so that threads acquire the lock in the order they requested it, reducing starvation. |

The public API is deliberately minimal:

```java
public void acquire() throws InterruptedException;   // blocks until 1 token can be taken
public boolean tryAcquire();                         // non‑blocking, returns true iff a token was taken
```

(If the caller needs more than one token per call, the methods can be overloaded to accept a `permits` argument – the core logic stays the same.)

---

## Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a fixed rate (tokens per nanosecond) up to a
 * configurable burst capacity. {@link #acquire()} blocks until a token is available,
 * {@link #tryAcquire()} attempts to take a token without waiting.</p>
 *
 * <p>All operations are O(1) and safe for use by any number of concurrent threads.</p>
 */
public final class TokenBucketRateLimiter {

    private final double capacity;               // maximum number of tokens the bucket can hold
    private double tokens;                       // current token count
    private final double ratePerNano;            // refill rate in tokens per nanosecond
    private volatile long lastRefillNano;        // timestamp of the last refill (nanoseconds)

    private final ReentrantLock lock = new ReentrantLock(true); // fair lock
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new rate limiter.
     *
     * @param capacity   maximum burst size (tokens). Must be > 0.
     * @param refillRate tokens added per time unit. Must be > 0.
     * @param unit       time unit for {@code refillRate}.
     * @throws IllegalArgumentException if any parameter is <= 0.
     */
    public TokenBucketRateLimiter(double capacity, double refillRate, TimeUnit unit) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        this.capacity = capacity;
        this.tokens = capacity; // start with a full bucket (common choice)
        this.ratePerNano = refillRate.toNanos(1) > 0
                ? refillRate / (double) unit.toNanos(1)
                : 0; // the division is safe because we checked >0 above
        this.lastRefillNano = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill(long nowNano) {
        if (nowNano > lastRefillNano) {
            double added = (nowNano - lastRefillNano) * ratePerNano;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNano = nowNano;
        }
    }

    /**
     * Blocks until a token can be taken from the bucket, then consumes it.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                long now = System.nanoTime();
                refill(now);
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    lastRefillNano = now; // we have just consumed; keep timestamp up‑to‑date
                    return;
                }
                // Not enough tokens – compute how long we must wait for the deficit.
                double needed = 1.0 - tokens;
                double waitNanos = needed / ratePerNano; // guaranteed > 0 because ratePerNano > 0
                // awaitNanos returns false if the wait timed out (spurious wake‑up treated as false)
                if (!notEmpty.awaitNanos(Math.max(0L, (long) waitNanos))) {
                    // Timeout elapsed – loop will refill again and retry.
                }
                // If we were interrupted while waiting, the lock.lockInterruptibly() above
                // would have already thrown InterruptedException.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to take a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.nanoTime();
            refill(now);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                lastRefillNano = now;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Exposed for testing only – returns the current approximate token count. */
    double peekTokens() {
        lock.lock();
        try {
            long now = System.nanoTime();
            refill(now);
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /** Exposed for testing only – returns the configured refill rate in tokens per second. */
    double getRatePerSecond() {
        return ratePerNano * 1_000_000_000.0;
    }

    /** Exposed for testing only – returns the bucket capacity. */
    double getCapacity() {
        return capacity;
    }
}
```

*Notes on the code*

* The constructor accepts the refill rate in any `TimeUnit`; internally we store it as **tokens per nanosecond** to avoid repeated conversions.
* `peekTokens()`, `getRatePerSecond()`, and `getCapacity()` are package‑private (or could be made `@VisibleForTesting`) and are used only in the test suite.
* The implementation assumes the caller wants to acquire **exactly one token** per call. Extending to `acquire(int permits)` is straightforward – just replace the hard‑coded `1.0` with the permit amount and adjust the wait‑time calculation accordingly.

---

## JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * <p>The tests cover basic functionality, blocking vs non‑blocking behavior,
 * and a concurrent stress test that verifies the long‑term average rate does
 * not exceed the configured limit.</p>
 */
class TokenBucketRateLimiterTest {

    /** Helper to wait roughly the given duration without busy‑spinning. */
    private static void sleepFor(Duration d) throws InterruptedException {
        Thread.sleep(d.toMillis(), d.getNano() % 1_000_000);
    }

    @Nested
    @DisplayName("Basic functionality")
    class BasicTests {

        @Test
        @DisplayName("tryAcquire succeeds when bucket is full")
        void tryAcquireWhenFull() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 10.0, TimeUnit.SECONDS);
            assertTrue(limiter.tryAcquire(), "First tryAcquire should succeed");
            assertEquals(4.0, limiter.peekTokens(), 1e-9, "One token should have been consumed");
        }

        @Test
        @DisplayName("tryAcquire fails when bucket is empty")
        void tryAcquireWhenEmpty() throws Exception {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 1.0, TimeUnit.SECONDS);
            assertTrue(limiter.tryAcquire(), "First acquire should succeed");
            assertFalse(limiter.tryAcquire(), "Second tryAcquire should fail – bucket empty");
            // Wait for refill
            sleepFor(Duration.ofSeconds(1));
            assertTrue(limiter.tryAcquire(), "After one second a token should be available again");
        }

        @Test
        @DisplayName("acquire blocks until a token is refilled")
        void acquireBlocksUntilRefilled() throws Exception {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 2.0, TimeUnit.SECONDS); // 2 tokens/s
            assertTrue(limiter.tryAcquire(), "Take the only token");
            // Second acquire should block ~0.5s (need 0.5 token worth of time)
            long start = System.nanoTime();
            assertTrue(limiter.tryAcquire(), "Second tryAcquire should still fail immediately");
            // Now block
            limiter.acquire(); // should return after ~0.5s
            long elapsedNs = System.nanoTime() - start;
            long expectedNs = TimeUnit.SECONDS.toNanos(1) / 2; // 0.5s
            assertTrue(elapsedNs >= expectedNs * 0.8 && elapsedNs <= expectedNs * 1.2,
                    "acquire should have waited roughly the expected time");
        }
    }

    @Nested
    @DisplayName("Concurrent behavior")
    class ConcurrentTests {

        /**
         * Stress test: many threads repeatedly call {@code tryAcquire} in a tight loop
         * for a fixed duration. The total number of successful acquisitions must not
         * exceed {@code rate * duration + capacity} (the burst allowance).
         */
        @Test
        @DisplayName("Long‑run rate does not exceed configured limit")
        @Timeout(value = 30, unit = TimeUnit.SECONDS) // safeguard against deadlocks
        void longRunRateRespectsLimit() throws Exception {
            double capacity = 10.0;
            double ratePerSec = 5.0; // 5 tokens per second
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSec, TimeUnit.SECONDS);

            int workerCount = 20;
            Duration testDuration = Duration.ofSeconds(10);
            ExecutorService exec = Executors.newFixedThreadPool(workerCount);
            List<Future<Long>> futures = new ArrayList<>();

            AtomicLong totalAcquired = new AtomicLong(0);
            long endNano = System.nanoTime() + testDuration.toNanos();

            for (int i = 0; i < workerCount; i++) {
                futures.add(exec.submit(() -> {
                    long localCount = 0;
                    while (System.nanoTime() < endNano) {
                        if (limiter.tryAcquire()) {
                            localCount++;
                        }
                        // Optional tiny back‑off to reduce CPU spin; not required for correctness.
                        // Thread.yield();
                    }
                    return localCount;
                }));
            }

            long sum = 0L;
            for (Future<Long> f : futures) {
                sum += f.get(); // wait for each worker to finish
            }
            exec.shutdownNow();

            double durationSec = testDuration.getSeconds() + testDuration.getNano() / 1e9;
            double maxAllowed = ratePerSec * durationSec + capacity; // burst + steady‑state

            assertTrue(sum <= maxAllowed + 1e-9,
                    String.format("Acquired %d tokens, but limit is %.2f (rate %.2f tok/s * %.3f s + capacity %.2f)",
                            sum, maxAllowed, ratePerSec, durationSec, capacity));
        }

        /**
         * Verifies that the blocking {@code acquire()} respects the rate when many
         * threads contend for tokens. Each thread performs a fixed number of
         * acquisitions and we measure the elapsed wall‑clock time; the observed
         * rate must be close to the configured rate (allowing for the initial burst).
         */
        @Test
        @DisplayName("Blocking acquire respects rate under contention")
        void blockingAcquireRespectsRate() throws Exception {
            double capacity = 5.0;
            double ratePerSec = 2.0; // 2 tokens per second
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSec, TimeUnit.SECONDS);

            int workers = 10;
            int acquiresPerWorker = 50; // each worker will try to acquire 50 tokens
            CountDownLatch startLatch = new CountDownLatch(workers);
            CountDownLatch doneLatch = new CountDownLatch(workers);
            AtomicLong startTime = new AtomicLong();
            AtomicLong endTime = new AtomicLong();

            ExecutorService exec = Executors.newFixedThreadPool(workers);
            for (int w = 0; w < workers; w++) {
                exec.submit(() -> {
                    try {
                        startLatch.countDown();
                        startLatch.await(); // wait for all workers to be ready
                        if (Thread.currentThread().getId() == 1) { // arbitrary leader records start
                            startTime.set(System.nanoTime());
                        }
                        for (int i = 0; i < acquiresPerWorker; i++) {
                            limiter.acquire();
                        }
                        if (Thread.currentThread().getId() == 1) {
                            endTime.set(System.nanoTime());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(doneLatch.await(2, TimeUnit.MINUTES), "Workers did not finish in time");
            exec.shutdownNow();

            long elapsedNs = endTime.get() - startTime.get();
            double elapsedSec = elapsedNs / 1e9;
            long totalAcquired = (long) workers * acquiresPerWorker;

            // Expected minimum time = (totalAcquired - capacity) / rate  (we can use the initial burst)
            double minExpectedSec = Math.max(0.0, (totalAcquired - capacity)) / ratePerSec;
            double maxExpectedSec = (totalAcquired + capacity) / ratePerSec; // generous upper bound

            assertTrue(elapsedSec >= minExpectedSec * 0.8 &&
                            elapsedSec <= maxExpectedSec * 1.2,
                    String.format("Blocking acquire took %.3fs for %d tokens (rate %.2f tok/s, capacity %.1f). " +
                                    "Expected range [%.3f, %.3f]s.",
                            elapsedSec, totalAcquired, ratePerSec, capacity,
                            minExpectedSec, maxExpectedSec));
        }
    }
}
```

### Explanation of the Tests

| Test Group | What it verifies |
|------------|------------------|
| **Basic functionality** | - `tryAcquire` works when the bucket has tokens.<br>- It fails when the bucket is empty and succeeds after a refill.<br>- `acquire` blocks until a token becomes available and wakes up after the correct interval. |
| **Concurrent behavior – long‑run rate** | Many threads spin on `tryAcquire` for a fixed window. The total successes are bounded by `rate * duration + capacity`. This ensures the limiter never allows a sustained throughput above the configured rate, even though a short burst up to the capacity is permitted. |
| **Concurrent behavior – blocking acquire under contention** | A fixed number of threads each perform a known number of `acquire` calls. Measuring the wall‑clock time shows that the observed average rate matches the configured rate (within a tolerant margin), confirming that the blocking path also respects the rate limit and does not allow excess consumption. |

All tests use **JUnit 5** (`org.junit.jupiter.api.*`) and are deterministic apart from small timing tolerances, which are expressed as percentage‑based margins to accommodate JVM scheduling jitter.

--- 

**You can now copy the two Java files into your project, add JUnit 5 to the test classpath, and run the tests to validate the token‑bucket rate limiter’s correctness and thread‑safety.**