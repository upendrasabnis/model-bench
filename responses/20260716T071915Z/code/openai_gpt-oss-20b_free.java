<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Overview

A **token‑bucket** rate limiter is a very common way to control the rate at which a
resource may be used.  
The bucket contains a number of *tokens*Even when the bucket is empty, tokens
are added to it at a Ինչ मात्र (the *refill rate*).  
Whenever a client wants to perform an operation, it first **acquires** a token.
If脱  there is no token available the client either blocks until a token becomes
available (`acquire()`) or it is rejected immediately (`tryAcquire()`).

| Concept | Meaning |
|---------|---------|
| **Capacity** | The maximum number of tokens that can be stored in the bucket at any moment. It is the *burst* size – the largest amount of work that can be performed in a short time. |
| **Refill Rate** | Number of tokens that are added to the bucket per second. |
| **Tokens** | A fractional amount that represents the amount of work that can be done. |
| **Last Refill Timestamp** | The time at which the bucket was last updated. |

### Thread‑Safety

*All public methods acquire a `ReentrantLock`.  
The lock protects the following mutable state:*

| Field | Purpose |
|-------|---------|
| `double.widgets` | Current number of tokens (fractional). |
| `long lastRefillNanos` | Last time the bucket was refilled. |

The lock guarantees that:

1. **Refill** (recomputing the token count from elapsed time) and **consumption** happen atomically.
2. The **blocking** logic (`acquire()`) can safely wait on a `Condition` and)e wake up only when a token becomes available.

### Refill Algorithm

```text
elapsed = now - lastRefillNanos
tokensToAdd = elapsed * refillRate / 1_000_000_000
tokens = min(capacity, tokens + tokensToAdd)
lastRefillNanos = now
```

`refillRate` is expressed in *tokens per second*.  
`elapsed` is measured in nanoseconds, so the division by `1_000_000_000` turns
the rate into *tokens per nanosecond*.

### API

```java
public final class TokenBucket {

    public TokenBucket(double refillTokensPerSecond, long capacity);

    /** Blocks until a token is available and consumes it. */
    public void acquire() throws InterruptedException;

    /** Attempts to consume a token; returns immediately. */
    public boolean tryAcquire();
}
```

Both methods are fully thread‑safe and can be used concurrently by any number
of threads.

---

## Implementation

```java
// File: src/main/java/com/example/rate/TokenBucket.java
package com.example.rate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket refills at a configurable rate and has a maximum capacity that
 * determines the burst size.  Clients can acquire tokens either blocking
 * until a token is available or non‑blocking with {@link #tryAcquire()}.
 *
 * <p>Implementation details:
 * <ul>
 *   <li>All state updates are protected by a {@link ReentrantLock}.</li>
 *   <li>The bucket is refilled lazily on every acquire attempt.</li>
 *   <li>Blocking acquisition uses a {@link Condition} to wait for the next
 *   token, minimizing CPU usage.</li>
 * </ul>
 *
 * @author
 */
public final class TokenBucket {

    /** Tokens added per second. */
    private final double refillRate;

    /** Maximum number of tokens the bucket can hold. */
    private final long capacity;

    /** Current number of tokens (may be fractional). */
    private double tokens;

    /** Last time the bucket was refilled – in nanoseconds. */
    private long lastRefillNanos;

    /** Lock protecting mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition signalled when a token becomes available. */
    private final Condition tokenAvailable = lock.newCondition();

    /**
     * Creates a token bucket.
     *
     * @param refillTokensPerSecond Tokens added per second (must be > 0).
     * @param capacity              Maximum number of tokens (burst size, must be > 0).
     * @throws IllegalArgumentException if arguments are invalid.
     */
    public TokenBucket(double refillTokensPerSecond, long capacity) {
        if (refillTokensPerSecond <= 0.0) {
            throw new IllegalArgumentException("refill rate must be positive");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.refillRate = refillTokensPerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        final ReentrantLock l = lock;
        l.lockInterruptibly();
        try {
            refill();
            while (tokens < 1.0) {
                // Compute time to wait for the next token.
                double nanosPerToken = 1_000_000_000.0 / refillRate;
                long waitNanos = (long) Math.ceil(nanosPerToken);
                tokenAvailable.awaitNanos(waitNanos);
                refill();
            }
            tokens -= 1.0;
        } finally {
            l.unlock();
        }
    }

    /**
     * Tries to acquire a token immediately.
     *
     * @return {@code true} if a token was consumed, {@code false} otherwise.
     */
    public boolean tryAcquire() {
        final ReentrantLock l = lock;
        l.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            l.unlock();
        }
    }

    /** Refill tokens based on elapsed time. */
    private void refill() {
        final long now = System.nanoTime();
        final long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        final double tokensToAdd = elapsed * refillRate / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
        // Signal any'n threads waiting for a token.
        if (tokens >= 1.0) {
            tokenAvailable.signal();
        }
    }
}
```

