<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260719T052627Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Java Implementation & Tests**  
*(Thread‑safe, configurable refill rate & burst capacity, blocking `acquire` and non‑blocking `tryAcquire`)*  

---

## 1. Design Overview  

| Concern | Decision | Rationale |
|---------|----------|-----------|
| **Algorithm** | Classic token bucket: a bucket holds up to `capacity` tokens. Tokens are added continuously at a fixed `rate` (tokens / second). An `acquire(n)` consumes `n` tokens; if insufficient tokens are present the caller waits until enough have been refilled. | Simple, well‑understood, provides both rate limiting and burst tolerance. |
| **Thread safety** | `java.util.concurrent.locks.ReentrantLock` + a single `Condition`. All state (`tokens`, `lastNanoTime`) is read/updated only while holding the lock. | Guarantees mutual exclusion, allows efficient waiting (`awaitNanos`) and avoids busy‑spinning. |
| **Time source** | `System.nanoTime()` (monotonic, high‑resolution). | Immune to system‑clock adjustments; sufficient precision for sub‑millisecond rates. |
| **Token representation** | `double` (fractional tokens). | Allows arbitrary rates (e.g., 0.3 tokens/s) without rounding errors; the bucket capacity is also a `double` but the public API works with integer permits for simplicity. |
| **Overflow handling** | When refilling, new tokens = `elapsedSec * rate`. `tokens = Math.min(capacity, tokens + newTokens)`. | Guarantees the bucket never exceeds its configured burst capacity. |
| **Spurious wake‑ups** | The `acquire` loop re‑checks the token condition after each `awaitNanos`. | Standard practice with `Condition`. |
| **Interruptibility** | `acquire` respects thread interruption – if the waiting thread is interrupted, `InterruptedException` is propagated. | Makes the limiter usable in cancelable scenarios. |
| **Blocking vs non‑blocking** | `acquire(int permits)` blocks until permits are available. `tryAcquire(int permits)` attempts once and returns `false` if not enough tokens. An overload with a timeout (`tryAcquire(int permits, long timeout, TimeUnit unit)`) offers bounded waiting. | Covers the three typical usage patterns. |
| **Fairness** | No explicit fairness guarantee – the underlying `ReentrantLock` is created in non‑fair mode (default). If strict FIFO ordering is required, construct the lock with `true`. | Keeps the implementation lightweight; most rate‑limiting use‑cases do not need strict fairness. |
| **Performance** | Minimal lock contention: the lock is held only for the short refill‑check‑consume critical section. Waiting threads park on the `Condition`, consuming no CPU. | Scales well to many contending threads. |

### State Diagram (conceptual)

```
[Lock] --> refill() --> (tokens >= needed?) --> consume() --> [Unlock]
                                   |
                                   |<--- insufficient tokens ---|
                                   v
                         waitForRefill() --> (awaitNanos) --> loop
```

---

## 2. Implementation  

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a fixed rate (tokens per second)
 * up to a maximum burst capacity. {@code acquire} blocks until the requested
 * number of tokens is available; {@code tryAcquire} attempts once and returns
 * {@code true} iff the tokens are immediately available. A timed variant of
 * {@code tryAcquire} is also provided.</p>
 *
 * <p>All operations are {@code O(1)} and scale well with many concurrent
 * threads.</p>
 */
public final class TokenBucket {

    /** Number of tokens added per second. */
    private final double rate;

    /** Maximum number of tokens the bucket can hold (burst capacity). */
    private final double capacity;

    /** Current token count (may be fractional). */
    private double tokens;

    /** Nano‑time of the last refill operation. */
    private volatile long lastNanoTime;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when insufficient tokens are available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket.
     *
     * @param rate      refill rate in tokens per second (must be > 0)
     * @param capacity  maximum burst capacity (must be > 0)
     * @throws IllegalArgumentException if rate or capacity is non‑positive
     */
    public TokenBucket(double rate, double capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.rate = rate;
        this.capacity = capacity;
        this.tokens = capacity; // start full (allows an initial burst)
        this.lastNanoTime = System.nanoTime();
    }

