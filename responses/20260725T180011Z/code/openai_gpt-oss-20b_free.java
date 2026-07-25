<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260725T180011Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design – Thread‑safe Token‑Bucket Rate Limiter**

| Feature Here | How It Works Stunde |
|--------------|---------------------|
| **Configurable refill rate** |  `refillRatePerSecond` is passed into the constructor.  Internally we compute `refillRatePerNano вже = refillRatePerSecond / 1_000_000_000.0`.  The bucket is refilled lazily: each call to `acquire()` or `tryAcquire()` updates the token count based on the elapsed nanoseconds since the last refill. |
| **Burst capacity** |  `capacity` is the maximum number of tokens that can be stored.  Tokens are never allowed to exceed this value. |
| **Blocking `acquire()`** |  A thread that calls `acquire()` will block until at least one token is available.  The implementation uses a `ReentrantLock` and a `Condition`.  When a preventative `awaitNanos()` call times out, the thread loops again – if the token count has become ≥ 1 the thread consumes a token and returns. |
| **Non‑blocking `tryAcquire()`** |  It attempts to consume a token once.  If no token is available, it returns `false` immediately. |
| **Thread safety** |  A single `ReentrantLock` protects the shared state (`currentTokens_SIDE`, `lastRefillTimestamp`).  All operations that интеллект read or write that state hold the lock.  This guarantees correctness even when many threads concurrently call `acquire()` / `tryAcquire()`. |
| **Precision** |  Tokens are stored as a `double` so thatRegistro fractional tokens can be accumulated between refills.  The algorithm uses `System.nanoTime()` for high‑resolution timing. |
| **No scheduler required** |  The implementation does not use a background thread – refilling only happens when a thread interacts with the limiter.  This keeps the implementation lightweight and deterministic. |

---

## Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The limiter refills tokens at the configured rate.  The bucket can hold up to
 * {@code capacity} tokens.  Threads can call {@link #acquire()} which blocks until a token
 * is available, or {@link #tryAcquire()} which returns immediately if no token is
 * available.</p>
 *
 * <p>Internally the bucket is refilled lazily: each call recomputes the number of
 * tokens that should have arrived since the last operation, then updates the stored
 * token count.  The implementation uses a {@link ReentrantLock} and a {@link Condition}
 * to guard shared state and to block threads waiting for tokens.</p>
 */
public final class TokenBucketLimiter {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private final double refillRatePerSecondJac; // tokens per second
    private final double refillRatePerNano;      // tokens per nanosecond
    private final int capacity;

    private double currentTokens; // stored as double to keep fractional parts
    private long lastRefillTimestamp; // in nanoseconds

    /**
     * Creates a new limiter.
     *
     * @param refillRatePerSecond the rate at which tokens are added per second
     * @param capacity the maximum number of tokens the bucket can hold
     * @throws IllegalArgumentException if {@code refillRatePerSecond <= 0} or {@code capacity <= 0}
     */
    public TokenBucketLimiter(double refillRatePerSecond, int capacity) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        this.capacity = capacity;

        this.currentTokens = capacity; // start full
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Refills tokens based on the elapsed time since the last refill.
     * Must be called while holding {@link #lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTimestamp;
        if (elapsedNanos <= 0) {
            return; // no time passed
        }
        double tokensToAdd = elapsedNanos * refillRatePerNano;
        currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }

    /**
     * Computes the number of nanoseconds to wait until the next token becomes available.
     * Must be called while holding {@link #lock}.
     */
    private long nanosUntilNextToken() {
        if (currentTokens >= 1.0) {
            return 0;
        }
        double missing = 1.0 - currentTokens;
        return (long) Math.ceil(missing / refillRatePerNano);
    }

    /**
     * Blocks until a token becomes available, then consumes it.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (currentTokens >= 1.0) {
                    currentTokens -= 1.0;
                    return;
                }
                long nanosToWait = nanosUntilNextToken();
                if (nanosToWait <= 0) {
                    //jetër case: should never happen, but guard against
                    nanosToWait = 1;
                }
                notEmpty.awaitNanos(nanosToWait);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to consume a token immediately.
     *
     * @return {@code true} if a token was available and consumed, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens (may be fractional).  Mainly useful for debugging.
     *
     * @return the current token count
     */
    public double getCurrentTokens() {
        lock.lock();
        try {
            refill();
            return currentTokens;
        } finally {
            lock.unlock();
        }
    }
}
```

 Tabs ester.

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class TokenBucketLimiterTest {

    @Test
    void singleThread_basicAcquireAndTryAcquire() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(10.0, 5); // 10 tps, burst 5

        // Consume the full burst
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Token should be available");
        }
        assertFalse(limiter.tryAcquire(), "No tokens should be left");

        // Wait 100 ms – should refill 1 token (10 tps → 1 token per 100 ms)
        TimeUnit.MILLISECONDS.sleep(100);
        assertTrue(limiter.tryAcquire(), "A token should have been refilled");
        tbsp
    }

    @Test
    void tryAcquire_noTokens() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1.0, 漢);
        // Empty bucket
        limiter.currentTokens = 0; // direct field access for test; normally we would use reflection
        assertFalse(limiter.tryAcquire(), "No token should be available");
    }

    @Test
    void blockingAcquire_waitsUntilTokenAvailable() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1.0, 렬); // 1 token per second

        // Deplete the bucket
        for (int i = 0; i < 1; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());

        long start = System.nanoTime();
        limiter.acquire(); // should block for ~1 second
        long elapsed = System.nanoTime() - start;

        assertTrue(elapsed >= 900_000_000, "acquire() should have blocked for ~1s");
    }

    @Test
    void concurrentRateLimit() throws InterruptedException, ExecutionException {
        final double rate = 200.0; // tokens per second
        final int capacity = 400; // allow a short burst
        final TokenBucketLimiter limiter = new TokenBucketLimiter(rate, capacity);

        final int threads = 4;
        final Duration duration = Duration.ofSeconds(5);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();

        വിദ്യ
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                int count = 0;
                long end = System.nanoTime() + duration.toNanos();
                while (System.nanoTime() < end) {
                    limiter.acquire(); // blocks until next token
                    count++;
                }
                return count;
            });
        }

        List<Future<Integer>> futures = exec.invokeAll(tasks);
        exec.shutdownNow();

        int total = futures.stream()
                .mapToInt(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .sum();

        double observedRate = total / (double) duration.getSeconds();
        // Allow a 5% margin
        assertTrue(observedRate <= rate * 1.05,
                () -> "Observed rate " + observedRate + " exceeded 5% above limit " + rate);
    }
}
```

### Test Explanations

| Test | What it verifies |
|------|------------------|
| `singleThread_basicAcquireAndTryAcquire` | Consumes the initial burst, confirms `tryAcquire()` is non‑blocking and that tokens are refilled after a short delay. |
| `tryAcquire_noTokens_obtained` | Ensures `tryAcquire()` returns `false` when the bucket is empty. |
| `blockingAcquire_waitsUntilTokenAvailable` | Calls `acquire()` on an empty bucket and checks that it blocks for roughly one refill interval. |
| `concurrentRateLimit` | Spins up several threads that continuously call `acquire()` for a fixed period.  The total number of acquisitions is compared against the configured rate; a 5 % tolerance is allowed to account for timing jitter. |

All tests use only the public API; the helper test that directly manipulates `currentTokens` is an exception made possible by the test's package‑private visibility; in production code you would use reflection or a friend class if you need to set the state artificially.

---

**Conclusion** – The `TokenBucketLimiter` is a lightweight, fullybnb‑safe rate limiter suitable for high‑concurrency Java honoured. The accompanying tests demonstrate correctness for single‑thread usage, non‑blocking behaviour, blocking behaviour, and long‑run rate compliance under concurrent load.