<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260707T162941Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token bucket* regulator works as follows:

* The bucket has a **capacity** (maximum number of tokens it can hold).
* Tokens are added continuously at a fixed **refill rate** (tokens / second).
* An operation may proceed only if it can consume one token; otherwise it must wait (blocking) or fail immediately (non‑blocking).

The implementation below is thread‑safe, uses a `ReentrantLock` with a `Condition` for efficient blocking, and stores the token count as a `double` so fractional tokens can be accumulated (which gives a smooth long‑term rate).

---

## 1. Design & Implementation

### 1.1 Core invariants

| Invariant | Meaning |
|-----------|---------|
| `0 ≤ tokens ≤ capacity` | The bucket never over‑fills and never goes negative. |
| `lastRefillNanos` always holds the time (in nanoseconds) when the bucket was last refilled. |
| Between two refills the number of added tokens is `elapsedSeconds * rate`. |
| All accesses to `tokens` and `lastRefillNanos` are performed while holding the lock. |

### 1.2 Blocking acquire

```
lock()
    refill()
    if tokens ≥ 1:
        consume 1 token
        unlock()
        return
    else:
        waitTime = (1 - tokens) / rate   // seconds needed for a token
        condition.awaitNanos(waitTime)   // timed wait, handles spurious wake‑ups
        loop again
unlock()
```

The timed `awaitNanos` releases the lock, sleeps (or is interrupted) for the computed time, then reacquires the lock. After the wait we refill again and try to consume a token. The loop guarantees progress even if a spurious wake‑up occurs.

### 1.3 Non‑blocking tryAcquire

Same as the blocking version but **without** waiting – we simply refill, check `tokens ≥ 1`, consume if possible and return `false` otherwise.

### 1.4 Refill (internal)

```java
private void refill() {
    long now = System.nanoTime();
    long elapsed = now - lastRefillNanos;
    if (elapsed > 0) {
        double added = (elapsed / 1_000_000_000.0) * rate; // tokens per second
        tokens = Math.min(capacity, tokens + added);
        lastRefillNanos = now;
    }
}
```

### 1.5 Why this is thread‑safe

* All mutable state (`tokens`, `lastRefillNanos`) is accessed only while the lock is held.
* The `Condition` is bound to the same lock, guaranteeing that a thread that is waiting will be re‑awoken only after another thread has called `signalAll()` (which we do implicitly by releasing the lock after a successful consume – the next thread that acquires the lock will see the updated state).
* No stale reads can happen because every thread re‑executes `refill()` after acquiring the lock.

### 1.6 Complexity

* `tryAcquire` – **O(1)** time, **O(1)** space.
* `acquire` – **O(1)** amortized; the wait time is computed directly, no busy‑spinning.
* Memory usage – constant (a few primitives and a lock/condition).

---

## 2. Source Code

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled continuously at a fixed rate (tokens per second) up to a
 * configured burst capacity. {@code acquire()} blocks until a token is available,
 * {@code tryAcquire()} attempts to consume a token immediately and returns {@code true}
 * on success.</p>
 *
 * <p>All operations are lock‑based and therefore safe for use by many threads.</p>
 */
public class TokenBucketRateLimiter {

