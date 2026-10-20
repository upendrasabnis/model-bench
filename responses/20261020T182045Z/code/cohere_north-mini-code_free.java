<!-- model: cohere/north-mini-code:free | category: code | run: 20261020T182045Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

Here's a complete, thread-safe implementation of a token-bucket rate limiter in Java with comprehensive documentation and tests:

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * A thread-safe token-bucket rate limiter implementation.
 * 
 * Design:
 * 
 * The TokenBucketRateLimiter implements a token-bucket algorithm for rate limiting.
 * Tokens are added to the bucket at a constant refill rate (tokens per second).
 * The bucket has a maximum capacity (burst capacity) - when full, tokens are dropped.
 * 
 * Key Design Decisions:
 * 
 * 1. Thread Safety:
 *    - Uses a ReentrantLock for exclusive access to shared state
 *    - The lock protects the token count and last refill timestamp
 *    - All public methods are synchronized through this lock
 * 
 * 2. Refill Logic:
 *    - Refill happens on-demand when acquire is called
 *    - Tokens are calculated based on time elapsed since last refill
 *    - Uses atomic operations for timestamp calculations
 * 
 * 3. Burst Capacity:
 *    - Bucket has a maximum capacity - tokens are capped at this limit
 *    - Allows bursts up to the full capacity when the bucket is full
 * 
 * 4. Time Handling:
 *    - Uses nanoseconds internally for precision
 *    - Refill rate is specified in tokens per second
 *    - Supports both blocking and non-blocking acquire operations
 * 
 * 5. Performance:
 *    - Lock is held only for the duration of the critical section
 *    - Non-blocking operations avoid waiting for the lock when possible
 * 
 * Usage Example:
 * <pre>
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // 10 tokens/sec, burst up to 1 token
 * boolean acquired = limiter.tryAcquire();
 * if (acquired) {
 *     // Perform action
 * } else {
 *     // Rate limit exceeded
 * }
 * </pre>
 */
public class TokenBucketRateLimiter {
    private final long refillTokensPerNano; // tokens to add per nanosecond
    private final long maxCapacity; // maximum tokens the bucket can hold
    private final AtomicLong tokens; // current tokens in bucket
    private final AtomicLong lastRefillTimestamp; // last time tokens were refilled (nanoseconds)
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    
    /**
     * Creates a new TokenBucketRateLimiter with specified refill rate and burst capacity.
     * 
     * @param refillRate Tokens to add per second (must be positive)
     * @param burstCapacity Maximum number of tokens the bucket can hold (must be positive)
     * @throws IllegalArgumentException if refillRate or burstCapacity is not positive
     */
    public TokenBucketRateLimiter(long refillRate, long burstCapacity) {
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be positive");
        }
        
        this.maxCapacity = burstCapacity;
        // Convert tokens per second to tokens per nanosecond for precision
        this.refillTokensPerNano = refillRate / 1_000_000_000L;
        this.tokens = new AtomicLong(0);
        this.lastRefillTimestamp = new AtomicLong(System.nanoTime());
    }
    
    /**
     * Attempts to acquire a token from the bucket.
     * If a token is available, it is consumed and true is returned.
     * Otherwise, false is returned immediately.
     * 
     * @return true if a token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Acquires a token from the bucket, blocking until one is available.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens.get() > 0) {
                    tokens.decrementAndGet();
                    return;
                }
                // Wait for next refill
                long waitTime = calculateWaitTime();
                if (waitTime > 0) {
                    available.await(waitTime, TimeUnit.NANOSECONDS);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to acquire a token within the specified timeout.
     * 
     * @param timeout The maximum time to wait
     * @param unit The time unit of the timeout
     * @return true if a token was acquired, false if the timeout expires
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative");
        }
        
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens.get() > 0) {
                    tokens.decrementAndGet();
                    return true;
                }
                
                long waitTime = calculateWaitTime();
                if (waitTime <= 0) {
                    return false;
                }
                
                long remainingNanos = unit.toNanos(timeout);
                if (remainingNanos <= 0) {
                    return false;
                }
                
                long waitNanos = Math.min(waitTime, remainingNanos);
                if (available.await(waitNanos, TimeUnit.NANOSECONDS)) {
                    continue;
                } else {
                    return false;
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Refills tokens based on time elapsed since last refill.
     * Tokens are added up to the bucket's maximum capacity.
     */
    private void refill() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTimestamp.get();
        long elapsedNanos = now - lastRefill;
        
        if (elapsedNanos <= 0) {
            return;
        }
        
        long tokensToAdd = elapsedNanos * refillTokensPerNano;
        if (tokensToAdd <= 0) {
            return;
        }
        
        // Atomically update tokens and lastRefillTimestamp
        while (true) {
            long currentTokens = tokens.get();
            long newTokens = Math.min(currentTokens + tokensToAdd, maxCapacity);
            
            if (tokens.compareAndSet(currentTokens, newTokens)) {
                // Update lastRefillTimestamp only if we've added all possible tokens
                // This ensures we don't lose track of elapsed time
                if (newTokens >= maxCapacity) {
                    lastRefillTimestamp.compareAndSet(lastRefill, now);
                }
                break;
            }
        }
    }
    
    /**
     * Calculates the time in nanoseconds until the next token will be available.
     * 
     * @return Time to wait in nanoseconds, or 0 if a token is already available
     */
    private long calculateWaitTime() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTimestamp.get();
        long elapsedNanos = now - lastRefill;
        
        if (elapsedNanos <= 0) {
            return 0;
        }
        
        long tokensToAdd = elapsedNanos * refillTokensPerNano;
        long currentTokens = tokens.get();
        long tokensNeeded = 1 - currentTokens;
        
        if (tokensNeeded <= 0) {
            return 0;
        }
        
        // Calculate time needed to get enough tokens
        // tokensNeeded = tokensToAdd + futureTokens
        // futureTokens = refillTokensPerNano * futureTime
        // tokensNeeded = tokensToAdd + refillTokensPerNano * futureTime
        // futureTime = (tokensNeeded - tokensToAdd) / refillTokensPerNano
        long futureTime = (tokensNeeded - tokensToAdd) / refillTokensPerNano;
        if (futureTime < 0) {
            futureTime = 0;
        }
        
        return futureTime;
    }
    
    /**
     * Gets the current number of tokens in the bucket.
     * This is a read-only operation and is not affected by concurrent refills.
     * 
     * @return Current token count
     */
    public long getAvailableTokens() {
        lock.lock();
        try {
            refill();
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
import org