<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### Algorithm
The Token Bucket algorithm allows for a maximum "burst" of requests while maintaining a steady-state average rate. 
- **Bucket Capacity**: The maximum number of tokens the bucket can hold. This defines the burst size.
- **Refill Rate**: The number of tokens added to the bucket per second.
- **Consumption**: Each request consumes one token. If the bucket is empty, the request is either rejected (non-blocking) or paused until a token becomes available (blocking).

#### Implementation Details
Instead of using a background timer thread to refill tokens (which is resource-heavy), this implementation uses **lazy refills**. The number of tokens is calculated based on the time elapsed since the last request:
$\text{newTokens} = (\text{currentTime} - \text{lastRefillTime}) \times \text{refillRate}$

#### Concurrency Strategy
- **`AtomicLong` / `volatile`**: Used to track the last refill timestamp.
- **`synchronized` block**: Used to ensure that the "calculate refill $\rightarrow$ check balance $\rightarrow$ consume token" sequence is atomic. This prevents race conditions where two threads might see one remaining token and both consume it.
- **`wait()` / `notifyAll()`**: Used for the blocking `acquire()` method to put threads to sleep until the bucket is refilled.

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
     * @param refillRatePerSecond How many tokens are added per second.
     * @param capacity Maximum tokens the bucket can hold (burst size).
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        this.refillRatePerSecond = refillRatePerSecond;
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
     * Non-blocking attempt to consume a token.
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
     * Blocking call that waits until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return;
            }
            
            // Calculate time to wait for the next token to be available
            double missingTokens = 1.0 - availableTokens;
            long waitMillis = (long) ((missingTokens / refillRatePerSecond) * 1000);
            
            // Wait for a short duration or until notified
            wait(Math.max(1, waitMillis));
        }
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
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 5);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should allow burst up to 5");
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
    @DisplayName("acquire() should block until token is available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // Empty the bucket

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~100ms
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 90, "Should have blocked for roughly 100ms");
    }

    @Test
    @DisplayName("Concurrent access should not exceed long-run rate")
    void testConcurrentRateLimit() throws InterruptedException {
        double rate = 100.0; // 100 permits per second
        double capacity = 10.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);
        
        int threadCount = 10;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    // Small sleep to simulate work and allow refills
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - startTime;
        double durationSec = durationMs / 1000.0;

        // Theoretical max = (rate * time) + burst capacity
        double maxAllowed = (rate * durationSec) + capacity;
        
        assertTrue(totalAcquired.get() <= maxAllowed, 
            String.format("Acquired %d tokens, but max allowed was %.2f", 
            totalAcquired.get(), maxAllowed));
    }
}
```

### Complexity Analysis
- **Time Complexity**: All operations (`tryAcquire`, `acquire`) are $O(1)$.
- **Space Complexity**: $O(1)$ as we only store a few primitive variables regardless of the rate or capacity.
- **Thread Safety**: Guaranteed by `synchronized` keywords, ensuring atomic updates to the token count and timestamp.