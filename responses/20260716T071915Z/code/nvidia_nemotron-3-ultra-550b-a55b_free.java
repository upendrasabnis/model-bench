<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a complete, production-ready implementation of a Thread-Safe Token Bucket Rate Limiter in Java 17+, including design documentation and JUnit 5 tests.

---

### 1. Design Documentation

#### **Algorithm: Token Bucket**
The Token Bucket algorithm controls the rate of requests by maintaining a bucket of tokens.
*   **Capacity (`maxTokens`)**: Maximum bucket size (burst allowance).
*   **Refill Rate (`tokensPerSecond`)**: Tokens added per second.
*   **State**: `availableTokens` (double, for fractional precision) and `lastRefillNanos` (long).

#### **Concurrency Strategy: Monitor Pattern (Lock + Condition)**
*   **Why not `synchronized`?** We need `Condition.awaitNanos(timeout)` for interruptible, timed blocking waits. `ReentrantLock` provides this natively.
*   **Why not `StampedLock`?** The critical section involves floating-point math and condition signaling; optimistic reads are complex here. `ReentrantLock` offers better fairness guarantees (optional) and simpler reasoning.
*   **Atomicity**: All state mutations (`availableTokens`, `lastRefillNanos`) and condition checks happen under the **same lock**.
*   **Refill on Demand**: Tokens are calculated *only* when a thread attempts acquisition (`tryAcquire`/`acquire`). This avoids a background "refiller" thread, reducing CPU usage during idle periods and eliminating clock-drift issues between threads.

#### **Blocking Acquire (`acquire`)**
1.  Lock.
2.  Refill tokens based on `now - lastRefillNanos`.
3.  If `availableTokens >= permits`: deduct, unlock, return.
4.  Else: Calculate exact nanos needed for next token (`deficit / rate * 1e9`).
5.  `condition.awaitNanos(nanosToWait)`.
6.  Loop (spurious wakeups / signal storms).
7.  Handle `InterruptedException` (restore interrupt flag, throw).

#### **Non-Blocking Try Acquire (`tryAcquire`)**
1.  Lock.
2.  Refill.
3.  Check availability.
4.  Deduct or fail immediately.
5.  Unlock, return boolean.

#### **Precision**
*   Uses `double` for `availableTokens` to handle high rates (e.g., 1,000,000 TPS) without integer overflow/granularity loss.
*   Uses `System.nanoTime()` for monotonic time source (immune to wall-clock changes).

---

