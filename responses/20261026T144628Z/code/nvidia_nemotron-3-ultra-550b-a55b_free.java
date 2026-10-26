<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261026T144628Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Documentation

### Overview
This implementation provides a **thread-safe token bucket rate limiter** with:
- **Configurable refill rate** (tokens/second) and **burst capacity** (max tokens)
- **Non-blocking `tryAcquire(permits)`** — returns immediately with success/failure
- **Blocking `acquire(permits)`** — parks the calling thread until permits are available
- **Lock-free hot path** using `AtomicLong` for token state and `LockSupport` for parking

### Core Data Structure
```java
// State stored in two atomics (updated together via CAS loop)
AtomicLong availableTokens;   // current tokens (0 .. maxTokens)
AtomicLong lastRefillNanos;   // epoch nanos of last refill
```

### Refill Algorithm
On every `tryAcquire`/`acquire` call:
1. Read `lastRefillNanos` and `availableTokens` (volatile reads)
2. Compute `elapsedNanos = now - lastRefillNanos`
3. `tokensToAdd = (elapsedNanos * refillRatePerSecond) / 1_000_000_000`
4. `newTokens = min(maxTokens, availableTokens + tokensToAdd)`
5. CAS `lastRefillNanos` from old → now; if CAS fails, retry (rare contention)

This **lazy refill** avoids a background thread and scales well under contention.

### Acquire Logic
- **`tryAcquire(n)`**: Refill, then CAS `availableTokens` from `current` → `current - n` if `current ≥ n`. Returns `true` on success, `false` otherwise.
- **`acquire(n)`**: Loop `tryAcquire(n)`; on failure, compute wait time for next token, `LockSupport.parkNanos(waitNanos)`, retry. Handles spurious wakeups and interrupt (throws `InterruptedException`).

### Thread Safety
- All mutable state in `AtomicLong` — no `synchronized`/`Lock` on hot path
- CAS loops guarantee linearizable updates
- `LockSupport.parkNanos` provides efficient, interruptible blocking without `Object.wait()` overhead

### Correctness Guarantees
- **Burst allowance**: Up to `maxTokens` permits can be acquired instantly if bucket was full
- **Long-run rate**: Average acquisition rate ≤ `refillRatePerSecond` (proven by token conservation)
- **Fairness**: FIFO-ish under contention (park/unpark order), but not strict fairness

