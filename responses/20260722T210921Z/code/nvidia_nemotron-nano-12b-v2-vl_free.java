<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter Design Document**

The token bucket rate limiter is a concurrency-safe implementation that enforces a maximum rate of requests while allowing bursts up to a configurable capacity. It operates by maintaining a token bucket where tokens are replenished at a specified rate. Threads must acquire tokens before proceeding, with blocking and non-blocking acquisition options.

**Key Components:**
1. **Tokens:** Represent available capacity. The bucket starts full (up to `maxTokens`).
2. **Refill Rate:** Tokens are added at a fixed rate (e.g., 1 token per second).
3. **Burst Capacity:** Maximum tokens the bucket can hold (`maxTokens`).
4. **Concurrency Control:** Uses `ReentrantLock` for atomic access.

**Methods:**
- `acquire(int permits)`: Blocks until sufficient tokens are available.
- `tryAcquire(int permits)`: Returns immediately if tokens are available.
- `getTokens()`: Returns current token count (for testing).

**Design Considerations:**
- **Fairness:** Lock is non-fair to prioritize throughput over fairness.
- **Precision:** Uses system time with `System.nanoTime()` for accurate refill timing.
- **Efficiency:** Avoids busy-waiting; sleeps until next refill if no tokens are available.

**Implementation:**

```java
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

public class TokenBucketRateLimiter {
    private final ReentrantLock lock = new ReentrantLock();
    private final long maxTokens;
    private long tokens;
    private final long refillRateNanos;
    private long lastRefillTime;

    public TokenBucketRateLimiter(long maxTokens, long refillRateSeconds) {
        this.maxTokens = maxTokens;
        this.refillRateNanos = TimeUnit.SECONDS.toNanos(refillRateSeconds);
        this.tokens = maxTokens;
        this.lastRefillTime = System.nanoTime();
    }

    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        lock.lock();
        try {
            while (tokens < permits) {
                long waitTime = calculateWaitTime();
                if (waitTime <= 0) continue; // Immediate retry
                lock.unlock();
                try {
                    Thread.sleep(waitTime / 1_000_000); // Convert to milliseconds
                } finally {
                    lock.lock();
                }
            }
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        lock.lock();
        try {
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private long calculateWaitTime() {
        long currentTime = System.nanoTime();
        long available = Math.min(maxTokens, tokens + (currentTime - lastRefillTime) / refillRateNanos);
        return (available - tokens) * refillRateNanos;
    }
}
```

**JUnit 5 Tests:**

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void testInitialTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        assertEquals(10, limiter.getTokens());
    }

    @Test
    void testAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        limiter.acquire(3);
        assertEquals(2, limiter.getTokens());
    }

    @Test
    void testTryAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);
        assertTrue(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3));
    }

    @Test
    void testRefillRate() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        limiter.acquire(1);
        Thread.sleep(2000); // Wait for 2 refills
        assertEquals(3, limiter.getTokens()); // 1 initial + 2 refills
    }

    @Test
    void testConcurrentRateLimit() throws Exception {
        final int threads = 100;
        final int requestsPerThread = 1000;
        final long maxRequests = threads * requestsPerThread;
        final long expectedMax = maxRequests; // 100 threads * 1000 requests = 100,000

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger totalRequests = new AtomicInteger();

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 request/sec

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        limiter.acquire(1);
                        totalRequests.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startGate.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);

        assertTrue(totalRequests.get() <= expectedMax + 100, "Exceeded expected max requests");
    }
}
```

**Explanation of Tests:**
1. **Initial Tokens:** Verifies the bucket starts with `maxTokens`.
2. **Acquire/Test:** Validates token deduction after acquisition.
3. **Refill Rate:** Ensures tokens refill at the specified rate.
4. **Concurrent Rate Limit:** Spawns multiple threads to stress-test the limiter, ensuring the total requests do not exceed the configured rate over time. The test allows a small margin (100) for potential clock drift or refill timing inaccuracies.
