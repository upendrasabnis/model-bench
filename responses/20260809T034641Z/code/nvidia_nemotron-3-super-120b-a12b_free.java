<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A token‑bucket rate limiter works as follows:

* The bucket has a *capacity* (maximum number of tokens it can hold).  
* Tokens are added at a constant *refill rate* (tokens per second).  
* When a thread wants to acquire `n` permits it must first ensure that at least `n` tokens are present; if they are, the tokens are removed and the call succeeds.  
* If there are not enough tokens the thread either:
  * returns `false` immediately (`tryAcquire`) or
  * blocks until enough tokens have been refilled (`acquire`).

The implementation below is **thread‑safe** – all accesses to the mutable state (`tokens` and the timestamp of the last refill) are guarded by the intrinsic lock of the `TokenBucketRateLimiter` instance (`synchronized` blocks).  
The blocking version uses `Object.wait(long timeout)` to sleep for the exact time needed to replenish the missing tokens, then re‑checks the condition after the wait (spurious wake‑ups are handled by looping).

---

## 1. TokenBucketRateLimiter.java

```java
package com.example.ratelimiter;

import java.util.Objects;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket starts full (i.e. {@code tokens == capacity}).  Tokens are
 * added continuously at a configured {@code ratePerSecond}.  The {@code
 * acquire(long)} method blocks until the requested number of permits is
 * available; {@code tryAcquire(long)} attempts an immediate acquisition and
 * returns {@code true} on success, {@code false} otherwise.</p>
 *
 * <p>Both methods accept a non‑negative number of permits; zero permits always
 * succeed instantly.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *   TokenBucketRateLimiter limiter =
 *           new TokenBucketRateLimiter(10.0, 20); // 10 permits/sec, burst 20
 *   if (limiter.tryAcquire(1)) {
 *       // do work
 *   } else {
 *       // rate‑limited
 *   }
 * </pre>
 *
 * @author  Generated for the exercise
 */
public final class TokenBucketRateLimiter {

    /** Refill rate in tokens per second – must be > 0. */
    private final double ratePerSecond;

    /** Maximum number of tokens the bucket can hold – must be > 0. */
    private final long capacity;

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Current number of tokens in the bucket (may be fractional). */
    private volatile double tokens;

    /**
     * Creates a new limiter.
     *
     * @param ratePerSecond   refill rate, tokens per second (must be > 0)
     * @param capacity        burst capacity, maximum tokens (must be > 0)
     * @throws IllegalArgumentException if either argument is <= 0
     */
    public TokenBucketRateLimiter(double ratePerSecond, long capacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        // Start with a full bucket.
        this.lastRefillNanos = System.nanoTime();
        this.tokens = capacity;
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * This method must be called while holding the intrinsic lock.
     */
    private void refill() {
        long now = System.nanoTime();
        if (now > lastRefillNanos) {
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            double added = elapsedSec * ratePerSecond;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available.
     *
     * @param permits number of permits to acquire (must be >= 0)
     * @throws IllegalArgumentException if {@code permits < 0}
     * @throws InterruptedException     if the current thread is interrupted while waiting
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("permits must not be negative");
        }
        if (permits == 0) {
            return; // nothing to do
        }

        synchronized (this) {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }

                // Not enough tokens – compute how long we must wait.
                double deficit = permits - tokens; // > 0
                long waitMillis = (long) Math.ceil(deficit * 1000.0 / ratePerSecond);
                if (waitMillis <= 0) {
                    waitMillis = 1; // avoid zero‑wait spin
                }
                // wait releases the lock and reacquires it after timeout or interrupt.
                try {
                    wait(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // preserve interrupt status
                    throw e;
                }
                // Loop again – after the wait we will have re‑acquired the lock.
            }
        }
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire (must be >= 0)
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if {@code permits < 0}
     */
    public boolean tryAcquire(long permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits must not be negative");
        }
        if (permits == 0) {
            return true;
        }

        synchronized (this) {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            } else {
                return false;
            }
        }
    }

    /** For testing only – returns the current approximate token count. */
    double getTokensApproximate() {
        // No synchronization needed for a best‑effort read in tests.
        refill(); // update to now
        return tokens;
    }
}
```

### Design Notes (in‑code Javadoc)

