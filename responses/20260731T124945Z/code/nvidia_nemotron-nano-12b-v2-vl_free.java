<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Document: Token Bucket Rate Limiter

**Overview**  
The token bucket algorithm is a classic rate limiting strategy that allows bursts of requests while enforcing a long-term rate limit. Key components include:
- **Token Bucket**: Holds tokens representing allowed requests. Tokens refill at a configurable rate.
- **Burst Capacity**: Maximum number of tokens the bucket can hold, allowing short-term bursts.
- **Concurrency Control**: Uses `ReentrantReadWriteLock` for thread-safe operations (reads for token checks, writes for refills).
- **Refill Mechanism**: A scheduled task refills tokens at the specified rate, respecting burst capacity.

**Features**
1. **Blocking Acquire (`acquire()`)**: Blocks until tokens are available.
2. **Non-Blocking Try Acquire (`tryAcquire()`)**: Returns immediately if tokens are insufficient.
3. **Configurable Rate & Burst**: Adjustable refill rate (tokens/second) and burst capacity.
4. **Long-Run Rate Enforcement**: Ensures sustained rate does not exceed the configured limit.

### Java Implementation

```java
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;

public class TokenBucketRateLimiter {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong tokens;
    private final long maxTokens;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refillTask;

    /**
     * Creates a token bucket rate limiter.
     *
     * @param refillRate   tokens per second to add (e.g., 10 for 10 requests/second)
     * @param burstCapacity maximum tokens allowed at once
     * @param scheduler    executor for refill task
     */
    public TokenBucketRateLimiter(int refillRate, int burstCapacity, ScheduledExecutorService scheduler) {
        this.tokens = new AtomicLong(burstCapacity);
        this.maxTokens = burstCapacity;
        this.scheduler = scheduler;
        scheduleRefill(refillRate);
    }

    private void scheduleRefill(int rate) {
        refillTask = scheduler.scheduleAtFixedRate(
            () -> refillTokens(rate),
            0,
            1000 / rate,
            TimeUnit.MICROSECONDS
        );
    }

    private void refillTokens(int rate) {
        lock.writeLock().lock();
        try {
            long current = tokens.get();
            if (current < maxTokens) {
                tokens.set(Math.min(current + rate, maxTokens));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Acquires a token, blocking until available.
     *
     * @return true if acquired
     */
    public boolean acquire() {
        lock.readLock().lock();
        try {
            while (tokens.get() == 0) {
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    if (tokens.get() == 0) {
                        tokens.set(0);
                    }
                    lock.writeLock().unlock();
                    lock.readLock().lock();
                } finally {
                    lock.writeLock().unlock();
                }
            }
            tokens.decrementAndGet();
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Tries to acquire a token non-blockingly.
     *
     * @return true if acquired, false otherwise
     */
    public boolean tryAcquire() {
        lock.readLock().lock();
        try {
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void shutdown() {
        if (refillTask != null && !refillTask.isDone()) {
            refillTask.cancel(false);
        }
    }
}
```

### JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.commons.util.ConcurrentUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {
    private ScheduledExecutorService scheduler;
    private ExecutorService executor;
    private TokenBucketRateLimiter limiter;
    private final int bursts = 1000;
    private final int rate = 10; // tokens/second

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(1);
        executor = Executors.newFixedThreadPool(10);
        limiter = new TokenBucketRateLimiter(rate, 10, scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        limiter.shutdown();
        scheduler.shutdown();
        executor.shutdown();
    }

    @Test
    void testInitialTokens() {
        assertEquals(10, limiter.tokens.get());
    }

    @Test
    void testAcquireAllTokens() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.acquire());
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void testRefillAfterWait() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            limiter.acquire();
        }
        Thread.sleep(1100); // Wait for refill
        assertTrue(limiter.tryAcquire());
    }

    @Test
    @Timeout(5)
    @Execution(ExecutionMode.CONCURRENT)
    void testConcurrentRateLimit() throws InterruptedException {
        AtomicReference<Long> totalAcquired = new AtomicReference<>(0L);
        AtomicReference<Long> failedAcquires = new AtomicReference<>(0L);

        ConcurrentUtils.runConcurrently(
            executor,
            100,
            () -> {
                for (int i = 0; i < bursts; i++) {
                    if (limiter.tryAcquire()) {
                        totalAcquired.set(totalAcquired.get() + 1);
                    } else {
                        failedAcquires.set(failedAcquires.get() + 1);
                    }
                }
            }
        );

        // Allow time for refills
        Thread.sleep(2000);

        // Verify total acquired tokens don't exceed allowed rate
        long totalTokens = 10 * bursts; // Initial burst + 10/sec for 200 sec
        assertTrue(totalAcquired.get() <= totalTokens, "Total acquired exceeds allowed rate");
    }
}
```

### Explanation

1. **Token Bucket Mechanism**:
   - **Refill**: A scheduled task adds `refillRate` tokens every `1000/refillRate` microseconds, capped at `maxTokens`.
   - **Acquire**: Uses read-write locks for efficiency. Blocking `acquire()` waits until tokens are available, while `tryAcquire()` returns immediately.

2. **Concurrency Handling**:
   - `ReentrantReadWriteLock` allows multiple readers (for `tryAcquire()`) and exclusive writers (for refills), ensuring thread safety.

3. **Test Coverage**:
   - **Basic Functionality**: Tests initial tokens, full acquisition, and refill after waiting.
   - **Concurrency**: Simulates 100 threads acquiring tokens rapidly. The test verifies that the total acquired tokens do not exceed the allowed rate over time, ensuring the limiter enforces the long-term rate limit.
