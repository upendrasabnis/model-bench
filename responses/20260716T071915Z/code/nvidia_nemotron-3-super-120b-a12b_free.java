<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

A token bucket limits the average request rate while allowing short bursts up to a configured capacity.

* **State**
  * `capacity` – maximum number of tokens the bucket can hold (burst size).
  * `tokens`   – current number of available tokens (0 ≤ tokens ≤ capacity).
  * `lastRefill` – timestamp (nanoseconds) of the last refill operation.
  * `refillRatePerSec` – how many tokens are added per second (the long‑term rate).

* **Refill algorithm** (lazy, lock‑protected)  
  When any method needs to know the current token count we:
  1. Compute elapsed time since `lastRefill`.
  2. Add `elapsed * refillRatePerSec` tokens, capped at `capacity`.
  3. Update `lastRefill` to `now`.

* **Thread‑safety**  
  All mutable state is guarded by a `java.util.concurrent.locks.ReentrantLock`.  
  A `Condition` (`notEmpty`) is used for the blocking `acquire()`: a thread waits until the condition is signalled after a refill makes at least one token available.

* **Operations**
  * `tryAcquire()` – non‑blocking. Refills, checks if a token is available, consumes it and returns `true`; otherwise returns `false` immediately.
  * `acquire()` – blocking. Same as `tryAcquire()` but if no token is available it waits on `notEmpty` for the exact time needed to refill the missing token(s). After the wait it refills again (to cope with spurious wakeups) and consumes a token.
  * `acquire(long timeout, TimeUnit unit)` – optional overload that bounds the wait.

* **Why this works**
  * The lazy refill guarantees that the token count is always consistent with the elapsed real time, without a background thread.
  * The lock + condition provides a classic monitor‑style solution that is safe for any number of producer/consumer threads.
  * The algorithm never lets the token count exceed `capacity`, thus enforcing the burst limit.
  * The waiting time is computed from the *deficit* of tokens, so a thread wakes up exactly when enough tokens would have been added (subject to the lock’s scheduling latency).

---

## TokenBucket.java

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token bucket rate limiter.
 *
 * <p>The bucket refills at a constant rate (tokens per second) up to a maximum
 * {@code capacity}.  {@code tryAcquire()} attempts to consume a token without
 * blocking; {@code acquire()} blocks until a token is available (or the
 * optional timeout expires).</p>
 *
 * <p>All operations are O(1) and require only a single lock.</p>
 */
public class TokenBucket {

