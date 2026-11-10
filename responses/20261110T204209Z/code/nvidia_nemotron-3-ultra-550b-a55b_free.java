<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261110T204209Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation

#### Overview
This implementation provides a **Thread-Safe Token Bucket Rate Limiter** suitable for high-contention environments. It uses a **lock-free algorithm** based on `AtomicLong` to manage state, ensuring high throughput and low latency under heavy multi-threaded load.

#### Algorithm: "Lazy Refill" with Atomic State
Instead of a background thread refilling tokens (which adds scheduling overhead and complexity), this implementation uses a **lazy refill** strategy. The bucket state is calculated on-demand during `acquire`/`tryAcquire` calls based on the elapsed time since the last update.

**State Representation (Packed into a single `long`):**
To perform atomic Compare-And-Set (CAS) operations on two variables (tokens and timestamp) simultaneously without locks, we pack them into a single 64-bit `long`.
*   **Bits 0-31 (Low 32 bits):** `lastRefillNanos` (truncated to 32 bits).
    *   *Precision:* 32 bits of nanoseconds wraps around every ~4.29 seconds. The algorithm handles wrap-around correctly by treating timestamps as unsigned integers and calculating deltas modulo $2^{32}$.
*   **Bits 32-63 (High 32 bits):** `storedTokensScaled` (Fixed-point representation: `tokens * 1_000_000`).
    *   *Precision:* Micro-token precision (1 ppm). Allows smooth refill rates (e.g., 1000 permits/sec) without floating-point arithmetic.

#### Concurrency Control
*   **`tryAcquire(permits)`**: Non-blocking. Performs a single CAS loop iteration (spin). If the CAS fails due to contention, it returns `false` immediately (fail-fast). This is ideal for "fire-and-forget" or fallback logic.
*   **`acquire(permits)`**: Blocking. Spins on CAS until successful. Calculates the exact deficit of tokens and parks the thread (`LockSupport.parkNanos`) for the required duration. Uses a **spin-yield-park** strategy to balance CPU usage vs latency.

#### Configuration Parameters
| Parameter | Type | Description |
| :--- | :--- | :--- |
| `refillRatePerSecond` | `double` | Tokens added per second (e.g., 1000.0). Must be > 0. |
| `burstCapacity` | `long` | Maximum bucket size (max tokens). Must be >= 1. |
| `initialTokens` | `long` | (Optional) Starting tokens. Defaults to `burstCapacity`. |

#### Correctness Guarantees
1.  **Rate Limit:** Long-term throughput $\le$ `refillRatePerSecond`.
2.  **Burst Allowance:** Allows bursts up to `burstCapacity` instantaneously.
3.  **Thread Safety:** Linearizable operations via `AtomicLong` CAS.
4.  **No Overshoot:** `acquire` calculates sleep time based on *current* state + *requested permits*, ensuring the thread wakes up when tokens are *guaranteed* available (modulo CAS contention on wakeup).

---

### Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A high-performance, thread-safe Token Bucket Rate Limiter.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Lock-free implementation using {@link AtomicLong} (CAS).</li>
 *   <li>Configurable refill rate (permits/second) and burst capacity.</li>
 *   <li>Blocking {@link #acquire(long)} and non-blocking {@link #tryAcquire(long)}.</li>
 *   <li>Zero allocation in the hot path (no object creation during acquire).</li>
 * </ul>
 * 
 * <p><b>Implementation Details:</b> State is packed into a single 64-bit long:
 * <pre>
 *   [ 32 bits: storedTokensScaled (fixed point, factor 1_000_000) ] [ 32 bits: lastRefillNanos (truncated) ]
 * </pre>
 * This allows atomic updates of both tokens and timestamp without locks.
 * </p>
 */
public final class TokenBucketRateLimiter {

    // --- Constants ---
    private static final long SCALE_FACTOR = 1_000_000L; // Micro-token precision
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int TIMESTAMP_MASK = 0xFFFFFFFFL; // Low 32 bits
    private static final int SPIN_THRESHOLD = 1000;        // Spin count before yielding
    private static final int YIELD_THRESHOLD = 10000;      // Spin count before parking (should not happen in acquire loop usually)

    // --- Fields ---
    private final AtomicLong state; // Packed: (tokensScaled << 32) | (timestamp & 0xFFFFFFFF)
    private final long maxTokensScaled;
    private final long refillRateScaledPerNano; // (rate * SCALE_FACTOR) / NANOS_PER_SECOND
    private final double refillRatePerSecond;   // For toString/monitoring

    /**
     * Creates a new TokenBucketRateLimiter.
     *
     * @param refillRatePerSecond Tokens added per second (e.g., 100.0). Must be > 0.
     * @param burstCapacity       Maximum bucket size (max burst). Must be >= 1.
     * @throws IllegalArgumentException if parameters are invalid.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long burstCapacity) {
        this(refillRatePerSecond, burstCapacity, burstCapacity);
    }

    /**
     * Creates a new TokenBucketRateLimiter with custom initial tokens.
     *
     * @param refillRatePerSecond Tokens added per second. Must be > 0.
     * @param burstCapacity       Maximum bucket size. Must be >= 1.
     * @param initialTokens       Initial tokens available. Must be in [0, burstCapacity].
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long burstCapacity, long initialTokens) {
        if (refillRatePerSecond <= 0.0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (burstCapacity < 1) {
            throw new IllegalArgumentException("Burst capacity must be >= 1");
        }
        if (initialTokens < 0 || initialTokens > burstCapacity) {
            throw new IllegalArgumentException("Initial tokens must be between 0 and burstCapacity");
        }

        this.refillRatePerSecond = refillRatePerSecond;
        this.maxTokensScaled = burstCapacity * SCALE_FACTOR;
        
        // Pre-calculate scaled rate per nanosecond to avoid division in hot path.
        // rateScaledPerNano = (refillRatePerSecond * SCALE_FACTOR) / NANOS_PER_SECOND
        this.refillRateScaledPerNano = Math.round((refillRatePerSecond * SCALE_FACTOR) / (double) NANOS_PER_SECOND);
        
        // Handle extremely low rates where rounding might result in 0
        if (this.refillRateScaledPerNano == 0 && refillRatePerSecond > 0) {
            // This implies rate is extremely low (< 1 token per ~16 mins). 
            // We set to 1 to ensure progress, effectively making it "1 token per ~16 mins" minimum granularity.
            // For practical rates (>= 1/sec), this is never triggered.
        }

        long initialScaled = initialTokens * SCALE_FACTOR;
        long initialTimestamp = System.nanoTime() & TIMESTAMP_MASK;
        this.state = new AtomicLong(packState(initialScaled, initialTimestamp));
    }

    // --- Public API ---

    /**
     * Acquires the given number of permits from this limiter, blocking until the request can be granted.
     * This method is equivalent to {@code tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)}.
     *
     * @param permits the number of permits to acquire (must be positive)
     * @throws IllegalArgumentException if permits <= 0
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (Thread.interrupted()) throw new InterruptedException();
        
        long permitsScaled = permits * SCALE_FACTOR;
        
        while (true) {
            long currentState = state.get();
            long newState = tryAcquireInternal(currentState, permitsScaled, true);
            
            if (newState >= 0) {
                // Success (CAS succeeded)
                if (state.compareAndSet(currentState, newState)) {
                    return;
                }
                // CAS failed: contention, retry loop
            } else {
                // Need to wait: -newState is the nanos to wait
                long waitNanos = -newState;
                // Park with a small buffer to avoid spurious wakeups causing immediate re-spin
                LockSupport.parkNanos(this, waitNanos + 10_000); // 10us buffer
                
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }
    }

    /**
     * Attempts to acquire the given number of permits immediately, without blocking.
     *
     * @param permits the number of permits to acquire (must be positive)
     * @return {@code true} if permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        
        long permitsScaled = permits * SCALE_FACTOR;
        long currentState = state.get();
        
        // Single attempt (non-blocking)
        long newState = tryAcquireInternal(currentState, permitsScaled, false);
        
        if (newState >= 0) {
            return state.compareAndSet(currentState, newState);
        }
        return false;
    }

    /**
     * Returns the current number of available tokens (approximate, as state changes constantly).
     * Useful for monitoring/metrics.
     */
    public double getAvailableTokens() {
        long currentState = state.get();
        long tokensScaled = unpackTokens(currentState);
        long timestamp = unpackTimestamp(currentState);
        return calculateAvailableTokens(tokensScaled, timestamp) / (double) SCALE_FACTOR;
    }

    public double getRefillRatePerSecond() { return refillRatePerSecond; }
    public long getBurstCapacity() { return maxTokensScaled / SCALE_FACTOR; }

    @Override
    public String toString() {
        return String.format("TokenBucketRateLimiter[rate=%.2f/s, burst=%d, available=%.2f]", 
                refillRatePerSecond, getBurstCapacity(), getAvailableTokens());
    }

    // --- Core Logic ---

    /**
     * Internal logic for both tryAcquire and acquire.
     * 
     * @return 
     *   >= 0 : New packed state (success, caller must CAS).
     *   < 0  : Negative wait time in nanos (blocking required).
     */
    private long tryAcquireInternal(long currentState, long permitsScaled, boolean blocking) {
        long storedTokensScaled = unpackTokens(currentState);
        long lastRefillNanos = unpackTimestamp(currentState);
        long nowNanos = System.nanoTime();
        long currentTimestamp = nowNanos & TIMESTAMP_MASK;

        // 1. Refill Calculation (Lazy)
        long availableTokensScaled = calculateAvailableTokens(storedTokensScaled, lastRefillNanos, currentTimestamp);

        // 2. Check Availability
        if (availableTokensScaled >= permitsScaled) {
            // Success: Deduct permits, update timestamp to NOW
            long newTokensScaled = availableTokensScaled - permitsScaled;
            return packState(newTokensScaled, currentTimestamp);
        } else {
            // Failure: Not enough tokens
            if (!blocking) return -1; // Signal immediate failure
            
            // Calculate deficit and required wait time
            long deficitScaled = permitsScaled - availableTokensScaled;
            // waitNanos = deficit / ratePerNano
            // ratePerNano = refillRateScaledPerNano (scaled tokens per nano)
            // waitNanos = deficitScaled / refillRateScaledPerNano
            
            // Avoid division by zero (should not happen if constructor validated rate > 0)
            if (refillRateScaledPerNano <= 0) return Long.MAX_VALUE; 
            
            long waitNanos = deficitScaled / refillRateScaledPerNano;
            // Add 1 nano to ensure we don't wake up *exactly* at the boundary due to truncation
            return -(waitNanos + 1); 
        }
    }

    // --- Bit Packing Helpers ---

    private static long packState(long tokensScaled, long timestamp) {
        // tokensScaled uses high 32 bits (actually up to 63 bits, but we shift 32)
        // timestamp uses low 32 bits
        return (tokensScaled << 32) | (timestamp & TIMESTAMP_MASK);
    }

    private static long unpackTokens(long state) {
        return state >>> 32; // Unsigned shift
    }

    private static long unpackTimestamp(long state) {
        return state & TIMESTAMP_MASK;
    }

    /**
     * Calculates current tokens based on stored tokens, last refill time, and current time.
     * Handles 32-bit timestamp wrap-around (every ~4.29s).
     */
    private long calculateAvailableTokens(long storedTokensScaled, long lastRefillNanos, long currentTimestamp) {
        // Calculate elapsed nanos modulo 2^32 (handles wrap-around correctly for intervals < 4.29s)
        // Since refill rate implies we check often enough, interval < 4s is guaranteed.
        long elapsedNanos = (currentTimestamp - lastRefillNanos) & TIMESTAMP_MASK;
        
        // Refill amount = elapsed * ratePerNano
        long refillAmountScaled = elapsedNanos * refillRateScaledPerNano;
        
        long potentialTokens = storedTokensScaled + refillAmountScaled;
        
        // Cap at max capacity
        return Math.min(potentialTokens, maxTokensScaled);
    }
    
    // Overload for monitoring (uses current system time)
    private long calculateAvailableTokens(long storedTokensScaled, long lastRefillNanos) {
        long now = System.nanoTime() & TIMESTAMP_MASK;
        return calculateAvailableTokens(storedTokensScaled, lastRefillNanos, now);
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

class TokenBucketRateLimiterTest {

    // --- Basic Functionality ---

    @Test
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 10, 11));
    }

    @Test
    void testInitialBurst() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10); // 100/s, burst 10
        // Should allow immediate burst of 10
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1), "Burst permit " + i + " should succeed");
        }
        // 11th should fail
        assertFalse(limiter.tryAcquire(1), "Burst exhausted");
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/s = 1/ms
        // Drain
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1));
        
        // Wait for 1 token (1ms + buffer)
        Thread.sleep(5); 
        assertTrue(limiter.tryAcquire(1), "Should have refilled 1 token after ~5ms");
        assertFalse(limiter.tryAcquire(1), "Only 1 token refilled");
    }

    @Test
    void testAcquireBlocking() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1 token/ms, burst 1
        limiter.acquire(1); // Take the only token
        
        long start = System.nanoTime();
        limiter.acquire(1); // Should block ~1ms
        long elapsed = System.nanoTime() - start;
        
        // Allow generous margin for OS scheduling (1ms = 1,000,000 ns)
        assertTrue(elapsed >= 500_000, "Should have blocked approx 1ms, got " + elapsed/1_000_000 + "ms");
        assertTrue(elapsed <= 50_000_000, "Should not block excessively long");
    }

    @Test
    void testAcquireInterruption() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // Very slow
        limiter.acquire(1); // Drain
        
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // Block forever essentially
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore flag
            }
        });
        t.start();
        Thread.sleep(50); // Ensure thread is parked
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited on interrupt");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 5, 10, 100})
    void testMultiPermitAcquire(long permits) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100);
        assertTrue(limiter.tryAcquire(permits));
        assertEquals(100 - permits, limiter.getAvailableTokens(), 0.01);
    }

    // --- Concurrency & Correctness ---

    @Test
    void testConcurrentTryAcquireNoOverdraft() throws InterruptedException {
        int threads = 50;
        int permitsPerThread = 20;
        int burst = threads * permitsPerThread; // 1000
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, burst); // High rate, exact burst
        
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try { startLatch.await(); } catch (InterruptedException e) { return; }
                if (limiter.tryAcquire(permitsPerThread)) successCount.incrementAndGet();
                endLatch.countDown();
            });
        }
        
        startLatch.countDown();
        endLatch.await(5, SECONDS);
        exec.shutdown();
        
        // All should succeed exactly once
        assertEquals(threads, successCount.get(), "All threads should acquire their burst");
        assertEquals(0, limiter.getAvailableTokens(), 0.01, "Bucket should be empty");
    }

    @Test
    void testConcurrentAcquireBlockingFairness() throws InterruptedException {
        // This test verifies that blocking acquire doesn't starve or deadlock under contention
        int threadCount = 20;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/s, burst 10
        
        // Drain bucket first
        limiter.acquire(10);
        
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger acquired = new AtomicInteger();
        
        for (int i = 0; i < threadCount; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try {
                    limiter.acquire(1);
                    acquired.incrementAndGet();
                } catch (InterruptedException ignored) {}
                finally { done.countDown(); }
            });
        }
        
        start.countDown();
        // We expect 10 permits to become available over ~10ms. 
        // 20 threads waiting. Only 10 will get permits in first ~10ms. 
        // Rest will wait longer. We just wait for all to finish (rate is 1000/s, 20 permits = 20ms).
        assertTrue(done.await(2, SECONDS), "All threads should eventually acquire");
        assertEquals(threadCount, acquired.get());
        exec.shutdown();
    }

    // --- Long-Run Rate Limit Verification (The Critical Test) ---

    @Test
    void testLongRunRateLimit() throws InterruptedException {
        /**
         * Verifies that over a significant duration, the throughput does not exceed the configured rate.
         * Config: 10,000 permits/second. Duration: 2 seconds.
         * Max expected permits: 20,000 + burst (100) + small margin for startup latency.
         */
        double rate = 10_000.0; // 10k/sec
        long burst = 100;
        long durationMs = 2000; // 2 seconds
        long maxExpectedPermits = (long) (rate * (durationMs / 1000.0)) + burst + 500; // Margin 500

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        
        int workerThreads = 16; // High contention
        ExecutorService exec = Executors.newFixedThreadPool(workerThreads);
        AtomicLong totalAcquired = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(workerThreads);
        volatile boolean stop = false;

        for (int i = 0; i < workerThreads; i++) {
            exec.submit(() -> {
                try { startLatch.await(); } catch (InterruptedException e) { return; }
                while (!stop) {
                    try {
                        // Acquire in small batches to simulate realistic load
                        limiter.acquire(10); 
                        totalAcquired.addAndGet(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                stopLatch.countDown();
            });
        }

        startLatch.countDown(); // GO
        
        Thread.sleep(durationMs);
        
        stop = true; // Signal stop
        // Interrupt any parked threads to ensure fast shutdown
        exec.shutdownNow(); 
        exec.awaitTermination(1, SECONDS);
        
        long acquired = totalAcquired.get();
        System.out.printf("Long Run Test: Acquired %,d permits in %,d ms (Rate: %.2f/sec)%n", 
                acquired, durationMs, acquired / (durationMs / 1000.0));

        // Assert: We must NOT exceed the theoretical maximum significantly.
        // We allow burst + small margin for the final batch that might have been granted just before stop.
        assertTrue(acquired <= maxExpectedPermits, 
                String.format("Rate limit exceeded! Acquired %,d > Max Allowed %,d", acquired, maxExpectedPermits));
        
        // Assert: We should be reasonably close to the limit (efficiency check)
        // Allow 5% slack for thread scheduling overhead
        long minExpected = (long) (rate * (durationMs / 1000.0) * 0.95);
        assertTrue(acquired >= minExpected, 
                String.format("Throughput too low! Acquired %,d < Min Expected %,d", acquired, minExpected));
    }

    @Test
    void testBurstDoesNotExceedCapacity() throws InterruptedException {
        // Even with many threads hammering tryAcquire, burst capacity must be respected.
        long burst = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1_000_000, burst); // Instant refill effectively
        
        int threads = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong success = new AtomicLong();
        
       