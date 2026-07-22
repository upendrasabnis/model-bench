<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

A *token bucket* regulator works as follows:

* The bucket has a **maximum capacity** (`capacity`) – the largest number of tokens it can hold.
* Tokens are added at a constant **refill rate** (`refillRatePerSec`).  
  After each `nanosecond` that passes we add  
  `refillRatePerSec / 1_000_000_000` tokens.
* An operation that wants to execute consumes **one token**.  
  If a token is available the call succeeds immediately; otherwise it must wait
  until enough time has elapsed for a token to be refilled.

To be safe for concurrent use we protect all mutable state (`tokens`,
`lastRefillNanos`) with a `java.util.concurrent.locks.ReentrantLock`.  
A `Condition` (`notEmpty`) is used so that threads waiting in `acquire()`
can be parked efficiently instead of spinning.

The implementation supplies three public methods:

| Method | Behaviour |
|--------|-----------|
| `void acquire() throws InterruptedException` | Blocks until a token can be taken (or the thread is interrupted). |
| `boolean tryAcquire()` | Non‑blocking – returns `true` iff a token was taken immediately. |
| `boolean tryAcquire(long timeout, TimeUnit unit)` | Tries to acquire a token, waiting at most the given timeout. Returns `true` on success, `false` on timeout. |

The bucket starts **full** (`tokens = capacity`) so that an initial burst up to the
capacity is allowed – this matches the typical token‑bucket semantics.

---

### TokenBucket.java
```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket refills continuously at a configurable rate (tokens per second)
 * and can hold at most {@code capacity} tokens.  Consuming a token corresponds
 * to granting permission to execute an action.</p>
 *
 * <p>All public methods are safe for concurrent invocation by any number of
 * threads.</p>
 */
public class TokenBucket {

    /** Maximum number of tokens the bucket can hold. */
    private final long capacity;

    /** Refill rate expressed as tokens per nanosecond. */
    private final double refillRatePerNanos;

    /** Protects {@code tokens} and {@code lastRefillNanos}. */
    private final Lock lock = new ReentrantLock();

    /** Signalled when a token may have become available. */
    private final Condition notEmpty = lock.newCondition();

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Current number of tokens (may be fractional). */
    private double tokens;

    /**
     * Creates a new token bucket.
     *
     * @param capacity      maximum tokens the bucket can hold; must be > 0
     * @param refillRatePerSec refill rate in tokens per second; must be > 0
     * @throws IllegalArgumentException if either argument is not positive
     */
    public TokenBucket(long capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerNanos = refillRatePerSec / 1_000_000_000.0;
        // start with a full bucket – allows an initial burst up to capacity
        this.tokens = (double) capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double added = elapsed * refillRatePerNanos;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    /**
     * Blocks until a token is available, then consumes it.
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
                // Not enough tokens – compute how long we must wait for one token.
                double needed = (1.0 - tokens) / refillRatePerNanos;
                long nanosToWait = Math.max(0L, (long) needed);
                if (nanosToWait > 0) {
                    notEmpty.awaitNanos(nanosToWait);
                }
                // Loop again; either we now have a token or we were interrupted.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to consume a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise
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
     * Attempts to consume a token, waiting at most the given timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of {@code timeout}
     * @return {@code true} if a token was taken, {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                long now = System.nanoTime();
                if (now >= deadline) {
                    return false;
                }
                double needed = (1.0 - tokens) / refillRatePerNanos;
                long nanosToWait = Math.max(0L, (long) needed);
                long remaining = deadline - now;
                if (nanosToWait > remaining) {
                    nanosToWait = remaining;
                }
                if (nanosToWait > 0) {
                    notEmpty.awaitNanos(nanosToWait);
                }
                // Loop again – either we have a token now or we timed out.
            }
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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucket}.
 */
class TokenBucketTest {

    /** Helper: wait until the bucket has had enough time to refill {@code tokens} tokens. */
    private void waitForRefill(TokenBucket bucket, double tokens, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            if (bucket.tryAcquire()) {
                if (--tokens <= 0) {
                    return;
                }
            } else {
                Thread.sleep(1); // avoid busy‑spin
            }
        }
        fail("Timeout while waiting for refill");
    }

    @Test
    void testTryAcquireBasics() {
        TokenBucket bucket = new TokenBucket(3, 2.0); // capacity 3, 2 tokens/sec

        // bucket starts full
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire()); // empty now

        // wait >0.5 sec -> at least 1 token added (2 tokens/sec * 0.5 = 1)
        Thread.sleep(600);
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire()); // still empty (only 1 token added)
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 1.0); // 1 token/sec, capacity 1

        assertTrue(bucket.tryAcquire()); // take the only token
        // next acquire should block for ~1 sec
        long start = System.nanoTime();
        bucket.acquire();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 900 && elapsedMs <= 1100,
                "Blocking acquire did not wait approx. 1 sec (got " + elapsedMs + " ms)");
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 2.0); // 2 tokens/sec

        assertTrue(bucket.tryAcquire()); // consume the token
        // try to acquire with a short timeout – should fail quickly
        assertFalse(bucket.tryAcquire(200, TimeUnit.MILLISECONDS));
        // wait long enough for a token to appear
        assertTrue(bucket.tryAcquire(600, TimeUnit.MILLISECONDS));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentRateLimit() throws InterruptedException {
        double ratePerSec = 5.0;   // expected long‑run rate
        long capacity = 20;        // allow a modest burst
        TokenBucket bucket = new TokenBucket(capacity, ratePerSec);

        int threadCount = 25;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        AtomicLong acquired = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        long testDurationMillis = 4000; // run for 4 seconds

        for (int i = 0; i < threadCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    long stop = System.currentTimeMillis() + testDurationMillis;
                    while (System.currentTimeMillis() < stop) {
                        if (bucket.tryAcquire()) {
                            acquired.incrementAndGet();
                        }
                        // Thread.yield() reduces CPU load while still testing the limiter
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // let all threads go
        doneLatch.await();      // wait for everybody to finish
        exec.shutdownNow();

        long totalAcquired = acquired.get();
        double expectedMax = capacity + ratePerSec * (testDurationMillis / 1000.0);
        // Allow a 15% tolerance for scheduling jitter and the fact that we used tryAcquire in a tight loop.
        double tolerance = expectedMax * 0.15;
        assertTrue(totalAcquired <= expectedMax + tolerance,
                String.format("Acquired %d tokens, expected max %.2f (+15%% tolerance %.2f)",
                        totalAcquired, expectedMax, expectedMax + tolerance));
    }
}
```

**Explanation of the tests**

* `testTryAcquireBasics` checks the simple token‑counting behaviour and that the bucket refills over time.
* `testBlockingAcquire` verifies that `acquire()` blocks until a token becomes available and wakes up after roughly the expected time.
* `testTryAcquireWithTimeout` ensures the timed‑try variant respects both success and timeout cases.
* `testConcurrentRateLimit` launches many threads that continuously call `tryAcquire()` for a fixed interval.  
  The total number of successful acquisitions must not exceed the theoretical maximum  
  `capacity + rate × duration` (plus a modest tolerance to account for OS scheduling and the tight loop).  
  This validates that the limiter respects the configured long‑run rate even under heavy concurrent contention.

All tests are JUnit 5 compatible and can be run with any standard test runner (e.g., Maven Surefire, Gradle, or IDE).