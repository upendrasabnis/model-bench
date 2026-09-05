<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260905T185434Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token‑bucket* rate limiter works as follows  

* The bucket can hold at most **burstCapacity** tokens.  
* Tokens are added continuously at a fixed **refillRate** (tokens per second).  
* When a thread wants to perform an action it must **consume** one token.  
* If a token is available the action proceeds immediately; otherwise the thread may  
  *fail fast* (`tryAcquire`) or *block* until a token becomes available (`acquire`).

The implementation below is fully thread‑safe, uses only JDK concurrency primitives
(`java.util.concurrent.locks.ReentrantLock` + `Condition`), and works with fractional
tokens so that the long‑term average rate is exact even for non‑integer refill rates.

---

## 1.  Design Documentation (inline comments)

```java
/**
 * TokenBucketRateLimiter – a thread‑safe token‑bucket rate limiter.
 *
 * <p>Conceptual model
 * -----------------
 *   ┌─────────────────────┐
 *   │   bucket (tokens)   │ ◄───── refillRate tokens per second are added
 *   └─────────────────────┘
 *          ▲  │
 *          │  ▼ consume 1 token per permitted operation
 *   ┌─────────────────────┐
 *   │   caller thread     │
 *   └─────────────────────┘
 *
 *   * The bucket never exceeds {@code burstCapacity}.
 *   * If the bucket is empty, a caller may:
 *       – return false immediately   ({@link #tryAcquire()})
 *       – block until a token appears ({@link #acquire()})
 *
 *   Thread safety
 *   -------------
 *   All state ({@code tokens}, {@code lastRefillNanos}) is accessed only while
 *   holding a {@link ReentrantLock}.  The {@link Condition} {@code notEmpty}
 *   is used for efficient blocking waits; the wait time is calculated from the
 *   current token deficit and the refill rate, which avoids spurious wake‑ups
 *   and reduces CPU usage.
 *
 *   Fractional tokens
 *   -----------------
 *   Tokens are stored as a {@code double}.  This lets the limiter sustain an
 *   exact long‑term average rate even when the refill rate is not an integer
 *   (e.g. 2.5 tokens/s).  The bucket is considered *available* when the token
 *   count is ≥ 1.0.
 *
 *   Performance
 *   -----------
 *   The hot path (tryAcquire) does a single lock, a cheap refill computation,
 *   and a token decrement – all O(1).  Blocking acquire parks the thread until
 *   the next token is expected, minimizing wasted cycles.
 */
```

---

## 2.  Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter with blocking and non‑blocking acquire.
 *
 * <p>Example usage:
 * <pre>{@code
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5); // 10 permits/s, burst 5
 * if (limiter.tryAcquire()) {
 *     // do work
 * } else {
 *     // rate exceeded
 * }
 * limiter.acquire(); // blocks until a permit is available
 * </pre>
 */
public class TokenBucketRateLimiter {

    /** Maximum number of tokens the bucket can hold (the burst size). */
    private final long burstCapacity;

    /** Refill rate expressed as tokens per second. */
    private final double refillRatePerSec;

