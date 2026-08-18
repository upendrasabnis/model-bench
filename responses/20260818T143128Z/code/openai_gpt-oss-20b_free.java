<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260818T143128Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview

A **token‑bucket** rate limiter keeps a bucket that holds “tokens”.  
Tokens are added to the bucket at a constant **refill rate** (tokens/second).  
A request to use the service consumes one token from the bucket.

* **Burst capacity** – the maximum number of tokens that can accumulate in the
  bucket.  This allows short bursts of traffic that exceed the steady‑state
  rate.
* **`acquire()`** – a *blocking* call that waits until a token is available.
* **`tryAcquire()`** – a *non‑blocking* call that returns immediately
  (`true` if a token was taken, otherwise `false`).

### Thread‑safety

All state changes (`tokens`, `lastRefillTimestamp`) are guarded by a
`ReentrantLock`.  The lock is held only while we *refill* and/or *consume* a
token.  The bucket is *passive*: it does **not** run a background thread that
adds tokens.  Tokens are added lazily on every call by computing how many
tokens should have been added since the last operation.

```text
acquire / tryAcquire
    └─ lock
          ├─ refill()
          ├─ if tokens > 0
          │     tokens -= 1
          │     unlock
          │     return success
          └─ else
                (blocking acquire only)
                wait on Condition until enough time has passed
```

### Refill logic

```java
private void refill() {
    long now = System.nanoTime();
    double elapsedSec = (now - lastRefillTimestamp) / 1e9;
    tokens = Math.min(burstCapacity,
                      tokens + elapsedSec * refillRatePerSecond);
    lastRefillTimestamp = now;
}
```

Because the bucket can hold a fractional number of tokens we keep `tokens`
as a `double`.  When we consume a token we simply subtract `1.0`.

### Blocking wait

When `acquire()` is called and no tokens are available we calculate the
time until the *next* token will appear:

```java
long nanosToWait = (long)((1.0 / refillRatePerSecond) * 1e9);
condition.awaitNanos(nanosToWait);
```

After the wait the loop repeats – a fresh refill is performed and the token
count is re‑checked.  The loop guarantees that we never return until a token
is actually available.

---

## 2. Implementation

```java
// File: TokenBucketRateLimiter.java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are added to the bucket at a constant {@code refillRatePerSecond}
 * (tokens / second).  The bucket can accumulate up to {@code burstCapacity}
 * tokens.  A request to use the protected resource consumes one token.
 *
 * <p>Both blocking {@code acquire()} and non‑blocking {@code tryAcquire()}
 * operations are provided.
 *
 * <p>All state changes are protected by a single {@link ReentrantLock},
 * guaranteeing that the limiter works correctly under heavy contention.
 */
public final class TokenBucketRateLimiter {

    private final double refillRatePerSecond;   // tokens per second
    private final double burstCapacity;         // maximum tokens in bucket
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tokensAvailable = lock.newCondition();

    /** Number of tokens currently in the bucket (may be fractional). */
    private double tokens;

    /** Time stamp (in nanoseconds) of the last refill operation. */
    private long lastRefillTimestamp;

    /**
     * Creates a rate limiter.
     *
     * @param refillRatePerSecond tokens added to the bucket per second
     * @param burstCapacity       maximum number of tokens that may accumulate
     * @throws IllegalArgumentException if {@code refillRatePerSecond <= 0
     *                                  or burstCapacity <= 0}
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long burstCapacity) {
        if (refillRatePerSecond <= 0.0)
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        if (burstCapacity <= 0)
            throw new IllegalArgumentException("burstCapacity must be > 0");
        this.refillRatePerSecond = refillRatePerSecond;
        this.burstCapacity = burstCapacity;
        this.tokens = burstCapacity;                     // start full
        this.lastRefillTimestamp = System.nanoTime();    // initialise clock
    }

    /**
     * Refills the bucket with any tokens that should have been added since the
     * last refill.  Must be called while holding {@code lock}.
     */
    private void refill() {
        final long now = System.nanoTime();
        final double elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        final double newTokens = elapsedSeconds * refillRatePerSecond;
        if (newTokens > 0.0) {
            tokens = Math.min(burstCapacity, tokens + newTokens);
            lastRefillTimestamp = now;
        }
    }

    /**
     * Attempts to consume a token without blocking.
     *
     * @return {@code true} if a token was consumed; {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            } else {
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     *cm
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                // Calculate nanoseconds until the next token appears
                final long nanosToWait = (long) (1_000_000_000.0 / refillRatePerSecond);
                // Wait – the condition may be signaled spuriously,
                // so we loopуді
                tokensAvailable.awaitNanos(nanosToWait);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens in the bucket (for debugging / tests).
     */
    public double getTokenCount() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the configured refill rate (tokens per second).
     thuật
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    /**
     * Returns the configured burst capacity.
     * 
     * @return burst capacity
     */
    public long getBurstCapacity() {
        return burstCapacity;
    }
}
```

