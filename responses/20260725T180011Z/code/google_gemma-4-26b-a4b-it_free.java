<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260725T180011Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a **lock-free approach** utilizing `AtomicLong` to manage tokens. Instead of using a background thread to refill tokens (which is resource-intensive), we calculate the number of tokens to add based on the time elapsed since the last request.

### 1. Implementation: `TokenBucketRateLimiter.java`

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design:
 * - Uses "Lazy Refilling": Tokens are calculated based on time elapsed since the last access.
 * - Lock-free: Uses AtomicLong and a Compare-And-Swap (CAS) loop to ensure high performance 
 *   under contention without the overhead of synchronized blocks.
 * - Precision: Uses nanoseconds for high-precision timing.
 * 
 * @param capacity The maximum number of tokens the bucket can hold (burst capacity).
 * @param refillRatePerSecond The number of tokens added to the bucket per second.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillRatePerNano;
    
    // Stores the current number of tokens (scaled by 1e9 to handle fractional tokens via integer math)
    // We use a fixed-point representation where 1.0 token = 1,000,000,000 units.
    private final AtomicLong availableTokensScaled;
    private final AtomicLong lastRefillTimestampNanos;

    private static final long SCALE = 1_000_000_000L;

    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        if (capacity < 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Capacity and rate must be positive");
        }
        this.capacity = capacity;
        this.refillRatePerNano = refillRatePerSecond / SCALE;
        this.availableTokensScaled = new AtomicLong(capacity * SCALE);
        this.lastRefillTimestampNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * 
     * @param tokens Number of tokens to consume.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(long tokens) {
        long tokensToConsumeScaled = tokens * SCALE;
        
        while (true) {
            long now = System.nanoTime();
            refill(now);
            
            long currentTokensScaled = availableTokensScaled.get();
            if (currentTokensScaled < tokensToConsumeScaled) {
                return false;
            }
            
            if (availableTokensScaled.compareAndSet(currentTokensScaled, currentTokensScaled - tokensToConsumeScaled)) {
                return true;
            }
            // CAS failed, loop and retry
        }
    }

    /**
     * Blocking attempt to acquire tokens. Waits until tokens are available.
     * 
     * @param tokens Number of tokens to consume.
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return true if tokens were acquired, false if timeout occurred.
     */
    public boolean acquire(long tokens, long timeout, TimeUnit unit) throws InterruptedException {
        long nanosTimeout = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanosTimeout;

        while (System.nanoTime() < deadline) {
            if (tryAcquire(tokens)) {
                return true;
            }
            // Exponential backoff or simple sleep to prevent CPU spinning
            Thread.sleep(1); 
        }
        return false;
    }

    /**
     * Internal method to update the token count based on elapsed time.
     * Must be called within the CAS loop or before checking availability.
     */
    private void refill(long now) {
        long lastRefill = lastRefillTimestampNanos.get();
        long elapsedNanos = now - lastRefill;

        if (elapsedNanos <= 0) return;

        long tokensToAddScaled = (long) (elapsedNanos * refillRatePerNano);
        
        if (tokensToAddScaled > 0) {
            // Try to update the timestamp first to "claim" this time slice
            if (lastRefillTimestampNanos.compareAndSet(lastRefill, now)) {
                availableTokensScaled.updateAndGet(current -> {
                    long newTotal = current + tokensToAddScaled;
                    return Math.min(capacity * SCALE, newTotal);
                });
            }
        }
    }
}
```

---

### 2. Unit Tests: `TokenBucketRateLimiterTest.java`

Requires JUnit 5.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should respect burst capacity")
    void testBurstCapacity() {
        // Capacity 5, Refill 1/s
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);

        // Can take 5 immediately
        assertTrue(limiter.tryAcquire(5));
        // 6th should fail
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("Should refill over time")
    void testRefill() throws InterruptedException {
        // Capacity 1, Refill 10/s (1 token every 100ms)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0);

        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));

        // Wait 150ms -> should have ~1 token
        Thread.sleep(150);
        assertTrue(limiter.tryAcquire(1), "Should have refilled at least one token");
    }

    @Test
    @DisplayName("Blocking acquire should wait until tokens are available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0);
        
        assertTrue(limiter.tryAcquire(1)); // Empty the bucket
        
        long start = System.currentTimeMillis();
        boolean acquired = limiter.acquire(1, 500, TimeUnit.MILLISECONDS);
        long end = System.currentTimeMillis();

        assertTrue(acquired);
        assertTrue((end - start) >= 100, "Should have waited for refill");
    }

    @Test
    @DisplayName("Concurrent Test: Long-run rate should not exceed configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int refillRatePerSec = 100;
        int capacity = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRatePerSec);

        int threadCount = 10;
        int durationSeconds = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Track successful acquisitions across threads
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Sync start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire(1)) {
                            successfulAcquires.incrementAndGet();
                        }
                        // Small sleep to prevent infinite tight loop in test
                        Thread.sleep(1);
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown(); // Start all threads
        Thread.sleep(durationSeconds * 1000L);
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Expected: capacity (initial) + (refillRate * duration)
        // We allow a small margin of error for timing jitter
        int expectedMax = capacity + (refillRatePerSec * durationSeconds);
        int actual = successfulAcquires.get();

        // We check that it doesn't EXCEED the limit significantly
        // (Allowing +5 for scheduling jitter/rounding)
        assertTrue(actual <= expectedMax + 5, 
            String.format("Rate limit violated! Expected max ~%d, but got %d", expectedMax, actual));
        
        // Also ensure we actually performed work
        assertTrue(actual > 0, "Should have acquired some tokens");
    }
}
```

### Design Documentation

#### 1. Complexity & Performance
*   **Time Complexity**: `tryAcquire` is $O(1)$ (amortized). The CAS loop ensures that even under high contention, threads don't block each other using heavy OS-level mutexes, but rather spin briefly.
*   **Space Complexity**: $O(1)$. We only store a few `long` values.

#### 2. The "Lazy Refill" Strategy
Instead of having a background thread wake up every millisecond to add tokens (which wastes CPU cycles and causes context switching), this implementation calculates the "delta" of tokens at the moment a user requests them. 
*   $TokensToAdd = \Delta Time \times \text{RefillRate}$
*   To avoid floating-point errors and precision loss when dealing with very small rates, I used **Fixed-Point Arithmetic**. All token counts are internally stored as `long` values scaled by $10^9$ (representing nanoseconds). This allows us to treat "0.5 tokens" as "500,000,000 units," maintaining high precision.

#### 3. Concurrency Model
The implementation is **Lock-Free**. 
*   It uses `AtomicLong.compareAndSet` (CAS) to update the `availableTokensScaled`.
*   If two threads attempt to refill or consume tokens simultaneously, one will succeed in the CAS, and the other will fail, re-read the updated state, and retry. This is significantly faster than `synchronized` for high-throughput scenarios.

#### 4. Edge Cases Handled
*   **Bursting**: The `capacity` parameter limits the maximum tokens available at once, preventing a massive flood of requests after a period of inactivity.
*   **Precision**: Using `System.nanoTime()` ensures that even at very high refill rates (e.g., 1,000,000 tokens/sec), the timing is granular enough to prevent "clumping."
*   **Blocking/Non-blocking**: Provides both `tryAcquire` for high-performance non-blocking logic (e.g., returning `429 Too Many Requests`) and `acquire` for worker threads that should wait for capacity.