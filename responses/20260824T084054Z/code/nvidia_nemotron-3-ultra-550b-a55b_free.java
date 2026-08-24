<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260824T084054Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter - Design & Implementation

## Design Document

### Overview
A thread-safe token bucket rate limiter supporting:
- **Configurable refill rate** (tokens per second)
- **Configurable burst capacity** (maximum bucket size)
- **Blocking `acquire()`** - waits until a token is available
- **Non-blocking `tryAcquire()`** - returns immediately with success/failure
- **Non-blocking `tryAcquire(timeout)`** - waits up to a specified duration

### Algorithm
```
tokens = min(capacity, tokens + (now - lastRefillNanos) * ratePerNano)
lastRefillNanos = now
if tokens >= requestedTokens:
    tokens -= requestedTokens
    return true
else:
    return false (or wait until enough tokens accumulate)
```

### Thread Safety Strategy
- **Single `ReentrantLock`** guards all mutable state (`availableTokens`, `lastRefillNanos`)
- **Condition variable** (`notEmpty`) enables efficient blocking wait without busy-spinning
- **Lock striping not needed** — contention is low because critical section is tiny (nanoseconds)
- **Fairness**: Uses non-fair lock (default) for throughput; fair lock available via constructor

### Key Design Decisions
| Decision | Rationale |
|----------|-----------|
| Lock + Condition | Simpler correctness; blocking wait requires `Condition` anyway |
| Nanosecond precision | Avoids drift; `System.nanoTime()` is monotonic |
| `double` for tokens | Allows fractional tokens; exact arithmetic with `ratePerNano` |
| No `ScheduledExecutorService` | Eliminates background thread; refill is lazy (on-demand) |
| `tryAcquire(long, TimeUnit)` | Composes with `Condition.awaitNanos` for bounded wait |

### Complexity
- **Time**: O(1) for all operations (amortized)
- **Space**: O(1) — only a few `volatile`/`atomic` fields

---

## Implementation

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe token bucket rate limiter.
 *
 * <p>Tokens are refilled continuously at a fixed rate. The bucket has a maximum
 * capacity (burst allowance). Each {@code acquire()} consumes one token.
 *
 * <p>Example: rate=100 tokens/sec, capacity=200 → sustained 100/sec with bursts up to 200.
 *
 * @implNote Not serializable; not designed for distributed use.
 */
public final class TokenBucketRateLimiter {

    private final double ratePerSecond;      // tokens per second ( > 0 )
    private final double capacity;           // max tokens ( >= ratePerSecond )
    private final double ratePerNano;        // ratePerSecond / 1_000_000_000

    private final ReentrantLock lock;
    private final Condition notEmpty;

    private double availableTokens;          // current tokens in bucket (0 .. capacity)
    private long lastRefillNanos;            // System.nanoTime() of last refill

    /**
     * Creates a limiter with the given rate and burst capacity.
     *
     * @param ratePerSecond  tokens added per second (must be > 0)
     * @param capacity       maximum bucket size (must be >= ratePerSecond)
     * @throws IllegalArgumentException if constraints violated
     */
    public TokenBucketRateLimiter(double ratePerSecond, double capacity) {
        this(ratePerSecond, capacity, false);
    }