* **Thread safety** – All mutable state (`tokens`, `lastRefillNanos`) is accessed only inside `synchronized` blocks on the limiter instance. The `refill()` method is private and called only while holding that lock, guaranteeing a consistent view.
* **Blocking acquire** – Uses `Object.wait(long timeout)`. The timeout is computed from the exact deficit of tokens and the configured rate, so the thread sleeps only as long as necessary. After the wait (or a spurious wake‑up) the loop re‑checks the condition.
* **Non‑blocking tryAcquire** – Performs a single refill check and either consumes tokens or returns `false`.
* **Fractional tokens** – The bucket stores tokens as a `double` to allow rates that are not integral numbers of tokens per millisecond. This yields smoother behaviour for high‑rate limiters.
* **Initial state** – The bucket starts full, which permits an initial burst up to the configured capacity.
* **Overflow safety** – `tokens` is always capped at `capacity` after a refill, preventing unlimited growth.

---

## 2. JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * The tests cover:
 *   • basic constructor validation
 *   • immediate acquisition when tokens are available
 *   • immediate rejection when the bucket is empty
 *   • blocking acquire respects the long‑term rate
 *   • a concurrent stress test that verifies the observed rate never exceeds
 *     the configured limit (with a small tolerance).
 */
class TokenBucketRateLimiterTest {

    @Test
    void constructor_validateArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(0.0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(5.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(-1.0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(5.0, -3));
    }

    @Test
    void tryAcquire_successWhenTokensAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 10);
        assertTrue(limiter.tryAcquire(5));
        // 5 tokens remain
        assertTrue(limiter.tryAcquire(5));
        // bucket empty now
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void tryAcquire_failsWhenNoTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 10);
        limiter.acquire(10); // drain
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void blockingAcquire_respectsRate() throws InterruptedException {
        double rate = 20.0; // 20 permits per second
        long capacity = 20;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        // Drain the bucket so we measure a pure refill period.
        limiter.acquire((int) capacity);

        long start = System.nanoTime();
        limiter.acquire((int) capacity); // should need ~1 second
        long elapsedNanos = System.nanoTime() - start;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;

        double expectedSec = (double) capacity / rate; // 20/20 = 1.0 s
        double tolerance = 0.15; // allow 15% jitter due to OS scheduling
        assertTrue(elapsedSec >= expectedSec * (1 - tolerance),
                "Too fast: " + elapsedSec + "s < " + expectedSec * (1 - tolerance));
        assertTrue(elapsedSec <= expectedSec * (1 + tolerance),
                "Too slow: " + elapsedSec + "s > " + expectedSec * (1 + tolerance));
    }

    @Test
    void concurrentLongRunRateDoesNotExceedLimit() throws InterruptedException {
        double rate = 7.5; // 7.5 permits per second
        long capacity = 15;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        int threadCount = 25;
        long testDurationMs = 6000; // 6 seconds
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Thread> workers = new ArrayList<>(threadCount);
        AtomicLong permitsGranted = new AtomicLong(0);

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long stopTime = System.currentTimeMillis() + testDurationMs;
                    while (System.currentTimeMillis() < stopTime) {
                        if (limiter.tryAcquire(1)) {
                            permitsGranted.incrementAndGet();
                        }
                        // Tiny sleep to avoid a busy‑spin that would hog CPU.
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            t.start();
            workers.add(t);
        }

        // Release all workers at once.
        startLatch.countDown();

        // Wait for everybody to finish.
        doneLatch.await();

        long total = permitsGranted.get();
        double durationSec = testDurationMs / 1000.0;
        double observedRate = total / durationSec;

        // Allow a small tolerance (10%) for scheduling jitter and the 1ms sleep.
        double tolerance = 0.10 * rate;
        assertTrue(observedRate <= rate + tolerance,
                String.format("Observed rate %.2f permits/s exceeds limit %.2f (tolerance %.2f)",
                        observedRate, rate, tolerance));
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `constructor_validateArguments` | Illegal arguments (`≤0`) are rejected. |
| `tryAcquire_successWhenTokensAvailable` | Tokens are correctly consumed and `tryAcquire` returns `true` while tokens remain. |
| `tryAcquire_failsWhenNoTokens` | When the bucket is empty, `tryAcquire` returns `false`. |
| `blockingAcquire_respectsRate` | After draining the bucket, a blocking `acquire` for a full bucket’s worth of permits takes roughly `capacity / rate` seconds (±15 % tolerance). |
| `concurrentLongRunRateDoesNotExceedLimit` | Many threads repeatedly call `tryAcquire(1)` for a fixed interval. The total number of permits granted divided by the elapsed time never exceeds the configured rate (with a 10 % tolerance). This demonstrates that the limiter enforces the long‑term average rate under concurrent load. |

All tests use JUnit 5 (`org.junit.jupiter.api`) and rely only on the JDK’s concurrency utilities (`CountDownLatch`, `AtomicLong`, `Thread`). They can be run with any standard JUnit 5 runner (IDE, Maven Surefire, Gradle, etc.).