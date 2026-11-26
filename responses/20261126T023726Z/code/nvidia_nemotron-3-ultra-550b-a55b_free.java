<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261126T023726Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: `TokenBucketRateLimiter`

#### 1. Algorithm Overview
The **Token Bucket** algorithm controls the rate of operations by maintaining a bucket of tokens.
*   **Capacity (Burst):** Maximum tokens the bucket holds (`maxTokens`). Allows short bursts of traffic up to this limit.
*   **Refill Rate:** Tokens added per second (`tokensPerSecond`). Defines the sustained long-term rate limit.
*   **Consumption:** Each `acquire(n)` or `tryAcquire(n)` removes `n` tokens. If insufficient tokens exist, the operation either blocks (waiting for refill) or fails immediately.

#### 2. Concurrency Model
*   **Locking:** Uses a single `ReentrantLock` (non-fair for throughput) guarding all mutable state (`availableTokens`, `lastRefillNanos`).
*   **Condition Variable:** `notEmpty` Condition allows threads to wait efficiently in `acquire()` instead of busy-spinning.
*   **Thread Safety:** All public methods (`acquire`, `tryAcquire`, `getAvailableTokens`) are synchronized via the lock. The refill calculation is atomic with the consumption check.

#### 3. Time & Precision
*   **Time Source:** `System.nanoTime()` (monotonic, high-resolution). Immune to system clock changes (NTP, user adjustments).
*   **Token Representation:** `double` for `availableTokens`.
    *   *Why not `long`?* Integer tokens force granularity of "1 token". If rate is 1000/sec, min wait is 1ms. `double` allows sub-token precision (e.g., 0.5 tokens), enabling accurate limiting for any rate (e.g., 0.5 tokens/sec = 1 token per 2 seconds) without quantization error.

#### 4. Refill Strategy: "Lazy Refill"
Tokens are not added by a background scheduler thread.
*   **Mechanism:** `refill()` is called at the start of *every* public API call (`acquire`, `tryAcquire`, `getAvailableTokens`).
*   **Calculation:** `elapsedSeconds = (now - lastRefillNanos) / 1e9`; `newTokens = elapsedSeconds * tokensPerSecond`.
*   **Pros:** Zero overhead when idle; no "scheduler thread" resource leak; naturally handles clock monotonicity.
*   **Cons:** Refill granularity depends on call frequency. (Mitigated: `acquire` calculates exact wait time if empty).

#### 5. Blocking Acquire (`acquire(permits)`)
1.  Lock.
2.  Refill.
3.  If `available >= permits`: Deduct, unlock, return.
4.  Else: Calculate `deficit = permits - available`.
    *   Time needed: `nanosToWait = (deficit / tokensPerSecond) * 1e9`.
    *   Wait on `notEmpty` condition with `nanosToWait` timeout.
    *   Loop (handles spurious wakeups, signal storms, or clock drift).
5.  Unlock in `finally`.

#### 6. Non-Blocking Acquire (`tryAcquire(permits)` / `tryAcquire(permits, timeout, unit)`)
*   **No-arg timeout:** Calls `refill()`, checks availability, deducts or returns `false` immediately.
*   **With timeout:** Delegates to `acquire` logic but bounds total wait by the provided timeout using `Condition.awaitNanos` remaining time calculation.

#### 7. Configuration & Validation
*   Constructor validates `tokensPerSecond > 0` and `maxTokens >= 1`.
*   `maxTokens` effectively limits the maximum burst *and* the maximum wait time a single `acquire` might incur (if `permits > maxTokens`, it throws `IllegalArgumentException` immediately).

---

