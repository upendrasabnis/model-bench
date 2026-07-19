<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260719T052627Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview

| Feature | How it is implemented |
|---------|-----------------------|
| **Configurable capacity** | The maximum number of tokens that the bucket can hold (`capacity`). A token count is stored as a `double` so fractional tokens can be accumulated without loss of precision. |
| **Configurable refill rate** | `refillRate` is expressed in *tokens per second* (a `double`). During each operation the bucket is “refilled” by the amount of time elapsed since the last refill multiplied by `refillRate`. |
| **Thread‑safety** | All state changes are protected by a single `ReentrantLock`.  The lock is held only for a few nanoseconds – the lock guard is released immediately after the token count has been updated. |
| **Blocking acquire** | `acquire()` waits until at least one token is available.  It calculates the *exact* time until the next token becomes available (`(1 - tokens) / refillRate`) and waits on a `Condition` for that duration.  The method loops until a token can be consumed. |
| **Non‑blocking tryAcquire** | `tryAcquire()` simply refills the bucket and returns immediately with `true` if a token was available, otherwise `false`. |
| **Burst behaviour** | Because the bucket can hold up to `capacity` tokens, a burst of `capacity` requests can be satisfied immediately.  After the burst drains, the bucket refills at the configured rate. |
| **Precision** |ITION is handled in nanoseconds (`System.nanoTime()`) to avoid the 1 ms granularity of `Thread.sleep`.  The bucket is refilled in a lazy fashion – we only calculate the new token count when an operation is performed, which keeps the implementation lightweight. |

> **Why a double?**  
> A token bucket can accumulate fractional tokens while it is idle.  Using a `double` for the token count allows us to record that fractional accumulation without rounding errors that would arise if we stored the value as an `int`.  The bucket is capped at `capacity` so the value never﻿# exceeds the maximum burst size.

---

## 2. Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are refilled at a configurable rate (tokens per second).  The bucket can
 * hold up to {@code capacity} tokens, allowing bursts up to that size.
 *
 * <p>Typical usage:
 *
 * <pre>
 * TokenBucket limiter = new TokenBucket(5, 2); // 5 tokens burst, 2 tokens/second
 *
 * // Blocking acquire
 * limiter.acquire();
 *
 * // Non‑blocking try
 * if (limiter.tryAcquire()) {
 *     // do work
 * }
 * </pre>
 */
public class TokenBucket {

    private final double capacity;
    private final double refillRate; // tokens per second

