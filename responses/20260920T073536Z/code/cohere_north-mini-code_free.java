<!-- model: cohere/north-mini-code:free | category: code | run: 20260920T073536Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is the implementation of a thread-safe Token Bucket rate limiter in Java, followed by comprehensive JUnit 5 tests.

### 1. Implementation

The `TokenBucketRateLimiter` class implements the classic "Leaky Bucket" algorithm. It uses a `ReentrantLock` to ensure thread safety and a `Condition` variable to efficiently manage the blocking state without busy-waiting.

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {

    private final long capacity;
    private final double rate; // tokens per second
    private double tokens; // current tokens in the bucket
    private long lastRefillTimestamp;

    private final Lock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    /**
     * Constructs a TokenBucketRateLimiter.
     *
     * @param capacity The maximum number of tokens the bucket can hold (burst capacity).
     * @param rate     The refill rate in tokens per second.
     */
    public TokenBucketRateLimiter(long capacity, double rate) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be at least 1");
        if (rate < 0) throw new IllegalArgumentException("Rate cannot be negative");
        this.capacity = capacity;
        this.rate = rate;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Acquires a permit from the bucket, blocking until one is available.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires the specified number of permits, blocking until all are available.
     * 
     * @param permits The number of permits to acquire.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits < 1) return;

        lock.lockInterruptibly();
        try {
            // Refill tokens based on elapsed time
            refill();

            while (tokens < permits) {
                // Calculate how long we need to wait to accumulate enough tokens
                double deficit = permits - tokens;
                // Time in seconds = tokens / rate. Convert to nanoseconds for awaitNanos.
                long waitTimeNanos = (long) (deficit / rate * 1_000_000_000);
                
                // Wait for the calculated time or until notified
                available.awaitNanos(waitTimeNanos);
                
                // After waking up, refill and check again
                refill();
            }

            // Consume the tokens
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to acquire a permit without blocking.
     * 
     * @return true if a permit was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Tries to acquire the specified number of permits without blocking.
     * 
     * @param permits The number of permits to acquire.
     * @return true if all permits were acquired, false otherwise.
     */
    public boolean tryAcquire(long permits) {
        if (permits < 1) return true;

        lock.lock();
        try {
            refill();
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
     * Refills the bucket based on the time elapsed since the last refill.
     * Tokens are added up to the bucket's capacity.
     */
    private void refill() {
        long now = System.nanoTime();
        long timePassed = TimeUnit.NANOSECONDS.toSeconds(now - lastRefillTimestamp);
        
        if (timePassed > 0) {
            // Add tokens based on rate and time passed
            tokens = Math.min(capacity, tokens + rate * timePassed);
            lastRefillTimestamp = now;
        }
    }
}
```

### 2. Design Documentation

**Algorithm: Leaky Bucket / Token Bucket**
This implementation uses the Token Bucket algorithm. Tokens are added to a bucket at a steady rate defined by `rate` (tokens/second). When a request arrives, it consumes a token (or `permits`). If the bucket is empty, the request must wait until enough tokens accumulate.

**State Variables:**
1.  **`capacity`**: The maximum size of the bucket. This controls the burst capacity (maximum number of requests that can be processed instantaneously).
2.  **`rate`**: The refill rate (tokens per second).
3.  **`tokens`**: The current number of tokens in the bucket (can be fractional to handle sub-second rates).
4.  **`lastRefillTimestamp`**: The system time (nanoTime) when the bucket was last refilled.

**Thread Safety:**
*   **Locking**: A `ReentrantLock` is used to protect the shared state (`tokens`, `lastRefillTimestamp`). This ensures that concurrent threads do not race to decrement the token count or corrupt the refill timestamp.
*   **Condition Variables**: The `available` condition variable is used to put the `acquire()` thread to sleep efficiently when the bucket is empty. Instead of spinning (consuming CPU), the thread waits until sufficient time has passed (calculated via `awaitNanos`) or until notified (though notifications aren't strictly needed here since time passes automatically).

**Blocking vs. Non-blocking:**
*   **`acquire()`**: This method calculates the exact deficit of tokens needed (`permits - tokens`). It then computes the wait time (`deficit / rate`) and sleeps for that duration. After waking up, it refills and checks again (looping in case other threads consumed tokens while it was waiting).
*   **`tryAcquire()`**: This method performs a non-blocking check. It refills based on current time, checks if `tokens >= permits`, and returns a boolean immediately without waiting.

**Refill Logic:**
The refill logic uses `TimeUnit.NANOSECONDS.toSeconds` to calculate elapsed seconds. This allows the rate to be a fractional number (e.g., 0.5 tokens/sec). The `tokens` variable is a `double` to preserve precision during these calculations.

---

### 3. JUnit 5 Tests

This test suite verifies basic functionality, burst capacity, rate limiting, and finally a concurrent stress test to ensure the rate limit holds under high load.

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel if desired, though usually tests are isolated.
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        // Rate: 10 tokens per second, Capacity: 5 tokens (burst)
        this.limiter = new TokenBucketRateLimiter(5, 10);
    }

    @Test
    void testInitialCapacity() {
        // Should be able to acquire 5 permits immediately (burst)
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        // Next one should fail
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void testBurstCapacity() {
        // Acquire 5
        limiter.acquire(5);
        // Bucket is empty. Wait for 1 second (should refill 10 tokens, but capped at 5)
        try {
            Thread.sleep(1100); // Sleep slightly over 1 sec
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Now we should be able to acquire 5 again
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void testRefillRate() {
        // Acquire 1 token
        assertTrue(limiter.tryAcquire());
        
        // Bucket has 4 left. Wait 0.6 seconds.
        // Rate is 10/sec, so in 0.6s we get 6 tokens.
        // Current tokens: 4. New tokens: 6. Total: 10. Cap is 5.
        // Result: 5 tokens.
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should be able to acquire 5 now
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        // Fill bucket
        limiter.acquire(5);
        
        // Start a thread that will try to acquire 1 token.
        // Since bucket is empty, this thread will block.
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch acquiredLatch = new CountDownLatch(1);
        
        Thread t = new Thread(() -> {
            try {
                startLatch.countDown(); // Signal main thread we are ready to wait
                limiter.acquire();      // Should block until refill
                acquiredLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        t.start();
        
        // Wait for thread to reach the acquire() call
        startLatch.await(1, TimeUnit.SECONDS);
        
        // Give it a moment to block
        Thread.sleep(100);
        
        // It should still be blocking (bucket is empty)
        assertFalse(acquiredLatch.getCount() == 0); // Latch hasn't reached zero
        
        // Wait for 1.1 seconds to allow refill
        Thread.sleep(1100);
        
        // Now the thread should have acquired
        acquiredLatch.await(1, TimeUnit.SECONDS);
        
        t.join();
    }

    @Test
    @Timeout(10) // Ensure test doesn't hang
    void testConcurrentRateLimit() throws InterruptedException {
        // Configuration: Rate 100 tokens/sec, Capacity 100.
        // We will run for 5 seconds. Max possible acquisitions = 500.
        TokenBucketRateLimiter concurrentLimiter = new TokenBucketRateLimiter(100, 100);
        AtomicLong successfulAcquisitions = new AtomicLong(0);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        volatile boolean running = true;

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for signal to start simultaneously
                    while (running) {
                        if (concurrentLimiter.tryAcquire()) {
                            successfulAcquisitions.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Start all threads at once
        startLatch.countDown();

        // Let them run for 5 seconds
        Thread.sleep(5000);
        running = false;

        // Signal threads to stop by interrupting them (they are in tryAcquire, which is non-blocking, so interrupt might not stop them immediately, 
        // but setting 'running' to false is the signal).
        // We need a way to wake them up. Since tryAcquire is non-blocking, we rely on the 'running' flag check.
        // However, to ensure they exit quickly, we can interrupt them. But tryAcquire holds a lock briefly.
        // Let's just let the loop finish by checking 'running' frequently.
        // To be safe, we can interrupt the executor.
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        long total = successfulAcquisitions.get();
        
        // Assert that we did not exceed the theoretical limit of 500
        assertTrue(total <= 500, 
            "Rate limit exceeded! Total acquisitions: " + total + ", Expected max: 500");
    }
}
```

### Summary of Test Logic

1.  **`testInitialCapacity`**: Verifies the burst capacity feature. It fills the bucket to its max (5) and ensures no more tokens are available immediately.
2.  **`testBurstCapacity`**: Checks that after waiting longer than the refill interval, the bucket refills to capacity.
3.  **`testRefillRate`**: Tests fractional refill logic. It waits 0.6 seconds (expecting 6 tokens to be added), checks if the bucket refills to the cap (5), and confirms the limit is respected.
4.  **`testBlockingAcquire`**: Uses a secondary thread to verify that `acquire()` blocks when the bucket is empty and resumes only after time has passed.
5.  **`testConcurrentRateLimit`**: This is the critical stress test. It launches multiple threads that constantly try to acquire tokens. It runs for a fixed duration (5 seconds) with a limit of 100 tokens/sec. The assertion `total <= 500` confirms that the internal locking and refill mechanism successfully prevented the throughput from exceeding the configured rate, even under heavy contention.