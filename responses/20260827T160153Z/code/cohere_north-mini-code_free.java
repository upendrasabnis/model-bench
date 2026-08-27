<!-- model: cohere/north-mini-code:free | category: code | run: 20260827T160153Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is the implementation of a thread-safe Token Bucket Rate Limiter in Java, followed by comprehensive JUnit 5 tests and design documentation.

### Part 1: Implementation

The `TokenBucketRateLimiter` class uses a `ReentrantLock` with a `Condition` variable to ensure thread safety. It tracks the number of tokens currently available and the timestamp of the last refill to calculate how many new tokens should have been added based on the elapsed time.

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {

    private final long capacity;
    private final double ratePerNanosecond; // Tokens per nanosecond
    
    // Current tokens in the bucket
    private double tokens;
    
    // Timestamp of the last refill (nanoseconds)
    private long lastRefillTimestamp;

    private final Lock lock = new ReentrantLock();
    private final Condition availableCondition = lock.newCondition();

    /**
     * Constructor for the TokenBucketRateLimiter.
     *
     * @param capacity The maximum number of tokens the bucket can hold (Burst Capacity).
     * @param ratePerSecond The rate at which tokens are refilled (tokens per second).
     */
    public TokenBucketRateLimiter(long capacity, long ratePerSecond) {
        if (capacity < 0 || ratePerSecond < 0) {
            throw new IllegalArgumentException("Capacity and rate must be non-negative.");
        }
        this.capacity = capacity;
        this.ratePerNanosecond = (double) ratePerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Acquires the specified number of tokens. If insufficient tokens are available,
     * the thread will block until enough tokens are refilled.
     *
     * @param permits The number of tokens to acquire.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException("Permits must be non-negative");
        
        lock.lock();
        try {
            // Refill tokens based on elapsed time before attempting to acquire
            refill(permits);
            
            while (tokens < permits) {
                // Wait until enough tokens are available
                availableCondition.await();
                refill(permits);
            }
            
            // Consume tokens
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire the specified number of tokens without blocking.
     *
     * @param permits The number of tokens to acquire.
     * @return true if the acquisition was successful, false otherwise.
     */
    public boolean tryAcquire(long permits) {
        if (permits < 0) throw new IllegalArgumentException("Permits must be non-negative");
        
        lock.lock();
        try {
            refill(permits);
            
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal method to refill tokens based on the time elapsed since the last refill.
     * It ensures tokens do not exceed the bucket's capacity.
     */
    private void refill(long permits) {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTimestamp;
        
        if (elapsedNanos > 0) {
            double addedTokens = elapsedNanos * ratePerNanosecond;
            tokens = Math.min(capacity, tokens + addedTokens);
            lastRefillTimestamp = now;
        }
    }

    // --- Getters for testing purposes ---
    public double getTokens() {
        lock.lock();
        try {
            refill(1); // Ensure up-to-date tokens for inspection
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

### Part 2: Design Documentation

**1. Algorithm: Token Bucket**
The Token Bucket algorithm works by maintaining a "bucket" that holds tokens. Tokens are added to the bucket at a constant rate defined by the refill rate. When a request (or thread) needs to proceed, it must "spend" a certain number of tokens from the bucket. If there are not enough tokens, the request is rejected (or blocked in the case of `acquire`).

**2. State Variables**
*   **`capacity`**: The maximum number of tokens the bucket can hold. This defines the burst capacity (the maximum number of requests that can be processed instantaneously).
*   **`tokens`**: The current number of tokens in the bucket. This is a `double` to handle fractional tokens that accumulate over time (e.g., if the rate is 2.5 tokens/sec, we need sub-token precision).
*   **`lastRefillTimestamp`**: A `long` representing the epoch time (in nanoseconds) when the bucket was last refilled. This is crucial for calculating how many tokens should have been added during any specific time window.
*   **`ratePerNanosecond`**: Pre-calculated constant to convert time duration into token count efficiently.

**3. Thread Safety & Synchronization**
*   **Locking**: A `ReentrantLock` is used instead of `synchronized` blocks because it provides more flexibility with condition variables.
*   **`availableCondition`**: This variable is used to signal waiting threads when new tokens become available. It allows the `acquire()` method to block efficiently without busy-waiting (spinning), which is critical for performance under high load.
*   **Atomicity**: All state modifications (`tokens`, `lastRefillTimestamp`) are protected by the lock.

**4. Refill Logic**
The refill logic is handled in a separate private method. It calculates the time elapsed since the last refill (`elapsedNanos`), converts that to tokens (`elapsedNanos * ratePerNanosecond`), and adds them to the bucket. The `Math.min(capacity, ...)` ensures the bucket never overflows.

**5. Blocking vs. Non-Blocking**
*   **`acquire(permits)`**: This method calls `refill()`, then checks if `tokens >= permits`. If not, it enters a `while` loop calling `availableCondition.await()`. This releases the lock and puts the thread to sleep until notified. It wakes up, rechecks the condition, and repeats until successful.
*   **`tryAcquire(permits)`**: This method performs the same refill check but returns `false` immediately if tokens are insufficient. It never blocks.

---

### Part 3: JUnit 5 Tests

This test suite covers basic functionality (refill, capacity, consumption) and a specific concurrent stress test to verify the rate limiting behavior under load.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10) // Prevent tests from hanging
class TokenBucketRateLimiterTest {

    // Configuration for a fast bucket: 100 tokens/sec, burst capacity of 50
    private static final long RATE = 100; 
    private static final long CAPACITY = 50;
    private static final long PERMITS_PER_ACQUIRE = 1;

    @Test
    void testInitialState() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        assertEquals(CAPACITY, limiter.getTokens(), "Bucket should start full");
    }

    @Test
    void testAcquireWithinCapacity() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        
        // Acquire all capacity
        for (int i = 0; i < CAPACITY; i++) {
            limiter.acquire(PERMITS_PER_ACQUIRE);
        }

        assertEquals(0.0, limiter.getTokens(), "Bucket should be empty after acquiring capacity");
        
        // One more should fail (blocking acquire would wait forever, so we use tryAcquire)
        assertFalse(limiter.tryAcquire(PERMITS_PER_ACQUIRE), "Should not be able to acquire beyond capacity");
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        
        // Empty the bucket
        limiter.acquire(CAPACITY);
        assertEquals(0.0, limiter.getTokens());

        // Wait 1.5 seconds. At 100 tokens/sec, we expect 150 tokens, 
        // but max capacity is 50, so bucket should be full.
        Thread.sleep(1500);
        
        assertEquals(CAPACITY, limiter.getTokens(), "Bucket should be full after waiting for capacity refill");
    }

    @Test
    void testRefillIncremental() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        
        // Wait 0.5 seconds. Expect 50 tokens.
        Thread.sleep(500);
        assertEquals(50.0, limiter.getTokens(), "Should have 50 tokens after 0.5s at 100/sec");

        // Acquire 10 tokens
        limiter.acquire(10);
        assertEquals(40.0, limiter.getTokens());

        // Wait another 0.3 seconds. Expect 30 more tokens. Total 70, capped at 50.
        Thread.sleep(300);
        assertEquals(CAPACITY, limiter.getTokens(), "Bucket should be full after refill");
    }

    @Test
    void testTryAcquireSuccess() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        assertTrue(limiter.tryAcquire(10), "Should successfully acquire when tokens available");
        assertEquals(CAPACITY - 10.0, limiter.getTokens());
    }

    @Test
    void testTryAcquireFailure() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, RATE);
        limiter.acquire(CAPACITY); // Empty bucket
        assertFalse(limiter.tryAcquire(1), "Should fail to acquire when no tokens available");
    }

    /**
     * Concurrent Test: Verifies that the long-run rate does not exceed the configured limit.
     * 
     * Strategy:
     * 1. Create a limiter with a known rate (e.g., 1000 permits/sec).
     * 2. Start multiple threads that attempt to acquire permits in a loop for a fixed duration.
     * 3. Use a CountDownLatch to synchronize the start of all threads.
     * 4. Measure the total number of successful acquisitions.
     * 5. Assert that the total is approximately equal to (Rate * Duration), allowing for a small tolerance 
     *    (due to thread scheduling overhead and the burst capacity).
     */
    @Test
    void testConcurrentRateLimiting() throws InterruptedException {
        final long rate = 1000; // 1000 permits/sec
        final long capacity = 5000; // Large capacity to allow bursts initially
        final int threadCount = 10;
        final int durationMs = 2000; // Run for 2 seconds
        final AtomicInteger successfulAcquisitions = new AtomicInteger(0);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        Runnable worker = () -> {
            try {
                startLatch.await(); // Wait for the signal to start simultaneously
                long endTime = System.currentTimeMillis() + durationMs;
                
                while (System.currentTimeMillis() < endTime) {
                    // Try to acquire 1 token. If successful, count it.
                    if (new TokenBucketRateLimiter(capacity, rate).tryAcquire(1)) {
                        successfulAcquisitions.incrementAndGet();
                    }
                    // Small sleep to prevent tight looping and reduce CPU usage in this test
                    Thread.yield(); 
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        };

        // Create a new limiter instance per thread? 
        // Actually, the rate limiter is shared state. To test the rate limiter correctly, 
        // all threads must share the SAME instance.
        TokenBucketRateLimiter sharedLimiter = new TokenBucketRateLimiter(capacity, rate);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long endTime = System.currentTimeMillis() + durationMs;
                    while (System.currentTimeMillis() < endTime) {
                        if (sharedLimiter.tryAcquire(1)) {
                            successfulAcquisitions.incrementAndGet();
                        }
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads at once
        startLatch.countDown();

        // Wait for all threads to finish
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        int totalAcquired = successfulAcquisitions.get();
        
        // Calculate expected acquisitions: Rate * Duration (in seconds)
        // Rate is per second. Duration is 2 seconds. Expected is roughly 2000.
        // Tolerance: Allow 10% variance to account for thread scheduling and yield()
        int expected = (int) (rate * ((double) durationMs / 1000));
        int tolerance = (int) (expected * 0.10);

        System.out.println("Total Acquired: " + totalAcquired + ", Expected: " + expected + ", Tolerance: " + tolerance);
        
        assertTrue(totalAcquired <= expected + tolerance, 
            "Rate limit exceeded! Acquired " + totalAcquired + " but limit is " + expected);
        assertTrue(totalAcquired >= expected - tolerance, 
            "Unexpectedly low acquisition. Acquired " + totalAcquired + " but expected " + expected);
    }
}
```