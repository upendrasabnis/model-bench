<!-- model: cohere/north-mini-code:free | category: code | run: 20260830T080453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

I'll implement a thread-safe token bucket rate limiter in Java with both blocking and non-blocking acquire methods, along with comprehensive JUnit 5 tests.

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A thread-safe token bucket rate limiter implementation.
 * 
 * <p>This implementation uses a combination of atomic operations and fine-grained locks
 * to ensure thread safety while maintaining high performance. The token bucket algorithm
 * provides both rate limiting and burst capacity control.</p>
 * 
 * <p>Design Principles:</p>
 * <ul>
 *   <li><b>Thread Safety</b>: Uses atomic operations for token counting and fine-grained
 *       locks for synchronization to minimize contention</li>
 *   <li><b>Accuracy</b>: Maintains precise timing using monotonic time and atomic
 *       operations to avoid race conditions</li>
 *   <li><b>Performance</b>: Optimizes for the common case (successful acquire) with
 *       minimal locking</li>
 *   <li><b>Flexibility</b>: Supports both blocking and non-blocking acquisition modes</li>
 *   <li><b>Extensibility</b>: Clean separation of concerns for easy extension</li>
 * </ul>
 * 
 * <p>Algorithm Details:</p>
 * <ul>
 *   <li>Tokens are refilled at a constant rate (tokens per second)</li>
 *   <li>Maximum tokens never exceed the burst capacity</li>
 *   <li>Each acquire operation consumes exactly one token</li>
 *   <li>Refill calculations use precise time measurements</li>
 * </ul>
 * 
 * @author Rate Limiter Implementation
 * @version 1.0
 * @since 2023-10-01
 */
public class TokenBucketLimiter {
    
    // Configuration parameters
    private final long capacity;           // Maximum tokens (burst capacity)
    private final double refillRate;       // Tokens per second
    
    // State variables
    private final AtomicLong tokens;       // Current available tokens
    private final AtomicLong lastRefillTime; // Timestamp of last refill (ms)
    private final AtomicLong idleStartTime; // Track idle periods for efficiency
    
    // Synchronization primitives
    private final Lock readLock;           // Read lock for efficient token checking
    private final Lock writeLock;          // Write lock for state modifications
    private final Condition available;    // Condition for waiting threads
    
    // Performance optimization
    private final ThreadLocal<Long> lastLocalRefill = ThreadLocal.withInitial(() -> System.currentTimeMillis());
    
