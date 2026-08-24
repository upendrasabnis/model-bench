<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260824T084054Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To solve this problem, we need to implement a thread-safe token-bucket rate limiter in Java that supports configurable refill rate and burst capacity. The rate limiter should allow both blocking and non-blocking acquisition of tokens, ensuring that the long-run token acquisition rate does not exceed the configured limit.

### Approach
1. **Token Bucket Algorithm**: The token bucket algorithm is used to control the rate at which tokens are consumed. Tokens are added to the bucket at a fixed rate, and each token represents permission to perform an action. The bucket has a maximum capacity (burst capacity) to allow for temporary bursts of activity.

2. **Concurrency Control**: To ensure thread safety, a `ReentrantLock` is used to synchronize access to the token bucket. This prevents race conditions when multiple threads attempt to acquire tokens simultaneously.

3. **Blocking and Non-Blocking Acquire**: The `acquire()` method blocks until tokens are available, while the `tryAcquire()` method returns immediately if tokens are not available. Both methods adjust the token count and refill the bucket based on elapsed time.

4. **Refill Mechanism**: The bucket is refilled periodically based on the configured refill rate and elapsed time since the last refill. This ensures that tokens are available up to the burst capacity when needed.

5. **Documentation**: The design is documented to explain the components and their interactions, ensuring clarity for future maintenance and use.

### Solution Code
```java
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TokenBucketRateLimiter {
    private final int capacity;
    private final int refillRate;
    private final ReentrantLock lock;
    private int tokens;
    private long lastRefillTime;

    public TokenBucketRateLimiter(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.lock = new ReentrantLock();
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }

    public void acquire(int permits) throws InterruptedException {
        lock.lock();
        try {
            refill();
            while (tokens < permits) {
                lock.wait(1000); // Wait for 1 second before checking again
                refill();
            }
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits) {
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

    private void refill() {
        long currentTime = System.nanoTime();
        long elapsedNanos = currentTime - lastRefillTime;
        int refills = (int) (elapsedNanos / TimeUnit.NANOSECONDS.toNanos(1000)); // 1 second intervals
        int addedTokens = refills * refillRate;
        tokens = Math.min(tokens + addedTokens, capacity);
        lastRefillTime = currentTime;
    }
}

public class TokenBucketRateLimiterTest {
    @Test
    public void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2);
        limiter.acquire(3);
        Assertions.assertEquals(2, limiter.tokens);
    }

    @Test
    public void testNonBlockingAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2);
        Assertions.assertTrue(limiter.tryAcquire(3));
        Assertions.assertEquals(2, limiter.tokens);
    }

    @Test
    public void testConcurrentRateLimit() throws InterruptedException {
        final int capacity = 10;
        final int refillRate = 2;
        final int numThreads = 100;
        final int requestsPerThread = 1000;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRate);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    try {
                        limiter.acquire(1);
                        totalAcquired.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Calculate expected maximum tokens acquired
        long totalTime = 10000; // 10 seconds
        int expectedMax = (int) (totalTime * refillRate) + capacity;
        Assertions.assertTrue(totalAcquired.get() <= expectedMax, "Total acquired exceeds expected maximum");
    }
}
```

### Explanation
1. **Token Bucket Initialization**: The `TokenBucketRateLimiter` is initialized with a specified capacity and refill rate. The initial token count is set to the capacity, and the last refill time is set to the current time.

2. **Refill Mechanism**: The `refill()` method calculates the number of tokens to add based on the elapsed time since the last refill. Tokens are added up to the burst capacity, ensuring the bucket does not overflow.

3. **Blocking Acquire**: The `acquire()` method uses a `ReentrantLock` to ensure thread safety. It waits for tokens to become available, refilling the bucket as necessary before consuming the required tokens.

4. **Non-Blocking Acquire**: The `tryAcquire()` method checks if tokens are available immediately. If not, it returns false without blocking.

5. **Concurrent Test**: The concurrent test simulates multiple threads acquiring tokens. It verifies that the total tokens acquired do not exceed the expected maximum, which is calculated based on the refill rate and the total time the threads were running.

This approach ensures that the rate limiter is both efficient and thread-safe, adhering to the specified constraints and providing both blocking and non-blocking acquisition methods.
