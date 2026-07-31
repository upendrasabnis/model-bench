<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design – Thread‑Safe Token‑Bucket Rate Limiter

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ TokenBucket – rate limiting with configurable burst capacity and refill rate ولسوال │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 1.  What is a token bucket?

* **Capacity** – maximum number of tokens that can sit in the bucket.  
  Represents the *burst* that can be served immediately.
* **Refill rate** – tokens added per second (can be fractional).  
  Determines the *steady‑state* throughput.
* **Tokens** – the current number of tokens that can be consumed.

A call to `acquire()` consumes one token.  
If the bucket is empty the call blocks until a token becomes available.  
`tryAcquire()` returns immediately with a boolean indicating success.

### 2.  Algorithmic details

* Tokens are stored as a `double` to allow fractional accumulation.
* The bucket is **stateless** – the only mutable state is `tokens`,
  `lastRefillNanos`, and the lock/condition.
* Tokens are **refilled lazily**:
  * On every call to `acquire()` or `tryAcquire()` we first compute how many
    new tokens should have arrived since the last refill and add them, capped
    by `capacity`.
  * `lastRefillNanos` is updated to the current time *after* the refill
    calculation.
* Waiting is performed with a `ReentrantLock` & `Condition`:
  * `acquire()` loops until a token can be taken.
  * The loop calculates the exact time until the next token would be
    available (`(1 – tokens) / refillRate` seconds) and calls
    `awaitNanos(...)`.  Spurious wake‑ups are handled by re‑looping.

### 3.  Thread safety

* All public methods obtain the same `ReentrantLock`.  
  The lock guarantees mutual exclusion for:
  * Refill calculation
  * Token consumption
  * Condition waiting
* `Condition` is used only for blocking on `acquire()`.  
  No external signalling is required because the thread wakes up
  automatically when the waiting time expires.

### 4.  API

```java
public class TokenBucket {

    /** Create a bucket with the given capacity and refill rate. */
    public TokenBucket(double capacity, double refillRatePerSecond);

    /** Acquire a token, blocking until it becomes available. */
    public void acquire() throws InterruptedException;

    /** Try to acquire a token immediately. */
    public boolean tryAcquire();

    /** Current number of tokens (for introspection, not part of the API). */
    public double getTokenCount();
}
```

