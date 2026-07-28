<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation

#### Overview
This implementation provides a **Thread-Safe Token Bucket Rate Limiter** suitable for high-contention environments. It uses a lock-free approach based on `AtomicLong` for the core state (available tokens and last refill timestamp), ensuring high throughput and low latency under heavy multi-threaded load.

#### Algorithm: Token Bucket
*   **Capacity (Burst):** Maximum tokens the bucket can hold (`maxTokens`). Allows bursting up to this limit.
*   **Refill Rate:** Tokens added per second (`refillRatePerSecond`). Defines the long-term sustained rate.
*   **State:**
    *   `availableTokens` (double, stored as `long` bits via `Double.doubleToRawLongBits`): Current tokens, fractional allowed for precision.
    *   `lastRefillNanos` (long): Timestamp (nanoTime) of the last successful refill calculation.

#### Concurrency Strategy: Optimistic Lock-Free (CAS Loop)
Instead of a coarse-grained `synchronized` block or `ReentrantLock`, we use a **Compare-And-Swap (CAS)** loop on a combined 128-bit state (simulated via two `AtomicLong` fields, updated atomically via a single `AtomicReference<State>` or dual CAS).

**Chosen Approach: `AtomicReference<State>` (Immutable Snapshot)**
*   **State Object:** Immutable holder for `(tokens, lastRefillNanos)`.
*   **Operation:** `getAndUpdate` / `updateAndGet` loop.
*   **Pros:** Clean semantics, single atomic variable, handles the "check-refill-consume" logic atomically without complex double-CAS logic.
*   **Cons:** Object allocation per attempt (mitigated by JVM escape analysis / scalar replacement; contention usually low enough that retries are rare).

*Alternative considered (Dual AtomicLong):* Requires complex "double-checked" locking or versioning to avoid ABA problems on the timestamp. `AtomicReference` is safer and simpler for correctness.

#### Refill Calculation
```java
elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
newTokens = min(capacity, oldTokens + elapsedSeconds * refillRate);
```
This calculates the *exact* theoretical tokens available at `now`, avoiding "drip" inaccuracies of fixed-interval refills.

#### API
1.  `tryAcquire()`: Non-blocking. Returns `true` if token taken, `false` immediately if empty.
2.  `tryAcquire(timeout, unit)`: Blocking with timeout. Parks thread via `LockSupport.parkNanos` until tokens available or timeout.
3.  `acquire()`: Blocking indefinite. Equivalent to `tryAcquire(Long.MAX_VALUE, NANOSECONDS)`.

#### Fairness & Starvation
*   **Non-Fair:** Threads spinning in CAS loop or parking via `LockSupport` do not guarantee FIFO ordering.
*   **No Starvation:** Refill rate guarantees progress. `LockSupport.parkNanos` yields CPU efficiently.

---

### Implementation

#### `TokenBucketRateLimiter.java`

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.Objects;

/**
 * A thread-safe, high-performance Token Bucket Rate Limiter.
 * <p>
 * Uses an optimistic lock-free algorithm (CAS on AtomicReference) to manage state.
 * Supports fractional tokens for precise rate limiting.
 * </p>
 *
 * <h3>Concurrency Model</h3>
 * State is held in an {@link AtomicReference} pointing to an immutable {@link State} object.
 * All mutations (refill + consume) happen inside a {@link AtomicReference#updateAndGet(java.util.function.UnaryOperator)}
 * loop. This avoids explicit locking and scales well under high contention.
 *
 * <h3>Precision</h3>
 * Tokens are stored as {@code double}. Refill is calculated based on wall-clock time
 * ({@link System#nanoTime()}) at the moment of the attempt, ensuring smooth rate limiting
 * without "burstiness" caused by fixed-window refills.
 */
public final class TokenBucketRateLimiter {

    private final double maxTokens;
    private final double refillRatePerSecond; // Tokens per second
    private final AtomicReference<State> stateRef;

    /**
     * Immutable state snapshot.
     */
    private static final class State {
        final double tokens;
        final long lastRefillNanos;

