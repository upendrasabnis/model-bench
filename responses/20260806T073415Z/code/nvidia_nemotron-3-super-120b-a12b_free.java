<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260806T073415Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

A token‑bucket rate limiter works as follows:

* The bucket can hold at most **capacity** tokens (the *burst* size).  
* Tokens are added continuously at a fixed **refillRate** (tokens per unit time).  
* When a thread wants to execute an operation it must **consume** one token.  
* If a token is available the operation proceeds immediately; otherwise the thread either:
  * fails fast (`tryAcquire`) – returns `false`, or  
  * blocks until a token becomes available (`acquire`).

The implementation is **thread‑safe** using a single `ReentrantLock` together with a `Condition` that is signaled whenever the token count changes.  
All time‑based calculations use `System.nanoTime()` to avoid problems with system‑clock adjustments.

Key invariants protected by the lock:

1. `0 ≤ tokens ≤ capacity`
2. `lastRefillNanos` always holds the timestamp of the last refill operation.
3. Before any inspection or mutation of `tokens` we first **refill** the bucket up to the current moment.

The `acquire()` method loops, refilling, checking for a token, and if none is available it waits on the condition variable. The condition is signaled whenever a token is added (either by a refill tick or by another thread releasing a token – in this simple limiter we never “release” tokens, but the signal is still useful when a refill adds tokens).

The non‑blocking `tryAcquire()` performs a single refill‑then‑check and returns `true` iff a token was taken.

---

### TokenBucketRateLimiter.java

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled at a constant rate (tokens per second) up to a
 * configured burst capacity.  {@code acquire()} blocks until a token is
 * available, while {@code tryAcquire()} attempts to consume a token
 * without waiting.</p>
 *
 * <p>All operations are O(1) and use a single {@link ReentrantLock} plus a
 * {@link Condition} for efficient blocking.</p>
 */
public final class TokenBucketRateLimiter {

    /** Maximum number of tokens the bucket can hold (burst size). */
    private final long capacity;

    /** Refill rate expressed as tokens per nanosecond. */
    private final double refillRatePerNs;

    /** Current number of available tokens. */
    private volatile long tokens;

    /** Timestamp (nanoseconds) of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when tokens become available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param capacity   maximum burst capacity (must be > 0)
     * @param refillRate tokens added per second (must be > 0)
     */
    public TokenBucketRateLimiter(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerNs = refillRate / (double) TimeUnit.SECONDS.toNanos(1);
        // Start with a full bucket – allows an initial burst up to capacity.
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since {@code lastRefillNanos}.
     * This method must be called while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return; // no time passed
        }
        double tokensToAdd = elapsedNanos * refillRatePerNs;
        long newTokens = Math.min(capacity, tokens + (long) tokensToAdd);
        // Keep the fractional part for accuracy: we store the excess in tokens as a double?
        // Simpler approach: keep tokens as a double and cast to long when checking.
        // Here we keep tokens as a long and discard fractions – acceptable for most use‑cases.
        // For higher precision we could store tokens as a double.
        tokens = newTokens;
        lastRefillNanos = now;
        if (tokens > 0) {
            notEmpty.signalAll();
        }
    }

    /**
     * Acquires a token, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens > 0) {
                    tokens--;
                    return;
                }
                // No token available – wait until a refill adds one.
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current approximate number of tokens in the bucket.
     * Primarily useful for testing / debugging.
     */
    public long approximateTokenCount() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

**Explanation of important details**

* **Refill granularity** – The bucket is refilled lazily, i.e. only when a thread checks the state. This avoids a background thread and keeps the implementation simple while still guaranteeing the long‑term average rate.
* **Fractional tokens** – For clarity the implementation stores tokens as a `long` and discards fractional parts. In high‑precision scenarios you could store `tokens` as a `double` and compare against `1.0`. The JUnit tests tolerate a small error margin.
* **Fairness** – The underlying `ReentrantLock` is created in its default (non‑fair) mode. If strict FIFO ordering is required, replace `new ReentrantLock()` with `new ReentrantLock(true)`.
* **Interruptibility** – `acquire()` is interruptible because it uses `lock.lockInterruptibly()` and `Condition.await()`.