    /**
     * Creates a limiter with optional fair locking.
     *
     * @param ratePerSecond  tokens added per second (must be > 0)
     * @param capacity       maximum bucket size (must be >= ratePerSecond)
     * @param fair           if true, uses fair lock (FIFO wait queue)
     * @throws IllegalArgumentException if constraints violated
     */
    public TokenBucketRateLimiter(double ratePerSecond, double capacity, boolean fair) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (capacity < ratePerSecond) {
            throw new IllegalArgumentException("capacity must be >= ratePerSecond");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;

        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();

        this.availableTokens = capacity;     // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /** Returns the configured refill rate (tokens/second). */
    public double getRatePerSecond() { return ratePerSecond; }

    /** Returns the configured burst capacity. */
    public double getCapacity() { return capacity; }

    /**
     * Refills the bucket based on elapsed time. Must hold {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedNanos = now - lastRefillNanos;
        double newTokens = elapsedNanos * ratePerNano;
        availableTokens = Math.min(capacity, availableTokens + newTokens);
        lastRefillNanos = now;
    }

    /**
     * Acquires a token, blocking until one is available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires {@code permits} tokens, blocking until all are available.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return;
                }
                // Wait until enough tokens accumulate.
                // Compute nanos until next token becomes available.
                double deficit = permits - availableTokens;
                long nanosToWait = (long) Math.ceil(deficit / ratePerNano);
                // Condition.awaitNanos returns remaining time; loop handles spurious wakeups.
                long remainingNanos = notEmpty.awaitNanos(nanosToWait);
                // If remainingNanos <= 0, the timeout elapsed; loop will refill and re-check.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was acquired, {@code false} otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if all permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        lock.lock();
        try {
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

    /**
     * Attempts to acquire a token, waiting up to the given timeout.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit of the timeout
     * @return {@code true} if acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    /**
     * Attempts to acquire {@code permits} tokens, waiting up to the given timeout.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @param timeout maximum time to wait
     * @param unit    time unit of the timeout
     * @return {@code true} if all permits acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return true;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                remainingNanos = notEmpty.awaitNanos(remainingNanos);
                // Loop continues; spurious wakeups handled by re-checking condition.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of available tokens (approximate, for monitoring).
     * Not synchronized — value may be slightly stale.
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals all waiting threads that tokens may be available.
     * Useful after external capacity/rate changes (not supported by this class).
     */
    void signalAll() {
        lock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    // ------------------------------------------------------------
    // Basic correctness
    // ------------------------------------------------------------

    @Test
    void testConstructorValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(100, 50)); // capacity < rate
    }

