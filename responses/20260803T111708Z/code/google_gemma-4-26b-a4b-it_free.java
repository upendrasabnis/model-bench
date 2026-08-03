<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260803T111708Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses an **atomic, lock-free approach** based on `AtomicReference`. Instead of using a background thread to refill tokens (which is resource-intensive), we calculate the "refilled" amount lazily whenever a thread attempts to acquire a token.

### 1. Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design:
 * - Lazy Refill: Tokens are calculated based on time elapsed since the last access.
 * - Lock-free: Uses AtomicReference with a Compare-And-Swap (CAS) loop to ensure 
 *   thread safety without the overhead of synchronized blocks.
 * - Precision: Uses nanoseconds for high-resolution timing.
 * 
 * Complexity:
 * - Time: O(1) for both acquire and tryAcquire.
 * - Space: O(1).
 */
public class TokenBucket {

    private final long maxCapacity;
    private final long refillRatePerNanos;

    // State object held in an AtomicReference to ensure atomic updates of multiple variables
    private static class State {
        final double availableTokens;
        final long lastRefillNanos;

        State(double availableTokens, long lastRefillNanos) {
            this.availableTokens = availableTokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    private final AtomicReference<State> state;

    /**
     * @param maxCapacity Maximum tokens the bucket can hold (burst size).
     * @param refillRatePerSecond How many tokens are added per second.
     */
    public TokenBucket(long maxCapacity, double refillRatePerSecond) {
        if (maxCapacity <= 0 || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Capacity and rate must be positive");
        }
        this.maxCapacity = maxCapacity;
        // Pre-calculate rate to avoid division in the hot path
        this.refillRatePerNanos = (long) (refillRatePerSecond / 1_000_000_000.0); 
        // Note: For very low rates, we use double precision for the rate calculation
        this.state = new AtomicReference<>(new State(maxCapacity, System.nanoTime()));
    }

    /**
     * Non-blocking attempt to consume tokens.
     * @param tokens Number of tokens to consume.
     * @return true if tokens were consumed, false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        while (true) {
            long now = System.nanoTime();
            State current = state.get();
            
            // 1. Calculate how many tokens have been added since last check
            double elapsedNanos = Math.max(0, now - current.lastRefillNanos);
            double tokensToAdd = elapsedNanos * (getRefillRatePerNano());
            
            // 2. Calculate new balance
            double newBalance = Math.min(maxCapacity, current.availableTokens + tokensToAdd);

            // 3. Check if we have enough
            if (newBalance < tokens) {
                return false;
            }

            // 4. Try to commit the state change
            State newState = new State(newBalance - tokens, now);
            if (state.compareAndSet(current, newState)) {
                return true;
            }
            // If CAS fails, another thread updated the state; loop and retry.
        }
    }

    /**
     * Blocking attempt to consume tokens.
     * @param tokens Number of tokens to consume.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(int tokens) throws InterruptedException {
        while (!tryAcquire(tokens)) {
            // Simple backoff strategy: sleep for a small duration or until enough time passes.
            // In a production system, one might use a more sophisticated Wait/Notify or LockSupport.
            Thread.sleep(1); 
        }
    }

    private double getRefillRatePerNano() {
        // We use double to handle fractional tokens per nanosecond accurately
        return (double) (state.get().availableTokens / 1.0) == 0 ? 0 : 0; // Placeholder logic logic
    }

    // Re-implementing the rate logic inside the loop for precision
    private double getRefillRatePerNanoInternal(double ratePerSec) {
        return ratePerSec / 1_000_000_000.0;
    }

    // Corrected logic for the loop to handle precision
    private double refillRatePerNano;

    public TokenBucket(long maxCapacity, double refillRatePerSecond, boolean internal) {
        this.maxCapacity = maxCapacity;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        this.state = new AtomicReference<>(new State(maxCapacity, System.nanoTime()));
    }
    
    // Refined constructor for the actual implementation used below
}
```

*Wait, let me provide the clean, production-ready version in one block.*

```java
import java.util.concurrent.atomic.AtomicReference;

public class TokenBucket {
    private final long maxCapacity;
    private final double refillRatePerNano;
    private final AtomicReference<State> state;

    private static class State {
        final double availableTokens;
        final long lastRefillNanos;

        State(double availableTokens, long lastRefillNanos) {
            this.availableTokens = availableTokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    public TokenBucket(long maxCapacity, double refillRatePerSecond) {
        this.maxCapacity = maxCapacity;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        this.state = new AtomicReference<>(new State((double) maxCapacity, System.nanoTime()));
    }

    public boolean tryAcquire(int tokens) {
        while (true) {
            long now = System.nanoTime();
            State current = state.get();
            
            double elapsed = Math.max(0, now - current.lastRefillNanos);
            double refilledTokens = elapsed * refillRatePerNano;
            double newBalance = Math.min(maxCapacity, current.availableTokens + refilledTokens);

            if (newBalance < tokens) {
                return false;
            }

            State nextState = new State(newBalance - tokens, now);
            if (state.compareAndSet(current, nextState)) {
                return true;
            }
        }
    }

    public void acquire(int tokens) throws InterruptedException {
        while (!tryAcquire(tokens)) {
            Thread.sleep(1); // Minimal sleep to prevent CPU spinning
        }
    }
}
```

---

### 2. JUnit 5 Tests

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
        TokenBucket bucket = new TokenBucket(10, 1.0); // 10 capacity, 1 token/sec
        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryAcquire(1), "Should allow burst up to capacity");
        }
        assertFalse(bucket.tryAcquire(1), "Should deny after burst capacity exceeded");
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void testRefill() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1, 10.0); // 1 capacity, 10 tokens/sec (1 every 100ms)
        
        assertTrue(bucket.tryAcquire(1));
        assertFalse(bucket.tryAcquire(1));

        Thread.sleep(150); // Wait for refill
        assertTrue(bucket.tryAcquire(1), "Should have refilled at least 1 token");
    }

    @Test
    @DisplayName("Concurrent test: Long-run rate should not exceed configured limit")
    void testConcurrentRateLimiting() throws InterruptedException {
        int refillRatePerSec = 100;
        int testDurationSec = 2;
        int maxBurst = 10;
        TokenBucket bucket = new TokenBucket(maxBurst, refillRatePerSec);
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger successfulAcquires = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        // Task: Constantly try to acquire tokens
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() - startTime < (testDurationSec * 1000)) {
                    if (bucket.tryAcquire(1)) {
                        successfulAcquires.incrementAndGet();
                    }
                    // Small yield to prevent extreme contention starvation
                    Thread.yield(); 
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(testDurationSec + 1, TimeUnit.SECONDS);

        // Expected = Burst + (Rate * Seconds)
        // We add a small buffer (10%) for timing jitter in the test environment
        double expectedMax = maxBurst + (refillRatePerSec * testDurationSec);
        double buffer = expectedMax * 0.15; 

        assertTrue(successfulAcquires.get() <= expectedMax + buffer, 
            String.format("Rate exceeded! Expected ~%f, got %d", expectedMax, successfulAcquires.get()));
        
        // Ensure it's not too low (checks that it actually worked)
        assertTrue(successfulAcquires.get() > (refillRatePerSec * testDurationSec) * 0.8, 
            "Rate too low, limiter might be too aggressive");
    }
}
```

### Design Summary
1.  **AtomicReference & CAS**: Instead of `synchronized`, I used `AtomicReference<State>`. This allows multiple threads to attempt updates simultaneously. If two threads collide, one fails the `compareAndSet` and immediately retries with the new state.
2.  **State Object**: Since we need to update both `availableTokens` and `lastRefillNanos` atomically, they are encapsulated in an immutable `State` object. This prevents the "lost update" problem where one thread updates tokens and another updates the timestamp.
3.  **Lazy Refilling**: No background thread is used. This is highly efficient for systems where the rate limiter might be idle for long periods. The math `(now - lastRefill) * rate` is performed on-demand.
4.  **Precision**: Using `System.nanoTime()` and `double` for token math ensures that even high-frequency rate limiters (e.g., 1,000,000 tokens/sec) function accurately.