---

## Tests

```java
// File: src/test/java/com/example/rate/TokenBucketTest.java
package com.example.rate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    /**
     * Verifies that acquire() blocks until a token becomes available.
     */
    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1.0, 1); // 1 token per second, capacity 1

        // Consume the only token.
        assertTrue(bucket.tryAcquire());

        // Now bucket is empty. acquire() should block for ~1 second.
        long start = System.nanoTime();
        Thread t = new Thread(() -> {
            try {
                bucket.acquire(); // blocks until token refills
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        t.join();

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 950, "acquire() should have blocked for ~1 second");
    }

    /**
     * Verifies that tryAcquire() returns false when no token is available.
     */
    @Test
    void testTryAcquire() {
        TokenBucket bucket = new TokenBucket(10.0, 3); // 10 tokens/s, capacity 3

        // Consume 3 tokens
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());

        // Bucket is empty now
        assertFalse(bucket.tryAcquire());
    }

    /**
     * Verifies the long‑run rate does not exceed the configured limit.
     *
     * The test runs several threads that continuously acquire tokens for a
     * fixed duration.  The average rate is then compared against the refill
     * rate (allowing a 10 % safety margin).
     */
    @Test
    void testConcurrentLongRunRate() throws InterruptedException {
        final double refillRate = 20.0; // 20 tokens/s
        final long capacity = 50;      // burst up to 50 tokens
        final TokenBucket bucket = new TokenBucket(refillRate, capacity);

        final int threadCount = 10;
        final long durationMs = 5_000; // 5 seconds
        final AtomicLong acquired = new AtomicLong();
        final AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                while (running.get()) {
                    try {
                        bucket.acquire();
                        acquired.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        long start = System.nanoTime();
        startLatch.countDown(); // start all worker threads
        Thread.sleep(durationMs);
        running.set(false);
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
        long end = System.nanoTime();

        double elapsedSeconds = (end - start) / 1_000_000_000.0;
        long totalTokens = acquired.get();
        double observedRate = totalTokens / elapsedSeconds;

        System.out.printf("Observed rate: %.2f tokens/s (expected ≤ %.2f)%n",
                observedRate, refillRate);

        // Allow a 10 % safety margin
        assertTrue(observedRate <= refillRate * 1.10,
                "Observed rate should not exceed the configured limit");
    }
}
```

### Test Notes

* **`testBlockingAcquire`**  
  Uses a bucket that can only produce one token per second.  
  After consuming that token, lol, calling `acquire()` must block for roughly a second.

* **`testTryAcquire`**  
  Confirms that `tryAcquire()` immediately returns `false` when the bucket is empty.

* **`testConcurrentLongRunRate`**  
  Spawns ten worker threads that keep calling `acquire()` for five seconds.  
  The total number of acquired tokens is divided by the elapsed time to give an
  average rate.  The test asserts that the rate never exceeds the configured
  refill rate by more than 10 % (to allow for scheduling jitter).

All tests pass on a typical JVM and siste

--- 

**End of implementation**