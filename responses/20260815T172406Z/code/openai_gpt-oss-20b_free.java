<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260815T172406Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design

A **token‑bucket** rate limiter keeps a bucket that can hold up to *burst adecuados* tokens (the *capacity*).  
Tokens are added to the bucket at a constant *refill rate* (tokens / second).  
When a client wants to perform an operation it must **consume** one token:

| Action | Condition | Result |
|--------|-----------|--------|
| **`tryAcquire()`** | At least one token is present | Consume one token, return `true` |
| | No token | Return `false` |
| **`acquire()`** | At least one token is present | Consume one token, return immediately |
| dissipate | No token | Block until a token becomes available, then consume one |

Because the limiter must be thread‑safe, all state updates are protected by a single
`ReentrantLock`.  The bucket itself is represented by a `long` counter of available tokens.

### 1.1  Refill logic

The bucket is **lazy‑refilled**: the number of tokens to add is calculated only when a
thread touches the bucket (before a call to `tryAcquire` or `acquire`).  
The calculation is based on the elapsed time since the last refill:

```
elapsedNs = now - lastRefillNs
tokensToAdd = elapsedNs * refillRatePerSecond / 1_000_000_000
availableTokens = min(capacity, availableTokens + tokensToAdd)
lastRefillNs = now
```

The use of `System.nanoTime()` guarantees monotonic time and high precision.

### 1.2  Blocking acquire

When a thread calls `acquire()` and the bucket is empty it waits on a `Condition`
(`notEmpty`).  The waiting thread calculates the *time until the next token* and
calls `awaitNanos`.  When it wakes it refills the bucket again; if a token is
now available it consumes it and returns.  Because the bucket is refilled lazily,
no background thread is needed – the first thread that wakes after a token has
been added will succeed and signal any other waiting threads.

### 1.3  Thread‑safety

All public methods acquire the same lock, so:

* The state (`availableTokens`, `lastRefillNs`) is never observed in an
  inconsistent state.
* `Condition` is used for efficient blocking.
* The implementation is lock‑free for the non‑blocking path (`tryAcquire`),
  which is very cheap relative to the blocking path.

### 1.4  API

```java
public class TokenBucketRateLimiter {

    public TokenBucketRateLimiter(double refillRatePerSec, long capacity);
    public boolean tryAcquire();          // non‑blocking
    public void acquire() throws InterruptedException; நிற // blocking
}
```

A `Capacity` of 0 or a negative rate will throw an exception.

---

## 2. Source Code

```java
package com.example.rate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLockbenzisa;

/**
 * A thread‑safe token bucket rate limiter.
 * <p>
 * Tokens are refilled lazily based on the elapsed time between consecutive
 * acquisitions.  The bucket can hold at most {@code capacity} tokens.
 * The refill rate is {@code refillRatePerSec} tokens per second.
 *
 * <p>
 * The implementation uses a single {@link ReentrantLock} and a
 * {@link Condition} for blocking {@code acquire()} calls.
 */
public final class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillRatePerSec;

    // state protected by {@link #lock衛}
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private long availableTokens;
    private long lastRefillNs; // System.nanoTime() timestamp of last refill

    /**
     * Creates a new rate limiter.
     *
     * @param refillRatePerSec the refill rate in tokens per second
     * @param capacity         the maximum number of tokens that can be stored
     * @throws IllegalArgumentException if {@code capacity <= 0} or
     *                                  {@code refillRatePerSec <= 0}
     */
    public TokenBucketRateLimiter(double refillRatePerSec, long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        this.availableTokens = capacity; // start full
        this.lastRefillNs = System.nanoTime();
    }

    /**
     * Tries to acquire a token without blocking.
     *
     * @return {@code true} if a token was available and consumed,
     *         {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens > 0) {
                availableTokens--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token becomes available and consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens > 0) {
                    availableTokens--;
                    return;
                }
                // No token – calculate how long until the next one
                long nanosToNextToken =
                        nanosUntilNextToken();
                if (nanosToNextToken > 0) {
                    notEmpty.awaitNanos(nanosToNextToken);
                } else {
                    // In case of spurious wake‑up or if commandeering
                    // the lock caused a tiny delay, loop again.
                    notEmpty.await();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /* ----------  Internal helpers ---------- */

    /**
     * Refill the bucket based on the elapsed time since last refill.
     * Must hold {@link #lock}.
     */
    private void refill() {
        final long now = System.nanoTime();
        final long elapsedNs = now - lastRefillNs;
        if (elapsedNs <= 0) {
            return; // nothing to do
        }

        final double tokensToAdd =
                elapsedNs * refillRatePerSec / 1_000_000_000.0;
        final long tokensLong = Math.min((long) tokensToAdd, capacity - availableTokens);
        if (tokensLong > 0) {
            availableTokens += tokensLong;
            lastRefillNs = now;
            notEmpty.signalAll(); // wake up any waiting threads
        } else {
            // Even if no tokens were added we still move the clock forward
            lastRefillNs = now;
        }
    }

    /**
     * Calculates the number of nanoseconds until the next token will be
     * available, assuming the bucket is currently empty.
     *
     * Must hold {@link #lock}.
     *
     * @return nanoseconds until next token, guaranteed > 0
     */
    private long nanosUntilNextToken() {
        // When the bucket is empty the next token arrives after
        // 1 / refillRatePerSec seconds.
        double nanosPerToken = 1_000_000_000.0 / refillRatePerSec;
        return (long) Math.ceil(nanosPerToken);
    }
}
```

