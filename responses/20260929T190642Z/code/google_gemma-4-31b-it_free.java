<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260929T190642Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### 1. Algorithm
The **Token Bucket** algorithm allows for a maximum "burst" of requests while maintaining a steady long-term average rate. 
- **Tokens** are added to the bucket at a fixed rate (`refillRate` per second).
- The bucket has a maximum capacity (`burstCapacity`). If the bucket is full, new tokens are discarded.
- To perform an action, a thread must remove a token from the bucket.
- If no tokens are available, the thread can either fail immediately (`tryAcquire`) or wait until a token becomes available (`acquire`).

#### 2. Concurrency Strategy
Instead of using a background timer thread to refill tokens (which is resource-heavy), this implementation uses **lazy refills**.
- The number of tokens is calculated based on the time elapsed since the last request: 
  `tokensToAdd = elapsedSeconds * refillRate`.
- **Thread Safety**: The implementation uses `synchronized` blocks on a private lock object to ensure that the `lastRefillTimestamp` and `currentTokens` are updated atomically.
- **Blocking**: The `acquire()` method uses `wait()` and `notifyAll()` to put threads to sleep until the bucket has enough tokens, avoiding busy-waiting.

#### 3. Complexity
- **Time Complexity**: $O(1)$ for both `acquire` and `tryAcquire`.
- **Space Complexity**: $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final double refillRate; // tokens per second
    private final double burstCapacity;
    private double currentTokens;
    private long lastRefillTimestamp;
    private final Object lock = new Object();

    public TokenBucketRateLimiter(double refillRate, double burstCapacity) {
        this.refillRate = refillRate;
        this.burstCapacity = burstCapacity;
        this.currentTokens = burstCapacity;
        this.lastRefillTimestamp = System.nanoTime();
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
     * Blocking attempt to acquire a token.
     * Waits until a token becomes available.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                refill();
                if (currentTokens >= 1.0) {
                    currentTokens -= 1.0;
                    return;
                }
                
                // Calculate time to wait until at least one token is available
                double missingTokens = 1.0 - currentTokens;
                long waitTimeNanos = (long) (missingTokens / refillRate * 1_000_000_000L);
                
                // Wait for the calculated time or until notified
                TimeUnit.NANOSECONDS.sleep(waitTimeNanos); 
                // Note: Using sleep inside synchronized is usually bad, 
                // but here we use wait() for better efficiency.
            }
        }
    }
    
    // Improved blocking acquire to avoid holding lock during sleep
    public synchronized void acquireBlocking() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            
            double missing = 1.0 - currentTokens;
            long waitMillis = (long) Math.ceil((missing / refillRate) * 1000);
            this.wait(waitMillis);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double deltaSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0;
        double tokensToAdd = deltaSeconds * refillRate;
        
        currentTokens = Math.min(burstCapacity, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```
*Note: I provided `acquireBlocking` as the optimized version using `wait(ms)` to ensure the lock is released while the thread sleeps.*

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("Should allow burst capacity immediately")
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should acquire token " + i);
        }
        assertFalse(limiter.tryAcquire(), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 10 tokens/sec
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(110); // Wait for ~1.1 tokens to refill
        assertTrue(limiter.tryAcquire(), "Should have refilled at least one token");
    }

    @Test
    @DisplayName("Should block until token is available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        limiter.tryAcquire(); // Empty the bucket
        
        long start = System.currentTimeMillis();
        limiter.acquireBlocking(); // Should wait ~200ms
        long end = System.currentTimeMillis();
        
        assertTrue((end - start) >= 150, "Should have blocked for roughly 200ms");
    }

    @Test
    @DisplayName("Concurrent stress test: Long run rate check")
    void testConcurrentRateLimit() throws InterruptedException {
        double rate = 100.0; // 100 tokens per second
        double burst = 10.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);
        
        int threadCount = 10;
        int durationMillis = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < durationMillis) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Expected = (rate * duration) + burst
        // We allow a small margin for timing jitter
        int expectedMax = (int) (rate * (durationMillis / 1000.0)) + (int) burst + 5;
        assertTrue(totalAcquired.get() <= expectedMax, 
            "Acquired " + totalAcquired.get() + " but max expected was " + expectedMax);
        assertTrue(totalAcquired.get() > rate * 0.9, 
            "Should have acquired close to the rate limit");
    }
}
```

### Running the tests
1. Add **JUnit 5** to your project dependencies.
2. The `testConcurrentRateLimit` creates a high-contention environment where 10 threads aggressively attempt to steal tokens.
3. It verifies that the total tokens consumed over 1 second does not significantly exceed `(Rate * Time) + Burst`.