    /** Refills the bucket based on the elapsed time since the last refill. */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastNanoTime;
        if (elapsedNanos <= 0) {
            return; // no time passed (or clock went backwards – ignore)
        }
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double added = elapsedSec * rate;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastNanoTime = now;
        }
    }

    /**
     * Acquires the given number of tokens, blocking until they are available.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws IllegalArgumentException if permits <= 0
     * @throws InterruptedException     if the current thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                // Not enough tokens – compute how long to wait for the deficit.
                double deficit = permits - tokens; // tokens we still need
                double waitSec = deficit / rate;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                // Guard against overflow / excessively long waits
                if (waitNanos <= 0) {
                    waitNanos = 1L; // minimal wait to avoid busy loop
                }
                notEmpty.awaitNanos(waitNanos);
                // Loop again – a spurious wakeup or a change in tokens will be re‑checked.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire the given number of tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        lock.lock();
        try {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire the given number of tokens, waiting up to the
     * specified timeout.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if the permits were acquired, {@code false} if the
     *         timeout elapsed before they became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        long timeoutNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return true;
                }
                if (timeoutNanos <= 0) {
                    return false; // timed out
                }
                double deficit = permits - tokens;
                double waitSec = deficit / rate;
                long waitNanos = Math.min((long) (waitSec * 1_000_000_000.0), timeoutNanos);
                if (waitNanos <= 0) {
                    waitNanos = 1L; // avoid zero‑wait spin
                }
                long remaining = notEmpty.awaitNanos(waitNanos);
                timeoutNanos = remaining;
                // loop again – re‑check tokens after wait (or timeout)
            }
        } finally {
            lock.unlock();
        }
    }

    /** Convenience method that acquires a single token. */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /** Convenience method that tries to acquire a single token. */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /** Convenience method that tries to acquire a single token with a timeout. */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }
}
```

### Key Points in the Code  

* **Monotonic time** – `System.nanoTime()` guarantees that elapsed time never goes backwards (except for the extremely rare case of a clock wrap‑around, which we ignore because the elapsed nanos would be negative and we simply skip refilling).  
* **Fractional tokens** – Allows rates like `0.5` tokens/s without losing precision.  
* **Waiting logic** – The wait time is computed from the current deficit: `waitSec = deficit / rate`. This is the exact time needed for the bucket to refill enough tokens, assuming a constant refill rate.  
* **Condition usage** – `awaitNanos` puts the thread to sleep; upon waking we re‑check the token count (handles spurious wakeups and the case where another thread consumed tokens just before we woke).  
* **Interruptibility** – The lock is acquired with `lockInterruptibly()` and we propagate `InterruptedException`. The timed `tryAcquire` also respects interruption.  

---

## 3. JUnit 5 Tests  

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.*;

class TokenBucketTest {

    /** Helper to run a task for a fixed wall‑clock time and return the number of executions. */
    private static long runForDuration(Runnable task, Duration duration) throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(task);
        try {
            future.get(duration.toNanos(), TimeUnit.NANOSECONDS);
            fail("Task should have timed out");
        } catch (TimeoutException expected) {
            // expected – task stopped because time ran out
        } finally {
            future.cancel(true); // ensure thread stops
            exec.shutdownNow();
        }
        // The task itself counts its executions; we retrieve the count via a shared counter.
        // This helper assumes the task increments an AtomicLong passed via closure.
        throw new AssertionError("Unreachable");
    }

    @Test
    void testInitialBurstAndTryAcquire() {
        TokenBucket tb = new TokenBucket(10.0, 10.0); // 10 permits/s, burst 10

        // Bucket starts full → we can acquire up to 10 permits immediately
        assertTrue(tb.tryAcquire(5));
        assertTrue(tb.tryAcquire(3)); // total 8 used
        assertFalse(tb.tryAcquire(5)); // only 2 left → not enough for 5
        assertTrue(tb.tryAcquire(2)); // exactly the remaining 2
        assertFalse(tb.tryAcquire(1)); // empty now
    }

    @Test
    void testBlockingAcquireRespectsRate() throws InterruptedException {
        TokenBucket tb = new TokenBucket(5.0, 5.0); // 5 permits/s, burst 5

        // Consume the burst instantly
        for (int i = 0; i < 5; i++) {
            tb.acquire(1); // should not block
        }

        // Next acquire must wait ~200ms for one token to refill (1/5 s = 0.2s)
        long start = System.nanoTime();
        tb.acquire(1);
        long elapsedNs = System.nanoTime() - start;
        assertTrue(elapsedNs >= Duration.ofMillis(180).toNanos(),
                "Should have waited at least ~180ms for refill");
        assertTrue(elapsedNs <= Duration.ofMillis(260).toNanos(),
                "Should not have waited excessively long");
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucket tb = new TokenBucket(2.0, 2.0); // 2 permits/s, burst 2

        // Drain the bucket
        tb.acquire(2);
        assertFalse(tb.tryAcquire(1)); // no tokens immediately

        // Wait up to 150ms (less than the 500ms needed for a token)
        assertFalse(tb.tryAcquire(1, 150, TimeUnit.MILLISECONDS));

        // Wait long enough for a token (500ms) – should succeed
        assertTrue(tb.tryAcquire(1, 600, TimeUnit.MILLISECONDS));
    }

    @Test
    void testConcurrentLongRunRateLimit() throws Exception {
        final double ratePerSec = 100.0;   // target long‑run rate
        final double capacity   = 20.0;    // allow a modest burst
        final TokenBucket limiter = new TokenBucket(ratePerSec, capacity);
        final int workerCount = 20;
        final Duration testDuration = Duration.ofSeconds(3);

        AtomicLong permitsGranted = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(workerCount);
        CountDownLatch doneLatch  = new CountDownLatch(workerCount);

        Runnable worker = () -> {
            try {
                startLatch.countDown(); // signal ready
                startLatch.await();     // wait for all workers to start together
                long local = 0;
                long deadline = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < deadline) {
                    // Each iteration tries to grab a single permit, blocking if necessary.
                    limiter.acquire(1);
                    local++;
                }
                permitsGranted.addAndGet(local);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve interrupt status
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            pool.submit(worker);
        }

        // Wait for all workers to finish (or be interrupted)
        assertTrue(doneLatch.await(testDuration.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS),
                "Workers did not finish in expected time");
        pool.shutdownNow();

        long total = permitsGranted.get();
        double elapsedSec = testDuration.getSeconds();

        // Expected maximum tokens = burst + rate * time
        double maxExpected = capacity + ratePerSec * elapsedSec;
        // Allow a small tolerance (≈2 %) for timing jitter and thread scheduling.
        double tolerance = 0.02 * maxExpected;
        assertTrue(total <= Math.ceil(maxExpected + tolerance),
                String.format("Total permits %d exceeded expected max %.2f (rate=%.2f/s, burst=%.1f, time=%.2fs)",
                        total, maxExpected, ratePerSec, capacity, elapsedSec));

        // Additionally, ensure we actually got close to the rate (not drastically lower)
        double minExpected = ratePerSec * elapsedSec * 0.9; // allow 10 % slowdown due to start‑up latency
        assertTrue(total >= Math.floor(minExpected),
                String.format("Total permits %d too low; expected at least %.2f",
                        total, minExpected));
    }
}
```

### Test Explanation  

| Test | What it validates |
|------|-------------------|
| `testInitialBurstAndTryAcquire` | Starts with a full bucket, checks that `tryAcquire` consumes tokens correctly and returns `false` when the bucket is empty. |
| `testBlockingAcquireRespectsRate` | After draining the burst, a blocking `acquire` must wait roughly the inverse of the refill rate for each token. |
| `testTryAcquireWithTimeout` | Confirms the timed variant returns `false` when the timeout expires before a token is available and `true` when it succeeds. |
| `testConcurrentLongRunRateLimit` | **Core concurrent correctness check**: many threads continuously acquire permits for a fixed wall‑clock window. The total number of permits granted must not exceed `burst + rate × time` (the theoretical maximum allowed by a token bucket). A lower bound ensures the limiter is not overly pessimistic (i.e., it does not throttle far below the configured rate). The test tolerates minor jitter due to thread scheduling. |

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and rely on standard JDK concurrency utilities (`ExecutorService`, `CountDownLatch`, `AtomicLong`). They can be run with any JUnit 5‑compatible runner (IDE, Maven Surefire, Gradle, etc.).  

---  

**End of solution**. Enjoy the rate limiter!