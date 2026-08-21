<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260821T111453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token‑Bucket Rate Limiter – Java Implementation**

Below is a compact, thread‑safe token‑bucket implementation that supports:

* **Configurable refill rate** – tokens are added continuously at `rate` tokens per second.
* **Burst capacity** – the bucket can hold at most `capacity` tokens (the initial fill).
* **Blocking `acquire()`** – waits (with optional timeout) until a token is available.
* **Non‑blocking `tryAcquire()`** – attempts to take a token immediately and returns `false` if none are available.

The implementation uses a single `ReentrantLock` + `Condition` to coordinate waiting threads.  
All state updates (`tokens`, `lastRefillNano`) happen while holding the lock, guaranteeing visibility and atomicity.

---

### 1. Design Overview

| Concern | Solution |
|---------|----------|
| **Thread safety** | All public methods acquire the same `ReentrantLock`. The condition `notEmpty` is signaled whenever the token count may have increased (after a refill or after a thread releases a token – which never happens in a pure limiter, but we keep the signal for symmetry). |
| **Refill logic** | On every entry to `acquire` / `tryAcquire` we compute the elapsed time since the last refill, add `elapsed * rate` tokens (capped at `capacity`), and store the current time as `lastRefillNano`. This yields a *continuous* refill model without a background thread. |
| **Blocking acquire** | If after refilling `tokens < 1`, we compute the exact time needed to wait for the next token (`deficit / rate`) and call `Condition.awaitNanos`. When the wait ends we retry the loop (spurious wake‑ups are handled). |
| **Non‑blocking tryAcquire** | Same refill step, then if `tokens >= 1` we consume one and return `true`; otherwise we return `false` without waiting. |
| **Precision** | Tokens are stored as `double` to allow fractional rates (e.g., 0.5 tokens/s). The bucket never stores more than `capacity`. |
| **Interruptibility** | The blocking `acquire()` respects thread interruption – if the waiting thread is interrupted, `InterruptedException` is propagated. An overload with a timeout is also provided. |

---

### 2. Source Code

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a configured rate (tokens per second)
 * up to a maximum burst capacity. {@code acquire()} blocks until a token is
 * available; {@code tryAcquire()} attempts to take a token without waiting.
 *
 * <p>Typical usage:
 * <pre>{@code
 * TokenBucket limiter = new TokenBucket(10.0, 2.0); // 2 permits/s, burst 10
 * limiter.acquire(); // wait if needed
 * if (limiter.tryAcquire()) { ... }
 * }</pre>
 */
public final class TokenBucket {

    /** Maximum number of tokens the bucket can hold (also the initial fill). */
    private final double capacity;

    /** Refill rate in tokens per second. */
    private final double ratePerSecond;

    /** Current number of tokens available (may be fractional). */
    private double tokens;

    /** Nano‑time of the last refill operation. */
    private volatile long lastRefillNano;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when the token count may have increased. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a token bucket that starts full.
     *
     * @param capacity   maximum burst size (tokens). Must be > 0.
     * @param ratePerSecond refill rate (tokens per second). Must be > 0.
     */
    public TokenBucket(double capacity, double ratePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        this.capacity = capacity;
        this.ratePerSecond = ratePerSecond;
        this.tokens = capacity;          // start with a full bucket
        this.lastRefillNano = System.nanoTime();
    }

