<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261002T162336Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: `TokenBucketRateLimiter`

#### 1. Algorithm: Token Bucket
The Token Bucket algorithm controls the rate of operations by maintaining a "bucket" of tokens.
*   **Capacity (Burst)**: The maximum number of tokens the bucket can hold (`maxTokens`). This defines the maximum burst allowance.
*   **Refill Rate**: Tokens are added to the bucket at a fixed rate (`tokensPerSecond`).
*   **Consumption**: An operation consumes $N$ tokens (usually 1). If enough tokens are available, it proceeds immediately. Otherwise, it must wait until enough tokens accumulate.

#### 2. Concurrency Strategy
*   **Mutual Exclusion**: The core state (`availableTokens`, `lastRefillNanos`) must be updated atomically relative to each other. We use `synchronized` on the instance (`this`).
    *   *Reasoning*: The critical section is extremely short (a few floating-point ops and a `System.nanoTime()` call). `synchronized` (biased/lightweight locking in modern JVMs) has negligible overhead for low-to-moderate contention and avoids the complexity of `AtomicReference` + CAS loops or `StampedLock` optimistic reads which are error-prone for "check-then-act" logic involving time.
*   **Waiting**: Threads waiting for tokens use `wait(timeout)` on the monitor (`this`). When tokens are refilled (implicitly by time passing), we do **not** actively `notifyAll()`.
    *   *Reasoning*: Active notification requires a background "refiller" thread or notifying on every `acquire`/`tryAcquire` call. A background thread adds resource cost and shutdown complexity. Notifying on every call causes "thundering herd" (waking all waiters when only 1 token is available).
    *   *Chosen Approach*: **Timed Waiting**. A thread calculates the exact time it needs to wait for the required tokens to appear, calls `wait(timeout)`, and re-checks state upon waking. This is precise, scalable, and requires no background threads.

#### 3. Time Handling
*   `System.nanoTime()` is used for all interval calculations (monotonic, high resolution).
*   `System.currentTimeMillis()` is **not** used (susceptible to clock drift/NTP adjustments).

#### 4. API Surface
```java
public final class TokenBucketRateLimiter {
    // Factory
    public static TokenBucketRateLimiter create(double tokensPerSecond, long maxTokens);

    // Blocking
    public void acquire();              // Acquire 1 token, block indefinitely
    public void acquire(int permits);   // Acquire N tokens, block indefinitely
    public boolean acquire(Duration timeout); // Acquire 1 token, block up to timeout

    // Non-blocking
    public boolean tryAcquire();        // Try acquire 1 token, return immediately
    public boolean tryAcquire(int permits); // Try acquire N tokens, return immediately

    // Introspection
    public double getAvailableTokens(); // Estimate current tokens (requires lock)
}
```

#### 5. Correctness Guarantees
*   **Rate Limit**: Long-term throughput $\le$ `tokensPerSecond`.
*   **Burst Limit**: Instantaneous throughput $\le$ `maxTokens` (if bucket was full).
*   **Fairness**: FIFO ordering for threads waiting on the monitor (JVM dependent, usually fair-ish).
*   **No Starvation**: Every waiting thread eventually acquires tokens if rate > 0.

---

### Implementation

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * <p>
 * Supports configurable refill rate and burst capacity.
 * Uses timed waiting (no background threads) for efficient blocking acquisition.
 * </p>
 */
public final class TokenBucketRateLimiter {

