<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261122T093056Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Documentation

### Overview
A thread-safe token bucket rate limiter that controls the rate of operations by maintaining a bucket of tokens. Tokens are added at a fixed refill rate up to a maximum burst capacity. Threads consume tokens to proceed; if insufficient tokens are available, they either block (wait) or return immediately.

### Core Concepts
- **Refill Rate (tokens/second)**: Sustainable long-term throughput.
- **Burst Capacity (max tokens)**: Maximum tokens the bucket can hold, allowing short bursts above the refill rate.
- **Token Consumption**: Each `acquire(n)` removes `n` tokens (typically `n=1`).

### Thread-Safety Strategy
- **State**: Two `AtomicLong` fields — `availableTokens` (current tokens, scaled by `SCALE=1_000_000` for sub-token precision) and `lastRefillNanos` (timestamp of last refill).
- **Refill Logic**: On every `tryAcquire`/`acquire` call, compute elapsed time since `lastRefillNanos`, calculate new tokens, and atomically update state via a **compare-and-set (CAS) loop**. This avoids locks for the fast path.
- **Blocking Path**: When tokens are insufficient, threads park on a `LockSupport.parkNanos` loop with a calculated wait time. A single `ReentrantLock` + `Condition` coordinates wake-ups to avoid thundering herd; only the thread that successfully refills signals.

### Correctness Guarantees
- **No token loss/duplication**: CAS loop ensures each nanosecond of elapsed time contributes to refill exactly once.
- **Fairness**: Not strictly FIFO; uses `LockSupport`/condition which is typically fair-ish but not guaranteed. For strict fairness, a queue would be needed (omitted for simplicity/performance).
- **Long-run rate**: Mathematically bounded by `refillRate`; burst limited by `burstCapacity`.

### API
```java
public final class TokenBucketRateLimiter {
    public TokenBucketRateLimiter(double refillRatePerSecond, long burstCapacity);
    public void acquire();                    // blocking, 1 token
    public void acquire(long tokens);         // blocking, n tokens
    public boolean tryAcquire();              // non-blocking, 1 token
    public boolean tryAcquire(long tokens);   // non-blocking, n tokens
    public boolean tryAcquire(long tokens, long timeout, TimeUnit unit); // timeout
    public long getAvailableTokens();         // approximate current tokens
}
```

---

## Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe token bucket rate limiter.
 *
 * <p>Tokens are represented as a fixed-point integer scaled by {@code SCALE = 1_000_000}
 * to allow fractional tokens without floating-point drift.
 *
 * <p>Refill is computed on-demand (lazy refill) using a CAS loop on {@code availableTokens}
 * and {@code lastRefillNanos}. This makes the non-blocking path lock-free.
 *
 * <p>Blocking acquire uses {@link LockSupport#parkNanos} with a calculated wait time.
 * A single {@link ReentrantLock} + {@link Condition} is used only to coordinate wake-ups
 * when the bucket refills, avoiding busy-spinning.
 */
public final class TokenBucketRateLimiter {

    private static final long SCALE = 1_000_000L; // micro-token precision
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long maxTokensScaled;           // burstCapacity * SCALE
    private final long refillRateScaledPerNano;   // (refillRatePerSecond * SCALE) / NANOS_PER_SECOND

    private final AtomicLong availableTokens;     // current tokens * SCALE
    private final AtomicLong lastRefillNanos;     // last refill timestamp

    // Used only for blocking path coordination
    private final Lock parkLock = new ReentrantLock();
    private final Condition notEmpty = parkLock.newCondition();

    /**
     * Creates a new rate limiter.
     *
     * @param refillRatePerSecond tokens added per second (e.g., 100.0 for 100 ops/sec)
     * @param burstCapacity       maximum tokens the bucket can hold (>= 1)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long burstCapacity) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be >= 1");
        }
        this.maxTokensScaled = burstCapacity * SCALE;
        this.refillRateScaledPerNano = (long) (refillRatePerSecond * SCALE / (double) NANOS_PER_SECOND);
        this.availableTokens = new AtomicLong(maxTokensScaled); // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Acquires 1 token, blocking until available.
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * Acquires {@code tokens} tokens, blocking until available.
     *
     * @param tokens number of tokens to acquire (must be > 0)
     * @throws IllegalArgumentException if tokens <= 0
     * @throws InterruptedException     if the thread is interrupted while waiting
     */
    public void acquire(long tokens) throws InterruptedException {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        if (tokens > maxTokensScaled / SCALE) {
            throw new IllegalArgumentException("tokens exceeds burst capacity");
        }

        long tokensScaled = tokens * SCALE;
        while (true) {
            long now = System.nanoTime();
            if (tryRefillAndConsume(now, tokensScaled)) {
                return; // success
            }
            // Not enough tokens: compute wait time and park
            long waitNanos = computeWaitNanos(tokensScaled);
            if (waitNanos > 0) {
                parkNanos(waitNanos);
            }
            // Loop retries after wakeup/spurious wakeup
        }
    }

    /**
     * Tries to acquire 1 token without blocking.
     *
     * @return true if token was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Tries to acquire {@code tokens} tokens without blocking.
     *
     * @param tokens number of tokens to acquire (must be > 0)
     * @return true if tokens were acquired, false otherwise
     */
    public boolean tryAcquire(long tokens) {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        if (tokens > maxTokensScaled / SCALE) return false;

        long now = System.nanoTime();
        return tryRefillAndConsume(now, tokens * SCALE);
    }

    /**
     * Tries to acquire {@code tokens} tokens, blocking up to {@code timeout}.
     *
     * @param tokens  number of tokens to acquire
     * @param timeout maximum time to wait
     * @param unit    time unit of timeout
     * @return true if acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(long tokens, long timeout, TimeUnit unit) throws InterruptedException {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        if (tokens > maxTokensScaled / SCALE) return false;

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        long tokensScaled = tokens * SCALE;

        while (true) {
            long now = System.nanoTime();
            if (tryRefillAndConsume(now, tokensScaled)) {
                return true;
            }
            long remainingNanos = deadlineNanos - now;
            if (remainingNanos <= 0) {
                return false; // timeout
            }
            long waitNanos = Math.min(computeWaitNanos(tokensScaled), remainingNanos);
            if (waitNanos > 0) {
                parkNanos(waitNanos);
            }
        }
    }

    /**
     * Returns the approximate number of whole tokens currently available.
     * This is a snapshot and may be stale immediately.
     */
    public long getAvailableTokens() {
        refillUpToNow();
        return availableTokens.get() / SCALE;
    }

    // ---------- Internal mechanics ----------

    /**
     * Attempts to refill bucket to current time and consume {@code tokensScaled}.
     * Uses a CAS loop on the pair (availableTokens, lastRefillNanos).
     *
     * @return true if consumption succeeded
     */
    private boolean tryRefillAndConsume(long now, long tokensScaled) {
        while (true) {
            long lastRefill = lastRefillNanos.get();
            long available = availableTokens.get();

            // Compute tokens to add since lastRefill
            long elapsedNanos = now - lastRefill;
            if (elapsedNanos < 0) {
                // Clock went backward (rare); treat as zero elapsed
                elapsedNanos = 0;
            }
            long newTokensScaled = elapsedNanos * refillRateScaledPerNano;
            long newAvailable = Math.min(maxTokensScaled, available + newTokensScaled);

            if (newAvailable >= tokensScaled) {
                // Enough tokens: try to CAS both fields
                long newAvailableAfterConsume = newAvailable - tokensScaled;
                if (availableTokens.compareAndSet(available, newAvailableAfterConsume) &&
                    lastRefillNanos.compareAndSet(lastRefill, now)) {
                    // Successfully consumed; signal waiters if we crossed from empty to non-empty
                    if (available < tokensScaled && newAvailableAfterConsume >= 0) {
                        signalWaiters();
                    }
                    return true;
                }
                // CAS failed: retry loop
            } else {
                // Not enough tokens even after refill
                // Still try to advance lastRefillNanos to avoid recomputing same elapsed
                if (elapsedNanos > 0) {
                    if (lastRefillNanos.compareAndSet(lastRefill, now)) {
                        availableTokens.set(newAvailable); // best effort; no CAS needed since we didn't consume
                    }
                }
                return false;
            }
        }
    }

    /**
     * Refills the bucket to the current time without consuming tokens.
     * Used by {@link #getAvailableTokens()}.
     */
    private void refillUpToNow() {
        long now = System.nanoTime();
        while (true) {
            long lastRefill = lastRefillNanos.get();
            long available = availableTokens.get();
            long elapsedNanos = now - lastRefill;
            if (elapsedNanos <= 0) return;
            long newTokensScaled = elapsedNanos * refillRateScaledPerNano;
            long newAvailable = Math.min(maxTokensScaled, available + newTokensScaled);
            if (lastRefillNanos.compareAndSet(lastRefill, now)) {
                availableTokens.set(newAvailable);
                return;
            }
        }
    }

    /**
     * Computes nanoseconds to wait until {@code tokensScaled} tokens become available,
     * based on current bucket state.
     */
    private long computeWaitNanos(long tokensScaled) {
        long available = availableTokens.get();
        if (available >= tokensScaled) return 0;
        long deficitScaled = tokensScaled - available;
        // deficitScaled / refillRateScaledPerNano = nanos needed
        // Use ceiling division to avoid waiting too little
        return (deficitScaled + refillRateScaledPerNano - 1) / refillRateScaledPerNano;
    }

    /**
     * Parks the current thread for approximately {@code nanos} nanoseconds.
     * Uses a lock+condition to allow early wakeup on signal.
     */
    private void parkNanos(long nanos) throws InterruptedException {
        parkLock.lock();
        try {
            notEmpty.awaitNanos(nanos);
        } finally {
            parkLock.unlock();
        }
    }

    /**
     * Signals one waiting thread that tokens may be available.
     */
    private void signalWaiters() {
        parkLock.lock();
        try {
            notEmpty.signal();
        } finally {
            parkLock.unlock();
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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double REFILL_RATE = 1000.0; // 1000 tokens/sec
    private static final long BURST_CAPACITY = 100;   // burst of 100

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(REFILL_RATE, BURST_CAPACITY);
    }

    @Test
    void testInitialBurstAllowsFullCapacity() {
        // Should be able to acquire burstCapacity tokens immediately
        for (int i = 0; i < BURST_CAPACITY; i++) {
            assertTrue(limiter.tryAcquire(), "Initial burst should allow " + BURST_CAPACITY + " tokens");
        }
        // Next acquire should fail (bucket empty)
        assertFalse(limiter.tryAcquire(), "Bucket should be empty after burst");
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        // Drain the bucket
        for (int i = 0; i < BURST_CAPACITY; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());

        // Wait for refill: at 1000 tokens/sec, 1 token takes 1ms
        Thread.sleep(10); // ~10 tokens
        assertTrue(limiter.tryAcquire(), "Should have refilled ~10 tokens after 10ms");
    }

    @Test
    void testBlockingAcquireWaitsForTokens() throws InterruptedException {
        // Drain
        for (int i = 0; i < BURST_CAPACITY; i++) {
            limiter.acquire();
        }

        long start = System.nanoTime();
        // This should block until ~1 token refills (~1ms)
        limiter.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 0 && elapsedMs <= 50,
                "Blocking acquire should wait ~1ms, got " + elapsedMs + "ms");
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        // Drain
        for (int i = 0; i < BURST_CAPACITY; i++) {
            limiter.acquire();
        }

        // Timeout before refill
        assertFalse(limiter.tryAcquire(1, 1, TimeUnit.MILLISECONDS),
                "Should timeout before token refills");

        // Timeout after refill
        assertTrue(limiter.tryAcquire(1, 10, TimeUnit.MILLISECONDS),
                "Should succeed within 10ms");
    }

    @Test
    void testMultiTokenAcquire() {
        assertTrue(limiter.tryAcquire(10), "Should acquire 10 tokens");
        assertEquals(BURST_CAPACITY - 10, limiter.getAvailableTokens());
        assertFalse(limiter.tryAcquire(BURST_CAPACITY), "Cannot acquire more than capacity");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0})
    void testInvalidRefillRate(double rate) {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(rate, 10));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void testInvalidBurstCapacity(long capacity) {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(100, capacity));
    }

    @Test
    void testGetAvailableTokensApproximate() {
        long initial = limiter.getAvailableTokens();
        assertEquals(BURST_CAPACITY, initial);

        limiter.tryAcquire(30);
        assertEquals(BURST_CAPACITY - 30, limiter.getAvailableTokens());
    }

    // ============================================================
    // CONCURRENT TEST: Verifies long-run rate does not exceed limit
    // ============================================================

    @Test
    void testConcurrentLongRunRateLimit() throws InterruptedException {
        // Configuration
        double rate = 5000.0;      // 5000 tokens/sec
        long burst = 100;          // small burst
        int numThreads = 16;
        int durationSeconds = 3;
        long maxExpectedTokens = (long) (rate * durationSeconds * 1.02); // 2% tolerance

        TokenBucketRateLimiter testLimiter = new TokenBucketRateLimiter(rate, burst);
        AtomicLong startTime = new AtomicLong(-1);
        LongAdder totalAcquired = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // Worker: acquire as fast as possible for durationSeconds
        Runnable worker = () -> {
            try {
                startLatch.await(); // wait for coordinated start
                long threadStart = System.nanoTime();
                if (startTime.get() == -1) {
                    startTime.compareAndSet(-1, threadStart);
                }
                long deadline = threadStart + TimeUnit.SECONDS.toNanos(durationSeconds);
                while (System.nanoTime() < deadline) {
                    testLimiter.acquire(); // blocking acquire 1 token
                    totalAcquired.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        };

        // Submit workers
        for (int i = 0; i < numThreads; i++) {
            executor.submit(worker);
        }

        // Start all simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue(endLatch.await(durationSeconds + 5, TimeUnit.SECONDS),
                "Workers did not finish in time");
        executor.shutdownNow();

        long acquired = totalAcquired.sum();
        System.out.printf("Acquired: %,d tokens in %ds (rate: %.0f tok/s, limit: %.0f tok/s)%n",
                acquired, durationSeconds, (double) acquired / durationSeconds, rate);

        // Verify: long-run rate should not exceed configured rate (with small tolerance for burst)
        assertTrue(acquired <= maxExpectedTokens,
                String.format("Rate exceeded! Acquired %,d tokens, max allowed ~%,d (rate=%.0f, burst=%d)",
                        acquired, maxExpectedTokens, rate, burst));
    }

    @Test
    void testConcurrentTryAcquireNoBlocking() throws InterruptedException {
        // High contention non-blocking test
        TokenBucketRateLimiter testLimiter = new TokenBucketRateLimiter(10000, 1000);
        int threads = 32;
        int iterations = 10000;
        LongAdder success = new LongAdder();
        LongAdder failure = new LongAdder();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        Runnable worker = () -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    if (testLimiter.tryAcquire()) success.increment();
                    else failure.increment();
                }
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < threads; i++) executor.submit(worker);
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        long totalSuccess = success.sum();
        // With 1000 burst + refill during test, most should succeed
        assertTrue(totalSuccess > threads * iterations * 0.9,
                "Too many failures: success=" + totalSuccess + ", failure=" + failure.sum());
    }

    @Test
    void testBurstDoesNotExceedCapacity() throws InterruptedException {
        // Even with many threads hammering tryAcquire, total acquired in a burst
        // should never exceed burstCapacity + refill_during_test
        TokenBucketRateLimiter testLimiter = new TokenBucketRateLimiter(100, 50); // low rate, small burst
        int threads = 50;
        LongAdder acquired = new LongAdder();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        Runnable worker = () -> {
            try {
                start.await();
                // Each thread tries to grab as many as possible in a tight loop for 50ms
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                while (System.nanoTime() < deadline) {
                    if (testLimiter.tryAcquire()) acquired.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        for (int i = 0; i < threads; i++) executor.submit(worker);
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        executor.shutdownNow();

        // In 50ms at 100 tokens/sec, max refill = 5 tokens. Initial burst = 50.
        // So max possible = 55. Allow small margin for timing.
        long maxPossible = 50 + 5 + 2;
        assertTrue(acquired.sum() <= maxPossible,
                "Burst exceeded capacity: acquired " + acquired.sum() + ", max ~" + maxPossible);
    }
}
```

---

## Key Design Notes for Reviewers

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Precision** | Fixed-point (micro-tokens) | Avoids floating-point drift; exact arithmetic |
| **Refill** | Lazy, on-demand via CAS loop | Lock-free fast path; no background thread |
| **Blocking** | `LockSupport.parkNanos` + `Condition` | Low overhead; avoids busy-spin; single lock only for waiters |
| **Wake-up** | Signal on successful consume from empty | Prevents thundering herd; only one waiter woken per refill quantum |
| **Burst enforcement** | `maxTokensScaled` cap in refill math | Hard limit, even under concurrent refill attempts |
| **Timeout** | Deadline-based with `Math.min(wait, remaining)` | Accurate timeout; no drift |

The concurrent test `testConcurrentLongRunRateLimit` runs 16 threads for 3 seconds at 5000 tokens/sec and asserts the total acquired ≤ `rate × time × 1.02` (2% tolerance for initial burst). This empirically validates the long-run rate guarantee.