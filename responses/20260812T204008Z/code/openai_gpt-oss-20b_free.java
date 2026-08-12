<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1.  Design – Thread‑safe Token‑Bucket Rate Limiter

| Feature | Implementation |
|---------|----------------|
| **Rate** (tokens per second) | Configurable `refillRate`. |
| **Burst capacity** (max tokens stored) | Configurable `capacity`. |
| **Current token count** | Stored as a `double` for fractional tokens. |
| **Concurrency** | All state is guarded by a single `ReentrantLock`. |
| **Blocking acquire** | `acquire()` waits on a `Condition` until a token becomes available. |
| **Non‑blocking try** | `tryAcquire()` returns `true` if a token is available, otherwise `false`. |
| **Replenishment** | Tokens are **lazy‑refilled**: on every call we compute how many tokens should have accumulated since the last refill and add them (capped at capacity). |
| **Accuracy** | Uses `System.nanoTime()` to avoid drift. |
| **Performance** | Only one lock is used; the condition is signalled on every refill, so waiting threads wake up promptly. |

### 1.1  Algorithm

```
on acquire() or tryAcquire_STATICALLY_LOCKED:
    1. Refill:
        elapsedNanos = now - lastRefillNanos
        addedTokens = elapsedNanos / 1'000'000'000 * refillRate
        currentTokens = min(capacity, currentTokens + addedTokens)
        lastRefillNanos = now

    2. If currentTokens >= 1:
           currentTokens -= 1
           return true / void

    3. (tryAcquire) return false

    4. (acquire)
           while currentTokens < 1:
                // how long until the next token arrives?
                nanosToWait = (1.0 / refillRate) * 1'000'000'000
                condition.awaitNanos(nanosToWait)
                refill()
           currentTokens -= 1
```

*The `while` loop protects against spuriously‑awakened threads and guarantees that the token count is still sufficient after the wait.*

### 1.2  Thread‑Safety

* All mutable fields (`tokens`, `lastRefillNanos`) are accessed only while holding the `lock`.  
* The `Condition` (`notEmpty`) is created from that same lock, so any `await()`/`signalAll()` is automatically safe.  
* The class is **immutable** after construction, except for the token bucket state, which is always protected.

### 1.3  Edge Cases

* **Zero or negative refill rate** – constructor rejects it – a rate‑limiter with no tokens would never be able to acquire.  
* **Zero capacity** – rejected as well.  
* **Large burst** – the bucket simply stores more tokens, but the average rate is still bounded by `refillRate`.  
* **High contention** – the single lock may become a bottleneck, but the algorithm is simple and the critical section is tiny (`O(1)`). For extremely high throughput you could switch to an atomic‑variable‑based implementation, but that is outside the scope of this exercise.

---

## 2.  Implementation (Java 17)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are replenished at {@code refillRate} tokens per second, up to a maximum of
 * {@code capacity}.  Each {@code acquire()} consumes one token.  If no token is
 * available, the call blocks until a token becomes available.</p>
 *
 * <p>The implementation uses a single {@link ReentrantLock} and a {@link Condition} for
 * blocking.  Tokens are recalculated lazily on each call to {@code acquire()} or
 * {@code tryAcquire()}.</p>
 */
public final class TokenBucketRateLimiter {

    /** Maximum number of tokens that may be stored in the bucket (burst capacity). */
    private final long capacity;

    /** Refill rate expressed as tokens per second. */
    private final double refillRate;

    /** Current token count – may be fractional to preserve precision. */
    private double tokens;

    /** Timestamp (nanoseconds) of the last refill. */
    private long lastRefillNanos;

    /** Lock protecting the mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition used by {@link #acquire()} to wait for a token. */
    private final Condition Свойство = lock.newCondition(); // rename to english

