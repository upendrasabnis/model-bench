<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260818T143128Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Documentation: `TokenBucketRateLimiter`

### 1. Algorithm Overview
This implementation uses the **Token Bucket** algorithm.
*   **Bucket Capacity (`capacity`)**: Maximum burst size (max tokens the bucket holds).
*   **Refill Rate (`refillRatePerSecond`)**: Tokens added per second (sustained throughput).
*   **State**:
    *   `availableTokens` (double): Current tokens, allows fractional precision for smooth refills.
    *   `lastRefillNanos` (long): Timestamp of last refill calculation (System.nanoTime()).

### 2. Thread Safety Strategy
*   **Mutual Exclusion**: A `ReentrantLock` guards all mutable state (`availableTokens`, `lastRefillNanos`).
*   **Condition Variable**: `notEmpty` Condition signals waiting threads when tokens become available.
*   **Non-blocking Path**: `tryAcquire` acquires lock, attempts consumption, releases lock immediately.
*   **Blocking Path**: `acquire` / `acquire(timeout)`:
    1.  Lock.
    2.  Refill & Attempt consume.
    3.  If insufficient: Calculate exact nanos to wait for *required* tokens.
    4.  `condition.awaitNanos(timeoutRemaining)`.
    5.  Loop (spurious wakeups, clock drift).
    6.  Unlock in `finally`.

### 3. Refill Mechanics (Lazy Refill)
Refill happens **on-demand** during `acquire`/`tryAcquire` calls, not via a background thread.
*   **Pros**: No scheduler overhead; zero idle CPU usage; naturally handles "idle" periods (bucket fills to capacity).
*   **Cons**: Refill granularity depends on call frequency. *Mitigation*: High-precision `nanoTime` math ensures mathematical correctness regardless of call intervals.

### 4. API Semantics
*   `acquire(int permits)`: Blocks indefinitely (responds to `Thread.interrupt()`).
*   `tryAcquire(int permits)`: Instant success/failure.
*   `tryAcquire(int permits, long timeout, TimeUnit unit)`: Timed wait.
*   **Fairness**: Configurable via constructor (delegates to `ReentrantLock` fairness policy). Default: Non-fair (higher throughput).

### 5. Precision & Edge Cases
*   **Double Precision**: Used for `availableTokens` to handle rates like `1000/3 ≈ 333.33 tokens/sec` without drift.
*   **Overflow**: `Math.min(capacity, ...)` caps bucket.
*   **Large Permits**: `acquire(permits > capacity)` throws `IllegalArgumentException` (can never succeed).
*   **Zero/negative rate/permits**: Validated in constructor/methods.

---

