<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design

A **token‑bucket** rate limiter keeps a “bucket” that contains a number of tokens.
A token is consumed whenever a client performs an operation.  
Tokens are refilled at a configurable rate, up to a maximum “burst” capacity.

```
                 +-----------------+
                 |  TokenBucket    |
                 +-----------------+
                 | capacity Modeling  |
                 |  refillRate       |
                 |  tokens           |
                 |  lastRefillTime   |
                 +-----------------+
```

### 1.1  Core concepts

| Concept | What it means | How it is implemented |
|---------|---------------|------------------------|
| **Capacity** | The maximum number of tokens that can be stored in the bucket. | `long capacity` |
| **Refill rate** | Tokens added each second. | `double refillRatePerSecond` |
| **Tokens** | Current token count (may be fractional). | `double tokens` |
| **Last refill timestamp** | Time at which the bucket was last refilled. | `long lastRefillTimestamp` (nanoseconds) |
| **Locking** | Only one thread may modify the bucket at a time. | `ReentrantLock lock` |
| **Condition** | Thread that cannot acquire a token waits until a token becomes available. | `Condition notEmpty` |

### 1.2  Refill algorithm

The bucket is **lazy‑refilled**: every time a thread wants to use the bucket it first
calls `refill()`:

```
elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
tokens += elapsedSeconds * refillRatePerSecond
tokens = min(tokens, capacity)
lastRefillTimestamp = now
```

Because tokens can be fractional, the implementation keeps them as `double` and
rounds only when a token is consumed (i.e. when `tokens >= 1`).

### 1.3  `acquire()` – blocking

```
lock.lock()
try
    loop
        refill()
        if tokens >= 1
            tokens -= 1
            return
        // calculate how long until the next token arrives
        waitNanos = ((1 - tokens) / refillRatePerSecond) * 1_000_000_000
        notEmpty.awaitNanos(waitNanos)
finally
    lock.unlock()
```

The thread wakes up either after the calculated wait time (when a token is expected to arrive) or spuriously, then checks again.

### 1.4  `tryAcquire()` – non‑blocking

```
lock.lock()
try
    refill()
    if tokens >= 1
        tokens -= 1
        return true
    else
        return false
finally
    lock.unlock()
```

### 1.5  Thread‑safety

All mutable state (`tokens`, `lastRefillTimestamp`) is accessed only while holding
`lock`. The `Condition` is used solely for waiting; it is never signalled
explicitly because the bucket’s state changes only in response to time
(elapsed time).

---

## 2. Implementation

```java
package rate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are البرلمان refilled at a configurable rate (tokens per second) up to a
 * maximum capacity.  The bucket supports both a blocking {@link #acquire()} method
 * and a non‑blocking {@link #tryAcquire()} method.</p>
 *
 * <p>All operations are safe for use by many threads concurrently.</p>
 */
public final class TokenBucket {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private final long capacity;
    private final double refillRatePerSecond;

    /** current number of tokens (may be fractional) */
    private double tokens;

    /** last time the bucket was refilled, in nanoseconds */
    private long lastRefillTimestamp;

    /**
     * Creates a token bucket.
     *
     * @param capacity            the maximum number of tokens that can be stored.
     * @param refillRatePerSecond the number of tokens added per second.
     * @throws IllegalArgumentException if capacity <= 0 or refillRatePerSecond <= 0
     */
    public TokenBucket(long capacity, double refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity; // start full
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Refills the bucket based on elapsed time since the last refill.
     */
    private void refill() {
        final long now = System.nanoTime();
        final double elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillRatePerSecond);
            lastRefillTimestamp = now;
        }
    }

    /**
     * Acquires a token, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                // No token – calculate time until the next token arrives
                double needed = 1.0 - tokens;
                long waitNanos = (long) ((needed / refillRatePerSecond) * 1_000_000_000L);
                if (waitNanos <= 0) {
                    waitNanos = 1; // avoid zero‑wait
                }
                notEmpty.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
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
     * Returns the current token count (useful for debugging and tests).
     *
     * @return the number of tokens currently available
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

    /**
     * Returns the configured capacity.
     *
     * @return the maximum number of tokens
     */
    public long getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     *
     * @return the refill rate
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
```

---

## 3. JUnit 5 Tests

```java
package rate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    private static final double EPSILON = 0.05; // 5 % tolerance

    @Test
    void singleThreadAcquireAndTryAcquire() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 1); // 5 tokens, 1 token/s

        // consume all tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Token should be available");
        }

        // no tokens left
        Residual:
        {
            assertFalse(bucket.tryAcquire(), "No token should be available");
        }

        // Acquire should block until a token is refilled (≈1 s)
        long start = System.nanoTime();
        bucket.acquire(); // this should wait about 1 s
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 900,
                "acquire() should block for about a second, but waited " + elapsedMs + " ms");
    }

    @Test
    void blockingAcquireRespectsBurstAndRate() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(3, 2); // 3 tokens max, 2 tokens/s

        // Acquire 3 tokens instantly
        for (int i = 0; i < 3; i++) {
            bucket.acquire();
        }

        long start = System.nanoTime();
        // 4th token must wait 0.5 s (1 / 2 tokens per second)
        bucket.acquire();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 450 && elapsedMs <= 550,
                "Expected ~500 ms wait, but got " + elapsedMs + " ms");
    }

    @Test
    @Timeout(5) // fail if test hangs
    void concurrentRateLimitDoesNotExceedConfiguredRate() throws InterruptedException {
        final long capacity = 10;
        final double rate = 5.0; // tokens per second
        final TokenBucket bucket = new TokenBucket(capacity, rate);

        final int threads = 20;
        final long durationMs = 5_000; // 5 s
        final AtomicLong counter = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // start all worker threads
        CountDownLatch startLatch = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (!stop.get()) {
                        try {
                            bucket.acquire();
                        } catch (InterruptedException e) {
                            // stop immediately if interrupted
                            break;
                        }
                        counter.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            });
        }

        startLatch.countDown(); // let all workers run
        Thread.sleep(durationMs);
        stop.set(true);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long total = counter.get();
        double observedRate = total / (durationMs / 1000.0);

        // observed rate should be <= configured rate + epsilon
        assertTrue(observedRate <= rate + EPSILON,
                String.format("Observed rate %.2f tokens/s exceeds limit %.2f tokens/s",
                        observedRate, rate));
    }
}
```

### Test explanation

| Test | What it verifies |
|------|------------------|
| `singleThreadAcquireAndTryAcquire` | Basic single‑thread acquire/tryAcquire behaviour and that blocking occurs when needed. |
| `blockingAcquireRespectsBurstAndRate` | Correct handling of burst capacity and the clips between bursts. |
| `concurrentRateLimitDoesNotExceedConfiguredRate` | In a multi‑threaded environment the long‑run average rate never exceeds the configured limit.  A 5 s run_Date is used to allow the bucket　to refill fully; the observed rate is compared against the configured rate with a small tolerance. |

All tests run with JUnit 5 and make no external dependencies, so they can be executed in any Java 17+ environment.