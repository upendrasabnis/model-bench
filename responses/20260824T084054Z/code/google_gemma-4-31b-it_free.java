<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260824T084054Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: Token Bucket Rate Limiter

#### Architecture
The `TokenBucket` implementation uses a **lazy-refill strategy**. Instead of using a background thread to add tokens periodically (which would be resource-intensive), the bucket calculates how many tokens should have been added since the last request based on the elapsed time.

#### Key Components
1.  **State Management**: 
    *   `availableTokens`: Tracks current tokens.
    *   `lastRefillTimestamp`: Tracks the last time tokens were updated.
2.  **Concurrency Control**:
    *   The class uses `synchronized` blocks (or a `ReentrantLock`) to ensure that the read-modify-write cycle of updating tokens is atomic.
    *   `wait()` and `notifyAll()` are used to implement the blocking `acquire()` method, putting threads to sleep until enough tokens are generated.
3.  **Complexity**:
    *   **Time Complexity**: $O(1)$ for both `tryAcquire` and `acquire`.
    *   **Space Complexity**: $O(1)$.

#### Logic
*   **Refill**: `tokensToAdd = (currentTime - lastRefillTime) * refillRatePerNano`.
*   **Burst**: The `availableTokens` is capped at `capacity`.
*   **Blocking**: If tokens are insufficient, the thread calculates the required wait time based on the refill rate and sleeps.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;

public class TokenBucket {
    private final long capacity;
    private final double refillRatePerNano;
    
    private double availableTokens;
    private long lastRefillTimestamp;

    /**
     * @param capacity Maximum tokens the bucket can hold (burst size).
     * @param refillRate Tokens to add per second.
     */
    public TokenBucket(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRatePerNano = (double) refillRate / TimeUnit.SECONDS.toNanos(1);
        this.availableTokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    private synchronized void refill() {
        long now = System.nanoTime();
        long nanosElapsed = now - lastRefillTimestamp;
        double tokensToAdd = nanosElapsed * refillRatePerNano;
        
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Blocking call to acquire a token.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return;
            }

            // Calculate time to wait until at least 1 token is available
            double missingTokens = 1.0 - availableTokens;
            long waitNanos = (long) (missingTokens / refillRatePerNano);
            
            // Convert nanos to millis for wait()
            long waitMillis = waitNanos / 1_000_000;
            int waitNanosRemainder = (int) (waitNanos % 1_000_000);
            
            this.wait(waitMillis, waitNanosRemainder);
        }
    }
}
```

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class TokenBucketTest {

    @Test
    @DisplayName("Should allow burst up to capacity")
    void testBurstCapacity() {
        TokenBucket bucket = new TokenBucket(5, 1); // Capacity 5, 1 token/sec
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Should acquire token " + (i + 1));
        }
        assertFalse(bucket.tryAcquire(), "Should not acquire 6th token");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 10); // 10 tokens per second
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());

        // Wait 200ms -> should refill ~2 tokens
        Thread.sleep(200);
        assertTrue(bucket.tryAcquire(), "Should have refilled at least one token");
    }

    @Test
    @DisplayName("Acquire should block until token is available")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 1); // 1 token/sec
        bucket.tryAcquire(); // Empty the bucket

        long start = System.currentTimeMillis();
        bucket.acquire(); // Should block for ~1 second
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 900, "Should have blocked for roughly 1 second, but took " + duration + "ms");
    }

    @Test
    @DisplayName("Concurrent long-run rate test")
    void testConcurrentRateLimit() throws InterruptedException {
        int refillRate = 100; // 100 tokens per second
        int capacity = 10;
        TokenBucket bucket = new TokenBucket(capacity, refillRate);
        
        int threadCount = 10;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    if (bucket.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - startTime;
        
        // Max allowed = Burst Capacity + (Rate * DurationInSeconds)
        double maxAllowed = capacity + (refillRate * (durationMs / 1000.0));
        
        assertTrue(totalAcquired.get() <= maxAllowed + 1, 
            String.format("Acquired %d tokens, but max allowed was ~%.2f", totalAcquired.get(), maxAllowed));
    }
}
```

### Key Testing Explanations:
1.  **`testBurstCapacity`**: Ensures the `capacity` parameter is respected and doesn't allow more than the burst size immediately.
2.  **`testBlockingAcquire`**: Verifies that `acquire()` actually puts the thread to sleep and wakes up when the refill logic permits.
3.  **`testConcurrentRateLimit`**: This is the stress test. It spawns multiple threads hammering the bucket. It uses the formula $\text{Total} \le \text{Capacity} + (\text{Rate} \times \text{Time})$ to verify that the limiter doesn't "leak" extra tokens under high contention.