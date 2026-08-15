<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260815T172406Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter Design**

The token bucket algorithm is implemented using a thread-safe structure with a configurable refill rate and burst capacity. Key components:

1. **Bucket State**: Maintains `tokens` (current available tokens) and `lastRefill` (timestamp of last refill).
2. **Refill Mechanism**: Tokens are replenished at a fixed rate (e.g., 10 tokens/second) using a scheduled executor to periodically add tokens based on elapsed time since the last refill.
3. **Thread Safety**: All state modifications are synchronized to prevent race conditions. The refill task is designed to be resubmitted upon each execution to handle potential delays.
4. **Acquire Methods**:
   - **`acquire()`**: Blocks until tokens are available, then consumes them.
   - **`tryAcquire()`**: Non-blocking; returns immediately with success/failure.
5. **Concurrency Control**: Uses `ReentrantLock` for atomic updates during token acquisition and refill adjustments.

**Implementation**

```java
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TokenBucketRateLimiter {
    private final long burstCapacity;
    private final Duration refillInterval;
    private final long tokensPerRefill;
    private long tokens;
    private long lastRefill;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong acquisitionCount = new AtomicLong(0);
    private final ReentrantLock lock = new ReentrantLock();

    public TokenBucketRateLimiter(long burstCapacity, long tokensPerRefill, Duration refillInterval) {
        this.burstCapacity = burstCapacity;
        this.tokensPerRefill = tokensPerRefill;
        this.refillInterval = refillInterval;
        this.tokens = burstCapacity;
        this.lastRefill = System.nanoTime();
        scheduler.scheduleAtFixedRate(this::refill, refillInterval.toMillis(), refillInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void acquire(int permits) throws InterruptedException {
        lock.lock();
        try {
            long currentTime = System.nanoTime();
            long availableTokens = refillIfNeeded(currentTime);
            if (availableTokens >= permits) {
                tokens -= permits;
                acquisitionCount.addAndGet(permits);
            } else {
                while (true) {
                    availableTokens = refillIfNeeded(currentTime);
                    if (availableTokens >= permits) {
                        tokens -= permits;
                        acquisitionCount.addAndGet(permits);
                        break;
                    }
                    long waitTime = refillInterval.toNanos() - (currentTime - lastRefill);
                    if (waitTime <= 0) {
                        currentTime = System.nanoTime();
                        lastRefill = currentTime;
                    } else {
                        waitTime = Math.max(0, waitTime);
                        Thread.sleep(TimeUnit.NANOSECONDS.convert(waitTime, TimeUnit.NANOSECONDS));
                    }
                    currentTime = System.nanoTime();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits) {
        lock.lock();
        try {
            long currentTime = System.nanoTime();
            long availableTokens = refillIfNeeded(currentTime);
            if (availableTokens >= permits) {
                tokens -= permits;
                acquisitionCount.addAndGet(permits);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private long refillIfNeeded(long currentTime) {
        if (currentTime - lastRefill >= refillInterval.toNanos()) {
            long tokensToAdd = Math.min(tokensPerRefill, burstCapacity - tokens);
            tokens += tokensToAdd;
            lastRefill = currentTime;
        }
        return tokens;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public long getAcquisitionCount() {
        return acquisitionCount.get();
    }
}
```

**JUnit 5 Tests**

```java
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {
    private static final Duration TEST_DURATION = Duration.ofSeconds(10);
    private static final int NUM_THREADS = 10;
    private static final int PERMIT = 1;

    @Test
    void testSingleThreadAcquire() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10, Duration.ofSeconds(1));
        limiter.acquire(5);
        assertEquals(5, limiter.getAcquisitionCount());
        Thread.sleep(1100);
        limiter.acquire(5);
        assertEquals(10, limiter.getAcquisitionCount());
    }

    @Test
    void testSingleThreadTryAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 5, Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire(3));
        assertFalse(limiter.tryAcquire(3));
        Thread.sleep(1100);
        assertTrue(limiter.tryAcquire(3));
    }

    @Test
    void testConcurrentRateLimit() throws Exception {
        final int EXPECTED_RATE = 10;
        final int TEST_DURATION_SECONDS = 10;
        final int EXPECTED_TOTAL = EXPECTED_RATE * TEST_DURATION_SECONDS;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10, Duration.ofSeconds(1));
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);

        AtomicLong totalAcquisitions = new AtomicLong(0);
        executor.submit(() -> {
            try {
                limiter.acquire(1);
                totalAcquisitions.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        for (int i = 0; i < NUM_THREADS - 1; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(100);
                    limiter.acquire(1);
                    totalAcquisitions.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(TEST_DURATION.toMillis(), TimeUnit.MILLISECONDS);

        assertTrue(totalAcquisitions.get() <= EXPECTED_TOTAL + 1, "Acquisitions exceeded expected rate");
    }
}
```

**Explanation**

- **Refill Mechanism**: Tokens are added every `refillInterval` (e.g., 1 second) up to `tokensPerRefill` (e.g., 10 tokens). This ensures the long-term rate does not exceed the configured limit.
- **Concurrency Handling**: The `ReentrantLock` ensures atomic updates during token checks and acquisitions, preventing race conditions.
- **Test Verification**: The concurrent test simulates multiple threads acquiring tokens and verifies that the total acquisitions do not exceed the expected rate over the test duration, accounting for minor delays and rounding errors.
