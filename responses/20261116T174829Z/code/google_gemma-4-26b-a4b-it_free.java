<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20261116T174829Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a robust implementation of a Token Bucket rate limiter in Java, followed by the design documentation and JUnit 5 tests.

### 1. Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design: Instead of using a background thread to refill tokens (which is expensive),
 * this implementation calculates the "refill" lazily whenever a request is made.
 * It uses the elapsed time since the last request to determine how many tokens 
 * should have been added to the bucket.
 */
public class TokenBucketRateLimiter {

    private final long maxCapacity;
    private final double refillRatePerSecond;

    private double currentTokens;
    private long lastRefillTimestampNanos;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param maxCapacity The maximum number of tokens the bucket can hold (burst capacity).
     * @param refillRatePerSecond How many tokens are added to the bucket every second.
     */
    public TokenBucketRateLimiter(long maxCapacity, double refillRatePerSecond) {
        if (maxCapacity <= 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Capacity and rate must be positive");
        }
        this.maxCapacity = maxCapacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = maxCapacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * 
     * @param tokens Number of tokens to consume.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        lock.lock();
        try {
            refill();
            if (currentTokens >= tokens) {
                currentTokens -= tokens;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocking attempt to acquire tokens. Waits until enough tokens are available.
     * 
     * @param tokens Number of tokens to consume.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(int tokens) throws InterruptedException {
        while (true) {
            lock.lock();
            try {
                refill();
                if (currentTokens >= tokens) {
                    currentTokens -= tokens;
                    return;
                }
                
                // Calculate how long we need to wait for the required tokens
                double needed = tokens - currentTokens;
                long waitNanos = (long) (needed * (1_000_000_000.0 / refillRatePerSecond));
                
                // Release lock and sleep to allow other threads to progress/refill
                lock.unlock();
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                lock.lock(); // Re-acquire to check condition again
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Updates the token count based on time elapsed since last refill.
     * Must be called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillTimestampNanos;
        
        double tokensToAdd = (nanosElapsed / 1_000_000_000.0) * refillRatePerSecond;
        
        currentTokens = Math.min(maxCapacity, currentTokens + tokensToAdd);
        lastRefillTimestampNanos = now;
    }

    // For testing purposes
    public double getCurrentTokens() {
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

### 2. Design Documentation

#### Algorithm: Lazy Refill Token Bucket
The implementation uses a "Lazy Refill" strategy. Instead of running a dedicated background thread that wakes up every few milliseconds to add tokens (which consumes CPU and causes context-switching overhead), we calculate the number of tokens that *should* have been added based on the time elapsed since the last interaction.

#### Core Components:
1.  **State Variables**:
    *   `currentTokens`: A `double` to allow for fractional token accumulation (crucial for high-precision rates).
    *   `lastRefillTimestampNanos`: Stores the last time the bucket was updated to calculate delta time.
2.  **Concurrency Control**: 
    *   A `ReentrantLock` ensures that `refill()` and the token subtraction are atomic. This prevents race conditions where two threads see enough tokens simultaneously but together exceed the bucket capacity.
3.  **Complexity**:
    *   **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
    *   **Space Complexity**: $O(1)$.

#### Methods:
*   **`tryAcquire(n)`**: Checks if `currentTokens >= n`. If yes, subtracts and returns `true`. Otherwise, returns `false` immediately.
*   **`acquire(n)`**: If tokens are unavailable, it calculates the required sleep time: $\text{wait} = \frac{\text{neededTokens}}{\text{refillRate}}$. It releases the lock during sleep to prevent deadlocking the entire system, then re-checks the condition upon waking.

---

### 3. Unit Tests (JUnit 5)

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst up to max capacity")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        
        // Should be able to take 5 immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1), "Should acquire token " + i);
        }
        // 6th should fail
        assertFalse(limiter.tryAcquire(1), "Should not exceed burst capacity");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1));
        
        // Wait 250ms -> should have ~2 tokens
        Thread.sleep(250);
        
        assertTrue(limiter.tryAcquire(2), "Should have refilled at least 2 tokens");
        assertFalse(limiter.tryAcquire(1), "Should not have 3 tokens yet");
    }

    @Test
    @DisplayName("acquire() should block until tokens are available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 2); // 2 tokens/sec
        
        assertTrue(limiter.tryAcquire(1));
        
        long start = System.currentTimeMillis();
        limiter.acquire(1); // Should wait ~500ms
        long duration = System.currentTimeMillis() - start;
        
        assertTrue(duration >= 400, "Should have blocked for approximately 500ms");
    }

    @Test
    @DisplayName("Concurrent Test: Long-run rate should not exceed configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int ratePerSecond = 100;
        int testDurationSeconds = 2;
        int totalExpectedMax = ratePerSecond * testDurationSeconds + 10; // + margin for burst
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, ratePerSecond);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        
        long endTime = System.currentTimeMillis() + (testDurationSeconds * 1000);

        // Spawn threads that constantly try to acquire
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    if (limiter.tryAcquire(1)) {
                        successfulAcquires.incrementAndGet();
                    }
                    // Small yield to prevent tight-looping CPU too hard
                    Thread.yield();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // The number of successful acquires should be close to (rate * duration) + burst
        // We use a slightly generous buffer to account for timing jitter in OS scheduling
        int actual = successfulAcquires.get();
        assertTrue(actual <= totalExpectedMax, 
            String.format("Rate exceeded! Expected ~%d, got %d", totalExpectedMax, actual));
        assertTrue(actual >= (ratePerSecond * testDurationSeconds - 20), 
            "Rate too low, possibly implementation error");
    }
}
```

### How to run:
1.  Ensure you have **JUnit 5** on your classpath.
2.  The `testConcurrentRateLimiting` test is the most critical: it spawns multiple threads competing for tokens and verifies that even under heavy contention, the number of successful operations does not exceed the mathematical limit defined by the rate and duration.