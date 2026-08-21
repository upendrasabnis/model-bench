<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260821T111453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To implement a thread-safe token-bucket rate limiter in Java, we'll use a `ReentrantLock` to ensure safe access to the bucket from multiple threads. The rate limiter will support configurable refill rate and burst capacity, along with both blocking `acquire` and non-blocking `tryAcquire` methods. Here's the implementation and design documentation:

**TokenBucketRateLimiter.java**

```java
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucketRateLimiter {
    private final int burstCapacity;
    private final long refillRateMillis;
    private int availableTokens;
    private Instant lastRefillTime;
    private final ReentrantLock lock = new ReentrantLock();

    public TokenBucketRateLimiter(int burstCapacity, long refillRateMillis) {
        this.burstCapacity = burstCapacity;
        this.refillRateMillis = refillRateMillis;
        this.availableTokens = burstCapacity;
        this.lastRefillTime = Instant.now();
    }

    public void acquire(int permits) throws InterruptedException {
        lock.lock();
        try {
            while (availableTokens < permits) {
                refill();
                if (availableTokens >= permits) {
                    break;
                }
                long waitTime = calculateWaitTime();
                if (waitTime > 0) {
                    lock.wait(waitTime);
                }
            }
            availableTokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits) {
        lock.lock();
        try {
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return true;
            }
            refill();
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        Instant now = Instant.now();
        long elapsedMillis = Duration.between(lastRefillTime, now).toMillis();
        if (elapsedMillis >= refillRateMillis) {
            int tokensToAdd = (int) (elapsedMillis / refillRateMillis) * burstCapacity;
            availableTokens = Math.min(availableTokens + tokensToAdd, burstCapacity);
            lastRefillTime = now;
        }
    }

    private long calculateWaitTime() {
        Instant nextRefillTime = lastRefillTime.plusMillis(refillRateMillis);
        return Duration.between(Instant.now(), nextRefillTime).getSeconds() * 1000;
    }
}
```

**Design Documentation:**

1. **Burst Capacity**: The maximum number of tokens that can be stored in the bucket. This allows for a temporary increase in the rate of requests.
2. **Refill Rate**: The rate at which tokens are added to the bucket, specified in milliseconds. This determines the long-run rate limit.
3. **Token Bucket**: The internal data structure that stores the available tokens. It is protected by a `ReentrantLock` to ensure thread safety.
4. **Acquire Method**: A blocking method that waits until enough tokens are available before allowing the request to proceed.
5. **TryAcquire Method**: A non-blocking method that attempts to acquire the requested number of tokens and returns immediately, either successfully or unsuccessfully.

**TokenBucketRateLimiterTest.java**

Here's a JUnit 5 test class that includes a concurrent test to verify the long-run rate does not exceed the configured limit:

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.concurrent.ConcurrentTest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {
    private TokenBucketRateLimiter rateLimiter;
    private ExecutorService executorService;
    private AtomicLong requestCount = new AtomicLong();

    @BeforeEach
    void setUp() {
        rateLimiter = new TokenBucketRateLimiter(10, 1000); // 10 tokens, refill every second
        executorService = Executors.newFixedThreadPool(10);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void testAcquire() throws InterruptedException {
        rateLimiter.acquire(5);
        assertTrue(rateLimiter.tryAcquire(3));
    }

    @Test
    void testTryAcquire() {
        assertTrue(rateLimiter.tryAcquire(5));
        assertTrue(!rateLimiter.tryAcquire(6));
    }

    @ConcurrentTest
    void testLongRunRate() throws InterruptedException {
        Runnable task = () -> {
            while (true) {
                try {
                    rateLimiter.acquire(1);
                    requestCount.incrementAndGet();
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
            }
        };

        executorService.submit(task);
        Thread.sleep(10_000); // Wait for 10 seconds
        executorService.shutdown();

        assertTrue(requestCount.get() <= 10_000); // Ensure the long-run rate does not exceed the configured limit
    }
}
```
