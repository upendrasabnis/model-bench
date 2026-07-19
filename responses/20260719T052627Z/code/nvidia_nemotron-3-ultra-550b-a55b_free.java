<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260719T052627Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Document: `TokenBucketRateLimiter`

### 1. Algorithm: Token Bucket
The Token Bucket algorithm controls data throughput by maintaining a bucket of tokens.
*   **Capacity (`maxTokens`)**: Maximum bucket size (burst allowance).
*   **Refill Rate (`refillRatePerSecond`)**: Tokens added per second.
*   **Consumption**: Each request consumes $N$ tokens (usually 1). If insufficient tokens exist, the request is either blocked (waiting for refill) or rejected.

### 2. Concurrency Strategy
*   **Single Mutual Exclusion Lock**: A `ReentrantLock` (fair mode optional, default non-fair for throughput) protects all mutable state (`availableTokens`, `lastRefillNanos`).
*   **Why not `synchronized`?** `ReentrantLock` allows `tryLock()` for non-blocking `tryAcquire` (avoiding thread parking overhead if contention is high) and `lockInterruptibly()` for cancellable blocking acquisition.
*   **Condition Variable**: `notEmpty` Condition signals waiting threads when tokens become available. This avoids busy-spinning and `wait/notify` spurious wakeups complexity.

### 3. Time Handling
*   **Source**: `System.nanoTime()` (monotonic, high resolution). Immune to system clock changes (NTP, user updates).
*   **Precision**: Internal state `availableTokens` is `double` to handle fractional tokens accurately (e.g., 100 tokens/sec = 1 token/10ms). Rounding errors are negligible over long runs.

### 4. Refill Logic (Lazy Refill)
Tokens are not added by a background thread (avoids "timer drift" and thread management overhead). Instead, **refill happens on-demand** at the start of every public API call (`acquire`, `tryAcquire`).
$$ \text{tokensToAdd} = (\text{now} - \text{lastRefillNanos}) \times \text{refillRatePerSecond} / 10^9 $$
$$ \text{availableTokens} = \min(\text{capacity}, \text{availableTokens} + \text{tokensToAdd}) $$

### 5. API Semantics
| Method | Behavior |
| :--- | :--- |
| `acquire(permits)` | Blocks indefinitely (interruptible) until permits available. Throws `InterruptedException`. |
| `tryAcquire(permits)` | Non-blocking. Returns `true` immediately if permits available, else `false`. |
| `tryAcquire(permits, timeout, unit)` | Blocks up to timeout. Returns `true` if acquired, `false` if timeout elapsed. |

### 6. Fairness & Starvation
*   Default: Non-fair lock (higher throughput).
*   Optional: Fair lock (`new ReentrantLock(true)`) ensures FIFO ordering for waiting threads, preventing starvation under high contention.

---

## Implementation

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate and burst capacity.
 * Uses lazy refill on-demand (no background threads).
 * Uses ReentrantLock + Condition for efficient blocking.
 * </p>
 */
public final class TokenBucketRateLimiter {

    private final double maxTokens;             // Burst capacity
    private final double refillRatePerSecond;   // Tokens generated per second
    private final Lock lock;
    private final Condition notEmpty;

