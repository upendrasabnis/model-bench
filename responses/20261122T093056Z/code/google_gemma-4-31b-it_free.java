<!-- model: google/gemma-4-31b-it:free | category: code | run: 20261122T093056Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a professional implementation of a Token Bucket rate limiter in Java.

### Design Documentation

#### 1. Algorithm
The **Token Bucket** algorithm allows for a specific amount of "burstiness." 
- **Bucket Capacity**: The maximum number of tokens the bucket can hold.
- **Refill Rate**: The number of tokens added to the bucket per second.
- **Token Consumption**: Each request consumes one token. If the bucket is empty, the request must either wait (blocking) or be rejected (non-blocking).

#### 2. Implementation Strategy
Instead of using a background timer thread to add tokens (which is resource-intensive), this implementation uses **Lazy Refilling**. 
- We store the `lastRefillTimestamp`.
- Whenever a request arrives, we calculate how many tokens should have been added since the last request: 
  $\text{tokensToAdd} = (\text{currentTime} - \text{lastRefill}) \times \text{refillRatePerNano}$.
- This ensures $O(1)$ time complexity for all operations and eliminates unnecessary thread overhead.

#### 3. Thread Safety
- **`AtomicLong` / `volatile`**: Not sufficient alone because updating the token count depends on the previous timestamp (a read-modify-write cycle).
- **`synchronized` block**: Used to ensure that the calculation of tokens and the deduction of tokens happen atomically. This prevents "double-spending" of tokens under high concurrency.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {
    private final double refillRatePerNano;
    private final long capacity;
    
    private double currentTokens;
    private long lastRefillTimestamp;

    /**
     * @param refillRate Tokens to add per second.
     * @param capacity   Maximum burst size.
     */
    public TokenBucketRateLimiter(double refillRate, long capacity) {
        this.refillRatePerNano = refillRate / 1_000_000_000.0;
        this.capacity = capacity;
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
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
     * Blocking attempt to acquire a token. 
     * Parks the thread until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            
            // Calculate wait time until at least 1 token is available
            double missingTokens = 1.0 - currentTokens;
            long waitNanos = (long) (missingTokens / refillRatePerNano);
            
            // Release lock while waiting to allow other threads to potentially 
            // check or for time to pass, then re-acquire.
            this.notifyAll(); // Wake up others if logic changes
            this.wait(TimeUnit.NANOSECONDS.toMillis(waitNanos));
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillTimestamp;
        double tokensToAdd = nanosElapsed * refillRatePerNano;
        
        currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

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
        // 10 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        
        // Empty the bucket
        for (int i = 0; i < 10; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());

        // Wait 200ms -> should refill approx 2 tokens
        Thread.sleep(200);
        
        assertTrue(limiter.tryAcquire(), "Should have refilled at least 1 token");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // use the only token

        long start = System.currentTimeMillis();
        limiter.acquire(); // should block for ~100ms
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 90, "Should have blocked for at least 100ms (approx)");
    }

    @Test
    @DisplayName("Concurrent test: Rate should not exceed limit over long run")
    void testConcurrentRateLimit() throws InterruptedException {
        int refillRate = 100; // 100 tokens per second
        int capacity = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(refillRate, capacity);
        
        int threadCount = 10;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    if (limiter.tryAcquire()) {
                        successCount.incrementAndGet();
                    }
                    // Small sleep to prevent tight-looping and allow refill
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        double maxExpectedTokens = capacity + (refillRate * durationSeconds);
        
        assertTrue(successCount.get() <= maxExpectedTokens + 1, 
            String.format("Acquired %d tokens, but max expected was %.2f", 
            successCount.get(), maxExpectedTokens));
    }
}
```

### Complexity Analysis
- **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
- **Space Complexity**: $O(1)$ as we only store a few primitives regardless of the rate or capacity.
- **Concurrency**: The use of `synchronized` ensures that the state transitions (refill $\rightarrow$ check $\rightarrow$ consume) are atomic. Using `wait()` in the blocking call ensures the thread does not consume CPU cycles while waiting for tokens to generate.