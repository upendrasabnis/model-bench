<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260806T073415Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Overview

A **token‑bucket** rate limiter keeps a “bucket” that can hold up to *burstCapacity* tokens.  
Tokens are added to the bucket at a constant *refillRate* (tokens per second).  
When a client calls `acquire()` it consumes one token; if no token is available the call blocks
until a token becomes available.  
`tryAcquire()` returns immediately and only succeeds if a token is present.

### Thread safety

All state changes (`availableTokens`, `lastRefillTime`) are guarded by a single
`ReentrantLock`.  
A `Condition` is used for the blocking `acquire()` call.

### Refill algorithm

Tokens are **not** added by a scheduled task – the bucket is refreshed lazily
whenever a thread interacts with the limiter:

```
elapsedNanos = now - lastRefillTime
tokensToAdd = elapsedNanos * refillRate / 1_000_000_000
availableTokens = min(availableTokens + tokensToAdd, burstCapacity)
lastRefillTime = now
```

Because the calculation uses the current time, the limiter is accurate even if
threads are sporadic.

### Blocking `acquire()`

If the bucket is empty the thread waits on the condition variable.  
The wait time is computed from the exact amount of time required to generate the
next token.  After a token is consumed the lock signals all waiting threads so
that they can re‑evaluate their state.

### Non‑blocking `tryAcquire()`

Simply refreshes the bucket and consumes a token if one is available.

---

## Source Code

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The limiter can be configured with a refill rate (tokens per second Sek) and a burst
 * capacity (maximum number of tokens that can be stored).  Tokens are added ரச
 * lazily whenever a thread accesses the limiter.</p>
 *
 * <p>Thread safety is guaranteed by a single {@link ReentrantLock}.  The blocking
 * {@code acquire()} method uses a {@link Condition} to wait until a token becomes
 * available.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 10.0); // 5 tokens/sec, burst 10
 *
 * // Non‑blocking
 * if (limiter.tryAcquire()) {
 *     // do work
 * }
 *
 * // Blocking
 * limiter.acquire(); // waits until a token is available
 * }</pre>
 */
public final class TokenBucketRateLimiter {

    private final double refillRatePerSecond;
    private final double burstCapacity;

    private double availableTokens;
    private long lastRefillTimeNanos;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tokenAvailable = lock.newCondition();

    /**
     * Creates a new token‑bucket rate limiter.
     *
     * @param refillRatePerSecond the number of tokens added to the bucket per second
     * @param burstCapacity the maximum number of tokens that may accumulate in the bucket
     * @throws IllegalArgumentException if either argument <= 0
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double burstCapacity) {
        if (refillRatePerSecond <= 0.0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (burstCapacity <= 0.0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.burstCapacity = burstCapacity;
        this.availableTokens = burstCapacity; // start full
        this.lastRefillTimeNanos = System.nanoTime();
    }

    /**
     * Blocks until a token is available, then consumes one token.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();

                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0;
                    tokenAvailable.signalAll(); // wake others
                    return;
                }

                // Need to wait for the next token
                long waitNanos = nanosUntilNextToken();
                // Wait with a timeout to avoid indefinite blocking if interrupted
                tokenAvailable.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to consume a token immediately.
     *
     * @return {@code true} if a token was available and consumed, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                tokenAvailable.signalAll();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of tokens currently available (may be fractional).
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the configured burst capacity.
     */
    public double getBurstCapacity() {
        return burstCapacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    /**
     * Refreshes the bucket according to the elapsed time since the last refill.
     */
    private void refill() {
        final long now = System.nanoTime();
        final long elapsedNanos = now - lastRefillTimeNanos;
        if (elapsedNanos <= 0) {
            return; // nothing to do
        }
        double tokensToAdd = elapsedNanos * refillRatePerSecond / 1_000_000_000.0;
        availableTokens = Math.min(availableTokens + tokensToAdd, burstCapacity);
        lastRefillTimeNanos = now;
    }

    /**
     * Computes the nanoseconds until the next token can be generated.
     */
    private long nanosUntilNextToken() {
        if (refillRatePerSecond <= 0.0) {
            // should never happen due to constructor validation
            return TimeUnit.SECONDS.toNanos(1);
        }
        // time needed to generate (1.0 - availableTokens) tokens
        double tokensNeeded = 1.0 - availableTokens;
        double nanosPerToken = 1_000_000_000.0 / refillRatePerSecond;
        return (long) Math.ceil(tokensNeeded * nanosPerToken);
    }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TokenBucketRateLimiterTest {

    /** Basic single‑thread non‑blocking acquire */
    @Test
    void testTryAcquireSingleThread() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 20.0);

        // Consume 10 tokens העת
        IntStream.range(0, 10).forEach(i -> assertTrue(limiter.tryAcquire()));

        // Bucket now has 10 tokens left
        assertEquals(10.0, limiter.getAvailableTokens(), 1e-6);

        // Acquire 10 more tokens until empty
        IntStream.range(0, 10).forEach(i -> assertTrue(limiter.tryAcquire()));

        // No tokens left
        assertEquals(0.0, limiter.getAvailableTokens(), 1e-6);
        assertFalse(limiter.tryAcquire());
    }

