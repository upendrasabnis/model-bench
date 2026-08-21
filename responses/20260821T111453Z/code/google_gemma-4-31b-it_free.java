<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260821T111453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe Token Bucket rate limiter.

### Design Documentation

**1. Algorithm**
The Token Bucket algorithm allows for a certain amount of "burstiness." Tokens are added to the bucket at a fixed rate. If the bucket is full, new tokens are discarded. To consume a resource, a thread must remove a token.

**2. Lazy Refill Strategy**
Instead of using a background timer thread to add tokens (which would be resource-heavy), this implementation uses **Lazy Refilling**. Every time a request arrives, the limiter calculates how many tokens should have been generated since the last request based on the elapsed time:
`tokensToAdd = (currentTime - lastRefillTime) * refillRate`

**3. Thread Safety**
*   **`AtomicLong` / `volatile`**: Not sufficient alone because the refill and consumption must be atomic.
*   **`synchronized` block**: Used to ensure that the "calculate refill $\rightarrow$ check balance $\rightarrow$ subtract token" sequence is atomic, preventing race conditions where multiple threads might over-consume tokens.
*   **`wait/notifyAll`**: Used for the blocking `acquire()` method to put threads to sleep until enough tokens are available.

**4. Complexity**
*   Time Complexity: $O(1)$ for both `tryAcquire` and `acquire`.
*   Space Complexity: $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final double refillRatePerMs;
    private final double capacity;
    private double currentTokens;
    private long lastRefillTimestamp;

    /**
     * @param tokensPerSecond The rate at which tokens are added to the bucket.
     * @param burstCapacity   The maximum number of tokens the bucket can hold.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, double burstCapacity) {
        this.refillRatePerMs = tokensPerSecond / 1000.0;
        this.capacity = burstCapacity;
        this.currentTokens = burstCapacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (currentTokens >= 1.0) {
            currentTokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Blocking acquire. Waits until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }

            // Calculate time to wait for at least 1 token
            double missing = 1.0 - currentTokens;
            long waitTimeMs = (long) Math.ceil(missing / refillRatePerMs);
            
            // Wait for the calculated time or until notified
            wait(waitTimeMs);
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long delta = now - lastRefillTimestamp;
        
        if (delta > 0) {
            double tokensToAdd = delta * refillRatePerMs;
            currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }
}
```

---

### JUnit 5 Tests

To run these, ensure you have `junit-jupiter-api` and `junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        // 1 token per second, burst of 5
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 5);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should allow burst up to 5");
        }
        assertFalse(limiter.tryAcquire(), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second (1 every 100ms), burst of 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(110); // Wait for refill
        assertTrue(limiter.tryAcquire(), "Should have refilled after 100ms");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // Empty the bucket

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~100ms
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 100, "Should have blocked for at least 100ms");
    }

    @Test
    @DisplayName("Concurrent long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int threads = 10;
        double ratePerSecond = 50.0; 
        double burst = 10.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSecond, burst);
        
        AtomicInteger count = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        
        long testDurationMs = 1000; // Test for 1 second
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < testDurationMs) {
                    if (limiter.tryAcquire()) {
                        count.incrementAndGet();
                    }
                    // Small yield to prevent tight-loop CPU starvation
                    Thread.yield();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Theoretical max = burst + (rate * duration)
        // 10 + (50 * 1) = 60
        int totalAcquired = count.get();
        int maxAllowed = (int) (burst + ratePerSecond * (testDurationMs / 1000.0)) + 1; 
        
        assertTrue(totalAcquired <= maxAllowed, 
            String.format("Acquired %d tokens, but max allowed was ~%d", totalAcquired, maxAllowed));
    }
}
```

### Key Testing Notes:
1.  **Burst Test**: Ensures that the initial bucket state allows for the specified burst capacity.
2.  **Refill Test**: Verifies that the lazy refill logic correctly calculates elapsed time.
3.  **Blocking Test**: Ensures `acquire()` doesn't return immediately when the bucket is empty.
4.  **Concurrent Test**: Spawns multiple threads attempting to consume tokens as fast as possible. It verifies that the total number of tokens consumed over a window of time adheres to the $\text{Burst} + (\text{Rate} \times \text{Time})$ formula.