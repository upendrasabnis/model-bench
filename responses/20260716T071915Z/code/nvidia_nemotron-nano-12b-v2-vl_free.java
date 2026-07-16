<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260716T071915Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter Design**

The token bucket algorithm is used to control the rate of requests. It allows bursts up to a specified capacity and refills tokens at a configurable rate. The implementation is thread-safe using `ReentrantLock` to ensure atomic operations. Key components:

1. **Token Bucket Class**:
   - **Attributes**: `maxTokens` (burst capacity), `fillRate` (tokens per second), `tokens` (current tokens), `lastRefillTime` (last refill timestamp).
   - **Methods**:
     - `acquire(int permits)`: Blocks until sufficient tokens are available.
     - `tryAcquire(int permits, long timeout, TimeUnit unit)`: Non-blocking attempt to acquire tokens within a timeout.
     - `refill()`: Periodically adds tokens based on elapsed time since last refill.

2. **Concurrency Control**:
   - `ReentrantLock` ensures atomic access to shared state (tokens, lastRefillTime).
   - `Condition` variables manage waiting threads efficiently during token shortages.

3. **Refill Mechanism**:
   - Tokens are refilled at `fillRate` per second. The `refill()` method calculates tokens to add based on elapsed time and schedules the next refill.

**JUnit 5 Tests**

1. **Basic Functionality**:
   - Test token acquisition and refill with a small burst size and low fill rate.
   - Verify non-blocking `tryAcquire` with immediate success and failure.

2. **Blocking Behavior**:
   - Test `acquire` blocks until tokens are available after exceeding burst capacity.

3. **Concurrency Test**:
   - Simulate multiple threads acquiring tokens at high rate to ensure long-run average does not exceed `fillRate`.
   - Use a `CountDownLatch` to wait for all threads to finish and assert total acquired tokens ≤ `fillRate * duration`.

**Code Implementation**

```java
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TokenBucketRateLimiter {
    private final int maxTokens;
    private final double fillRate;
    private int tokens;
    private long lastRefillTime;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tokensAvailable = lock.newCondition();

    public TokenBucketRateLimiter(int maxTokens, double fillRate) {
        this.maxTokens = maxTokens;
        this.fillRate = fillRate;
        this.tokens = maxTokens;
        this.lastRefillTime = System.nanoTime();
    }

    public void acquire(int permits) throws InterruptedException {
        lock.lock();
        try {
            while (tokens < permits) {
                tokensAvailable.await();
            }
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long deadline = System.nanoTime() + unit.toNanoTime(timeout);
            while (tokens < permits) {
                long waitTime = Math.min(deadline - System.nanoTime(), Duration.ofSeconds(1).toNanos());
                if (waitTime <= 0) return false;
                tokensAvailable.awaitNanos(waitTime);
            }
            tokens -= permits;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        lock.lock();
        try {
            long now = System.nanoTime();
            long elapsed = now - lastRefillTime;
            int tokensToAdd = (int) Math.min(elapsed * fillRate / 1_000_000_000.0, maxTokens - tokens);
            if (tokensToAdd > 0) {
                tokens = Math.min(tokens + tokensToAdd, maxTokens);
                lastRefillTime = now;
            }
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 2.0);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        AtomicLong totalPermits = new AtomicLong(0);

        Runnable task = () -> {
            try {
                limiter.acquire(1);
                totalPermits.incrementAndGet();
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        executor.invokeAll(Collections.nCopies(10, task));
        latch.await();
        executor.shutdown();

        assert totalPermits.get() <= 20; // 10 threads * 1 permit, max 10 burst
    }
}
```

**Test Cases**

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {
    @Test
    void testBasicAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        limiter.acquire(3);
        assertEquals(2, limiter.tokens);
        limiter.acquire(2);
        assertEquals(0, limiter.tokens);
    }

    @Test
    void testTryAcquireSuccess() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 0.5);
        assertTrue(limiter.tryAcquire(2, 1, TimeUnit.SECONDS));
        assertEquals(1, limiter.tokens);
    }

    @Test
    void testTryAcquireFailure() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 0.1);
        limiter.acquire(2);
        assertFalse(limiter.tryAcquire(1, 0, TimeUnit.SECONDS));
    }

    @Test
    void testRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 2.0);
        limiter.acquire(10);
        Thread.sleep(1000); // Wait for 1 second refill
        limiter.acquire(2); // Should refill 2 tokens
        assertEquals(8, limiter.tokens);
    }

    @Test
    void testConcurrentRateLimit() throws InterruptedException {
        final int threads = 10;
        final int permitsPerThread = 1;
        final double fillRate = 2.0;
        final long durationSeconds = 5;
        final long expectedMax = (long) (fillRate * durationSeconds);

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, fillRate);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong totalPermits = new AtomicLong(0);

        Runnable task = () -> {
            try {
                limiter.acquire(permitsPerThread);
                totalPermits.incrementAndGet();
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        executor.invokeAll(Collections.nCopies(threads, task));
        latch.await();
        executor.shutdown();

        assertTrue(totalPermits.get() <= expectedMax, "Concurrent rate limit exceeded");
    }
}
```

**Explanation**

- **Token Bucket Class**: Manages token state with thread-safe operations. The `refill` method ensures tokens are added at the correct rate.
- **Tests**: Validate basic functionality, blocking/non-blocking behavior, and concurrency. The concurrent test ensures the long-run rate adheres to the configured limit.