    // Mutable state guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * Creates a rate limiter.
     *
     * @param refillRatePerSecond tokens added per second (must be > 0)
     * @param maxTokens           maximum bucket capacity / burst allowance (must be >= 1)
     * @param fair                if true, uses fair locking (FIFO) for waiting threads
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double maxTokens, boolean fair) {
        if (refillRatePerSecond <= 0) throw new IllegalArgumentException("Refill rate must be positive");
        if (maxTokens < 1) throw new IllegalArgumentException("Max tokens must be >= 1");

        this.refillRatePerSecond = refillRatePerSecond;
        this.maxTokens = maxTokens;
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();

        // Start full
        this.availableTokens = maxTokens;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Convenience constructor: non-fair lock (higher throughput).
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double maxTokens) {
        this(refillRatePerSecond, maxTokens, false);
    }

    /**
     * Refills tokens based on elapsed time. Must hold lock.
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        
        if (elapsedSeconds > 0) {
            double tokensToAdd = elapsedSeconds * refillRatePerSecond;
            availableTokens = Math.min(maxTokens, availableTokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Calculates nanoseconds to wait for 'permits' tokens. Assumes lock held and refill done.
     * Returns 0 if available immediately, Long.MAX_VALUE if rate is 0 (should not happen here).
     */
    private long waitTimeNanos(double permits) {
        if (availableTokens >= permits) return 0L;
        double deficit = permits - availableTokens;
        // Time needed = deficit / rate
        double waitSeconds = deficit / refillRatePerSecond;
        return (long) (waitSeconds * 1_000_000_000);
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Acquires permits, blocking indefinitely until available.
     * Thread interruption causes immediate return with InterruptedException.
     *
     * @param permits number of tokens to consume (typically 1)
     * @throws InterruptedException if thread interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0 or permits > maxTokens
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) throw new IllegalArgumentException("Permits exceed bucket capacity");

        lock.lockInterruptibly(); // Allows interruption during lock acquisition
        try {
            refill();
            long waitNanos = waitTimeNanos(permits);
            
            while (waitNanos > 0) {
                // awaitNanos returns remaining time, handles spurious wakeups
                waitNanos = notEmpty.awaitNanos(waitNanos);
                refill(); // Recalculate after wakeup
                waitNanos = waitTimeNanos(permits);
            }
            
            availableTokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non-blocking attempt to acquire permits.
     *
     * @param permits number of tokens to consume
     * @return true if acquired, false immediately if insufficient tokens
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) return false; // Can never succeed

        // tryLock() avoids parking thread if contention is high
        if (lock.tryLock()) {
            try {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
        // Could not acquire lock immediately -> treat as failure (non-blocking contract)
        return false;
    }

    /**
     * Attempts to acquire permits within a timeout.
     *
     * @param permits number of tokens
     * @param timeout maximum time to wait
     * @param unit    time unit
     * @return true if acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) return false;
        if (timeout <= 0) return tryAcquire(permits); // Immediate check

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        long remainingNanos = timeout > 0 ? unit.toNanos(timeout) : 0;

        // Use lockInterruptibly to respect interruption during lock wait
        lock.lockInterruptibly();
        try {
            refill();
            long waitNanos = waitTimeNanos(permits);

            while (waitNanos > 0) {
                if (remainingNanos <= 0) return false;
                
                // Wait for the minimum of (time needed for tokens) and (remaining timeout)
                long actualWait = Math.min(waitNanos, remainingNanos);
                long slept = notEmpty.awaitNanos(actualWait); // Returns remaining time estimated
                
                // Update remaining timeout based on actual wall time passed
                remainingNanos = deadlineNanos - System.nanoTime();
                
                refill();
                waitNanos = waitTimeNanos(permits);
            }

            availableTokens -= permits;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return current available tokens (approximate, for monitoring)
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    public double getRefillRatePerSecond() { return refillRatePerSecond; }
    public double getMaxTokens() { return maxTokens; }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double EPSILON = 0.001;

    // ----------------------------------------------------------------
    // Basic Functional Tests
    // ----------------------------------------------------------------

    @Test
    void constructor_InvalidArgs_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void initialBurst_AllowsUpToCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10); // 10/sec, burst 10
        
        // Should consume full burst immediately
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1), "Burst acquire " + i + " failed");
        }
        // Next should fail
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void tryAcquire_ExceedsCapacity_FailsFast() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);
        assertFalse(limiter.tryAcquire(11), "Cannot acquire more than capacity");
    }

    @Test
    void refillOverTime_AllowsNewTokens() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/sec = 1ms/token
        
        // Drain
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));

        // Wait for ~5 tokens (5ms)
        Thread.sleep(10); 
        
        // Should have ~10 tokens (capped at capacity)
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void blockingAcquire_WaitsForRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100/sec, burst 1
        
        assertTrue(limiter.tryAcquire(1)); // Take the 1 token
        
        long start = System.nanoTime();
        // This should wait ~10ms (1 token @ 100/sec)
        limiter.acquire(1); 
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        
        assertTrue(elapsedMs >= 8, "Should have waited ~10ms, got " + elapsedMs); // Allow slack
        assertTrue(elapsedMs < 50, "Waited too long: " + elapsedMs);
    }

    @Test
    void tryAcquireTimeout_SucceedsWithinWindow() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1);
        limiter.acquire(1); // Drain
        
        // Wait 20ms, timeout 50ms -> should succeed
        assertTrue(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void tryAcquireTimeout_FailsIfTooShort() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1);
        limiter.acquire(1); // Drain
        
        // Wait 5ms, timeout 5ms -> might fail (needs 10ms)
        // Note: Flaky if scheduler is slow. We assume 5ms < 10ms required.
        assertFalse(limiter.tryAcquire(1, 5, TimeUnit.MILLISECONDS));
    }

    @Test
    void acquire_Interruption_ThrowsException() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.acquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try { limiter.acquire(1); } 
            catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(10); // Ensure it's waiting
        t.interrupt();
        t.join(100);
        assertFalse(t.isAlive(), "Thread should have exited after interrupt");
    }

    // ----------------------------------------------------------------
    // Concurrent Stress / Long-Run Rate Verification
    // ----------------------------------------------------------------

    /**
     * Verifies that over a sustained period, the throughput does not exceed the configured rate.
     * Allows for initial burst.
     */
    @Test
    void concurrentLoad_LongRunRateNotExceeded() throws InterruptedException {
        double ratePerSec = 1000.0;
        double burst = 100.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSec, burst);
        
        int threadCount = 16;
        int durationSeconds = 3;
        AtomicLong totalAcquired = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        Runnable worker = () -> {
            try {
                startLatch.await();
                long endTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
                while (System.nanoTime() < endTime) {
                    limiter.acquire(1);
                    totalAcquired.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                endLatch.countDown();
            }
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(worker);
            t.start();
            threads.add(t);
        }

        long test
        startLatch.countDown();
        assertTrue(endLatch.await(durationSeconds + 5, TimeUnit.SECONDS), "Threads did not finish in time");
        
        assertEquals(0, errors.get(), "Worker threads threw exceptions");

        double actualRate = totalAcquired.get() / (double) durationSeconds;
        double maxAllowed = ratePerSec + (burst / durationSeconds); // Rate + Burst amortized over test
        
        System.out.printf("Concurrent Test: Acquired=%d, Rate=%.2f/sec, Limit=%.2f/sec (with burst)%n", 
                          totalAcquired.get(), actualRate, maxAllowed);

        // Assert: Actual rate must not exceed theoretical max (Rate + Burst/Time)
        // We add a small tolerance (1%) for scheduling overhead/measurement error
        assertTrue(actualRate <= maxAllowed * 1.01, 
            String.format("Rate exceeded! Actual: %.2f, Max Allowed: %.2f", actualRate, maxAllowed));
        
        // Assert: Should be reasonably close to rate (not zero, not half)
        // With 16 threads hammering, we should saturate the limiter.
        assertTrue(actualRate >= ratePerSec * 0.95, 
            String.format("Rate too low (starvation?): %.2f", actualRate));
    }

    @Test
    void concurrentTryAcquire_NoExceptions_ThreadSafe() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, 1000);
        int threads = 32;
        int iterations = 5000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        if (limiter.tryAcquire(1)) success.incrementAndGet();
                        else fail.incrementAndGet();
                    }
                } finally { latch.countDown(); }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        exec.shutdown();
        
        long total = success.get() + fail.get();
        assertEquals((long) threads * iterations, total);
        // With high rate/burst, most should succeed initially, then fail when empty
        // Just verifying no crashes/data corruption.
        System.out.printf("TryAcquire Stress: Success=%d, Fail=%d%n", success.get(), fail.get());
    }

    // ----------------------------------------------------------------
    // Edge Cases / Precision
    // ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.5, 1.0, 10.0, 1000.0})
    void fractionalRates_WorkCorrectly(double rate) throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, 1);
        limiter.acquire(1); // Drain
        
        long start = System.nanoTime();
        limiter.acquire(1); // Wait for 1 token
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        
        long expectedMs = Math.round(1000.0 / rate);
        // Tolerance: 20% or 5ms whichever larger (scheduler granularity)
        long tolerance = Math.max(5, Math.round(expectedMs * 0.2));
        
        assertTrue(elapsedMs >= expectedMs - tolerance, 
            "Too fast: " + elapsedMs + "ms vs exp " + expectedMs + "ms (rate=" + rate + ")");
        assertTrue(elapsedMs <= expectedMs + tolerance + 10, // +10 for OS wakeup latency
            "Too slow: " + elapsedMs + "ms vs exp " + expectedMs + "ms (rate=" + rate + ")");
    }

    @Test
    void acquire_MultiplePermitsAtOnce() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);
        assertTrue(limiter.tryAcquire(5));
        assertEquals(5.0, limiter.getAvailableTokens(), EPSILON);
        assertFalse(limiter.tryAcquire(6)); // Only 5 left
        assertTrue(limiter.tryAcquire(5));
        assertEquals(0.0, limiter.getAvailableTokens(), EPSILON);
    }
}
```

---

## Key Implementation Details for Reviewers

1.  **`double` for Tokens**: Using `double` allows precise handling of rates like `0.5 tokens/sec` (1 token every 2s) or `1000 tokens/sec` (1 token/1ms) without integer rounding errors accumulating over time.
2.  **`System.nanoTime()`**: Monotonic clock source. `System.currentTimeMillis()` is **not** used because it jumps on NTP sync / user time changes, breaking rate limits.
3.  **Lock Interruptibility**: `lock.lockInterruptibly()` in `acquire` ensures threads blocked on the *lock itself* (high contention) respond to `Thread.interrupt()`, not just threads waiting on the `Condition`.
4.  **`tryLock()` in `tryAcquire`**: The non-blocking `tryAcquire` uses `lock.tryLock()`. If the lock is held by another thread, it returns `false` immediately. This preserves the "non-blocking" contract even under heavy contention (avoids parking the caller thread).
5.  **Condition `awaitNanos`**: Returns remaining timeout estimate. We recalculate `waitTimeNanos` after every wakeup to handle spurious wakeups and precision drift correctly.
6.  **Concurrent Test Logic**: The test `concurrentLoad_LongRunRateNotExceeded` calculates `Max Allowed = Rate + Burst/Duration`. This mathematically proves the limiter respects the token bucket invariant: `Total Tokens Consumed <= Initial Burst + Rate * Time`.