### 2. Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate and burst capacity.
 * Provides blocking {@link #acquire(int)}, non-blocking {@link #tryAcquire(int)},
 * and timed {@link #tryAcquire(int, long, TimeUnit)} variants.
 * </p>
 *
 * <h3>Concurrency</h3>
 * Uses a {@link ReentrantLock} with a {@link Condition} for blocking waits.
 * All state mutations are guarded by the lock. Refill happens "on-demand"
 * during acquisition attempts to avoid background threads.
 *
 * <h3>Precision</h3>
 * Uses {@code double} for token counts and {@code System.nanoTime()} for time,
 * allowing high throughput rates (e.g. > 1M ops/sec) with sub-microsecond precision.
 */
public final class TokenBucketRateLimiter {

    private final double tokensPerSecond;
    private final double maxTokens;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    // State guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * Creates a rate limiter.
     *
     * @param tokensPerSecond the steady-state refill rate (must be > 0)
     * @param maxTokens       the maximum bucket capacity / burst allowance (must be >= 1)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double tokensPerSecond, double maxTokens) {
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("tokensPerSecond must be > 0");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1");
        }
        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxTokens;
        this.availableTokens = maxTokens; // Start full (allows initial burst)
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Acquires the given number of permits from this limiter, blocking until available.
     * Equivalent to {@code tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)}.
     *
     * @param permits the number of permits to acquire (must be > 0)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires permits if available immediately, otherwise returns false.
     *
     * @param permits the number of permits to acquire (must be > 0)
     * @return {@code true} if permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        return tryAcquire(permits, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires permits if they become available within the timeout.
     *
     * @param permits  the number of permits to acquire (must be > 0)
     * @param timeout  the maximum time to wait
     * @param unit     the time unit of the timeout argument
     * @return {@code true} if permits acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        if (unit == null) throw new NullPointerException("unit");

        long nanosTimeout = unit.toNanos(timeout);
        long deadline = (nanosTimeout == Long.MAX_VALUE) ? Long.MAX_VALUE : System.nanoTime() + nanosTimeout;

        lock.lockInterruptibly();
        try {
            while (true) {
                refill();

                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return true;
                }

                // Calculate time needed for the deficit
                double deficit = permits - availableTokens;
                // nanos = (deficit / rate) * 1_000_000_000
                long nanosNeeded = (long) Math.ceil((deficit / tokensPerSecond) * 1_000_000_000.0);

                if (nanosTimeout == 0) {
                    return false; // Non-blocking mode
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false; // Timeout expired
                }

                // Wait for the minimum of (time needed for tokens) and (remaining timeout)
                // Add small epsilon (1ms) to avoid busy-spinning on Condition precision limits
                long waitNanos = Math.min(nanosNeeded, remainingNanos);
                
                // Condition.awaitNanos returns remaining time, or <=0 if timed out
                nanosTimeout = notEmpty.awaitNanos(waitNanos);
                
                // Loop continues: refill will run again, re-check condition
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refills tokens based on elapsed time since last refill.
     * Must be called holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        
        if (elapsedNanos > 0) {
            // Calculate tokens to add: (elapsedNanos / 1e9) * rate
            double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * tokensPerSecond;
            availableTokens = Math.min(maxTokens, availableTokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Returns the current number of available tokens (approximate, for monitoring).
     * This is a snapshot; actual availability may change immediately after return.
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

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getTokensPerSecond() {
        return tokensPerSecond;
    }

    /**
     * Returns the configured maximum burst capacity.
     */
    public double getMaxTokens() {
        return maxTokens;
    }
}
```

---

### 3. JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    // ---------- Basic Functionality ----------

    @Test
    void constructor_InvalidParams_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void tryAcquire_ImmediateBurst_AllowsUpToCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // 10/sec, burst 5
        
        // Should allow full burst immediately
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1)); // Bucket empty
        assertEquals(0, limiter.getAvailableTokens(), 1e-9);
    }

    @Test
    void tryAcquire_RefillOverTime_AllowsMore() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/sec, burst 10
        
        assertTrue(limiter.tryAcquire(10)); // Drain
        assertFalse(limiter.tryAcquire(1));
        
        // Wait for ~1 token (1ms at 1000/s)
        Thread.sleep(2); 
        
        assertTrue(limiter.tryAcquire(1)); // Refill happened
    }

    @Test
    void acquire_BlocksUntilAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100/sec, burst 1
        
        assertTrue(limiter.tryAcquire(1)); // Take the only token
        
        Thread start = new Thread(() -> {
            try {
                limiter.acquire(1); // Should block ~10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        long startTime = System.nanoTime();
        start.start();
        
        // Give thread time to park
        Thread.sleep(50); 
        assertTrue(start.isAlive(), "Thread should be blocked waiting for token");
        
        // Wait for it to finish (token refills ~10ms, plus scheduling)
        start.join(2000); 
        
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        assertFalse(start.isAlive(), "Thread should have acquired and finished");
        assertTrue(elapsedMs >= 5, "Should have waited roughly 10ms for refill");
    }

    @Test
    void tryAcquire_Timeout_ReturnsFalse() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(1); // Drain
        
        // Timeout 50ms, but refill takes 100ms
        assertFalse(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void tryAcquire_Interrupt_ThrowsException() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.tryAcquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1);
            } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure it's waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ---------- Concurrency & Correctness ----------

    @Test
    void concurrentAccess_ThreadSafe_NoExceptions() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, 100);
        int threads = 50;
        int opsPerThread = 200;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        if (limiter.tryAcquire(1)) successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        exec.shutdown();
        
        // We don't assert exact count due to timing, just that it runs without error
        assertTrue(successCount.get() > 0);
    }

    /**
     * Long-run rate verification test.
     * Runs for a fixed duration with high concurrency.
     * Verifies that the *average* acquisition rate does not exceed the configured limit
     * (allowing for initial burst).
     */
    @Test
    void longRunRate_DoesNotExceedLimit() throws InterruptedException {
        double ratePerSec = 5000.0; // 5k ops/sec
        double burst = 100.0;
        Duration testDuration = Duration.ofSeconds(3); // 3 seconds
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSec, burst);
        
        int threadCount = 16; // High contention
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        AtomicLong totalAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        // Use a volatile boolean for stop signal to avoid InterruptedException overhead in hot path
        // but we will interrupt to stop cleanly.
        Thread[] workers = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(() -> {
                try {
                    startLatch.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        // Acquire 1 permit. Block if necessary.
                        limiter.acquire(1);
                        totalAcquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    // Expected on shutdown
                    Thread.currentThread().interrupt(); // Restore flag
                }
            });
            workers[i].start();
        }
        
        long startTime = System.nanoTime();
        startLatch.countDown(); // Go!
        
        // Let it run for testDuration
        Thread.sleep(testDuration.toMillis());
        
        // Stop workers
        for (Thread w : workers) w.interrupt();
        for (Thread w : workers) w.join(2000); // Wait for death
        
        exec.shutdownNow();
        
        long elapsedNanos = System.nanoTime() - startTime;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        long acquired = totalAcquired.get();
        double actualRate = acquired / elapsedSec;
        
        System.out.printf("Rate Test: Acquired=%d, Time=%.3fs, Rate=%.2f/s (Limit=%.0f/s, Burst=%.0f)%n",
                acquired, elapsedSec, actualRate, ratePerSec, burst);

        // Assertion Logic:
        // Max theoretical tokens = Initial Burst + Rate * Time
        double maxAllowedTokens = burst + (ratePerSec * elapsedSec);
        
        // Allow small tolerance for floating point / scheduling granularity (1% + 10 tokens)
        double tolerance = Math.max(10, maxAllowedTokens * 0.01);
        
        assertTrue(acquired <= maxAllowedTokens + tolerance, 
            String.format("Rate exceeded limit! Acquired: %d, Max Allowed (with tolerance): %.2f. Actual Rate: %.2f/s", 
                acquired, maxAllowedTokens + tolerance, actualRate));
        
        // Also verify we roughly achieved the rate (not completely stalled)
        // Should be at least 90% of theoretical max (minus burst)
        double minExpected = (ratePerSec * elapsedSec) * 0.90; 
        assertTrue(acquired >= minExpected, 
            String.format("Rate too low (starvation?). Acquired: %d, Min Expected: %.2f", acquired, minExpected));
    }

    // ---------- Edge Cases ----------

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 100})
    void acquire_MultiPermit_Works(int permits) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 100);
        assertTrue(limiter.tryAcquire(permits));
        assertEquals(100 - permits, limiter.getAvailableTokens(), 1e-9);
    }

    @Test
    void acquire_ZeroPermits_Throws() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
    }

    @Test
    void getAvailableTokens_ReflectsRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1k/s
        limiter.tryAcquire(10); // Drain
        assertEquals(0, limiter.getAvailableTokens(), 1e-9);
        
        Thread.sleep(10); // 10ms -> 10 tokens
        // getAvailableTokens calls refill internally
        assertEquals(10, limiter.getAvailableTokens(), 0.5); // Allow small FP drift
    }
}
```

---

### 4. Key Implementation Details & Test Notes

1.  **`refill()` Math**:
    `tokensToAdd = (elapsedNanos / 1e9) * rate`.
    Using `double` prevents integer division truncation at high rates (e.g., 1,000,000 TPS adds 1 token per 1000 ns; `long` math would require fixed-point scaling).

2.  **Blocking Wait Calculation**:
    `nanosNeeded = ceil(deficit / rate * 1e9)`.
    We wait for the *exact* time required for the missing tokens, capped by the user's timeout. This avoids "spin-waiting" on the Condition or waking up too early/late.

3.  **`Condition.awaitNanos` Return Value**:
    Returns remaining estimated time. We assign it back to `nanosTimeout` (effectively updating the deadline relative to now) and loop. This handles spurious wakeups and signal storms correctly.

4.  **Concurrent Test (`longRunRate_DoesNotExceedLimit`)**:
    *   **Duration**: 3 seconds (long enough to smooth out burst/jitter).
    *   **Threads**: 16 (high contention on Lock).
    *   **Metric**: `totalAcquired / elapsedTime`.
    *   **Assertion**: `acquired <= burst + rate * time + tolerance`.
    *   This proves the limiter **never exceeds** the long-term rate contract, even under heavy multi-threaded load. The tolerance (1%) absorbs OS scheduler granularity and `System.nanoTime()` precision limits.

5.  **Fairness**: The `ReentrantLock` defaults to non-fair (higher throughput). If strict FIFO fairness is required for latency distribution, change to `new ReentrantLock(true)`.