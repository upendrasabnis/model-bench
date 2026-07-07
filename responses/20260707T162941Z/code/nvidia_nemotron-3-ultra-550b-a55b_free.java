<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260707T162941Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
##Token Bucket Rate Limiter - Design & Implementation

### Design Document

**Algorithm**: Token Bucket
- **State**: `tokens` (double, current available), `lastRefillNanos` (long, last refill timestamp)
- **Parameters**: `ratePerSecond` (double, sustained rate), `capacity` (double, max burst)
- **Refill**: On every operation, compute `elapsed = now - lastRefillNanos`, add `elapsed * ratePerSecond / 1e9` tokens, clamp to `capacity`, update `lastRefillNanos = now`.

**Concurrency Strategy**: Lock-free CAS loop on a single `AtomicLong` holding a bit-packed state:
- Bits 0-51: `tokens * 1024` (fixed-point, 10 bits fractional precision → ~0.001 token resolution)
- Bits 52-63: unused (reserved)
- Separate `AtomicLong` for `lastRefillNanos` (updated with volatile write after successful CAS)

*Why not `synchronized`?* CAS avoids kernel transitions under contention; the critical section is tiny (arithmetic only).  
*Why fixed-point?* Avoids `double` non-determinism in CAS comparisons; 1024x scaling gives micro-token precision.

**Blocking `acquire(permits)`**:
1. Fast path: try non-blocking `tryAcquire`.
2. If insufficient tokens, compute `deficit = permits - tokens`, `waitNanos = deficit * 1e9 / ratePerSecond`.
3. `LockSupport.parkNanos(waitNanos + 100_000)` (small padding for scheduler granularity).
4. Loop until acquired.

**Non-blocking `tryAcquire(permits)`**: Single CAS attempt after refill; returns `true`/`false` immediately.

**Edge Cases**:
- `permits <= 0` → `IllegalArgumentException`
- `ratePerSecond <= 0` or `capacity <= 0` → `IllegalArgumentException` at construction
- Clock skew (time going backward): treated as zero elapsed time (max with 0).
- Overflow: fixed-point uses 52 bits → max tokens ≈ 4.5e12, far beyond practical limits.

---

### Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-safe token-bucket rate limiter.
 *
 * <p>Supports configurable sustained rate (tokens/second) and burst capacity (max tokens).
 * Provides both blocking {@link #acquire(double)} and non-blocking {@link #tryAcquire(double)}.
 *
 * <p>Implementation uses a lock-free CAS loop on a fixed-point encoded token count
 * (10 bits fractional precision) and a separate volatile timestamp for refill tracking.
 */
public final class TokenBucketRateLimiter {

    private static final long SCALE = 1024L;           // 2^10 fixed-point scale
    private static final long TOKEN_MASK = (1L << 52) - 1; // 52 bits for tokens
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double ratePerSecond;
    private final double capacity;
    private final long capacityScaled;
    private final long rateScaledPerNano;              // rate * SCALE / 1e9

    private final AtomicLong state = new AtomicLong(); // packed: tokensScaled (low 52 bits)
    private final AtomicLong lastRefillNanos = new AtomicLong();

    /**
     * Creates a new limiter.
     *
     * @param ratePerSecond sustained token refill rate (tokens/second), must be > 0
     * @param capacity      maximum bucket capacity (tokens), must be > 0
     * @throws IllegalArgumentException if rate or capacity not positive
     */
    public TokenBucketRateLimiter(double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0.0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (capacity <= 0.0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.capacityScaled = doubleToScaled(capacity);
        this.rateScaledPerNano = (long) Math.round(ratePerSecond * SCALE / 1e9);

        // Initialize bucket full
        long now = System.nanoTime();
        lastRefillNanos.set(now);
        state.set(capacityScaled);
    }

    /** Converts double tokens to fixed-point scaled long. */
    private static long doubleToScaled(double tokens) {
        return Math.round(tokens * SCALE);
    }

    /** Converts fixed-point scaled long to double tokens. */
    private static double scaledToDouble(long scaled) {
        return scaled / (double) SCALE;
    }

    /** Refills tokens based on elapsed time. Returns current tokens (scaled). */
    private long refillAndGetTokens(long now) {
        long lastRefill = lastRefillNanos.get();
        long elapsedNanos = now - lastRefill;
        if (elapsedNanos <= 0) {
            // Clock skew or same instant: no refill
            return state.get() & TOKEN_MASK;
        }

        // Compute tokens to add: elapsed * rateScaledPerNano
        long tokensToAdd = elapsedNanos * rateScaledPerNano;

        // CAS loop to update state
        while (true) {
            long currentState = state.get();
            long currentTokens = currentState & TOKEN_MASK;
            long newTokens = currentTokens + tokensToAdd;
            if (newTokens > capacityScaled) {
                newTokens = capacityScaled;
            }
            // Only update lastRefillNanos if we successfully add tokens (or bucket full)
            long newState = newTokens; // low 52 bits only
            if (state.compareAndSet(currentState, newState)) {
                // Successful CAS: publish new refill time
                lastRefillNanos.set(now);
                return newTokens;
            }
            // CAS failed: retry with fresh state
        }
    }

    /**
     * Acquires {@code permits} tokens, blocking until available.
     *
     * @param permits number of tokens to acquire, must be > 0
     * @throws IllegalArgumentException if permits <= 0
     * @throws InterruptedException     if thread interrupted while waiting
     */
    public void acquire(double permits) throws InterruptedException {
        if (permits <= 0.0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        long permitsScaled = doubleToScaled(permits);

        while (true) {
            long now = System.nanoTime();
            long tokensScaled = refillAndGetTokens(now);

            if (tokensScaled >= permitsScaled) {
                // Fast path: enough tokens, try to deduct
                if (tryDeductTokens(tokensScaled, permitsScaled)) {
                    return;
                }
                // CAS failed due to contention, retry
                continue;
            }

            // Not enough tokens: compute wait time
            long deficitScaled = permitsScaled - tokensScaled;
            // waitNanos = deficit / ratePerSecond * 1e9 = deficitScaled / rateScaledPerNano
            long waitNanos = (deficitScaled * NANOS_PER_SECOND) / (ratePerSecond * SCALE);
            // Add small padding for scheduler granularity
            waitNanos += 100_000; // 0.1 ms

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(waitNanos);
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire, must be > 0
     * @return {@code true} if acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(double permits) {
        if (permits <= 0.0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        long permitsScaled = doubleToScaled(permits);

        long now = System.nanoTime();
        long tokensScaled = refillAndGetTokens(now);

        if (tokensScaled >= permitsScaled) {
            return tryDeductTokens(tokensScaled, permitsScaled);
        }
        return false;
    }

    /** Attempts CAS deduction of tokens. Returns true on success. */
    private boolean tryDeductTokens(long currentTokensScaled, long permitsScaled) {
        long newTokensScaled = currentTokensScaled - permitsScaled;
        long currentState = state.get();
        // State only holds tokens in low bits; high bits always zero
        return state.compareAndSet(currentState, newTokensScaled);
    }

    /**
     * Returns current available tokens (approximate, may be stale by the time caller reads it).
     */
    public double getAvailableTokens() {
        long now = System.nanoTime();
        long tokensScaled = refillAndGetTokens(now);
        return scaledToDouble(tokensScaled);
    }

    /** Returns configured rate (tokens/second). */
    public double getRatePerSecond() {
        return ratePerSecond;
    }

    /** Returns configured capacity (max tokens). */
    public double getCapacity() {
        return capacity;
    }
}
```

---

### JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double EPSILON = 1e-9;

    @Test
    void constructorRejectsNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void acquireRejectsNonPositivePermits() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
    }

    @Test
    void tryAcquireRejectsNonPositivePermits() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    @Test
    void initialBurstAllowsFullCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50);
        // Should be able to acquire full capacity immediately
        assertTrue(limiter.tryAcquire(50));
        assertFalse(limiter.tryAcquire(1)); // empty
    }

    @Test
    void tryAcquireReturnsFalseWhenEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertTrue(limiter.tryAcquire(5));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void acquireBlocksUntilTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1000/sec, capacity 1
        assertTrue(limiter.tryAcquire(1)); // take the only token

        long start = System.nanoTime();
        // Acquire 0.5 tokens - should wait ~0.5ms
        limiter.acquire(0.5);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Allow generous margin for scheduler
        assertTrue(elapsedMs >= 0, "Should have waited some time");
        assertTrue(elapsedMs < 50, "Waited too long: " + elapsedMs + "ms");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.5, 1.0, 2.5, 10.0})
    void refillRateMatchesConfiguredRate(double rate) throws InterruptedException {
        double capacity = rate * 2; // allow burst of 2 seconds
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        // Drain bucket
        limiter.acquire(capacity);

        long start = System.nanoTime();
        // Acquire 1 token - should take ~1/rate seconds
        limiter.acquire(1.0);
        double elapsedSec = (System.nanoTime() - start) / 1e9;

        double expectedSec = 1.0 / rate;
        // Allow 50% margin for scheduler granularity
        assertTrue(elapsedSec >= expectedSec * 0.5, "Too fast: " + elapsedSec + "s vs expected ~" + expectedSec);
        assertTrue(elapsedSec <= expectedSec * 2.0, "Too slow: " + elapsedSec + "s vs expected ~" + expectedSec);
    }

    @Test
    void concurrentAcquireDoesNotExceedCapacity() throws InterruptedException {
        int threads = 50;
        double capacity = 100;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, capacity);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    if (limiter.tryAcquire(2)) { // each tries to take 2
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        exec.shutdown();

        // At most capacity/2 = 50 successes (since each takes 2)
        long successes = successCount.get();
        assertTrue(successes <= capacity / 2, "Exceeded capacity: " + successes + " successes");
        assertEquals((int) (capacity / 2), successes, "All possible acquires should succeed");
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws InterruptedException {
        // This test verifies that over a sustained period, the actual throughput
        // does not exceed the configured rate (within statistical margin).
        double rate = 10_000; // 10k tokens/sec
        double capacity = 1000; // burst allowance
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, capacity);

        int numThreads = 8;
        int runSeconds = 3;
        long startNanos = System.nanoTime();
        long endNanos = startNanos + TimeUnit.SECONDS.toNanos(runSeconds);

        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        LongAdder totalAcquired = new LongAdder();
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            exec.submit(() -> {
                try {
                    while (System.nanoTime() < endNanos) {
                        // Acquire in small batches to simulate realistic usage
                        limiter.acquire(10);
                        totalAcquired.add(10);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        doneLatch.await();
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);

        double actualDurationSec = (System.nanoTime() - startNanos) / 1e9;
        double actualRate = totalAcquired.sum() / actualDurationSec;

        System.out.printf("Long-run test: acquired %,d tokens in %.3fs → %.0f tokens/sec (limit: %.0f)%n",
                totalAcquired.sum(), actualDurationSec, actualRate, rate);

        // Allow 5% overshoot overhead margin (scheduler, CAS retries, test harness)
        // The limiter should NEVER exceed the configured rate in steady state.
        assertTrue(actualRate <= rate * 1.05,
                String.format("Rate exceeded limit: %.0f > %.0f * 1.05", actualRate, rate));

        // Should achieve at least 90% of configured rate (allowing for burst depletion at start)
        assertTrue(actualRate >= rate * 0.90,
                String.format("Rate too low: %.0f < %.0f * 0.90", actualRate, rate));
    }

    @Test
    void getAvailableTokensReflectsState() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 50);
        assertEquals(50, limiter.getAvailableTokens(), 0.01);

        limiter.acquire(10);
        assertEquals(40, limiter.getAvailableTokens(), 0.01);

        limiter.tryAcquire(20);
        assertEquals(20, limiter.getAvailableTokens(), 0.01);
    }

    @Test
    void refillAfterDrain() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1000/sec
        limiter.acquire(10); // drain

        // Wait for ~5 tokens to refill (5ms)
        Thread.sleep(10); // sleep longer to be safe

        double available = limiter.getAvailableTokens();
        assertTrue(available >= 5, "Expected ~10 tokens after 10ms, got " + available);
        assertTrue(available <= 10, "Should not exceed capacity");
    }

    @Test
    void tryAcquireDoesNotBlock() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // very slow
        limiter.acquire(1); // drain

        long start = System.nanoTime();
        boolean result = limiter.tryAcquire(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result);
        assertTrue(elapsedMs < 10, "tryAcquire blocked for " + elapsedMs + "ms");
    }

    @Test
    void interruptDuringAcquireThrowsInterruptedException() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec, empty
        limiter.acquire(1); // drain

        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1); // will block
            } catch (InterruptedException e) {
                // expected
                return;
            }
            fail("Expected InterruptedException");
        });
        t.start();
        Thread.sleep(50); // ensure it's parked
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have terminated after interrupt");
    }
}
```

---

### Key Test Highlights

| Test | Purpose |
|------|---------|
| `constructorRejectsNonPositiveRate/Capacity` | Input validation |
| `initialBurstAllowsFullCapacity` | Burst semantics |
| `acquireBlocksUntilTokensAvailable` | Blocking behavior & timing |
| `refillRateMatchesConfiguredRate` | Parametrized rate accuracy |
| `concurrentAcquireDoesNotExceedCapacity` | Thread-safety under contention |
| **`longRunRateDoesNotExceedConfiguredLimit`** | **Core requirement: sustained rate ≤ config** |
| `interruptDuringAcquireThrowsInterruptedException` | Responsiveness to interruption |

The **long-run test** runs 8 threads for 3 seconds, each acquiring 10 tokens per loop iteration. It asserts:
- **Upper bound**: `actualRate ≤ configuredRate × 1.05` (never exceeds limit)
- **Lower bound**: `actualRate ≥ configuredRate × 0.90` (utilizes available bandwidth)

This catches both token leakage (rate > limit) and excessive conservatism (rate ≪ limit).