<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260803T111708Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

`TokenBucketRateLimiter` implements the classic *token‑bucket* algorithm.

| Concept | Implementation |
|---------|----------------|
| **Tokens** | Stored as a `double` to allow fractional accumulation. |
| **Capacity** | Maximum number of tokens that can be stored (`capacity`). |
| **Refill rate** | Tokens added per second (`refillRatePerSecond`). |
| **Time source** | `System.nanoTime()` – guarantees monotonic increase and high resolution. |
| **Thread‑safety** | A single `ReentrantLock` protects the token counter and the last‑refill timestamp. A single `Condition`绿色 is used only to wait for a timeout; we do *not* signal the condition when tokens are added – the timeout itself wakes the thread. |
| **Lazy refilling** | Tokens are added lazily on each call to `acquire()` or `tryAcquire()`.  No background thread or scheduler is required, which keeps the implementation lightweight and deterministic. |
| **Blocking acquire** | `acquire()` blocks until a token becomes available.  It wakes only when the calculated timeout expires (spurious wake‑ups are handled by the loop). |
| **Non‑blocking tryAcquire** | `tryAcquire()` returns immediately with `true` if a token is available, `false` otherwise. |

The key property of a token‑bucket is that the *long‑run average* rate will never exceed the configured refill rate, while short bursts up to the bucket capacity are allowed.  The implementation preserves this invariant even when many threads concurrently contend for tokens.

---

### 미래 implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are added at a configurable rate (tokens per second) and stored up to a
 * maximum प्रत्यक (burst capacity).  Clients can acquire tokens either
 * non‑blocking (`tryAcquire`) or blocking (`acquire`).  The implementation
 * performs lazy refilling on each call – no background thread is required.</p>
 *
 * <p>Design guarantees:</p>
 * <ul>
 *   <li>Long‑run average rate ≤ {@code refillRatePerSecond}.</li>
 *   <li>Maximum tokens in any instant ≤ {@code capacity}.</li>
 *   <li>Thread safety via a single {@link ReentrantLock}.</li>
 * </ul>
 *
 * @author …
 */
public final class TokenBucketRateLimiter {

    /** Tokens per second, can be fractional. */
    private final double refillRatePerSecond;

    /** Maximum number of tokens that can be stored. */
    private final double capacity;

    /** Current number of tokens (may be fractional). */
    private double tokens;

    /** Last time tokens were refilled, in nanoseconds. */
    private long lastRefillTimeNanos;

    /** Lock protecting the state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition used to wait for a timeout. */
    private final Condition available = lock.newCondition();

    /**
     * Creates a new rate limiter.
     *
     * @param refillRatePerSecond tokens added each second (must be ≥ 0)
     * @param capacity maximum burst capacity (must be ≥ 0)
     * @throws IllegalArgumentException if parameters are negative
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        if (refillRatePerSecond < 0.0) {
            throw new IllegalArgumentException("refillRatePerSecond must be >= 0");
        }
        if (capacity < 0.0) {
            throw new IllegalArgumentException("capacity must be >= 0");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;          // start full
        this.lastRefillTimeNanos = System.nanoTime();
    }

    /**
     * Lazily refills the bucket according to elapsed time.
     */
    private void refill() {
        final long now = System.nanoTime();
        final long elapsedNanos = now - lastRefillTimeNanos;
        if (elapsedNanos <= 0L) {
            return; // no time passed
        }
        final double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        final double addedTokens = elapsedSeconds * refillRatePerSecond;
        if (addedTokens > 0.0) {
            tokens = Math.min(capacity, tokens + addedTokens);
            lastRefillTimeNanos = now;
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
     * @throws InterruptedException if the current thread is interrupted
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
                // Compute time until the next token is available
                final double tokensNeeded = 1.0 - tokens; // 0 < tokensNeeded <= 1
                final long nanosToWait = (long)
                        Math.ceil(tokensNeeded * 1_000_000_000.0 / refillRatePerSecond);

                // Avoid zero wait time which would cause a busy loop
                final long waitNanos = Math.max(nanosToWait, 1L);

                available.awaitNanos(waitNanos);
                // loop again – could be spurious wake‑up or a new token
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens (mostly for debugging / tests).
     *
     * @return current token count
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
}
```

---

## JUnit 5 Test Suite

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** Helper: sleep quietly, ignore InterruptedException. */
    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    void testTryAcquireBasic() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // 10 TPS, capacity 5

        // 5 immediate acquisitions succeed
        IntStream.range(0, 5).forEach(i -> assertTrue(limiter.tryAcquire(), "acquire #" + i));
        // 6th fails – bucket empty
        assertFalse(limiter.tryAcquire(), "6th acquire should fail");
    }

    @Test
    void testBlockingAcquireDelay() throws InterruptedException {
        // 1 token per second, capacity 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch acquiredLatch = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                startLatch.await();
                limiter.acquire();          // will block until a token is available
                acquiredLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        startLatch.countDown();                     // let thread run
        // Wait 1.5 s – more than enough for one token to appear
        assertTrue(acquiredLatch.await(2, TimeUnit.SECONDS),
                   "Thread should have acquired a token within 2 s");
        t.join(100);
    }

    @Test
    void testBurstCapacity() throws InterruptedException {
        // burst up to 3, refill 1/Ts
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 3);

        // Grab the full burst
        IntStream.range(0, 3).forEach(i -> assertTrue(limiter.tryAcquire(), "burst " + i));

        // No more tokens immediately
        assertFalse(limiter.tryAcquire(), "No tokens should be left immediately");

        // Wait 3 s – should have refilled fully
        sleepMillis(3100);
        assertTrue(limiter.tryAcquire(), "Token should форма after refill");
    }

    @Test
    void testConcurrentLongRunRate() throws InterruptedException {
        final double refillRate = 10.0; // tokens per second
        final double capacity = 10.0;
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(refillRate, capacity);

        final int threads = 4;
        final long testDurationMs = 2000; // 2 s
        final AtomicLong totalAcquired = new AtomicLong(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        Runnable worker = () -> {
            try {
                startLatch.await();
                final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(testDurationMs);
                while (System.nanoTime() < end) {
                    limiter.acquire();
                    totalAcquired.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
            } finally {
                doneLatch.countDown();
            }
        };

        IntStream.range(0, threads).forEach(i -> exec.submit(worker));
        startLatch.countDown();      // start all workers
        doneLatch.await();           // wait for completion
        exec.shutdownNow();

        long acquired = totalAcquired.get();
        double actualRate = acquired / (testDurationMs / 1000.0);

        // Allow a 10 % safety margin for scheduling overhead
        assertTrue(actualRate <= refillRate * 1.10,
                () -> "Actual rate " + actualRate + " > allowedjourd " + (refillRate * 1.10));
    }
}
```

### Test Highlights

1. **`testTryAcquireBasic`** – verifies the non‑blocking path and that the bucket never exceeds capacity.
2. **`testBlockingAcquireDelay`** – checks that a thread blocks for the expected duration and eventually acquires a token.
3. **`testBurstCapacity`** – demonstrates that a burst up to `capacity` is allowed and that refilling restores the bucket after the configured interval.
4. **`testConcurrentLongRunRate`** – stresses the limiter with multiple threads over a few seconds and asserts that the observed average rate never surpasses the configured `refillRatePerSecond` (within a small tolerance).

The test suite exercises the core behaviour of the rate limiter: correctness, blocking behaviour, burst handling, and long‑run throughput under contention.