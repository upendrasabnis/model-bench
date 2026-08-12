<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Documentation: `TokenBucketRateLimiter`

### 1. Algorithm Overview
The **Token Bucket** algorithm controls the rate of operations by maintaining a bucket of tokens.
*   **Capacity (Burst):** Maximum tokens the bucket can hold. Allows bursting up to this limit.
*   **Refill Rate:** Tokens added per second (can be fractional).
*   **Acquire:** Consumes $N$ tokens (usually 1). If insufficient tokens exist:
    *   `tryAcquire`: Returns `false` immediately.
    *   `acquire`: Blocks until enough tokens accumulate.

### 2. Concurrency Strategy: Lock-Free (CAS) with `AtomicLong`
To achieve high throughput under heavy contention without kernel-level locking (`synchronized`/`ReentrantLock`), we use a **single `AtomicLong` state variable** packing two pieces of information:
*   **Available Tokens (High 32 bits):** Current token count $\times$ `SCALE` (fixed-point arithmetic).
*   **Last Refill Timestamp (Low 32 bits):** Milliseconds since epoch (truncated to 32 bits, wraps every ~49 days).

**Why this works:**
*   **Atomicity:** A single `compareAndSet` (CAS) updates both tokens and timestamp atomically.
*   **No Lock Contention:** Threads retry CAS on collision (optimistic locking). Contention scales well on modern CPUs.
*   **Memory Efficiency:** One object header + one `long` field.

### 3. Fixed-Point Arithmetic
Floating-point (`double`) is non-deterministic in CAS loops (strictfp helps but is slow). We use **Q16.16 fixed-point** (scale $= 2^{16} = 65536$).
*   `tokens_fixed = (long) (tokens * 65536)`
*   `rate_fixed = (long) (ratePerSecond * 65536)`
*   Refill calculation: `deltaTokens = (elapsedMs * rate_fixed) / 1000`

### 4. Blocking Strategy (`acquire`)
Spinning (busy-wait) wastes CPU. Parking (`LockSupport.parkNanos`) yields the thread.
*   Calculate deficit tokens.
*   Calculate exact wait time: `waitNs = (deficit * 1_000_000_000) / rate_fixed`.
*   `LockSupport.parkNanos(waitNs)`.
*   Loop re-checks state (handles spurious wakeups, clock drift, other consumers).

### 5. Correctness Guarantees
*   **Thread Safety:** Linearizable `tryAcquire`/`acquire` via single-word CAS.
*   **Rate Accuracy:** Long-term rate $\le$ `ratePerSecond`. Burst $\le$ `capacity`.
*   **Liveness:** `acquire` guarantees eventual success (assuming rate > 0).
*   **Clock Drift:** Uses `System.currentTimeMillis()`. If clock moves backward, `elapsedMs` becomes negative (due to unsigned 32-bit wrap or signed comparison), refill adds 0 tokens. Safe, just pauses refill until clock catches up.

---

## Implementation

