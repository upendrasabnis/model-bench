<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260815T172406Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a "lazy refill" strategy. Instead of having a background thread constantly adding tokens (which is resource-intensive), we calculate how many tokens should have been added since the last request based on the elapsed time.

### Design Documentation

#### 1. Algorithm: Token Bucket
*   **Tokens:** Represent the availability to perform an action.
*   **Capacity (Burst):** The maximum number of tokens the bucket can hold at once.
*   **Refill Rate:** The number of tokens added per second.
*   **Lazy Refill:** On every `acquire` or `tryAcquire` call, we calculate `elapsedTime * refillRate` and add that to the current balance, up to the capacity.

#### 2. Concurrency Strategy
*   **Thread Safety:** We use `synchronized` blocks on a private lock object. While `AtomicLong` is faster for single counters, the logic requires updating two dependent variables (tokens and lastRefillTime) atomically. A lock ensures that the "calculate-then-update" sequence is atomic.
*   **Blocking vs. Non-Blocking:**
    *   `tryAcquire()`: Checks if tokens $\ge$ requested amount. If yes, subtracts and returns true; otherwise, returns false immediately.
    *   `acquire()`: If tokens are insufficient, it calculates the `waitNanos` required for the bucket to reach the required threshold and uses `Thread.sleep()`.

#### 3. Complexity
*   **Time Complexity:** $O(1)$ for both operations.
*   **Space Complexity:** $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe Token Bucket rate limiter.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillRatePerSecond;
    
    private double availableTokens;
    private long lastRefillTimestampNanos;

    private final Object lock = new Object();

    /**
     * @param capacity Maximum tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.availableTokens = capacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire tokens.
     * @param tokens Number of tokens to acquire.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        synchronized (lock) {
            refill();
            if (availableTokens >= tokens) {
                availableTokens -= tokens;
                return true;
            }
            return false;
        }
    }

    /**
     * Blocking attempt to acquire tokens.
     * @param tokens Number of tokens to acquire.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(int tokens) throws InterruptedException {
        synchronized (lock) {
            refill();
            
            // If we have enough, take them and return
            if (availableTokens >= tokens) {
                availableTokens -= tokens;
                return;
            }

            // Calculate time needed to reach required tokens
            double tokensNeeded = tokens - availableTokens;
            long nanosToWait = (long) (tokensNeeded / refillRatePerSecond * 1_000_000_000L);
            
            // Release lock while sleeping to allow other threads to attempt acquisition
            // Note: This is a simplified approach. In a high-contention production 
            // system, we'd use Condition variables to avoid busy-waiting/sleeping.
            long waitTime = nanosToWait;
            lock.unnotifyAll(); // Ensure any waiting threads are woken if they were waiting on state
            
            // We must release the lock before sleeping so other threads can call tryAcquire
            // But we need to re-verify state after waking up.
            // To keep this implementation clean and correct for a "standard" interview-style 
            // implementation, we use a loop with a sleep.
        }
        
        // Actual blocking logic
        performBlockingWait(tokens);
    }

    private void performBlockingWait(int tokens) throws InterruptedException {
        while (true) {
            synchronized (lock) {
                refill();
                if (availableTokens >= tokens) {
                    availableTokens -= tokens;
                    return;
                }
                
                double tokensNeeded = tokens - availableTokens;
                long nanosToWait = (long) (tokensNeeded / refillRatePerSecond * 1_000_000_000L);
                
                // Sleep for a portion of the required time to prevent CPU spinning
                // but wake up frequently enough to be accurate.
                long sleepMillis = Math.max(1, nanosToWait / 1_000_000);
                lock.unnotifyAll();
            }
            Thread.sleep(sleepMillis);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long nanosPassed = now - lastRefillTimestampNanos;
        double tokensToAdd = (nanosPassed / 1_000_000_000.0) * refillRatePerSecond;
        
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillTimestampNanos = now;
    }
}
```

---

### JUnit 5 Tests

To run these, ensure you have `org.junit.jupiter:junit-jupiter:5.x.x` in your classpath.

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
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);

        // Should be able to take 5 immediately
        assertTrue(limiter.tryAcquire(5));
        // Should fail to take more
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("Should refill over time")
    void testRefill() throws InterruptedException {
        // Capacity 1, Refill 10/sec (1 token every 100ms)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0);

        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));

        // Wait 250ms -> should have ~2 tokens
        Thread.sleep(250);

        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("Blocking acquire should eventually succeed")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 2.0); // 2 per sec = 1 per 500ms

        assertTrue(limiter.tryAcquire(1));
        
        long start = System.currentTimeMillis();
        limiter.acquire(1); // Should block for ~500ms
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 400, "Should have waited at least 400ms");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int refillRate = 50; // 50 tokens per second
        int durationSeconds = 2;
        int numThreads = 10;
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, (double) refillRate);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Synchronized start
                    while (!Thread.currentThread().isInterrupted()) {
                        if (limiter.tryAcquire(1)) {
                            totalAcquired.incrementAndGet();
                        } else {
                            // Small sleep to prevent tight loop if we fail
                            Thread.sleep(1);
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown(); // Start all threads
        Thread.sleep(durationSeconds * 1000L + 500); // Run for duration + buffer
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Expected tokens = Initial Burst (10) + (Rate * Duration)
        // We allow a small margin of error for timing/scheduling
        int expectedMax = 10 + (refillRate * durationSeconds) + 5; 
        int actualAcquired = totalAcquired.get();

        assertTrue(actualAcquired <= expectedMax, 
            String.format("Rate exceeded! Expected max ~%d, but got %d", expectedMax, actualAcquired));
        assertTrue(actualAcquired > (refillRate * durationSeconds), 
            "Should have acquired at least the refill amount");
    }
}
```

### Key Test Explanations:
1.  **`testBurstCapacity`**: Verifies that we can consume the full capacity immediately without waiting.
2.  **`testRefill`**: Verifies that the "lazy" calculation works by waiting a short duration and checking if new tokens appeared.
3.  **`testConcurrentRateLimiting`**: This is the most critical test. It spawns multiple threads trying to "steal" tokens. We calculate the theoretical maximum tokens available (Initial Capacity + (Rate $\times$ Time)) and ensure the actual count doesn't exceed this by a significant margin, proving the rate limiter is correctly throttling even under high contention.