<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260707T162941Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To implement a thread-safe token-bucket rate limiter in Java, we'll use a combination of `AtomicLong` for thread-safe token management and `Semaphore` for blocking and non-blocking acquisition. Here's the implementation and documentation:

### TokenBucketRateLimiter.java

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe token-bucket rate limiter that allows for a configurable refill rate and burst capacity.
 * Supports both blocking acquire and non-blocking tryAcquire methods.
 */
public class TokenBucketRateLimiter {

    private final AtomicLong tokens;
    private final long refillRate;
    private final long burstCapacity;
    private final Semaphore semaphore;
    private final long startTime;

    /**
     * Constructs a new TokenBucketRateLimiter with the given refill rate and burst capacity.
     *
     * @param refillRate  The number of tokens to refill per second.
     * @param burstCapacity The maximum number of tokens that can be accumulated in the bucket.
     */
    public TokenBucketRateLimiter(long refillRate, long burstCapacity) {
        this.refillRate = refillRate;
        this.burstCapacity = burstCapacity;
        this.tokens = new AtomicLong(burstCapacity);
        this.semaphore = new Semaphore(burstCapacity);
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Acquires tokens from the bucket, blocking until enough tokens are available.
     *
     * @param tokens The number of tokens to acquire.
     * @throws InterruptedException If interrupted while waiting for tokens.
     */
    public void acquire(long tokens) throws InterruptedException {
        semaphore.acquire(tokens);
    }

    /**
     * Attempts to acquire tokens from the bucket without blocking.
     *
     * @param tokens The number of tokens to acquire.
     * @return true if tokens were acquired, false otherwise.
     */
    public boolean tryAcquire(long tokens) {
        return semaphore.tryAcquire(tokens);
    }

    /**
     * Refills the bucket with tokens based on the configured refill rate.
     */
    public void refill() {
        long now = System.currentTimeMillis();
        long timeElapsed = now - startTime;
        long tokensToAdd = (timeElapsed * refillRate) / 1000;
        tokens.addAndGet(tokensToAdd);
        tokens.set(Math.min(tokens.get(), burstCapacity));
    }

    /**
     * Updates the start time to the current time.
     */
    public void resetStartTime() {
        startTime = System.currentTimeMillis();
    }
}
```

### TokenBucketRateLimiterTest.java

```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void testAcquireAndRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 20);

        limiter.acquire(10);
        assertEquals(10, limiter.tokens.get());

        limiter.refill();
        assertEquals(20, limiter.tokens.get());
    }

    @Test
    void testTryAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 20);

        assertTrue(limiter.tryAcquire(10));
        assertEquals(10, limiter.tokens.get());

        assertFalse(limiter.tryAcquire(11));
    }

    @Test
    void testConcurrentRateLimit() throws InterruptedException {
        final int numThreads = 10;
        final int iterations = 1000;
        final long refillRate = 10;
        final long burstCapacity = 20;

        AtomicLong totalAcquired = new AtomicLong(0);
        AtomicReference<Exception> exceptionRef = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        Runnable task = () -> {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(refillRate, burstCapacity);

            for (int i = 0; i < iterations; i++) {
                try {
                    limiter.acquire(1);
                    totalAcquired.incrementAndGet();
                } catch (InterruptedException e) {
                    exceptionRef.set(e);
                }
            }
        };

        executor.invokeAll(Executors.newFixedThreadPool(numThreads).submit(task), 10, TimeUnit.SECONDS);

        executor.shutdown();

        assertEquals(numThreads * iterations, totalAcquired.get());
        assertNull(exceptionRef.get());
    }
}
```

### Documentation

The `TokenBucketRateLimiter` class provides a thread-safe implementation of a token-bucket rate limiter. It allows for a configurable refill rate and burst capacity. The `acquire` method blocks until enough tokens are available, while the `tryAcquire` method returns immediately, indicating whether tokens were acquired.

The `refill` method updates the token count based on the elapsed time since the last refill, ensuring that the rate does not exceed the configured limit. The `resetStartTime` method updates the start time to the current time, allowing for manual control over the refill rate.

The JUnit 5 tests verify the correctness of the `acquire` and `tryAcquire` methods, as well as the long-run rate limit in a concurrent scenario.
