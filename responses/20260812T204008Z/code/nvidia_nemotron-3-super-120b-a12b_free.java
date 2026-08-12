<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A token‑bucket rate limiter works as follows:

* A bucket can hold at most **capacity** tokens.
* Tokens are added continuously at a **refillRate** (tokens / second).
* When a thread wants to execute an action it must first **acquire** one token.
  * If a token is available the action proceeds immediately.
  * If no token is available the thread either:
    * returns `false` (non‑blocking `tryAcquire`), or
    * blocks until a token becomes available (blocking `acquire`).

The implementation below is **thread‑safe** – all mutable state is protected by a single `ReentrantLock`.  
Time is measured with `System.nanoTime()` to avoid problems with clock adjustments.  
Fractional tokens are stored as a `double`; this makes the math simple and avoids rounding‑error accumulation.

---

## 1. Token Bucket Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable burst capacity (maximum number of tokens the bucket can hold).</li>
 *   <li>Configurable refill rate (tokens per second).</li>
 *   <li>Non‑blocking {@link #tryAcquire()} that returns immediately.</li>
 *   <li>Blocking {@link #acquire()} that waits until a token is available.</li>
 *   <li>Interruptible blocking acquire ({@link #acquireInterruptibly()}).</li>
 * </ul>
 *
 * <p>The implementation uses a single {@link ReentrantLock} and a {@link Condition}
 * to make the blocking acquire efficient – threads only wait the exact time
 * needed for the next token to become available (or are woken up early on
 * spurious wake‑ups, which are handled by a loop).
 *
 * <p>All time calculations are performed in nanoseconds using {@link System#nanoTime()}
 * to be immune to system‑clock changes.
 */
public class TokenBucketRateLimiter {

    private final double capacity;               // maximum tokens the bucket can hold
    private final double refillRatePerSec;       // tokens added per second
    private volatile double tokens;              // current token count (may be fractional)
    private volatile long lastRefillNanos;       // timestamp of the last refill

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket.
     *
     * @param capacity        maximum number of tokens the bucket can hold (burst size)
     * @param refillRatePerSec long‑term average rate at which tokens are added (tokens/second)
     * @throws IllegalArgumentException if capacity <= 0 or refillRatePerSec <= 0
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
        this.tokens = capacity; // start with a full bucket
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            double tokensToAdd = ((double) elapsedNanos / 1_000_000_000.0) * refillRatePerSec;
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was acquired, {@code false} otherwise
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
     * Acquires a token, blocking until one becomes available.
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
                    return;
                }
                // Compute how long we need to wait for at least one token
                double needed = 1.0 - tokens; // tokens still missing (0 < needed <= 1)
                long waitNanos = (long) (needed / refillRatePerSec * 1_000_000_000.0);
                // awaitNanos returns remaining time; we loop to handle spurious wake‑ups
                if (notEmpty.awaitNanos(waitNanos) <= 0) {
                    // timeout elapsed – retry (refill may have happened)
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires a token, blocking until one becomes available or the thread is interrupted.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquireInterruptibly() throws InterruptedException {
        acquire(); // same implementation; lockInterruptibly already used
    }

    /**
     * Returns the current number of tokens in the bucket (for testing/debugging).
     * This method is *not* synchronized with acquisition operations; it provides a
     * snapshot that may be stale by the time it is used.
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

### Design Notes

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Thread safety** | Single `ReentrantLock` protects all mutable state (`tokens`, `lastRefillNanos`). | Simpler than fine‑grained locking; contention is low because the critical section is tiny (just a few arithmetic ops). |
| **Time source** | `System.nanoTime()` (monotonic). | Immune to wall‑clock adjustments; provides high‑resolution timing needed for accurate refill. |
| **Token representation** | `double` (fractional tokens). | Allows exact rate calculations without rounding to whole tokens; the bucket can still only grant whole tokens (`>=1`). |
| **Blocking wait** | `Condition.awaitNanos(timeout)`. | Threads sleep only the exact time needed for the next token, reducing CPU usage. Loop handles spurious wake‑ups. |
| **Interruptibility** | `lock.lockInterruptibly()` + `awaitNanos`. | Standard Java idiom; interruption propagates as `InterruptedException`. |
| **Burst capacity** | Initialized to `capacity` tokens; `refill()` never lets `tokens` exceed `capacity`. | Allows short‑term spikes up to the configured burst size. |
| **Public API** | `tryAcquire()` (non‑blocking), `acquire()` (blocking), `acquireInterruptibly()`. | Matches typical rate‑limiter usage patterns. |
| **Testing hook** | `getTokens()` (snapshot). | Useful for unit tests; not part of the production contract. |

---

## 2. JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** Tolerance for rate checks (5 %). */
    private static final double RATE_TOLERANCE = 0.05;

    @Test
    void testTryAcquireInitialBurst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0); // 5 burst, 1 token/sec

        // Should be able to acquire up to the burst immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should acquire token " + i);
        }
        // Sixth attempt should fail because bucket is empty and refill is too slow
        assertFalse(limiter.tryAcquire(), "No token should be available immediately");
    }

    @Test
    void testTryAcquireRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 2.0); // 2 burst, 2 tokens/sec

        // Drain the bucket
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait 0.6 s → should have refilled 1.2 tokens (enough for one more)
        Thread.sleep(600);
        assertTrue(limiter.tryAcquire(), "One token should be available after refill");
        // Bucket now has ~0.2 tokens left → not enough for another immediate acquire
        assertFalse(limiter.tryAcquire());

        // Wait another 0.6 s → total refill ~2.4 tokens → enough for a second acquire
        Thread.sleep(600);
        assertTrue(limiter.tryAcquire(), "Second token should be available after more refill");
    }

    @Test
    void testBlockingAcquireRespectsRate() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 2.0); // 1 burst, 2 tokens/sec

        // Acquire the single token immediately
        assertTrue(limiter.tryAcquire());

        long start = System.nanoTime();
        // The next acquire should block until ~0.5 s have passed (1 token / 2 per sec)
        limiter.acquire();
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = elapsedNs / 1_000_000_000.0;

        assertTrue(elapsedSec >= 0.45 && elapsedSec <= 0.55,
                "Blocking acquire should wait roughly 0.5 s, got " + elapsedSec + "s");
    }

    @Test
    void testConcurrentLongRunRate() throws Exception {
        int numThreads = 20;
        double ratePerSec = 10.0; // target long‑term rate
        double capacity = 5.0;    // allow modest burst
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSec);

        int testDurationSec = 4; // run test for a few seconds to smooth out bursts
        AtomicLong successes = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long localCount = 0;
                    long endTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(testDurationSec);
                    while (System.nanoTime() < endTime) {
                        if (limiter.tryAcquire()) {
                            localCount++;
                        } else {
                            // optional tiny back‑off to reduce contention
                            Thread.sleep(0, 100); // 100 ns sleep (practically a yield)
                        }
                    }
                    successes.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Kick off all workers simultaneously
        startLatch.countDown();
        // Wait for all threads to finish
        assertTrue(doneLatch.await(testDurationSec + 5, TimeUnit.SECONDS),
                "Threads did not finish in expected time");
        exec.shutdownNow();

        long total = successes.get();
        double actualRate = total / (double) testDurationSec;

        // Allow a small tolerance because of burst capacity and scheduling jitter
        double maxAllowed = ratePerSec * (1.0 + RATE_TOLERANCE);
        assertTrue(actualRate <= maxAllowed,
                String.format("Observed rate %.2f tokens/s exceeds allowed %.2f tokens/s (limit %.2f + %.0f%%)",
                        actualRate, maxAllowed, ratePerSec, RATE_TOLERANCE * 100));

        // Also ensure we are not far below the rate (the limiter should be able to keep up)
        double minExpected = ratePerSec * (1.0 - RATE_TOLERANCE);
        assertTrue(actualRate >= minExpected,
                String.format("Observed rate %.2f tokens/s is too low (< %.2f tokens/s)",
                        actualRate, minExpected));
    }

    @Test
    void testBlockingAcquireDoesNotExceedRateUnderLoad() throws Exception {
        int numThreads = 10;
        double rate = 5.0; // tokens per sec
        double capacity = 2.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, rate);

        int durationSec = 3;
        AtomicLong acquired = new AtomicLong(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long local = 0;
                    long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
                    while (System.nanoTime() < stop) {
                        try {
                            limiter.acquire(); // blocks until a token is available
                            local++;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    acquired.addAndGet(local);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(durationSec + 5, TimeUnit.SECONDS), "Threads did not finish");
        pool.shutdownNow();

        long total = acquired.get();
        double observedRate = total / (double) durationSec;
        double maxAllowed = rate * (1.0 + RATE_TOLERANCE);
        assertTrue(observedRate <= maxAllowed,
                String.format("Blocking acquire exceeded rate: %.2f > %.2f (limit %.2f + %.0f%%)",
                        observedRate, maxAllowed, rate, RATE_TOLERANCE * 100));
    }
}
```

### Test Explanation

| Test | What it verifies |
|------|------------------|
| `testTryAcquireInitialBurst` | The bucket starts full and can grant up to `capacity` tokens immediately. |
| `testTryAcquireRefillOverTime` | Tokens are refilled correctly over time; after a wait the bucket can grant again. |
| `testBlockingAcquireRespectsRate` | A blocking `acquire()` waits the exact time needed for a token to appear (based on rate). |
| `testConcurrentLongRunRate` | Many threads repeatedly call `tryAcquire()` for a fixed interval; the total number of acquisitions per second never exceeds the configured rate (plus a small tolerance). This is the **core concurrent correctness** test. |
| `testBlockingAcquireDoesNotExceedRateUnderLoad` | Similar to the concurrent test but using the blocking `acquire()`; ensures that even when threads are forced to wait, the long‑term throughput does not exceed the limit. |

The tolerance (`RATE_TOLERANCE = 5 %`) accommodates:

* Burst capacity (the limiter may briefly exceed the average rate).
* Scheduling jitter and the granularity of `Thread.sleep`/`System.nanoTime`.
* Minor errors introduced by floating‑point arithmetic.

---

**Usage Example**

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
        capacity = 10.0,   // allow bursts of up to 10 requests
        refillRatePerSec = 5.0 // long‑term limit of 5 requests per second
);

// In your request‑handling code:
if (limiter.tryAcquire()) {
    // process request
} else {
    // reject or queue request (rate limit exceeded)
}
```

The limiter is lightweight, has no external dependencies, and is safe for concurrent use by any number of threads. Happy rate‑limiting!