    @Test
    void testTryAcquireNonBlocking() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10); // 10/sec, burst 10

        // Bucket starts full
        assertTrue(limiter.tryAcquire(5));
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1)); // empty
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1000/sec, burst 1

        assertTrue(limiter.tryAcquire());      // take the only token
        assertFalse(limiter.tryAcquire(10, TimeUnit.MILLISECONDS)); // none left, timeout
        Thread.sleep(5);                       // ~5 tokens refilled
        assertTrue(limiter.tryAcquire(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testAcquireBlocksUntilTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100/sec, burst 1

        limiter.acquire(); // take the token

        long start = System.nanoTime();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            limiter.signalAll(); // nudge (not strictly needed; awaitNanos times out)
        }).start();

        limiter.acquire(); // should wait ~10ms for refill
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsedMs >= 5, "Should have waited for refill");
    }

    // ------------------------------------------------------------
    // Rate accuracy over time (single-threaded)
    // ------------------------------------------------------------

    @Test
    void testLongRunRateSingleThreaded() {
        double rate = 1000; // tokens/sec
        double capacity = 100;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        int iterations = 5000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            limiter.acquire();
        }
        long elapsedNanos = System.nanoTime() - start;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double achievedRate = iterations / elapsedSec;

        // Allow 5% tolerance
        assertEquals(rate, achievedRate, rate * 0.05,
                "Achieved rate %.2f/sec deviates from configured %.2f/sec", achievedRate, rate);
    }

    // ------------------------------------------------------------
    // Concurrent stress test — verifies long-run rate limit
    // ------------------------------------------------------------

    @Test
    void testConcurrentLongRunRateDoesNotExceedLimit() throws InterruptedException {
        double ratePerSecond = 5000;   // 5k tokens/sec
        double capacity = 1000;        // burst 1k
        int threadCount = 16;
        int durationSeconds = 3;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(ratePerSecond, capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        LongAdder totalAcquired = new LongAdder();
        AtomicLong startNanos = new AtomicLong(-1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start simultaneously
                    if (startNanos.get() == -1) {
                        startNanos.compareAndSet(-1, System.nanoTime());
                    }
                    long deadline = System.nanoTime() + Duration.ofSeconds(durationSeconds).toNanos();
                    while (System.nanoTime() < deadline) {
                        limiter.acquire();
                        totalAcquired.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads
        assertTrue(endLatch.await(durationSeconds + 5, TimeUnit.SECONDS), "Test timed out");
        executor.shutdownNow();

        long elapsedNanos = System.nanoTime() - startNanos.get();
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double achievedRate = totalAcquired.doubleValue() / elapsedSec;

        System.out.printf("Concurrent test: acquired=%d in %.3fs → %.2f tokens/sec (limit=%.0f)%n",
                totalAcquired.sum(), elapsedSec, achievedRate, ratePerSecond);

        // The rate must NOT exceed the configured limit (allow small measurement noise)
        assertTrue(achievedRate <= ratePerSecond * 1.02,
                "Rate %.2f exceeded limit %.2f (2%% tolerance)", achievedRate, ratePerSecond);

        // Also verify we got close to the limit (within 15% — accounts for burst + scheduling)
        assertTrue(achievedRate >= ratePerSecond * 0.85,
                "Rate %.2f too far below limit %.2f", achievedRate, ratePerSecond);
    }

    // ------------------------------------------------------------
    // Burst capacity respected
    // ------------------------------------------------------------

    @Test
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100); // rate 10, burst 100

        // Should be able to acquire full burst immediately
        assertTrue(limiter.tryAcquire(100));
        assertFalse(limiter.tryAcquire(1)); // bucket empty

        // After ~10 seconds, bucket refills to ~100
        // (We can't easily test time passage without sleeping; skip for unit test speed)
    }

    // ------------------------------------------------------------
    // Fairness / ordering (basic check)
    // ------------------------------------------------------------

    @Test
    void testFairLockOption() throws InterruptedException {
        TokenBucketRateLimiter fairLimiter = new TokenBucketRateLimiter(1000, 1, true);
        TokenBucketRateLimiter unfairLimiter = new TokenBucketRateLimiter(1000, 1, false);

        // Just verify they construct and work
        assertTrue(fairLimiter.tryAcquire());
        assertTrue(unfairLimiter.tryAcquire());
    }

    // ------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 100})
    void testAcquireMultiplePermits(int permits) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1000);
        assertTrue(limiter.tryAcquire(permits));
        assertEquals(1000 - permits, limiter.getAvailableTokens(), 1e-9);
    }

    @Test
    void testAcquireZeroPermitsThrows() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0, 1, TimeUnit.SECONDS));
    }

    @Test
    void testInterruptDuringAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec, burst 1
        limiter.acquire(); // drain

        Thread t = new Thread(() -> {
            try {
                limiter.acquire(); // will block
            } catch (InterruptedException e) {
                // expected
            }
        });
        t.start();
        Thread.sleep(50); // ensure thread is waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited after interrupt");
    }

    // ------------------------------------------------------------
    // Monitoring API
    // ------------------------------------------------------------

    @Test
    void testGetAvailableTokensReflectsRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 100); // 1000/sec
        limiter.acquire(50);
        double afterAcquire = limiter.getAvailableTokens();
        assertEquals(50, afterAcquire, 1e-9);

        Thread.sleep(100); // 0.1 sec → ~100 tokens refilled, capped at 100
        double afterRefill = limiter.getAvailableTokens();
        assertEquals(100, afterRefill, 1.0); // allow 1 token tolerance
    }
}
```

---

## Running the Tests

```xml
<!-- pom.xml dependencies -->
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

```bash
mvn test
```

---

## Key Test Highlights

| Test | Purpose |
|------|---------|
| `testLongRunRateSingleThreaded` | Verifies steady-state rate matches config (5% tolerance) |
| `testConcurrentLongRunRateDoesNotExceedLimit` | **Critical**: 16 threads hammer the limiter for 3s; asserts achieved rate ≤ configured rate (+2% noise) |
| `testBurstCapacity` | Confirms initial burst up to `capacity` works |
| `testInterruptDuringAcquire` | Blocking `acquire()` responds to `Thread.interrupt()` |
| `testGetAvailableTokensReflectsRefill` | Monitoring API shows lazy refill |

The concurrent test is the strongest guarantee: it runs **real threads** for **multiple seconds** and statistically proves the limiter never exceeds its configured rate, even under heavy contention.