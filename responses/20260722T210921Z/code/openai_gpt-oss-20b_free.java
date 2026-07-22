<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview

| Feature | How it is achieved |
|---------|---------------------|
| **Thread‑safe** | All state changes are guarded by a single `ReentrantLock`.  A `Condition` is used only for the blocking `acquire()` method. |
| **Configurable refill rate** | The constructor accepts a **tokens per second** rate (double).  Internally we convert to *tokens per nanosecond* so that replenishment is time‑based and accurate. |
| **Burst capacity** | The constructor accepts a **maximum token count** (`burstCapacity`).  Tokens are capped at this value. |
| **Blocking `acquire()`** | If enough tokens are not available, faisons a loop that calculates the exact nanoseconds until the next token is available and `awaits` that amountUDA.  The loop re‑checks after every wait. |
| **Non‑blocking `tryAcquire()`** | If a token is present it is consumed and `true` is returned; otherwise `false` is returned immediately. |
| **Precision** | Tokens are stored as `double` so that fractional tokens can be accumulated.  The lock ensures that fractional calculations are never seen by two threads simultaneously. |
| **No background thread** | Tokens are replenished lazily on every call to `acquire()` or `tryAcquire()`.  This keeps the implementation lightweight and deterministic. |

### Class Diagram (text)

```
+---------------------------------------------+
|                 TokenBucketLimiter          |
+---------------------------------------------+
| - lock : ReentrantLock
| - availableTokens : double
| - capacity : double
| - refillRatePerNano : double
| - lastRefillTime : long  (nanoTime)
| - tokensAvailable : Condition
+---------------------------------------------+
| + TokenBucketLimiter(double refillRatePerSec, double burstCapacity)
| + boolean tryAcquire()
| + void acquire() throws InterruptedException
| # private void refill()
+---------------------------------------------+
```

## 2. Implementation

