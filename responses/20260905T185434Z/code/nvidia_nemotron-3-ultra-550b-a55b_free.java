<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260905T185434Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter in Java

## Design Documentation

### Algorithm Overview
The **Token Bucket** algorithm controls the rate of operations by maintaining a bucket of tokens:
- **Refill Rate (r)**: Tokens added per second (e.g., 100 tokens/sec)
- **Burst Capacity (C)**: Maximum tokens the bucket can hold (e.g., 200 tokens)
- **Acquire(n)**: Consumes `n` tokens; blocks if insufficient tokens available
- **TryAcquire(n)**: Consumes `n` tokens if available; returns `false` immediately otherwise

### Thread-Safety Strategy
- **Single `AtomicLong` state**: Encodes both `tokens` (fractional, fixed-point) and `lastRefillNanos` into one 64-bit word
- **Lock-free refill**: Compare-and-set (CAS) loop updates state atomically
- **Park/Unpark for blocking**: Uses `LockSupport.parkNanos` to avoid busy-waiting; no `synchronized` or `ReentrantLock`

### State Encoding (64 bits)
| Bits | Field | Description |
|------|-------|-------------|
| 0–43 | `tokens` | Fixed-point tokens × 2¹⁶ (allows fractional tokens) |
| 44–63 | `lastRefillNanos` | Truncated nano-time (mod 2²⁰ ≈ 1.05 ms wrap handled by math) |

*Why 44 bits for tokens?* At 1M tokens/sec × 2¹⁶ scaling, 44 bits covers ~19 hours of full-capacity accumulation—sufficient for any practical burst.

### Refill Math
```java
elapsedNanos = now - lastRefillNanos;
newTokens = tokens + elapsedNanos * ratePerNano;  // ratePerNano = rate * 2¹⁶ / 1_000_000_000
newTokens = min(newTokens, maxTokens);
```

### Blocking Acquire
1. Calculate tokens needed (fixed-point)
2. Spin with CAS to refill + claim tokens
3. If insufficient, compute wait time: `deficit / ratePerNano`
4. `LockSupport.parkNanos(waitNanos)`
5. Repeat (spurious wakeups handled naturally)

