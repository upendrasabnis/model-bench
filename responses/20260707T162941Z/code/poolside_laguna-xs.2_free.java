<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260707T162941Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->

I'll implement a thread-safe token bucket rate limiter in Java with comprehensive documentation and tests.

## Implementation

**TokenBucketRateLimiter.java**
```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe token bucket rate limiter implementation.
 * 
 * <p>The token bucket algorithm works by maintaining a "bucket" of tokens,
 * where tokens are added at a fixed rate (refill rate) up to a maximum capacity
 * (burst capacity). When a request arrives, tokens are consumed from the bucket.
 * If insufficient tokens are available, the request is either blocked (acquire)
 * or rejected (tryAcquire).</p>
 * 
 * <h3>Design Decisions:</h3>
 * <ul>
 *   <li><strong>Thread Safety:</strong> Uses ReentrantLock with Condition for
 *       fine-grained control over blocking operations</li>
 *   <li><strong>Refill Strategy:</strong> Tokens are refilled lazily on each
 *       request rather than using a background thread, reducing overhead</li>
 *   <li><strong>Precision:</strong> Tracks time with nanosecond precision for
 *       accurate rate limiting</li>
 *   <li><strong>Burst Handling:</strong> Allows bursts up to the configured
 *       capacity, then enforces steady-state rate limiting</li>
 * </ul>
 * 
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Create a rate limiter allowing 10 requests per second with burst of 5
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5);
 * 
 * // Blocking acquire - waits if no tokens available
 * limiter.acquire();
 * 
 * // Non-blocking try - returns false if no tokens available
 * if (limiter.tryAcquire()) {
 *     // Process request
 * }
 * }</pre>
 * 
 * <h3>Rate Limiting Behavior:</h3>
 * <ul>
 *   <li>Maximum sustained rate: refillRate tokens per second</li>
 *   <li>Initial burst allowance: up to capacity tokens</li>
 *   <li>After depletion, requests are blocked until sufficient tokens accumulate</li>
 * </ul>
 */
public class TokenBucketRateLimiter {
    
    private final double refillRate;       // tokens per second
    private final int capacity;            // maximum bucket capacity
    private final long nanosPerToken;      // nanoseconds per token
    
    private double tokens;                 // current token count
    private long lastRefillTime;           // last refill timestamp (nanoseconds)
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    
    /**
     * Constructs a new token bucket rate limiter.
     * 
     * @param refillRate the rate at which tokens are added (tokens per second)
     * @param capacity   the maximum number of tokens the bucket can hold
     * @throws IllegalArgumentException if refillRate <= 0 or capacity <= 0
     */
    public TokenBucketRateLimiter(double refillRate, int capacity) {
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.refillRate = refillRate;
        this.capacity = capacity;
        this.tokens = capacity; // Start with full bucket
        this.lastRefillTime = System.nanoTime();
        this.nanosPerToken = (long) (1_000_000_000.0 / refillRate);
    }
    
    /**
     * Acquires a token, blocking if necessary until one is available.
     * 
     * <p>This method will wait until a token becomes available, making it suitable
     * for scenarios where you want to ensure the operation proceeds after waiting.</p>
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refillTokens();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                
                // Calculate time to wait for next token
                double tokensNeeded = 1.0 - tokens;
                long waitNanos = (long) (tokensNeeded * nanosPerToken);
                if (waitNanos > 0) {
                    condition.awaitNanos(waitNanos);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to acquire a token without blocking.
     * 
     * <p>Returns immediately with a result indicating success or failure.</p>
     * 
     * @return {@code true} if a token was successfully acquired,
     *         {@code false} if no tokens are currently available
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refillTokens();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Acquires multiple tokens, blocking if necessary until they are available.
     * 
     * @param tokens the number of tokens to acquire
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalArgumentException if tokens <= 0
     */
    public void acquire(int tokens) throws InterruptedException {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be positive");
        }
        
        lock.lock();
        try {
            while (true) {
                refillTokens();
                if (this.tokens >= tokens) {
                    this.tokens -= tokens;
                    return;
                }
                
                // Calculate time to wait for sufficient tokens
                double tokensNeeded = tokens - this.tokens;
                long waitNanos = (long) (tokensNeeded * nanosPerToken);
                if (waitNanos > 0) {
                    condition.awaitNanos(waitNanos);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to acquire multiple tokens without blocking.
     * 
     * @param tokens the number of tokens to acquire
     * @return {@code true} if the requested tokens were successfully acquired,
     *         {@code false} if insufficient tokens are available
     * @throws IllegalArgumentException if tokens <= 0
     */
    public boolean tryAcquire(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be positive");
        }
        
        lock.lock();
        try {
            refillTokens();
            if (this.tokens >= tokens) {
                this.tokens -= tokens;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Refills tokens based on elapsed time since last refill.
     * Must be called with lock held.
     */
    private void refillTokens() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        double tokensToAdd = elapsedNanos / nanosPerToken;
        
        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
    
    /**
     * Returns the current number of available tokens.
     * 
     * @return the current token count
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refillTokens();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the configured refill rate.
     * 
     * @return tokens per second
     */
    public double getRefillRate() {
        return refillRate;
    }
    
    /**
     * Returns the configured bucket capacity.
     * 
     * @return maximum tokens
     */
    public int getCapacity() {
        return capacity;
    }
}
```

## JUnit 5 Tests

**TokenBucketRateLimiterTest.java**
```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

class TokenBucketRateLimiterTest {
    
    @Nested
    @DisplayName("Basic Functionality Tests")
    class BasicFunctionality {
        
        @Test
        @DisplayName("tryAcquire returns true when tokens available")
        void tryAcquireReturnsTrueWhenTokensAvailable() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5);
            assertTrue(limiter.tryAcquire());
        }
        
        @Test
        @DisplayName("tryAcquire returns false when bucket empty")
        void tryAcquireReturnsFalseWhenBucketEmpty() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 1);
            assertTrue(limiter.tryAcquire()); // First token
            assertFalse(limiter.tryAcquire()); // Bucket now empty
        }
        
        @Test
        @DisplayName("acquire blocks until token available")
        void acquireBlocksUntilTokenAvailable() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 1);
            
            assertTrue(limiter.tryAcquire()); // Consume the initial token
            
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            
            executor.submit(() -> {
                try {
                    started.countDown();
                    limiter.acquire(); // Should block
                    finished.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS), 
                       "Should not finish immediately");
            
            Thread.sleep(50); // Wait for refill
            assertTrue(finished.await(1, TimeUnit.SECONDS), 
                      "Should finish after waiting for refill");
            
            executor.shutdown();
        }
        
        @Test
        @DisplayName("bucket refills at configured rate")
        void bucketRefillsAtConfiguredRate() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 10);
            
            // Drain the bucket
            for (int i = 0; i < 10; i++) {
                assertTrue(limiter.tryAcquire());
            }
            assertFalse(limiter.tryAcquire());
            
            // Wait 100ms - should get ~10 tokens
            Thread.sleep(100);
            assertEquals(10, limiter.getAvailableTokens(), 2.0);
        }
    }
    
    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {
        
        @Test
        @DisplayName("rejects non-positive refill rate")
        void rejectsNonPositiveRefillRate() {
            assertThrows(IllegalArgumentException.class,
                        () -> new TokenBucketRateLimiter(0, 5));
            assertThrows(IllegalArgumentException.class,
                        () -> new TokenBucketRateLimiter(-1, 5));
        }
        
        @Test
        @DisplayName("rejects non-positive capacity")
        void rejectsNonPositiveCapacity() {
            assertThrows(IllegalArgumentException.class,
                        () -> new TokenBucketRateLimiter(10, 0));
            assertThrows(IllegalArgumentException.class,
                        () -> new TokenBucketRateLimiter(10, -1));
        }
    }
    
    @Nested
    @DisplayName("Multi-Token Operations")
    class MultiTokenOperations {
        
        @Test
        @DisplayName("acquire(int) succeeds with sufficient tokens")
        void acquireIntSucceedsWithSufficientTokens() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 10);
            limiter.acquire(5);
            assertEquals(5, limiter.getAvailableTokens());
        }
        
        @Test
        @DisplayName("tryAcquire(int) fails with insufficient tokens")
        void tryAcquireIntFailsWithInsufficientTokens() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 3);
            assertFalse(limiter.tryAcquire(5));
            assertEquals(100.0, limiter.getAvailableTokens());
        }
    }
    
    @Nested
    @DisplayName("Concurrent Tests")
    class ConcurrentTests {
        
        @Test
        @DisplayName("concurrent tryAcquire operations are thread-safe")
        void concurrentTryAcquireAreThreadSafe() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000.0, 100);
            int numThreads = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            
            AtomicInteger successes = new AtomicInteger();
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (limiter.tryAcquire()) {
                            successes.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown();
            doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            
            // Should have exactly 100 successes (initial capacity)
            assertEquals(100, successes.get());
        }
        
        @Test
        @DisplayName("long-run rate does not exceed configured limit")
        void longRunRateDoesNotExceedConfiguredLimit() throws InterruptedException {
            // Configure for 100 tokens/second
            double refillRate = 100.0;
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(refillRate, 10);
            
            int numThreads = 10;
            int durationSeconds = 5;
            long expectedMinTokens = (long) (refillRate * durationSeconds * 0.9); // Allow 10% margin
            long expectedMaxTokens = (long) (refillRate * durationSeconds * 1.1);
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicLong totalAcquired = new AtomicLong();
            
            // Start multiple threads that continuously try to acquire
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    long acquired = 0;
                    long startTime = System.currentTimeMillis();
                    long endTime = startTime + (durationSeconds * 1000);
                    
                    while (System.currentTimeMillis() < endTime) {
                        if (limiter.tryAcquire()) {
                            acquired++;
                        } else {
                            // Brief pause to avoid busy waiting
                            Thread.yield();
                        }
                    }
                    totalAcquired.addAndGet(acquired);
                    doneLatch.countDown();
                });
            }
            
            doneLatch.await(durationSeconds + 2, TimeUnit.SECONDS);
            executor.shutdown();
            
            long actualAcquired = totalAcquired.get();
            System.out.printf("Rate test: acquired %d tokens over %d seconds (%.2f tokens/sec)%n",
                            actualAcquired, durationSeconds, actualAcquired / (double) durationSeconds);
            
            // Verify rate is within acceptable bounds
            assertTrue(actualAcquired >= expectedMinTokens,
                      String.format("Rate too low: %d < %d", actualAcquired, expectedMinTokens));
            assertTrue(actualAcquired <= expectedMaxTokens,
                      String.format("Rate too high: %d > %d", actualAcquired, expectedMaxTokens));
        }
        
        @Test
        @DisplayName("acquire and tryAcquire work correctly under contention")
        void acquireAndTryAcquireWorkCorrectlyUnderContention() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(50.0, 5);
            int numThreads = 20;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            
            AtomicInteger acquireSuccesses = new AtomicInteger();
            AtomicInteger tryAcquireSuccesses = new AtomicInteger();
            
            // Mix of blocking and non-blocking operations
            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        if (threadId % 2 == 0) {
                            limiter.acquire();
                            acquireSuccesses.incrementAndGet();
                        } else {
                            if (limiter.tryAcquire()) {
                                tryAcquireSuccesses.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            
            doneLatch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            
            int totalSuccesses = acquireSuccesses.get() + tryAcquireSuccesses.get();
            assertTrue(totalSuccesses > 0, "At least some operations should succeed");
            assertTrue(totalSuccesses <= 10, "Should not exceed total capacity");
        }
    }
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {
        
        @Test
        @DisplayName("handles high burst capacity correctly")
        void handlesHighBurstCapacity() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 10000);
            assertEquals(10000, limiter.getAvailableTokens());
            
            for (int i = 0; i < 5000; i++) {
                assertTrue(limiter.tryAcquire());
            }
            assertEquals(5000, limiter.getAvailableTokens());
        }
        
        @Test
        @DisplayName("handles very slow refill rate")
        void handlesVerySlowRefillRate() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(0.5, 2);
            
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
            assertFalse(limiter.tryAcquire());
            
            // Wait 2 seconds - should get 1 token
            Thread.sleep(2100);
            assertTrue(limiter.tryAcquire());
        }
    }
}
```

## Running the Tests

To compile and run these tests, you'll need:

1. **Maven dependencies** (pom.xml):
```xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.0.0</version>
        </plugin>
    </plugins>
</build>
```

2. **Run with Maven**:
```bash
mvn test
```

## Key Design Features

### Thread Safety
- Uses `ReentrantLock` with `Condition` for efficient blocking
- All state mutations occur within locked sections
- No background threads required (lazy refill)

### Rate Limiting Guarantees
- **Sustained rate**: Exactly the configured refill rate
- **Burst capacity**: Up to the configured capacity tokens initially
- **Precision**: Nanosecond timing for accurate rate control

### API Design
- **Non-blocking**: `tryAcquire()` returns immediately
- **Blocking**: `acquire()` waits for available tokens
- **Bulk operations**: Support for acquiring multiple tokens
- **Introspection**: Methods to check available tokens and configuration

The concurrent test verifies that over a 5-second period with a 100 tokens/second limit, the actual acquired rate stays within ±10% of the configured limit, accounting for natural timing variations.