    private final long capacity;                     // maximum tokens (burst)
    private final double refillRatePerSec;           // tokens added per second
    private volatile long lastRefillNanos;           // last time we refilled
    private double tokens;                           // current token count (may be fractional)

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a token bucket.
     *
     * @param capacity          maximum number of tokens the bucket can hold (burst size)
     * @param refillRatePerSec  long‑term refill rate (tokens per second). Must be > 0.
     */
    public TokenBucket(long capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        // start with a full bucket – allows an initial burst up to capacity
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return; // no time passed
        }
        double tokensToAdd = ((double) elapsedNanos / 1_000_000_000.0) * refillRatePerSec;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was consumed, {@code false} otherwise.
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
     * Blocks until a token is available, then consumes it and returns.
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
                // compute how long we must wait for at least one token
                double deficit = 1.0 - tokens; // >0
                double waitSec = deficit / refillRatePerSec;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                // wait with a timeout; we will re‑check the condition after wakeup
                if (!notEmpty.await(waitNanos, TimeUnit.NANOSECONDS)) {
                    // timeout spurious wakeup – loop will refill again
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token, waiting at most the given timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if a token was acquired, {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                double deficit = 1.0 - tokens;
                double waitSec = deficit / refillRatePerSec;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false; // timeout
                }
                long toWait = Math.min(waitNanos, remaining);
                if (!notEmpty.await(toWait, TimeUnit.NANOSECONDS)) {
                    // timeout or spurious wakeup – loop will recheck
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** For testing only – returns the current approximate token count. */
    double getTokens() {
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

## TokenBucketTest.java (JUnit 5)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
 java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The tests cover:</p>
 * <ul>
 *   <li>basic {@code tryAcquire}/{@code acquire} behaviour</li>
 *   <li>burst capacity enforcement</li>
 *   <li>long‑term rate compliance under concurrent load</li>
 * </ul>
 */
class TokenBucketTest {

    private static final double EPS = 1e-9; // tolerance for floating point comparisons

    @Test
    void testTryAcquireAndAcquireBasics() {
        TokenBucket tb = new TokenBucket(5, 10); // 5 burst, 10 tokens/sec

        // Initially full -> 5 immediate tries succeed
        for (int i = 0; i < 5; i++) {
            assertTrue(tb.tryAcquire(), "tryAcquire should succeed while tokens available");
        }
        // Sixth try should fail (bucket empty)
        assertFalse(tb.tryAcquire(), "tryAcquire should fail when no tokens");

        // Blocking acquire should succeed after waiting for a refill
        // With rate 10 tok/s, one token appears every 100 ms.
        long start = System.nanoTime();
        assertDoesNotThrow(tb::acquire);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 80 && elapsedMs <= 150,
                "acquire should wait roughly one token interval (~100ms)");
        // After the blocking acquire the bucket is empty again
        assertFalse(tb.tryAcquire());
    }

    @Test
    void testBurstCapacity() {
        long capacity = 3;
        double rate = 1.0; // 1 token per second -> very slow refill
        TokenBucket tb = new TokenBucket(capacity, rate);

        // We can consume up to 'capacity' tokens instantly
        for (long i = 0; i < capacity; i++) {
            assertTrue(tb.tryAcquire(),
                    "Should be able to acquire burst tokens up to capacity");
        }
        // Next immediate try must fail
        assertFalse(tb.tryAcquire(),
                "After consuming burst, tryAcquire must fail until refill");

        // Wait a bit and see that tokens slowly come back
        try {
            Thread.sleep(1500); // 1.5 sec -> ~1.5 tokens refilled
        } catch (InterruptedException ignored) {
        }
        // At most one whole token should be available now
        assertTrue(tb.tryAcquire(), "After waiting, at least one token should be available");
        assertFalse(tb.tryAcquire(), "Second token not yet fully refilled");
    }

    @Test
    @Timeout(value = 5L) // safeguard against deadlocks
    void testLongRunRateDoesNotExceedConfiguredLimit() throws Exception {
        long capacity = 10;
        double ratePerSec = 20.0; // 20 tokens per second
        TokenBucket tb = new TokenBucket(capacity, ratePerSec);

        int workerCount = 8;
        Duration testDuration = Duration.ofSeconds(3);
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        AtomicLong totalAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all workers to start together
                    long local = 0;
                    long endTime = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < endTime) {
                        if (tb.tryAcquire()) {
                            local++;
                        } else {
                            // tiny pause to avoid busy‑spinning; still allows high throughput
                            Thread.yield();
                        }
                    }
                    totalAcquired.addAndGet(local);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers simultaneously
        startLatch.countDown();
        // Wait for all to finish
        assertTrue(doneLatch.await(testDuration.toMillis() + 2000, TimeUnit.MILLISECONDS),
                "Workers did not finish in time");

        exec.shutdownNow();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));

        long acquired = totalAcquired.get();
        double expectedMax = ratePerSec * testDuration.getSeconds() + capacity; // burst + sustained
        // Allow a small slack (5%) for scheduling jitter and timer granularity
        double slack = 0.05 * expectedMax;
        long maxAllowed = (long) Math.ceil(expectedMax + slack);

        assertTrue(acquired <= maxAllowed,
                String.format("Acquired %d tokens, expected ≤ %d (rate=%.2f/s, duration=%ds, burst=%d)",
                        acquired, maxAllowed, ratePerSec,
                        testDuration.getSeconds(), capacity));
        // Also ensure we made reasonable progress (not zero)
        assertTrue(acquired > ratePerSec * testDuration.getSeconds() * 0.5,
                "Acquired too few tokens; likely a bug in the limiter");
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `testTryAcquireAndAcquireBasics` | Correct behaviour of `tryAcquire` (non‑blocking) and `acquire` (blocking) when the bucket starts full, then empties, and finally refills. |
| `testBurstCapacity` | The bucket never allows more than `capacity` immediate consumptions; after the burst is exhausted, further `tryAcquire` calls fail until the refill mechanism adds tokens. |
| `testLongRunRateDoesNotExceedConfiguredLimit` | A concurrent stress test: many workers repeatedly call `tryAcquire` for a fixed interval. The total number of successful acquisitions is bounded by `rate × time + capacity` (burst allowance). A small tolerance (5 %) accommodates OS scheduling and timing granularity. The test uses a `CountDownLatch` to start all workers simultaneously, preventing ramp‑up bias, and ends after a known duration. |

All tests are JUnit 5 compatible and can be run with `mvn test` or any IDE that supports JUnit 5. The implementation is lock‑based, simple to understand, and safe for an arbitrary number of threads.