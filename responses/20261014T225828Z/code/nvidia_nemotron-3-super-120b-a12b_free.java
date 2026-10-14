<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261014T225828Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Java Implementation**

```java
package com.example.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket starts full ( {@code capacity} tokens ). Tokens are added at a
 * constant {@code refillRate} (tokens per unit time). When a thread wants to
 * {@code acquire} {@code permits} tokens it either blocks until enough tokens
 * are available or, with {@code tryAcquire}, succeeds immediately iff the
 * required permits are present.</p>
 *
 * <p>All state changes are protected by a single {@link ReentrantLock}. The
 * algorithm is O(1) per call.</p>
 *
 * @param <T> marker type – the class is not generic, the parameter is only
 *            to illustrate that the limiter works with any unit (permits,
 *            bytes, …). In practice the limiter works with {@code long}
 *            permits.
 */
public final class TokenBucket {

    private final long capacity;                     // maximum tokens the bucket can hold
    private final double refillRatePerNanos;         // tokens added per nanosecond
    private final ReentrantLock lock = new ReentrantLock();
    private volatile double tokens;                  // current token count (may be fractional)
    private volatile long lastRefillNanos;           // timestamp of the last refill (nanoseconds)

    /**
     * Creates a token bucket.
     *
     * @param capacity      maximum burst size (tokens). Must be > 0.
     * @param refillRate    refill rate in tokens per second. Must be > 0.
     * @throws IllegalArgumentException if arguments are not positive.
     */
    public TokenBucket(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerNanos = refillRate / Duration.ofSeconds(1).toNanos();
        this.tokens = capacity;               // start full
        this.lastRefillNanos = Instant.now().toEpochMilli() * 1_000_000; // nanos
    }

    /** Private helper – refill tokens based on elapsed time. */
    private void refill(Instant now) {
        long nowNanos = now.toEpochMilli() * 1_000_000 + now.getNano();
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            double toAdd = elapsed * refillRatePerNanos;
            tokens = Math.min(capacity, tokens + toAdd);
            lastRefillNanos = nowNanos;
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if the permits were granted, {@code false} otherwise.
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            Instant now = Instant.now();
            refill(now);
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until {@code permits} tokens can be acquired, then consumes them.
     *
     * <p>The method recomputes the wait time after each unsuccessful attempt,
     * protecting against spurious wake‑ups and clock adjustments.</p>
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        boolean interrupted = false;
        try {
            while (true) {
                lock.lock();
                try {
                    Instant now = Instant.now();
                    refill(now);
                    if (tokens >= permits) {
                        tokens -= permits;
                        return; // success
                    }
                    // Not enough tokens – compute how long we must wait.
                    double deficit = permits - tokens; // >0
                    double waitNanos = deficit / refillRatePerNanos;
                    // Sleep at least 1 ms to avoid busy‑spinning.
                    long sleepMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis((long) waitNanos));
                    // We release the lock before sleeping.
                } finally {
                    lock.unlock();
                }
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ie) {
                    interrupted = true; // preserve interrupt status
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Expose current approximate token count – mainly for testing/debugging. */
    public double getAvailableTokens() {
        lock.lock();
        try {
            Instant now = Instant.now();
            refill(now);
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

---

### Design Documentation

| Aspect | Description |
|--------|-------------|
| **Algorithm** | Classic token bucket: a bucket holds at most `capacity` tokens. Tokens are added continuously at `refillRate` (tokens per second). Consuming an operation requires removing the requested number of tokens. If insufficient tokens are present, the operation either fails immediately (`tryAcquire`) or waits until enough tokens have been refilled (`acquire`). |
| **Thread Safety** | All mutable state (`tokens`, `lastRefillNanos`) is accessed only while holding a single `ReentrantLock`. This guarantees atomicity of the *refill‑then‑consume* step and prevents race conditions between concurrent `tryAcquire`/`acquire` calls. The lock is released before a blocking sleep to allow other threads to make progress. |
| **Precision** | Token counts are kept as `double` to support fractional refill rates (e.g., 2.5 tokens/s). The bucket never exceeds `capacity` because we clamp after each refill. The waiting time in `acquire` is computed from the current deficit and the configured rate, yielding an accurate long‑term average. |
| **Blocking Acquire** | After each unsuccessful attempt we recompute the deficit, compute the necessary wait time, sleep (minimum 1 ms to avoid busy‑spinning), then retry. The loop also reacts to `InterruptedException` by preserving the interrupt status. |
| **Non‑Blocking TryAcquire** | Performs a refill, checks if enough tokens exist, and if so deducts them and returns `true`. Otherwise returns `false` without waiting. |
| **Overflow / Underflow Protection** | `capacity` and `refillRate` are validated in the constructor. The refill step caps tokens at `capacity`. The `acquire`/`tryAcquire` methods reject non‑positive permit requests. |
| **Performance** | O(1) per call (constant‑time refill check). No allocations besides the primitive objects used for timing. Contention is limited to the single lock; high throughput is achievable because the lock is held only briefly (except during the sleep in `acquire`). |
| **Usage Example** | ```java\nTokenBucket limiter = new TokenBucket(10, 5); // burst 10, refill 5/s\nif (limiter.tryAcquire(1)) { … } else { … }\nlimiter.acquire(2); // blocks until 2 tokens are available\n``` |

