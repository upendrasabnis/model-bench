<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20261011T083101Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses `java.util.concurrent.atomic.AtomicLong` to manage the bucket state. Instead of using a background thread to refill tokens (which is resource-intensive), this implementation uses **lazy refilling**: tokens are calculated based on the time elapsed since the last request.

### 1. Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design:
 * 1. Lazy Refill: Tokens are not refilled by a background thread. Instead, 
 *    the bucket is updated based on the time delta during each access.
 * 2. Atomic State: Uses a single AtomicLong to represent the "last refill time" 
 *    and another for "available tokens" to ensure lock-free/low-contention updates.
 *    To ensure atomicity between time and tokens, we use a synchronized block 
 *    or a single atomic state. For simplicity and correctness in high contention, 
 *    this implementation uses a synchronized monitor on the state update.
 * 3. Burst Capacity: The 'capacity' defines the maximum tokens the bucket can hold.
 * 4. Refill Rate: Defined as tokens per unit of time.
 */
public class TokenBucket {
    private final long capacity;
    private final long refillTokensPerNanos;
    
    private long availableTokens;
    private long lastRefillNanos;

    /**
     * @param capacity Maximum number of tokens in the bucket (burst size).
     * @param refillRate Tokens to add per second.
     */
    public TokenBucket(long capacity, double refillRate) {
        if (capacity <= 0 || refillRate <= 0) {
            throw new IllegalArgumentException("Capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.availableTokens = capacity;
        this.lastRefillNanos = System.nanoTime();
        // Calculate how many nanoseconds it takes to generate 1 token
        this.refillTokensPerNanos = (long) (TimeUnit.SECONDS.toNanos(1) / refillRate);
    }

    /**
     * Internal method to refill tokens based on elapsed time.
     * Must be called within a synchronized block.
     */
    private void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillNanos;
        
        if (nanosElapsed > 0) {
            long tokensToAdd = nanosElapsed / refillTokensPerNanos;
            if (tokensToAdd > 0) {
                availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
                // Advance time by the amount actually consumed for token generation
                // to prevent "drifting" or losing fractional tokens.
                lastRefillNanos += tokensToAdd * refillTokensPerNanos;
            }
        }
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens >= 1) {
            availableTokens--;
            return true;
        }
        return false;
    }

    /**
     * Blocking attempt to acquire a token.
     */
    public synchronized void acquire() {
        while (true) {
            refill();
            if (availableTokens >= 1) {
                availableTokens--;
                return;
            }
            // Wait until the next token is likely available
            // We use LockSupport to avoid busy-waiting
            LockSupport.parkNanos(refillTokensPerNanos);
        }
    }

    // For testing purposes
    public synchronized long getAvailableTokens() {
        refill();
        return availableTokens;
    }
}
```

---

### 2. Unit Tests (JUnit 5)

The tests cover basic functionality, the burst capacity, and a high-concurrency test that verifies the long-run rate.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        TokenBucket bucket = new TokenBucket(5, 1.0); // 5 capacity, 1 token/sec
        
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Should acquire token " + i);
        }
        assertFalse(bucket.tryAcquire(), "Should not acquire 6th token");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucket bucket = new TokenBucket(10, 10.0);
        
        // Drain bucket
        for (int i = 0; i < 10; i++) bucket.tryAcquire();
        assertFalse(bucket.tryAcquire());

        // Wait for 250ms -> should get ~2 tokens
        Thread.sleep(250);
        
        assertTrue(bucket.tryAcquire(), "Should have refilled at least 1 token");
        assertTrue(bucket.tryAcquire(), "Should have refilled at least 2 tokens");
        assertFalse(bucket.tryAcquire(), "Should not have refilled a 3rd token yet");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 10.0); // 1 token every 100ms
        
        bucket.tryAcquire(); // Empty it
        
        long start = System.currentTimeMillis();
        bucket.acquire(); // This should block
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 100, "Blocking acquire should have waited for refill");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int refillRatePerSec = 100;
        int testDurationSec = 2;
        int totalThreads = 10;
        
        TokenBucket bucket = new TokenBucket(10, (double) refillRatePerSec);
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (testDurationSec * 1000L);

        // Submit multiple threads trying to acquire as fast as possible
        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    if (bucket.tryAcquire()) {
                        successfulAcquires.incrementAndGet();
                    }
                    // Small yield to prevent CPU hogging in this test loop
                    Thread.yield();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Expected: Initial burst (10) + (refillRate * duration)
        // We allow a small margin of error for timing jitter
        int expectedMax = 10 + (refillRatePerSec * testDurationSec) + 5; 
        int actual = successfulAcquires.get();

        System.out.println("Actual acquires: " + actual + " | Expected max: ~" + expectedMax);
        
        assertTrue(actual <= expectedMax, 
            String.format("Rate exceeded! Allowed ~%d, but got %d", expectedMax, actual));
        assertTrue(actual >= (refillRatePerSec * testDurationSec), 
            "Rate too low, likely timing issues");
    }
}
```

### Design Documentation

#### 1. Algorithm: Token Bucket
The Token Bucket algorithm allows for a certain amount of "burstiness." Tokens are added to a bucket at a fixed rate. Every request consumes one token. If the bucket is empty, the request is rejected (non-blocking) or waits (blocking).

#### 2. Complexity Analysis
*   **Time Complexity**: 
    *   `tryAcquire()`: $O(1)$ - Constant time mathematical calculation.
    *   `acquire()`: $O(1)$ amortized - It may block, but the calculation remains constant.
*   **Space Complexity**: $O(1)$ - Only stores a few long variables.

#### 3. Thread Safety Strategy
I used `synchronized` methods. While `AtomicLong` can be used for individual variables, the relationship between `availableTokens` and `lastRefillNanos` is **interdependent**. To prevent a race condition where two threads calculate a refill based on the same timestamp but update tokens differently, the state update (refill + consumption) must be atomic. 

In high-contention scenarios, `synchronized` is highly optimized in modern JVMs (biased locking/thin locks). For extreme scale, one could combine both values into a single `AtomicReference<State>` object to achieve a fully lock-free implementation, but that increases GC pressure due to object allocation on every request.

#### 4. Precision and Drift
To prevent "token drift" (losing fractional tokens due to integer division), the `lastRefillNanos` is not simply set to `now`. Instead, it is incremented by `tokensToAdd * refillTokensPerNanos`. This ensures that any "leftover" nanoseconds are preserved for the next calculation.