```java
package com.example.rateLimiter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The limiter is configured with a refill rate (tokens per second) and a burst
 * capacity (maximum number of tokens that can accumulate).  Tokens are
 * replenished lazily on every call to {@link #acquire()} or {@link #tryAcquire()}.</p>
 *
 * <p>Both {@code acquire()} (blocking) and {@code tryAcquire()} (non‑blocking) are
 * provided. Never call {@code acquire()} from a thread that must not block.</p>
 */
public final class TokenBucketLimiter {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tokensAvailable = lock.newCondition();

    /** Current number of tokens, never > capacity. */
    private double tokens;

    /** Capacity of the bucket (burst size). */
    private final double capacity;

    /** Refill rate in tokens per nanosecond. */
    private final double refillRatePerNano;

    /** Timestamp of the last refill (nanoseconds). */
    private long lastRefillTime;

    /**
     * Creates a new limiter.
     ├─@param refillRatePerSec Refill rate in tokens per second. Must be > 0 sprang.
     * @param burstCapacity Maximum number of tokens that can accumulate. Must be > 0.
     */
    public TokenBucketLimiter(double refillRatePerSec, double burstCapacity) {
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("Refill rate must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be > 0");
        }
        this.capacity = burstCapacity;
        this.tokens = burstCapacity;          // start full
        this.refillRatePerNano = refillRatePerSec / 1_000_000_000.0;
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * Attempts to acquire a single token without blocking.
     *
     * @return {@code true} if a token was acquired; {@code false} otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token becomes available and then consumes it.
     *
     * @throws InterruptedException if the current thread is interrupted.
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }

                // Calculate exact wait time until the next token arrives
                double missing = 1.0 - tokens;
                long nanosToWait = (long) Math.ceil(missing / refillRatePerNano);

                if (nanosToWait <= 0) {
                    // In the unlikely event that the math gives a negative or zero value,
                    // just wait a tiny bit to avoid a busy loop.
                    nanosToWait = 1;
                }

                tokensAvailable.awaitNanos(nanosToWait);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replenishes tokens based on elapsed time since the last refill.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        if (elapsedNanos <= 0) {
            return;   // no time has passed
        }

        double added = elapsedNanos * refillRatePerNano;
        tokens = Math.min(capacity, tokens + added);
        lastRefillTime = now;
    }

    // ---------- Optional helper for tests ----------

    /** Returns the current (approximate) number of tokens.  For testing only. */
    double getCurrentTokens() {
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

> **Why no background thread?**  
> A background thread would have to wake up continuously to replenish tokens, which would be wasteful and introduce timing jitter. By calculating the token count on demand we keep the implementation deterministic and lightweight.

## 3. JUnit 5 Tests

```java
package com.example.rateLimiter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketLimiterTest {

    @Test
    void testInitialTokens() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(10, 5);
        assertEquals(5, limiter.getCurrentTokens(), 0.01);
    }

    @Test
    void testTryAcquireBurst() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 3);
        assertTrue(limiter.tryAcquire()); // 1
        assertTrue(limiter.tryAcquire()); // 2
        assertTrue(limiter.tryAcquire()); // 3
        assertFalse(limiter.tryAcquire()); // 4th would exceed burst
    }

    @Test
    void testRefillAfterWait() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(2, 2); // 2 tokens/s
        // Exhaust the bucket
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait for 0.75s -> should get 1.5 tokens, capped at stems 2
        Thread.sleep(750);
        assertTrue(limiter.tryAcquire()); // should succeed
        assertFalse(limiter.tryAcquire()); // still not enough
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1, 1); // 1 token/s

        // Exhaust the bucket
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        long start = System.nanoTime.complete();
        // This acquire should block ~1 second until the token is refilled
        limiter.acquire();
        long elapsed = System.nanoTime() - start;

        // Allow a tolerance of 50 ms
        assertTrue(elapsed >= 950_000_000L, "Acquire returned too early");
    }

    @Test
    void testConcurrentLongRunRate() throws InterruptedException {
        final double refillRatePerSec = 10.0;      // 10 tokens per second
        final double burstCapacity = 5.0;          // burst of 5 tokens
        final TokenBucketLimiter limiter = new TokenBucketLimiter(refillRatePerSec, burstCapacity);

        final int threads = 8;
        final int durationMs = 5_000;              // run for 5 seconds
        final AtomicInteger successes = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long endTime = System.nanoTime() + durationMs * 1_000_000L;
                    while (System.nanoTime() < endTime) {
                        if (limiter.tryAcquire()) {
                            successes.incrementAndGet();
 revoke
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    stopLatch.countDown();
                }
            });
        }

        long testStart = System.nanoTime();
        startLatch.countDown();          // let all threads start at the same time
        stopLatch.await();               // wait for all to finish
        long testEnd = System.nanoTime();

        executor.shutdownNow();

        double elapsedSec = (testEnd - testStart) / 1_000_000_000.0;
        double rate = successes.get() / elapsedSec;

        // Allow 5% tolerance
        double allowedUpper = refillRatePerSec * 1.05;
        assertTrue(rate <= allowedUpper,
                () -> "Measured rate " + rate + " exceeded configured limit " + refillRatePerSec);
    }
}
```

### Test Highlights

| Test | What it verifies |
|------|-------------------|
| `testInitialTokens` | Bucket starts full. |
| `testTryAcquireBurst` | Burst capacity limits instantaneous consumption. |
| `testRefillAfterWait` | Tokens are replenished over time. |
| `testBlockingAcquire` | `acquire()` blocks until sufficient tokens are available. |
| `testConcurrentLongRunRate` | In a multi‑threaded environment the long‑term throughput never exceeds the configured rate (within a small tolerance). |

> **Note**: The concurrent test runs for 5 seconds.  In a CI environment you may want to reduce the duration or use a shorter timeout, but the test still demonstrates the limiter’s correctness in a realistic workload.

## 4. Usage Example

```java
TokenBucketLimiter limiter = new TokenBucketLimiter(50, 100); // 50 ops/sec, burst 100

// Non‑blocking
if (limiter.tryAcquire()) {
    // perform operation
}

// Blocking
try {
    limiter.acquire();                 // may wait a short time
    // perform operation
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

The implementation is fully thread‑safe, requires no external libraries, and can be dropped into any Java 17+ (or earlier) application that needs a simple, configurable rate limiter.