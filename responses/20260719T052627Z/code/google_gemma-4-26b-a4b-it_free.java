<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260719T052627Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a **Lock-Free** approach leveraging `AtomicLong` to manage tokens. Instead of using a background thread to refill tokens (which is resource-intensive), this implementation uses **Lazy Refilling**: tokens are calculated based on the time elapsed since the last request.

### 1. Implementation: `TokenBucketRateLimiter.java`

```java
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * 
 * Design:
 * 1. Lazy Refilling: Instead of a background thread, we calculate available tokens 
 *    on-demand based on the time delta since the last update.
 * 2. Lock-Free: Uses AtomicLong and a CAS (Compare-And-Set) loop to ensure 
 *    high performance under heavy contention without synchronized blocks.
 * 3. Precision: Uses nanoseconds for internal calculations to minimize drift.
 */
public class TokenBucketRateLimiter {

    private final long maxBurst;
    private final long refillTokensPerNano;
    private final long refillIntervalNano;

    // State: Total tokens available (represented as fixed-point to handle fractional math if needed, 
    // but here we use long for simplicity and convert time to tokens).
    // We store the "last refill timestamp" and "current tokens" separately.
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillNanos;

    /**
     * @param capacity The maximum number of tokens the bucket can hold (Burst size).
     * @param refillRatePerSecond How many tokens are added to the bucket every second.
     */
    public TokenBucketRateLimiter(long capacity, long refillRatePerSecond) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be > 0");
        if (refillRatePerSecond < 1) throw new IllegalArgumentException("Refill rate must be > 0");

        this.maxBurst = capacity;
        this.refillTokensPerNano = 1L; // Logic handled via division to avoid float issues
        this.refillIntervalNano = 1_000_000_000L / refillRatePerSecond;
        
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * @param tokens Number of tokens to acquire.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        while (true) {
            long currentTokens = availableTokens.get();
            long lastRefill = lastRefillNanos.get();
            
            // 1. Calculate new token count based on time elapsed
            long now = System.nanoTime();
            long elapsedNanos = Math.max(0, now - lastRefill);
            long tokensToAdd = elapsedNanos / refillIntervalNano;
            
            long newTokens = Math.min(maxBurst, currentTokens + tokensToAdd);

            // 2. Check if we have enough
            if (newTokens < tokens) {
                return false;
            }

            // 3. Attempt to update state via CAS
            // We only update tokens if we actually added something or if we are consuming
            if (availableTokens.compareAndSet(currentTokens, newTokens - tokens)) {
                // Update timestamp only if we actually added tokens to avoid losing time increments
                if (tokensToAdd > 0) {
                    lastRefillNanos.set(now);
                }
                return true;
            }
            // If CAS fails, another thread updated the state; loop and retry.
        }
    }

    /**
     * Blocking attempt to acquire tokens.
     * @param tokens Number of tokens to acquire.
     * @param timeout How long to wait.
     * @param unit Time unit for timeout.
     * @return true if tokens were acquired, false if timeout reached.
     */
    public boolean acquire(int tokens, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        
        while (System.nanoTime() < deadline) {
            if (tryAcquire(tokens)) {
                return true;
            }
            // Adaptive backoff: sleep for a tiny amount to prevent CPU spinning
            Thread.sleep(1); 
        }
        return false;
    }

    public long available() {
        return availableTokens.get();
    }
}
```

---

### 2. Testing: `TokenBucketRateLimiterTest.java`

Requires **JUnit 5** and **AssertJ** (optional, but used standard assertions here).

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
        // Capacity 5, Refill 1/sec
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1), "Should allow burst up to capacity");
        }
        assertFalse(limiter.tryAcquire(1), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // Capacity 1, Refill 10/sec (1 token every 100ms)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10);
        
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1)); // Empty

        Thread.sleep(150); // Wait for refill
        assertTrue(limiter.tryAcquire(1), "Should have refilled at least one token");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 2); // 2 per sec (1 every 500ms)
        
        assertTrue(limiter.tryAcquire(1)); // Use first
        
        long start = System.currentTimeMillis();
        boolean acquired = limiter.acquire(1, 1, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        assertTrue(acquired);
        assertTrue(duration >= 400, "Should have waited at least 400ms for refill");
    }

    @Test
    @DisplayName("Concurrent Test: Long-