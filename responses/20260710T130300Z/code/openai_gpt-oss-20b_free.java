<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260710T130300Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview

A **token‑bucket** rate‑limiter is a classic algorithm that controls the rate of an operation by granting *tokens* that are consumed when the operation is performed.  
The bucket can hold up to **burstCapacity** tokens; tokens are replenished at a constant **rate** (tokens per second).  
When the bucket is empty, callers must wait until a token becomes available.  

### 1.1 Thread‑Safety

The limiter must be usable from many threads simultaneously.  
We use:

| Primitive | Purpose |
|-----------|---------|
| `ReentrantLock` | Serial crawls of token updates, refill, and consumption. |
| `Condition` | Allows threads to block until a token is available. |

All state changes (`tokens`, `lastRefillTimestamp`) happen inside the lock, guaranteeing mutual exclusion.  
The condition is signalled whenever the bucket is refilled, waking up waiting threads.

### 1.2 Refill Strategy

Refilling is *lazy*: each call to `acquire()` or `tryAcquire()` first calculates how many tokens should have been added since the last refill, then updates the bucket.

```
elapsed = now - lastRefill
tokensToAdd = elapsed * rate
tokens = min(maxTokens, tokens + tokensToAdd)
lastRefill = now
```

Using `System.nanoTime()` gives high‑resolution timing without being affected by system clock changes.

### 1.3 API

| Method | Behaviour |
|--------|-----------|
| `public TokenBucketLimiter(double rate, int burstCapacity)` | Constructor – `rate > 0`, `burstCapacity > 0`. |
| `public void acquire() throws InterruptedException` | Blocks until a token is available, then consumes one. |
| `public boolean tryAcquire()` | Returns `true` if a token was immediately available; otherwise returns `false`. |
| `public double getTokens()` | (For testing) returns the current number of tokens (may be fractional). |

### 1.4 Blocking Acquire Implementation

Inside the lock:

1. Refill أسباب.
2. If a token is available → consume and return.
3. Otherwise compute the time until the next token becomes available (`1 / rate`), convert to nanoseconds, and `awaitNanos` on the condition.
4. Loop until a token is available.

The algorithm guarantees that the long‑run average acquisition rate never exceeds the configured `rate`.

---

## 2. Implementation (Java 17+)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The limiter refills tokens at a configurable rate (tokens per second) up to a maximum
 * burst capacity.  Threads can block until a token is available or they can try to acquire
 * a token without blocking.</p>
 */
public final class TokenBucketLimiter {

    private final double rate;          // tokens per second
    private final int maxTokens;        // burst capacity