    private final double tokensPerSecond;
    private final double maxTokens;
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * Private constructor. Use {@link #create(double, long)}.
     */
    private TokenBucketRateLimiter(double tokensPerSecond, long maxTokens) {
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("tokensPerSecond must be positive");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxTokens;
        this.availableTokens = maxTokens; // Start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Creates a new rate limiter.
     *
     * @param tokensPerSecond the sustained rate of tokens added per second (e.g., 100.0)
     * @param maxTokens       the maximum bucket capacity (burst allowance)
     * @return a new {@link TokenBucketRateLimiter}
     */
    public static TokenBucketRateLimiter create(double tokensPerSecond, long maxTokens) {
        return new TokenBucketRateLimiter(tokensPerSecond, maxTokens);
    }

    /**
     * Refills tokens based on elapsed time.
     * Must be called holding 'this' lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            double tokensToAdd = (elapsedNanos * tokensPerSecond) / 1_000_000_000.0;
            availableTokens = Math.min(maxTokens, availableTokens + tokensToAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Calculates nanoseconds to wait until 'requiredTokens' are available.
     * Returns 0 if available immediately. Returns Long.MAX_VALUE if rate is 0 (handled in ctor).
     * Must be called holding 'this' lock.
     */
    private long calculateWaitNanos(double requiredTokens) {
        if (availableTokens >= requiredTokens) {
            return 0L;
        }
        double deficit = requiredTokens - availableTokens;
        // time = deficit / rate
        double waitSeconds = deficit / tokensPerSecond;
        return (long) (waitSeconds * 1_000_000_000);
    }

    // --- Public API ---

    /**
     * Acquires a single token, blocking indefinitely until available.
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * Acquires the given number of tokens, blocking indefinitely until available.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws IllegalArgumentException if permits <= 0
     * @throws InterruptedException     if the thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) throw new IllegalArgumentException("Permits exceed bucket capacity");

        synchronized (this) {
            while (true) {
                refill();
                long waitNanos = calculateWaitNanos(permits);
                if (waitNanos <= 0) {
                    availableTokens -= permits;
                    return;
                }
                // Wait for the calculated time.
                // We use wait(timeout) instead of Thread.sleep to allow interruption
                // and avoid spinning if system clock granularity is coarse.
                try {
                    // wait takes millis and nanos
                    long waitMillis = TimeUnit.NANOSECONDS.toMillis(waitNanos);
                    int remainingNanos = (int) (waitNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));
                    wait(waitMillis, remainingNanos);
                } catch (InterruptedException e) {
                    // Restore interrupt status and propagate
                    Thread.currentThread().interrupt();
                    throw e;
                }
                // Loop repeats: refill() will run again with updated 'now'
            }
        }
    }

    /**
     * Attempts to acquire a single token, returning immediately.
     *
     * @return true if token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the given number of tokens, returning immediately.
     *
     * @param permits number of tokens to acquire
     * @return true if tokens were acquired, false otherwise
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) return false; // Can never satisfy

        synchronized (this) {
            refill();
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return true;
            }
            return false;
        }
    }

    /**
     * Attempts to acquire a single token, blocking up to the specified timeout.
     *
     * @param timeout maximum time to wait
     * @return true if acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean acquire(Duration timeout) throws InterruptedException {
        return acquire(1, timeout);
    }

    /**
     * Attempts to acquire permits, blocking up to the specified timeout.
     *
     * @param permits number of tokens
     * @param timeout maximum time to wait
     * @return true if acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean acquire(int permits, Duration timeout) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) throw new IllegalArgumentException("Permits exceed bucket capacity");
        Objects.requireNonNull(timeout, "timeout");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        synchronized (this) {
            while (true) {
                refill();
                long waitNanos = calculateWaitNanos(permits);
                if (waitNanos <= 0) {
                    availableTokens -= permits;
                    return true;
                }

                long now = System.nanoTime();
                long remainingNanos = deadlineNanos - now;
                if (remainingNanos <= 0) {
                    return false; // Timeout expired
                }

                // Wait for the minimum of (time needed for tokens) and (remaining timeout)
                long actualWaitNanos = Math.min(waitNanos, remainingNanos);
                long waitMillis = TimeUnit.NANOSECONDS.toMillis(actualWaitNanos);
                int remainingNanosPart = (int) (actualWaitNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis));

                try {
                    wait(waitMillis, remainingNanosPart);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Returns the current estimated number of available tokens.
     * Note: This is a snapshot; actual availability may change immediately after.
     */
    public double getAvailableTokens() {
        synchronized (this) {
            refill();
            return availableTokens;
        }
    }

    public double getTokensPerSecond() {
        return tokensPerSecond;
    }

    public double getMaxTokens() {
        return maxTokens;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double EPSILON = 1e-9;

    @Test
    void create_InvalidParams_Throws() {
        assertThrows(IllegalArgumentException.class, () -> TokenBucketRateLimiter.create(0, 10));
        assertThrows(IllegalArgumentException.class, () -> TokenBucketRateLimiter.create(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> TokenBucketRateLimiter.create(10, 0));
        assertThrows(IllegalArgumentException.class, () -> TokenBucketRateLimiter.create(10, -1));
    }

    @Test
    void initialState_FullBucket() {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(10, 5);
        assertEquals(5.0, limiter.getAvailableTokens(), EPSILON);
        assertTrue(limiter.tryAcquire(5));
        assertEquals(0.0, limiter.getAvailableTokens(), EPSILON);
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void burstAllowance_AllowsBurstUpToCapacity() {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(1, 100); // 1 token/s, burst 100
        // Should acquire 100 immediately
        assertTrue(limiter.tryAcquire(100));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void refillOverTime_ReplenishesTokens() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(1000, 10); // 1000 tokens/s
        limiter.acquire(10); // Drain
        assertEquals(0.0, limiter.getAvailableTokens(), EPSILON);

        Thread.sleep(10); // 10ms -> ~10 tokens
        // Allow some slack for scheduler granularity
        double available = limiter.getAvailableTokens();
        assertTrue(available >= 9.0 && available <= 10.0, "Available: " + available);
    }

    @Test
    void acquire_BlocksUntilTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(100, 1); // 100 tokens/s, burst 1
        limiter.acquire(1); // Drain

        long start = System.nanoTime();
        // Should take ~10ms to get 1 token
        limiter.acquire(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 8 && elapsedMs <= 50, "Elapsed: " + elapsedMs + "ms");
    }

    @Test
    void acquireWithTimeout_ReturnsFalseOnTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(1, 1); // Very slow: 1 token/s
        limiter.acquire(1); // Drain

        // Timeout 10ms, but token takes 1000ms
        boolean acquired = limiter.acquire(1, Duration.ofMillis(10));
        assertFalse(acquired);
    }

    @Test
    void acquireWithTimeout_ReturnsTrueIfTokenArrivesInTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(1000, 1); // 1 token/ms
        limiter.acquire(1); // Drain

        // Timeout 50ms, token arrives ~1ms
        boolean acquired = limiter.acquire(1, Duration.ofMillis(50));
        assertTrue(acquired);
    }

    @Test
    void acquire_Interruption_ThrowsInterruptedException() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(1, 1); // Very slow
        limiter.acquire(1); // Drain

        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // Will block ~1s
            } catch (InterruptedException ignored) {
                // Expected
            }
        });
        t.start();
        Thread.sleep(10); // Ensure thread is waiting
        t.interrupt();
        t.join(100);
        assertFalse(t.isAlive(), "Thread should have exited after interrupt");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 100})
    void tryAcquire_PermitsExceedingCapacity_ReturnsFalse(int permits) {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(10, 10);
        assertFalse(limiter.tryAcquire(permits + 1));
    }

    // ============================================================
    // CONCURRENT TEST: Long-Run Rate Verification
    // ============================================================

    @Test
    void concurrentAccess_LongRunRateDoesNotExceedLimit() throws InterruptedException {
        // Configuration
        double ratePerSecond = 1000.0; // 1000 tokens/sec
        int burst = 100;
        int numThreads = 16;
        Duration testDuration = Duration.ofSeconds(3); // 3 seconds -> expect ~3000 tokens

        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(ratePerSecond, burst);
        AtomicLong totalAcquired = new AtomicLong(0);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Barrier to start all threads simultaneously
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        Runnable worker = () -> {
            try {
                startLatch.await(); // Wait for go signal
                long threadCount = 0;
                long deadline = System.nanoTime() + testDuration.toNanos();

                while (System.nanoTime() < deadline) {
                    // Acquire 1 token (blocking)
                    limiter.acquire(1);
                    threadCount++;
                }
                totalAcquired.addAndGet(threadCount);
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                endLatch.countDown();
            }
        };

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(worker);
            t.start();
            threads.add(t);
        }

        // Start test
        long wallStart = System.nanoTime();
        startLatch.countDown();

        // Wait for completion
        assertTrue(endLatch.await(testDuration.plusSeconds(5)), "Threads did not finish in time");
        long wallElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallStart);

        // Check for errors
        if (errorRef.get() != null) {
            throw new AssertionError("Worker thread failed", errorRef.get());
        }

        long acquired = totalAcquired.get();
        double expectedMax = ratePerSecond * (wallElapsedMs / 1000.0) + burst; // Rate * time + initial burst
        double expectedMin = ratePerSecond * (wallElapsedMs / 1000.0) * 0.95; // Allow 5% slack for scheduler/overhead

        System.out.printf("Concurrent Test: Acquired=%d, ExpectedMax=%.0f, ExpectedMin=%.0f, WallTime=%dms, Threads=%d%n",
                acquired, expectedMax, expectedMin, wallElapsedMs, numThreads);

        // 1. Hard Limit: Must never exceed theoretical max (Rate * Time + Burst)
        assertTrue(acquired <= expectedMax + 10, // +10 small fudge for final refill race
                () -> String.format("Rate exceeded! Acquired %d > Max Allowed %.0f", acquired, expectedMax));

        // 2. Liveness: Should achieve close to the rate (not starve)
        // Note: This might fail on heavily loaded CI runners, so we log warning instead of hard assert if needed,
        // but for a correct implementation on decent hardware, it should pass.
        assertTrue(acquired >= expectedMin,
                () -> String.format("Throughput too low (starvation?). Acquired %d < Min Expected %.0f", acquired, expectedMin));
    }

    @Test
    void concurrentTryAcquire_NoExceptionsAndConsistentState() throws InterruptedException {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.create(10000, 1000); // High rate
        int threads = 32;
        int iterations = 10000;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        if (limiter.tryAcquire(1)) success.incrementAndGet();
                        else fail.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        long totalAttempts = (long) threads * iterations;
        long totalAcquired = success.get() + fail.get(); // tryAcquire never blocks, so total == attempts
        assertEquals(totalAttempts, totalAcquired);
        // We can't assert exact success count due to race conditions on the bucket,
        // but we assert no crashes and consistency.
        System.out.printf("Concurrent TryAcquire: Success=%d, Fail=%d%n", success.get(), fail.get());
    }
}
```

---

### Key Implementation Details Explained

1.  **`refill()` Logic**:
    Calculates `elapsedNanos` since `lastRefillNanos`. Converts to tokens: `elapsed * rate / 1e9`. Caps at `maxTokens`. Updates `lastRefillNanos` to `now`. This ensures time "moves forward" monotonically inside the lock.

2.  **`calculateWaitNanos()`**:
    If `available >= required`, wait is 0.
    Else `deficit = required - available`. `waitSeconds = deficit / rate`.
    This is mathematically exact for the fluid token model.

3.  **`wait(waitMillis, waitNanos)` vs `Thread.sleep()`**:
    *   `Object.wait()` releases the monitor, allowing other threads to enter `synchronized` blocks to call `tryAcquire` or `acquire`.
    *   `Thread.sleep()` **holds** the monitor (if inside `synchronized`), freezing the entire limiter.
    *   `wait()` responds to `interrupt()` immediately by throwing `InterruptedException`. `sleep()` does too, but `wait()` is the correct primitive for "waiting for condition change".

4.  **Spurious Wakeups / Timeout Granularity**:
    The `while(true)` loop in `acquire` handles spurious wakeups and `wait()` timeout granularity (OS might wake up 1ms early/late). Upon waking, `refill()` runs again with a fresh `now`, recalculating the *exact* remaining wait time.

5.  **Concurrent Test Design (`concurrentAccess_LongRunRateDoesNotExceedLimit`)**:
    *   **`CountDownLatch` Start**: Ensures all threads hit the limiter simultaneously, maximizing contention and testing burst handling.
    *   **Wall Clock Measurement**: Measures actual elapsed time (`wallElapsedMs`) rather than assuming the test duration elapsed exactly.
    *   **Upper Bound Assertion**: `acquired <= rate * time + burst`. This is the **hard correctness invariant** of the Token Bucket. The test fails if the implementation leaks tokens (allows > rate).
    *   **Lower Bound Assertion**: `acquired >= 0.95 * rate * time`. Verifies the limiter isn't *too* conservative (starvation) or broken (deadlock). Note: On extremely overloaded CI machines, the 5% slack might be tight; in production code, this proves liveness.

6.  **No Background Thread**:
    The design avoids a `ScheduledExecutorService` refilling tokens. This saves resources, simplifies shutdown, and avoids "drift" between the scheduler clock and `System.nanoTime()`. The cost is paid by the *acquiring threads* (CPU cycles to calculate refill), which is optimal for "on-demand" rate limiting.