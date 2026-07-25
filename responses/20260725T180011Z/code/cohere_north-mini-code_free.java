<!-- model: cohere/north-mini-code:free | category: code | run: 20260725T180011Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

Here's a thread-safe token-bucket rate limiter implementation in Java with both blocking and non-blocking acquire methods:

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.logging.Logger;

/**
 * A thread-safe token-bucket rate limiter implementation.
 * 
 * Design:
 * 1. Token bucket algorithm: Tokens are added to the bucket at a steady rate (refill rate).
 * 2. The bucket has a maximum capacity (burst capacity) - tokens cannot exceed this limit.
 * 3. When acquire() is called, tokens are removed from the bucket. If insufficient tokens,
 *    the thread waits until enough tokens are available.
 * 4. The implementation uses a single AtomicLong for token count to ensure thread safety
 *    without explicit locking for the token count itself.
 * 5. A ReentrantLock and Condition variable are used to manage waiting threads efficiently.
 * 6. The refill operation is lazy: tokens are only added when needed, not at fixed intervals.
 * 7. For non-blocking tryAcquire, we check available tokens without waiting.
 * 
 * Key attributes:
 * - rate: tokens per second (refill rate)
 * - capacity: maximum tokens in the bucket
 * - tokens: current token count (atomic)
 * - lastRefillTimestamp: when tokens were last refilled (atomic)
 * - lock: for coordinating waiting threads
 * - notEmpty: condition variable for threads waiting for tokens
 * 
 * Performance considerations:
 * - Uses atomic operations for token count updates to minimize contention
 * - Lazy refilling reduces unnecessary operations
 * - Semaphore-like behavior with precise rate limiting
 */
public class TokenBucketRateLimiter {
    private final long rate; // tokens per second
    private final long capacity;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTimestamp;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    
    private static final Logger logger = Logger.getLogger(TokenBucketRateLimiter.class.getName());
    
    /**
     * Constructs a TokenBucketRateLimiter with the given rate and capacity.
     * 
     * @param rate tokens per second (refill rate)
     * @param capacity maximum number of tokens the bucket can hold
     */
    public TokenBucketRateLimiter(long rate, long capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.rate = rate;
        this.capacity = capacity;
        this.tokens = new AtomicLong(0);
        this.lastRefillTimestamp = new AtomicLong(System.currentTimeMillis());
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
    }
    
    /**
     * Acquires one token, blocking until a token is available.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        if (!tryAcquire(1, 0, TimeUnit.MILLISECONDS)) {
            lock.lock();
            try {
                while (!tryAcquire(1, Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                    // Keep waiting
                }
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * Attempts to acquire one token, returning immediately if not available.
     * 
     * @return true if a token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1, 0, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Attempts to acquire the specified number of tokens, blocking for up to the given timeout.
     * 
     * @param tokensCount number of tokens to acquire
     * @param timeout maximum time to wait
     * @param unit time unit for timeout
     * @return true if all tokens were acquired, false if timeout occurred
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long tokensCount, long timeout, TimeUnit unit) throws InterruptedException {
        if (tokensCount <= 0) {
            throw new IllegalArgumentException("Token count must be positive");
        }
        
        // Convert timeout to milliseconds
        long timeoutMs = unit.toMillis(timeout);
        
        // Calculate available tokens
        long available = getAvailableTokens();
        if (available >= tokensCount) {
            // We have enough tokens, consume them
            consumeTokens(tokensCount);
            return true;
        }
        
        if (timeoutMs <= 0) {
            // Non-blocking mode: not enough tokens
            return false;
        }
        
        // Blocking mode: wait for tokens
        lock.lock();
        try {
            // We need to wait, so we check if we have enough tokens now
            // (in case of race condition)
            long availableNow = getAvailableTokens();
            if (availableNow >= tokensCount) {
                consumeTokens(tokensCount);
                return true;
            }
            
            // Calculate how long we need to wait
            long deficit = tokensCount - availableNow;
            long waitTimeMs = (deficit * 1000L) / rate + 1; // +1 to ensure we wait long enough
            
            if (waitTimeMs > timeoutMs) {
                return false;
            }
            
            // Wait for the calculated time
            notEmpty.await(waitTimeMs, TimeUnit.MILLISECONDS);
            
            // After waiting, check if we have enough tokens
            long availableAfterWait = getAvailableTokens();
            if (availableAfterWait >= tokensCount) {
                consumeTokens(tokensCount);
                return true;
            }
            
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Gets the current number of available tokens.
     * 
     * @return number of available tokens
     */
    private long getAvailableTokens() {
        // Refill tokens based on elapsed time
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTimestamp.get();
        long elapsedMs = now - lastRefill;
        
        if (elapsedMs <= 0) {
            return tokens.get();
        }
        
        // Calculate how many tokens to add
        long tokensToAdd = (elapsedMs * rate) / 1000L;
        
        // Refill tokens
        long currentTokens;
        long newTokens;
        do {
            currentTokens = tokens.get();
            newTokens = Math.min(capacity, currentTokens + tokensToAdd);
        } while (!tokens.compareAndSet(currentTokens, newTokens));
        
        // Update last refill timestamp
        lastRefillTimestamp.compareAndSet(lastRefill, now);
        
        return newTokens;
    }
    