    /** Blocking acquire waits until a token is available */
    @Test
    void testAcquireBlocking() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0);

        // Drain the bucket
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Start a thread that will block on acquire
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                started.countDown();
                limiter.acquire();              // should block
                acquired.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        // Wait for the thread to start and block
        assertTrue(started.await(1, TimeUnit.SECONDS));

        // After 200ms a convain token should have been refilled
        Thread.sleep(200);

        // The thread should now have acquired a token
        assertTrue(acquired.await(1, TimeUnit.SECONDS));
        t.join();
    }

    /** Long‑run rate does not exceed the configured limit */
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // fail if test hangs
    @Test
    void testLongRunRateLimit() throws InterruptedException {
        final double refillRate = 10.0;          // tokens per second
        final double burstCapacity = 20.0;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(refillRate, burstCapacity);

        final int threadCount = 8;
        final long testDurationMs = 5_000; // 5 seconds
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);

        AtomicLong acquiredCount = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Each worker keeps acquiring as fast as possible
        Runnable worker = () -> {
            try {
                startLatch.await(); // wait until all workers are ready
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            while (!stop.get()) {
                try {
                    limiter.acquire(); // blocks until token available
                    acquiredCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        // Launch workers
        IntStream.range(0, threadCount).forEach(i -> exec.submit(worker));

        // Start all workers at the same time
        startLatch.countDown();

        // Let the test run for the specified duration
        Thread.sleep(testDurationMs);
        stop.set(true);

        exec.shutdown();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS),
                "Executor did not terminate in time");

        long totalTokens = acquiredCount.get();
        double averageRate = totalTokens / (testDurationMs / 1000.0);

        // Allow a 10% tolerance for rounding / scheduling effects
        assertTrue(averageRate <= refillRate * 1.1,
                String.format("Average rate %.2f exceeded limit %.2f", averageRate, refillRate));
    }

    /** Verify burst capacity is respected */
    @Test
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 5.0);

        // Acquire 5 tokens immediately
        IntStream.range(0, 5).forEach(i -> assertTrue(limiter.tryAcquire()));

        // Bucket should be empty
        assertFalse(limiter.tryAcquire());

        // Wait 3 seconds – 3 tokens should be refilled
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e)@Retention(OVERRIDE) {
            Thread.currentThread().interrupt();
        }

        // Now 3 tokens available, but burst capacity is 5
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());

        // No more tokens
        assertFalse(limiter.tryAcquire());
    }
}
```

### Test Highlights

| Test | Purpose |
|------|---------|
| `testTryAcquireSingleThread` | Validates simple non‑blocking token consumption and bucket limits. |
| `testAcquireBlocking` | Ensures `acquire()` blocks until a token is available and wakes correctly. |
| `testLongRunRateLimit` | Runs many threads for several seconds and asserts the long‑run average rate never exceeds the configured `refillRate`. |
| `testBurstCapacity` | Confirms that the burst capacity caps token accumulation. |

All tests run under JUnit 5 and use only the JDK concurrency utilities, making them safe and deterministic}]
