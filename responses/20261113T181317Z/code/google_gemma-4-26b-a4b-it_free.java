<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20261113T181317Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a "lazy refill" strategy. Instead of having a background thread constantly adding tokens (which is CPU intensive), we calculate how many tokens should have been added based on the time elapsed since the last request.

### Design Documentation

#### 1. Algorithm: Lazy Refill Token Bucket
The bucket maintains a `currentTokens` count. Every time a thread attempts to acquire a token, the algorithm:
1. Calculates the time elapsed since the last access.
2. Calculates how many new tokens should have been generated during that duration: `elapsedTime * refillRate`.
3. Adds these tokens to the bucket, capping the total at `maxBurstCapacity`.
4. Attempts to consume the requested tokens.

#### 2. Concurrency Strategy
*   **Thread Safety:** We use a `ReentrantLock`. While `AtomicLong` could be used for some parts, the "calculate-then-update" logic (read time $\to$ calculate tokens $\to$ update tokens) must be **atomic** to prevent race conditions where two threads both see the same old token count and over-refill.
*   **Blocking vs. Non-Blocking:**
    *   `tryAcquire()`: Uses `lock.tryLock()` or simply checks the state within the lock and returns `false` immediately if insufficient tokens exist.
    *   `acquire()`: Uses `Condition.awaitNanos()` to park the thread until the required number of tokens are mathematically expected to be available, preventing busy-waiting.

#### 3. Complexity
*   **Time Complexity:** $O(1)$ for both acquire and tryAcquire.
*   **Space Complexity:** $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {

    private final long maxBurstCapacity;
    private final double refillRatePerNano; // tokens per nanosecond

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition sufficientTokensCondition = lock.newCondition();

    private double currentTokens;
    private long lastRefillTimestampNanos;

    /**
     * @param refillRatePerSecond How many tokens are added per second.
     * @param maxBurstCapacity    Maximum number of tokens the bucket can hold.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long maxBurstCapacity) {
        if (refillRatePerSecond <= 0 || maxBurstCapacity <= 0) {
            throw new IllegalArgumentException("Rate and capacity must be positive");
        }
        this.maxBurstCapacity = maxBurstCapacity;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        this.currentTokens = maxBurstCapacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        if (tokens <= 0) return true;
        if (tokens > maxBurstCapacity) return false;

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
     * Blocking attempt to acquire tokens.
     * @param tokens Number of tokens to consume.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void acquire(int tokens) throws InterruptedException {
        if (tokens <= 0) return;
        if (tokens > maxBurstCapacity) {
            throw new IllegalArgumentException("Requested tokens exceed max burst capacity");
        }

        lock.lock();
        try {
            refill();
            while (currentTokens < tokens) {
                // Calculate how long we need to wait for the required tokens
                double tokensNeeded = tokens - currentTokens;
                long nanosToWait = (long) (tokensNeeded / refillRatePerNano);
                
                if (nanosToWait <= 0) {
                    // Should not happen due to refill() call, but for safety:
                    refill();
                    if (currentTokens < tokens) {
                        sufficientTokensCondition.awaitNanos(1); 
                    }
                } else {
                    sufficientTokensCondition.awaitNanos(nanosToWait);
                }
                refill(); // Refill after waking up
            }
            currentTokens -= tokens;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillTimestampNanos;
        
        if (nanosElapsed > 0) {
            double tokensToAdd = nanosElapsed * refillRatePerNano;
            currentTokens = Math.min(maxBurstCapacity, currentTokens + tokensToAdd);
            lastRefillTimestampNanos = now;
            // Signal threads waiting in acquire()
            sufficientTokensCondition.signalAll();
        }
    }
}
```

---

### Unit Tests (JUnit 5)

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("tryAcquire should return true if tokens are available and false if empty")
    void testTryAcquire() {
        // 1 token per second, burst of 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);

        assertTrue(limiter.tryAcquire(1), "Should acquire first token");
        assertFalse(limiter.tryAcquire(1), "Should fail to acquire second token immediately");
    }

    @Test
    @DisplayName("acquire should block until tokens are available")
    void testAcquireBlocking() throws InterruptedException {
        // 10 tokens per second, burst of 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        
        assertTrue(limiter.tryAcquire(1));
        
        long start = System.currentTimeMillis();
        limiter.acquire(1); // Should wait ~100ms
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 90, "Should have blocked for approximately 100ms");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed configured limit")
    void testConcurrentRateLimit() throws InterruptedException {
        double ratePerSecond = 100.0;
        int burst = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSecond, burst);

        int threadCount = 10;
        int durationSeconds = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Wait for simultaneous start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire(1)) {
                            totalAcquired.incrementAndGet();
                        }
                        // Small sleep to prevent tight-looping CPU, 
                        // but small enough not to interfere with rate
                        Thread.sleep(1); 
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        latch.countDown(); // Start all threads
        Thread.sleep(durationSeconds * 1000L);
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        int total = totalAcquired.get();
        // Expected: burst (initial) + (rate * duration)
        // We allow a small margin (15%) for timing jitter in OS scheduling
        double expectedMax = burst + (ratePerSecond * durationSeconds);
        double margin = expectedMax * 0.15;

        assertTrue(total <= expectedMax + margin, 
            String.format("Rate exceeded! Expected ~%f, but got %d", expectedMax, total));
        
        // Also ensure it's not significantly lower than expected
        assertTrue(total > (ratePerSecond * durationSeconds) * 0.8, 
            "Rate too low! Got " + total);
    }
}
```

### How to run the tests
1. Ensure you have **JUnit 5** in your classpath (via Maven or Gradle).
2. The `testConcurrentRateLimit` test is the most important; it spawns 10 threads that constantly hammer the `tryAcquire` method. It verifies that even under heavy contention, the `totalAcquired` count stays within a reasonable margin of the mathematical limit ($\text{Burst} + \text{Rate} \times \text{Time}$).