    /**
     * Internal method to add tokens based on elapsed time.
     * Caller must hold {@code lock}.
     */
    private void refill(long nowNanos) {
        if (tokens >= capacity) {
            // already full – nothing to do, just update timestamp to avoid
            // accumulating a huge negative drift if called repeatedly.
            lastRefillNano = nowNanos;
            return;
        }
        double elapsedSec = (nowNanos - lastRefillNano) / 1_000_000_000.0;
        double added = elapsedSec * ratePerSecond;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNano = nowNanos;
        }
    }

    /**
     * Acquires a single token, blocking until one is available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        acquire(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires a single token, blocking until one is available or the timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if a token was acquired, {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean acquire(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                refill(System.nanoTime());
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true; // acquired
                }
                // Not enough tokens – compute how long to wait for the next token.
                double deficit = 1.0 - tokens; // >0
                long waitNanos = (long) (deficit / ratePerSecond * 1_000_000_000.0);
                if (waitNanos <= 0) {
                    // Should not happen, but guard against rounding errors.
                    waitNanos = 1L;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false; // timeout
                }
                long toWait = Math.min(waitNanos, remaining);
                try {
                    if (!notEmpty.awaitNanos(toWait)) {
                        // awaitNanos returns false only when the timeout elapsed.
                        return false;
                    }
                } catch (InterruptedException ie) {
                    // Preserve interrupt status and propagate.
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                // Loop again – either we got a token or we need to recompute wait.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a single token without blocking.
     *
     * @return {@code true} if a token was acquired, {@code false} otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill(System.nanoTime());
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** For testing / diagnostics – current approximate token count. */
    public double getTokens() {
        lock.lock();
        try {
            refill(System.nanoTime());
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

**Key Points**

* The bucket starts **full** (`tokens = capacity`). This matches the classic burst‑capacity semantics.
* All state changes happen under the lock; `Condition.awaitNanos` provides efficient blocking with nanosecond precision.
* The `refill` method is called at the start of every public operation, guaranteeing that the token count is always up‑to‑date with respect to real time.
* The implementation is **fair** in the sense that threads are woken in FIFO order by the underlying `ReentrantLock` (default fairness is false, but the lock still guarantees progress; if strict FIFO is required, construct the lock with `true`).

---

### 3. JUnit 5 Tests

The test suite validates:

* Basic behavior of `tryAcquire`.
* Blocking `acquire` respects the rate over a short interval.
* A **concurrent stress test** where many threads repeatedly call `acquire` for a fixed duration; the measured long‑run rate must not exceed the configured limit (allowing a small tolerance for timing jitter).

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class TokenBucketTest {

    private static final double TOLERANCE = 0.15; // 15% tolerance for timing jitter

    @Test
    void tryAcquire_immediateSuccessWhenFull() {
        TokenBucket bucket = new TokenBucket(5.0, 1.0); // 5 burst, 1 token/s
        assertTrue(bucket.tryAcquire(), "First acquire should succeed");
        assertTrue(bucket.tryAcquire(), "Second acquire should succeed");
        assertEquals(3.0, bucket.getTokens(), 1e-9, "Three tokens should remain");
    }

    @Test
    void tryAcquire_failsWhenEmpty() {
        TokenBucket bucket = new TokenBucket(1.0, 10.0); // capacity 1, high rate
        assertTrue(bucket.tryAcquire(), "First acquire should succeed");
        assertFalse(bucket.tryAcquire(), "Second acquire should fail (bucket empty)");
        // Wait a bit for refill
        Thread.sleep(150);
        assertTrue(bucket.tryAcquire(), "After delay a token should be available");
    }

    @Test
    void acquire_blocking_respectsRate() throws InterruptedException {
        double rate = 5.0; // 5 tokens per second
        double capacity = 10.0;
        TokenBucket bucket = new TokenBucket(capacity, rate);

        long start = System.nanoTime();
        // Acquire 7 tokens – first 5 come instantly (burst), remaining 2 must wait.
        for (int i = 0; i < 7; i++) {
            bucket.acquire();
        }
        long elapsedNanos = System.nanoTime() - start;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;

        // Expected time: burst covers 5 tokens instantly,
        // then need (7-5)/rate = 2/5 = 0.4 s.
        double expectedSec = 0.0 + (7 - 5) / rate;
        assertTrue(elapsedSec >= expectedSec * 0.9,
                "Elapsed time should be at least the theoretical minimum");
        assertTrue(elapsedSec <= expectedSec * 1.5,
                "Elapsed time should not be wildly larger than expected");
    }

    @Test
    void concurrentAcquire_doesNotExceedConfiguredRate() throws Exception {
        int parallelism = 20;
        double ratePerSec = 50.0; // target: 50 tokens/sec
        double capacity = 100.0; // large burst to avoid early starvation
        TokenBucket bucket = new TokenBucket(capacity, ratePerSec);

        int testDurationSec = 4; // run for a few seconds to smooth out jitter
        ExecutorService exec = Executors.newFixedThreadPool(parallelism);
        AtomicLong permitsAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(parallelism);

        for (int i = 0; i < parallelism; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long localCount = 0;
                    long endTime = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(testDurationSec);
                    while (System.nanoTime() < endTime) {
                        // blocking acquire – will wait if needed
                        bucket.acquire();
                        localCount++;
                    }
                    permitsAcquired.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers simultaneously
        startLatch.countDown();

        // Wait for all threads to finish or timeout
        boolean finished = doneLatch.await(testDurationSec + 2, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertTrue(finished, "All threads should have finished within the timeout");

        long total = permitsAcquired.get();
        double actualRate = total / (double) testDurationSec;

        // The rate must not exceed the configured limit (allow tolerance for scheduling jitter)
        assertTrue(actualRate <= ratePerSec * (1.0 + TOLERANCE),
                String.format("Measured rate %.2f tokens/s exceeds configured %.2f tokens/s (+%.0f%%)",
                        actualRate, ratePerSec, TOLERANCE * 100));

        // Additionally, we expect the rate to be close to the limit (not drastically lower)
        // because the bucket starts with a large burst and the threads are hungry.
        assertTrue(actualRate >= ratePerSec * 0.5,
                String.format("Measured rate %.2f tokens/s is too low (< 50%% of configured)",
                        actualRate));
    }
}
```

**Explanation of the Concurrent Test**

* **Setup** – 20 hammer threads share a single `TokenBucket` configured for 50 tokens/s with a large burst (100).  
* **Procedure** – All threads start at the same time (`startLatch`) and repeatedly call `bucket.acquire()` for a fixed wall‑clock duration (`testDurationSec`). Each successful acquire increments a shared `AtomicLong`.  
* **Assertions** –  
  * The *observed* rate (`total permits / duration`) must be **≤** `ratePerSec × (1 + tolerance)`. The tolerance (15 %) accounts for OS scheduling, JVM safepoints, and the fact that the bucket may be temporarily empty causing threads to block.  
  * A secondary lower‑bound check ensures the bucket is not grossly under‑utilizing the capacity (the test would still pass if the limiter were overly conservative, but we want to confirm it’s actually doing work).  

If the implementation mistakenly allowed more tokens than the rate, the upper‑bound check would fail. If it mistakenly blocked too aggressively, the lower‑bound check would fire.

---

### 4. Usage Notes

* The limiter is **permit‑based** (`acquire()` consumes exactly one token). For multi‑permit operations you can wrap a loop or add an overload that accepts a `permits` argument (the same refill logic applies, just check `tokens >= permits`).
* Because the bucket uses `double` for token counts, it works well with fractional rates (e.g., 0.3 tokens/s). If you need strictly integer permits, cast/refine accordingly.
* No background thread is required; the refill is piggy‑backed on each call, making the implementation lightweight and suitable for high‑concurrency scenarios (the lock contention is the only scalability bottleneck – acceptable for most rate‑limiting workloads).

--- 

**End of solution.**