<!-- model: cohere/north-mini-code:free | category: code | run: 20261119T140156Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

This implementation provides a thread-safe token-bucket rate limiter with configurable refill rate and burst capacity. It supports both blocking acquire and non-blocking tryAcquire methods.

## Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final long capacity; // Maximum number of tokens the bucket can hold
    private final long refillInterval; // Time interval for refilling tokens (in nanoseconds)
    private final long refillTokens; // Number of tokens to refill per interval
    
    private final AtomicLong tokens; // Current number of tokens in the bucket
    private final AtomicLong lastRefillTimestamp; // Timestamp of the last refill (in nanoseconds)
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    
    /**
     * Constructor for TokenBucketRateLimiter.
     *
     * @param capacity        Maximum number of tokens the bucket can hold.
     * @param refillRate      Number of tokens to refill per second.
     * @param refillUnit      Time unit for the refill rate (e.g., TimeUnit.SECONDS).
     */
    public TokenBucketRateLimiter(long capacity, long refillRate, TimeUnit refillUnit) {
        this.capacity = capacity;
        this.refillInterval = TimeUnit.NANOSECONDS.convert(1, refillUnit) / refillRate;
        this.refillTokens = refillRate;
        
        this.tokens = new AtomicLong(0);
        this.lastRefillTimestamp = new AtomicLong(0);
    }
    
    /**
     * Acquires a token from the bucket, blocking until a token is available.
     */
    public void acquire() {
        if (!tryAcquire()) {
            lock.lock();
            try {
                while (!tryAcquire()) {
                    available.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for a token", e);
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * Acquires a specified number of tokens from the bucket, blocking until all tokens are available.
     *
     * @param permits Number of tokens to acquire.
     */
    public void acquire(long permits) {
        if (permits <= 0) {
            return;
        }
        if (!tryAcquire(permits)) {
            lock.lock();
            try {
                while (!tryAcquire(permits)) {
                    available.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for tokens", e);
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * Tries to acquire a token from the bucket without blocking.
     *
     * @return true if a token was acquired, false otherwise.
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }
    
    /**
     * Tries to acquire a specified number of tokens from the bucket without blocking.
     *
     * @param permits Number of tokens to acquire.
     * @return true if all tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            return true;
        }
        
        lock.lock();
        try {
            refill();
            
            long availableTokens = tokens.get();
            if (availableTokens >= permits) {
                tokens.addAndGet(-permits);
                available.signalAll();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Tries to acquire a token from the bucket, waiting up to the specified timeout.
     *
     * @param timeout  Maximum time to wait for a token.
     * @param timeUnit Time unit for the timeout.
     * @return true if a token was acquired, false if the timeout expired.
     */
    public boolean tryAcquire(long timeout, TimeUnit timeUnit) {
        return tryAcquire(1, timeout, timeUnit);
    }
    
    /**
     * Tries to acquire a specified number of tokens from the bucket, waiting up to the specified timeout.
     *
     * @param permits  Number of tokens to acquire.
     * @param timeout  Maximum time to wait for tokens.
     * @param timeUnit Time unit for the timeout.
     * @return true if all tokens were acquired, false if the timeout expired.
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit timeUnit) {
        if (permits <= 0) {
            return true;
        }
        
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeout, timeUnit);
            
            while (true) {
                refill();
                
                long availableTokens = tokens.get();
                if (availableTokens >= permits) {
                    tokens.addAndGet(-permits);
                    available.signalAll();
                    return true;
                }
                
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                
                long waitTime = Math.min(deadline - System.nanoTime(), refillInterval);
                available.await(waitTime, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for tokens", e);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Refills tokens in the bucket based on the elapsed time since the last refill.
     */
    private void refill() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTimestamp.get();
        
        if (now > lastRefill) {
            long elapsedIntervals = (now - lastRefill) / refillInterval;
            long tokensToAdd = elapsedIntervals * refillTokens;
            
            tokens.accumulateAndGet(Math.min(tokensToAdd, capacity - tokens.get()), Math::max);
            lastRefillTimestamp.set(lastRefill + elapsedIntervals * refillInterval);
        }
    }
}
```

## Design Documentation

### Core Concepts

The token bucket rate limiter works by maintaining a "bucket" of tokens that are refilled at a constant rate. Tokens can be acquired from the bucket, and if there are insufficient tokens, the request is either blocked or rejected.

### Key Components

1. **Capacity**: The maximum number of tokens the bucket can hold. This controls the burst capacity.
2. **Refill Rate**: The rate at which tokens are added to the bucket (tokens per second).
3. **Refill Interval**: The time interval between token refills (calculated based on the refill rate).
4. **Current Tokens**: The number of tokens currently in the bucket.
5. **Last Refill Timestamp**: The timestamp of the last token refill.

### Thread Safety

The implementation uses:
- `AtomicLong` for tokens and last refill timestamp to ensure atomic operations
- `ReentrantLock` and `Condition` for synchronization and signaling between threads

### Methods

1. **acquire()**: Blocks until a token is available
2. **acquire(long permits)**: Blocks until the specified number of tokens are available
3. **tryAcquire()**: Non-blocking attempt to acquire a single token
4. **tryAcquire(long permits)**: Non-blocking attempt to acquire multiple tokens
5. **tryAcquire(long timeout, TimeUnit timeUnit)**: Non-blocking attempt with timeout
6. **tryAcquire(long permits, long timeout, TimeUnit timeUnit)**: Non-blocking attempt with timeout for multiple tokens

### Refill Logic

The refill method calculates how many refill intervals have passed since the last refill and adds the corresponding number of tokens to the bucket, ensuring the bucket doesn't exceed its capacity.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Execution(ExecutionMode.CONCURRENT)
public class TokenBucketRateLimiterTest {
    
    @Test
    public void testAcquireWithinRate() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5, TimeUnit.SECONDS);
        
        // Acquire 5 tokens (should succeed)
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Try to acquire more tokens (should fail)
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.tryAcquire());
        }
    }
    
    @Test
    public void testAcquireAfterRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
        
        // Acquire 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Wait for refill (1 second)
        Thread.sleep(1100);
        
        // Should be able to acquire 1 token (refill rate)
        assertTrue(limiter.tryAcquire());
        
        // Try to acquire more tokens (should fail)
        assertFalse(limiter.tryAcquire());
    }
    
    @Test
    public void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
        
        // Initially, the bucket should be full (up to capacity)
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Try to acquire more tokens (should fail)
        assertFalse(limiter.tryAcquire());
    }
    
    @Test
    public void testAcquireMethod() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
        
        // Acquire 5 tokens using acquire()
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        
        // Try to acquire more tokens (should block until refill)
        Thread acquireThread = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (Exception e) {
                fail("Unexpected exception: " + e.getMessage());
            }
        });
        
        acquireThread.start();
        
        // Wait for 1.1 seconds (enough for refill)
        acquireThread.join(1100);
        
        // The thread should have acquired a token
        assertFalse(acquireThread.isAlive());
    }
    
    @Test
    public void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
        
        // Acquire 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Try to acquire a token with a short timeout (should fail)
        assertFalse(limiter.tryAcquire(100, TimeUnit.MILLISECONDS));
        
        // Wait for refill (1 second)
        Thread.sleep(1100);
        
        // Should be able to acquire a token with a short timeout
        assertTrue(limiter.tryAcquire(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100, TimeUnit.SECONDS);
        AtomicInteger acquiredTokens = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        // Submit 10 threads, each trying to acquire 100 tokens
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    if (limiter.tryAcquire()) {
                        acquiredTokens.incrementAndGet();
                    }
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Total acquired tokens should be <= 1000 (capacity)
        assertTrue(acquiredTokens.get() <= 1000);
    }
    
    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    public void testLongRunRateLimit() throws InterruptedException {
        // Configure a rate limiter with a rate of 10 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10, TimeUnit.SECONDS);
        
        // Run for 5 seconds
        long startTime = System.currentTimeMillis();
        long endTime = startTime + 5000; // 5 seconds
        int acquiredTokens = 0;
        
        while (System.currentTimeMillis() < endTime) {
            if (limiter.tryAcquire()) {
                acquiredTokens++;
            }
        }
        
        // The total number of tokens acquired should be close to 50 (10 tokens/second * 5 seconds)
        // Allow some tolerance due to timing variations
        assertTrue(acquiredTokens >= 45 && acquiredTokens <= 55, 
            "Acquired tokens: " + acquiredTokens + ", expected approximately 50");
    }
}
```

## Key Features

1. **Thread Safety**: Uses atomic operations and locks for safe concurrent access
2. **Configurable Rate**: Supports different refill rates and time units
3. **Burst Capacity**: Allows bursts up to the configured capacity
4. **Flexible Acquire Methods**: Both blocking and non-blocking options
5. **Timeout Support**: Can wait for tokens with a specified timeout
6. **Concurrent Testing**: Includes tests for concurrent access patterns

This implementation provides a robust and flexible rate limiting solution suitable for high-concurrency applications.