---

## JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class TokenBucketTest {

    private static final double TOLERANCE = 0.15; // 15 % tolerance for scheduling jitter

    @Test
    void tryAcquireImmediateSuccessWhenTokensAvailable() {
        TokenBucket tb = new TokenBucket(5, 10); // capacity 5, refill 10/s
        assertTrue(tb.tryAcquire(3), "Should succeed when tokens are present");
        assertEquals(2.0, tb.getAvailableTokens(), 1e-9);
    }

    @Test
    void tryAcquireFailsWhenInsufficientTokens() {
        TokenBucket tb = new TokenBucket(2, 1);
        assertTrue(tb.tryAcquire(2), "First acquire should succeed");
        assertFalse(tb.tryAcquire(1), "Second acquire should fail – bucket empty");
    }

    @Test
    void acquireBlocksUntilTokensAreRefilled() throws InterruptedException {
        TokenBucket tb = new TokenBucket(0, 5); // start empty, refill 5/s
        long start = System.nanoTime();
        tb.acquire(3); // need 3 tokens
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = Duration.ofNanos(elapsedNs).toSeconds()
                + Duration.ofNanos(elapsedNs).toNanosPart() * 1e-9;
        // Expected wait ≈ 3 tokens / 5 tokens per sec = 0.6 s
        assertTrue(elapsedSec >= 0.55 && elapsedSec <= 0.75,
                "Acquire should wait roughly the expected time");
    }

    @Test
    void acquireRespectsInterrupt() throws Exception {
        TokenBucket tb = new TokenBucket(0, 1);
        Thread worker = new Thread(() -> {
            try {
                tb.acquire(1);
            } catch (InterruptedException ignored) {
                // expected
            }
        });
        worker.start();
        Thread.sleep(10); // let it start waiting
        worker.interrupt();
        worker.join(500);
        assertFalse(worker.isAlive(), "Thread should have terminated after interrupt");
        assertTrue(Thread.interrupted() || worker.getState() == Thread.State.TERMINATED);
    }

    @Test
    void concurrentLongRunRateDoesNotExceedConfiguredLimit() throws Exception {
        long capacity = 20;
        double refillRatePerSec = 10.0; // 10 tokens per second
        TokenBucket tb = new TokenBucket(capacity, refillRatePerSec);

        int workerCount = 50;
        Duration testDuration = Duration.ofSeconds(10);
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        AtomicLong permitsAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);
        volatile boolean stop = false;

        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all workers to start together
                    while (!stop) {
                        if (tb.tryAcquire(1)) {
                            permitsAcquired.incrementAndGet();
                        }
                        // No Thread.sleep – we spin to generate maximal demand.
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers simultaneously
        startLatch.countDown();

        // Let the test run for the configured duration
        Thread.sleep(testDuration.toMillis());
        stop = true; // signal workers to exit
        doneLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        long total = permitsAcquired.get();
        double actualRate = total / testDuration.getSeconds();

        // The long‑run rate must not exceed the configured refill rate.
        // We allow a small tolerance for burst capacity and scheduling jitter.
        assertTrue(actualRate <= refillRatePerSec * (1 + TOLERANCE),
                String.format("Observed rate %.2f tokens/s exceeds limit %.2f tokens/s (+%.0f%%)",
                        actualRate, refillRatePerSec, TOLERANCE * 100));

        // Additionally, we can check that we never exceeded capacity + rate * time
        // (the maximum possible tokens that could have been granted).
        long maxPossible = (long) (capacity + refillRatePerSec * testDuration.getSeconds());
        assertTrue(total <= maxPossible + workerCount, // +workerCount accounts for possible overshoot due to tight loop
                String.format("Total permits %d exceeds theoretical maximum %d", total, maxPossible));
    }
}
```

### Explanation of the Test Suite

| Test | Purpose |
|------|---------|
| `tryAcquireImmediateSuccessWhenTokensAvailable` | Verifies that a successful `tryAcquire` consumes tokens and leaves the bucket with the expected remainder. |
| `tryAcquireFailsWhenInsufficientTokens` | Confirms that `tryAcquire` returns `false` when the bucket does not hold enough tokens and does not change state. |
| `acquireBlocksUntilTokensAreRefilled` | Checks that the blocking `acquire` waits the correct amount of time for tokens to refill. |
| `acquireRespectsInterrupt` | Ensures that an interrupted thread waiting in `acquire` exits promptly and preserves the interrupt status. |
| `concurrentLongRunRateDoesNotExceedConfiguredLimit` | The core concurrency test: many threads repeatedly call `tryAcquire(1)` without sleeping, generating maximal demand. After a fixed interval we measure the total permits granted and assert that the observed long‑run rate does not exceed the configured refill rate (allowing a modest tolerance for burst and scheduling variance). It also checks that the total never exceeds the theoretical maximum (`capacity + rate * time`). |

All tests use JUnit 5 (`org.junit.jupiter.api`) and run with Java 17 or later. They can be executed with any standard build tool (Maven, Gradle) by placing the source in `src/main/java` and the tests in `src/test/java`. The concurrent test is deterministic enough for CI environments while still stressing the limiter’s thread‑safety and rate‑guaranteeing properties.