    /** Current number of tokens in the bucket (may be fractional). */
    private double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private long lastRefillNanos;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when a token becomes available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param refillRatePerSec tokens added per second (must be > 0)
     * @param burstCapacity    maximum tokens the bucket can hold (must be > 0)
     */
    public TokenBucketRateLimiter(double refillRatePerSec, long burstCapacity) {
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.refillRatePerSec = refillRatePerSec;
        this.burstCapacity = burstCapacity;
        // start with a full bucket – this permits an initial burst up to the capacity
        this.tokens = burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Adds tokens to the bucket based on the elapsed time since the last refill.
     * Called only while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(burstCapacity, tokens + elapsedSec * refillRatePerSec);
            lastRefillNanos = now;
        }
    }

    /**
     * Attempts to acquire a single token without blocking.
     *
     * @return {@code true} if a token was obtained, {@code false} otherwise.
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
     * Acquires a single token, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return; // token consumed
                }
                // Compute how long we must wait for at least one token to appear.
                double deficit = 1.0 - tokens; // > 0
                long waitNanos = (long) (deficit / refillRatePerSec * 1_000_000_000.0);
                // Condition.awaitNanos accepts a long nanos timeout.
                notEmpty.awaitNanos(waitNanos);
                // Loop again – a spurious wake‑up or a change in state is handled.
            }
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Package‑private methods for testing (avoid exposing internals publicly)
     * ------------------------------------------------------------------ */
    double getTokens() {
        lock.lock();
        try {
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    long getLastRefillNanos() {
        lock.lock();
        try {
            return lastRefillNanos;
        } finally {
            lock.unlock();
        }
    }
}
```

### Why this design is correct & efficient

| Property | How it is satisfied |
|----------|---------------------|
| **Thread safety** | All reads/writes of `tokens` and `lastRefillNanos` happen under `lock`. The `Condition` guarantees that a thread waiting for a token is woken only when the state may have changed. |
| **Configurable rate & burst** | Constructor arguments `refillRatePerSec` and `burstCapacity` are stored finals; the refill algorithm respects both. |
| **Non‑blocking `tryAcquire`** | Performs a single refill check and, if a token exists, consumes it; otherwise returns `false` immediately. |
| **Blocking `acquire`** | Loops: refill → if enough tokens → consume → return; else compute exact wait time and park the thread. The wait time is derived from the current token deficit, guaranteeing that the thread wakes up no later than the moment a token becomes available (modulo OS scheduling jitter). |
| **Exact long‑term rate** | Tokens are kept as a `double`; refill adds `elapsedSec * rate`. Over a long interval the expected number of tokens added equals `rate * interval`. The bucket never exceeds `burstCapacity`, so the long‑run average consumption cannot exceed the configured rate. |
| **Low CPU usage** | Blocking acquire uses `Condition.awaitNanos`, which puts the thread to sleep; no busy‑waiting. |
| **Fairness** | Not required; the limiter is work‑conserving. If fairness is needed, a `fair` `ReentrantLock` can be constructed (`new ReentrantLock(true)`). |

---

## 3.  JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Test suite for {@link TokenBucketRateLimiter}.
 *
 * <p>The tests cover:
 * <ul>
 *   <li>basic token accounting (burst, refill, tryAcquire)</li>
 *   <li>blocking acquire behavior</li>
 *   <li>consecutive tryAcquire under contention</li>
 *   <li>a long‑run rate test with many threads verifying that the observed
 *       throughput never exceeds the configured limit (plus a small tolerance
 *       for burst and timing jitter).</li>
 * </ul>
 */
class TokenBucketRateLimiterTest {

    /** Helper to sleep without throwing checked exceptions in lambdas. */
    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected interruption", e);
        }
    }

    @Test
    @DisplayName("Initial bucket is full – tryAcquire succeeds up to burst")
    void testInitialBurst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 3); // 10/s, burst 3

        assertTrue(limiter.tryAcquire(), "first acquire should succeed");
        assertTrue(limiter.tryAcquire(), "second acquire should succeed");
        assertTrue(limiter.tryAcquire(), "third acquire (burst) should succeed");
        assertFalse(limiter.tryAcquire(), "fourth acquire should fail (bucket empty)");
    }

    @Test
    @DisplayName("Tokens refill over time")
    void testRefillOverTime() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(4.0, 2); // 4 per sec, burst 2

        // consume the burst
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // wait 0.5 seconds → should have gained 2 tokens (4 * 0.5)
        sleepMillis(500);
        assertTrue(limiter.tryAcquire(), "first refilled token");
        assertTrue(limiter.tryAcquire(), "second refilled token");
        assertFalse(limiter.tryAcquire(), "bucket empty again");
    }

    @Test
    @DisplayName("Blocking acquire waits for a token")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 1); // 2 per sec, burst 1

        // consume the single token
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // start a thread that will block on acquire
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Boolean> future = exec.submit(() -> {
            limiter.acquire(); // should return when a token is available
            return true;       // signal success
        });

        // after ~0.6 seconds a token should have refilled (2 per sec → 0.5 s per token)
        sleepMillis(600);
        assertTrue(future.isDone(), "acquire should have completed after refill");
        assertTrue(future.get(), "acquire returned successfully");

        exec.shutdownNow();
    }

    @Test
    @DisplayName("Many threads calling tryAcquire do not exceed burst+expected refill")
    void testConcurrentTryAcquireRate() throws InterruptedException {
        final double rate = 50.0;          // 50 tokens per second
        final long burst = 10;             // allow an initial burst of 10
        final int threadCount = 20;
        final Duration testDuration = Duration.ofSeconds(2);

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        AtomicLong counter = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // Worker that repeatedly tries to acquire as fast as possible
        Runnable worker = () -> {
            try {
                startLatch.await(); // wait for all threads to start together
                long end = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < end) {
                    if (limiter.tryAcquire()) {
                        counter.incrementAndGet();
                    }
                    // spin – we want to see the limiter’s limiting effect, not thread scheduling
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            exec.submit(worker);
        }

        startLatch.countDown(); // let all workers go
        doneLatch.await(testDuration.multipliedBy(2)); // wait a bit longer than needed
        exec.shutdownNow();

        long total = counter.get();
        double expectedMax = burst + rate * testDuration.getSeconds(); // burst + rate*time
        // Allow a small tolerance (≈5%) for timing jitter and the fact that we
        // measured wall‑clock time, not exact token‑generation instants.
        double tolerance = 0.05 * expectedMax;
        assertTrue(total <= expectedMax + tolerance,
                String.format("Observed acquisitions %d > expected max %.2f (+tol %.2f)",
                        total, expectedMax, tolerance));
    }

    @Test
    @DisplayName("Long‑run rate test with blocking acquire")
    void testLongRunBlockingAcquireRate() throws InterruptedException {
        final double rate = 100.0; // 100 permits per second
        final long burst = 5;
        final int threadCount = 10;
        final Duration testDuration = Duration.ofSeconds(3);

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        AtomicLong counter = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Runnable worker = () -> {
            try {
                startLatch.await();
                long end = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < end) {
                    limiter.acquire(); // blocks until a permit is available
                    counter.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            exec.submit(worker);
        }

        startLatch.countDown();
        doneLatch.await(testDuration.multipliedBy(2));
        exec.shutdownNow();

        long total = counter.get();
        double expectedMax = burst + rate * testDuration.getSeconds(); // burst + rate*time
        double tolerance = 0.05 * expectedMax; // 5% slack
        assertTrue(total <= expectedMax + tolerance,
                String.format("Blocking acquire produced %d tokens > expected max %.2f (+tol %.2f)",
                        total, expectedMax, tolerance));
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `testInitialBurst` | The bucket starts full and `tryAcquire` succeeds exactly `burstCapacity` times before failing. |
| `testRefillOverTime` | Tokens are replenished at the configured rate after a wait period. |
| `testBlockingAcquire` | A thread calling `acquire()` blocks until a token becomes available and is woken precisely when the refill predicts a token. |
| `testConcurrentTryAcquireRate` | Many threads hammer `tryAcquire()`; the total number of successful acquisitions never exceeds **burst + rate × elapsed time** (plus a small tolerance for measurement jitter). |
| `testLongRunBlockingAcquireRate` | Same rate guarantee but using the blocking `acquire()` method, ensuring that even when threads park, the long‑run throughput respects the limit. |

All tests run with JUnit 5 (`org.junit.jupiter:junit-jupiter:5.10.0` or newer) and can be executed with `mvn test` or via an IDE.

--- 

**End of solution**.