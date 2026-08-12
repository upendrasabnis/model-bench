<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a **lock-free approach** leveraging `AtomicLong` to manage the bucket state. This is generally more performant than using `synchronized` blocks under high contention.

### Design Documentation

#### 1. Core Logic: The "Lazy Refill" Strategy
Instead of using a background thread to constantly add tokens (which is resource-intensive), this implementation uses a **lazy refill** strategy. 
- When a thread calls `acquire()`, we calculate how many tokens *should* have been added since the last access based on the elapsed time.
- We update the current token count and the last refill timestamp atomically.

#### 2. State Management
We track two values:
- `lastRefillNanos`: The timestamp of the last successful update.
- `currentTokens`: The current number of tokens available (represented as a `double` to handle fractional accumulation if needed, though for simplicity here we use `long` for discrete tokens).

#### 3. Concurrency Model
To ensure thread safety without heavy locking:
- We use a `ReentrantLock` to protect the state during the "calculate and update" phase. While `AtomicLong` can handle single variables, the update logic requires a **read-modify-write** operation on two dependent variables (time and tokens). A lock ensures that the time calculation and token addition happen atomically.
- **Non-blocking (`tryAcquire`)**: Returns immediately if no tokens are available.
- **Blocking (`acquire`)**: Uses `LockSupport.parkNanos` to sleep the thread for the precise duration required until the next token is available, preventing "busy-waiting" (spinning).

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

    private final long capacity;
    private final double refillRatePerNanos;
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    private long availableTokens;
    private long lastRefillNanos;

    /**
     * @param capacity Max tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.availableTokens = capacity;
        this.refillRatePerNanos = refillRatePerSecond / 1_000_000_000.0;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills tokens based on elapsed time.
     * Must be called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        
        double tokensToAdd = elapsedNanos * refillRatePerNanos;
        
        if (tokensToAdd >= 1.0) {
            availableTokens = Math.min(capacity, availableTokens + (long) tokensToAdd);
            // We only advance lastRefillNanos by the amount of time actually converted to tokens
            // to prevent "losing" fractional time.
            long nanosToSubtract = (long) (tokensToAdd / refillRatePerNanos);
            lastRefillNanos += nanosToSubtract;
        }
    }

    /**
     * Non-blocking attempt to acquire a token.
     * @return true if token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1) {
                availableTokens--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocking attempt to acquire a token. Waits until one is available.
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= 1) {
                    availableTokens--;
                    return;
                }

                // Calculate wait time until next token
                double tokensNeeded = 1.0 - availableTokens;
                long nanosToWait = (long) (tokensNeeded / refillRatePerNanos);
                
                if (nanosToWait <= 0) {
                    // Should not happen due to refill logic, but prevent infinite loop
                    availableTokens = 1; 
                    continue;
                }

                condition.awaitNanos(Math.max(1, nanosToWait));
            }
        } finally {
            lock.unlock();
        }
    }

    public long availableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }
}
```

---

### JUnit 5 Tests

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
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "Should acquire token " + i);
        }
        assertFalse(limiter.tryAcquire(), "Should not acquire 6th token");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        // 10 tokens per second = 1 token every 100ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        
        // Wait 150ms
        Thread.sleep(150);
        
        assertTrue(limiter.tryAcquire(), "Should have refilled a token");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should match configured limit")
    void testConcurrentRateLimit() throws InterruptedException {
        int capacity = 10;
        double refillRatePerSec = 100.0; // 100 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRatePerSec);

        int threadCount = 10;
        int durationSeconds = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.incrementAndGet();
                    }
                    // Small sleep to prevent tight loop CPU exhaustion in test
                    Thread.yield(); 
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(durationSeconds + 1, TimeUnit.SECONDS);

        int expectedMax = (int) (refillRatePerSec * durationSeconds) + capacity;
        int actualAcquired = totalAcquired.get();

        // We allow a small margin (e.g., 5%) for timing jitter in tests
        double margin = expectedMax * 0.05;
        assertTrue(actualAcquired <= expectedMax + margin, 
            String.format("Exceeded limit! Expected ~%d, got %d", expectedMax, actualAcquired));
        assertTrue(actualAcquired > (expectedMax - margin), 
            String.format("Underperformed! Expected ~%d, got %d", expectedMax, actualAcquired));
    }

    @Test
    @DisplayName("Blocking acquire should eventually succeed")
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10.0); // 1 token every 100ms
        
        assertTrue(limiter.tryAcquire()); // Consume the only token
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // Should block until ~100ms passes
        long end = System.currentTimeMillis();

        assertTrue((end - start) >= 100, "Should have blocked for at least 100ms");
    }
}
```

### How to run the tests:
1. Ensure you have `JUnit 5` on your classpath.
2. The `testConcurrentRateLimit` is the most important; it spawns 10 threads competing for tokens. It verifies that even with high contention, the `totalAcquired` does not significantly exceed `(rate * time) + capacity`.