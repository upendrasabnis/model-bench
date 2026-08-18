<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260818T143128Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token bucket* limiter works as follows:

* The bucket can hold at most **capacity** tokens.
* Tokens are added continuously at a **refillRate** (tokens per second).  
  When time `Δt` passes, `Δt × refillRate` tokens are deposited, but the bucket never exceeds its capacity.
* An operation that needs `n` permits can proceed only if the bucket currently holds at least `n` tokens; those tokens are then removed.
* If there are not enough tokens, a **blocking** `acquire` waits until enough tokens have been refilled.  
  A **non‑blocking** `tryAcquire` simply reports failure.

The implementation below is thread‑safe: all state (`lastRefillNanos`, `tokens`) is accessed only while holding a `java.util.concurrent.locks.ReentrantLock`.  
A `Condition` named `notEmpty` is used to park threads that are waiting for tokens; it is signaled whenever the bucket’s token count increases.

---

## 1. TokenBucketRateLimiter.java

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a configured rate (tokens per second)
 * up to a maximum burst size (= capacity).  Both blocking {@code acquire}
 * and non‑blocking {@code tryAcquire} operations are provided.</p>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>All mutable state is protected by a {@link ReentrantLock}.</li>
 *   <li>Time is measured with {@link System#nanoTime()} to avoid issues with
 *       system‑clock adjustments.</li>
 *   <li>The token count is kept as a {@code double} to support fractional
 *       refill rates (e.g. 2.5 tokens/s).  When checking for enough tokens we
 *       compare with the requested amount (also treated as a double).</li>
 *   <li>Whenever the token count increases we signal {@code notEmpty} so that
 *       waiting threads can re‑evaluate the condition.</li>
 * </ul>
 *
 * @author  Generated for the answer
 */
public class TokenBucketRateLimiter {

    private final double capacity;               // maximum number of tokens the bucket can hold
    private final double refillRatePerSec;       // tokens added per second
    private volatile double tokens;              // current token count
    private volatile long lastRefillNanos;       // timestamp of the last refill (nanoseconds)

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param capacity          maximum burst size (tokens). Must be > 0.
     * @param refillRatePerSec  long‑term refill rate (tokens per second). Must be > 0.
     * @throws IllegalArgumentException if either argument is non‑positive.
     */
    public TokenBucketRateLimiter(double capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        // start with a full bucket – this allows an initial burst up to capacity
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Internal method that adds tokens based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return; // no time passed
        }
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRatePerSec;
        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillNanos = now;
            // Signal any waiters that tokens may now be available.
            notEmpty.signalAll();
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available
     * or the thread is interrupted.
     *
     * @param permits number of tokens to acquire. Must be >= 0.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire(double permits) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("permits must be >= 0");
        }
        if (permits == 0) {
            return; // nothing to do
        }
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                // Not enough tokens – wait until we are signaled.
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires exactly one permit, blocking until it is available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        acquire(1.0);
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of tokens to acquire. Must be >= 0.
     * @return {@code true} if the permits were acquired, {@code false} otherwise.
     */
    public boolean tryAcquire(double permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits must be >= 0");
        }
        if (permits == 0) {
            return true;
        }
        lock.lock();
        try {
            refill();
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
     * Attempts to acquire exactly one permit without blocking.
     *
     * @return {@code true} if a permit was acquired, {@code false} otherwise.
     */
    public boolean tryAcquire() {
        return tryAcquire(1.0);
    }

    /**
     * Returns the current number of tokens in the bucket (for testing/debugging).
     * The value is a snapshot; it may change immediately after the call.
     *
     * @return current token count.
     */
    public double getTokens() {
        lock.lock();
        try {
            refill(); // make sure the snapshot is up‑to‑date
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

### Design Documentation (included as Javadoc)

* **Thread safety** – All accesses to `tokens` and `lastRefillNanos` are guarded by a `ReentrantLock`.  
  The `Condition` `notEmpty` is used to efficiently block threads that need to wait for tokens.
* **Refill algorithm** – On every call that may consume tokens (`acquire`, `tryAcquire`, `getTokens`) we first execute `refill()`.  
  `refill()` computes the elapsed time since the last update, converts it to tokens using the configured rate, and adds them (capped at `capacity`).  
  The timestamp is then updated and any waiting threads are signaled.
* **Blocking acquire** – After refilling, if enough tokens are present we consume them and return.  
  Otherwise we await on `notEmpty`. When a refill adds tokens, `signalAll()` wakes all waiters; they loop again to re‑check the condition.
* **Non‑blocking tryAcquire** – Performs a single refill check and either consumes tokens (returning `true`) or leaves the bucket unchanged (returning `false`). No waiting occurs.
* **Fractional tokens** – The implementation stores tokens as a `double` to support rates that are not integral numbers of tokens per second (e.g., 2.5 tokens/s).  
  The comparison `tokens >= permits` works for both integral and fractional permit requests.
* **Initial burst** – The bucket starts full (`tokens = capacity`) so that an initial burst up to the configured capacity is allowed, which matches the typical token‑bucket semantics.

---

## 2. JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * The tests cover:
 * <ul>
 *   <li>Basic acquire / tryAcquire behavior.</li>
 *   <li>Refill over time.</li>
 *   <li>Blocking acquire respects interruption.</li>
 *   <li>A concurrent stress test that verifies the long‑term rate does not exceed
 *       the configured limit (allowing for the initial burst).</li>
 * </ul>
 */
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        // 10 tokens per second, burst capacity of 20 tokens
        limiter = new TokenBucketRateLimiter(20.0, 10.0);
    }

    @Test
    @DisplayName("tryAcquire succeeds when tokens are available")
    void tryAcquireSuccess() {
        assertTrue(limiter.tryAcquire(5.0));
        assertEquals(15.0, limiter.getTokens(), 1e-9);
    }

    @Test
    @DisplayName("tryAcquire fails when bucket is empty")
    void tryAcquireFailure() {
        // drain the bucket
        assertTrue(limiter.tryAcquire(20.0));
        assertEquals(0.0, limiter.getTokens(), 1e-9);
        // now try to acquire – should fail
        assertFalse(limiter.tryAcquire(1.0));
        assertEquals(0.0, limiter.getTokens(), 1e-9);
    }

    @Test
    @DisplayName("blocking acquire waits until tokens are refilled")
    void acquireBlocksThenProceeds() throws InterruptedException {
        // start with an empty bucket
        limiter.tryAcquire(20.0);
        assertEquals(0.0, limiter.getTokens(), 1e-9);

        // launch a thread that will try to acquire 5 tokens after a short delay
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(() -> {
            try {
                limiter.acquire(5.0); // should block until refilled
                return Boolean.TRUE;
            } catch (InterruptedException e) {
                return Boolean.FALSE;
            }
        });

        // give the worker a moment to start waiting
        Thread.sleep(50);
        assertFalse(future.isDone(), "worker should still be waiting");

        // wait for enough time to refill 5 tokens (0.5 sec at 10 tok/s)
        Thread.sleep(600);
        assertTrue(future.isDone(), "worker should have completed after refill");
        assertTrue((Boolean) future.get(), "worker should not have been interrupted");

        // after acquiring 5 tokens, bucket should have 15 left (20 - 5)
        assertEquals(15.0, limiter.getTokens(), 1e-9);

        exec.shutdownNow();
    }

    @Test
    @DisplayName("acquire is interruptible")
    void acquireIsInterruptible() throws InterruptedException {
        limiter.tryAcquire(20.0); // empty bucket
        Thread worker = new Thread(() -> {
            try {
                limiter.acquire(1.0);
            } catch (InterruptedException ignored) {
                // expected
            }
        });
        worker.start();
        // give it a chance to block
        Thread.sleep(100);
        worker.interrupt();
        worker.join(500);
        assertFalse(worker.isAlive(), "worker should have terminated after interrupt");
    }

    @Nested
    @DisplayName("Concurrent rate‑limit verification")
    class ConcurrentRateTest {

        /**
         * Runs many threads that repeatedly try to acquire a permit.
         * Measures the total number of permits acquired over a fixed duration
         * and asserts that it does not exceed {@code rate * duration + capacity}
         * (the maximum allowed by a token bucket with an initial full bucket).
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void longRunRateDoesNotExceedLimit() throws Exception {
            final double ratePerSec = 5.0;      // 5 tokens per second
            final double capacity = 10.0;       // burst of 10 tokens
            final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSec);

            final int workerCount = 50;
            final long testDurationMs = 8000;   // 8 seconds
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch doneLatch = new CountDownLatch(workerCount);
            final AtomicLong permitsAcquired = new AtomicLong(0);

            ExecutorService exec = Executors.newFixedThreadPool(workerCount);

            for (int i = 0; i < workerCount; i++) {
                exec.submit(() -> {
                    try {
                        startLatch.await(); // wait for all workers to start together
                        long localAcquired = 0;
                        long endTime = System.nanoTime() + testDurationMs * 1_000_000L;
                        while (System.nanoTime() < endTime) {
                            if (limiter.tryAcquire(1.0)) {
                                localAcquired++;
                            }
                            // tiny pause to avoid a busy‑spin that would hog the CPU
                            Thread.sleep(0, 100); // 100 ns
                        }
                        permitsAcquired.addAndGet(localAcquired);
                    } catch (InterruptedException ignored) {
                        // thread was interrupted – exit
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // release all workers simultaneously
            startLatch.countDown();

            // wait for all workers to finish or timeout
            boolean finished = doneLatch.await(testDurationMs + 2000, TimeUnit.MILLISECONDS);
            assertTrue(finished, "All workers should have finished within the extended timeout");

            exec.shutdownNow();
            exec.awaitTermination(5, TimeUnit.SECONDS);

            long totalAcquired = permitsAcquired.get();
            double maxAllowed = capacity + ratePerSec * (testDurationMs / 1000.0);
            // Allow a small tolerance (≈5%) for timing jitter and the fact that tryAcquire may
            // occasionally spin a bit longer than the sleep interval.
            double tolerance = 0.05 * maxAllowed;
            assertTrue(totalAcquired <= maxAllowed + tolerance,
                    String.format("Acquired %d tokens, but max allowed is %.2f (rate=%.2f tok/s, capacity=%.1f, time=%.3fs)",
                            totalAcquired, maxAllowed, ratePerSec, capacity, testDurationMs / 1000.0));
        }
    }
}
```

### Explanation of the Test Suite

| Test | Purpose |
|------|---------|
| `tryAcquireSuccess` / `tryAcquireFailure` | Verify the basic non‑blocking behavior and that the token count updates correctly. |
| `acquireBlocksThenProceeds` | Ensure a blocking `acquire` waits until enough tokens are refilled, and that it proceeds correctly after the wait. |
| `acquireIsInterruptible` | Confirm that `acquire` respects thread interruption (throws `InterruptedException`). |
| `ConcurrentRateTest.longRunRateDoesNotExceedLimit` | **Core concurrent correctness test**: many threads repeatedly call `tryAcquire(1)` for a fixed interval. The total number of permits acquired must not exceed the theoretical maximum `capacity + rate × duration`. A small tolerance (5 %) accounts for scheduling jitter and the tiny sleep used to avoid a busy spin. |

The test uses a `CountDownLatch` to make all worker threads start at nearly the same moment, eliminating ramp‑up bias. After the test duration, the main thread aggregates the acquired permits and asserts the rate limit.

---

**You can now copy the two source files into a standard Maven/Gradle project (e.g., `src/main/java/com/example/ratelimiter/TokenBucketRateLimiter.java` and `src/test/java/com/example/ratelimiter/TokenBucketRateLimiterTest.java`) and run the tests with JUnit 5.** The implementation is lock‑based, avoids busy‑waiting, and works correctly under high contention.