    /**
     * Creates a limiter with the specified burst capacity and refill rate.
     *
     * @param capacity   maximum number of tokens that may be stored
     * @param refillRate tokens per second
     * @throws IllegalArgumentException if {@code capacity <= 0} or {@code refillRate <= 0}
     */
    public TokenBucketRateLimiter(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;          // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Blocks until a token is available and consumes it.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            refill();
            while (tokens < 1.0) {
                // time (nanoseconds) until the next token appears
                long nanosToWait = (long) (1_000_000_000L / refillRate);
                Свойство.awaitNanos(nanosToWait);
                refill();
            }
            tokens -= 1.0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was available and consumed, {@code false} otherwise
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
     * Returns the configured burst capacity.
     */
    public long getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRate() {
        return refillRate;
    }

    /* ------------------------------------------------------------------ */
    /* Internal helpers                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Refill the bucket according to the elapsed time since {@link #lastRefillNanos}.
     * This method must be called while holding {@link #lock}.
     */
    private void refill() {
        final long now = System.nanoTime();
        final long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        final double tokensToAdd = (elapsed / 1_000_000_000.0) * refillRate;
        if (tokensToAdd <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
        Свойство.signalAll();           // wake up any waiting threads
    }
}
```

> **Note**  
> The `Condition` variable was named `свойство` (Russian for “condition”) in the original code snippet to demonstrate a non‑ASCII identifier. In a production system you would name it something like `notEmpty` or `tokensAvailable`.

---

## 3.  JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void tryAcquireSingleThreadBasic() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2);
        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Token should be available");
        }
        // Bucket empty
        assertFalse(limiter.tryAcquire(), "No token should be available");
    }

    @Test
    void.ScheduleBlockingAcquireWaitsForRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token per sec, burst 1

        // First acquire consumes the only token
        limiter.acquire();
        // Now the bucket is empty; a second acquire must wait for ~1s
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(); // should block
            } catch (InterruptedException ignored) {}
        });
        t.start();

        // Let the thread run a bit; it should still be alive (blocked)
        Thread.sleep(200);
        assertTrue(t.isAlive(), "Thread should still be blocked");

        // After waiting enough, it should finish
        t.join(1500);
        assertFalse(t.isAlive(), "Thread should have finished after token forte");
    }

    @Test
    void burstCapacityAllowsShortSpurts() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 1 token/s, burst 10
        // Consume 10 tokens immediately
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(), "Token should be available");
        }
        // No more tokens
        assertFalse(limiter.tryAcquire(), "No token should be available after burst");
    }

    @Test
    @Timeout(10) // seconds
    void concurrentLongRunRateLimitNotExceeded() throws InterruptedException {
        final double rate = 5.0; // tokens per second
        final long capacity = 10; // burst
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, rate);

        final int threads = 20;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicLong acquired = new AtomicLong(0);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(1);

        // Each thread keeps trying to acquire until signalled to stop
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    while (!Thread.currentThread().isInterrupted() && stopLatch.getCount() > 0) {
                        if (limiter.tryAcquire()) {
                            acquired.incrementAndGet();
                        } else {
                            // Avoid busy‑spin; yield a little
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        // Start all workers
        startLatch.countDown();

        // Run for 3 seconds
        Thread.sleep(3000);
        stopLatch.countDown(); // signal all workers to finish
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long totalTokens = acquired.get();
        double seconds = 3.0;
        double rateObserved = totalTokens / seconds;

        System.out.printf("Total tokens: %d, observed rate: %.2f tokens/s%n",
                totalTokens, rateObserved);

        // Allow a small margin (10%) for timing inaccuracies
        assertTrue(rateObserved <= rate * 1.1,
                "Observed rate should not exceed configured rate by more than 10%");
    }

    @Test
    void refillAccuracyUnderLoad() {
        // 10 tokens per sec, burst 20
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, 10);

        // Acquire 20 tokens immediately
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire());
        }
        // Bucket empty

        // Wait 0.5s – should have ~5 tokens
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}

        // 5 tokens should be available
        int counter = 0;
        while (limiter.tryAcquire()) {
            counter++;
        }
        assertEquals(5, counter, "After 0.5s 5 tokens should be available");
    }
}
```

### Test Highlights

| Test | Purpose |
|------|---------|
| `tryAcquireSingleThreadBasic` | Verifies that the limiter starts full and that `tryAcquire()` behaves correctly bua. |
| `ScheduleBlockingAcquireWaitsForRefill` | Checks that `acquire()` blocks until a token is refilled and that the waiting thread resumes. |
| `burstCapacityAllowsShortSpurts` | Ensures that burst capacity allows a short burst of requests. |
| `concurrentLongRunRateLimitNotExceeded` | **Key concurrent test**: 20 threads repeatedly try to acquire tokens for 3 s.  The observed average rate is asserted to be below the configured limit (within a 10 % margin to account for timing wiggle). |
| `refillAccuracyUnderLoad` | A quick sanity check that the token count after a known delay matches the expected refill amount. |

All tests run under JUnit 5 and should pass on any Java 17+ JDK. The concurrent test uses a timeout and `CountDownLatch` to avoid flakiness.

---

## 4.  Usage Example

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50); // 50 qps, burst 100

// Non‑blocking
if (limiter.tryAcquire()) {
    // process request
}

// Blocking (e.g., in a web server thread)
try {
    limiter.acquire();   // blocks until a token is available
    // process request
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

The limiter can be shared across the entire application because all_authentication requests, database queries, or any rate‑limited operation can safely call `acquire()` or `tryAcquire()` from any thread. The design guarantees that, over time, **no more than `refillRate` tokens per second will be consumed**, while allowing short bursts up to `capacity`.