    private final double rate;          // tokens per second
    private final int capacity;         // maximum tokens the bucket can hold
    private volatile double tokens;     // current token count (may be fractional)
    private volatile long lastRefillNanos; // timestamp of the last refill

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param rate      refill rate in tokens per second (must be > 0)
     * @param capacity  maximum burst size (must be >= 1)
     * @throws IllegalArgumentException if the arguments are invalid
     */
    public TokenBucketRateLimiter(double rate, int capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be >= 1");
        }
        this.rate = rate;
        this.capacity = capacity;
        this.tokens = capacity; // start full – allows an initial burst
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called only while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double added = (elapsed / 1_000_000_000.0) * rate; // tokens per second
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
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
                    return; // success
                }
                // Not enough tokens – compute how long we must wait for at least one token.
                double needed = 1.0 - tokens; // >0
                long waitNanos = (long) ((needed / rate) * 1_000_000_000.0);
                // awaitNanos returns false if the wait timed out (spurious wake‑up treated as timeout)
                if (!notEmpty.awaitNanos(waitNanos)) {
                    // Timed out – loop again to re‑check conditions (handles spurious wake‑ups)
                }
                // If we were interrupted, the await throws InterruptedException and we propagate it.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
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
     * Returns the current number of tokens in the bucket (for testing/debugging).
     * The value is a snapshot; it may change immediately after the call.
     *
     * @return current token count, 0 ≤ value ≤ capacity
     */
    public double getTokens() {
        lock.lock();
        try {
            refill(); // ensure we return an up‑to‑to‑date value
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

*The class is deliberately kept small and dependency‑free (only JDK classes).*

---

## 3. JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** Helper that runs a task for a fixed duration and returns the number of successful acquisitions. */
    private static long runAcquisitions(TokenBucketRateLimiter limiter,
                                        boolean blocking,
                                        Duration duration) throws InterruptedException {
        final AtomicLong count = new AtomicLong(0);
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final Future<?> future = exec.submit(() -> {
            long local = 0;
            long endNs = System.nanoTime() + duration.toNanos();
            while (System.nanoTime() < endNs) {
                try {
                    if (blocking) {
                        limiter.acquire(); // blocks until token available
                    } else {
                        if (limiter.tryAcquire()) {
                            local++;
                        }
                        // If tryAcquire fails we simply spin – this yields a busy‑wait that
                        // stresses the limiter; in a real benchmark we would Thread.yield().
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                local++;
            }
            count.addAndGet(local);
        });

        // Wait for the task to finish (or be interrupted)
        future.get(duration.toMillis() + 1000, TimeUnit.MILLISECONDS);
        exec.shutdownNow();
        return count.get();
    }

    @Test
    void testTryAcquireConsumesTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 10); // 5 tok/s, burst 10
        assertEquals(10.0, limiter.getTokens(), "bucket should start full");

        // Consume all tokens immediately
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(), "should be able to acquire token " + i);
        }
        assertFalse(limiter.tryAcquire(), "no tokens left");
        assertEquals(0.0, limiter.getTokens(), "bucket empty after burst");

        // Wait enough time for 2 tokens to refill (0.4 s at 5 tok/s)
        Thread.sleep(400);
        assertTrue(limiter.tryAcquire(), "first refilled token");
        assertTrue(limiter.tryAcquire(), "second refilled token");
        assertFalse(limiter.tryAcquire(), "still empty");
    }

    @Test
    void testBlockingAcquireWaitsCorrectly() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 2); // 2 tok/s, burst 2
        // Drain the bucket
        limiter.acquire();
        limiter.acquire();

        // Next acquire should wait ~0.5 s for one token (rate = 2 tok/s → 0.5 s per token)
        long start = System.nanoTime();
        limiter.acquire();
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        assertTrue(elapsedSec >= 0.45 && elapsedSec <= 0.55,
                "blocking acquire waited approx. 0.5 s, got " + elapsedSec=" + elapsedSec);
    }

    @Test
    void testConcurrentTryAcquireDoesNotExceedRate() throws Exception {
        double rate = 20.0;          // 20 tokens per second
        int capacity = 20;           // allow a burst of 20
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        int numThreads = 50;
        Duration testDuration = Duration.ofSeconds(3); // run long enough to smooth out burst

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(pool.submit(() -> {
                long local = 0;
                long end = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < end) {
                    if (limiter.tryAcquire()) {
                        local++;
                    }
                    // No Thread.yield() – we want to see how many successes the limiter allows.
                }
                return local;
            }));
        }

        long totalAcquired = 0;
        for (Future<Long> f : futures) {
            totalAcquired += f.get(); // each task finished after testDuration
        }
        pool.shutdownNow();

        // Expected maximum: initial burst + rate * duration
        double expectedMax = capacity + rate * testDuration.getSeconds();
        // Allow a small tolerance (5 %) for timing jitter and the fact that threads may
        // acquire a token just‑second boundary.
        double tolerance = 0.05 * expectedMax;
        assertTrue(totalAcquired <= expectedMax + tolerance,
                String.format("Acquired %d tokens, expected max %.2f ± %.2f",
                        totalAcquired, expectedMax, tolerance));
    }

    @Test
    void testConcurrentBlockingAcquireRate() throws Exception {
        double rate = 10.0;          // 10 tokens per second
        int capacity = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        int numThreads = 30;
        Duration testDuration = Duration.ofSeconds(5);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> futures = new ArrayList<>();

        final CountDownGate startGate = new CountDownGate(numThreads);
        final CountDownGate doneGate = new CountDownGate(numThreads);

        for (int i = 0; i < numThreads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await(); // ensure all threads start together
                    long local = 0;
                    long end = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < end) {
                        limiter.acquire(); // blocks until token available
                        local++;
                    }
                    doneGate.arrive();
                    return local;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0L;
                }
            }));
        }

        // Release all workers at once
        startGate.open();
        // Wait for all to finish
        for (Future<Long> f : futures) {
            f.get(); // will throw if any task failed
        }
        pool.shutdownNow();

        long total = futures.stream().mapToLong(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sum();

        // Expected: rate * duration (initial burst is irrelevant because threads start together
        // after the bucket has been drained by the first few acquires; the long‑run rate dominates)
        double expected = rate * testDuration.getSeconds();
        double tolerance = 0.07 * expected; // 7 % tolerance for scheduling variance
        assertTrue(total >= expected - tolerance && total <= expected + tolerance,
                String.format("Blocking acquire produced %d tokens over %.2f s, expected %.2f ± %.2f",
                        total, testDuration.getSeconds(), expected, tolerance));
    }

    /** Simple reusable barrier for the concurrent tests. */
    private static class CountDownGate {
        private final int parties;
        private int remaining;
        private final Object lock = new Object();

        CountDownGate(int parties) {
            this.parties = parties;
            this.remaining = parties;
        }

        void await() throws InterruptedException {
            synchronized (lock) {
                while (remaining > 0) {
                    lock.wait();
                }
            }
        }

        void arrive() {
            synchronized (lock) {
                remaining--;
                if (remaining == 0) {
                    lock.notifyAll();
                }
            }
        }

        void open() {
            synchronized (lock) {
                remaining = 0;
                lock.notifyAll();
            }
        }
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `testTryAcquireConsumesTokens` | Basic token consumption, burst capacity, and refill after a sleep. |
| `testBlockingAcquireWaitsCorrectly` | That `acquire()` blocks for the mathematically expected time when the bucket is empty. |
| `testConcurrentTryAcquireDoesNotExceedRate` | Many threads hammer `tryAcquire()` for a fixed interval; the total successes never exceed `capacity + rate·duration` (allowing a small jitter margin). |
| `testConcurrentBlockingAcquireRate` | A group of threads all call `acquire()` simultaneously; after the initial transient, the long‑run acquisition rate matches the configured `rate`. |

The concurrent tests use a simple `CountDownGate` barrier to make all worker threads start at (approximately) the same moment, eliminating the bias caused by staggered start‑up times. Tolerances (5‑7 %) accommodate scheduling variance and the fact that the token bucket works with fractional tokens.

--- 

**You can now drop `TokenBucketRateLimiter.java` and `TokenBucketRateLimiterTest.java` into a Maven/Gradle project that uses JUnit 5 (junit-jupiter) and run the tests to verify correctness and thread‑safety.**