<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260710T130300Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To implement a token-bucket rate limiter in Java, we can use the `java.util.concurrent.Semaphore` class to control access to the token bucket. Here's the design and implementation:

**Design:**

1. **Token Bucket:** A data structure that holds a certain number of tokens (burst capacity) and refills at a specified rate. The bucket has two main components:
   - `tokenCount`: The current number of tokens in the bucket.
   - `maxTokens`: The maximum number of tokens the bucket can hold (burst capacity).

2. **Refill Rate:** The rate at which tokens are added to the bucket. This can be configured using a `TimeUnit` and a `long` value representing the number of tokens to add per refill interval.

3. **Blocking Acquire (`acquire()`):** A method that blocks the calling thread until there are enough tokens to acquire. If the bucket is empty, the thread will wait until tokens become available.

4. **Non-Blocking Try Acquire (`tryAcquire()`):** A method that attempts to acquire tokens without blocking. If there are not enough tokens available, the method returns `false`.

5. **Concurrency Control:** To ensure thread safety, we'll use a `Semaphore` to control access to the token bucket. The semaphore's permit count will be equal to the burst capacity.

**Implementation:**

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TokenBucketRateLimiter {

    private final AtomicInteger tokenCount;
    private final int maxTokens;
    private final AtomicLong lastRefillTime;
    private final long refillRate;
    private final TimeUnit refillTimeUnit;
    private final Semaphore semaphore;

    public TokenBucketRateLimiter(int maxTokens, long refillRate, TimeUnit refillTimeUnit) {
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.refillTimeUnit = refillTimeUnit;
        this.tokenCount = new AtomicInteger(maxTokens);
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        this.semaphore = new Semaphore(maxTokens);
    }

    public void acquire() throws InterruptedException {
        semaphore.acquire();
        refillTokens();
    }

    public boolean tryAcquire() {
        if (semaphore.tryAcquire()) {
            refillTokens();
            return true;
        }
        return false;
    }

    private void refillTokens() {
        long now = System.currentTimeMillis();
        long timeElapsed = now - lastRefillTime.get();
        if (timeElapsed >= refillTimeUnit.toMillis(1)) {
            int tokensToAdd = (int) (timeElapsed / refillTimeUnit.toMillis(1)) * refillRate;
            int newTokenCount = Math.min(tokenCount.get() + tokensToAdd, maxTokens);
            tokenCount.set(newTokenCount);
            lastRefillTime.set(now);
            semaphore.release(tokensToAdd);
        }
    }

    public static void main(String[] args) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    limiter.acquire();
                    System.out.println("Acquired token");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
    }
}
```

**JUnit 5 Tests:**

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(10, 1, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        limiter = null;
    }

    @Test
    void testAcquire() throws InterruptedException {
        limiter.acquire();
        assertEquals(9, limiter.tokenCount.get());
    }

    @Test
    void testTryAcquire() {
        assertTrue(limiter.tryAcquire());
        assertEquals(9, limiter.tokenCount.get());
    }

    @Test
    @Timeout(10)
    void testLongRunRate() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger acquiredTokens = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    limiter.acquire();
                    acquiredTokens.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(acquiredTokens.get() <= 10);
    }
}
```
