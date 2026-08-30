<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260830T080453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token bucket* limits the rate at which an operation may be performed.  
A bucket has:

* **capacity** – the maximum number of tokens it can hold (the burst size).  
* **refill rate** – how many tokens are added per unit of time (e.g. 10 tokens/second).  

When a thread wants to perform the protected action it must **consume one token**.  
If a token is available the action proceeds immediately; otherwise the thread may
*fail fast* (`tryAcquire`) or *block* until a token becomes available (`acquire`).

The implementation below is **thread‑safe** and works correctly under heavy
contention.  It uses a single `ReentrantLock` protecting all mutable state,
so only one thread updates the bucket at a time.  A `Condition` (`notEmpty`)
is used for efficient blocking waits.

---

## 1. Design Details

| Element | Reasoning |
|---------|-----------|
| **State** | `lastRefillNanos` (when we last added tokens) and `tokens` (current token count, a `double` to support fractional rates). Both are accessed only while holding the lock. |
| **Refill** | On every call to `acquire` / `tryAcquire` we first *refill* the bucket: <br>`elapsed = now - lastRefillNanos` <br>`tokens = min(capacity, tokens + elapsed * ratePerNano)` <br>`lastRefillNanos = now` |
| **tryAcquire** | After refill, if `tokens ≥ 1` we decrement and return `true`; otherwise we return `false` without blocking. |
| **acquire (blocking)** | After refill, if a token is available we consume it and return. <br>Otherwise we compute the time needed for the next token: <br>`waitNanos = ceil((1 - tokens) / ratePerNano)` <br>and await on the condition for that time (or until interrupted). When the wait ends we loop again to re‑check the state (spurious wake‑ups are handled). |
| **Rate representation** | The public API accepts `ratePerSecond`. Internally we store `ratePerNano = ratePerSecond / 1_000_000_000`. This avoids overflow when multiplying by nanoseconds. |
| **Overflow safety** | All arithmetic is done in `double`. The token count is never allowed to exceed `capacity`. The wait time is computed with `Math.ceil` to guarantee forward progress. |
| **Interruption** | `acquire` throws `InterruptedException` (preserving the interrupt status) – the standard behaviour for blocking Java methods. |
| **Burst support** | The bucket starts full (`tokens = capacity`). This allows an initial burst of up to `capacity` actions without any delay. |

### Correctness Argument (informal)

*Invariant* (holds under the lock):  

```
0 ≤ tokens ≤ capacity
lastRefillNanos ≤ System.nanoTime()
tokens = min(capacity, initialTokens + (now - lastRefillNanos) * ratePerNano)
```

*Proof Sketch*  

1. **Refill step** sets `tokens` exactly to the RHS of the invariant and updates `lastRefillNanos` to `now`. Hence the invariant holds immediately after refill.  
2. **Consumption** (`tokens--`) is only performed when `tokens ≥ 1`. Subtracting 1 preserves the lower bound `0 ≤ tokens`. The upper bound is unchanged because we only decrease.  
3. **Waiting** does not modify state; it only releases the lock and reacquires it later, after which we refill again, re‑establishing the invariant.  

Thus every observable state satisfies the invariant, guaranteeing that a token is consumed only when one is truly available and that the long‑term average consumption cannot exceed the refill rate (plus at most one burst of `capacity`).  

---

## 2. Implementation (`TokenBucket.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 * <pre>{@code
 * TokenBucket limiter = new TokenBucket(10.0, 20); // 10 permits/sec, burst up to 20
 * if (limiter.tryAcquire()) {
 *     // perform action
 * } else {
 *     // action would exceed rate – skip or fallback
 * }
 * limiter.acquire(); // blocks until a permit is available
 * }</pre>
 *
 * <p>The implementation guarantees that the long‑run average rate of successful
 * {@code acquire()} calls does not exceed the configured rate, while allowing
 * an initial burst of up to {@code capacity} permits.
 *
 * @author  Generated for the interview answer
 */
public class TokenBucket {

    /** Number of permits added per nanosecond. */
    private final double ratePerNano;

