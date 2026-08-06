<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260806T073415Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### 1. Algorithm Overview
The Token Bucket algorithm allows for a maximum "burst" of requests while enforcing a steady-state average rate. 
- **Bucket Capacity**: The maximum number of tokens the bucket can hold. This defines the burst size.
- **Refill Rate**: The number of tokens added to the bucket per second.
- **Tokens**: Each request consumes one token. If the bucket is empty, the request must either wait (blocking) or be rejected (non-blocking).

#### 2. Implementation Strategy
Instead of using a background thread to refill tokens (which is resource-heavy), this implementation uses **Lazy Refilling**. 
- We store the `lastRefillTimestamp`.
- Whenever a request arrives, we calculate how many tokens should have been generated between the current time and the last refill time:
  `tokensToAdd = (currentTime - lastRefillTime) * refillRatePerNano`.
- This ensures $O(1)$ time complexity and minimal overhead.

#### 3. Thread Safety
- **Synchronization**: The state (`availableTokens` and `lastRefillTimestamp`) is guarded by a `ReentrantLock`.
- **Blocking**: A `Condition` variable is used to put threads to sleep when the bucket is empty, waking them up only when tokens are likely available, avoiding busy-waiting.

---

### Implementation

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final double refillRatePerNano;
    private final long capacity;
    
    private double availableTokens;
    private long lastRefillTimestamp;
    
    private final Lock lock = new ReentrantLock();
    private final Condition hasTokens = lock.newCondition();

    /**
     * @param tokensPerSecond The steady-state rate of token generation.
     * @param burstCapacity   The maximum number of tokens the bucket can hold.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, long burstCapacity) {
        this.refillRatePerNano = tokensPerSecond / 1_000_000_000.0;
        this.capacity = burstCapacity;
        this.availableTokens = burstCapacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosSinceLastRefill = now - lastRefillTimestamp;
        double tokensToAdd = nanosSinceLastRefill * refillRatePerNano;
        
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocking attempt to acquire a token.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();
                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0;
                    return;
                }
                
                // Calculate wait time to avoid tight loop
                double missingTokens = 1.0 - availableTokens;
                long waitNanos = (long) (missingTokens / refillRatePerNano);
                
                // Use nano wait for precision
                hasTokens.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }
}
```

---

### JUnit 5 Tests

To run these tests, add `org.junit.jupiter:junit-jupiter:5.10.0` to your dependencies.

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
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // empty the bucket

        long start = System.currentTimeMillis();
        limiter.acquire(); // should block for ~100ms
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 90, "Should have blocked for at least 90ms");
    }

    @Test
    @DisplayName("Concurrent long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int threads = 10;
        double ratePerSecond = 100.0;
        long capacity = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSecond, capacity);
        
        AtomicInteger totalAcquired = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        
        long testDurationMs = 1000;
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < testDurationMs) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Expected: Burst (10) + Rate (100 * 1s) = 110 tokens
        // We allow a small margin for timing jitter
        int acquired = totalAcquired.get();
        int maxExpected = (int) (capacity + ratePerSecond + 5); 
        
        assertTrue(acquired <= maxExpected, 
            String.format("Acquired %d tokens, but limit was ~%d", acquired, maxExpected));
        assertTrue(acquired >= ratePerSecond - 10, 
            "Should have acquired close to the configured rate");
    }
}
```

### Complexity Analysis
- **Time Complexity**: `tryAcquire()` and `acquire()` both operate in $O(1)$ time (excluding the wait time for `acquire()`).
- **Space Complexity**: $O(1)$ as it only stores a few primitive variables regardless of the rate or capacity.