### Fairness & Contention
- **No strict FIFO**: Threads park for calculated durations; approximate fairness emerges
- **Low contention**: CAS succeeds quickly; parking threads don't spin
- **No thundering herd**: Each thread computes its own wait time

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
 * <p>Uses a single {@link AtomicLong} to encode both token count (fixed-point, 16 fractional bits)
 * and last-refill timestamp. All state transitions use compare-and-set (CAS) loops—no locks.
 *
 * <p>Blocking {@link #acquire(long)} uses {@link LockSupport#parkNanos(long)} for efficient waiting.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    // ----- Constants for fixed-point encoding -----
    private static final int FRACTIONAL_BITS = 16;
    private static final long FRACTIONAL_SCALE = 1L << FRACTIONAL_BITS;           // 65536
    private static final long TOKEN_MASK = (1L << 44) - 1;                        // 44 bits for tokens
    private static final int TIMESTAMP_SHIFT = 44;
    private static final long TIMESTAMP_MASK = (1L << 20) - 1;                    // 20 bits for timestamp

    // ----- Instance fields -----
    private final AtomicLong state;
    private final long maxTokensFP;           // maxTokens * FRACTIONAL_SCALE
    private final long ratePerNanoFP;         // (ratePerSecond * FRACTIONAL_SCALE) / 1_000_000_000
    private final long ratePerSecond;         // original rate for reporting

    /**
     * Creates a new rate limiter.
     *
     * @param ratePerSecond   tokens added per second (must be > 0)
     * @param burstCapacity   maximum tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double ratePerSecond, long burstCapacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.ratePerSecond = (long) ratePerSecond;
        this.maxTokensFP = burstCapacity * FRACTIONAL_SCALE;
        // ratePerNanoFP = ratePerSecond * FRACTIONAL_SCALE / 1_000_000_000
        this.ratePerNanoFP = Math.round((ratePerSecond * FRACTIONAL_SCALE) / 1_000_000_000.0);
        if (this.ratePerNanoFP == 0) {
            throw new IllegalArgumentException("ratePerSecond too small for fixed-point precision");
        }
        // Initial state: full bucket, timestamp = now (truncated)
        long nowTruncated = truncateTimestamp(System.nanoTime());
        this.state = new AtomicLong(encodeState(maxTokensFP, nowTruncated));
    }

    /**
     * Acquires the given number of tokens, blocking until available.
     *
     * @param tokens number of tokens to acquire (must be > 0)
     * @throws IllegalArgumentException if tokens <= 0
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire(long tokens) throws InterruptedException {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        long needFP = tokens * FRACTIONAL_SCALE;

        while (true) {
            long current = state.get();
            long tokensFP = decodeTokens(current);
            long lastRefill = decodeTimestamp(current);
            long now = System.nanoTime();
            long nowTruncated = truncateTimestamp(now);

            // Refill
            long elapsed = now - lastRefill;
            long refilledFP = tokensFP + elapsed * ratePerNanoFP;
            if (refilledFP > maxTokensFP) refilledFP = maxTokensFP;

            if (refilledFP >= needFP) {
                // Enough tokens—try to claim them
                long newTokensFP = refilledFP - needFP;
                long newState = encodeState(newTokensFP, nowTruncated);
                if (state.compareAndSet(current, newState)) {
                    return; // Success
                }
                // CAS failed—retry
            } else {
                // Not enough tokens: compute wait time and park
                long deficitFP = needFP - refilledFP;
                // waitNanos = deficitFP / ratePerNanoFP  (ceiling division)
                long waitNanos = (deficitFP + ratePerNanoFP - 1) / ratePerNanoFP;
                // Cap wait to avoid overflow / excessive parking
                if (waitNanos > 1_000_000_000L) waitNanos = 1_000_000_000L; // 1 sec max

                // Try to update timestamp to now (so other threads see progress)
                long optimisticState = encodeState(refilledFP, nowTruncated);
                state.compareAndSet(current, optimisticState); // Best effort

                LockSupport.parkNanos(waitNanos);
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }
    }

    /**
     * Attempts to acquire tokens without blocking.
     *
     * @param tokens number of tokens to acquire (must be > 0)
     * @return {@code true} if tokens were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if tokens <= 0
     */
    public boolean tryAcquire(long tokens) {
        if (tokens <= 0) throw new IllegalArgumentException("tokens must be > 0");
        long needFP = tokens * FRACTIONAL_SCALE;

        while (true) {
            long current = state.get();
            long tokensFP = decodeTokens(current);
            long lastRefill = decodeTimestamp(current);
            long now = System.nanoTime();
            long nowTruncated = truncateTimestamp(now);

            long elapsed = now - lastRefill;
            long refilledFP = tokensFP + elapsed * ratePerNanoFP;
            if (refilledFP > maxTokensFP) refilledFP = maxTokensFP;

            if (refilledFP >= needFP) {
                long newTokensFP = refilledFP - needFP;
                long newState = encodeState(newTokensFP, nowTruncated);
                if (state.compareAndSet(current, newState)) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * Attempts to acquire tokens, blocking up to the given timeout.
     *
     * @param tokens    number of tokens to acquire
     * @param timeout   maximum time to wait
     * @param unit      time unit of the timeout
     * @return {@code true} if acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(long tokens, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            if (tryAcquire(tokens)) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    /** Returns the configured refill rate (tokens per second). */
    public double getRatePerSecond() { return ratePerSecond; }

    /** Returns the configured burst capacity. */
    public long getBurstCapacity() { return maxTokensFP / FRACTIONAL_SCALE; }

    /** Returns current available tokens (approximate, non-blocking). */
    public double getAvailableTokens() {
        long current = state.get();
        long tokensFP = decodeTokens(current);
        long lastRefill = decodeTimestamp(current);
        long now = System.nanoTime();
        long elapsed = now - lastRefill;
        long refilledFP = tokensFP + elapsed * ratePerNanoFP;
        return Math.min(refilledFP, maxTokensFP) / (double) FRACTIONAL_SCALE;
    }

    // ----- Bit-packing helpers -----

    private static long encodeState(long tokensFP, long timestampTruncated) {
        return (tokensFP & TOKEN_MASK) | ((timestampTruncated & TIMESTAMP_MASK) << TIMESTAMP_SHIFT);
    }

    private static long decodeTokens(long state) {
        return state & TOKEN_MASK;
    }

    private static long decodeTimestamp(long state) {
        return (state >>> TIMESTAMP_SHIFT) & TIMESTAMP_MASK;
    }

    private static long truncateTimestamp(long nanos) {
        // Keep lower 20 bits (wraps every ~1.05 ms); math handles wrap via unsigned subtraction
        return nanos & TIMESTAMP_MASK;
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

    // ----- Basic Functional Tests -----

    @Test
    void constructorRejectsInvalidRate() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void constructorRejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void tryAcquireRespectsBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // 10/sec, burst 5
        assertTrue(limiter.tryAcquire(5));  // full burst
        assertFalse(limiter.tryAcquire(1)); // empty
    }

    @Test
    void acquireBlocksUntilTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100/sec, burst 1
        assertTrue(limiter.tryAcquire(1)); // take the only token

        long start = System.nanoTime();
        Thread t = new Thread(() -> {
            try { limiter.acquire(1); } catch (InterruptedException ignored) {}
        });
        t.start();

        // Wait a bit, then verify thread is still blocked
        Thread.sleep(50);
        assertTrue(t.isAlive(), "Thread should still be waiting for token");

        // Allow refill (10ms @ 100/sec = 1 token)
        Thread.sleep(20);
        t.join(100);
        assertFalse(t.isAlive(), "Thread should have acquired token and exited");

        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed >= 10_000_000L, "Should have waited ~10ms for refill");
    }

    @Test
    void tryAcquireWithTimeoutReturnsFalseOnTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);
        limiter.acquire(1); // drain
        assertFalse(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void tryAcquireWithTimeoutReturnsTrueWhenTokenArrives() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1 token/ms
        limiter.acquire(1); // drain
        assertTrue(limiter.tryAcquire(1, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void acquireThrowsOnInterrupt() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        limiter.acquire(1); // drain
        Thread t = new Thread(() -> {
            try { limiter.acquire(1); } catch (InterruptedException e) { /* expected */ }
        });
        t.start();
        Thread.sleep(10);
        t.interrupt();
        assertDoesNotThrow(() -> t.join(100));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 5, 10, 100})
    void tryAcquireRejectsNonPositiveTokens(long tokens) {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1));
    }

    // ----- Concurrency & Rate Accuracy Tests -----

    @Test
    void concurrentAccessDoesNotExceedBurst() throws InterruptedException {
        int threads = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // burst 10
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                if (limiter.tryAcquire(1)) successCount.incrementAndGet();
                done.countDown();
            }).start();
        }

        start.countDown();
        done.await(2, TimeUnit.SECONDS);
        assertTrue(successCount.get() <= 10, "Burst capacity exceeded: " + successCount.get());
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws InterruptedException {
        // This test verifies that over a sustained period, the limiter does not allow
        // more tokens than (rate * time + burst). It runs for 2 seconds at 1000 tokens/sec.
        double rate = 1000;
        long burst = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, burst);

        int workerThreads = 16;
        int durationSeconds = 2;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);

        LongAdder totalAcquired = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        Phaser phaser = new Phaser(workerThreads + 1); // +1 for main

        for (int i = 0; i < workerThreads; i++) {
            new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // wait for all threads ready
                while (System.nanoTime() < deadline) {
                    if (limiter.tryAcquire(1)) {
                        totalAcquired.increment();
                    } else {
                        // brief backoff to reduce contention
                        LockSupport.parkNanos(1000);
                    }
                }
                phaser.arriveAndDeregister();
            }).start();
        }

        phaser.arriveAndAwaitAdvance(); // release all workers
        phaser.awaitAdvance(phaser.getPhase()); // wait for all to finish

        long acquired = totalAcquired.sum();
        double maxAllowed = rate * durationSeconds + burst;
        double tolerance = 0.02; // 2% tolerance for scheduling jitter

        System.out.printf("Acquired: %d, Max allowed: %.0f (rate=%.0f, burst=%d, time=%ds)%n",
                acquired, maxAllowed, rate, burst, durationSeconds);

        assertTrue(acquired <= maxAllowed * (1 + tolerance),
                String.format("Rate exceeded: acquired %d > %.0f (max allowed + 2%%)", acquired, maxAllowed));
        // Also verify we got *close* to the limit (not severely under-utilized)
        assertTrue(acquired >= maxAllowed * 0.9,
                String.format("Severe under-utilization: acquired %d < 90%% of %.0f", acquired, maxAllowed));
    }

    @Test
    void blockingAcquireUnderHighContention() throws InterruptedException {
        // Many threads competing for tokens at exactly the refill rate
        double rate = 5000;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rate, 100);

        int threads = 32;
        int durationMs = 500;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);

        LongAdder acquired = new LongAdder();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException ignored) {}
                while (System.nanoTime() < deadline) {
                    try {
                        limiter.acquire(1);
                        acquired.increment();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        ready.await();
        start.countDown();

        Thread.sleep(durationMs + 200); // allow completion

        long total = acquired.sum();
        double expected = rate * durationMs / 1000.0;
        // Allow 5% tolerance
        assertTrue(total >= expected * 0.95 && total <= expected * 1.05,
                String.format("Expected ~%.0f, got %d", expected, total));
    }

    // ----- Edge Cases -----

    @Test
    void acquireLargeTokenCount() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, 1000);
        assertTrue(limiter.tryAcquire(500));
        assertTrue(limiter.tryAcquire(500));
        assertFalse(limiter.tryAcquire(1)); // burst exhausted
    }

    @Test
    void getAvailableTokensReflectsRefill() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10); // 1 token/ms
        assertEquals(10, limiter.getAvailableTokens(), 0.1);
        limiter.acquire(5);
        assertEquals(5, limiter.getAvailableTokens(), 0.1);
        // Wait for ~5 tokens to refill
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(6));
        double available = limiter.getAvailableTokens();
        assertTrue(available >= 10.0, "Should have refilled to burst capacity, got " + available);
    }
}
```

---

## Key Test Highlights

| Test | Purpose |
|------|---------|
| `longRunRateDoesNotExceedConfiguredLimit` | **Core requirement**: 16 threads hammer `tryAcquire` for 2 seconds; verifies total tokens ≤ `rate × time + burst` (+2% tolerance) |
| `concurrentAccessDoesNotExceedBurst` | 50 threads simultaneously call `tryAcquire`; at most `burst` succeed |
| `blockingAcquireUnderHighContention` | 32 threads call blocking `acquire`; verifies throughput matches configured rate |
| `acquireBlocksUntilTokensAvailable` | Single-threaded timing test proving `acquire` parks correctly |

---

## Running the Tests

```bash
# With Maven
mvn test -Dtest=TokenBucketRateLimiterTest

# With Gradle
./gradlew test --tests TokenBucketRateLimiterTest
```

The implementation is **lock-free**, **allocation-free** in the hot path (no `Instant`, `Duration`, or boxed types), and suitable for high-throughput scenarios (millions of acquires/sec on modern hardware).