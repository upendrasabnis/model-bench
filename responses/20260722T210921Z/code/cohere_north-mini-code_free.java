<!-- model: cohere/north-mini-code:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

I'll implement a thread-safe token bucket rate limiter in Java with both blocking and non-blocking acquire methods, along with comprehensive JUnit 5 tests.

## Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucket {
    private final long capacity; // Maximum tokens in the bucket
    private final long refillRate; // Tokens to refill per unit time
    private final long refillInterval; // Time unit for refill rate
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    
    private long tokens; // Current available tokens
    private long lastRefillTimestamp; // Timestamp of last refill
    
    /**
     * Creates a new TokenBucket with the specified capacity and refill rate.
     *
     * @param capacity The maximum number of tokens the bucket can hold
     * @param refillRate The number of tokens to refill per refillInterval
     * @param refillInterval The time interval for refilling tokens (in nanoseconds)
     */
    public TokenBucket(long capacity, long refillRate, long refillInterval) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (refillInterval <= 0) {
            throw new IllegalArgumentException("Refill interval must be positive");
        }
        
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.refillInterval = refillInterval;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.nanoTime();
    }
    
    /**
     * Acquires the specified number of tokens. This method blocks until all tokens are acquired.
     *
     * @param tokens The number of tokens to acquire
     * @throws IllegalArgumentException if tokens is not positive
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire(int tokens) throws InterruptedException {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be positive");
        }
        
        lock.lock();
        try {
            waitForTokens(tokens);
            consumeTokens(tokens);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Tries to acquire the specified number of tokens within the given timeout.
     *
     * @param tokens The number of tokens to acquire
     * @param timeout The maximum time to wait for tokens
     * @param unit The time unit of the timeout
     * @return true if the tokens were acquired, false otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(int tokens, long timeout, TimeUnit unit) throws InterruptedException {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be positive");
        }
        
        lock.lock();
        try {
            if (!waitForTokens(tokens, timeout, unit)) {
                return false;
            }
            consumeTokens(tokens);
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Tries to acquire the specified number of tokens immediately without waiting.
     *
     * @param tokens The number of tokens to acquire
     * @return true if the tokens were acquired, false otherwise
     */
    public boolean tryAcquire(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be positive");
        }
        
        lock.lock();
        try {
            refillTokens();
            if (tokens <= tokens) {
                consumeTokens(tokens);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Waits until the specified number of tokens are available.
     *
     * @param tokens The number of tokens to wait for
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private void waitForTokens(int tokens) throws InterruptedException {
        while (true) {
            refillTokens();
            if (this.tokens >= tokens) {
                break;
            }
            
            long waitTime = calculateWaitTime(tokens);
            available.await(waitTime, TimeUnit.NANOSECONDS);
        }
    }
    
    /**
     * Waits until the specified number of tokens are available within the timeout.
     *
     * @param tokens The number of tokens to wait for
     * @param timeout The maximum time to wait
     * @param unit The time unit of the timeout
     * @return true if the tokens were acquired, false otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private boolean waitForTokens(int tokens, long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        
        while (timeoutNanos > 0) {
            refillTokens();
            if (this.tokens >= tokens) {
                return true;
            }
            
            long waitTime = Math.min(calculateWaitTime(tokens), timeoutNanos);
            long remaining = available.await(waitTime, TimeUnit.NANOSECONDS);
            
            if (remaining > 0) {
                timeoutNanos -= waitTime;
            }
        }
        
        return false;
    }
    
    /**
     * Refills tokens based on the elapsed time since the last refill.
     */
    private void refillTokens() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillTimestamp;
        
        if (elapsed > 0) {
            long tokensToAdd = (elapsed * refillRate) / refillInterval;
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }
    
    /**
     * Calculates the time needed to acquire the specified number of tokens.
     *
     * @param tokens The number of tokens to acquire
     * @return The time in nanoseconds needed to acquire the tokens
     */
    private long calculateWaitTime(int tokens) {
        if (this.tokens >= tokens) {
            return 0;
        }
        
        long deficit = tokens - this.tokens;
        return (deficit * refillInterval) / refillRate;
    }
    
    /**
     * Consumes the specified number of tokens.
     *
     * @param tokens The number of tokens to consume
     */
    private void consumeTokens(int tokens) {
        this.tokens -= tokens;
    }
    
    /**
     * Returns the current number of tokens available.
     *
     * @return The current number of tokens
     */
    public long getAvailableTokens() {
        lock.lock();
        try {
            refillTokens();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the next refill timestamp.
     *
     * @return The next refill timestamp in nanoseconds
     */
    public long getNextRefillTimestamp() {
        lock.lock();
        try {
            return lastRefillTimestamp;
        } finally {
            lock.unlock();
        }
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class TokenBucketTest {
    
    private TokenBucket tokenBucket;
    
    @BeforeEach
    void setUp() {
        // 10 tokens per second with capacity for 20 tokens
        tokenBucket = new TokenBucket(20, 10, TimeUnit.SECONDS.toNanos(1));
    }
    
    @Test
    void testInitialTokens() {
        assertEquals(20, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testAcquireWithinCapacity() throws InterruptedException {
        tokenBucket.acquire(5);
        assertEquals(15, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testTryAcquireSuccess() {
        assertTrue(tokenBucket.tryAcquire(5));
        assertEquals(15, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testTryAcquireFailure() {
        // Fill the bucket first
        for (int i = 0; i < 20; i++) {
            tokenBucket.tryAcquire(1);
        }
        
        assertFalse(tokenBucket.tryAcquire(1));
        assertEquals(0, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testAcquireBlocks() throws InterruptedException {
        // Fill the bucket
        for (int i = 0; i < 20; i++) {
            tokenBucket.tryAcquire(1);
        }
        
        AtomicInteger acquired = new AtomicInteger(0);
        Thread thread = new Thread(() -> {
            try {
                tokenBucket.acquire(1);
                acquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        thread.start();
        thread.join(100, TimeUnit.MILLISECONDS);
        
        assertEquals(0, acquired.get(), "Thread should still be waiting");
        assertEquals(0, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testAcquireWithTimeout() throws InterruptedException {
        // Fill the bucket
        for (int i = 0; i < 20; i++) {
            tokenBucket.tryAcquire(1);
        }
        
        boolean acquired = tokenBucket.tryAcquire(1, 100, TimeUnit.MILLISECONDS);
        assertFalse(acquired, "Should timeout since no tokens available");
        assertEquals(0, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testRefillRate() throws InterruptedException {
        // Consume all tokens
        for (int i = 0; i < 20; i++) {
            tokenBucket.tryAcquire(1);
        }
        
        // Wait for half a second to get 5 tokens (10 tokens per second)
        Thread.sleep(500);
        
        assertTrue(tokenBucket.tryAcquire(5), "Should have 5 tokens after 0.5 seconds");
        assertEquals(0, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testConcurrentAcquire() throws InterruptedException {
        int numThreads = 10;
        int tokensPerThread = 2;
        int totalTokens = numThreads * tokensPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    if (tokenBucket.tryAcquire(tokensPerThread, 1, TimeUnit.SECONDS)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        
        // The rate limiter should allow at most 20 tokens at once
        // In this test, each thread tries to acquire 2 tokens, so we expect all to succeed
        assertEquals(numThreads, successCount.get(), "All threads should succeed");
        assertEquals(0, tokenBucket.getAvailableTokens());
    }
    
    @Test
    void testLongRunRateLimit() throws InterruptedException {
        // Create a token bucket with 100 tokens per second and capacity for 200 tokens
        TokenBucket rateLimiter = new TokenBucket(200, 100, TimeUnit.SECONDS.toNanos(1));
        
        int numThreads = 50;
        int tokensPerThread = 2;