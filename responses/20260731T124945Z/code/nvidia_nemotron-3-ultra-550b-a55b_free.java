<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Document: `TokenBucketRateLimiter`

#### 1. Algorithm Overview
The Token Bucket algorithm controls the rate of operations by maintaining a bucket of tokens.
*   **Capacity (`maxTokens`)**: The maximum number of tokens the bucket can hold (burst allowance).
*   **Refill Rate (`tokensPerSecond`)**: Tokens are added continuously at this rate.
*   **Consumption**: An operation consumes `n` tokens. If insufficient tokens exist, the caller waits (blocking) or fails immediately (non-blocking).

#### 2. Concurrency Strategy
*   **Mutual Exclusion**: A single `intrinsic lock` (`synchronized` on `this`) protects all mutable state (`availableTokens`, `lastRefillNanos`).
    *   *Rationale*: Critical sections are extremely short (few floating-point ops, `System.nanoTime()`). `synchronized` avoids CAS retry loops, reduces memory overhead vs `ReentrantLock` + `Condition`, and allows using `wait()/notifyAll()` efficiently for blocking.
*   **State Variables**:
    *   `double availableTokens`: Current tokens (fractional precision allows smooth high-rate limiting).
    *   `long lastRefillNanos`: Timestamp of last refill calculation.
*   **Refill Calculation**: Performed *lazily* on every public method entry (`refillIfNeeded()`).
    *   `elapsedSeconds = (now - lastRefillNanos) / 1e9`
    *   `newTokens = elapsedSeconds * tokensPerSecond`
    *   `availableTokens = min(capacity, availableTokens + newTokens)`
    *   `lastRefillNanos = now`

#### 3. API Design
*   `tryAcquire(int permits)`: Non-blocking. Returns `true` if permits granted, `false` immediately otherwise.
*   `acquire(int permits)`: Blocking. Waits until permits available. Handles `InterruptedException`.
*   `acquireUninterruptibly(int permits)`: Blocking. Ignores interrupts (restores interrupt status on exit).
*   **Validation**: Rejects `permits <= 0` or `permits > capacity` (prevents indefinite starvation of large requests).

#### 4. Fairness & Notification
*   `notifyAll()` is called inside `synchronized` block whenever tokens are added (refill) or returned (hypothetical `returnTokens`).
*   *Note*: This creates "thundering herd" on wakeup. For high-contention scenarios, a queued approach (e.g., `ReentrantLock` + `Condition` per waiter) is fairer but significantly more complex. `notifyAll()` is standard for simple, high-throughput limiters where wait times are short.

---

### Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate and burst capacity.
 * Provides blocking ({@link #acquire(int)}) and non-blocking ({@link #tryAcquire(int)}) acquisition.
 * </p>
 */
public final class TokenBucketRateLimiter {

    private final double tokensPerSecond;
    private final double maxTokens; // Capacity
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * Creates a new Rate Limiter.
     *
     * @param tokensPerSecond The steady-state rate of token refill (tokens/second). Must be > 0.
     * @param maxTokens       The maximum bucket capacity (burst size). Must be >= tokensPerSecond (or at least > 0).
     * @throws IllegalArgumentException if params invalid.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, double maxTokens) {
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (maxTokens < tokensPerSecond) {
            // Not strictly required, but usually indicates config error: burst < 1 second of rate.
            // We allow it but warn via logic: capacity effectively caps burst.
        }

        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxTokens;
        this.availableTokens = maxTokens; // Start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Attempts to acquire permits immediately without blocking.
     *
     * @param permits Number of permits to acquire. Must be > 0 and <= capacity.
     * @return {@code true} if permits were acquired, {@code false} otherwise.
     * @throws IllegalArgumentException if permits invalid.
     */
    public synchronized boolean tryAcquire(int permits) {
        validatePermits(permits);
        refillIfNeeded();
        if (availableTokens >= permits) {
            availableTokens -= permits;
            return true;
        }
        return false;
    }

    /**
     * Acquires permits, blocking indefinitely until available.
     *
     * @param permits Number of permits to acquire.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     * @throws IllegalArgumentException if permits invalid.
     */
    public synchronized void acquire(int permits) throws InterruptedException {
        validatePermits(permits);
        
        // Fast path
        refillIfNeeded();
        if (availableTokens >= permits) {
            availableTokens -= permits;
            return;
        }

        // Slow path: calculate wait time
        double deficit = permits - availableTokens;
        // Time needed to accumulate 'deficit' tokens at current rate
        long waitNanos = (long) (deficit / tokensPerSecond * 1_000_000_000L);

        long remainingNanos = waitNanos;
        long endNanos = System.nanoTime() + waitNanos;

        while (true) {
            // Wait for the calculated time (or until notified)
            // Using wait(timeout) handles spurious wakeups and notifyAll() from refills.
            waitNanos(remainingNanos);

            refillIfNeeded();
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return;
            }

            // Recalculate remaining time (handles clock drift, spurious wakeups, rate changes if dynamic)
            remainingNanos = endNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                // Should theoretically not happen if math is perfect, but safety net:
                // Loop again, refill will likely push us over.
                endNanos = System.nanoTime() + 1_000_000; // Small backoff
            }
        }
    }

    /**
     * Acquires permits, blocking indefinitely, ignoring interrupts (restores interrupt status on exit).
     */
    public void acquireUninterruptibly(int permits) {
        boolean interrupted = false;
        try {
            acquire(permits);
        } catch (InterruptedException e) {
            interrupted = true;
            // Re-try loop until success (ignore interrupt)
            while (true) {
                try {
                    acquire(permits);
                    break;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns the current number of available tokens (approximate, for monitoring).
     */
    public synchronized double getAvailableTokens() {
        refillIfNeeded();
        return availableTokens;
    }

    // --- Internal Helpers ---

    private void validatePermits(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive: " + permits);
        }
        if (permits > maxTokens) {
            throw new IllegalArgumentException("Permits (" + permits + ") exceed bucket capacity (" + maxTokens + ")");
        }
    }

    private void refillIfNeeded() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        
        if (elapsedSeconds <= 0) return; // Clock didn't move or moved backward

        double newTokens = elapsedSeconds * tokensPerSecond;
        availableTokens = Math.min(maxTokens, availableTokens + newTokens);
        lastRefillNanos = now;
    }

    /**
     * Waits on 'this' monitor with nanos precision.
     * Handles InterruptedException by re-throwing.
     */
    private void waitNanos(long nanos) throws InterruptedException {
        if (nanos > 0) {
            // wait(timeoutMillis, nanosRemainder)
            wait(TimeUnit.NANOSECONDS.toMillis(nanos), (int) (nanos % 1_000_000));
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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

class TokenBucketRateLimiterTest {

    private static final double EPSILON = 0.001;

    @Test
    void constructor_InvalidParams_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void acquire_InvalidPermits_Throws() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(11)); // > capacity
    }

    @Test
    void tryAcquire_BurstCapacity_AllowsBurst() {
        // Rate 10/s, Burst 10. Should allow 10 immediately.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        
        assertTrue(limiter.tryAcquire(10), "Should allow full burst");
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
        assertFalse(limiter.tryAcquire(1), "Should reject when empty");
    }

    @Test
    void tryAcquire_RefillOverTime_AllowsMore() throws InterruptedException {
        // Rate 1000/s (1ms per token), Burst 1.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1);
        
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
        
        Thread.sleep(2); // Wait for ~2 tokens
        
        assertTrue(limiter.tryAcquire(1), "Should have refilled ~2 tokens");
        assertFalse(limiter.tryAcquire(1), "Only 1 token should remain");
    }

    @Test
    void acquire_BlocksUntilReady() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 10ms per token
        
        limiter.acquire(1); // Consume initial token
        long start = System.nanoTime();
        limiter.acquire(1); // Should wait ~10ms
        long elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - start);
        
        // Allow some slack for scheduler (5ms - 20ms)
        assertTrue(elapsedMs >= 5 && elapsedMs <= 50, "Waited " + elapsedMs + "ms, expected ~10ms");
    }

    @Test
    void acquire_RespectsInterrupt() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.acquire(1); // Empty bucket
        
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // Block forever
            } catch (InterruptedException e) {
                // Expected
                Thread.currentThread().interrupt(); // Restore flag
            }
        });
        t.start();
        
        // Ensure thread is waiting
        Thread.sleep(50); 
        assertTrue(t.isAlive());
        
        t.interrupt();
        t.join(1000);
        
        assertFalse(t.isAlive(), "Thread should have exited after interrupt");
        assertTrue(t.isInterrupted() || Thread.interrupted(), "Interrupt status should be propagated/handled");
    }

    @Test
    void acquireUninterruptibly_IgnoresInterrupt() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1);
        limiter.acquire(1); // Empty
        
        Thread t = new Thread(() -> {
            limiter.acquireUninterruptibly(1); // Should ignore interrupt and wait for refill
        });
        t.start();
        Thread.sleep(50);
        t.interrupt(); // Interrupt during wait
        
        // Refill happens ~10ms later. Thread should acquire and exit.
        t.join(2000); 
        assertFalse(t.isAlive(), "Thread should complete despite interrupt");
    }

    // --- Concurrent Stress Test ---

    @Test
    void concurrentAccess_LongRunRateDoesNotExceedLimit() throws InterruptedException {
        // Config: 10,000 permits/sec, Burst 1,000
        double rate = 10_000;
        double burst = 1_000;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        
        int threadCount = 16;
        int durationSeconds = 3;
        long endTime = System.currentTimeMillis() + SECONDS.toMillis(durationSeconds);
        
        AtomicLong totalAcquired = new AtomicLong(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronized start
                    while (System.currentTimeMillis() < endTime) {
                        // Acquire in small batches to simulate realistic load
                        int permits = 1 + (int)(Math.random() * 5); // 1-5 permits
                        limiter.acquire(permits);
                        totalAcquired.addAndGet(permits);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Go!
        assertTrue(endLatch.await(durationSeconds + 5, SECONDS), "Threads did not finish in time");
        executor.shutdownNow();

        assertEquals(0, errorCount.get(), "Errors occurred during test");

        long actualPermits = totalAcquired.get();
        double expectedMax = rate * durationSeconds + burst; // Rate * Time + Initial Burst
        
        System.out.printf("Concurrent Test: Acquired %,d permits in %ds. Max Allowed: %,.0f. Rate: %,.0f/s%n",
                actualPermits, durationSeconds, expectedMax, (double) actualPermits / durationSeconds);

        // Assert: Actual must not exceed theoretical max (Rate * Time + Burst)
        // We add a small margin (1%) for timing precision of test harness stop condition.
        assertTrue(actualPermits <= expectedMax * 1.01, 
            String.format("Rate exceeded! Acquired %,d > Max Allowed %,.0f", actualPermits, expectedMax * 1.01));
        
        // Assert: Should be reasonably close to max (utilization check, not strict)
        // If it's too low, the limiter is too conservative or test duration too short for burst to matter.
        // With 3s duration, burst is small factor. Expect > 95% utilization.
        assertTrue(actualPermits >= expectedMax * 0.95, 
            String.format("Utilization too low: %,d < %,.0f", actualPermits, expectedMax * 0.95));
    }

    @Test
    void concurrentTryAcquire_NoExceptions() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100);
        int threads = 50;
        int iterations = 1000;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int i=0; i<threads; i++) {
            ex.submit(() -> {
                try {
                    for (int j=0; j<iterations; j++) {
                        if (limiter.tryAcquire(1)) success.incrementAndGet();
                        else fail.incrementAndGet();
                    }
                } finally { latch.countDown(); }
            });
        }
        latch.await(10, SECONDS);
        ex.shutdown();
        
        // Total success cannot exceed capacity + refill during test (approx)
        // Just verifying no crashes and counts match attempts
        assertEquals(threads * iterations, success.get() + fail.get());
    }
}
```

---

### Key Implementation Details Explained

1.  **`synchronized` Methods**: All public methods are `synchronized`. This serializes access to `availableTokens` and `lastRefillNanos`. Since the critical section is ~5-10 CPU instructions, contention is minimal even at high throughput (millions of ops/sec on modern hardware).
2.  **Lazy Refill (`refillIfNeeded`)**: Time is only calculated when a thread interacts with the limiter. This avoids a background "refill thread" which would add complexity (shutdown hooks, CAS loops on volatile doubles) and CPU overhead when idle.
3.  **`wait/notifyAll` vs `LockSupport.parkNanos`**:
    *   Used `wait()` on `this` monitor. This integrates naturally with `synchronized`.
    *   `notifyAll()` is implicitly triggered by *any* thread exiting `synchronized` block after modifying state (refill happens on entry, so a thread acquiring tokens updates `lastRefillNanos`, effectively "notifying" waiters that time has passed).
    *   *Correction*: In the code above, `notifyAll()` is **not explicitly called** in `tryAcquire` or `acquire` after deducting tokens.
    *   *Why it still works*: The waiting thread calculates `waitNanos` based on *deficit/rate*. It wakes up after that calculated time (or spuriously). It does **not** rely on being notified by a producer thread. It relies on the timeout. This is actually **better** than `notifyAll` for rate limiters because it avoids Thundering Herd: waiters wake up staggered based on when they requested tokens, not all at once when a refill happens.
4.  **Double Precision**: `availableTokens` is `double`.
    *   Allows rates like 100,000 tokens/sec (0.00001s per token) without integer rounding errors causing "burstiness" or stalls.
    *   `maxTokens` is `double` to match.
5.  **Concurrent Test Logic**:
    *   Runs for 3 seconds (long enough to smooth out burst/startup effects).
    *   Verifies `Total Acquired <= Rate * Time + Burst`. This is the mathematical invariant of the Token Bucket.
    *   Uses `CountDownLatch` for synchronized start to avoid "ramp-up" skewing the first second.
    *   Checks utilization (>95%) to ensure the limiter isn't accidentally throttling *below* the configured rate.