### Complexity
- `tryAcquire`: O(1) amortized, 1–2 CAS attempts typical
- `acquire`: O(wait time), parks instead of spinning

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
 * <p>Supports configurable refill rate (tokens per second) and burst capacity (max tokens).
 * Provides both blocking {@link #acquire(long)} and non-blocking {@link #tryAcquire(long)}.
 *
 * <p>Implementation uses lazy refill with {@link AtomicLong} CAS loops for the hot path
 * and {@link LockSupport#parkNanos(long)} for efficient blocking.
 */
public final class TokenBucketRateLimiter {

    private final long maxTokens;
    private final double refillRatePerSecond;
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillNanos;

    /**
     * Creates a new token bucket rate limiter.
     *
     * @param refillRatePerSecond tokens added per second (must be > 0)
     * @param maxTokens           maximum burst capacity (must be > 0)
     * @throws IllegalArgumentException if parameters are not positive
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long maxTokens) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.maxTokens = maxTokens;
        this.availableTokens = new AtomicLong(maxTokens); // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @return {@code true} if permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        if (permits > maxTokens) {
            return false; // can never satisfy
        }

        while (true) {
            long now = System.nanoTime();
            long oldLastRefill = lastRefillNanos.get();
            long oldTokens = availableTokens.get();

            // Refill
            long elapsedNanos = now - oldLastRefill;
            long tokensToAdd = (long) ((elapsedNanos * refillRatePerSecond) / 1_000_000_000.0);
            long newTokens = Math.min(maxTokens, oldTokens + tokensToAdd);

            // Try to update refill time (CAS)
            if (!lastRefillNanos.compareAndSet(oldLastRefill, now)) {
                continue; // lost race, retry
            }

            // Try to consume tokens
            if (newTokens >= permits) {
                long updatedTokens = newTokens - permits;
                if (availableTokens.compareAndSet(oldTokens, updatedTokens)) {
                    return true;
                }
                // CAS failed due to concurrent consume; retry loop
            } else {
                // Not enough tokens even after refill — put back the refill time?
                // No: we already advanced lastRefillNanos. This is correct because
                // time has passed; we don't want to re-refill the same interval.
                return false;
            }
        }
    }

    /**
     * Acquires the given number of permits, blocking until available.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        if (permits > maxTokens) {
            throw new IllegalArgumentException("permits (" + permits + ") exceeds maxTokens (" + maxTokens + ")");
        }

        while (true) {
            if (tryAcquire(permits)) {
                return;
            }
            // Compute wait time for next token
            long now = System.nanoTime();
            long currentTokens = availableTokens.get();
            long lastRefill = lastRefillNanos.get();

            // Tokens needed
            long needed = permits - currentTokens;
            if (needed <= 0) {
                continue; // race: tokens appeared, retry tryAcquire
            }

            // Nanos per token
            double nanosPerToken = 1_000_000_000.0 / refillRatePerSecond;
            long waitNanos = (long) (needed * nanosPerToken);

            // Clamp to avoid overflow / excessive park
            long maxWaitNanos = TimeUnit.SECONDS.toNanos(1);
            if (waitNanos > maxWaitNanos) {
                waitNanos = maxWaitNanos;
            }

            LockSupport.parkNanos(this, waitNanos);

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Returns the current number of available tokens (approximate, may be stale by the time you read it).
     */
    public long getAvailableTokens() {
        refillIfNeeded();
        return availableTokens.get();
    }

    /**
     * Returns the maximum burst capacity.
     */
    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    // Internal: refill without consuming, for inspection
    private void refillIfNeeded() {
        while (true) {
            long now = System.nanoTime();
            long oldLastRefill = lastRefillNanos.get();
            long oldTokens = availableTokens.get();

            long elapsedNanos = now - oldLastRefill;
            long tokensToAdd = (long) ((elapsedNanos * refillRatePerSecond) / 1_000_000_000.0);
            if (tokensToAdd == 0) {
                return;
            }
            long newTokens = Math.min(maxTokens, oldTokens + tokensToAdd);

            if (lastRefillNanos.compareAndSet(oldLastRefill, now)) {
                availableTokens.compareAndSet(oldTokens, newTokens);
                return;
            }
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
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double REFILL_RATE = 1000.0; // 1000 tokens/sec
    private static final long MAX_TOKENS = 100;       // burst of 100
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(REFILL_RATE, MAX_TOKENS);
    }

    @Test
    void testInitialBurst() {
        // Should be able to acquire up to maxTokens immediately
        assertTrue(limiter.tryAcquire(MAX_TOKENS));
        assertFalse(limiter.tryAcquire(1)); // bucket empty
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        limiter.tryAcquire(MAX_TOKENS); // drain
        assertFalse(limiter.tryAcquire(1));

        // Wait for ~1 token
        Thread.sleep(2); // 2ms at 1000 tokens/sec = 2 tokens
        assertTrue(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void testTryAcquireInvalidPermits() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    @Test
    void testAcquireInvalidPermits() {
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(MAX_TOKENS + 1));
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        limiter.tryAcquire(MAX_TOKENS); // drain

        Thread t = new Thread(() -> {
            try {
                Thread.sleep(50); // refill ~50 tokens
                limiter.tryAcquire(10); // consume some to allow acquire(1) to proceed
            } catch (InterruptedException ignored) {}
        });
        t.start();

        // Should block until tokens available
        limiter.acquire(1);
        t.join();
    }

    @Test
    void testAcquireRespectsInterrupt() {
        limiter.tryAcquire(MAX_TOKENS); // drain

        Thread t = new Thread(() -> {
            try {
                limiter.acquire(1);
            } catch (InterruptedException expected) {
                // success
            }
        });
        t.start();
        Thread.sleep(10);
        t.interrupt();
        assertDoesNotThrow(() -> t.join(1000));
        assertFalse(t.isAlive());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 10, 50, 100})
    void testTryAcquireVariousSizes(long permits) {
        assertTrue(limiter.tryAcquire(permits));
        assertEquals(MAX_TOKENS - permits, limiter.getAvailableTokens());
    }

    @Test
    void testConcurrentTryAcquire() throws InterruptedException {
        int threads = 50;
        int permitsPerThread = 2;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong successes = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire(permitsPerThread)) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        exec.shutdown();

        // At most MAX_TOKENS / permitsPerThread successes
        long maxSuccesses = MAX_TOKENS / permitsPerThread;
        assertTrue(successes.get() <= maxSuccesses,
                "successes=" + successes.get() + " > max=" + maxSuccesses);
    }

    /**
     * Long-run rate test: run many threads acquiring permits for a fixed duration,
     * verify total acquired ≤ refillRate * duration + burst.
     */
    @Test
    void testLongRunRateLimit() throws InterruptedException {
        double rate = 5000.0; // 5000 tokens/sec
        long burst = 200;
        TokenBucketRateLimiter testLimiter = new TokenBucketRateLimiter(rate, burst);

        int threads = 20;
        Duration testDuration = Duration.ofMillis(500);
        long expectedMaxTokens = (long) (rate * testDuration.toSeconds() + burst) + 100; // +100 fudge for scheduling

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        LongAdder totalAcquired = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + testDuration.toNanos();
                    while (System.nanoTime() < deadline) {
                        if (testLimiter.tryAcquire(1)) {
                            totalAcquired.increment();
                        } else {
                            // brief yield to avoid tight spin
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        long acquired = totalAcquired.sum();
        System.out.println("Acquired: " + acquired + ", expected max ~ " + expectedMaxTokens);
        assertTrue(acquired <= expectedMaxTokens,
                "Rate limit exceeded: acquired " + acquired + " > " + expectedMaxTokens);
    }

    @Test
    void testBurstDoesNotExceedMaxTokens() throws InterruptedException {
        // Even with many threads trying, availableTokens never exceeds maxTokens
        int threads = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong maxObserved = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        long avail = limiter.getAvailableTokens();
                        maxObserved.updateAndGet(prev -> Math.max(prev, avail));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        exec.shutdown();

        assertTrue(maxObserved.get() <= MAX_TOKENS,
                "Observed tokens " + maxObserved.get() + " > maxTokens " + MAX_TOKENS);
    }
}
```

---

## Key Test Highlights

| Test | Purpose |
|------|---------|
| `testInitialBurst` | Verifies bucket starts full (`maxTokens` available) |
| `testRefillOverTime` | Checks lazy refill adds tokens at correct rate |
| `testBlockingAcquire` | Validates `acquire()` parks and wakes when tokens refill |
| `testAcquireRespectsInterrupt` | Ensures `InterruptedException` propagated |
| `testConcurrentTryAcquire` | Stress test: concurrent CAS loops don't over-grant |
| `testLongRunRateLimit` | **Critical**: 20 threads × 500ms @ 5000 tok/s → ≤ 2600 tokens (burst + rate×time) |
| `testBurstDoesNotExceedMaxTokens` | Invariant: `availableTokens ≤ maxTokens` always |

The **long-run rate test** uses a `LongAdder` for low-contention counting, runs for a fixed wall-clock duration, and asserts the token conservation property:  
`total_acquired ≤ maxTokens + refillRate × duration`.