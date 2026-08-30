<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260830T080453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### 1. Algorithm
The **Token Bucket** algorithm allows for a certain amount of "burstiness" while maintaining a strict long-term average rate. 
- **Capacity ($C$):** The maximum number of tokens the bucket can hold. This defines the burst size.
- **Refill Rate ($R$):** The number of tokens added to the bucket per second.
- **Mechanism:** Instead of using a background timer thread to add tokens (which is resource-intensive), this implementation uses **lazy refills**. Whenever a request arrives, the limiter calculates how many tokens should have been added since the last request based on the elapsed time.

#### 2. Thread Safety
To ensure safety across multiple threads:
- **`AtomicLong` / `volatile`:** Not sufficient for the compound operation of "calculate refill $\rightarrow$ check balance $\rightarrow$ subtract".
- **`synchronized` block:** Used to protect the state (current tokens and last refill timestamp). This ensures that token consumption and refills are atomic.
- **`wait()` and `notifyAll()`:** Used to implement the blocking `acquire()` method, putting threads to sleep until enough tokens are available.

#### 3. Complexity
- **Time Complexity:** $O(1)$ for both `tryAcquire` and `acquire`.
- **Space Complexity:** $O(1)$.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final long capacity;
    private final double refillRatePerNano;
    
    private double currentTokens;
    private long lastRefillTimestamp;

    /**
     * @param capacity Maximum tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, long refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerNano = (double) refillRatePerSecond / TimeUnit.SECONDS.toNanos(1);
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
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
     * Waits until a token becomes available.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (currentTokens >= 1.0) {
                currentTokens -= 1.0;
                return;
            }
            
            // Calculate time to wait until at least 1 token is available
            double missing = 1.0 - currentTokens;
            long waitNanos = (long) (missing / refillRatePerNano);
            
            // Sleep for the required time (converting nanos to millis for wait())
            long waitMillis = waitNanos / 1_000_000;
            int waitNanosRemainder = (int) (waitNanos % 1_000_000);
            
            this.wait(waitMillis, waitNanosRemainder);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long deltaNanos = now - lastRefillTimestamp;
        double tokensToAdd = deltaNanos * refillRatePerNano;
        
        currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

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
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should allow burst up to 5");
        }
        assertFalse(limiter.tryAcquire(), "Should be empty after burst");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // Capacity 1, Refill 1 per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        Thread.sleep(1100); // Wait for refill
        assertTrue(limiter.tryAcquire(), "Token should have refilled");
    }

    @Test
    @DisplayName("Blocking acquire should wait for tokens")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        limiter.tryAcquire(); // Consume initial token

        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block for ~1 second
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 1000, "Should have blocked for at least 1 second");
    }

    @Test
    @DisplayName("Concurrent long-run rate should not exceed limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int capacity = 10;
        int ratePerSecond = 50;
        int numThreads = 10;
        int testDurationSeconds = 3;
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, ratePerSecond);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < testDurationSeconds * 1000) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Theoretical max = burst + (rate * time)
        int maxExpected = capacity + (ratePerSecond * testDurationSeconds);
        int actual = totalAcquired.get();

        assertTrue(actual <= maxExpected, 
            String.format("Acquired %d tokens, but max allowed was %d", actual, maxExpected));
        
        // Verify it's actually producing tokens (not just returning 0)
        assertTrue(actual > (ratePerSecond * (testDurationSeconds - 1)), 
            "Should have acquired close to the expected rate");
    }
}
```

### Key Testing Points:
1. **`testBurstCapacity`**: Ensures the bucket starts full and allows the exact burst size before failing.
2. **`testRefill`**: Validates the lazy refill logic by waiting and attempting a re-acquire.
3. **`testBlockingAcquire`**: Verifies that the `wait()` logic correctly pauses the thread.
4. **`testConcurrentRateLimit`**: Spawns multiple threads hammering the limiter. It calculates the theoretical maximum tokens available over 3 seconds ($\text{Capacity} + \text{Rate} \times \text{Time}$) and asserts that the actual count does not exceed this value.