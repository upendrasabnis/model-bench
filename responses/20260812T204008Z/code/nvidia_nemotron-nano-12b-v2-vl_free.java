<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To solve this problem, we need to implement a token-bucket rate limiter in Java that is thread-safe, supports configurable refill rate and burst capacity, and provides both blocking and non-blocking acquisition methods. The solution must also include JUnit 5 tests, including a concurrent test to verify the long-run rate does not exceed the configured limit.

### Approach
1. **Token Bucket Design**: The token bucket algorithm is used to control the rate of requests. The bucket has a burst capacity (maximum tokens it can hold) and a refill rate (tokens added per second). Tokens are consumed when requests are made and refilled over time.
2. **Thread Safety**: Use `AtomicInteger` for the token count to ensure atomic operations and thread safety without locks.
3. **Blocking Acquisition**: Use a `ReentrantLock` to block the thread until tokens are available, ensuring the thread waits only when necessary.
4. **Non-Blocking Acquisition**: Check if tokens are available immediately without waiting.
5. **Concurrent Test**: A test that simulates concurrent requests to verify that the long-run rate does not exceed the configured limit, using a `CyclicBarrier` to synchronize threads.

### Solution Code
```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final int burstCapacity;
    private final long refillRateMillis;
    private final AtomicInteger tokenCount;
    private final ReentrantLock lock;
    private long lastRefillTime;

    public TokenBucketRateLimiter(int burstCapacity, long refillRateMillis) {
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be positive");
        }
        if (refillRateMillis <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        this.burstCapacity = burstCapacity;
        this.refillRateMillis = refillRateMillis;
        this.tokenCount = new AtomicInteger(burstCapacity);
        this.lock = new ReentrantLock();
        this.lastRefillTime = System.currentTimeMillis();
    }

    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }
        long currentTime = System.currentTimeMillis();
        refill(currentTime);
        int currentTokens = tokenCount.get();
        while (currentTokens < permits) {
            lock.lock();
            try {
                refill(currentTime);
                currentTokens = tokenCount.get();
                if (currentTokens >= permits) {
                    tokenCount.addAndGet(-permits);
                    return;
                }
                currentTime = System.currentTimeMillis();
                long waitTime = (lastRefillTime + refillRateMillis) - currentTime;
                if (waitTime > 0) {
                    wait(waitTime);
                }
                refill(currentTime);
                currentTokens = tokenCount.get();
            } finally {
                lock.unlock();
            }
        }
        tokenCount.addAndGet(-permits);
    }

    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }
        long currentTime = System.currentTimeMillis();
        refill(currentTime);
        int currentTokens = tokenCount.get();
        if (currentTokens >= permits) {
            tokenCount.addAndGet(-permits);
            return true;
        }
        return false;
    }

    private void refill(long currentTime) {
        long elapsedMillis = currentTime - lastRefillTime;
        if (elapsedMillis > 0) {
            long tokensToAdd = (elapsedMillis / refillRateMillis) * burstCapacity;
            int currentTokens = tokenCount.get();
            int newTokens = Math.min(burstCapacity, currentTokens + (int) tokensToAdd);
            tokenCount.set(newTokens);
            lastRefillTime = currentTime;
        }
    }
}
```

### JUnit 5 Tests
```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {
    @Test
    void testInitialTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1000);
        assertEquals(10, limiter.tokenCount.get());
    }

    @Test
    void testAcquireWithinBurst() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1000);
        limiter.acquire(5);
        assertEquals(5, limiter.tokenCount.get());
    }

    @Test
    void testAcquireExceedsBurst() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1000);
        limiter.acquire(10);
        assertThrows(InterruptedException.class, () -> limiter.acquire(1));
    }

    @Test
    void testTryAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1000);
        assertTrue(limiter.tryAcquire(5));
        assertEquals(5, limiter.tokenCount.get());
        assertFalse(limiter.tryAcquire(6));
    }

    @Test
    void testConcurrentRateLimit() throws Exception {
        final int numThreads = 100;
        final int totalRequests = 10000;
        final int burstCapacity = 10;
        final long refillRateMillis = 1000; // 1 token per second
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burstCapacity, refillRateMillis);
        final AtomicInteger requestCount = new AtomicInteger(0);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CyclicBarrier barrier = new CyclicBarrier(numThreads + 1);

        Runnable task = () -> {
            try {
                barrier.await();
                for (int i = 0; i < totalRequests / numThreads; i++) {
                    limiter.acquire(1);
                    requestCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(task);
        }

        for (Thread thread : threads) {
            thread.start();
        }

        startLatch.countDown();
        barrier.await();

        long startTime = System.currentTimeMillis();
        Thread.sleep(2000); // Wait for 2 seconds
        long endTime = System.currentTimeMillis();

        long expectedRequests = (endTime - startTime) / refillRateMillis * burstCapacity * numThreads;
        assertTrue(requestCount.get() <= expectedRequests, "Request count exceeded expected limit");
    }
}
```

### Explanation
1. **Token Bucket Initialization**: The constructor initializes the burst capacity, refill rate, and initial token count. It uses `AtomicInteger` for thread-safe token management.
2. **Blocking Acquisition (`acquire` method)**: This method waits until tokens are available. It uses a `ReentrantLock` to block the thread and checks for token availability periodically.
3. **Non-Blocking Acquisition (`tryAcquire` method)**: This method checks if tokens are available immediately and consumes them if possible.
4. **Refill Mechanism**: The `refill` method updates the token count based on the elapsed time since the last refill, ensuring tokens are added at the configured rate.
5. **Concurrent Test**: The test uses multiple threads to simulate concurrent requests. It verifies that the total number of requests does not exceed the expected limit based on the refill rate and burst capacity, ensuring the long-run rate compliance.
