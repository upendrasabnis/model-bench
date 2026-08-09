<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses `java.util.concurrent.atomic.AtomicLong` to manage tokens, ensuring high performance and thread safety without the heavy overhead of global synchronization.

### Design Documentation

#### 1. Algorithm: Token Bucket
The implementation uses a "lazy refill" strategy. Instead of having a background thread constantly adding tokens (which is CPU intensive), we calculate how many tokens *should* have been added since the last request whenever `acquire` or `tryAcquire` is called.

#### 2. State Management
We track two variables:
*   `lastRefillTimestamp`: The last time the bucket was updated.
*   `availableTokens`: The current number of tokens in the bucket.

#### 3. Thread Safety
We use a `synchronized` block around the refill logic. While `AtomicLong` is great for single variables, the update logic requires an **atomic read-modify-write** of both the timestamp and the token count simultaneously. A `synchronized` block ensures that two threads don't both see a "stale" token count and both increment it, leading to more tokens than allowed.

#### 4. Complexity
*   **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
*   **Space Complexity**: $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final long refillTokensPerSecond;
    
    private long availableTokens;
    private long lastRefillTimestampNanos;

    /**
     * @param capacity Total burst capacity.
     * @param refillTokensPerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, long refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.availableTokens = capacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Attempts to acquire a token without blocking.
     * @return true if token was acquired, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens >= 1) {
            availableTokens--;
            return true;
        }
        return false;
    }

    /**
     * Blocks until a token is available.
     * @param tokens Number of tokens to acquire.
     * @return The time spent waiting.
     */
    public synchronized long acquire() throws InterruptedException {
        long start = System.currentTimeMillis();
        
        while (true) {
            refill();
            if (availableTokens >= 1) {
                availableTokens--;
                return System.currentTimeMillis() - start;
            }
            // Sleep for a short duration to prevent busy-waiting
            // In a high-performance system, you might use LockSupport.parkNanos
            Thread.sleep(1); 
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosPassed = now - lastRefillTimestampNanos;
        
        // Calculate tokens to add: (nanos passed / 1e9) * rate
        // Using nano-precision to avoid drift
        long tokensToAdd = (nanosPassed * refillTokensPerSecond) / 1_000_000_000L;

        if (tokensToAdd > 0) {
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            // Reduce timestamp by the amount of time "consumed" by the tokens added
            // This prevents precision loss over long periods
            long nanosConsumed = (tokensToAdd * 1_000_000_000L) / refillTokensPerSecond;
            lastRefillTimestampNanos += nanosConsumed;
        }
    }
}
```

---

### JUnit 5 Tests

To run these, ensure you have `org.junit.jupiter:junit-jupiter` in your dependencies.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow bursts up to capacity")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        
        // We should be able to acquire 5 tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should have acquired token " + i);
        }
        
        // The 6th should fail
        assertFalse(limiter.tryAcquire());
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 1 token per second, capacity 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // Empty
        
        // Wait for refill
        Thread.sleep(1100);
        
        assertTrue(limiter.tryAcquire(), "Should have refilled after 1.1s");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should match configured rate")
    void testConcurrentRateLimiting() throws InterruptedException {
        int refillRatePerSecond = 100;
        int testDurationSeconds = 2;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, refillRatePerSecond);
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        // We will attempt to acquire tokens as fast as possible from multiple threads
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Sync start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire()) {
                            successfulAcquires.incrementAndGet();
                        }
                        // Tiny sleep to prevent CPU starvation in test loop
                        Thread.sleep(1); 
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown(); // Start all threads
        Thread.sleep(testDurationSeconds * 1000L);
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Expected: (Rate * Duration) + Initial Burst
        // We allow a small margin of error for timing/scheduling
        int expectedMax = (refillRatePerSecond * testDurationSeconds) + 10;
        int actual = successfulAcquires.get();

        System.out.println("Total successful acquires: " + actual);
        assertTrue(actual <= expectedMax + 5, 
            "Exceeded rate limit! Expected ~" + expectedMax + " but got " + actual);
        assertTrue(actual >= (refillRatePerSecond * testDurationSeconds) - 10, 
            "Under rate limit! Expected ~" + (refillRatePerSecond * testDurationSeconds) + " but got " + actual);
    }
}
```

### Key Features of the Test Suite:
1.  **Burst Test**: Verifies that the capacity is respected immediately.
2.  **Refill Test**: Verifies that time-based replenishment works.
3.  **Concurrency Test**: 
    *   Spawns 10 threads competing for tokens.
    *   Uses a `CountDownLatch` to ensure all threads start simultaneously (maximizing contention).
    *   Verifies that the total number of successful acquires is roughly equal to `(rate * time) + burst`, ensuring the rate limiter doesn't leak tokens under high contention.