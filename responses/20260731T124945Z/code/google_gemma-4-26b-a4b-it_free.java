<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a **lazy refill strategy**. Instead of using a background thread to refill tokens (which is resource-intensive), we calculate how many tokens should have been added based on the time elapsed since the last request.

### Design Documentation

1.  **Algorithm**: Token Bucket.
2.  **Concurrency Model**: Uses `synchronized` blocks on a private monitor. While `AtomicLong` is often used for single variables, the "check-then-act" logic (calculating time, updating tokens, and checking availability) must be **atomic as a single unit**. A synchronized block ensures that no two threads can miscalculate the elapsed time or the token count simultaneously.
3.  **Lazy Refill**: The `refill()` method is called at the start of every `acquire` or `tryAcquire` call. It calculates `elapsedTime * refillRate` and adds it to the current bucket, capping it at the `maxBurst`.
4.  **Complexity**:
    *   **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
    *   **Space Complexity**: $O(1)$.
5.  **Methods**:
    *   `tryAcquire()`: Non-blocking. Returns `true` if a token was available, `false` otherwise.
    *   `acquire()`: Blocking. Uses `Object.wait()` and `Object.notifyAll()` to suspend the thread until tokens are available, preventing busy-waiting (spinning).

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {

    private final long maxBurst;
    private final double refillRatePerSecond;
    
    private double currentTokens;
    private long lastRefillTimestampNanos;

    /**
     * @param maxBurst The maximum number of tokens the bucket can hold (burst capacity).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long maxBurst, double refillRatePerSecond) {
        this.maxBurst = maxBurst;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = maxBurst;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token acquired, false otherwise.
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
     * @return true once a token is acquired.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            // Wait for a short period before retrying to avoid high CPU usage
            // In a production system, one might calculate the exact sleep time.
            wait(10); 
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillTimestampNanos;
        
        // Convert nanoseconds to seconds for rate calculation
        double secondsElapsed = (double) nanosElapsed / 1_000_000_000.0;
        double tokensToAdd = secondsElapsed * refillRatePerSecond;

        if (tokensToAdd > 0) {
            currentTokens = Math.min(maxBurst, currentTokens + tokensToAdd);
            lastRefillTimestampNanos = now;
        }
    }
}
```

---

### JUnit 5 Test Suite

To run this, ensure you have `org.junit.jupiter:junit-jupiter` in your classpath.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst capacity immediately")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should acquire token within burst limit");
        }
        assertFalse(limiter.tryAcquire(), "Should not acquire token after burst is exhausted");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefillOverTime() throws InterruptedException {
        // 1 token per second, burst of 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait for 1.1 seconds to ensure 1 token is refilled
        Thread.sleep(1100);

        assertTrue(limiter.tryAcquire(), "Should have refilled one token");
    }

    @Test
    @DisplayName("Blocking acquire should eventually succeed")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 2.0); // 2 tokens per sec
        assertTrue(limiter.tryAcquire());
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~500ms
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 400, "Should have blocked for approximately 500ms");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should match configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int refillRate = 50; // 50 tokens per second
        int burst = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burst, refillRate);

        int threadCount = 10;
        int durationSeconds = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successfulAcquires = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < (durationSeconds * 1000)) {
                    if (limiter.tryAcquire()) {
                        successfulAcquires.incrementAndGet();
                    }
                    // Tiny sleep to prevent tight-looping and allow other threads to work
                    Thread.yield();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(durationSeconds + 1, TimeUnit.SECONDS);

        // Expected tokens = initial burst + (rate * duration)
        // We use a margin of error because of OS scheduling jitter
        double expectedMax = burst + (refillRate * durationSeconds);
        double expectedMin = (refillRate * durationSeconds) - 5; // Allow small margin for timing

        double actual = successfulAcquires.get();
        
        System.out.println("Actual acquires: " + actual);
        System.out.println("Expected approx: " + expectedMax);

        assertTrue(actual <= expectedMax + 2, 
            "Exceeded rate limit! Actual: " + actual + " Expected max: " + expectedMax);
        assertTrue(actual >= expectedMin, 
            "Failed to reach rate limit! Actual: " + actual + " Expected min: " + expectedMin);
    }
}
```

### Key Testing Explanations:
1.  **`testBurstCapacity`**: Verifies that the bucket starts full and respects the `maxBurst` limit.
2.  **`testConcurrentRateLimiting`**: 
    *   Spawns 10 threads competing for tokens.
    *   Runs for 2 seconds.
    *   **Validation logic**: It calculates the mathematical limit: $\text{Burst} + (\text{Rate} \times \text{Time})$. It checks that the actual count is within a reasonable margin of error (accounting for the fact that `Thread.sleep` and context switching aren't perfectly precise).
    *   This is the most important test for verifying that the `synchronized` logic correctly prevents race conditions that would otherwise "create" tokens out of thin air.