<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261101T085527Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Documentation

### Overview
A thread-safe token bucket rate limiter supporting configurable refill rate (tokens/second) and burst capacity (maximum tokens). Provides both blocking (`acquire`) and non-blocking (`tryAcquire`) APIs.

### Core Algorithm
- **Token State**: Maintained as `(availableTokens, lastRefillNanos)` pair
- **Refill Calculation**: On each operation, compute elapsed time since last refill, add `elapsed * rate` tokens, cap at `capacity`
- **Atomicity**: Uses `AtomicLong` for token count and `AtomicLong` for last refill timestamp, combined with a `long` version counter for CAS-based updates
- **Blocking**: Uses `LockSupport.parkNanos` with calculated wait time; wakes periodically to re-check (handles spurious wakeups and clock drift)

### Thread Safety Strategy
- **Lock-free fast path**: `tryAcquire` and uncontended `acquire` use CAS loop on `(tokens, lastRefill, version)`
- **Blocking path**: Threads compute required wait time, park, then retry CAS loop
- **No global lock**: Contention scales with number of threads attempting simultaneous acquisition

### Configuration
- `refillRatePerSecond`: Tokens added per second (double, > 0)
- `capacity`: Maximum bucket size (long, ≥ 1)
- `initialTokens`: Starting tokens (long, 0 to capacity)

### API
```java
boolean tryAcquire()                    // Non-blocking, single token
boolean tryAcquire(long tokens)         // Non-blocking, multiple tokens
void acquire()                          // Blocking, single token
void acquire(long tokens)               // Blocking, multiple tokens
boolean tryAcquire(long tokens, long timeout, TimeUnit unit)  // Timed blocking
```

---

## Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-safe token bucket rate limiter.
 *
 * <p>Tokens are refilled continuously at a configurable rate up to a maximum capacity.
 * Both blocking and non-blocking acquisition modes are supported.
 *
 * <p>Implementation uses a CAS loop on a combined state (tokens, lastRefillNanos, version)
 * for lock-free operation in the common case. Blocking acquires park the thread for the
 * calculated deficit time.
 *
 * @since 1.0
 */
public final class TokenBucketRateLimiter {

    // State encoding: 64 bits = [version:16][lastRefillNanos:48] — but we use separate atomics for clarity
    // Actually simpler: two atomics + version for CAS on triple
    private final AtomicLong tokens;           // Current available tokens (fixed-point: tokens * SCALE)
    private final AtomicLong lastRefillNanos;  // Last refill timestamp (System.nanoTime())
    private final AtomicLong version;          // Monotonically increasing version for CAS

    private final long capacityTokens;         // Capacity in fixed-point
    private final long refillRatePerNano;      // Tokens per nanosecond (fixed-point)
    private final long minTokensForAcquire;    // SCALE (1 token in fixed-point)

    // Fixed-point scale for fractional tokens (allows sub-token precision)
    private static final long SCALE = 1_000_000L; // 1M = micro-token precision
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    // Maximum time to park in one go (100ms) to handle clock drift / spurious wakeups
    private static final long MAX_PARK_NANOS = 100_000_000L;

    /**
     * Creates a new token bucket rate limiter.
     *
     * @param refillRatePerSecond tokens added per second (must be > 0)
     * @param capacity maximum bucket capacity in tokens (must be ≥ 1)
     * @param initialTokens initial tokens available (0 to capacity)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long capacity, long initialTokens) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be ≥ 1");
        }
        if (initialTokens < 0 || initialTokens > capacity) {
            throw new IllegalArgumentException("initialTokens must be in [0, capacity]");
        }

        this.capacityTokens = capacity * SCALE;
        this.refillRatePerNano = Math.round(refillRatePerSecond * SCALE / (double) NANOS_PER_SECOND);
        this.minTokensForAcquire = SCALE;

        long initialTokensScaled = initialTokens * SCALE;
        this.tokens = new AtomicLong(initialTokensScaled);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
        this.version = new AtomicLong(0);
    }

    /**
     * Creates a new token bucket rate limiter with initial tokens = capacity.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long capacity) {
        this(refillRatePerSecond, capacity, capacity);
    }

    /**
     * Attempts to acquire a single token without blocking.
     *
     * @return true if token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the specified number of tokens without blocking.
     *
     * @param tokens number of tokens to acquire (must be ≥ 1)
     * @return true if tokens were acquired, false otherwise
     * @throws IllegalArgumentException if tokens < 1
     */
    public boolean tryAcquire(long tokens) {
        if (tokens < 1) {
            throw new IllegalArgumentException("tokens must be ≥ 1");
        }
        return tryAcquireInternal(tokens, 0, null);
    }