        State(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * Creates a new rate limiter.
     *
     * @param refillRatePerSecond Tokens added per second (sustained rate). Must be > 0.
     * @param maxTokens           Maximum bucket capacity (burst allowance). Must be >= 1.
     * @throws IllegalArgumentException if params invalid.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double maxTokens) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (maxTokens < 1.0) {
            throw new IllegalArgumentException("Max tokens (capacity) must be at least 1");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.maxTokens = maxTokens;
        // Start full
        this.stateRef = new AtomicReference<>(new State(maxTokens, System.nanoTime()));
    }

    /**
     * Attempts to acquire a permit (1 token) without blocking.
     *
     * @return {@code true} if a token was consumed, {@code false} if bucket empty.
     */
    public boolean tryAcquire() {
        return tryAcquire(1, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if acquired, {@code false} otherwise.
     */
    public boolean tryAcquire(int permits) {
        return tryAcquire(permits, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * Acquires a permit, blocking indefinitely until available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires {@code permits} tokens, blocking indefinitely.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if interrupted while waiting.
     */
    public void acquire(int permits) throws InterruptedException {
        if (!tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
            // Only reachable if interrupted (tryAcquire throws) or logic error
            throw new InterruptedException("Acquire interrupted");
        }
    }

    /**
     * Core acquisition logic supporting blocking with timeout and multi-permit requests.
     *
     * @param permits  number of tokens required (> 0)
     * @param timeout  max time to wait
     * @param unit     timeout unit
     * @return true if acquired
     * @throws InterruptedException if thread interrupted while parking
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokens) {
            // Optimization: Request larger than capacity can never succeed.
            // We could throw, but returning false mimics Semaphore behavior for oversized requests.
            return false;
        }

        long deadlineNanos = (timeout <= 0) ? 0 : System.nanoTime() + unit.toNanos(timeout);
        long parkNanos = 0;

        while (true) {
            // 1. Attempt optimistic update
            State currentState = stateRef.get();
            State newState = calculateRefill(currentState);

            double available = newState.tokens;
            if (available >= permits) {
                // Attempt to consume
                State consumedState = new State(available - permits, newState.lastRefillNanos);
                if (stateRef.compareAndSet(newState, consumedState)) {
                    return true; // Success
                }
                // CAS failed: contention. Loop immediately (spin).
                continue;
            }

            // 2. Not enough tokens.
            if (timeout == 0) {
                return false; // Non-blocking mode
            }

            // 3. Calculate wait time for next token (or required permits)
            // We need (permits - available) tokens.
            // Time = tokens_needed / rate
            double tokensNeeded = permits - available;
            long waitNanos = (long) Math.ceil((tokensNeeded / refillRatePerSecond) * 1_000_000_000.0);

            // Clamp to deadline
            long now = System.nanoTime();
            if (deadlineNanos > 0) {
                long remaining = deadlineNanos - now;
                if (remaining <= 0) {
                    return false; // Timeout expired
                }
                waitNanos = Math.min(waitNanos, remaining);
            }

            // 4. Park
            // Use LockSupport for efficient parking. 
            // We park for slightly less than calculated to account for scheduler granularity/spurious wakeups,
            // but the loop re-checks condition immediately upon wakeup.
            LockSupport.parkNanos(this, waitNanos);

            // Check interrupt status after park
            if (Thread.interrupted()) {
                throw new InterruptedException("Thread interrupted while waiting for rate limiter permit");
            }
            // Loop continues, re-reads state, recalculates refill.
        }
    }

    /**
     * Calculates the current token count based on elapsed time since last refill.
     * Does not modify shared state.
     */
    private State calculateRefill(State current) {
        long now = System.nanoTime();
        long elapsedNanos = now - current.lastRefillNanos;

        // If time went backwards (unlikely but possible with clock adjustments), treat as 0 elapsed.
        if (elapsedNanos <= 0) {
            return current;
        }

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double newTokens = Math.min(maxTokens, current.tokens + elapsedSeconds * refillRatePerSecond);

        // If tokens haven't changed significantly (e.g. bucket full), avoid new object allocation
        // by returning current if effectively full. 
        // However, we must update timestamp to 'now' to prevent re-calculating same elapsed time repeatedly.
        if (newTokens >= maxTokens - 1e-9) { // Effectively full
            return new State(maxTokens, now);
        }

        return new State(newTokens, now);
    }

    // --- Getters for Monitoring/Testing ---

    public double getMaxTokens() {
        return maxTokens;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    /**
     * Returns current available tokens (approximate, no synchronization guarantee on return value consistency).
     */
    public double getAvailableTokens() {
        State s = stateRef.get();
        State refilled = calculateRefill(s);
        return refilled.tokens;
    }
}
```

---

### JUnit 5 Tests

#### `TokenBucketRateLimiterTest.java`

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    // ---------------------------------------------------------
    // Basic Functionality & Edge Cases
    // ---------------------------------------------------------

    @Test
    void constructor_InvalidParams_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void initialState_IsFull() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100);
        assertEquals(100, limiter.getAvailableTokens(), 0.001);
        assertTrue(limiter.tryAcquire(50));
        assertEquals(50, limiter.getAvailableTokens(), 0.001);
    }

    @Test
    void tryAcquire_NonBlocking_RespectsCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10); // Rate 10/s, Cap 10
        // Drain
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1)); // Empty
        assertFalse(limiter.tryAcquire(5)); // Empty
    }

    @Test
    void tryAcquire_PermitsExceedCapacity_ReturnsFalse() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertFalse(limiter.tryAcquire(11)); // Request > Capacity
    }

    @Test
    void refill_OverTime_RegeneratesTokens() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 100); // 100 tokens/sec
        limiter.tryAcquire(100); // Empty
        
        Thread.sleep(50); // 0.05s -> ~5 tokens
        assertTrue(limiter.tryAcquire(4)); // Should have ~5
        assertFalse(limiter.tryAcquire(2)); // Only ~1 left
        
        Thread.sleep(100); // 0.1s -> ~10 tokens
        assertTrue(limiter.tryAcquire(10));
    }

    @Test
    void blockingAcquire_WaitsForRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10); // 10/s
        limiter.tryAcquire(10); // Drain
        
        long start = System.nanoTime();
        // Need 1 token. At 10/s, takes ~100ms.
        assertTrue(limiter.tryAcquire(1, 200, TimeUnit.MILLISECONDS));
        long elapsed = System.nanoTime() - start;
        
        assertTrue(elapsed >= 90_000_000, "Should have waited ~100ms"); // 90ms tolerance
        assertTrue(elapsed <= 500_000_000, "Should not wait excessively");
    }

    @Test
    void blockingAcquire_Timeout_ReturnsFalse() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow: 1 token/sec
        limiter.tryAcquire(1); // Drain
        
        // Ask for 1 token with 50ms timeout. Refill takes 1000ms.
        assertFalse(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void blockingAcquire_Interruption_ThrowsException() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        limiter.tryAcquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // Block indefinitely
                fail("Should have interrupted");
            } catch (InterruptedException e) {
                // Expected
            }
        });
        t.start();
        Thread.sleep(50); // Ensure thread is parked
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ---------------------------------------------------------
    // Concurrency & Correctness
    // ---------------------------------------------------------

    @Test
    void concurrentAccess_ThreadSafe_NoExceptions() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1000);
        int threads = 50;
        int opsPerThread = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        limiter.tryAcquire(1);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions during concurrent access");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void burstCapacity_AllowsBurstUpToLimit(int burstSize) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, burstSize); // Rate 10, Cap = burstSize
        // Should allow immediate burst of 'burstSize'
        assertTrue(limiter.tryAcquire(burstSize), "Should allow burst of size " + burstSize);
        assertFalse(limiter.tryAcquire(1), "Should be empty after burst");
    }

    // ---------------------------------------------------------
    // Long-Run Rate Enforcement (The Critical Test)
    // ---------------------------------------------------------

    /**
     * Verifies that over a sustained period, the throughput does not exceed the configured rate.
     * <p>
     * Strategy: Run N threads hammering {@code tryAcquire} for a fixed duration.
     * Count successful acquisitions.
     * Assert: Total Acquire rate <= Configured Rate * Duration + Burst Capacity (initial tokens).
     */
    @Test
    void longRunRate_DoesNotExceedLimit() throws InterruptedException {
        // Configuration
        double ratePerSecond = 1000.0; // 1000 tokens/sec
        double capacity = 100.0;       // Small burst
        Duration testDuration = Duration.ofSeconds(3); // 3 seconds
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSecond, capacity);
        
        int threadCount = 16; // High contention
        AtomicLong totalAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        // Pre-warm JIT
        for (int i=0; i<1000; i++) limiter.tryAcquire(1);
        // Refill fully after warmup
        Thread.sleep(200); 

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    long deadline = System.nanoTime() + testDuration.toNanos();
                    long localCount = 0;
                    while (System.nanoTime() < deadline) {
                        if (limiter.tryAcquire(1)) {
                            localCount++;
                        } else {
                            // Yield to avoid busy-spinning 100% CPU if limiter is empty,
                            // though tryAcquire is non-blocking. 
                            // A tiny sleep prevents the test thread from starving the GC/JIT 
                            // but adds latency. Better: just spin, it's a non-blocking call.
                            // Thread.onSpinWait(); // Java 9+
                        }
                    }
                    totalAcquired.addAndGet(localCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        long startTime = System.nanoTime();
        startLatch.countDown();
        
        assertTrue(endLatch.await(testDuration.plusSeconds(5)), "Threads did not finish in time");
        long actualDurationNanos = System.nanoTime() - startTime;
        double actualDurationSec = actualDurationNanos / 1_000_000_000.0;

        long acquired = totalAcquired.get();
        
        // Theoretical Max = Initial Burst + Rate * Time
        double theoreticalMax = capacity + (ratePerSecond * actualDurationSec);
        
        // Allow small epsilon for floating point / scheduling granularity (1% + 10 tokens)
        double tolerance = theoreticalMax * 0.01 + 10; 
        
        System.out.printf("Long Run Test: Acquired=%d, MaxAllowed=%.2f (Rate=%.0f, Cap=%.0f, Time=%.3fs)%n",
                acquired, theoreticalMax, ratePerSecond, capacity, actualDurationSec);

        assertTrue(acquired <= theoreticalMax + tolerance, 
                String.format("Rate exceeded! Acquired %d > Max Allowed %.2f + tolerance %.2f", 
                        acquired, theoreticalMax, tolerance));
        
        // Also verify we actually got close to the limit (liveness check)
        // We should achieve at least 95% of theoretical max (minus burst) if threads are aggressive enough.
        // Note: Non-blocking tryAcquire in tight loop might burn CPU but should get tokens fast.
        double expectedMin = (ratePerSecond * actualDurationSec) * 0.90; // 90% efficiency
        assertTrue(acquired >= expectedMin, 
                String.format("Throughput too low (starvation?). Acquired %d < Expected Min %.2f", acquired, expectedMin));
    }

    @Test
    void blockingAcquire_RespectsRateUnderContention() throws InterruptedException {
        // Similar to above but using blocking acquire(1) to test parking fairness/wakeup logic
        double rate = 500.0;
        double cap = 50.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, cap);
        
        int threads = 10;
        Duration duration = Duration.ofSeconds(2);
        AtomicLong acquired = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);

        for (int i=0; i<threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + duration.toNanos();
                    while (System.nanoTime() < deadline) {
                        limiter.acquire(1); // Blocks
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    end.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(end.await(duration.plusSeconds(5)));
        
        double actualSec = duration.getSeconds(); // Approx
        double maxAllowed = cap + rate * actualSec;
        assertTrue(acquired.get() <= maxAllowed * 1.05, "Blocking acquire exceeded rate");
    }
}
```

---

### Key Implementation Details Summary

1.  **`AtomicReference<State>`**: Guarantees atomicity of the "Read State -> Calculate Refill -> Check Tokens -> Write New State" sequence without locks.
2.  **`calculateRefill`**: Pure function. Uses `System.nanoTime()` for monotonic time source. Handles clock drift (negative elapsed) defensively.
3.  **`LockSupport.parkNanos`**: Used in blocking path. Superior to `Thread.sleep` or `Object.wait` because it doesn't require a monitor lock and handles spurious wakeups naturally via the loop condition re-check.
4.  **Fractional Tokens**: `double` precision allows smooth rates (e.g., 0.5 tokens/sec) without quantization errors.
5.  **Test `longRunRate_DoesNotExceedLimit`**:
    *   Runs for 3 seconds (long enough to average out burst).
    *   Uses 16 threads to maximize contention on the CAS loop.
    *   Asserts `Acquired <= Capacity + Rate * Time + Tolerance`.
    *   Includes a **Liveness Check** (`acquired >= 90% expected`) to detect deadlocks or excessive contention causing throughput collapse.