<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260914T143930Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### 1. Algorithm Overview
The **Token Bucket** algorithm allows for a certain amount of "burstiness" while maintaining a strict long-term average rate.
- **Bucket Capacity**: The maximum number of tokens the bucket can hold. This defines the maximum burst size.
- **Refill Rate**: The number of tokens added to the bucket per second.
- **Acquire**: To perform an action, a thread must remove a token from the bucket. If the bucket is empty, the thread must either wait (blocking) or fail (non-blocking).

#### 2. Implementation Strategy
Instead of using a background timer thread to add tokens (which is resource-intensive), this implementation uses **Lazy Refill**. The number of tokens is calculated based on the time elapsed since the last request:
$$\text{New Tokens} = (\text{Current Time} - \text{Last Refill Time}) \times \text{Refill Rate}$$

#### 3. Concurrency and Thread Safety
- **`synchronized` blocks**: Used to ensure that the state (token count and last refill timestamp) is updated atomically.
- **`wait()` and `notifyAll()`**: Used to implement the blocking `acquire()` method. When a thread finds the bucket empty, it calculates the time until the next token is available and sleeps.

#### 4. Time and Space Complexity
- **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
- **Space Complexity**: $O(1)$ as it only stores a few primitive variables regardless of the rate.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final double refillRatePerSecond;
    private final double capacity;
    
    private double availableTokens;
    private long lastRefillTimestamp;

    /**
     * @param refillRate Tokens added per second.
     * @param capacity Maximum tokens the bucket can hold (burst size).
     */
    public TokenBucketRateLimiter(double refillRate, double capacity) {
        this.refillRatePerSecond = refillRate;
        this.capacity = capacity;
        this.availableTokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        double deltaSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        double tokensToAdd = deltaSeconds * refillRatePerSecond;
        
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Blocking call to acquire a token.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return;
            }

            // Calculate wait time until at least 1 token is available
            double missingTokens = 1.0 - availableTokens;
            long waitMillis = (long) Math.ceil((missingTokens / refillRatePerSecond) * 1000);
            
            // Prevent excessively long waits or negative values
            wait(Math.max(1, waitMillis));
        }
    }
}
```

---

### JUnit 5 Tests

To run these tests, ensure you have `junit-jupiter-api` and `junit-jupiter-engine` in your dependencies.

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should allow burst up to capacity");
        }
        assertFalse(limiter.tryAcquire(), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(110); // Wait for refill
        
        assertTrue(limiter.tryAcquire(), "Token should have refilled");
    }

    @Test
    @DisplayName("Blocking acquire should wait for token")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // Consume initial token

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block until refill
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 90, "Should have blocked for roughly 100ms");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        double rate = 100.0; // 100 tokens per second
        double capacity = 10.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);
        
        int numThreads = 10;
        int durationMillis = 1000;
        AtomicInteger totalAcquired = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < durationMillis) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Expected: Capacity (burst) + (Rate * Duration)
        // 10 + (100 * 1) = 110
        int maxExpected = (int) (capacity + (rate * (durationMillis / 1000.0)) + 5); // +5 for timing jitter
        
        assertTrue(totalAcquired.get() <= maxExpected, 
            "Acquired " + totalAcquired.get() + " tokens, but max expected was " + maxExpected);
        assertTrue(totalAcquired.get() >= (int)rate - 10, 
            "Should have acquired close to the rate limit");
    }
}
```

### Key Test Explanations:
1.  **`testBurstCapacity`**: Verifies that the `capacity` parameter correctly allows an initial burst of requests without waiting.
2.  **`testRefill`**: Ensures that tokens are regenerated based on the `refillRate`.
3.  **`testBlockingAcquire`**: Validates that `acquire()` correctly puts the thread to sleep and wakes it up when a token is available.
4.  **`testConcurrentRateLimit`**: This is the stress test. It spawns multiple threads hammering the limiter. It calculates the theoretical maximum tokens allowed over a 1-second window ($\text{Burst} + \text{Rate} \times \text{Time}$) and asserts that the actual count does not exceed this, proving thread safety and rate accuracy.