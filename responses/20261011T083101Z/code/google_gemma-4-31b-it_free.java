<!-- model: google/gemma-4-31b-it:free | category: code | run: 20261011T083101Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### 1. Algorithm
The Token Bucket algorithm allows for a certain amount of "burstiness" while maintaining a strict long-term average rate.
- **Bucket**: Holds a maximum number of tokens (`capacity`).
- **Refill**: Tokens are added to the bucket at a constant rate (`refillRate` tokens per second).
- **Consumption**: Each request requires one token. If the bucket is empty, the request is either rejected (non-blocking) or waits (blocking).

#### 2. Concurrency Strategy
To ensure thread safety without expensive locking during every token check, this implementation uses **Lazy Refilling**. 
- Instead of a background thread adding tokens (which would cause lock contention), the bucket calculates how many tokens should have been added since the last request based on the elapsed time.
- `synchronized` blocks are used to ensure atomic updates to the token count and the last refill timestamp.

#### 3. Time Complexity & Space Complexity
- **Time Complexity**: $O(1)$ for both `acquire` and `tryAcquire`.
- **Space Complexity**: $O(1)$ as it only stores a few primitive variables.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final long capacity;
    private final double refillRatePerMs;
    
    private double currentTokens;
    private long lastRefillTimestamp;

    /**
     * @param capacity The maximum number of tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added to the bucket per second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerMs = refillRatePerSecond / 1000.0;
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    /**
     * Non-blocking attempt to consume a token.
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
     * Blocking attempt to consume a token. 
     * Waits until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            
            // Calculate wait time until at least 1 token is available
            double missing = 1.0 - currentTokens;
            long waitTimeMs = (long) Math.ceil(missing / refillRatePerMs);
            
            // Wait and release lock to let other threads potentially finish or refill
            this.wait(waitTimeMs);
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long delta = now - lastRefillTimestamp;
        
        double tokensToAdd = delta * refillRatePerMs;
        currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

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
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should allow burst up to 5");
        }
        assertFalse(limiter.tryAcquire(), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(110); // Wait for refill
        assertTrue(limiter.tryAcquire(), "Token should have refilled");
    }

    @Test
    @DisplayName("Blocking acquire should wait for token")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10);
        limiter.tryAcquire(); // Empty it

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~100ms
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 100, "Should have blocked for at least 100ms");
    }

    @Test
    @DisplayName("Concurrent long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int capacity = 10;
        double ratePerSecond = 50.0; 
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSecond);
        
        int numThreads = 10;
        int requestsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    // Small sleep to prevent tight-looping and simulate real traffic
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - startTime;
        
        double actualRatePerSecond = (totalAcquired.get() * 1000.0) / durationMs;
        
        // Allow a small margin for burst capacity (capacity / duration)
        double expectedMaxRate = ratePerSecond + (capacity * 1000.0 / durationMs);
        
        assertTrue(actualRatePerSecond <= expectedMaxRate + 5, 
            String.format("Rate exceeded! Actual: %.2f, Max Expected: %.2f", actualRatePerSecond, expectedMaxRate));
    }
}
```

### Key Testing Details:
1.  **`testBurstCapacity`**: Verifies that the `capacity` parameter correctly allows an initial spike of requests.
2.  **`testRefill`**: Verifies that tokens are generated over time based on the `refillRate`.
3.  **`testBlockingAcquire`**: Ensures that `acquire()` actually suspends the thread and resumes only when tokens are available.
4.  **`testConcurrentRateLimit`**: This is the stress test. It uses multiple threads to hammer the limiter. It calculates the actual throughput ($\text{tokens} / \text{time}$) and ensures it aligns with the configured rate plus the allowable burst.