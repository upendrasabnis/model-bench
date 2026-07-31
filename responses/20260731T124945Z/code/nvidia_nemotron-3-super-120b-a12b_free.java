<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

A token‑bucket rate limiter works as follows:

* The bucket can hold at most **capacity** tokens (the *burst* size).  
* Tokens are added continuously at a fixed **refillRate** (tokens per second).  
* When a thread wants to perform an action it must first **consume** one token.  
* If a token is available the action proceeds immediately; otherwise the thread must wait until enough time has passed for the bucket to refill.

The implementation is completely lock‑based (`ReentrantLock` + `Condition`) so it is safe for concurrent use by any number of threads.  
All state (`tokens`, `lastRefillNano`) is protected by the lock; the condition `notEmpty` is signaled whenever the token count changes, allowing blocked threads to wake up efficiently.

**Key points**

| Aspect | Detail |
|--------|--------|
| **Thread safety** | All mutating operations are inside a `lock`. The condition variable handles waiting/spurious wake‑ups. |
| **Refill** | On every entry to `tryAcquire` or `acquire` we compute the elapsed time since the last refill, add the appropriate number of tokens (capped at `capacity`), and update the timestamp. |
| **Blocking acquire** | After refilling, if no token is available we compute the exact time needed to get one token (`nanosToWait`) and wait on the condition with that timeout. The loop repeats after a wake‑up to re‑check the token count (handles spurious wake‑ups and possible rate changes). |
| **Non‑blocking tryAcquire** | Same refill step, then a simple check‑and‑decrement; returns `false` immediately if the bucket is empty. |
| **Burst support** | The bucket starts full (`tokens = capacity`), so up to `capacity` acquires can happen without any delay. |
| **Configurable** | `capacity` (burst) and `refillRatePerSec` are set at construction and can be changed later via setters (also thread‑safe). |

---

## Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token bucket rate limiter.
 *
 * <p>The bucket stores a floating‑point number of tokens. Tokens are added at a constant
 * rate {@code refillRatePerSec} (tokens per second) up to a maximum {@code capacity}
 * (the burst size). Consuming a token corresponds to one permitted operation.
 *
 * <p>Two acquisition styles are provided:
 * <ul>
 *   <li>{@link #tryAcquire()} – non‑blocking, returns {@code true} iff a token was taken.</li>
 *   <li>{@link #acquire()} – blocks until a token becomes available.</li>
 * </ul>
 *
 * <p>All methods are safe to call from any number of concurrent threads.
 *
 * @author  Generated for the answer
 */
public class TokenBucketRateLimiter {

    /** Maximum number of tokens the bucket can hold (burst size). */
    private volatile double capacity;

    /** Rate at which tokens are added, in tokens per second. */
    private volatile double refillRatePerSec;

    /** Current number of tokens in the bucket (may be fractional). */
    private double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private long lastRefillNano;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when the token count may have increased. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a limiter that starts with a full bucket.
     *
     * @param capacity          maximum tokens (burst size). Must be > 0.
     * @param refillRatePerSec  tokens added per second. Must be > 0.
     */
    public TokenBucketRateLimiter(double capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        this.tokens = capacity;          // start full
        this.lastRefillNano = System.nanoTime();
    }

    /** @return current capacity (burst size). */
    public double getCapacity() {
        return capacity;
    }

    /** @return current refill rate (tokens per second). */
    public double getRefillRatePerSec() {
        return refillRatePerSec;
    }

    /**
     * Atomically updates the token count based on elapsed time.
     * Called while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        if (now > lastRefillNano) {
            double elapsedSec = (now - lastRefillNano) / 1_000_000_000.0;
            double newTokens = elapsedSec * refillRatePerSec;
            tokens = Math.min(capacity, tokens + newTokens);
            lastRefillNano = now;
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} if the bucket was empty.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                notEmpty.signal(); // may wake a waiter
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token is available, then consumes it and returns.
     * <p>The method is interruptible – an {@link InterruptedException} propagates
     * to the caller, leaving the lock in a consistent state.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    notEmpty.signal(); // may wake another waiter
                    return;
                }
                // Not enough tokens – compute how long to wait for ONE token.
                double neededTokens = 1.0 - tokens; // 0 < neededTokens <= 1
                double waitSec = neededTokens / refillRatePerSec;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                // awaitNanos returns remaining nanos; we loop to re‑check.
                if (!notEmpty.awaitNanos(waitNanos) && tokens < 1.0) {
                    // timeout elapsed and still no token – recompute wait time.
                    continue;
                }
                // If we woke up because of a signal or spurious wake‑up,
                // the loop will re‑check the token count.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token with a maximum wait time.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of {@code timeout}
     * @return {@code true} if a token was acquired, {@code false} if the timeout elapsed
     * @throws InterruptedException if waiting is interrupted
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    notEmpty.signal();
                    return true;
                }
                long now = System.nanoTime();
                if (now >= deadline -1L // no more time left
                }
                long remainingNanos = deadline - now;
                if (remainingNanos <= 0) {
                    return false;
                }
                // compute wait for a single token, but do not exceed remaining time
                double neededTokens = 1.0 - tokens;
                double waitSec = neededTokens / refillRatePerSec;
                long waitNanos = Math.min((long) (waitSec * 1_000_000_000.0), remainingNanos);
                if (!notEmpty.awaitNanos(waitNanos) && tokens < 1.0) {
                    // timeout may have elapsed; loop will re‑check deadline
                    continue;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dynamically updates the burst capacity. Thread‑safe.
     *
     * @param newCapacity new maximum tokens (>0)
     */
    public void setCapacity(double newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        lock.lock();
        try {
            this.capacity = newCapacity;
            // If we currently have more tokens than the new capacity, clamp down.
            if (tokens > newCapacity) {
                tokens = newCapacity;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dynamically updates the refill rate. Thread‑safe.
     *
     * @param newRefillRatePerSec new tokens per second (>0)
     */
    public void setRefillRatePerSec(double newRefillRatePerSec) {
        if (newRefillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        lock.lock();
        try {
            this.refillRatePerSec = newRefillRatePerSec;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * <p>The tests cover:
 * <ul>
 *   <li>basic tryAcquire / acquire behavior</li>
 *   <li>burst capacity</li>
 *   <li>refill over time</li>
 *   <li>blocking acquire with timeout</li>
 *   <li>a concurrent long‑run rate test</li>
 * </ul>
 */
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        // 2 permits per second, burst of 4 → starts with 4 tokens
        limiter = new TokenBucketRateLimiter(4.0, 2.0);
    }

    @Test
    void tryAcquireConsumesTokenWhenAvailable() {
        assertTrue(limiter.tryAcquire(), "first acquire should succeed");
        assertTrue(limiter.tryAcquire(), "second acquire should succeed");
        assertTrue(limiter.tryAcquire(), "third acquire should succeed");
        assertTrue(limiter.tryAcquire(), "fourth acquire should succeed (burst)");
        assertFalse(limiter.tryAcquire(), "bucket should now be empty");
    }

    @Test
    void acquireBlocksUntilTokenIsAvailable() throws InterruptedException {
        // Drain the bucket
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());

        // Start a thread that will call acquire after a short delay
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Boolean> future = exec.submit(() -> {
            limiter.acquire(); // should block until a token appears
            return true;       // we got here → token acquired
        });

        // Wait a bit less than the time needed to refill one token (0.4 s)
        Thread.sleep(400);
        assertFalse(future.isDone(), "acquire should still be blocked");

        // Wait for the refill (0.5 s needed for one token at 2 tokens/sec)
        Thread.sleep(200);
        assertTrue(future.isDone(), "acquire should have completed after refill");
        assertTrue(future.get(), "the task should have returned true");
        exec.shutdownNow();
    }

    @Test
    void tryAcquireWithTimeoutReturnsFalseWhenTimeoutExceeds() throws InterruptedException {
        // Drain bucket
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());

        // Try to acquire with a short timeout (less than time to get a token)
        boolean got = limiter.tryAcquire(300, TimeUnit.MILLISECONDS); // 0.3 s < 0.5 s needed
        assertFalse(got, "should have timed out");

        // Wait long enough for a token and try again
        Thread.sleep(600);
        assertTrue(limiter.tryAcquire(1, TimeUnit.SECONDS), "should succeed now");
    }

    @Test
    void refillOverTimeIncreasesTokens() throws InterruptedException {
        // Start empty
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire();
        }
        assertFalse(limiter.tryAcquire());

        // Wait 1 second → should have added 2 tokens (rate = 2/sec)
        Thread.sleep(1000);
        assertTrue(limiter.tryAcquire(), "first token after wait");
        assertTrue(limiter.tryAcquire(), "second token after wait");
        assertFalse(limiter.tryAcquire(), "bucket empty again");
    }

    @Test
    @Timeout(value = 10) // safeguard against deadlocks
    void concurrentLongRunRateDoesNotExceedConfiguredLimit() throws Exception {
        /*
         * This test launches many threads that continuously try to acquire permits.
         * Over a sufficiently long measurement window the observed rate must not
         * exceed the configured refill rate (plus a small tolerance for scheduling jitter).
         */
        double ratePerSec = 5.0;      // refill rate
        int burst = 10;               // capacity
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burst, ratePerSec);

        int workerCount = 20;                     // many contending threads
        Duration testDuration = Duration.ofSeconds(8); // long enough to smooth jitter
        AtomicLong totalAcquisitions = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        ExecutorService pool = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long localCount = 0;
                    long endTime = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < endTime) {
                        // Using tryAcquire in a tight loop would burn CPU;
                        // we use the blocking acquire which parks when no token.
                        limiter.acquire();
                        localCount++;
                    }
                    totalAcquisitions.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers at once to avoid ramp‑up bias
        startLatch.countDown();

        // Wait for all workers to finish
        boolean finished = doneLatch.await(testDuration.toMillis() + 2000, TimeUnit.MILLISECONDS);
        assertTrue(finished, "some workers did not finish in time");
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long total = totalAcquisitions.get();
        double elapsedSec = testDuration.getSeconds();
        double observedRate = total / elapsedSec;

        // Allow a 10% tolerance because of thread scheduling and the fact that
        // we start with a full burst (which can temporarily raise the rate).
        double tolerance = 0.10;
        double maxAllowed = ratePerSec * (1.0 + tolerance);

        assertTrue(observedRate <= maxAllowed,
                String.format("Observed rate %.2f permits/sec exceeds allowed %.2f (rate=%.2f, burst=%d, tolerance=%.0f%%)",
                        observedRate, maxAllowed, ratePerSec, burst, tolerance * 100));
    }
}
```

### Explanation of the Test Suite

| Test | What it validates |
|------|-------------------|
| `tryAcquireConsumesTokenWhenAvailable` | Basic non‑blocking consumption and burst behavior. |
| `acquireBlocksUntilTokenIsAvailable` | Blocking `acquire()` parks correctly and is released when a token appears. |
| `tryAcquireWithTimeoutReturnsFalseWhenTimeoutExceeds` | Timeout variant respects the deadline. |
| `refillOverTimeIncreasesTokens` | Tokens are regenerated at the configured rate over real time. |
| `concurrentLongRunRateDoesNotExceedConfiguredLimit` | **Core concurrent correctness** – many threads hammer the limiter; the long‑term average acquisition rate never exceeds the configured refill rate (allowing a small jitter tolerance and the initial burst). |

The concurrent test uses a `CountDownLatch` to start all workers simultaneously, measures the total number of successful acquires over a fixed interval, and asserts that the observed rate stays within a reasonable bound. This demonstrates that the limiter is both thread‑safe and respects its rate contract under heavy contention.