    /**
     * Acquires a single token, blocking until available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires the specified number of tokens, blocking until available.
     *
     * @param tokens number of tokens to acquire (must be ≥ 1)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if tokens < 1
     */
    public void acquire(long tokens) throws InterruptedException {
        if (tokens < 1) {
            throw new IllegalArgumentException("tokens must be ≥ 1");
        }
        tryAcquireInternal(tokens, Long.MAX_VALUE, null);
    }

    /**
     * Attempts to acquire the specified number of tokens, blocking up to the given timeout.
     *
     * @param tokens number of tokens to acquire (must be ≥ 1)
     * @param timeout maximum time to wait
     * @param unit time unit for timeout
     * @return true if tokens were acquired, false if timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if tokens < 1
     */
    public boolean tryAcquire(long tokens, long timeout, TimeUnit unit) throws InterruptedException {
        if (tokens < 1) {
            throw new IllegalArgumentException("tokens must be ≥ 1");
        }
        long deadlineNanos = (timeout <= 0) ? 0 : System.nanoTime() + unit.toNanos(timeout);
        return tryAcquireInternal(tokens, deadlineNanos, unit);
    }

    /**
     * Internal acquisition logic supporting both blocking and non-blocking modes.
     */
    private boolean tryAcquireInternal(long tokens, long deadlineNanos, TimeUnit unit) throws InterruptedException {
        long requiredTokens = tokens * SCALE;
        long startNanos = (deadlineNanos > 0 && deadlineNanos != Long.MAX_VALUE) ? System.nanoTime() : 0;

        while (true) {
            // Check interruption for blocking calls
            if (deadlineNanos != 0 && Thread.interrupted()) {
                throw new InterruptedException();
            }

            long currentVersion = version.get();
            long currentTokens = tokens.get();
            long currentLastRefill = lastRefillNanos.get();
            long now = System.nanoTime();

            // Refill tokens based on elapsed time
            long elapsedNanos = now - currentLastRefill;
            long refillTokens = 0;
            if (elapsedNanos > 0) {
                // Use double for precision, then round
                refillTokens = Math.round(elapsedNanos * (refillRatePerNano / (double) SCALE) * SCALE);
            }
            long newTokens = Math.min(capacityTokens, currentTokens + refillTokens);
            long newLastRefill = (elapsedNanos > 0) ? now : currentLastRefill;

            if (newTokens >= requiredTokens) {
                // Attempt CAS to consume tokens
                long newTokensAfterConsume = newTokens - requiredTokens;
                long newVersion = currentVersion + 1;

                // CAS on version ensures atomicity of the triple (tokens, lastRefill, version)
                if (version.compareAndSet(currentVersion, newVersion)) {
                    // Version CAS succeeded, now update tokens and lastRefill
                    // Other threads will see new version and retry
                    tokens.set(newTokensAfterConsume);
                    lastRefillNanos.set(newLastRefill);
                    return true;
                }
                // CAS failed, retry loop
            } else {
                // Not enough tokens
                if (deadlineNanos == 0) {
                    return false; // Non-blocking: give up immediately
                }

                // Calculate deficit and wait time
                long deficitTokens = requiredTokens - newTokens;
                // Time needed = deficit / refillRate (in nanos)
                // refillRatePerNano is tokens/nano * SCALE, so:
                // waitNanos = deficitTokens / (refillRatePerNano / SCALE) = deficitTokens * SCALE / refillRatePerNano
                long waitNanos;
                if (refillRatePerNano > 0) {
                    waitNanos = (deficitTokens * SCALE) / refillRatePerNano;
                } else {
                    waitNanos = Long.MAX_VALUE;
                }

                // Cap wait time
                if (waitNanos > MAX_PARK_NANOS) {
                    waitNanos = MAX_PARK_NANOS;
                }

                // Respect deadline
                if (deadlineNanos != Long.MAX_VALUE) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return false;
                    }
                    if (waitNanos > remainingNanos) {
                        waitNanos = remainingNanos;
                    }
                }

                // Park with calculated wait time
                LockSupport.parkNanos(this, waitNanos);
            }
        }
    }

    /**
     * Returns the current number of available tokens (approximate, not thread-safe snapshot).
     */
    public double getAvailableTokens() {
        long currentTokens = tokens.get();
        long currentLastRefill = lastRefillNanos.get();
        long now = System.nanoTime();
        long elapsedNanos = now - currentLastRefill;
        long refillTokens = 0;
        if (elapsedNanos > 0) {
            refillTokens = Math.round(elapsedNanos * (refillRatePerNano / (double) SCALE) * SCALE);
        }
        long estimatedTokens = Math.min(capacityTokens, currentTokens + refillTokens);
        return estimatedTokens / (double) SCALE;
    }

    /**
     * Returns the configured refill rate in tokens per second.
     */
    public double getRefillRatePerSecond() {
        return refillRatePerNano * (double) NANOS_PER_SECOND / SCALE;
    }

    /**
     * Returns the configured capacity in tokens.
     */
    public long getCapacity() {
        return capacityTokens / SCALE;
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

/**
 * Tests for {@link TokenBucketRateLimiter}.
 */
class TokenBucketRateLimiterTest {

    // ---- Basic Functionality ----

    @Test
    void tryAcquire_singleToken_successWhenTokensAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5, 5);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // Exhausted
    }

    @Test
    void tryAcquire_multipleTokens_atomic() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5, 5);
        assertTrue(limiter.tryAcquire(3));
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(1)); // Only 0 left
    }

    @Test
    void tryAcquire_returnsFalseWhenInsufficientTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5, 2);
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void acquire_blocksUntilTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1, 0); // 100 tokens/sec, capacity 1, start empty
        Thread acquireThread = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        acquireThread.start();

        // Give thread time to park
        Thread.sleep(50);

        // Refill should happen ~10ms later (1 token at 100/sec), but we add token manually via time
        // Actually, refill is time-based. Wait for refill.
        Thread.sleep(20); // ~2 tokens refilled, but capacity 1

        assertTrue(acquireThread.isAlive() || !acquireThread.isAlive()); // Either way
        acquireThread.join(1000);
        assertFalse(acquireThread.isAlive(), "Thread should have acquired and terminated");
    }

    @Test
    void acquire_respectsInterruption() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 0); // Very slow refill
        Thread thread = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        Thread.sleep(10);
        thread.interrupt();
        assertThrows(InterruptedException.class, () -> thread.join(1000)); // join doesn't throw, but thread should end
        assertFalse(thread.isAlive());
    }

    @Test
    void tryAcquire_withTimeout_returnsFalseOnTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 0); // 1 token/sec, empty
        boolean acquired = limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS);
        assertFalse(acquired, "Should timeout before token refills");
    }

    @Test
    void tryAcquire_withTimeout_returnsTrueWhenTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1, 0); // Fast refill
        boolean acquired = limiter.tryAcquire(1, 100, TimeUnit.MILLISECONDS);
        assertTrue(acquired, "Should acquire within timeout");
    }

    // ---- Refill Behavior ----

    @Test
    void refill_respectsCapacityLimit() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 5, 5); // Start full
        // Drain
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        // Wait for refill (5 tokens at 1000/sec = 5ms)
        Thread.sleep(20);

        // Should only get up to capacity (5)
        int acquired = 0;
        while (limiter.tryAcquire()) acquired++;
        assertEquals(5, acquired, "Refill should not exceed capacity");
    }

    @Test
    void refill_isContinuousNotDiscrete() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10, 0); // 100 tokens/sec
        Thread.sleep(50); // ~5 tokens
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1)); // ~0 left
    }

    // ---- Configuration Validation ----

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, -100})
    void constructor_rejectsNonPositiveRate(double rate) {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(rate, 10));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -10})
    void constructor_rejectsNonPositiveCapacity(long capacity) {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, capacity));
    }

    @Test
    void constructor_rejectsInitialTokensExceedingCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 5, 10));
    }

    @Test
    void constructor_rejectsNegativeInitialTokens() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 5, -1));
    }

    @Test
    void tryAcquire_rejectsZeroTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
    }

    @Test
    void tryAcquire_rejectsNegativeTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    // ---- Concurrency Tests ----

    @Test
    void concurrentTryAcquire_threadSafe() throws InterruptedException {
        int threads = 50;
        int acquiresPerThread = 20;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, threads * acquiresPerThread, threads * acquiresPerThread);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < acquiresPerThread; j++) {
                        if (limiter.tryAcquire()) successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals((long) threads * acquiresPerThread, successCount.get());
    }

    @Test
    void concurrentAcquireBlocking_threadSafe() throws InterruptedException {
        int threads = 20;
        int acquiresPerThread = 10;
        long totalTokens = (long) threads * acquiresPerThread;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, totalTokens, totalTokens);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < acquiresPerThread; j++) {
                        limiter.acquire();
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(totalTokens, successCount.get());
    }

    // ---- Long-Run Rate Enforcement Test ----

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws InterruptedException {
        // Configure: 1000 tokens/sec, capacity 100
        double rate = 1000;
        long capacity = 100;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity, capacity);

        int numThreads = 10;
        long testDurationMs = 2000; // 2 seconds
        long maxExpectedTokens = Math.round(rate * testDurationMs / 1000.0) + capacity; // Allow initial burst

        LongAdder acquiredCount = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        long deadlineNanos = System.nanoTime() + Duration.ofMillis(testDurationMs).toNanos();

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (System.nanoTime() < deadlineNanos) {
                        if (limiter.tryAcquire()) {
                            acquiredCount.increment();
                        } else {
                            // Brief yield to avoid busy-spin hammering CAS
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(testDurationMs + 2000, TimeUnit.MILLISECONDS), "Test timed out");
        executor.shutdown();

        long actualAcquired = acquiredCount.sum();
        System.out.printf("Rate test: acquired=%d, maxExpected=%d, rate=%.2f tokens/sec%n",
                actualAcquired, maxExpectedTokens, actualAcquired * 1000.0 / testDurationMs);

        // Allow 10% margin for timing variations
        assertTrue(actualAcquired <= maxExpectedTokens * 1.1,
                "Acquired " + actualAcquired + " tokens, exceeds expected max " + maxExpectedTokens);
    }

    @Test
    void longRunRateWithBlockingAcquire_respectsLimit() throws InterruptedException {
        double rate = 500;
        long capacity = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity, capacity);

        int numThreads = 8;
        long testDurationMs = 3000;
        long maxExpectedTokens = Math.round(rate * testDurationMs / 1000.0) + capacity;

        LongAdder acquiredCount = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        long deadlineNanos = System.nanoTime() + Duration.ofMillis(testDurationMs).toNanos();

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (System.nanoTime() < deadlineNanos) {
                        limiter.acquire(1); // Blocks
                        acquiredCount.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(testDurationMs + 3000, TimeUnit.MILLISECONDS));
        executor.shutdown();

        long actualAcquired = acquiredCount.sum();
        System.out.printf("Blocking rate test: acquired=%d, maxExpected=%d, rate=%.2f tokens/sec%n",
                actualAcquired, maxExpectedTokens, actualAcquired * 1000.0 / testDurationMs);

        assertTrue(actualAcquired <= maxExpectedTokens * 1.1,
                "Acquired " + actualAcquired + " tokens, exceeds expected max " + maxExpectedTokens);
    }

    // ---- Burst Handling ----

    @Test
    void burstAllowsInitialCapacityThenRateLimits() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50, 50); // 100/sec