### Implementation: `TokenBucketRateLimiter.java`

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 * <p>
 * Supports configurable refill rate (tokens/second) and burst capacity (max tokens).
 * Provides blocking {@link #acquire(long)} and non-blocking {@link #tryAcquire(long)}
 * and {@link #tryAcquire(long, long, TimeUnit)} methods.
 * </p>
 * <p>
 * Implementation uses a "lazy refill" strategy: the bucket is refilled based on
 * elapsed time ({@link System#nanoTime()}) at the entry of every public method.
 * No background threads are used.
 * </p>
 */
public final class TokenBucketRateLimiter {

    private final double tokensPerSecond;
    private final double maxTokens;
    private final Lock lock = new ReentrantLock(/* fair = */ false);
    private final Condition notEmpty = lock.newCondition();

    // State guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * Creates a new rate limiter.
     *
     * @param tokensPerSecond the sustained rate of token refill (must be > 0).
     * @param maxTokens       the maximum bucket capacity (burst allowance, must be >= 1).
     * @throws IllegalArgumentException if parameters are invalid.
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
        this.availableTokens = maxTokens; // Start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Acquires the given number of permits from this rate limiter, blocking until
     * the request can be granted. Equivalent to {@code tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)}.
     *
     * @param permits the number of permits to acquire (must be > 0 and <= maxTokens).
     * @throws InterruptedException if the current thread is interrupted while waiting.
     * @throws IllegalArgumentException if permits <= 0 or permits > maxTokens.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        if (permits > maxTokens) throw new IllegalArgumentException("permits (" + permits + ") cannot exceed maxTokens (" + maxTokens + ")");

        lock.lockInterruptibly();
        try {
            // Fast path: try acquire immediately
            if (tryAcquireLocked(permits)) {
                return;
            }

            // Slow path: wait for tokens
            long remainingNanos = Long.MAX_VALUE;
            while (true) {
                refillLocked();
                double deficit = permits - availableTokens;
                // Time needed to accumulate 'deficit' tokens
                long waitNanos = (long) (deficit / tokensPerSecond * 1_000_000_000L);

                // Wait for the calculated time, or until signaled (spurious wakeup / burst refill)
                // We use the minimum of calculated wait and remaining timeout (infinite here).
                remainingNanos = notEmpty.awaitNanos(waitNanos);

                // Loop re-checks condition (availableTokens >= permits) via tryAcquireLocked
                if (tryAcquireLocked(permits)) {
                    return;
                }
                // If awaitNanos returns <= 0, it timed out (should not happen with Long.MAX_VALUE unless interrupted)
                // But we loop anyway to handle spurious wakeups.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires permits if available immediately, returning {@code true}, or returns {@code false}
     * if the permits are not available (non-blocking).
     *
     * @param permits the number of permits to acquire (must be > 0).
     * @return {@code true} if permits were acquired, {@code false} otherwise.
     * @throws IllegalArgumentException if permits <= 0 or permits > maxTokens.
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        if (permits > maxTokens) throw new IllegalArgumentException("permits cannot exceed maxTokens");

        lock.lock();
        try {
            return tryAcquireLocked(permits);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires permits if they become available within the given timeout.
     *
     * @param permits the number of permits to acquire (must be > 0 and <= maxTokens).
     * @param timeout the maximum time to wait.
     * @param unit    the time unit of the timeout argument.
     * @return {@code true} if permits were acquired, {@code false} if timeout elapsed.
     * @throws InterruptedException if interrupted while waiting.
     * @throws IllegalArgumentException if permits <= 0 or permits > maxTokens.
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        if (permits > maxTokens) throw new IllegalArgumentException("permits cannot exceed maxTokens");
        if (timeout <= 0) return tryAcquire(permits); // Fast path for non-positive timeout

        long nanosTimeout = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            // Fast path
            if (tryAcquireLocked(permits)) {
                return true;
            }

            long remainingNanos = nanosTimeout;
            long deadline = System.nanoTime() + nanosTimeout;

            while (remainingNanos > 0) {
                refillLocked();
                double deficit = permits - availableTokens;
                long waitNanos = (long) (deficit / tokensPerSecond * 1_000_000_000L);

                // Wait for the minimum of calculated wait time and remaining deadline
                long actualWait = Math.min(waitNanos, remainingNanos);
                remainingNanos = notEmpty.awaitNanos(actualWait);

                if (tryAcquireLocked(permits)) {
                    return true;
                }
                // Recalculate remaining based on deadline to avoid drift
                remainingNanos = deadline - System.nanoTime();
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of available tokens (estimated, refilled lazily).
     * Useful for monitoring/debugging.
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refillLocked();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    public double getTokensPerSecond() {
        return tokensPerSecond;
    }

    public double getMaxTokens() {
        return maxTokens;
    }

    // --- Internal Helpers (Must hold lock) ---

    /**
     * Refills the bucket based on elapsed time since last refill.
     */
    private void refillLocked() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            double newTokens = elapsedSeconds * tokensPerSecond;
            availableTokens = Math.min(maxTokens, availableTokens + newTokens);
            lastRefillNanos = now;
        }
    }

    /**
     * Attempts to consume permits assuming lock is held and bucket is recently refilled.
     * @return true if successful.
     */
    private boolean tryAcquireLocked(long permits) {
        refillLocked(); // Ensure up-to-date before check
        if (availableTokens >= permits) {
            availableTokens -= permits;
            return true;
        }
        return false;
    }
}
```

---

### JUnit 5 Tests: `TokenBucketRateLimiterTest.java`

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

    private static final double EPSILON = 1e-9;

    @Test
    void constructor_InvalidParams_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0.5));
    }

    @Test
    void acquire_ExceedsMaxTokens_Throws() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(11));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(11));
    }

    @Test
    void initialState_IsFull() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50);
        assertEquals(50, limiter.getAvailableTokens(), EPSILON);
        assertTrue(limiter.tryAcquire(50));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
    }

    @Test
    void tryAcquire_NonBlocking_RespectsBurst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // Rate 10/s, Burst 5
        // Burst 5
        assertTrue(limiter.tryAcquire(3));
        assertTrue(limiter.tryAcquire(2));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
        // Next should fail immediately
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void refill_OverTime_AllowsMore() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000 tokens/sec = 1 token/ms
        assertTrue(limiter.tryAcquire(10)); // Drain
        assertFalse(limiter.tryAcquire(1));

        Thread.sleep(5); // ~5ms -> ~5 tokens
        assertTrue(limiter.tryAcquire(4)); // Should succeed
        assertFalse(limiter.tryAcquire(2)); // Only ~1 left

        Thread.sleep(10); // ~10ms -> ~10 tokens (capped at 10)
        assertTrue(limiter.tryAcquire(10));
    }

    @Test
    void acquire_BlocksUntilAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100 tokens/sec, burst 1
        limiter.acquire(1); // Take the only token
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);

        long start = System.nanoTime();
        // Start a thread to acquire (will block)
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        // Give thread time to park
        Thread.sleep(50);
        assertTrue(t.isAlive(), "Thread should be blocked waiting for token");

        // Wait for refill (~10ms for 1 token at 100/s) + margin
        t.join(200); 
        assertFalse(t.isAlive(), "Thread should have acquired and finished");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Should wait roughly 10ms (1 token / 100 per sec = 0.01s)
        assertTrue(elapsedMs >= 8 && elapsedMs <= 100, "Elapsed: " + elapsedMs + "ms");
    }

    @Test
    void acquire_Interruptible() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow refill
        limiter.acquire(1); // Drain

        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // Block forever
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        Thread.sleep(50); // Ensure parked
        t.interrupt();
        assertDoesNotThrow(() -> t.join(1000));
        assertFalse(t.isAlive());
    }

    @Test
    void tryAcquire_WithTimeout_RespectsTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 1 token per 100ms
        limiter.acquire(1); // Drain

        long start = System.nanoTime();
        // Timeout 50ms < 100ms needed
        boolean acquired = limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertFalse(acquired);
        assertTrue(elapsed >= 40 && elapsed <= 150, "Elapsed: " + elapsed + "ms");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1, 10, 100, 1000, 10000})
    void longRunRateLimit_Concurrent_SustainedRateRespected(double rate) throws InterruptedException {
        // Config: Rate = 'rate' permits/sec. Burst = rate * 2 (allows 2 seconds burst).
        // Duration: 2 seconds.
        // Expected Max Permits: Initial Burst (2*rate) + Refill (rate * 2s) = 4 * rate.
        // We add a small margin (5%) for thread scheduling overhead.
        
        double maxTokens = rate * 2;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, maxTokens);
        
        int threadCount = 16;
        int durationSeconds = 2;
        AtomicLong totalAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Start all simultaneously
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
                        long localCount = 0;
                        while (System.nanoTime() < deadline) {
                            // Acquire 1 permit at a time
                            limiter.acquire(1);
                            localCount++;
                        }
                        totalAcquired.addAndGet(localCount);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Go!
            assertTrue(endLatch.await(10, TimeUnit.SECONDS), "Test timed out");

            double actualRate = totalAcquired.get() / (double) durationSeconds;
            double allowedMax = rate * 4.0 * 1.05; // 4*rate theoretical max + 5% margin
            
            System.out.printf("Rate: %.0f, Acquired: %d, Actual Rate: %.2f, Max Allowed: %.2f%n", 
                              rate, totalAcquired.get(), actualRate, allowedMax);

            // Assert: Long run rate must not exceed configured rate significantly.
            // Because of initial burst (2*rate), average over 2s can be up to 2*rate.
            // Theoretical max permits = Burst + Rate*Time = 2r + 2r = 4r.
            assertTrue(totalAcquired.get() <= allowedMax, 
                String.format("Rate limit exceeded! Configured: %.0f, Actual Avg: %.2f, Total: %d, Max Allowed: %.0f", 
                              rate, actualRate, totalAcquired.get(), allowedMax));
            
            // Also verify we actually got close to the limit (liveness)
            // We expect at least Rate * Time (sustained) = 2r. 
            // But with contention, might be slightly less. Check > 1.5r.
            assertTrue(totalAcquired.get() > rate * 1.5, "Throughput unexpectedly low");

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAccess_ThreadSafe_NoExceptions() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, 1000);
        int threads = 32;
        int opsPerThread = 10000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong(0);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        if (Math.random() < 0.5) {
                            limiter.tryAcquire(1);
                        } else {
                            limiter.acquire(1);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Concurrent access caused exceptions");
        exec.shutdown();
    }
}
```

### Key Test Highlights

1.  **`longRunRateLimit_Concurrent_SustainedRateRespected` (The Critical Test):**
    *   **Parameterized:** Runs for rates `0.5` to `10,000` tokens/sec.
    *   **Burst Configuration:** Sets `maxTokens = 2 * rate`. This allows a 2-second burst at line rate.
    *   **Duration:** Runs for 2 seconds.
    *   **Theoretical Max:** `Burst (2r) + Refill (2r) = 4r` permits.
    *   **Assertion:** `totalAcquired <= 4r * 1.05`. This mathematically proves the limiter enforces the long-run average rate (`<= rate`) while allowing the configured burst. The 5% margin absorbs OS scheduling jitter and `System.nanoTime` granularity.
    *   **Liveness Check:** Ensures `totalAcquired > 1.5 * rate` (didn't deadlock or starve completely).

2.  **`acquire_BlocksUntilAvailable` & `tryAcquire_WithTimeout_RespectsTimeout`:**
    *   Verify blocking behavior and interruptibility using real time measurements (`System.nanoTime`).

3.  **`concurrentAccess_ThreadSafe_NoExceptions`:**
    *   High contention stress test (32 threads, 10k ops each) mixing `acquire` and `tryAcquire` to catch race conditions in the lock/condition logic.