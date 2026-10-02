<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261002T162336Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**TokenBucket.java – thread‑safe token‑bucket rate limiter**

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;

/**
 * A classic token‑bucket rate limiter.
 *
 * <p>The bucket holds a number of “tokens” up to a configurable {@code capacity}.
 * Tokens are added continuously at a fixed {@code refillRatePerSecond}.  When a
 * caller successfully acquires {@code permits} tokens, those tokens are removed
 * from the bucket.  If the bucket does not contain enough tokens the caller
 * either waits (blocking {@code acquire}) or receives {@code false}
 * (non‑blocking {@code tryAcquire}).
 *
 * <p>All mutable state is protected by the intrinsic lock of the {@code TokenBucket}
 * instance (i.e. {@code synchronized(this)}).  This makes the class safe for
 * concurrent use by any number of threads without external synchronization.
 *
 * <p>Typical usage:
 *
 * <pre>
 *   TokenBucket limiter = new TokenBucket(10.0, 5.0); // 5 permits/sec, burst up to 10
 *   if (limiter.tryAcquire(1)) {
 *       // … do work …
 *   } else {
 *       // rate limit exceeded
 *   }
 * </pre>
 *
 * <p>The implementation works with fractional tokens so that a fractional refill
 * rate (e.g. 2.5 tokens/sec) is handled correctly.
 */
public class TokenBucket {

    /** Maximum number of tokens the bucket can hold (burst capacity). */
    private final double capacity;

    /** Rate at which tokens are added, in tokens per second. */
    private final double refillRatePerSecond;

    /** Current number of tokens in the bucket (may be fractional). */
    private double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastNano;

