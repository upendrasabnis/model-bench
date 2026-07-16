<!-- model: cohere/north-mini-code:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Documentation

### Core Algorithm
The token bucket algorithm implements a rate limiter by:
1. Maintaining a "bucket" of tokens that can hold up to a maximum capacity
2. Adding tokens to the bucket at a steady rate (refill rate)
3. Allowing requests to proceed only when sufficient tokens are available
4. Consuming one token per request

### Thread Safety Design
The implementation uses multiple synchronization mechanisms:
- **AtomicLong for tokens and timestamp**: Provides atomic updates for token counting
- **ReentrantLock with Condition**: Enables efficient waiting for tokens in blocking acquire
- **Lock protection**: Ensures atomic operations around token consumption and refilling

### Key Features
1. **Dual Acquisition Methods**:
   - `acquire()`: Blocks until tokens are available
   - `tryAcquire()`: Non-blocking, returns immediately with success/failure

2. **Configurable Parameters**:
   - `capacity`: Maximum tokens (burst capacity)
   - `rate`: Tokens per second (refill rate)

3. **Time-based Refilling**:
   - Tokens are added based on elapsed time since last refill
   - Handles concurrent access by tracking refill timestamps atomically

### Design Decisions
- Used `AtomicLong` for token counting to minimize lock contention
- Implemented `ReentrantLock` instead of `synchronized` for more complex waiting scenarios
- Separated refill logic into a private method for clarity
- Added comprehensive parameter validation

## Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * A thread-safe token bucket rate limiter implementation.
 * 
 * <p>The token bucket algorithm allows bursts of requests up to a certain capacity
 * while maintaining an average rate over time. Tokens are added to the bucket at
 * a steady rate. When a request arrives, it consumes one token. If no tokens are
 * available, the request is blocked (in blocking acquire) or fails immediately
 * (in non-blocking tryAcquire).
 * 
 * <p>Design decisions:
 * <ul>
 *   <li>Uses atomic operations for thread safety and performance</li>
 *   <li>Implements both blocking and non-blocking acquire methods</li>
 *   <li>Supports configurable refill rate and burst capacity</li>
 *   <li>Uses ReentrantLock with Condition for efficient waiting in blocking acquire</li>
 *   <li>Handles time-based refilling correctly in concurrent scenarios</li>
 * </ul>
 * 
 * @author Rate Limiter Implementation
 * @since 1.0
 */
