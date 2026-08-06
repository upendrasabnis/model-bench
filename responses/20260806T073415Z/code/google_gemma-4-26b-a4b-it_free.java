<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260806T073415Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation

#### Overview
The `TokenBucketRateLimiter` is a thread-safe rate limiter based on the Token Bucket algorithm. It allows for a specific "burst" capacity (maximum tokens the bucket can hold) and a "refill rate" (tokens added per unit of time).

#### Core Logic: Lazy Refill
Instead of using a background thread to refill tokens (which is resource-intensive), this implementation uses **Lazy Refill**. 
1. When a thread attempts to acquire a token, the limiter calculates how much time has passed since the `lastRefillTimestamp`.
2. It calculates the number of new tokens earned during that interval: `tokensToAdd = elapsedSeconds * refillRate`.
3. The bucket is updated, and the requested token is consumed.

#### Thread Safety
*   **Atomicity:** We use `synchronized` blocks on a private lock object. While `AtomicLong` could be used for the token count, the "check-then-act" logic (calculating time elapsed AND updating tokens) must be atomic to prevent race conditions where two threads both see 1 token left and both consume it.
*   **Visibility:** The `synchronized` keyword ensures that updates to `currentTokens` and `lastRefillTimestamp` are visible across all threads.

#### Complexity
*   **Time Complexity:** $O(1)$ for both `tryAcquire` and `acquire`.
*   **Space Complexity:** $O(1)$.

---

### Implementation

```java
import java.time.Duration;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillRatePerSecond;
    
    private double currentTokens;
    private long lastRefillTimestampNanos;
    private final Lock lock = new ReentrantLock();

    /**
     * @param capacity The maximum number of tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added to the bucket every second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = capacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Attempts to acquire a token immediately.
     * 
     * @return true if a token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks the current thread until a token becomes available.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        while (true) {
            lock.lock();
            try {
                refill();
                if (currentTokens >= 1.0) {
                    currentTokens -= 1.0;
                    return;
                }
                
                // Calculate time to wait until at least 1 token is available
                double tokensNeeded = 1.0 - currentTokens;
                long nanosToWait = (long) (tokensNeeded / refillRatePerSecond * 1_000_000_000L);
                
                // Release lock before sleeping to allow other threads to progress/refill
                lock.unlock();
                
                // Cap sleep to prevent extreme long sleeps if rate is very low
                long sleepTime = Math.max(1, Math.min(nanosToWait, 100_000_000L)); 
                Thread.sleep(sleepTime / 1_000_000, (int) (nanosToWait % 1_000_000));
                
                lock.lock(); // Re-acquire to loop and check again
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTimestampNanos) / 1_000_000_000.0;
        
        double tokensToAdd = elapsedSeconds * refillRatePerSecond;
        if (tokensToAdd > 0) {
            currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
            lastRefillTimestampNanos = now;
        }
    }

    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return currentTokens;
        } finally {
            lock.unlock();
        }
    }
}
```

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        
        // Should be able to take 5 tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should have acquired token " + i);
        }
        
        // 6th should fail
        assertFalse(limiter.tryAcquire());
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 1 token per second, capacity 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        // Wait 1.1 seconds
        Thread.sleep(1100);
        
        assertTrue(limiter.tryAcquire(), "Should have refilled a token");
    }

    @Test
    @DisplayName("Blocking acquire should eventually succeed")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0); // 10 tokens/sec
        
        limiter.tryAcquire(); // Empty the bucket
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~100ms
        long end = System.currentTimeMillis();
        
        assertTrue((end - start) >= 100, "Should have blocked for at least 100ms");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should match configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int capacity = 10;
        double refillRate = 50.0; // 50 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRate);
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        
        long testDurationMillis = 2000; // 2 seconds
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < testDurationMillis) {
                    if (limiter.tryAcquire()) {
                        successfulAcquires.incrementAndGet();
                    }
                    // Tiny sleep to prevent CPU hogging in this test loop
                    Thread.yield(); 
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);

        // Expected tokens: Initial capacity (10) + (refillRate * duration)
        // 10 + (50 * 2) = 110
        double expectedMax = capacity + (refillRate * (testDurationMillis / 1000.0));
        double actual = successfulAcquires.get();

        // We allow a small delta for timing jitter in the test
        assertTrue(actual <= expectedMax + 5, 
            String.format("Exceeded limit! Expected ~%f, got %f", expectedMax, actual));
        assertTrue(actual > expectedMax - 10, 
            String.format("Underperformed! Expected ~%f, got %f", expectedMax, actual));
    }
}
```

### How to run the tests:
1.  Ensure you have **JUnit 5** and **AssertJ** (optional, used standard JUnit here) in your classpath.
2.  The concurrent test uses `System.currentTimeMillis()` to bound the loop, ensuring the test completes.
3.  The `testConcurrentRateLimiting` verifies that even with 10 threads competing, the total number of successful `tryAcquire` calls stays within the mathematical bounds of the `capacity + (rate * time)` formula.