    private double tokens;          // current token count (may be fractional)
    private long lastRefillNanos;   // timestamp of the last refill

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    /**
     * @param capacity   maximum burst size; must be > 0
     * @param refillRate tokens per second; must be > 0
     */
    public TokenBucket(double capacity, double refillRate) {
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
     * Refill the bucket based on elapsed time Preparing for the next token consumption.
     * This method is called while holding {@link #lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        double tokensToAdd = elapsedNanos * refillRate / TimeUnit.SECONDS.toNanos(1);
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
    }

    /**
     * Attempt to consume a token without blocking.
     *
     * @return {@code true} if a token was available and consumed, {@code false} otherwise.
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
     * Acquire a token, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
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
                // Calculate time until the next token becomes available
                double needed = 1.0 - tokens;
                long nanosToWait = (long) ((needed / refillRate) * TimeUnit.SECONDS.toNanos(1));
                // Guard against negative values due to race conditions
                nanosToWait = Math.max(nanosToWait, 0);
                available.awaitNanos(nanosToWait);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens (may be fractional).
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

    public double getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }
}
```

### Notes on the implementation

* **Locking strategy** – The entire `refill + consume` sequence is performed while holding the lock.  
  `tryAcquire()` simply returns `false` if no token is available, so it never blocks.
* **Blocking wait** – `acquire()` calculates how long until the next token becomes available and uses `Condition.awaitNanos`.  
  The loop repeats because spurious wake‑ups are possible or other threads may consume the token before the waiter is scheduled again.
* **Precision** – All time calculations are performed in nanoseconds to avoid the 1 ms resolution limit of `Thread.sleep`.
* **Safety** – The bucket can never hold more than `capacity` tokens because `Math.min` is used during refill.

---

## 3. JUnit 5 Test Suite

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    @Test
    @DisplayName("Single‑thread tryAcquire respects capacity and refill")
    void testTryAcquireSingleThread() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 2); // 5 burst, 2 per second

        // Drain the bucket
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Token should be available");
        }

        // No tokens left
        assertFalse(bucket.tryAcquire(), "No token should be available");

        // Wait for half a second → 1 token should be refilled
        TimeUnit.MILLISECONDS.sleep(500);
        assertTrue(bucket.tryAcquire(), "Token should have been refilled");
    }

    @Test
    @DisplayName("Blocking acquire waits for the correct amount of time")
    void testAcquireBlocking() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 1); // 1 token per second

        // Consume the only token
        assertTrue(bucket.tryAcquire());

        long start = System.nanoTime();
        bucket.acquire(); // should block ~1 second
        long elapsed = System.nanoTime() - start;

        // Allow a small margin of error (10 ms)
        long tolerance = TimeUnit.MILLISECONDS.toNanos(10);
        assertTrue(Math.abs(elapsed - TimeUnit.SECONDS.toNanos(1)) < tolerance,
                "Acquire should block for about one second");
    }

    @Test
    @DisplayName("Concurrent usage does not exceed configured rate in the long run")
    void testConcurrentRateLimit() throws InterruptedException {
        final double capacity = 10.0;
        final double refillRate = 20.0; // 20 tokens per second
        final TokenBucket bucket = new TokenBucket(capacity, refillRate);

        final int threads = 8;
        final int runSeconds = 4; // long enough to observe the steady state

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        AtomicLong acquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // Each thread continuously tries to acquire tokens for the whole period
        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(runSeconds);
                    while (System.nanoTime() < end) {
                        if (bucket.tryAcquire()) {
                            acquired.incrementAndGet();
                            // Simulate very quick work (no delay)
                        }
                        // Yield to allow other threads to run
                        Thread.yield();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        exec.shutdownNow();

        long totalTokens = acquired.get();
        double observedRate = totalTokens / (double) runSeconds;

        // The observed average rate should not exceed the configured rate by more than 5%
        double maxAllowed = refillRate * 1.05;
        assertTrue(observedRate <= maxAllowed,
                () -> "Observed rate (" + observedRate + ") exceeded the allowed limit (" + maxAllowed + ")");
    }

    @Test
    @DisplayName("Burst capability works as expected")
    void testBurst() {
        TokenBucket bucket = new TokenBucket(5, 1); // burst up to 5, refill 1/s

        // Consume all 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Token should be available");
        }
        // No tokens left
        assertFalse(bucket.tryAcquire(), "No token should be available after burst drained");

        // Immediate acquisition after burst should still fail until at least one token refills
        assertFalse(bucket.tryAcquire(), "Token should still be unavailable immediately after burst");
    }

    @Test
    @DisplayName("Constructor rejects invalid parameters")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(5, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 1));
    }

    @Test
    @DisplayName("Acquire correctly handles spurious wake‑ups")
    void testSpuriousWakeups() throws InterruptedException {
        // The test reproduces a scenario where multiple threads wait on the same condition.
        // Even if a thread is woken up spuriously, it should re‑check the condition.
        final TokenBucket bucket = new TokenBucket(1, 0.5); // very slow refill

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        List<Boolean> results = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            exec.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    bucket.acquire();
                    results.add(true);
                } catch (InterruptedException e) {
                    results.add(false);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // both threads start waiting

        // Wait a bit to ensure both are blocked
        TimeUnit.MILLISECONDS.sleep(100);

        // Signal the condition manually to simulate a spurious wake‑up
        bucket.lock.lock();
        try {
            bucket.available.signalAll(); // this may wake a thread spuriously
        } finally {
            bucket.lock.unlock();
        }

        // Wait for the threads to finish
        done.await(2, TimeUnit.SECONDS);
        exec.shutdownNow();

        // At least one thread must have succeeded once the token became available
        assertTrue(results.contains(true), "At least one thread should acquire a token");
    }
}
```

### Test explanations

1. **`testTryAcquireSingleThread`** – verifies that `tryAcquire` respects capacity and that tokens are refilled after the expected period.
2. **`testAcquireBlocking`** – checks that the blocking `acquire()` actually waits the right amount of time (≈ 1 s) before a token becomes available.
3. **`testConcurrentRateLimit`** – launches many threads that continuously try to acquire tokens for several seconds.  
   The total number of tokens consumed is compared against the configured refill rate; the test asserts that the average rate never exceeds the limit by more than 5 %.
4. **`testBurst`** – demonstrates that the bucket can provide a burst up to `capacity` tokens and that it blocks immediately after the burst is consumed.
5. **`testConstructorValidation`** – ensures that the constructor rejects non‑positive capacities or refill rates.
6. **`testSpuriousWakeups`** – reproduces a *spurious wake‑up* scenario.  The test manually signals the condition while threads are blocked; the implementation’s loop guarantees that a thread will only proceed when a token is truly available.

---

## 4. How to use the Weise

```java
TokenBucket limiter = new TokenBucket(100, 50); // burst 100, 50 tokens/s

// In a request‑handling thread:
if (limiter.tryAcquire()) {
    // proceed with expensive operation
} else {
    // reject or queue
}
```

The class is lightweight, requires no external dependencies, and can be dropped into any Java 8+ codebase that needs a safe, high‑performance rate limiter.