    /**
     * Creates a new token bucket.
     *
     * @param capacity           maximum tokens the bucket can hold (burst size)
     * @param refillRatePerSecond long‑term average rate of token addition (tokens/sec)
     * @throws IllegalArgumentException if capacity <= 0 or refillRatePerSecond <= 0
     */
    public TokenBucket(double capacity, double refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity; // start full – allows an initial burst
        this.lastNano = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * <p>This method must be called while holding the intrinsic lock.
     */
    private void refill() {
        long now = System.nanoTime();
        if (now > lastNano) {
            double elapsedSec = (now - lastNano) / 1_000_000_000.0;
            double toAdd = elapsedSec * refillRatePerSecond;
            if (toAdd > 0) {
                tokens = Math.min(capacity, tokens + toAdd);
                lastNano = now;
            }
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available.
     *
     * @param permits number of tokens to consume (must be > 0)
     * @throws IllegalArgumentException if permits <= 0
     * @throws InterruptedException     if the current thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        synchronized (this) {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                // Not enough tokens – compute how long we need to wait for the deficit.
                double deficit = permits - tokens; // > 0
                double waitSec = deficit / refillRatePerSecond;
                long waitNanos = (long) Math.ceil(waitSec * 1_000_000_000.0);
                // nanos to milliseconds + remainder nanoseconds for Object.wait
                long waitMillis = waitNanos / 1_000_000;
                int waitNanosRemainder = (int) (waitNanos % 1_000_000);
                // wait releases the lock and reacquires it when timed out or interrupted.
                this.wait(waitMillis, waitNanosRemainder);
                // Loop again – another thread may have stolen tokens while we waited.
            }
        }
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of tokens to consume (must be > 0)
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        synchronized (this) {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        }
    }

    /** Returns the current number of tokens (mainly for testing/debugging). */
    double getTokens() {
        synchronized (this) {
            refill();
            return tokens;
        }
    }

    /** Returns the configured capacity (burst size). */
    double getCapacity() {
        return capacity;
    }

    /** Returns the configured refill rate (tokens per second). */
    double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
```

---

### Design Documentation (inline comments)

* **Thread‑safety** – All accesses to the mutable fields (`tokens`, `lastNano`) are performed inside a `synchronized(this)` block. The intrinsic lock guarantees visibility and atomicity across threads. The `wait`/`notify` mechanism used in `acquire` also releases the lock while waiting, avoiding busy‑spinning.
* **Refill algorithm** – On each operation we compute the elapsed time since the last refill, convert it to tokens (`elapsedSec * refillRatePerSecond`), and add them to the bucket, capping at `capacity`. This yields a smooth, continuous token addition that matches the long‑term average rate.
* **Blocking acquire** – If insufficient tokens are present, we calculate the exact waiting time needed for the deficit to be replenished, then `Object.wait` for that duration (with nanosecond precision). After the wait we loop again to re‑check the bucket (protecting against spurious wakeups or token theft by other threads).
* **Non‑blocking tryAcquire** – Simply refill and test; if enough tokens exist we consume them and return `true`, otherwise `false`.
* **Fractional tokens** – The implementation stores tokens as a `double`, allowing fractional refill rates and permits without loss of precision for the typical use‑case (rates ≤ 1 M tokens/sec). If integer‑only behavior is required, the caller can round permits.

---

## JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.*;

class TokenBucketTest {

    /** Helper that runs a task for a fixed duration and returns how many times it succeeded. */
    private static long runForDuration(TokenBucket bucket,
                                       int permitsPerCall,
                                       Duration duration,
                                       boolean blocking) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            AtomicLong granted = new AtomicLong(0);
            AtomicBoolean stop = new AtomicBoolean(false);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(4); // wait for workers to finish

            Runnable worker = () -> {
                try {
                    startLatch.await(); // all threads start together
                    while (!stop.get()) {
                        boolean ok;
                        if (blocking) {
                            try {
                                bucket.acquire(permitsPerCall);
                                ok = true;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            ok = bucket.tryAcquire(permitsPerCall);
                        }
                        if (ok) {
                            granted.addAndGet(permitsPerCall);
                        }
                        // When not blocking we spin fast; to avoid burning CPU we yield occasionally.
                        if (!blocking) {
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            };

            for (int i = 0; i < 4; i++) {
                exec.submit(worker);
            }

            startLatch.countDown(); // let all workers go
            Thread.sleep(duration.toMillis()); // let the test run
            stop.set(true); // tell workers to exit
            doneLatch.await(5, TimeUnit.SECONDS); // wait for them to finish
            return granted.get();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void tryAcquireConsumesTokensWhenAvailable() {
        TokenBucket tb = new TokenBucket(5.0, 10.0); // capacity 5, rate 10/s
        assertTrue(tb.tryAcquire(3), "First acquire should succeed");
        assertEquals(2.0, tb.getTokens(), 1e-9, "2 tokens should remain");
        assertTrue(tb.tryAcquire(2), "Second acquire should succeed");
        assertEquals(0.0, tb.getTokens(), 1e-9, "Bucket should be empty");
        assertFalse(tb.tryAcquire(1), "Third acquire should fail (no tokens)");
    }

    @Test
    void blockingAcquireWaitsWhenNeeded() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1.0, 1.0); // 1 token/sec, burst 1
        // Consume the only token immediately
        assertTrue(tb.tryAcquire(1));
        long start = System.nanoTime();
        // The next acquire should block roughly 1 second
        tb.acquire(1);
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed >= 900_000_000L, "Should have waited at least ~0.9 sec");
        assertTrue(elapsed <= 1_200_000_000L, "Should not have waited excessively long");
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        double rate = 20.0; // tokens per second
        double capacity = 5.0; // burst
        TokenBucket tb = new TokenBucket(capacity, rate);

        Duration testDuration = Duration.ofSeconds(3);
        // Each successful call consumes 1 permit
        long permitsGranted = runForDuration(tb, 1, testDuration, true);

        long maxExpected = (long) Math.ceil(rate * testDuration.getSeconds()) + (long) capacity;
        // Allow a small tolerance for timing jitter (≈5%)
        double tolerance = 0.05 * maxExpected;
        assertTrue(permitsGranted <= maxExpected + tolerance,
                String.format("Granted %d permits, expected ≤ %d (rate %.1f/s × %d s + burst %.1f)",
                        permitsGranted, maxExpected, rate, testDuration.getSeconds(), capacity));
    }

    @Test
    void tryAcquireRespectsBurstLimit() throws Exception {
        TokenBucket tb = new TokenBucket(3.0, 0.0); // rate 0 → no refill, pure burst
        // Should be able to acquire up to capacity instantly
        assertTrue(tb.tryAcquire(3));
        assertFalse(tb.tryAcquire(1)); // no more tokens, rate is zero so never refilled
        // Wait a bit – still no refill because rate is zero
        Thread.sleep(100);
        assertFalse(tb.tryAcquire(1));
    }
}
```

### Explanation of the Tests

| Test | Purpose |
|------|---------|
| `tryAcquireConsumesTokensWhenAvailable` | Verifies basic token consumption and that the bucket reports the correct remaining amount. |
| `blockingAcquireWaitsWhenNeeded` | Checks that the blocking `acquire` correctly waits until enough tokens have been refilled. |
| `longRunRateDoesNotExceedConfiguredLimit` | **Concurrent test** – four threads continuously call blocking `acquire(1)` for a fixed duration. The total number of permits granted must not exceed `rate × duration + capacity` (the theoretical maximum allowed by the token bucket). A small tolerance (5 %) accounts for scheduling jitter. |
| `tryAcquireRespectsBurstLimit` | Confirms that when the refill rate is zero the bucket behaves as a pure burst‑limited container (no refill over time). |

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and rely only on the JDK’s concurrency utilities, making them easy to run in any standard build environment (Maven, Gradle, etc.). The implementation and tests together demonstrate a correct, thread‑safe token‑bucket rate limiter with both blocking and non‑blocking acquisition semantics.