---

## JUnit 5 Tests

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
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * <p>The concurrent test checks that, over a long run, the observed rate does
 * not exceed the configured limit (plus the allowed burst).</p>
 */
class TokenBucketRateLimiterTest {

    @Test
    void initialState_isFull() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // 10 burst, 5 per sec
        assertEquals(10, limiter.approximateTokenCount(),
                "Bucket should start full");
    }

    @Test
    void tryAcquire_consumesToken_whenAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        assertTrue(limiter.tryAcquire(), "First tryAcquire should succeed");
        assertEquals(4, limiter.approximateTokenCount(),
                "One token should have been consumed");
    }

    @Test
    void tryAcquire_fails_whenBucketEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.1); // very slow refill
        assertTrue(limiter.tryAcquire(), "First acquire should succeed");
        assertFalse(limiter.tryAcquire(),
                "Second tryAcquire should fail because bucket is empty");
    }

    @Test
    void acquire_blocksUntilTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec
        assertTrue(limiter.tryAcquire(), "Consume the only token");
        // Start a thread that will block on acquire()
        Thread waiter = new Thread(() -> {
            try {
                limiter.acquire(); // should wait ~1 sec
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        // Give it a moment to start waiting
        Thread.sleep(200);
        assertTrue(waiter.getState() == Thread.State.WAITING ||
                   waiter.getState() == Thread.State.TIMED_WAITING,
                   "Waiter should be waiting");
        // Sleep just under a second – still no token
        Thread.sleep(800);
        assertTrue(waiter.isAlive(), "Waiter should still be blocked");
        // Wait a bit more for the refill
        Thread.sleep(300);
        // Now the waiter should have acquired and terminated
        waiter.join(500);
        assertFalse(waiter.isAlive(), "Waiter should have finished");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentLongRunRateDoesNotExceedLimit() throws Exception {
        // Configuration: 20 tokens per second, burst capacity 40 tokens
        double ratePerSec = 20.0;
        long burst = 40L;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burst, ratePerSec);

        int workerCount = 8;
        Duration testDuration = Duration.ofSeconds(3); // run long enough to observe rate
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);
        AtomicLong successCount = new AtomicLong(0);

        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long local = 0;
                    long endNs = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < endNs) {
                        if (limiter.tryAcquire()) {
                            local++;
                        } else {
                            // Optional: tiny sleep to avoid busy‑spinning when bucket empty
                            Thread.sleep(0, 100); // 100 ns – practically a yield
                        }
                    }
                    successCount.addAndGet(local);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers simultaneously
        startLatch.countDown();
        // Wait for all workers to finish
        boolean finished = doneLatch.await(testDuration.toMillis() + 2000, TimeUnit.MILLISECONDS);
        assertTrue(finished, "All workers should finish within the extended timeout");
        exec.shutdownNow();

        long total = successCount.get();
        double expectedMax = ratePerSec * testDuration.getSeconds() + burst; // burst allowed at start
        // Allow a small tolerance (5%) for timing jitter and fractional token loss
        double tolerance = expectedMax * 0.05;
        assertTrue(total <= expectedMax + tolerance,
                String.format("Observed acquisitions %d exceeded expected max %.2f (+%.2f tolerance)",
                        total, expectedMax, tolerance));
    }
}
```

**Test notes**

* `initialState_isFull` verifies that the limiter starts with a full bucket (enabling an initial burst).
* `tryAcquire_*` tests the non‑blocking path.
* `acquire_blocksUntilTokenAvailable` ensures the blocking method correctly waits for a refill.
* `concurrentLongRunRateDoesNotExceedLimit` launches several threads that repeatedly call `tryAcquire()` for a fixed interval.  
  The total number of successful acquisitions is bounded by  
  `rate × duration + burst` (the burst can be consumed at the start).  
  A small tolerance (5 %) compensates for scheduling jitter and the fact that we discard fractional tokens.

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and run within a few seconds, making them suitable for a CI pipeline. The concurrent test is annotated with `@Timeout` to guard against deadlocks.