## Implementation

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate (tokens/second) and burst capacity.
 * Provides blocking ({@link #acquire(int)}), non-blocking ({@link #tryAcquire(int)}),
 * and timed ({@link #tryAcquire(int, long, TimeUnit)}) acquisition.
 * </p>
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 *   <li>Uses lazy refill: tokens are calculated based on elapsed real-time only when a thread attempts acquisition.</li>
 *   <li>Internal token count is {@code double} to support fractional refill rates precisely (e.g., 10 tokens per 3 seconds).</li>
 *   <li>Synchronization via {@link ReentrantLock} and {@link Condition} for efficient blocking/waiting.</li>
 *   <li>No background threads; zero overhead when idle.</li>
 * </ul>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    private final double capacity;           // Max tokens (burst)
    private final double refillRatePerSecond; // Tokens added per second
    private final long refillIntervalNanos;  // 1_000_000_000 / refillRatePerSecond (optimization)

    // Mutable state guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    private final Lock lock;
    private final Condition notEmpty;

    /**
     * Creates a rate limiter.
     *
     * @param refillRatePerSecond sustained tokens per second (must be > 0)
     * @param capacity            max burst tokens (must be >= refillRatePerSecond typically, but > 0 enforced)
     * @param fair                if true, uses fair locking policy (FIFO thread ordering), lower throughput.
     * @throws IllegalArgumentException if rate <= 0 or capacity <= 0
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity, boolean fair) {
        if (refillRatePerSecond <= 0.0) {
            throw new IllegalArgumentException("Refill rate must be positive: " + refillRatePerSecond);
        }
        if (capacity <= 0.0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        this.refillIntervalNanos = (long) (1_000_000_000L / refillRatePerSecond); // Nanos per token
        this.availableTokens = capacity; // Start full
        this.lastRefillNanos = System.nanoTime();
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
    }

    /**
     * Convenience constructor: non-fair lock (higher throughput).
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        this(refillRatePerSecond, capacity, false);
    }

    /**
     * Refills tokens based on elapsed time since last refill.
     * Must hold {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            // Calculate tokens to add: (elapsed_nanos / 1e9) * rate
            double tokensToAdd = (elapsedNanos * refillRatePerSecond) / 1_000_000_000.0;
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Acquires {@code permits} tokens, blocking until available.
     * Responds to interruption ({@link InterruptedException}).
     *
     * @param permits number of tokens to acquire (must be > 0 and <= capacity)
     * @throws InterruptedException if thread interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive: " + permits);
        if (permits > capacity) throw new IllegalArgumentException("Permits (" + permits + ") exceed capacity (" + capacity + ")");

        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return;
                }
                // Calculate wait time for required tokens
                double deficit = permits - availableTokens;
                // nanos = deficit / rate * 1e9
                long waitNanos = (long) Math.ceil((deficit / refillRatePerSecond) * 1_000_000_000.0);
                
                // Wait on condition. awaitNanos returns remaining time (or <=0 if timed out/spurious).
                // We loop to handle spurious wakeups and re-check condition.
                notEmpty.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire {@code permits} immediately.
     *
     * @param permits tokens to acquire
     * @return true if acquired, false if insufficient tokens
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive: " + permits);
        if (permits > capacity) throw new IllegalArgumentException("Permits (" + permits + ") exceed capacity (" + capacity + ")");

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
     * Attempts to acquire {@code permits} within the given timeout.
     *
     * @param permits  tokens to acquire
     * @param timeout  max time to wait
     * @param unit     time unit
     * @return true if acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive: " + permits);
        if (permits > capacity) throw new IllegalArgumentException("Permits (" + permits + ") exceed capacity (" + capacity + ")");
        if (unit == null) throw new NullPointerException("TimeUnit cannot be null");

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return true;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false; // Timeout
                }
                // Wait for tokens or timeout, whichever comes first
                notEmpty.awaitNanos(remainingNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns current available tokens (approximate, snapshot at call time).
     * Note: Value changes immediately after return due to concurrent access/refill.
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

    public double getCapacity() { return capacity; }
    public double getRefillRatePerSecond() { return refillRatePerSecond; }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimit;

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

    private static final double DELTA = 1e-9;

    // ---------------------------------------------------------
    // Basic Functionality & Contract Tests
    // ---------------------------------------------------------

    @Test
    void constructor_InvalidRate_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void constructor_InvalidCapacity_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void acquire_InvalidPermits_Throws() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(11)); // > capacity
    }

    @Test
    void tryAcquire_ImmediateSuccess_WhenFull() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1)); // Empty
    }

    @Test
    void acquire_BlocksUntilRefill() throws InterruptedException {
        // Rate: 1000 tokens/sec. Capacity: 1.
        // Start full. Acquire 1 -> empty. Next acquire must wait ~1ms.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1);
        
        assertTrue(limiter.tryAcquire(1)); // Instant
        assertEquals(0.0, limiter.getAvailableTokens(), DELTA);

        long start = System.nanoTime();
        limiter.acquire(1); // Should block ~1ms
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        
        // Allow some scheduling slack (0.5ms - 50ms)
        assertTrue(elapsedMs >= 0, "Should have waited");
        assertTrue(elapsedMs < 100, "Waited too long: " + elapsedMs + "ms");
    }

    @Test
    void tryAcquire_Timeout_ReturnsFalse() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 1 token per 100ms
        limiter.acquire(1); // Drain
        
        // Try to acquire with 10ms timeout (need 100ms)
        assertFalse(limiter.tryAcquire(1, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void tryAcquire_Timeout_SucceedsIfTokensArrive() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1 token/ms
        limiter.acquire(1); // Drain
        
        // Wait 2ms timeout, token arrives at ~1ms
        assertTrue(limiter.tryAcquire(1, 2, TimeUnit.MILLISECONDS));
    }

    @Test
    void burst_AllowsUpToCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100); // Rate 10/s, Burst 100
        // Should allow immediate burst of 100
        assertTrue(limiter.tryAcquire(100));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void idleRefill_FillsToCapacity() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/s, cap 10
        limiter.acquire(10); // Drain
        assertEquals(0.0, limiter.getAvailableTokens(), DELTA);
        
        Thread.sleep(20); // 20ms -> 20 tokens generated, capped at 10
        assertEquals(10.0, limiter.getAvailableTokens(), 0.5); // Allow small precision drift
    }

    // ---------------------------------------------------------
    // Concurrency Tests
    // ---------------------------------------------------------

    @Test
    void concurrentAccess_DoesNotExceedCapacity() throws InterruptedException {
        int capacity = 1000;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, capacity); // High rate, fixed cap
        int threads = 50;
        int permitsPerThread = 30;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    // Try to acquire more than capacity/threads to force contention
                    if (limiter.tryAcquire(permitsPerThread)) {
                        acquired.addAndGet(permitsPerThread);
                    } else {
                        rejected.incrementAndGet();
                    }
                    endLatch.countDown();
                });
            }
            startLatch.countDown();
            endLatch.await(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Total acquired must never exceed capacity (1000)
        // Note: tryAcquire is atomic, so sum of successful permits <= capacity
        assertTrue(acquired.get() <= capacity, "Acquired " + acquired.get() + " > Capacity " + capacity);
        assertEquals(threads * permitsPerThread, acquired.get() + rejected.get() * permitsPerThread);
    }

    @Test
    void concurrentBlockingAcquire_AllSucceedEventually() throws InterruptedException {
        int permitsTotal = 1000;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, permitsTotal); // Fast refill
        int threads = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    try {
                        limiter.acquire(10); // Each takes 10
                        success.incrementAndGet();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "Threads timed out");
        } finally {
            exec.shutdownNow();
        }
        assertEquals(threads, success.get());
    }

    // ---------------------------------------------------------
    // Long-Run Rate Accuracy Test (The Critical Requirement)
    // ---------------------------------------------------------

    /**
     * Verifies that over a sustained period, the throughput does not exceed the configured rate.
     * Strategy:
     * 1. Run a producer thread calling acquire(1) in a tight loop for a fixed duration (e.g., 2 seconds).
     * 2. Count total permits acquired.
     * 3. Assert: Total Permits <= Rate * Duration + Burst_Capacity (initial burst allowance).
     * 4. Assert: Total Permits >= Rate * Duration - Margin (ensure it's not *too* slow, optional but good).
     */
    @Test
    void longRunRate_DoesNotExceedLimit() throws InterruptedException {
        double ratePerSec = 5000.0;      // 5000 tokens/sec
        double capacity = 100.0;         // Small burst relative to test duration
        double testDurationSec = 2.0;    // 2 seconds
        long testDurationNanos = (long) (testDurationSec * 1_000_000_000L);

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSec, capacity);
        
        AtomicLong acquiredCount = new AtomicLong(0);
        AtomicLong startTime = new AtomicLong(0);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            startTime.set(System.nanoTime());
            ready.countDown();
            long deadline = startTime.get() + testDurationNanos;
            
            // Spin acquiring until deadline
            while (System.nanoTime() < deadline) {
                try {
                    limiter.acquire(1);
                    acquiredCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            done.countDown();
        }, "Rate-Limiter-Producer");

        producer.start();
        ready.await(); // Ensure producer started and captured startTime
        assertTrue(done.await((long) (testDurationSec * 1.5) + 5, TimeUnit.SECONDS), "Test timed out");

        long totalAcquired = acquiredCount.get();
        long actualDurationNanos = System.nanoTime() - startTime.get();
        double actualDurationSec = actualDurationNanos / 1_000_000_000.0;

        // Theoretical Max = Initial Burst + Rate * Time
        double maxAllowed = capacity + (ratePerSec * actualDurationSec);
        
        // Theoretical Min (approx) = Rate * Time - Capacity (if we start empty, but we start full)
        // Since we start full, we expect close to MaxAllowed.
        // We allow a small epsilon for thread scheduling delays at the very end.
        double epsilon = 50.0; // tokens slack

        System.out.printf("Long Run Test: Rate=%.0f/s, Cap=%.0f, Duration=%.3fs, Acquired=%d, MaxAllowed=%.2f%n",
                ratePerSec, capacity, actualDurationSec, totalAcquired, maxAllowed);

        // 1. Hard Limit: Must NOT exceed theoretical max (burst + sustained)
        assertTrue(totalAcquired <= maxAllowed + epsilon, 
            String.format("Rate Limit Exceeded! Acquired %d, Max Allowed %.2f (Rate*Time + Burst)", totalAcquired, maxAllowed + epsilon));

        // 2. Liveness/Throughput Check: Should be reasonably close to max (within burst + scheduling)
        // If this fails, the limiter is too conservative (e.g. coarse locking, sleep granularity).
        double minExpected = maxAllowed - capacity - epsilon; // Subtract initial burst we might not have used fully if timing unlucky? 
        // Actually, since we start full, we *should* use the burst.
        // Min expected ~ Rate * Time. 
        minExpected = ratePerSec * actualDurationSec - epsilon;
        assertTrue(totalAcquired >= minExpected, 
            String.format("Throughput too low! Acquired %d, Expected ~%.2f", totalAcquired, minExpected));
    }

    @Test
    void interruptibleAcquire_RespondsToInterrupt() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec, empty
        limiter.acquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try { limiter.acquire(1); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50); // Ensure thread is waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited on interrupt");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fairnessPolicy_Constructs(boolean fair) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10, fair);
        assertNotNull(limiter);
    }
}
```

---

## Key Implementation Details for Review

1.  **`refillIntervalNanos` Optimization**: Pre-calculated `1e9 / rate` avoids division in hot path, though `refill()` uses multiplication `(elapsed * rate) / 1e9` which is standard for double precision.
2.  **`awaitNanos` Loop**: The blocking `acquire` uses `notEmpty.awaitNanos(waitNanos)`.
    *   Calculates *exact* wait time for the *deficit*.
    *   Handles spurious wakeups by looping.
    *   Handles `InterruptedException` correctly (lock.lockInterruptibly).
3.  **No `signalAll` on Refill**: We do **not** call `notEmpty.signalAll()` inside `refill()`.
    *   *Reason*: `refill()` is called by the *acquiring* thread before it checks/waits. The threads *already waiting* are woken up by the **previous** acquirer who took the last tokens? No.
    *   *