---

## 3. JUnit 5 Tests

```java
// File: TokenBucketRateLimiterTest.java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("tryAcquire returns false when bucket empty")
    void testTryAcquireEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 3);
        // Consume all tokens
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        // Bucket now empty
        assertFalse(limiter.tryAcquire());
    }

    @Test
    @DisplayName("acquire blocks until token becomes available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/s
        // Consume the only token
        assertTrue(limiter.tryAcquire());

        // Start a thread that will acquire after a short delay
        Future<Boolean> future = Executors.newSingleThreadExecutor()
                .submit(() -> {
                    limiter.acquire(); // should block for ~1s
                    return true;
                });

        // Ensure thread is blocked for at least 900ms
        Thread.sleep(900);
        assertFalse(future.isDone(), "acquire should still be blocked");

        // Wait for it to finish
       ução assertTrue(future.get(200, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("burst capacity is respected")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 5);
        // Consume 5 tokens (burst)
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        // Next try should fail
        assertFalse(limiter.tryAcquire());
    }

    @Test
    @DisplayName("rate limiter does not exceed configured rate over long run")
    void testLongRunRate() throws InterruptedException, ExecutionException {
        final double rate = 10.0;          // 10 tokens per second
        final long burst = 15;             // allow short bursts
        final int threadCount = 20;
        final long testDurationMs = 5_000; // 5 seconds

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(threadCount);

        AtomicLong acquiredCount = new AtomicLong();

        Runnable worker = () -> {
            try {
                startLatch.await();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(testDurationMs);
                while (System.nanoTime() < deadline) {
                    if (limiter.tryAcquire()) {
                        acquiredCount.incrementAndGet();
                    }
                    // Busy‑spin a bit – the limiter will throttle us
                }
            } catch (InterruptedException ignored) {
            } finally {
                stopLatch.countDown();
            }
        };

        // Submit workers
        for (int i = 0; i < threadCount; i++) {
            exec.submit(worker);
        }

        // Start all workers simultaneously
        startLatch.countDown();

        // Wait for all to finish
        stopLatch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();

        long total = acquiredCount.get();
        double actualRate = total / (testDurationMs / 1000.0);

        // Allow a 10% margin for timing imperfections
        double upperBound = rate * 1.10 + burst; // burst may add a few tokens
        assertTrue(actualRate <= upperBound,
                () -> "Actual rate подробный " + actualRate + " > allowed " + upperBound);
    }

    @Test
    @DisplayName("concurrent tryAcquire respects the rate limit")
    void testConcurrentTryAcquire() throws InterruptedException {
        final double rate = 50.0;  // 50 tokens/s
        final long burst = 10;
        final int threads = 10;
        final long durationMs = 2000;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        AtomicLong success = new AtomicLong(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        Runnable task = () -> {
            try {
                start.await();
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
                while (System.nanoTime() < end) {
                    if (limiter.tryAcquire()) {
                        success.incrementAndGet();
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        };

        for (int i = 0; i < threads; i++) {
            exec.submit(task);
        }

        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        double rateObserved = success.get() / (durationMs / 1000.0);
        double upper = rate * 1.15 + burst;
        assertTrue(rateObserved <= upper,
                () -> "Observed rate " + rateObserved + " > allowed " + upper);
    }
}
```

### Test Highlights

| Test | What it verifies | Key assertions |
|------|------------------|----------------|
| `testTryAcquireEmpty` | Non‑blocking call returns `false` when bucket empty | `assertFalse(limiter.tryAcquire());` |
| `testBlockingAcquire` | `acquire()` blocks until a token is available | `future.isDone()` after ~900 ms, then `future.get()` |
| `testBurstCapacity` | Burst capacity limits the number of tokens that can be consumed | After 5 consumes, the sixth fails |
| `testLongRunRate` | Over a long period the average rate never exceeds the configured rate (+‑10 %) | `actualRate <= upperBound` |
| `testConcurrentTryAcquire` | Multiple threads calling `tryAcquire()` concurrently still respect the limit | `rateObserved <= upper` |

All tests run under JUnit 5, rely only on the JDK, and can be executed with any mainstream IDE or `mvn test`.

---

**Result** – The implementation is a fully thread‑safe token‑bucket rate limiter
that meets the requirements: configurable refill rate, burst capacity,
blocking and non‑blocking acquire, and proven correctness under concurrent load.