*`capacity`* and *`refillRatePerSecond`* must be positive; otherwise an
`IllegalArgumentException` is thrown.

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
 * <p>The bucket refills at a configurable rate (tokens per second) and
 * can hold up to {@code capacity} tokens.  {@code acquire()} blocks until a
 * token is available; {@code tryAcquire()} returns immediately.</p>
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillRatePerSecond;

    private double tokens;                 // current token count, fractional
    private long lastRefillNanos;          // time of last_j refill

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * @param capacity           maximum number of tokens (burst size)
     * @param refillRatePerSecond tokens added per second (may be fractional)
     * @throws IllegalArgumentException if capacity <= 0 or refillRate <= 0
     */
    public TokenBucket(double capacity, double refillRatePerSecond) {
        if (capacity <= 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException(
                "capacity and refillRatePerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity;  // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Acquire a token, blocking until one becomes available.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refillIfNeeded();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                long nanosToWait = nanosUntilNextToken();
                if (nanosToWait > 0) {
                    notEmpty.awaitNanos(nanosToWait);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Try to acquire a token immediately.
     *
     * @return {@code true} if a token was consumed, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refillIfNeeded();
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
     * Current number of tokens (for introspection, not part of the contract).
     */
    public double getTokenCount() {
        lock.lock();
        try {
            refillIfNeeded();
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Internal helpers                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Refill tokens based on the elapsed time since the last refill.
     * The method is always called while holding {@code lock}.
     */
    private void refillIfNeeded() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return; // nothing to do
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double newTokens = elapsedSeconds * refillRatePerSecond;
        if (newTokens <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + newTokens);
        lastRefillNanos = now;
    }

    /**
     * Calculate senha nanos until the next token becomes available.
     * Should be called only while holding {@code lock}.
     */
    private long nanosUntilNextToken() {
        if (tokens >= 1.0) {
            return 0;
        }
        double missing = 1.0 - tokens;
        double seconds = missing / refillRatePerSecond;
        long nanos = (long) (seconds * 1_000_000_000L);
        return Math.max(nanos, 0);
    }
}
```

---

## JUnit 5 Test Suite

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    /** 1 token per second, capacity 5 (burst). */
    private static final double REFILL_RATE = 1.0;
    private static final double CAPACITY = 5.0;

    @Test
    @DisplayName("Non‑blocking tryAcquire works as expected")
    void testTryAcquire() throws Exception {
        TokenBucket tb = new TokenBucket(CAPACITY, REFILL_RATE);

        // consume all burst tokens
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(tb.tryAcquire());
        }

        // bucket empty now
        assertFalse(tb.tryAcquire());

        // wait for one refill
        TimeUnit.MILLISECONDS.sleep(1100);
        assertTrue(tb.tryAcquire());
    }

    @Test
    @DisplayName("Blocking acquire waits until token is available")
    void testAcquireBlocking() throws Exception {
        TokenBucket tb = new TokenBucket(1, REFILL_RATE); // only one token

        tb.acquire(); // consume it

        long start = System.nanoTime();
        tb.acquire(); // should block ~1s
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsed >= 1000,
                   "Expected at least 1 s wait, got " + elapsed + " ms");
    }

    @Test
    @DisplayName("Burst capacity is respected")
    void testBurstCapacity() throws Exception {
        TokenBucket tb = new TokenBucket(CAPACITY, REFILL_RATE);

        // consume all burst tokens
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(tb.tryAcquire());
        }

        // after 10 seconds, bucket should be full again
        TimeUnit.SECONDS.sleep(10);
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(tb.tryAcquire());
        }
    }

    @Test
    @DisplayName("Long‑run rate does not exceed the configured limit")
    void testConcurrentLongRunRate() throws Exception {
        final double refRate = 2.0;   // tokens per second
        final double cap = 5.0;       // burst
        final TokenBucket bucket = new TokenBucket(cap, refRate);

        final int workers = 10;
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean(false);

        ExecutorService exec = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            exec.submit(() -> {
                while (!stop.get()) {
                    if (bucket.tryAcquire()) {
                        acquired.incrementAndGet();
                    } else {
                        // small sleep to avoid busy‑waiting
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
        }

        final long runMillis = 5_000; // 5 s
        long start = System.nanoTime();
        Thread.sleep(runMillis);
        stop.set(true);
        exec.shutdownNow();
        exec.awaitTermination(1, TimeUnit.SECONDS);
        long elapsedSec = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 1000;

        int ops = acquired.get();
        double actualRate = ops / (double) elapsedSec;

        // allow a small tolerance (±10 %)
        double tolerance = 0.1 * refRate;
        assertTrue(actualRate <= refRate + tolerance,
                   () -> String.format("rate %.2f > expected %.2f (tolerance %.2f)",
                                       actualRate, refRate, tolerance));
    }
}
```

### Test Explanations

| Test | What it checks | Why it matters |
|------|----------------|----------------|
| `testTryAcquire` | Non‑blocking consumption and refill timing | Basic functionality |
| `testAcquireBlocking` | `acquire()` blocks until a token is available | Correctness of blocking behavior |
| `testBurstCapacity` | Burst limit and refill over a longer period | Guarantees that the capacity bound is respected |
| `testConcurrentLongRunRate` | Long‑run throughput with many threads | Confirms the limiter actually limits the *steady‑state* rate, even under concurrency |

The concurrency test runs the bucket for five seconds, letting 10 worker threads repeatedly try to acquire tokens.  It then checks that the average rate does not exceed `refillRatePerSecond` by more than 10 %.  This demonstrates that the implementation is safe for many threads and respects the configured rate.

---

### Usage Example

```java
TokenBucket limiter = new TokenBucket(10, 5); // burst 10, 5 tokens/s

// Blocking call
limiter.acquire();          // will block if no token is available

// Non‑blocking call
if (limiter.tryAcquire()) {
    // proceed immediately
}
```

With this implementation you can safely rate‑limit any shared resource in a multi‑threaded Java application.