    /**
     * Consumes the specified number of tokens.
     * 
     * @param tokensCount number of tokens to consume
     */
    private void consumeTokens(long tokensCount) {
        // Refill tokens first to ensure we have enough
        getAvailableTokens();
        
        long currentTokens;
        long newTokens;
        do {
            currentTokens = tokens.get();
            newTokens = Math.max(0, currentTokens - tokensCount);
        } while (!tokens.compareAndSet(currentTokens, newTokens));
        
        // Notify waiting threads if we have released tokens
        if (newTokens > tokensCount) {
            lock.lock();
            try {
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@DisplayName("TokenBucketRateLimiter Tests")
class TokenBucketRateLimiterTest {
    private static final Logger logger = Logger.getLogger(TokenBucketRateLimiterTest.class.getName());
    
    private TokenBucketRateLimiter rateLimiter;
    private static final long RATE = 10; // tokens per second
    private static final long CAPACITY = 20; // tokens
    
    @BeforeEach
    void setUp() {
        rateLimiter = new TokenBucketRateLimiter(RATE, CAPACITY);
        // Disable logging for cleaner test output
        logger.setLevel(Level.OFF);
    }
    
    @Test
    @DisplayName("Test basic acquire functionality")
    void testBasicAcquire() {
        // Initially, bucket should be empty
        assertTrue(rateLimiter.tryAcquire());
        assertEquals(CAPACITY - 1, rateLimiter.tokens.get(), "Token count should be capacity - 1 after first acquire");
        
        // Acquire more tokens
        for (int i = 0; i < CAPACITY - 1; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        
        // Bucket should be empty now
        assertEquals(0, rateLimiter.tokens.get(), "Token count should be 0 after filling the bucket");
        
        // Next acquire should block (non-blocking should fail)
        assertFalse(rateLimiter.tryAcquire(), "Should not be able to acquire when bucket is empty");
    }
    
    @Test
    @DisplayName("Test capacity limit")
    void testCapacityLimit() {
        // Fill the bucket to capacity
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        
        // Token count should be 0
        assertEquals(0, rateLimiter.tokens.get(), "Token count should be 0 at capacity");
        
        // Acquire one more token (should fail in non-blocking mode)
        assertFalse(rateLimiter.tryAcquire(), "Should not be able to exceed capacity");
    }
    
    @Test
    @DisplayName("Test refill rate")
    void testRefillRate() throws InterruptedException {
        // Empty the bucket
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        
        // Wait for 1.5 seconds to get 15 tokens (rate is 10 tokens per second)
        Thread.sleep(1500);
        
        // Try to acquire 15 tokens (should succeed)
        for (int i = 0; i < 15; i++) {
            assertTrue(rateLimiter.tryAcquire());
        }
        
        // Try to acquire one more token (should fail)
        assertFalse(rateLimiter.tryAcquire(), "Should not be able to acquire more than rate allows");
    }
    
    @Test
    @DisplayName("Test