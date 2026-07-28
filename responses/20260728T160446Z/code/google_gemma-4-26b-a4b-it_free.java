<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a **lock-free approach** utilizing `AtomicLong` to manage the token bucket. Instead of using a background thread to refill tokens (which is resource-intensive), this implementation calculates the number of tokens to add based on the time elapsed since the last request.

### Design Documentation

#### 1. Algorithm: Token Bucket
The bucket has a maximum capacity (`maxBurst`) and a refill rate (`tokensPerSecond`). 
*   **Tokens** are represented as a `long` value.
*   **Refill Strategy**: Instead of a background thread, we use **lazy refilling**. When a request comes in, we calculate `elapsedTime * refillRate` and add it to the current bucket count.

#### 2. Concurrency Model
To ensure thread safety without the heavy overhead of `synchronized` blocks or `ReentrantLock` for every request, I used an **Atomic CAS (Compare-And-Swap) loop**.
*   The state is encapsulated in a single `AtomicLong` representing the current token count.
*   The `tryAcquire` and `acquire` methods use a `while(true)` loop to attempt to decrement the token count. If another thread updates the count between our "read" and "write" operations, the CAS fails, and we retry.

#### 3. Complexity
*   **Time Complexity**: $O(1)$ for `tryAcquire`. $O(N)$ for `acquire` where $N$ is the number of contentions (typically very low).
*   **Space Complexity**: $O(1)$.

---

### Implementation

```java
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket Rate Limiter.
 * Uses a lock-free CAS approach for high performance under contention.
 */
public class TokenBucketRateLimiter {

    private final long maxBurst;
    private final double tokensPerSecond;
    
    // Stores the current number of tokens. 
    // We use AtomicLong to handle concurrency without heavy locking.
    private final AtomicLong currentTokens;
    
    // Stores the last time (in nanoseconds) the bucket was refilled.
    private final AtomicLong lastRefillNanos;

    /**
     * @param maxBurst The maximum number of tokens the bucket can hold (burst capacity).
     * @param tokensPerSecond The rate at which tokens are added to the bucket.
     */
    public TokenBucketRateLimiter(long maxBurst, double tokensPerSecond) {
        if (maxBurst < 1) throw new IllegalArgumentException("Burst must be at least 1");
        if (tokensPerSecond <= 0) throw new IllegalArgumentException("Rate must be positive");

        this.maxBurst = maxBurst;
        this.tokensPerSecond = tokensPerSecond;
        this.currentTokens = new AtomicLong(maxBurst);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if a token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        refill();
        long current = currentTokens.get();
        while (current > 0) {
            if (currentTokens.compareAndSet(current, current - 1)) {
                return true;
            }
            current = currentTokens.get();
        }
        return false;
    }

    /**
     * Blocking attempt to acquire a token. 
     * Waits until a token becomes available.
     */
    public void acquire() throws InterruptedException {
        while (!tryAcquire()) {
            // Sleep for a tiny amount to prevent CPU spinning 
            // In a high-perf production system, one might use LockSupport.parkNanos
            Thread.sleep(1); 
        }
    }

    /**
     * Refills the bucket based on time elapsed since the last call.
     * Uses CAS to ensure thread safety during the refill calculation.
     */
    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsedNanos = now - last;

        if (elapsedNanos <= 0) return;

        // Calculate new tokens: (nanos elapsed / 1e9) * rate
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * tokensPerSecond;
        long tokensToAddLong = (long) tokensToAdd;

        if (tokensToAddLong > 0) {
            // Attempt to update the last refill time
            if (lastRefillNanos.compareAndSet(last, now)) {
                long newTokenCount = Math.min(maxBurst, currentTokens.get() + tokensToAddLong);
                currentTokens.set(newTokenCount);
            }
        }
    }
    
    // Helper for testing
    protected long getCurrentTokens() {
        return currentTokens.get();
    }
}
```

---

### JUnit 5 Tests

You will need the `junit-jupiter-api` and `junit-jupiter-engine` dependencies to run this.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should respect burst capacity initially")
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
        // 1 token per second, burst of 1
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait 1.1 seconds
        Thread.sleep(1100);

        assertTrue(limiter.tryAcquire(), "Should have refilled a token");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 2.0); // 2 tokens/sec -> 1 token every 500ms
        
        assertTrue(limiter.tryAcquire());
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // This should block for ~500ms
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 400, "Should have waited for refill");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int ratePerSecond = 100;
        int burst = 10;
        int threadCount = 10;
        int durationSeconds = 2;
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burst, ratePerSecond);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Sync start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire()) {
                            successfulAcquires.incrementAndGet();
                        }
                        // Small sleep to prevent infinite tight loop in test
                        Thread.sleep(1); 
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown(); // Start all threads
        Thread.sleep(durationSeconds * 1000L); // Run for 2 seconds
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Theoretical max: burst + (rate * duration)
        // 10 + (100 * 2) = 210
        // We allow a small margin for timing jitter in the test
        int maxExpected = burst + (int)(ratePerSecond * durationSeconds) + 5;
        int minExpected = (int)(ratePerSecond * durationSeconds) - 5;

        assertTrue(successfulAcquires.get() <= maxExpected, 
            "Exceeded rate limit! Got: " + successfulAcquires.get() + " Max: " + maxExpected);
        assertTrue(successfulAcquires.get() >= minExpected, 
            "Underperformed rate limit! Got: " + successfulAcquires.get() + " Min: " + minExpected);
        
        System.out.println("Concurrent Test Passed. Total Acquires: " + successfulAcquires.get());
    }
}
```