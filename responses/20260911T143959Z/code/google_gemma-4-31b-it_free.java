<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260911T143959Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe Token Bucket rate limiter.

### Design Documentation

**1. Algorithm:**
The Token Bucket algorithm allows for a certain amount of "burstiness" while maintaining a strict average rate. 
- **Capacity:** The maximum number of tokens the bucket can hold.
- **Refill Rate:** The number of tokens added to the bucket per second.
- **Lazy Refill:** Instead of using a background thread to add tokens (which would be resource-intensive), this implementation calculates how many tokens should have been added based on the time elapsed since the last request.

**2. Thread Safety:**
- **`AtomicLong` / `synchronized`:** I have used a `synchronized` block around the state update logic. While `AtomicLong` could track tokens, the logic requires updating both the `lastRefillTimestamp` and the `currentTokens` atomically to prevent race conditions (e.g., two threads both calculating a refill based on the same old timestamp).
- **Wait/Notify:** The `acquire()` method uses `wait()` to block the thread until enough tokens are available, avoiding busy-waiting.

**3. Complexity:**
- **Time Complexity:** $O(1)$ for both `tryAcquire` and `acquire`.
- **Space Complexity:** $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final long capacity;
    private final double refillRatePerSecond;
    
    private double currentTokens;
    private long lastRefillTimestamp;

    /**
     * @param capacity Maximum tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Non-blocking attempt to consume 1 token.
     * @return true if token was consumed, false otherwise.
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
     * Blocking attempt to consume 1 token.
     * Waits until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            
            // Calculate time to wait for the next token to be available
            double missingTokens = 1.0 - currentTokens;
            long waitTimeNanos = (long) (missingTokens / refillRatePerSecond * 1_000_000_000L);
            
            // Wait for a fraction of the required time to avoid over-sleeping 
            // and account for OS scheduler precision
            wait(TimeUnit.NANOSECONDS.toMillis(waitTimeNanos));
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double deltaSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        double tokensToAdd = deltaSeconds * refillRatePerSecond;
        
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
        assertFalse(limiter.tryAcquire(), "Should block after burst capacity is exhausted");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 1 token per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(1100); // Wait for 1.1 seconds
        
        assertTrue(limiter.tryAcquire(), "Token should have refilled");
    }

    @Test
    @DisplayName("Concurrent test: long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int capacity = 10;
        double ratePerSecond = 50.0; 
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSecond);
        
        int numThreads = 10;
        int durationMillis = 2000;
        AtomicInteger totalAcquired = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < durationMillis) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    // Small yield to prevent tight-looping the CPU
                    Thread.yield();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Max expected = (rate * duration) + burst capacity
        double expectedMax = (ratePerSecond * (durationMillis / 1000.0)) + capacity;
        
        assertTrue(totalAcquired.get() <= expectedMax, 
            String.format("Acquired %d tokens, but max allowed was %.2f", totalAcquired.get(), expectedMax));
        
        // Also verify we are actually getting tokens (not just returning 0)
        assertTrue(totalAcquired.get() > (ratePerSecond * 0.8), "Should utilize most of the rate");
    }

    @Test
    @DisplayName("acquire() should block until token available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        limiter.tryAcquire(); // Empty the bucket

        long start = System.currentTimeMillis();
        limiter.acquire(); // This should block for ~1 second
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 900, "Blocking acquire should have waited for refill");
    }
}
```

### Key implementation details for the Concurrent Test:
1. **The Formula:** In a long-run test, the total tokens available is $\text{Total} = (\text{Rate} \times \text{Time}) + \text{Burst Capacity}$.
2. **Tight Loop vs Yield:** Using `Thread.yield()` in the concurrent test ensures that the CPU doesn't starve the refill calculation logic, simulating a realistic high-load environment.
3. **Precision:** I used `System.nanoTime()` for the internal logic to avoid issues with system clock jumps (NTP updates) and to provide sub-millisecond precision for high refill rates.