public class TokenBucketRateLimiter {
    private final long capacity; // Maximum tokens the bucket can hold
    private final long rate;     // Tokens per second to refill
    private final AtomicLong tokens; // Current number of tokens in the bucket
    private final AtomicLong lastRefillTimestamp; // Timestamp of last refill (nanoseconds)
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket rate limiter.
     *
     * @param capacity The maximum number of tokens the bucket can hold (burst capacity)
     * @param rate The number of tokens to add per second (refill rate)
     * @param rateUnit The time unit for the rate
     */
    public TokenBucketRateLimiter(long capacity, long rate, TimeUnit rateUnit) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative");
        }
        if (rate < 0) {
            throw new IllegalArgumentException("Rate must be non-negative");
        }
        
        this.capacity = capacity;
        this.rate = rateUnit.toNanos(rate); // Convert rate to nanoseconds
        this.tokens = new AtomicLong(0);
        this.lastRefillTimestamp = new AtomicLong(0);
    }

    /**
     * Acquires a token from the bucket, blocking until one is available.
     * 
     * <p>This method will block if no tokens are available and will wait until
     * tokens become available based on the refill rate.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        if (!tryAcquire(1, 0, TimeUnit.NANOSECONDS)) {
            lock.lock();
            try {
                while (!tryAcquire(1, Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    // Keep waiting until a token is available
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Attempts to acquire the specified number of tokens from the bucket.
     * 
     * <p>If the tokens are available immediately, they are consumed and true is returned.
     * If not enough tokens are available, no tokens are consumed and false is returned.
     *
     * @param permits The number of tokens to acquire
     * @param timeout The maximum time to wait for tokens
     * @param timeUnit The time unit of the timeout
     * @return true if tokens were acquired, false otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be non-negative");
        }
        
        lock.lock();
        try {
            long timeoutNanos = timeUnit.toNanos(timeout);
            long now = System.nanoTime();
            refillTokens(now);
            
            long availableTokens = tokens.get();
            if (availableTokens >= permits) {
                // Consume tokens
                tokens.addAndGet(-permits);
                return true;
            }
            
            if (timeoutNanos <= 0) {
                return false;
            }
            
            // Wait for tokens to become available
            long deadline = now + timeoutNanos;
            while (tokens.get() < permits && now < deadline) {
                long waitTime = Math.min(deadline - now, rate > 0 ? (permits - tokens.get()) * 1000000000L / rate : Long.MAX_VALUE);
                if (waitTime <= 0) {
                    break;
                }
                notEmpty.await(waitTime, TimeUnit.NANOSECONDS);
                now = System.nanoTime();
            }
            
            // Try to acquire after waiting
            refillTokens(now);
            if (tokens.get() >= permits) {
                tokens.addAndGet(-permits);
                return true;
            }
            
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Convenience method for non-blocking tryAcquire with no timeout.
     *
     * @param permits The number of tokens to acquire
     * @return true if tokens were acquired, false otherwise
     */
    public boolean tryAcquire(long permits) {
        try {
            return tryAcquire(permits, 0, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Convenience method for blocking acquire with no timeout.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be non-negative");
        }
        if (permits == 0) {
            return;
        }
        if (permits == 1) {
            acquire();
        } else {
            // For multiple permits, wait for all tokens to be available
            lock.lock();
            try {
                while (!tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    // Keep waiting until all tokens are available
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Refills tokens based on the elapsed time since the last refill.
     * 
     * @param now The current timestamp in nanoseconds
     */
    private void refillTokens(long now) {
        long lastRefill = lastRefillTimestamp.get();
        if (lastRefill == 0) {
            // First initialization
            tokens.set(capacity);
            lastRefillTimestamp.set(now);
            return;
        }
        
        long elapsedNanos = now - lastRefill;
        if (elapsedNanos <= 0) {
            return;
        }
        
        // Calculate tokens to add
        long tokensToAdd = elapsedNanos * rate / 1_000_000_000L;
        if (tokensToAdd > 0) {
            // Add tokens, but don't exceed capacity
            long newTokens = Math.min(capacity, tokens.addAndGet(tokensToAdd));
            tokens.set(newTokens);
            lastRefillTimestamp.set(now);
        }
    }

    /**
     * Gets the current number of available tokens.
     * 
     * @return The number of available tokens
     */
    public long getAvailableTokens() {
        lock.lock();
        try {
            long now = System.nanoTime();
            refillTokens(now);
            return tokens.get();
        } finally {
            lock.unlock();
        }
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Assertions;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

@Execution(ExecutionMode.CONCURRENT)
class TokenBucketRateLimiterTest {
    
    private TokenBucketRateLimiter rateLimiter;
    
    @BeforeEach
    void setUp() {
        // Create a rate limiter with 10 tokens burst capacity and 5 tokens per second rate
        rateLimiter = new TokenBucketRateLimiter(10, 5, TimeUnit.SECONDS);
    }
    
    @Test
    void testInitialState() {
        // Initially, the bucket should be full
        Assertions.assertEquals(10, rateLimiter.getAvailableTokens());
    }
    
    @Test
    void testAcquireWithinCapacity() {
        // Acquire 5 tokens - should succeed
        for (int i = 0; i < 5; i++) {
            rateLimiter.acquire();
        }
        
        // Should have 5 tokens remaining
        Assertions.assertEquals(5, rateLimiter.getAvailableTokens());
    }
    
    @Test
    void testAcquireBeyondCapacity() {
        // Try to acquire 15 tokens - only 10 should be available initially
        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(15);
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 15; i++) {
            executor.submit(() -> {
                try {
                    rateLimiter.acquire();
                    acquired.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Should have acquired exactly 10 tokens initially
        Assertions.assertEquals(10, acquired.get());
        // Bucket should be empty now
        Assertions.assertEquals(0, rateLimiter.getAvailableTokens());
    }
    
    @Test
    void testTryAcquireSuccess() {
        // Should successfully acquire 3 tokens
        Assertions.assertTrue(rateLimiter.tryAcquire(3));
        Assertions.assertEquals(7, rateLimiter.getAvailableTokens());
        
        // Should fail to acquire 8 tokens (only 7 available)
        Assertions.assertFalse(rateLimiter.tryAcquire(8));
        Assertions.assertEquals(7, rateLimiter.getAvailableTokens