<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a professional implementation of a Token Bucket rate limiter in Java.

### Design Documentation

**1. Core Algorithm**
Instead of using a background thread to "refill" tokens (which is resource-heavy), this implementation uses **Lazy Refill**. The number of tokens is calculated based on the time elapsed since the last request. 
`tokensToAdd = elapsedNanos * (refillRate / 1 second)`.

**2. Thread Safety**
*   **`AtomicLong` / `synchronized`**: I have used `synchronized` blocks on a private lock object. While `AtomicLong` is faster for single variables, the rate limiter requires an atomic update of two dependent variables: `lastRefillTimestamp` and `currentTokens`. A synchronized block ensures these updates happen as a single atomic transaction.
*   **Wait/Notify**: The `acquire()` method uses `wait()` to put threads to sleep until enough tokens are likely to be available, preventing "busy-waiting" (spinning).

**3. Complexity**
*   **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
*   **Space Complexity**: $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class TokenBucketRateLimiter {
    private final double refillRatePerSecond;
    private final double capacity;
    
    private double currentTokens;
    private long lastRefillTimestampNanos;
    private final Object lock = new Object();

    /**
     * @param refillRatePerSecond How many tokens are added per second.
     * @param capacity Maximum burst size.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        this.currentTokens = capacity;
        this.lastRefillTimestampNanos = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /**
     * Blocking call that waits until a token becomes available.
     */
    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                refill();
                if (currentTokens >= 1.0) {
                    currentTokens -= 1.0;
                    return;
                }
                
                // Calculate time to wait until at least 1 token is available
                double missing = 1.0 - currentTokens;
                long waitNanos = (long) (missing / refillRatePerSecond * 1_000_000_000L);
                
                // Wait for the calculated time or until notified
                long waitMillis = waitNanos / 1_000_000L;
                int waitNanosRemainder = (int) (waitNanos % 1_000_000L);
                lock.wait(waitMillis, waitNanosRemainder);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long deltaNanos = now - lastRefillTimestampNanos;
        
        double tokensToAdd = deltaNanos * (refillRatePerSecond / 1_000_000_000.0);
        currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
        lastRefillTimestampNanos = now;
    }
}
```

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-params` in your classpath.

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
        // 10 tokens per second (1 every 100ms)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(110); // Wait for refill
        assertTrue(limiter.tryAcquire(), "Should have refilled 1 token");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.tryAcquire(); // Empty the bucket

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~100ms
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 90, "Should have blocked for at least 90ms");
    }

    @Test
    @DisplayName("Concurrent long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        double rate = 100.0; // 100 tokens per second
        double capacity = 10.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);
        
        int numThreads = 10;
        int durationSeconds = 2;
        AtomicInteger totalAcquired = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < durationSeconds * 1000) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    // Small yield to prevent 100% CPU pinning in this test
                    Thread.yield(); 
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Max allowed = (rate * seconds) + initial capacity
        int maxExpected = (int) (rate * durationSeconds + capacity);
        int actual = totalAcquired.get();

        System.out.println("Acquired: " + actual + " / Max Expected: " + maxExpected);
        assertTrue(actual <= maxExpected + 1, 
            "Rate limit exceeded! Acquired " + actual + " but max should be ~" + maxExpected);
    }
}
```

### Key Testing Notes:
1.  **Burst Test**: Verifies that the `capacity` parameter is respected immediately upon startup.
2.  **Refill Test**: Verifies that the lazy refill logic works after a sleep period.
3.  **Blocking Test**: Ensures `acquire()` actually pauses the thread and doesn't just return immediately.
4.  **Concurrent Test**: This is the most critical test. It spawns 10 threads aggressively trying to steal tokens for 2 seconds. It calculates the theoretical maximum $\text{tokens} = (\text{rate} \times \text{time}) + \text{capacity}$ and asserts that the actual count does not exceed this.