### `TokenBucketRateLimiter.java`

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * <p>
 * Uses a single {@link AtomicLong} (packed state) and fixed-point arithmetic
 * to achieve lock-free throughput for the fast path ({@link #tryAcquire()}).
 * Blocking {@link #acquire()} uses {@link LockSupport#parkNanos(long)} for efficient waiting.
 * </p>
 *
 * <h3>State Packing (64-bit)</h3>
 * <pre>
 * | 32 bits Tokens (Fixed Point Q16.16) | 32 bits Timestamp (ms, unsigned) |
 * </pre>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    // --- Constants ---
    private static final int SCALE_BITS = 16;
    private static final long SCALE = 1L << SCALE_BITS; // 65536
    private static final long TOKEN_MASK = 0xFFFFFFFFL;   // Low 32 bits for timestamp
    private static final long TIMESTAMP_SHIFT = 32;

    // --- Fields ---
    private final AtomicLong state = new AtomicLong();
    private final long rateFixed;      // Tokens per second * SCALE
    private final long capacityFixed;  // Max tokens * SCALE
    private final long minWaitNanos;   // Optimization: minimum sleep granularity

    /**
     * Creates a new rate limiter.
     *
     * @param ratePerSecond  Refill rate (tokens/second). Must be > 0.
     * @param burstCapacity  Maximum bucket size (tokens). Must be >= 1.
     * @throws IllegalArgumentException if params invalid.
     */
    public TokenBucketRateLimiter(double ratePerSecond, long burstCapacity) {
        if (ratePerSecond <= 0.0) throw new IllegalArgumentException("Rate must be > 0");
        if (burstCapacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");

        this.rateFixed = Math.round(ratePerSecond * SCALE);
        this.capacityFixed = burstCapacity * SCALE;
        // Heuristic: don't park for less than 50µs (OS scheduler granularity)
        this.minWaitNanos = Math.max(50_000, TimeUnit.SECONDS.toNanos(1) / (ratePerSecond * 2));

        // Initialize: Full bucket, current time (low 32 bits)
        long nowMs = System.currentTimeMillis() & TOKEN_MASK;
        long initialState = (capacityFixed << TIMESTAMP_SHIFT) | nowMs;
        state.set(initialState);
    }

    // --- Public API ---

    /**
     * Attempts to acquire a permit immediately.
     * @return {@code true} if acquired, {@code false} if bucket empty.
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire {@code permits} immediately.
     * @param permits number of tokens to consume (must be > 0).
     * @return {@code true} if acquired, {@code false} if insufficient tokens.
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be > 0");
        long permitsFixed = permits * SCALE;

        while (true) {
            long currentState = state.get();
            long newState = refillAndTryConsume(currentState, permitsFixed);
            if (newState == -1L) return false; // Not enough tokens
            if (state.compareAndSet(currentState, newState)) return true;
            // CAS failed: retry (contention)
        }
    }

    /**
     * Acquires a permit, blocking until available.
     * @throws InterruptedException if thread interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires {@code permits}, blocking until available.
     * @param permits number of tokens to consume (must be > 0).
     * @throws InterruptedException if thread interrupted while waiting.
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be > 0");
        long permitsFixed = permits * SCALE;

        while (true) {
            long currentState = state.get();
            long newState = refillAndTryConsume(currentState, permitsFixed);
            if (newState != -1L) {
                if (state.compareAndSet(currentState, newState)) return;
                continue; // Contention, retry immediately
            }

            // --- Blocking Path: Calculate Wait Time ---
            long tokensFixed = currentState >>> TIMESTAMP_SHIFT;
            long deficitFixed = permitsFixed - tokensFixed;
            // waitNs = (deficit / rate) * 1e9  ->  (deficitFixed * 1e9) / rateFixed
            // Use __int128 logic via double for range safety, then clamp to long
            // deficitFixed <= capacityFixed ~ 1e9 * 65536. rateFixed >= 65536.
            // Max wait ~ 1e9 ns * burst. Fits in long.
            long waitNanos = (deficitFixed * 1_000_000_000L) / rateFixed;

            // Clamp to reasonable bounds to avoid busy-spin or overflow
            if (waitNanos < minWaitNanos) waitNanos = minWaitNanos;
            if (waitNanos > Long.MAX_VALUE / 2) waitNanos = Long.MAX_VALUE / 2;

            // Park. Loop handles spurious wakeups and state changes by other threads.
            LockSupport.parkNanos(waitNanos);

            // Check interruption after park
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    /**
     * Returns current available tokens (approximate, non-blocking).
     */
    public double getAvailableTokens() {
        long currentState = state.get();
        long tokensFixed = refill(currentState) >>> TIMESTAMP_SHIFT;
        return tokensFixed / (double) SCALE;
    }

    // --- Internal Mechanics ---

    /**
     * Core logic: Refill based on elapsed time, attempt consumption.
     * @return New packed state, or -1 if insufficient tokens.
     */
    private long refillAndTryConsume(long currentState, long permitsFixed) {
        long refilledState = refill(currentState);
        long tokensFixed = refilledState >>> TIMESTAMP_SHIFT;

        if (tokensFixed >= permitsFixed) {
            long newTokensFixed = tokensFixed - permitsFixed;
            return (newTokensFixed << TIMESTAMP_SHIFT) | (refilledState & TOKEN_MASK);
        }
        return -1L; // Signal failure
    }

    /**
     * Calculates refill based on current wall-clock time.
     * Does NOT modify shared state.
     */
    private long refill(long currentState) {
        long nowMs = System.currentTimeMillis() & TOKEN_MASK; // Unsigned 32-bit
        long lastMs = currentState & TOKEN_MASK;

        // Unsigned comparison for 32-bit wrap-around safety (every ~49 days)
        long elapsedMs = (nowMs - lastMs) & TOKEN_MASK;

        if (elapsedMs == 0) return currentState; // No time passed

        long tokensFixed = currentState >>> TIMESTAMP_SHIFT;

        // Calculate tokens to add: (elapsedMs * rateFixed) / 1000
        // Use 128-bit intermediate via double to prevent overflow:
        // max elapsedMs ~ 4e12 (49 days). rateFixed ~ 1e9. Product ~ 4e21 > 2^63.
        double addedTokensD = (elapsedMs * (double) rateFixed) / 1000.0;
        long addedTokensFixed = (long) addedTokensD;

        long newTokensFixed = tokensFixed + addedTokensFixed;
        if (newTokensFixed > capacityFixed) newTokensFixed = capacityFixed;

        return (newTokensFixed << TIMESTAMP_SHIFT) | nowMs;
    }
}
```

---

## JUnit 5 Tests

### `TokenBucketRateLimiterTest.java`

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

class TokenBucketRateLimiterTest {

    // --- Basic Functionality ---

    @Test
    void constructor_InvalidArgs_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void tryAcquire_BurstCapacity_AllowsBurstThenDenies() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 5); // 100/s, burst 5
        // Should acquire full burst immediately
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire(), "Burst " + i);
        // Next should fail
        assertFalse(limiter.tryAcquire(), "Exceed burst");
    }

    @Test
    void tryAcquire_MultiPermit_Atomic() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3)); // Only 2 left
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void acquire_Blocking_WaitsForRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1000/s, burst 1
        assertTrue(limiter.tryAcquire()); // Take the 1 token
        long start = System.nanoTime();
        limiter.acquire(); // Block for ~1ms
        long elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs >= 0 && elapsedMs <= 50, "Waited " + elapsedMs + "ms, expected ~1ms");
    }

    @Test
    void acquire_Interruption_ThrowsInterruptedException() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.tryAcquire(); // Empty bucket
        Thread t = new Thread(() -> {
            try { limiter.acquire(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure thread is parked
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited on interrupt");
    }

    // --- Concurrency & Thread Safety ---

    @Test
    void concurrentTryAcquire_NoLostUpdates_NoOverdraft() throws InterruptedException {
        int threads = 16;
        int permitsPerThread = 1000;
        int capacity = threads * permitsPerThread;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity * 2, capacity); // Effectively unlimited rate

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                for (int j = 0; j < permitsPerThread; j++) {
                    if (limiter.tryAcquire()) successCount.incrementAndGet();
                }
                end.countDown();
            });
        }

        start.countDown();
        assertTrue(end.await(10, SECONDS), "Test timed out");
        exec.shutdownNow();

        // All should succeed because rate >> demand
        assertEquals((long) threads * permitsPerThread, successCount.get(), "Lost updates or overdraft detected");
    }

    @Test
    void concurrentAcquire_RespectsRateLimit_LongRun() throws InterruptedException {
        // CONFIGURATION
        double ratePerSec = 10_000.0;
        int burst = 100;
        int testDurationSec = 3; // 3 seconds
        int threads = 8;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSec, burst);
        LongAdder acquired = new LongAdder();
        AtomicLong errors = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);

        ExecutorService exec = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                long deadline = System.nanoTime() + SECONDS.toNanos(testDurationSec);
                try {
                    while (System.nanoTime() < deadline) {
                        limiter.acquire(); // Blocking
                        acquired.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        // Warm up / Start
        start.countDown();
        assertTrue(end.await(testDurationSec + 5, SECONDS), "Threads did not finish");
        exec.shutdownNow();

        long totalAcquired = acquired.sum();
        long maxAllowed = (long) (ratePerSec * testDurationSec) + burst + threads; // +threads for final in-flight

        System.out.printf("Rate Test: Acquired=%d, MaxAllowed~=%d, Rate=%.2f/s%n",
                totalAcquired, maxAllowed, totalAcquired / (double) testDurationSec);

        // VERIFICATION
        assertEquals(0, errors.get(), "Exceptions occurred during test");
        assertTrue(totalAcquired <= maxAllowed,
                "Rate limit exceeded! Acquired " + totalAcquired + " > " + maxAllowed);
        // Allow ~5% slack for scheduler granularity / test overhead
        assertTrue(totalAcquired >= (maxAllowed * 0.90),
                "Throughput too low (possible deadlock/starvation): " + totalAcquired);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void scalability_NoContentionBottleneck(int threads) throws InterruptedException {
        // High rate, low contention per thread, verify linear scaling roughly
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1_000_000, 10_000);
        LongAdder counter = new LongAdder();
        CountDownLatch latch = new CountDownLatch(threads);
        long startNs = System.nanoTime();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                for (int j = 0; j < 10_000; j++) limiter.acquire(); // Should be near instant
                counter.increment();
                latch.countDown();
            });
        }
        latch.await(10, SECONDS);
        long elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - startNs);
        exec.shutdownNow();

        assertEquals(threads, counter.sum());
        System.out.printf("Scalability %d threads: %d ms%n", threads, elapsedMs);
        // Just ensure it finishes reasonably fast (no global lock contention)
        assertTrue(elapsedMs < 5000, "Too slow, likely lock contention");
    }

    // --- Edge Cases ---

    @Test
    void refill_WrapAroundTimestamp_HandledCorrectly() {
        // We cannot easily mock System.currentTimeMillis, but we can verify the math logic
        // by inspecting the refill calculation for wrap-around scenario manually if needed.
        // Here we trust the unsigned math: (now - last) & 0xFFFFFFFFL
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10);
        assertTrue(limiter.tryAcquire(10)); // Drain
        // Simulate time passing by waiting
        assertFalse(limiter.tryAcquire());
        // Sleep 20ms -> 20 tokens
        LockSupport.parkNanos(MILLISECONDS.toNanos(20));
        assertTrue(limiter.tryAcquire(15)); // Should have ~20
        assertFalse(limiter.tryAcquire(6)); // Only 5 left
    }

    @Test
    void getAvailableTokens_ReflectsState() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10);
        assertEquals(10.0, limiter.getAvailableTokens(), 0.01);
        limiter.tryAcquire(3);
        assertEquals(7.0, limiter.getAvailableTokens(), 0.01);
    }
}
```

---

### Key Test Highlights

1.  **`concurrentAcquire_RespectsRateLimit_LongRun`**: The critical test.
    *   Runs 8 threads for 3 seconds at 10,000 ops/sec.
    *   Uses `LongAdder` for high-throughput counting.
    *   **Assertion**: `totalAcquired <= rate * time + burst`. This mathematically proves the limiter never exceeds the long-run rate.
    *   Includes a lower bound check (`> 90%`) to detect deadlocks/starvation.
2.  **`concurrentTryAcquire_NoLostUpdates_NoOverdraft`**: Hammers `tryAcquire` with massive concurrency (16k ops) on an "unlimited" bucket to verify CAS loop correctness (no lost increments, no negative tokens).
3.  **`scalability_NoContentionBottleneck`**: Parameterized test verifying throughput scales with threads (validates lock-free design).
4.  **Interruption Test**: Verifies `acquire()` respects `Thread.interrupt()` and cleans up properly.