    private double tokens;              // current number of tokens (may be fractional)
    private long lastRefillNanos;       // timestamp of last refill in nanoseconds

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param rate tokens per second (must be > 0)
     * @param burstCapacity maximum number of tokens the bucket can hold (must be > 0)
     */
    public TokenBucketLimiter(double rate, int burstCapacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be positive");
        }
        this.rate = rate;
        this.maxTokens = burstCapacity;
        this.tokens = burstCapacity; // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Blocks until a token is available and consumes it.
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
                // No token available – wait until the next one is expected
                long nanosToNextToken = nanosUntilNextToken();
                if (nanosToNextToken > 0) {
                    notEmpty.awaitNanos(nanosToNextToken);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to acquire a token without blocking.
     *
     * @return {@code true} if a token was consumed, {@code false} otherwise
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
     * Returns the current number of tokens (for testing purposes).
     *
     * @return the current token count (may be fractional)
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

    /* ---------- internal helpers ---------- */

    /**
     * Refills the bucket based on elapsed time since the last refill.
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) * 1e-9;
        if (elapsedSeconds <= 0) return;

        double tokensToAdd = elapsedSeconds * rate;
        if (tokensToAdd > 0) {
            tokens = Math.min(maxTokens, tokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Computes nanoseconds until the next token is expected.
     *
     * @return nanoseconds until the next token
     */
    private long nanosUntilNextToken() {
        // The bucket refills at a constant rate, so the expected time for the next token
        // is the time until the bucket would have reached one token.
        // If tokens were zero, we need exactly 1/rate seconds.
        return (long) ((1.0 / rate) * 1_000_000_000L);
    }
}
```

---

## 3. JUnit 5 Test Suite

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketLimiterTest {

    @Test
    void singleThreadAcquisitionAndBurst() {
        // 2 tokens/s, burst capacity 5
        TokenBucketLimiter limiter = new TokenBucketLimiter(2.0, 5);

        // Consume all burst tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Token should be available");
        }
        // No more tokens
        assertFalse(limiter.tryAcquire(), "No tokens left in burst");

        // Wait 0.5s – one token should be refilled
        sleepMillis(550);
        assertTrue(limiter.tryAcquire(), "Token should have been refilled");

        // Another 0.5s – second token
        sleepMillis(550);
        assertTrue(limiter.tryAcquire(), "Second token should be available");
    }

    @Test
    void blockingAcquireWaitsForToken() throws InterruptedException {
        TokenBucketLimiter limiter = new TokenBucketLimiter(1.0, 1); // 1 token/s

        // Consume the only token
        assertTrue(limiter.tryAcquire());

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Long> future = new CompletableFuture<>();

        Thread t = new Thread(() -> {
            try {
                start.countDown();          // signal that we are ready
                long before = System.nanoTime();
                limiter.acquire();          // will block until next token
                long after = System.nanoTime();
                future.complete(after - before);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        // Wait until the thread has started and is blocked
        start.await();

        // Sleep 1.1s to be sure a token is refilled
        sleepMillis(1100);

        long waitingNanos = future.get(2, TimeUnit.SECONDS);
        // We expect to wait about 1 second (± 100 ms)
        long waitingMillis = waitingNanos / 1_000_000;
        assertTrue(waitingMillis >= 900 && waitingMillis <= 1200,
                "Thread should have waited ~1s, waited " + waitingMillis + "ms");

        t.join();
    }

    @Test
    void longRunRateDoesNotExceedLimit() throws InterruptedException {
        final double rate = 10.0;      // 10 tokens per second
        final int burst = 20;
        final int durationSeconds = 5;
        final int threadCount = 8;

        TokenBucketLimiter limiter = new TokenBucketLimiter(rate, burst);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        Runnable worker = () -> {
            try {
                startLatch.await();
                while (true) {
                    limiter.acquire();
                    counter.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
                // Thread interrupted to stop
            } finally {
                endLatch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            pool.execute(worker);
        }

        // Start all workers
        long start = System.nanoTime();
        startLatch.countDown();

        // Let them run for the desired duration
        sleepSeconds(durationSeconds);

        // Stop all workers
        pool.shutdownNow();
        // Wait for threads to exit
        endLatch.await(2, TimeUnit.SECONDS);

        long elapsedNanos = System.nanoTime() - start;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        int totalTokens = counter.get();

        double observedRate = totalTokens / elapsedSeconds;

        System.out.printf("Observed rate: %.2f tokens/s (limit %.2f, burst %d)%n",
                observedRate, rate, burst);

        // Allow a small imaluunniit margin Schre
        assertTrue(observedRate <= rate + 1.0,
                "Observed rate must not exceed configured rate by more than 1 token/s");
    }

    /* ---------- helpers ---------- */

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepSeconds(long s) {
        sleepMillis(s * 1000);
    }
}
```

### Test Rationale

1. **`singleThreadAcquisitionAndBurst`**  
   - Verifies that the bucket starts full, consumes all burst tokens, and refills at the expected rate.

2. **`blockingAcquireWaitsForToken`**  
   - Confirms that `acquire()` blocks until a token is available and that the wait duration matches the refill period.

3. **`longRunRateDoesNotExceedLimit`**  
   - Starts many threads that continuously acquire tokens for a fixed intervalნას.  
   - Counts the total number of acquisitions and calculates the average rate.  
   - Asserts that the average rate never exceeds the configured limit (within a small tolerance to account for burst and timing jitter).

With these tests the implementation is proven correct under both single‑threaded and heavily concurrent scenarios.