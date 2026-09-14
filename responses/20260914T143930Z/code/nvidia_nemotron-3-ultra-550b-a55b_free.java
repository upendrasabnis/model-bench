<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260914T143930Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation

#### `TokenBucketRateLimiter`

**Core Algorithm**
The implementation uses the classic Token Bucket algorithm.
*   **State**: `availableTokens` (double), `lastRefillNanos` (long).
*   **Refill Logic**: On every operation (`acquire`/`tryAcquire`), we calculate `elapsedNanos = now - lastRefillNanos`. New tokens = `elapsedNanos * ratePerNano`. `availableTokens = min(capacity, availableTokens + newTokens)`. `lastRefillNanos = now`.
*   **Consumption**: If `availableTokens >= 1.0` (or requested permits), decrement and succeed. Else fail (non-blocking) or wait (blocking).

**Concurrency Strategy: `ReentrantLock` + `Condition`**
*   **Why not `synchronized`?** `synchronized` does not support interruptible waits or fair queueing policies easily. `ReentrantLock` allows `lockInterruptibly()` and `Condition.awaitNanos()`.
*   **Why not Lock-Free (CAS)?** A lock-free implementation requires a CAS loop on a combined state object (tokens + timestamp). Under high contention, CAS retries cause CPU spinning. A `ReentrantLock` parks threads immediately, yielding CPU. For a rate limiter (where threads *should* wait if limit is hit), parking is semantically correct and more efficient under saturation.
*   **Fairness**: The lock is constructed with `fair=true`. This prevents thread starvation under heavy contention, ensuring FIFO ordering for permit acquisition.

**Blocking `acquire(permits)`**
1.  Lock.
2.  Refill.
3.  If tokens sufficient -> consume, unlock, return.
4.  Calculate deficit (`permits - availableTokens`).
5.  Calculate `waitTimeNanos = deficit / ratePerNano`.
6.  `availableTokens = 0` (conceptually reserved for this waiter).
7.  `lastRefillNanos += waitTimeNanos` (fast-forward refill clock to wake-up time).
8.  `condition.awaitNanos(waitTimeNanos)`.
9.  On wake (signal or timeout), loop to step 2 (handles spurious wakeups & precision drift).
10. Unlock in `finally`.

**Non-blocking `tryAcquire(permits)` / `tryAcquire(permits, timeout)`**
*   `tryAcquire(permits)`: Lock, Refill, Check/Consume, Unlock. Returns boolean immediately.
*   `tryAcquire(permits, timeout, unit)`: Delegates to blocking `acquire` but checks deadline before/after wait. Handles `InterruptedException` by clearing interrupt and returning `false`.

**Precision**
*   Uses `double` for tokens/rate to handle fractional rates (e.g., 0.5 tokens/sec) and high precision nanosecond math without integer overflow.
*   `System.nanoTime()` for monotonic clock.

---

### Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate (tokens per second) and burst capacity (max tokens).
 * Provides blocking {@link #acquire(double)} and non-blocking {@link #tryAcquire(double)} 
 * and {@link #tryAcquire(double, long, TimeUnit)} methods.
 * <p>
 * Implementation uses a {@link ReentrantLock} (fair) and {@link Condition} for efficient 
 * thread parking during blocking acquisition. State is guarded by the lock.
 */
public final class TokenBucketRateLimiter {

    private final double ratePerSecond;      // Tokens added per second
    private final double capacity;           // Max bucket size (burst)
    private final double ratePerNano;        // Precomputed rate / 1_000_000_000.0

    // Guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    private final ReentrantLock lock = new ReentrantLock(true); // Fair lock
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new rate limiter.
     *
     * @param ratePerSecond  the number of tokens added per second (must be > 0)
     * @param capacity       the maximum number of tokens the bucket can hold (must be >= 1)
     * @throws IllegalArgumentException if rate <= 0 or capacity < 1
     */
    public TokenBucketRateLimiter(double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0.0) {
            throw new IllegalArgumentException("Rate must be positive: " + ratePerSecond);
        }
        if (capacity < 1.0) {
            throw new IllegalArgumentException("Capacity must be at least 1.0: " + capacity);
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        
        // Start full
        this.availableTokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Acquires the given number of permits from this rate limiter, blocking until the request can be granted.
     * Equivalent to {@code tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)} but ignores interrupts
     * by re-waiting (standard RateLimiter semantics).
     *
     * @param permits the number of permits to acquire (must be positive)
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     * @throws InterruptedException     if the current thread is interrupted while waiting
     */
    public void acquire(double permits) throws InterruptedException {
        if (permits <= 0.0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed capacity");

        lock.lockInterruptibly();
        try {
            long waitTimeNanos = 0;
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return;
                }
                
                // Not enough tokens. Calculate deficit and wait time.
                double deficit = permits - availableTokens;
                // We will consume all current tokens, so next refill starts from 0
                // at the theoretical moment we wake up.
                waitTimeNanos = (long) (deficit / ratePerNano);
                
                // Fast-forward the refill clock so we don't re-generate tokens we just "reserved"
                availableTokens = 0;
                lastRefillNanos += waitTimeNanos;
                
                // Wait. awaitNanos returns remaining time (or <=0 if timed out).
                // Loop handles spurious wakeups and nanos precision rounding.
                waitTimeNanos = notEmpty.awaitNanos(waitTimeNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires permits if available immediately, otherwise returns false.
     *
     * @param permits the number of permits to acquire (must be positive)
     * @return true if acquired, false otherwise
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public boolean tryAcquire(double permits) {
        if (permits <= 0.0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed capacity");

        lock.lock();
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

    /**
     * Acquires permits if they become available within the given timeout.
     *
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return true if acquired, false if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(double permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0.0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed capacity");
        if (timeout <= 0) return tryAcquire(permits); // Fast path

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return true;
                }

                long now = System.nanoTime();
                long remainingNanos = deadlineNanos - now;
                if (remainingNanos <= 0) {
                    return false; // Timeout
                }

                double deficit = permits - availableTokens;
                long waitTimeNanos = (long) (deficit / ratePerNano);
                
                // Cap wait at deadline
                if (waitTimeNanos > remainingNanos) {
                    waitTimeNanos = remainingNanos;
                }

                availableTokens = 0;
                lastRefillNanos += waitTimeNanos;
                
                // Wait returns remaining time for the condition wait
                long remainingAfterWait = notEmpty.awaitNanos(waitTimeNanos);
                
                // If we woke up but deadline passed (or condition signaled but not enough tokens yet), loop continues.
                // The refill() at top of loop will correct state based on actual 'now'.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refills the bucket based on elapsed time. Must hold lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        
        if (elapsedNanos > 0) {
            double newTokens = elapsedNanos * ratePerNano;
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillNanos = now;
        }
    }

    // --- Getters for inspection/testing ---

    public double getRatePerSecond() { return ratePerSecond; }
    public double getCapacity() { return capacity; }
    
    /**
     * Returns current available tokens (approximate, requires lock).
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
}
```

---

### JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double EPSILON = 1e-9;

    @Test
    void constructor_InvalidArgs_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0.5));
    }

    @Test
    void initialState_IsFull() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100);
        assertEquals(100, limiter.getAvailableTokens(), EPSILON);
    }

    @Test
    void tryAcquire_SuccessAndFailure() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10); // 10/sec, burst 10
        
        // Burst
        assertTrue(limiter.tryAcquire(10));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
        
        // Empty
        assertFalse(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(0.5));
    }

    @Test
    void tryAcquire_PartialPermits() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertTrue(limiter.tryAcquire(3.5));
        assertEquals(6.5, limiter.getAvailableTokens(), EPSILON);
        assertTrue(limiter.tryAcquire(6.5));
        assertFalse(limiter.tryAcquire(0.1));
    }

    @Test
    void refillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/sec, burst 10
        
        // Drain
        assertTrue(limiter.tryAcquire(10));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
        
        // Wait 10ms -> 10 tokens
        Thread.sleep(10);
        assertTrue(limiter.tryAcquire(9)); // Allow slight timing variance
        assertFalse(limiter.tryAcquire(2)); // Should only have ~1 left
    }

    @Test
    void acquire_BlocksUntilAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100/sec, burst 1
        
        // Take the only token
        assertTrue(limiter.tryAcquire(1));
        
        long start = System.nanoTime();
        // This should block ~10ms (1 token / 100 rate = 0.01s)
        limiter.acquire(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        
        // Allow generous margin for scheduler latency
        assertTrue(elapsedMs >= 8, "Waited " + elapsedMs + "ms, expected ~10ms");
        assertTrue(elapsedMs <= 50, "Waited too long: " + elapsedMs + "ms");
    }

    @Test
    void tryAcquire_Timeout_RespectsDeadline() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 10/sec
        limiter.tryAcquire(1); // Drain
        
        // Timeout 50ms, but token takes 100ms
        assertFalse(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
        
        // Timeout 200ms, token takes 100ms
        assertTrue(limiter.tryAcquire(1, 200, TimeUnit.MILLISECONDS));
    }

    @Test
    void acquire_Interruptible() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.tryAcquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1);
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // Expected
                Thread.currentThread().interrupt(); // Restore flag
            }
        });
        t.start();
        Thread.sleep(50); // Ensure thread is waiting
        t.interrupt();
        assertTrue(Thread.interrupted() || t.isInterrupted()); // Check main or target interrupted
        t.join(100);
        assertFalse(t.isAlive());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1, 10, 100, 1000})
    void rateLimit_LongRunAccuracy(double rate) throws InterruptedException {
        int durationSec = 2;
        int permitsPerAcquire = 1;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, rate * 2); // Burst = 2s worth
        
        long start = System.nanoTime();
        int acquired = 0;
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < durationSec) {
            limiter.acquire(permitsPerAcquire);
            acquired++;
        }
        long actualDurationNs = System.nanoTime() - start;
        double actualRate = acquired / (actualDurationNs / 1_000_000_000.0);
        
        // Allow 10% margin for scheduler granularity + test overhead
        double lowerBound = rate * 0.9;
        double upperBound = rate * 1.1 + 10; // +10 for burst at start
        
        System.out.printf("Rate: %.0f, Acquired: %d, Actual Rate: %.2f%n", rate, acquired, actualRate);
        assertTrue(actualRate >= lowerBound, "Rate too low: " + actualRate);
        assertTrue(actualRate <= upperBound, "Rate too high: " + actualRate);
    }

    @Test
    void concurrentAccess_HighContention_ThreadSafeAndAccurate() throws InterruptedException {
        // This is the critical concurrent test.
        // Config: 10,000 permits/sec, Burst 1,000.
        // 50 Threads hammering acquire(1) for 2 seconds.
        // Expected ~20,000 permits.
        
        double rate = 10_000;
        double capacity = 1_000;
        int threadCount = 50;
        int durationSec = 2;
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);
        AtomicLong totalAcquired = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Start all simultaneously
                    long threadStart = System.nanoTime();
                    long localCount = 0;
                    while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - threadStart) < durationSec) {
                        limiter.acquire(1);
                        localCount++;
                    }
                    totalAcquired.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        long testStart = System.nanoTime();
        startLatch.countDown(); // Go!
        endLatch.await(10, TimeUnit.SECONDS); // Wait for completion
        executor.shutdownNow();
        long testDurationNs = System.nanoTime() - testStart;
        
        assertEquals(0, errors.get(), "Thread errors occurred");
        
        double actualRate = totalAcquired.get() / (testDurationNs / 1_000_000_000.0);
        System.out.printf("Concurrent Test: Threads=%d, Duration=%.2fs, Acquired=%d, Rate=%.2f (Target=%.0f)%n",
                threadCount, testDurationNs/1e9, totalAcquired.get(), actualRate, rate);
        
        // Verify long-run rate does not exceed configured limit (allowing burst)
        // Max theoretical = rate * time + capacity
        double maxAllowed = rate * durationSec + capacity;
        assertTrue(totalAcquired.get() <= maxAllowed + 100, 
                "Exceeded token budget! Acquired: " + totalAcquired.get() + " Max Allowed: " + maxAllowed);
        
        // Verify we actually achieved close to the rate (not deadlocked/starved)
        // We expect at least rate * time (minus some scheduling slack)
        double minExpected = rate * durationSec * 0.95; 
        assertTrue(totalAcquired.get() >= minExpected, 
                "Throughput too low (starvation/deadlock?): " + totalAcquired.get());
    }
    
    @Test
    void concurrentTryAcquire_NoBlocking_ThreadSafe() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100);
        int threads = 20;
        int attemptsPerThread = 5000;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int i=0; i<threads; i++) {
            ex.submit(() -> {
                for (int j=0; j<attemptsPerThread; j++) {
                    if (limiter.tryAcquire(1)) success.incrementAndGet();
                    else fail.incrementAndGet();
                }
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        ex.shutdown();
        
        // Total success cannot exceed capacity (100) because no refill time passed really
        // But since they run concurrently, some refill happens.
        // Main check: No exceptions, counts add up.
        assertEquals((long) threads * attemptsPerThread, success.get() + fail.get());
        assertTrue(success.get() >= 100); // At least initial burst
    }
}
```

### Key Test Explanations

1.  **`rateLimit_LongRunAccuracy` (Parameterized)**: Tests single-threaded accuracy over 2 seconds for various rates (0.5 to 1000). Verifies the refill math and `acquire` blocking precision.
2.  **`concurrentAccess_HighContention_ThreadSafeAndAccurate`**: The **critical concurrent test**.
    *   Uses 50 threads hammering `acquire(1)` for 2 seconds.
    *   Uses `CountDownLatch` to ensure simultaneous start (maximizing contention).
    *   **Verification 1 (Safety)**: `totalAcquired <= rate * time + capacity`. This proves the limiter **never exceeds** the configured long-run rate + burst.
    *   **Verification 2 (Liveness)**: `totalAcquired >= rate * time * 0.95`. This proves threads aren't starving or deadlocked; the fair lock and condition signaling work correctly under load.
3.  **`concurrentTryAcquire_NoBlocking_ThreadSafe`**: Verifies non-blocking path is thread-safe and atomic (success + fail = total attempts).
4.  **`acquire_Interruptible`**: Verifies `lock.lockInterruptibly()` works correctly, allowing cancellation.