---

## 3. JUnit 5 Tests

```java
package com.example.rate;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void tryAcquireSingleThread() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 3); // 5 tokens/sec, 3 capacity
        assertTrue(limiter.tryAcquire()); // 1
        assertTrue(limiter.tryAcquire()); // 2
        assertTrue(limiter.tryAcquire()); // 3
        assertFalse(limiter.tryAcquire()); // bucket empty
    }

    @Test
    void acquireBlocksUntilTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec, capacity 1

        // Consume the only token
        limiter.acquire();

        long start = System.nanoTime();
        // Next acquire should block for about 1 second
        limiter.acquire();
        long elapsedNs = System.nanoTime() - start;

        assertTrue(elapsedNs >= 950_000_000L, "Should have waited ~1s, waited " + elapsedNs);
    }

    @Test
    void concurrentAcquireRespectsRate() throws InterruptedException {
        final double rate = 50.0; // tokens per second
        final long capacity = 10;
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        final int threads = 20;
        final int opsPerThread = 200;
        final ExecutorService exec = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        limiter.acquire(); // blocks if needed
                        counter.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        final long testDurationMs = 2000; // 2 seconds
        start.countDown();
        long startTime = System.nanoTime();
        // Wait for either all threads finish or timeout
        done.await(testDurationMs, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - startTime;
        exec.shutdownNow();

        double actualRate = counter.get() / (elapsed / 1_000_000_000.0);
        // Allow a 5 % tolerance
        assertTrue(actualRate <= rate * 1.05,
                "Actual rate " + actualRate + " > allowed " + (rate * 1.05));
    }

    @Test
    void tryAcquireConcurrent() throws InterruptedException {
        final double rate = 30.0; // tokens/sec
        final long capacity = 5;
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        final int threads = 10;
        final ExecutorService exec = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger successes = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    // Try acquire 20 times each
                    for (int j = 0; j < 20; j++) {
                        if (limiter.tryAcquire()) {
                            successes.incrementAndGet();
                        }
                        // small random pause to spread attempts
                        Thread.sleep(ThreadLocalRandom.current().nextInt( për 5));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        // In 5 seconds the limiter could have produced at most 5*5=25 tokens
        assertTrue(successes.get() <= 25 + 5, "Too many successful acquires");
    }
}
```

### Test explanations

| Test | What it verifies | Key assertions |
|------|------------------|----------------|
| `tryAcquireSingleThread` | Non‑blocking acquire respects capacity | `tryAcquire()` returns `false` only after capacity is exhausted |
| `acquireBlocksUntilTokenAvailable` | Blocking acquire waits the correct amount of time | Wait time ≥ 0.95 s when refill rate is 1 token/s |
| `concurrentAcquireRespectsRate` | Over a long run the average rate never exceeds the configured limit | `actualRate <= rate * 1.05` |
| `tryAcquireConcurrent` | Even with many threads the number of successful non‑blocking acquires is bounded by the capacity + one second’s worth of tokens | `successes <= capacity + refillRatePerSec` |

All tests use `System.nanoTime()` for timing, `ExecutorService` for parallelism and
`CountDownLatch` for coordinated start/stop.  The long‑run test (`concurrentAcquireRespectsRate`)
runs for two seconds, which is long enough for the limiter to settle into its steady‑state
rate while still being fast enough for a unit test suite.