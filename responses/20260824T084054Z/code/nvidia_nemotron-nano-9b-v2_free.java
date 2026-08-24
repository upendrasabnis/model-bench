<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260824T084054Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->


```java
// TokenBucketRateLimiter.java
package com.example.rate Limiter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final int maxTokens;
    private final double refillRate; // tokens per second
    private int tokens;
    private long lastRefillTime;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tokensAvailable = lock.newCondition();

    public TokenBucketRateLimiter(int maxTokens, double refillRate) {
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
        if (refillRate < 0) throw new IllegalArgumentException("refillRate must be non-negative");
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.tokens = maxTokens;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public boolean tryAcquire(int permits) {
        lock.lock();
        try {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    public void acquire(int permits) throws InterruptedException {
        lock.lock();
        try {
            refill();
            while (tokens < permits) {
                tokensAvailable.await();
                refill(); // Refill again after waiting
            }
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed > 0) {
            double tokensToAdd = (elapsed / 1000.0) * refillRate;
            tokens = Math.min(tokens + (int) tokensToAdd, maxTokens);
            lastRefillTime = now;
        }
    }
}
```

```java
// TokenBucketRateLimiterTest.java
package com.example.rate Limiter;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(10, 2.0); // 10 tokens, 2 per second
    }

    @Test
    @Order(1)
    void testAcquireTokens() throws InterruptedException {
        // Acquire all tokens
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1));
        }
        assertFalse(limiter.tryAcquire(1)); // Should fail

        // Wait for 1 second, should refill 2 tokens
        Thread.sleep(1000);
        assertTrue(limiter.tryAcquire(2));
    }

    @Test
    @Order(2)
    void testBurstCapacity() {
        limiter = new TokenBucketRateLimiter(5, 0.0); // No refill

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1));
        }
        assertFalse(limiter.tryAcquire(1)); // Should fail
    }

    @Test
    @Order(3)
    void testConcurrentRateLimiter() throws InterruptedException {
        int maxTokens = 10;
        double refillRate = 5.0;
        int numThreads = 100;
        long durationMillis = 10000; // 10 seconds

        limiter = new TokenBucketRateLimiter(maxTokens, refillRate);
        AtomicInteger totalAcquired = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        limiter.acquire(1);
                        totalAcquired.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        Thread.sleep(durationMillis);
        executor.shutdownNow();

        long expectedMax = maxTokens + (long) (refillRate * durationMillis / 1000);
        assertTrue("Total acquired should not exceed " + expectedMax, totalAcquired.get() <= expectedMax);
    }
}
```

**Design Documentation:**

1. **Token Bucket Algorithm**: The limiter uses a bucket that holds tokens. Tokens are acquired when a thread calls `tryAcquire` or `acquire`. The bucket refills at a configurable rate (`refillRate` tokens per second) and has a maximum capacity (`maxTokens`).

2. **Thread Safety**: 
   - A `ReentrantLock` ensures atomic access to the bucket's state.
   - A `Condition` variable is used to wait for tokens in the blocking `acquire` method.

3. **Refill Mechanism**: 
   - Each acquisition attempt triggers a refill based on the time elapsed since the last refill.
   - Tokens are added as `refillRate * elapsedTime (in seconds)`, capped at `maxTokens`.

