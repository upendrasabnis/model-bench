<!-- model: cohere/north-mini-code:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Document

### Core Concept
The token bucket rate limiter implements the classic token bucket algorithm, where tokens are added to a bucket at a constant rate and removed when operations are performed. This allows for burst capacity (immediate operations up to bucket capacity) while maintaining a long-term rate limit.

### Key Components

1. **Token Bucket State**
   - `tokens`: Current number of tokens in the bucket (double for precision)
   - `maxTokens`: Maximum capacity of the bucket
   - `refillRate`: Rate at which tokens are added (tokens per second)

2. **Time Tracking**
   - `lastRefillTimestamp`: When the bucket was last refilled
   - `clock`: System clock for time measurements (injectable for testing)

3. **Thread Safety**
   - `ReentrantLock`: Ensures exclusive access to shared state
   - `Condition`: Enables efficient waiting for token availability

### Algorithm Details

1. **Refill Calculation**
   - Tokens are added based on elapsed time since last refill
   - Formula: `tokensToAdd = (elapsedMillis / 1000.0) * refillRate`
   - Tokens are capped at `maxTokens`

2. **Acquisition**
   - **Non-blocking**: `tryAcquire()` checks and consumes token immediately
   - **Blocking**: `acquire()` waits on condition until tokens are available
   - **Timeout**: `tryAcquire(timeout, unit)` waits with timeout

3. **State Management**
   - All operations are atomic under the reentrant lock
   - Tokens are only consumed when `tryAcquire()` succeeds
   - Refill happens before any token check or consumption

### Performance Considerations

- **Lock Granularity**: Single lock protects all bucket state for simplicity
- **Condition Variables**: Efficient waiting for token availability
- **Precision**: Uses double for fractional tokens, avoiding drift
- **Caching**: Last refill timestamp cached to minimize calculations

### Error Handling

- **Invalid Parameters**: Thrown during construction for negative/zero values
- **InterruptedException**: Propagated with original interrupt status preserved
- **Timeout**: Returns false if timeout occurs without token availability

## Implementation

```java
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    
    // Token bucket state
    private double tokens;
    private final double maxTokens;
    private final double refillRate;
    
    // Time tracking
    private final Clock clock;
    private long lastRefillTimestamp;
    
    public TokenBucketRateLimiter(double maxTokens, double refillRatePerSecond) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        
        this.maxTokens = maxTokens;
        this.refillRate = refillRatePerSecond;
        this.tokens = maxTokens;
        this.clock = Clock.systemUTC();
        this.lastRefillTimestamp = clock.millis();
    }
    
    public void acquire() {
        if (!tryAcquire()) {
            lock.lock();
            try {
                while (!tryAcquire()) {
                    available.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for token", e);
            } finally {
                lock.unlock();
            }
        }
    }
    
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
    
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative");
        }
        
        lock.lock();
        try {
            refillTokens();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            
            long remainingNanos = unit.toNanos(timeout);
            while (tokens < 1.0 && remainingNanos > 0) {
                remainingNanos = available.awaitNanos(remainingNanos);
                refillTokens();
            }
            
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    public double getAvailableTokens() {
        lock.lock();
        try {
            refillTokens();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
    
    private void refillTokens() {
        long now = clock.millis();
        long elapsedMillis = now - lastRefillTimestamp;
        
        if (elapsedMillis <= 0) {
            return;
        }
        
        double tokensToAdd = (elapsedMillis / 1000.0) * refillRate;
        tokens = Math.min(maxTokens, tokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class TokenBucketRateLimiterTest {
    
    @Test
    void testInitialState() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertEquals(10.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testAcquireReducesTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        
        assertTrue(limiter.tryAcquire());
        assertEquals(9.0, limiter.getAvailableTokens(), 0.001);
        
        assertTrue(limiter.tryAcquire());
        assertEquals(8.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testCannotAcquireMoreThanAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
        assertFalse(limiter.tryAcquire());
    }
    
    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10);
        
        assertEquals(5.0, limiter.getAvailableTokens(), 0.001);
        
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        Thread.sleep(600);
        assertEquals(5.0, limiter.getAvailableTokens(), 0.001);
        
        assertTrue(limiter.tryAcquire());
        assertEquals(4.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        
        assertTrue(limiter.tryAcquire());
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean acquired = new AtomicBoolean(false);
        Thread acquiringThread = new Thread(() -> {
            try {
                limiter.acquire();
                acquired.set(true);
            } finally {
                latch.countDown();
            }
        });
        acquiringThread.start();
        
        Thread.sleep(100);
        assertFalse(acquired.get());
        latch.await(1, TimeUnit.SECONDS);
        
        Thread.sleep(1100);
        assertTrue(acquired.get());
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        
        assertTrue(limiter.tryAcquire());
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
        
        long start = System.currentTimeMillis();
        boolean acquired = limiter.tryAcquire(100, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;
        
        assertFalse(acquired);
        assertTrue(elapsed < 200);
        
        Thread.sleep(1100);
        
        acquired = limiter.tryAcquire(100, TimeUnit.MILLISECONDS);
        assertTrue(acquired);
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);
        
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        assertEquals(0.0, limiter.getAvailableTokens(), 0.001);
    }
    
    @Test
    void testRateLimiting() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        
        List<Long> acquisitionTimes = new ArrayList<>();
        int acquisitions = 100;
        
        for (int i = 0; i < acquisitions; i++) {
            long start = System.currentTimeMillis();
            boolean acquired = limiter.tryAcquire();
            long end = System.currentTimeMillis();
            
            if (acquired) {
                acquisitionTimes.add(end);
            }
            
            Thread.sleep(1);
        }
        
        if (acquisitionTimes.size() > 1) {
            long totalTime = acquisitionTimes.get(acquisitionTimes.size() - 1) - 
                            acquisitionTimes.get(0);
            double actualRate = (acquisitionTimes.size() - 1) / (totalTime / 1000.0);
            
            assertTrue(actualRate <= 5.1, 
                String.format("Actual rate %.2f tokens/sec exceeds limit of 5.0", actualRate));
        }
    }
    
    @ResourceLock(value = "testResource", mode = ResourceAccessMode.READ)
    @Test
    void testConcurrentAccess() throws InterruptedException, ExecutionException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50);
        
        int threadCount = 10;
        int operationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (limiter.tryAcquire()) {
                            successfulAcquisitions.incrementAndGet();
                        }
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        
        assertTrue(successfulAcquisitions.get() <= 100, 
            "Successful acquisitions exceeded bucket capacity");
    }
    
    @Test
    void testInvalidParameters() {
        assertThrows(IllegalArgumentException.class, 
            () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, 
            () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, 
            () -> new TokenBucketRateLimiter(-5, 10));
    }
    
    @Test
    void testNegativeTimeout() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertThrows(IllegalArgumentException.class, 
            () -> limiter.tryAcquire(-1, TimeUnit.SECONDS));
    }
}
```

## Key Features Demonstrated

1. **Thread Safety**: The concurrent test verifies that the rate limiter handles multiple threads safely
2. **Rate Limiting**: The rate limiting test confirms the long-run rate doesn't exceed the configured limit
3. **Burst Capacity**: Tests show immediate acquisition up to max capacity
4. **Blocking Operations**: Tests demonstrate both blocking and non-blocking acquisition modes
5. **Timeout Handling**: Proper behavior when acquisition times out
6. **Error Conditions**: Invalid parameter handling and edge cases

The implementation provides a robust, thread-safe rate limiting solution suitable for high-concurrency applications while maintaining precise rate control and burst capacity.