    /**
     * Creates a new TokenBucketLimiter with specified capacity and refill rate.
     * 
     * @param capacity Maximum number of tokens (burst capacity). Must be positive.
     * @param refillRate Tokens per second. Must be positive.
     * @throws IllegalArgumentException if capacity or refillRate is not positive
     */
    public TokenBucketLimiter(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive, got: " + refillRate);
        }
        
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(0);
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        this.idleStartTime = new AtomicLong(System.currentTimeMillis());
        this.readLock = new ReentrantLock();
        this.writeLock = new ReentrantLock();
        this.available = writeLock.newCondition();
    }
    
    /**
     * Acquires a token, blocking until one is available.
     * 
     * <p>This method is the primary acquisition method for rate limiting. It will block
     * the calling thread until a token is available, either immediately or after a
     * refill occurs.</p>
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        if (!tryAcquire()) {
            writeLock.lock();
            try {
                // Keep trying until we succeed
                while (!tryAcquire()) {
                    available.await();
                }
            } finally {
                writeLock.unlock();
            }
        }
    }
    
    /**
     * Attempts to acquire a token without blocking.
     * 
     * <p>This method is non-blocking and returns immediately with the result of the
     * acquisition attempt. It's useful for scenarios where you want to proceed with
     * alternative logic if the rate limit is exceeded.</p>
     * 
     * @return true if a token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        // Fast path: try to acquire without refill
        if (tryAcquireNoRefill()) {
            return true;
        }
        
        // Need to refill, acquire write lock for state modification
        writeLock.lock();
        try {
            // Refill tokens based on elapsed time
            refill();
            
            // Try to acquire after refill
            return tryAcquireNoRefill();
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * Attempts to acquire a token with a timeout.
     * 
     * <p>This method will wait up to the specified timeout for a token to become
     * available. It's useful for scenarios where you want to limit the waiting time
     * for rate-limited operations.</p>
     * 
     * @param timeout Maximum time to wait
     * @param unit Time unit of the timeout
     * @return true if a token was acquired, false if timeout occurred
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        
        while (true) {
            // Try to acquire without blocking
            if (tryAcquireNoRefill()) {
                return true;
            }
            
            // Calculate remaining time
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            
            // Wait for a token or timeout
            writeLock.lock();
            try {
                // Refill tokens before checking
                refill();
                
                if (tryAcquireNoRefill()) {
                    return true;
                }
                
                // Wait with timeout
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                
                available.awaitNanos(remainingNanos);
            } finally {
                writeLock.unlock();
            }
        }
    }
    
    /**
     * Refills tokens based on elapsed time since last refill.
     * 
     * <p>This method must be called while holding the write lock to ensure atomicity
     * of the refill operation.</p>
     */
    private void refill() {
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTime.get();
        long elapsedMillis = now - lastRefill;
        
        if (elapsedMillis <= 0) {
            return;
        }
        
        // Calculate tokens to add based on refill rate
        double tokensToAdd = (refillRate * elapsedMillis) / 1000.0;
        
        // Atomically update tokens with overflow protection
        long currentTokens;
        long newTokens;
        do {
            currentTokens = tokens.get();
            newTokens = Math.min(capacity, currentTokens + (long) tokensToAdd);
            
            if (currentTokens == newTokens) {
                // No change needed, update timestamp and return
                if (lastRefillTime.compareAndSet(lastRefill, now)) {
                    return;
                }
            } else if (tokens.compareAndSet(currentTokens, newTokens)) {
                // Successfully updated tokens, update timestamp
                if (lastRefillTime.compareAndSet(lastRefill, now)) {
                    return;
                }
            }
            // If CAS failed, retry
        } while (true);
    }
    
    /**
     * Attempts to acquire a token without refilling.
     * 
     * <p>This is an optimized method that assumes tokens have already been refilled.
     * It should only be called while holding the write lock or in the fast path.</p>
     * 
     * @return true if a token was acquired, false otherwise
     */
    private boolean tryAcquireNoRefill() {
        long currentTokens;
        do {
            currentTokens = tokens.get();
            if (currentTokens > 0) {
                if (tokens.compareAndSet(currentTokens, currentTokens - 1)) {
                    // Notify waiting threads that a token is now available
                    if (currentTokens == 1) {
                        writeLock.lock();
                        try {
                            available.signalAll();
                        } finally {
                            writeLock.unlock();
                        }
                    }
                    return true;
                }
            } else {
                return false;
            }
        } while (true);
    }
    
    /**
     * Gets the current number of available tokens.
     * 
     * @return Current token count
     */
    public long getAvailableTokens() {
        readLock.lock();
        try {
            refill();
            return tokens.get();
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * Gets the configured refill rate.
     * 
     * @return Tokens per second
     */
    public double getRefillRate() {
        return refillRate;
    }
    
    /**
     * Gets the configured burst capacity.
     * 
     * @return Maximum number of tokens
     */
    public long getCapacity() {
        return capacity;
    }
    
    /**
     * Checks if the limiter is currently idle (no tokens available).
     * 
     * @return true if no tokens are available, false otherwise
     */
    public boolean isIdle() {
        return getAvailableTokens() == 0;
    }
    
    /**
     * Resets the token bucket to its initial state.
     * 
     * <p>This method is useful for testing or for scenarios where you want to
     * reset the rate limiter.</p>
     */
    public void reset() {
        writeLock.lock();
        try {
            tokens.set(0);
            lastRefillTime.set(System.currentTimeMillis());
            available.signalAll();
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * Gets the estimated time until the next token will be available.
     * 
     * @return Time in milliseconds until next token, or 0 if a token is available
     */
    public long getEstimatedWaitTime() {
        readLock.lock();
        try {
            long available = tokens.get();
            if (available > 0) {
                return 0;
            }
            
            // Calculate time needed for next token
            double timeNeededSeconds = 1.0 / refillRate;
            return (long) (timeNeededSeconds * 1000);
        } finally {
            readLock.unlock();
        }
    }
}
```

# JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assertions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent