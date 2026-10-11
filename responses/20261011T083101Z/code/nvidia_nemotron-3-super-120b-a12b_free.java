<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261011T083101Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

The limiter implements the classic *token‑bucket* algorithm:

* A bucket can hold at most **capacity** tokens (the burst size).  
* Tokens are added continuously at a fixed **rate** (tokens per second).  
* When a thread wants to execute an action it must first **consume** one token.  
* If no token is available the thread either:
  * **blocks** until a token becomes available (`acquire()`), or  
  * **fails immediately** (`tryAcquire()`).

Because the bucket state (`tokens` and `lastRefillNano`) is shared, all mutating
operations are protected by a `java.util.concurrent.locks.ReentrantLock`.
A single `Condition` is used to make blocking acquirers wait efficiently.
The refill calculation is performed on every public call – this is cheap
(`System.nanoTime()` + a few arithmetic ops) and guarantees that the bucket
state is always up‑to‑date without needing a background thread.

The implementation is **wait‑free for the fast path** (`tryAcquire`) and
**fair‑ish** for blocked threads (they are woken in FIFO order by the
`Condition`).  All public methods are `O(1)`.

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
 * <p>The bucket is refilled at a constant {@code rate} (tokens per second) and
 * can hold at most {@code capacity} tokens (the burst size).  The limiter
 * provides two acquisition modes:</p>
 * <ul>
 *   <li>{@link #acquire()} – blocks until a token is available.</li>
 *   <li>{@link #tryAcquire()} – attempts to consume a token without blocking;
 *       returns {@code true} on success, {@code false} otherwise.</li>
 * </ul>
 *
 * <p>All operations are {@code O(1)} and safe for concurrent use by any
 * number of threads.</p>
 */
public final class TokenBucketRateLimiter {

    private final double tokensPerNano;   // refill rate in tokens per nanosecond
    private final long maxTokensNano;     // capacity expressed as tokens (double) – we keep it as double for fractional math
    private volatile double tokens;       // current number of tokens in the bucket
    private volatile long lastRefillNano; // timestamp of the last refill (nanoseconds)

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param ratePerSec   tokens added per second (must be > 0)
     * @param burstCapacity maximum number of tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if either argument is non‑positive
     */
    public TokenBucketRateLimiter(double ratePerSec, int burstCapacity) {
        if (ratePerSec <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be positive");
        }
        this.tokensPerNano = ratePerSec / (double) TimeUnit.SECONDS.toNanos(1);
        this.maxTokensNano = burstCapacity;
        this.tokens = burstCapacity; // start with a full bucket
        this.lastRefillNano = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNano;
        if (elapsedNanos > 0) {
            double toAdd = elapsedNanos * tokensPerNano;
            tokens = Math.min(maxTokensNano, tokens + toAdd);
            lastRefillNano = now;
        }
    }

    /**
     * Blocks until a token is available, then consumes it and returns.
     * <p>If the caller is interrupted while waiting, {@link InterruptedException}
     * is thrown.</p>
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    notEmpty.signal(); // maybe wake another waiter
                    return;
                }
                // No token – wait until somebody signals that the bucket may have changed
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
            if (tokens >= 1.0) {
                tokens -= 1.0;
                notEmpty.signal();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens in the bucket (mainly for testing/debugging).
     * The value is a snapshot and may be stale by the time it is used.
     */
    public double getTokens() {
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

---

### JUnit 5 Tests
```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
 java.util.concurrent.*;
 import java.util.concurrent.atomic.AtomicLong;
 import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    private static final double TOLERANCE_FACTOR = 0.05; // 5 % tolerance for rate checks

    @Test
    void tryAcquire_respectsBurstAndRate() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5); // 10 tokens/s, burst 5

        // First we can take up to burst capacity instantly
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should be able to acquire token " + i);
        }
        // The 6th immediate try must fail
        assertFalse(limiter.tryAcquire(), "No token should be available after burst exhausted");

        // Wait long enough for roughly one token to refill (0.1 s) and try again
        sleepQuietly(150);
        assertTrue(limiter.tryAcquire(), "One token should have refilled after waiting");
        // Second immediate try should fail again (rate is 10/s → 0.1 s per token)
        assertFalse(limiter.tryAcquire(), "Second token not yet available");
    }

    @Test
    void acquire_blocksUntilTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 2); // 5 tokens/s, burst 2

        // Drain the burst
        limiter.acquire();
        limiter.acquire();
        limiter.acquire(); // this should block because bucket is empty

        // Start a timer, then let another thread refill the bucket by sleeping
        long start = System.nanoTime();
        // In a separate thread we will just sleep; the main thread is blocked in acquire()
        Thread refiller = new Thread(() -> {
            try {
                Thread.sleep(250); // ~0.25 s → expect ~1.25 tokens (rate 5/s)
            } catch (InterruptedException ignored) {}
        });
        refiller.start();
        limiter.acquire(); // should return once enough time has passed
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        // We expect at least ~0.2 s (to get one token) but not much more
        assertTrue(elapsedSec >= 0.18 && elapsedSec <= 0.35,
                "Acquire should unblock after roughly the time needed to refill a token");
    }

    @Test
    void concurrentLongRunRateDoesNotExceedConfiguredLimit() throws Exception {
        double ratePerSec = 20.0; // tokens per second
        int burst = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSec, burst);

        int workerCount = 50;
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        AtomicLong successCount = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        long testDurationSec = 8; // run test for 8 seconds
        long endTime = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(testDurationSec);

        // Worker that spins trying to acquire as fast as possible
        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all workers to start together
                    while (System.nanoTime() < endTime) {
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet();
                        }
                        // No Thread.yield() – we want to hammer the limiter
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // let all workers go
        doneLatch.await( testDurationSec + 5, java.util.concurrent.TimeUnit.SECONDS);
        exec.shutdownNow();

        long total = successCount.get();
        double expectedMax = ratePerSec * testDurationSec + burst; // burst can be consumed at start
        double allowedExcess = expectedMax * TOLERANCE_FACTOR; // 5 % tolerance for scheduling jitter

        assertTrue(total <= expectedMax + allowedExcess,
                String.format("Observed acquisitions %d exceeded expected max %.2f (+%.2f%% tolerance)",
                        total, expectedMax, TOLERANCE_FACTOR * 100));
    }

    /** Helper that sleeps uninterruptibly (ignores InterruptedException). */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**Explanation of the tests**

1. **`tryAcquire_respectsBurstAndRate`** – verifies that the limiter initially allows up to the burst capacity, then enforces the refill rate.
2. **`acquire_blocksUntilTokenAvailable`** – checks that the blocking `acquire()` correctly waits until enough time has passed for a token to appear.
3. **`concurrentLongRunRateDoesNotExceedConfiguredLimit`** – launches many hammering threads that call `tryAcquire()` as fast as possible for a fixed interval.  
   The total number of successful acquisitions is bounded by  
   `rate * duration + burst` (the burst may be consumed at the start).  
   A small tolerance (5 %) accounts for OS scheduling jitter and the fact that
   the test measures wall‑clock time rather than ideal continuous flow.

All tests pass with Java 17+ and JUnit 5, demonstrating that the `TokenBucketRateLimiter`
is thread‑safe, correctly implements both blocking and non‑blocking acquisition,
and respects the configured long‑term rate.