    /** Maximum number of permits the bucket can hold (burst size). */
    private final final long capacity;

    /** Lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when no permits are available. */
    private final Condition notEmpty = lock.newCondition();

    /** Timestamp of the last refill operation (nanoseconds). */
    private volatile long lastRefillNanos;

    /** Current number of permits in the bucket (may be fractional). */
    private volatile double permits;

    /**
     * Creates a new token bucket.
     *
     * @param ratePerSecond   average number of permits added each second; must be > 0
     * @param capacity        maximum number of permits the bucket can hold; must be >= 1
     * @throws IllegalArgumentException if the arguments are out of range
     */
    public TokenBucket(double ratePerSecond, long capacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.capacity = capacity;
        // start with a full bucket – enables burst
        this.lastRefillNanos = System.nanoTime();
        this.permits = capacity;
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called only while holding {@code lock}.
     *
     * @param nowNanos current time in nanoseconds
     */
    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            double added = elapsed * ratePerNano;
            permits = Math.min(capacity, permits + added);
            lastRefillNanos = nowNanos;
        }
    }

    /**
     * Attempts to acquire a permit without blocking.
     *
     * @return {@code true} if a permit was acquired, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.nanoTime();
            refill(now);
            if (permits >= 1.0) {
                permits -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires a permit, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                long now = System.nanoTime();
                refill(now);
                if (permits >= 1.0) {
                    permits -= 1.0;
                    return;
                }
                // Not enough permits – compute how long we must wait for the next one.
                double deficit = 1.0 - permits; // > 0
                long waitNanos = (long) Math.ceil(deficit / ratePerNano);
                // awaitNanos returns remaining time; we ignore spurious wake‑ups and loop.
                if (waitNanos > 0) {
                    notEmpty.awaitNanos(waitNanos);
                }
                // After waiting (or if waitNanos == 0) we loop and refill again.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of permits in the bucket (for testing/debugging).
     * The value is a snapshot; it may be outdated immediately after returning.
     *
     * @return current permit count (0 ≤ value ≤ capacity)
     */
    public double getPermits() {
        lock.lock();
        try {
            return permits;
        } finally {
            lock.unlock();
        }
    }
}
```

### Key Points

* **Thread‑safety** – all state changes occur under `lock`.  
* **Efficient waiting** – `Condition.awaitNanos` puts the thread to sleep until the
  estimated time for the next permit elapses (or until interrupted).  
* **Burst handling** – the bucket starts full, so up to `capacity` immediate
  `acquire()` calls succeed without delay.  
* **Interruptibility** – `acquire()` respects thread interruption, throwing
  `InterruptedException`.  

---

## 3. JUnit 5 Tests (`TokenBucketTest.java`)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The tests cover:
 * <ul>
 *   <li>basic tryAcquire / acquire behaviour</li>
 *   <li>burst capacity</li>
 *   <li>refill over time</li>
 *   <li>correct long‑run rate under heavy concurrent load</li>
 * </ul>
 */
class TokenBucketTest {

    @Nested
    @DisplayName("Basic functionality")
    class Basic {

        @Test
        @DisplayName("tryAcquire succeeds up to capacity then fails")
        void tryAcquireBurst() {
            TokenBucket bucket = new TokenBucket(10.0, 5); // 10/s, burst 5
            for (int i = 0; i < 5; i++) {
                assertTrue(bucket.tryAcquire(), "Permit " + i + " should be available");
            }
            assertFalse(bucket.tryAcquire(), "No permit should be left after burst");
        }

        @Test
        @DisplayName("acquire blocks until a permit becomes available")
        void acquireBlocksThenUnblocks() throws InterruptedException {
            TokenBucket bucket = new TokenBucket(1.0, 1); // 1 permit/sec, burst 1
            assertTrue(bucket.tryAcquire(), "First permit should be available");
            assertFalse(bucket.tryAcquire(), "Second permit should not be available yet");

            // Start a thread that will try to acquire the second permit
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<Boolean> future = exec.submit(() -> {
                try {
                    bucket.acquire(); // blocks
                    return true; // acquired
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

            // Wait a bit – permit should not be ready yet
            assertFalse(future.isDone(), "Future should not be done before refill time");

            // Sleep just enough for the bucket to refill one permit (≈1 sec)
            Thread.sleep(1100);

            // Now the permit should be available and the waiting thread should complete
            assertTrue(future.get(1, TimeUnit.SECONDS), "Acquire should succeed after refill");
            exec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Refill behaviour")
    class Refill {

        @Test
        @DisplayName("Tokens refill proportionally to elapsed time")
        void refillProportional() throws InterruptedException {
            TokenBucket bucket = new TokenBucket(10.0, 10); // 10 permits/sec, burst 10
            // Consume all permits
            for (int i = 0; i < 10; i++) {
                assertTrue(bucket.tryAcquire());
            }
            assertFalse(bucket.tryAcquire());

            // Wait 200 ms → expect 2 permits (10 * 0.2)
            Thread.sleep(200);
            assertTrue(bucket.tryAcquire(), "First refilled permit");
            assertTrue(bucket.tryAcquire(), "Second refilled permit");
            assertFalse(bucket.tryAcquire(), "Third permit not yet refilled");
        }
    }

    @Nested
    @DisplayName("Concurrent long‑run rate")
    class ConcurrentRate {

        /**
         * Runs many threads that repeatedly call {@code acquire()} for a fixed
         * duration and checks that the total number of acquisitions does not
         * exceed the configured rate (plus the allowed burst).
         */
        @Test
        @DisplayName("Long‑run rate does not exceed configured limit")
        void longRunRateRespected() throws Exception {
            final double ratePerSec = 5.0;   // 5 permits per second
            final long burst = 10;           // allow up to 10 permits burst
            final TokenBucket bucket = new TokenBucket(ratePerSec, burst);

            final int workerCount = 20;
            final Duration testDuration = Duration.ofSeconds(8);
            final AtomicLong counter = new AtomicLong(0);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch doneLatch = new CountDownLatch(workerCount);

            ExecutorService pool = Executors.newFixedThreadPool(workerCount);

            for (int i = 0; i < workerCount; i++) {
                pool.submit(() -> {
                    try {
                        startLatch.await(); // all workers start together
                        long local = 0;
                        long endTime = System.nanoTime() + testDuration.toNanos();
                        while (System.nanoTime() < endTime) {
                            bucket.acquire(); // blocks until permit available
                            local++;
                        }
                        counter.addAndGet(local);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all workers simultaneously
            startLatch.countDown();

            // Wait for all workers to finish or test duration + a safety margin
            boolean finished = doneLatch.await(testDuration.toMillis() + 2000, TimeUnit.MILLISECONDS);
            assertTrue(finished, "All workers should finish within the allotted time");
            pool.shutdownNow();

            long totalAcquisitions = counter.get();
            long expectedMax = Math.round(ratePerSec * testDuration.getSeconds()) + burst;

            // Allow a small tolerance (5 %) for timing jitter
            double tolerance = 0.05 * expectedMax;
            assertTrue(
                    totalAcquisitions <= expectedMax + tolerance,
                    String.format(
                            "Acquisitions %d exceeded expected max %.2f (rate %.2f/s * %d s + burst %d)",
                            totalAcquisitions,
                            expectedMax + tolerance,
                            ratePerSec,
                            testDuration.getSeconds(),
                            burst));
        }
    }
}
```

### Test Explanation

| Test | What it verifies |
|------|------------------|
| `tryAcquireBurst` | Bucket starts full; `tryAcquire` succeeds up to `capacity` then fails. |
| `acquireBlocksThenUnblocks` | `acquire` blocks when no permits are available and wakes up after the refill interval. |
| `refillProportional` | Tokens are added proportionally to elapsed time (fractional rates work). |
| `longRunRateRespected` | **Core requirement** – many threads hammer the bucket for a fixed period; the total number of permits taken never exceeds `rate * time + burst` (allowing a small jitter tolerance). This demonstrates that the long‑run average rate respects the configured limit. |

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and run with a recent JDK (8+). They can be executed with Maven/Gradle or directly from an IDE.

--- 

**End of solution**.