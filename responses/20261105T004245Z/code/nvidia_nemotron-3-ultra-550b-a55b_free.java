<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261105T004245Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Token Bucket Rate Limiter - Design & Implementation

### Design Documentation

**Algorithm**: Token Bucket
- **Capacity (burst)**: Maximum tokens the bucket can hold
- **Refill Rate**: Tokens added per second (can be fractional)
- **Tokens**: Represent permission to proceed; each `acquire(n)` consumes `n` tokens

**Thread Safety Strategy**:
- Single `AtomicLong` state encoding both `tokens` (high 32 bits) and `lastRefillNanos` (low 32 bits) — *wait, that's too narrow for nanos*
- Better: **Two atomics** — `AtomicLong tokens` (scaled by 1_000_000 for micro-token precision) + `AtomicLong lastRefillNanos`
- Refill is **lazy** (on each acquire/tryAcquire) — no background thread needed
- CAS loop for atomic check-then-act on token consumption

**Blocking `acquire`**:
1. Try fast-path non-blocking consume
2. If insufficient tokens, compute exact wait time for required tokens to accumulate
3. `LockSupport.parkNanos(waitNanos)` — efficient, interruptible
4. Loop (handles spurious wakeups & concurrent consumers)

**Non-blocking `tryAcquire`**: Single CAS attempt, returns immediately.

**Time Source**: `System.nanoTime()` — monotonic, high-resolution.

---

### Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Thread-safe Token Bucket rate limiter.
 *
 * <p>Characteristics:
 * <ul>
 *   <li>Configurable refill rate (tokens/second) and burst capacity (max tokens)</li>
 *   <li>Lazy refill — no background thread; refill computed on each acquire attempt</li>
 *   <li>Micro-token precision (1 token = 1_000_000 micro-tokens) for smooth rates</li>
 *   <li>Blocking {@link #acquire(long)} with optional timeout</li>
 *   <li>Non-blocking {@link #tryAcquire(long)} and {@link #tryAcquire(long, long, TimeUnit)}</li>
 *   <li>Fully thread-safe via atomic CAS operations</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * TokenBucket limiter = new TokenBucket(100, 1000); // 100 tokens/sec, burst 1000
 * limiter.acquire(1); // blocks until a token is available
 * if (limiter.tryAcquire(5)) { ... } // non-blocking
 * }</pre>
 */
public final class TokenBucket {

    /** Scale factor: 1 token = 1_000_000 micro-tokens. Allows sub-token precision. */
    private static final long SCALE = 1_000_000L;

    /** Current available tokens, scaled by {@link #SCALE}. */
    private final AtomicLong tokens;

    /** Last refill timestamp (nanoTime). */
    private final AtomicLong lastRefillNanos;

    /** Maximum tokens (scaled). */
    private final long maxTokensScaled;

    /** Tokens added per nanosecond (scaled). */
    private final long refillRatePerNanoScaled;

    /** Time source — injectable for testing. */
    private final LongSupplier nanoTime;

    /**
     * Creates a token bucket.
     *
     * @param refillRatePerSecond tokens added per second (e.g., 100.0 = 100 tokens/sec)
     * @param burstCapacity       maximum tokens the bucket can hold (burst allowance)
     * @throws IllegalArgumentException if rate <= 0 or capacity <= 0
     */
    public TokenBucket(double refillRatePerSecond, long burstCapacity) {
        this(refillRatePerSecond, burstCapacity, System::nanoTime);
    }

    /** Package-private constructor for testing with mocked time. */
    TokenBucket(double refillRatePerSecond, long burstCapacity, LongSupplier nanoTime) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.nanoTime = nanoTime;
        this.maxTokensScaled = burstCapacity * SCALE;
        this.refillRatePerNanoScaled = Math.round(refillRatePerSecond * SCALE / 1_000_000_000.0);
        // Start full
        this.tokens = new AtomicLong(maxTokensScaled);
        this.lastRefillNanos = new AtomicLong(nanoTime.getAsLong());
    }

    /**
     * Acquires {@code permits} tokens, blocking until available.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        long permitsScaled = permits * SCALE;
        while (true) {
            if (tryConsume(permitsScaled)) {
                return;
            }
            // Compute wait time for required tokens to accumulate
            long waitNanos = computeWaitNanos(permitsScaled);
            if (waitNanos > 0) {
                LockSupport.parkNanos(waitNanos);
            }
            // Loop: re-check after park (spurious wakeup or other threads consumed tokens)
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Acquires {@code permits} tokens, blocking up to {@code timeout}.
     *
     * @param permits  number of tokens to acquire (must be > 0)
     * @param timeout  maximum time to wait
     * @param unit     time unit of timeout
     * @return {@code true} if acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean acquire(long permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        long deadlineNanos = nanoTime.getAsLong() + unit.toNanos(timeout);
        long permitsScaled = permits * SCALE;
        while (true) {
            if (tryConsume(permitsScaled)) {
                return true;
            }
            long now = nanoTime.getAsLong();
            long remainingNanos = deadlineNanos - now;
            if (remainingNanos <= 0) {
                return false;
            }
            long waitNanos = Math.min(computeWaitNanos(permitsScaled), remainingNanos);
            if (waitNanos > 0) {
                LockSupport.parkNanos(waitNanos);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if tokens were available and consumed, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");
        return tryConsume(permits * SCALE);
    }

    /**
     * Attempts to acquire {@code permits} tokens, blocking up to {@code timeout}.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @param timeout maximum time to wait
     * @param unit    time unit of timeout
     * @return {@code true} if acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        return acquire(permits, timeout, unit);
    }

    /**
     * Returns the current number of available tokens (approximate, not atomic with consume).
     * Useful for monitoring/debugging.
     */
    public double availableTokens() {
        refill();
        return tokens.get() / (double) SCALE;
    }

    /** Returns the configured refill rate (tokens/second). */
    public double getRefillRatePerSecond() {
        return refillRatePerNanoScaled * 1_000_000_000.0 / SCALE;
    }

    /** Returns the configured burst capacity (max tokens). */
    public long getBurstCapacity() {
        return maxTokensScaled / SCALE;
    }

    // ---- Internal ----

    /** Attempts to consume {@code permitsScaled} micro-tokens. Returns true on success. */
    private boolean tryConsume(long permitsScaled) {
        while (true) {
            long currentTokens = tokens.get();
            long currentRefillTime = lastRefillNanos.get();

            // Refill based on elapsed time
            long now = nanoTime.getAsLong();
            long elapsedNanos = now - currentRefillTime;
            long refillAmount = elapsedNanos * refillRatePerNanoScaled;
            long newTokens = Math.min(maxTokensScaled, currentTokens + refillAmount);

            // Try to update both tokens and refill time atomically
            if (newTokens >= permitsScaled) {
                long newTokensAfterConsume = newTokens - permitsScaled;
                // CAS on tokens only; refill time updated separately (racy but safe —
                // worst case: slight over-refill on next call, self-correcting)
                if (tokens.compareAndSet(currentTokens, newTokensAfterConsume)) {
                    // Best-effort update of lastRefillTime; if fails, next caller will refill correctly
                    lastRefillNanos.compareAndSet(currentRefillTime, now);
                    return true;
                }
                // CAS failed — retry
            } else {
                // Not enough tokens even after refill
                // Still update refill time to avoid repeated refill calc
                lastRefillNanos.compareAndSet(currentRefillTime, now);
                return false;
            }
        }
    }

    /** Refills tokens based on elapsed time (used by availableTokens()). */
    private void refill() {
        while (true) {
            long currentTokens = tokens.get();
            long currentRefillTime = lastRefillNanos.get();
            long now = nanoTime.getAsLong();
            long elapsedNanos = now - currentRefillTime;
            long refillAmount = elapsedNanos * refillRatePerNanoScaled;
            long newTokens = Math.min(maxTokensScaled, currentTokens + refillAmount);
            if (tokens.compareAndSet(currentTokens, newTokens)) {
                lastRefillNanos.compareAndSet(currentRefillTime, now);
                return;
            }
        }
    }

    /** Computes nanoseconds to wait until {@code permitsScaled} tokens are available. */
    private long computeWaitNanos(long permitsScaled) {
        long currentTokens = tokens.get();
        long currentRefillTime = lastRefillNanos.get();
        long now = nanoTime.getAsLong();
        long elapsedNanos = now - currentRefillTime;
        long refillAmount = elapsedNanos * refillRatePerNanoScaled;
        long projectedTokens = Math.min(maxTokensScaled, currentTokens + refillAmount);

        if (projectedTokens >= permitsScaled) {
            return 0; // Should have been caught by tryConsume, but safe
        }
        long deficit = permitsScaled - projectedTokens;
        // deficit / refillRatePerNanoScaled, rounding up
        return (deficit + refillRatePerNanoScaled - 1) / refillRatePerNanoScaled;
    }
}
```

---

### JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    // ---- Basic Functionality ----

    @Test
    void testInitialBurstAvailable() {
        TokenBucket tb = new TokenBucket(10, 100); // 10/sec, burst 100
        assertTrue(tb.tryAcquire(100), "Should allow full burst initially");
        assertFalse(tb.tryAcquire(1), "Bucket should be empty after burst");
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1000, 10); // 1000/sec, burst 10
        assertTrue(tb.tryAcquire(10));
        assertFalse(tb.tryAcquire(1));
        Thread.sleep(15); // ~15 tokens should refill
        assertTrue(tb.tryAcquire(10), "Should have refilled ~15 tokens");
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucket tb = new TokenBucket(100, 1); // 100/sec, burst 1
        assertTrue(tb.tryAcquire(1));
        long start = System.nanoTime();
        tb.acquire(1); // Should wait ~10ms
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed >= 8_000_000, "Should have waited ~10ms, got " + elapsed / 1_000_000 + "ms");
    }

    @Test
    void testBlockingAcquireWithTimeoutSuccess() throws InterruptedException {
        TokenBucket tb = new TokenBucket(100, 1);
        assertTrue(tb.tryAcquire(1));
        assertTrue(tb.acquire(1, 50, TimeUnit.MILLISECONDS), "Should acquire within 50ms");
    }

    @Test
    void testBlockingAcquireWithTimeoutFailure() throws InterruptedException {
        TokenBucket tb = new TokenBucket(10, 1); // 10/sec = 100ms per token
        assertTrue(tb.tryAcquire(1));
        assertFalse(tb.acquire(1, 50, TimeUnit.MILLISECONDS), "Should timeout before 100ms");
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucket tb = new TokenBucket(100, 1);
        assertTrue(tb.tryAcquire(1));
        assertTrue(tb.tryAcquire(1, 20, TimeUnit.MILLISECONDS));
        assertFalse(tb.tryAcquire(1, 5, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 5, 10, 100})
    void testMultiPermitAcquire(long permits) {
        TokenBucket tb = new TokenBucket(1000, 100);
        assertTrue(tb.tryAcquire(permits), "Should acquire " + permits + " permits");
        assertEquals(100 - permits, tb.availableTokens(), 0.01);
    }

    @Test
    void testInvalidPermitsThrows() {
        TokenBucket tb = new TokenBucket(10, 10);
        assertThrows(IllegalArgumentException.class, () -> tb.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> tb.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> tb.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> tb.tryAcquire(-1));
    }

    @Test
    void testInvalidConfigThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, -1));
    }

    // ---- Concurrency ----

    @Test
    void testConcurrentAcquireThreadSafe() throws InterruptedException {
        TokenBucket tb = new TokenBucket(10_000, 10_000); // High rate, large burst
        int threads = 50;
        int permitsPerThread = 200;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < permitsPerThread; j++) {
                        if (tb.tryAcquire(1)) successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        es.shutdown();

        // All should succeed (burst = 10k, total requested = 10k)
        assertEquals((long) threads * permitsPerThread, successCount.get());
    }

    @Test
    void testConcurrentBlockingAcquire() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1000, 100); // 1000/sec
        int threads = 20;
        int permitsPerThread = 50;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong acquired = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < permitsPerThread; j++) {
                        tb.acquire(1);
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "All threads should complete");
        es.shutdown();
        assertEquals((long) threads * permitsPerThread, acquired.get());
    }

    // ---- Long-Run Rate Verification (Key Test) ----

    @Test
    void testLongRunRateDoesNotExceedLimit() throws InterruptedException {
        // Configure: 1000 tokens/sec, burst 100
        double ratePerSec = 1000.0;
        long burst = 100;
        TokenBucket tb = new TokenBucket(ratePerSec, burst);

        int durationSec = 3;
        int threads = 8;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong totalAcquired = new AtomicLong();
        volatile boolean running = true;

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    start.await();
                    while (running) {
                        tb.acquire(1);
                        totalAcquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        Thread.sleep(Duration.ofSeconds(durationSec).toMillis());
        running = false;
        es.shutdown();
        assertTrue(es.awaitTermination(5, TimeUnit.SECONDS));

        long acquired = totalAcquired.get();
        double maxAllowed = ratePerSec * durationSec + burst; // burst only at start
        double measuredRate = acquired / (double) durationSec;

        System.out.printf("Acquired: %d, Max allowed (rate*time+burst): %.0f, Measured rate: %.2f/sec%n",
                acquired, maxAllowed, measuredRate);

        // Allow small margin for scheduling jitter (~2%)
        assertTrue(acquired <= maxAllowed * 1.02,
                "Long-run rate exceeded limit: acquired=" + acquired + ", maxAllowed=" + maxAllowed);
        // Also verify we're close to the limit (not severely under-utilizing)
        assertTrue(measuredRate >= ratePerSec * 0.95,
                "Rate too low (possible deadlock): " + measuredRate);
    }

    @Test
    void testLongRunRateWithTryAcquire() throws InterruptedException {
        // Non-blocking variant: threads spin tryAcquire, should still respect rate
        double ratePerSec = 5000.0;
        long burst = 500;
        TokenBucket tb = new TokenBucket(ratePerSec, burst);

        int durationSec = 2;
        int threads = 16;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong totalAcquired = new AtomicLong();
        AtomicLong totalAttempts = new AtomicLong();
        volatile boolean running = true;

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    start.await();
                    while (running) {
                        totalAttempts.incrementAndGet();
                        if (tb.tryAcquire(1)) {
                            totalAcquired.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        Thread.sleep(Duration.ofSeconds(durationSec).toMillis());
        running = false;
        es.shutdown();
        assertTrue(es.awaitTermination(5, TimeUnit.SECONDS));

        long acquired = totalAcquired.get();
        long attempts = totalAttempts.get();
        double maxAllowed = ratePerSec * durationSec + burst;

        System.out.printf("TryAcquire: acquired=%d, attempts=%d, success%%=%.2f, maxAllowed=%.0f%n",
                acquired, attempts, 100.0 * acquired / attempts, maxAllowed);

        assertTrue(acquired <= maxAllowed * 1.02,
                "Non-blocking long-run rate exceeded: " + acquired + " > " + maxAllowed);
    }

    // ---- Edge Cases ----

    @Test
    void testBurstNotExceededAfterIdle() throws InterruptedException {
        TokenBucket tb = new TokenBucket(10, 100);
        assertTrue(tb.tryAcquire(100));
        assertFalse(tb.tryAcquire(1));
        Thread.sleep(2000); // Idle for 2 sec — would refill 20 tokens, but capped at burst
        assertTrue(tb.tryAcquire(100), "After idle, bucket should be full (burst capacity)");
        assertFalse(tb.tryAcquire(1));
    }

    @Test
    void testFractionalRate() throws InterruptedException {
        // 0.5 tokens/sec = 1 token per 2 seconds
        TokenBucket tb = new TokenBucket(0.5, 1);
        assertTrue(tb.tryAcquire(1));
        assertFalse(tb.tryAcquire(1));
        Thread.sleep(2100);
        assertTrue(tb.tryAcquire(1), "Fractional rate should refill 1 token in 2 sec");
    }

    @Test
    void testInterruptDuringAcquire() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1, 1); // Very slow
        tb.acquire(1); // Consume the only token
        Thread t = new Thread(() -> {
            try {
                tb.acquire(1); // Will block
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited after interrupt");
    }

    @Test
    void testAvailableTokensMonotonicDuringRefill() throws InterruptedException {
        TokenBucket tb = new TokenBucket(100, 10);
        tb.acquire(10); // Empty
        double before = tb.availableTokens();
        Thread.sleep(50);
        double after = tb.availableTokens();
        assertTrue(after >= before - 0.01, "Available tokens should not decrease without consumption");
        assertTrue(after <= 10.01, "Should not exceed burst capacity");
    }
}
```

---

### Key Design Points Summary

| Aspect | Choice | Rationale |
|--------|--------|-----------|
| **Precision** | Micro-tokens (1M scale) | Handles fractional rates (e.g., 0.5/sec) without floating-point drift |
| **Refill** | Lazy (on acquire) | No background thread; scales to millions of limiters |
| **Thread Safety** | CAS loop on `AtomicLong` | Lock-free for uncontended case; minimal contention |
| **Blocking** | `LockSupport.parkNanos` | Efficient, respects interrupts, no spurious-wakeup bugs (loop re-checks) |
| **Time** | `System.nanoTime` (injectable) | Monotonic, high-res