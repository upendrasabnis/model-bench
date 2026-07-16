<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses `java.util.concurrent.atomic.AtomicLong` to manage tokens. Instead of using a background thread to refill tokens (which is resource-intensive), this implementation uses a **lazy refill strategy**: it calculates the number of tokens that *should* have been added since the last request based on the elapsed time.

### 1. Implementation

```java
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design:
 * - Lazy Refill: Tokens are added based on time elapsed since the last request.
 *   This avoids the overhead of a background maintenance thread.
 * - Lock-free/Low-lock: Uses AtomicLong and a CAS (Compare-And-Set) loop 
 *   to ensure high concurrency without heavy synchronization.
 * - Capacity: Supports a 'burst' capacity (max tokens) and a 'efill rate' (tokens per second).
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillRatePerSecond;
    private final long refillIntervalNanos;

    // The current number of tokens available (scaled to nanoseconds for precision)
    // We store tokens as a long representing "available nanos of token" to handle 
    // fractional refills accurately without floating point drift in the state.
    // However, for simplicity and standard usage, we will use a double-based 
 *  // approach for the state or a long representing token count.
    // Here, we use a long for tokens and calculate elapsed time.
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillTimestampNanos;

    /**
     * @param capacity Max number of tokens (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        if (capacity < 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Capacity and rate must be positive");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.refillIntervalNanos = (long) (1_000_000_000L / refillRatePerSecond);
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillTimestampNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * 
     * @param tokens Number of tokens to acquire.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(long tokens) {
        refill();
        while (true) {
            long currentTokens = availableTokens.get();
            if (currentTokens < tokens) {
                return false;
            }
            if (availableTokens.compareAndSet(currentTokens, currentTokens - tokens)) {
                return true;
            }
            // CAS failed, loop and retry
        }
    }

    /**
     * Blocking attempt to acquire tokens. Waits until tokens are available.
     * 
     * @param tokens Number of tokens to acquire.
     * @return The number of tokens acquired (always returns 'tokens' if successful).
     */
    public void acquire(long tokens) {
        while (!tryAcquire(tokens)) {
            // Calculate wait time: how long until we have enough tokens?
            // To prevent busy-waiting, we use LockSupport.parkNanos.
            long current = availableTokens.get();
            if (current < tokens) {
                // Rough estimate of wait time to prevent CPU spinning
                long waitNanos = (long) ((tokens - current) * refillIntervalNanos);
                LockSupport.parkNanos(Math.max(1_000_000L, waitNanos)); 
            }
        }
    }

    /**
     * Updates the token count based on elapsed time.
     * Must be called before any acquire operation.
     */
    private void refill() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTimestampNanos.get();
        long elapsedNanos = now - lastRefill;

        if (elapsedNanos <= 0) return;

        // Calculate tokens to add: (elapsed nanos * rate) / 1e9
        long tokensToAdd = (long) (elapsedNanos * (refillRatePerSecond / 1_000_000_000.0));

        if (tokensToAdd > 0) {
            if (lastRefillTimestampNanos.compareAndSet(lastRefill, now)) {
                availableTokens.updateAndGet(current -> 
                    Math.min(capacity, current + tokensToAdd)
                );
            }
        }
    }
}
```

### 2. JUnit 5 Tests

You will need the `junit-jupiter` dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        
        // Consume 5 immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1), "Should allow token " + i);
        }
        
        // 6th should fail
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10.0);
        
        // Exhaust tokens
        limiter.acquire(10);
        assertFalse(limiter.tryAcquire(1));

        // Wait for 250ms -> should have ~2 tokens
        Thread.sleep(250);
        
        assertTrue(limiter.tryAcquire(2), "Should have refilled at least 2 tokens");
        assertFalse(limiter.tryAcquire(1), "Should not have 3rd token yet");
    }

    @Test
    @DisplayName("Concurrent Test: Long-run rate should match configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int capacity = 10;
        double ratePerSec = 100.0; // 100 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSec);

        int threadCount = 10;
        int durationSeconds = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Start multiple threads trying to acquire tokens as fast as possible
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Sync start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire(1)) {
                            totalAcquired.incrementAndGet();
                        } else {
                            // Small backoff to prevent CPU hogging in the test loop
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown(); // Release threads
        Thread.sleep(durationSeconds * 1000L + 500); // Run for duration + buffer
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        int expectedMax = (int) (ratePerSec * durationSeconds) + capacity;
        int actualAcquired = totalAcquired.get();

        // We check that we are within a reasonable margin of the expected rate
        // (Allowing for slight timing variations in Thread.sleep/System.nanoTime)
        double margin = expectedMax * 0.15; 
        assertTrue(actualAcquired <= expectedMax + margin, 
            "Acquired too many tokens: " + actualAcquired + " (Expected max ~" + expectedMax + ")");
        assertTrue(actualAcquired > (expectedMax - margin), 
            "Acquired too few tokens: " + actualAcquired + " (Expected min ~" + (expectedMax - margin) + ")");
    }
}
```

### Design Documentation

#### 1. Algorithm: Token Bucket
The Token Bucket algorithm allows for a certain amount of "burstiness." Tokens are added to the bucket at a fixed rate. If the bucket is full, new tokens are discarded. Each request consumes a token. If no tokens are available, the request is either rejected immediately (`tryAcquire`) or waits (`acquire`).

#### 2. Key Design Decisions
*   **Lazy Refill Strategy:** Instead of using a dedicated `ScheduledExecutorService` to add tokens every few milliseconds (which incurs context-switching overhead), this implementation calculates how many tokens *should* have been added by comparing the current `System.nanoTime()` with the `lastRefillTimestamp`. This is much more efficient for high-performance systems.
*   **Atomic State Management:** I used `AtomicLong` for both `availableTokens` and `lastRefillTimestampNanos`. This ensures that even if multiple threads call `refill()` simultaneously, only one thread succeeds in updating the timestamp and adding tokens, while others simply proceed with the current state.
*   **Precision:** Time is handled in nanoseconds to ensure that even at very high refill rates (e.g., 1,000,000 tokens/sec), the math remains accurate and doesn't suffer from integer division errors.
*   **Blocking Mechanism:** The `acquire` method uses `LockSupport.parkNanos`. This is a low-level primitive that puts the thread into a "waiting" state without consuming CPU cycles, unlike a `while(true)` loop which would cause 100% CPU usage.

#### 3. Complexity Analysis
*   **Time Complexity:** 
    *   `tryAcquire`: $O(1)$ (amortized, due to CAS loop).
    *   `acquire`: $O(1)$ (amortized, depending on wait time).
*   **Space Complexity:** $O(1)$ (only a few long variables are stored).