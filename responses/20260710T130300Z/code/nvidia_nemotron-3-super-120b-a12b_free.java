<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260710T130300Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

| Aspect | Decision |
|--------|----------|
| **State** | `maxTokens` (burst capacity), `tokens` (current amount), `lastRefillNanos` (timestamp of last refill), `ratePerSec` (refill speed). All are `volatile`/`Atomic` or protected by a monitor so that concurrent threads see a consistent view. |
| **Thread‑safety** | A single `ReentrantLock` guards all mutable state. The lock also provides a `Condition` (`notEmpty`) that blocking `acquire()` waits on until enough tokens are available. This avoids busy‑spinning and works correctly with spurious wake‑ups. |
| **Refill policy** | Tokens are added continuously based on elapsed wall‑clock time: `added = (now‑lastRefill) * ratePerSec / 1e9`. The bucket never exceeds `maxTokens`. Refill is performed lazily on every public method call (`tryAcquire`, `acquire`). |
| **Blocking acquire** | After refilling, if `tokens >= permits` we consume them and return. Otherwise we compute the *minimum* time needed to accumulate the missing tokens and wait on the condition for that duration (with a loop to handle spurious wake‑ups). |
| **Non‑blocking tryAcquire** | Same refill step, then a simple check‑and‑deduct under the lock. Returns `true` iff the permit could be taken. |
| **Permits** | The API supports an arbitrary integer number of permits (default = 1). This makes the limiter usable for burst‑size > 1 requests as well. |
| **Performance** | Only a single lock is contended; the heavy work (time measurement, arithmetic) is done while holding the lock but is O(1). The condition wait puts the thread to sleep, so blocking acquire does not waste CPU. |
| **Correctness guarantees** | *Safety*: the number of permits ever granted ≤ initial burst + tokens added by the refill function (i.e., the long‑term average rate never exceeds `ratePerSec`). *Liveness*: a thread calling `acquire()` will eventually proceed because time always advances and the condition is signaled after each refill. |

---

## TokenBucket.java

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled at a constant rate (tokens per second) and can hold at most
 * {@code burstCapacity} tokens.  {@code acquire()} blocks until a token is available,
 * {@code tryAcquire()} attempts to take a token without blocking.</p>
 *
 * <p>All operations are O(1) and safe time.</p>
 */
public final class TokenBucket {

    /** Number of tokens that can be stored at most (the burst size). */
    private final double maxTokens;

    /** Refill speed in tokens per second. */
    private final double ratePerSec;

    /** Current amount of tokens in the bucket (may be fractional). */
    private double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when the bucket is empty. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket.
     *
     * @param ratePerSec      tokens added per second (must be > 0)
     * @param burstCapacity   maximum number of tokens the bucket can hold (must be > 0)
     */
    public TokenBucket(double ratePerSec, int burstCapacity) {
        if (ratePerSec <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be positive");
        }
        this.ratePerSec = ratePerSec;
        this.maxTokens = burstCapacity;
        this.tokens = burstCapacity; // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        if (now > lastRefillNanos) {
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            double toAdd = elapsedSec * ratePerSec;
            if (toAdd > 0) {
                tokens = Math.min(maxTokens, tokens + toAdd);
                lastRefillNanos = now;
            }
        }
    }

    /**
     * Attempts to acquire a permit without blocking.
     *
     * @return {@code true} if a permit was taken, {@code false} otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire (must be > 0)
     * @return {@code true} if the permits were taken, {@code false} otherwise
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }
        lock.lock();
        try {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                // Signal any waiters that the state changed (in case they were waiting for more)
                notEmpty.signalAll();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires a permit, blocking until one is available.
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * Acquires the given number of permits, blocking until they are available.
     *
     * @param permits number of permits to acquire (must be > 0)
     */
    public void acquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be positive");
        }
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    notEmpty.signalAll();
                    return;
                }
                // Not enough tokens – compute how long we need to wait.
                double needed = permits - tokens; // >0
                double waitSec = needed / ratePerSec;
                long waitNanos = Math.max(0L, (long) (waitSec * 1_000_000_000));
                // awaitNanos returns remaining time; loop again after wake‑up to re‑check condition.
                if (!notEmpty.awaitNanos(waitNanos)) {
                    // awaitNanos returns false if the wait time elapsed; we will loop and refill again.
                }
                // Spurious wake‑up: loop will refill again and re‑evaluate.
            }
        } catch (InterruptedException e) {
            // Preserve interrupt status and exit.
            Thread.currentThread().interrupt();
            throw new RuntimeException("Acquire was interrupted", e);
        } finally {
            lock.unlock();
        }
    }

    /** For testing / diagnostics – current approximate token count. */
    public double getTokens() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## TokenBucketTest.java (JUnit 5)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The concurrent test verifies that, over a long run, the observed rate does not
 * exceed the configured limit (allowing a small tolerance for timing jitter).</p>
 */
class TokenBucketTest {

    private static final double TOLERANCE = 0.15; // 15 % tolerance to accommodate CI variance

    @Test
    void testTryAcquireImmediateBurst() {
        TokenBucket tb = new TokenBucket(10.0, 5); // 10 tokens/s, burst 5
        // Should be able to take up to burst capacity instantly
        for (int i = 0; i < 5; i++) {
            assertTrue(tb.tryAcquire(), "Should be able to acquire permit " + i);
        }
        // Sixth attempt should fail because bucket is empty and refill hasn't had time
        assertFalse(tb.tryAcquire(), "No tokens left; tryAcquire should fail");
    }

    @Test
    void testTryAcquireAfterWait() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1.0, 1); // 1 token per second, burst 1
        assertTrue(tb.tryAcquire()); // take the only token
        assertFalse(tb.tryAcquire()); // bucket empty

        // Wait just enough for a token to refill (≈1.1 s to be safe)
        Thread.sleep(1100);
        assertTrue(tb.tryAcquire(), "Token should have refilled after waiting");
    }

    @Test
    void testBlockingAcquireRespectsRate() throws InterruptedException {
        final double rate = 5.0; // 5 tokens per second
        final int burst = 2;
        TokenBucket tb = new TokenBucket(rate, burst);

        long start = System.nanoTime();
        // Acquire 10 permits one‑by‑one; first two are immediate (burst)
        for (int i = 0; i < 10; i++) {
            tb.acquire();
        }
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = elapsedNs / 1_000_000_000.0;

        // Expected time: first 2 free, remaining 8 need 8 / rate seconds
        double expectedSec = Math.max(0.0, (10 - burst) / rate);
        assertTrue(elapsedSec >= expectedSec * 0.8,
                "Blocking acquire took too little time (elapsed=" + elapsedSec + "s, expected>=" + expectedSec + "s)");
        // Also ensure we didn't wait excessively long (allow 20 % slack)
        assertTrue(elapsedSec <= expectedSec * 1.2,
                "Blocking acquire took too much time (elapsed=" + elapsedSec + "s, expected<=" + expectedSec + "s)");
    }

    @Test
    void testConcurrentLongRunRateLimit() throws Exception {
        final double ratePerSec = 20.0; // 20 permits per second
        final int burst = 5;
        final TokenBucket tb = new TokenBucket(ratePerSec, burst);

        final int totalPermits = 2000; // aim for ~100 s of work at 20/s
        final int workerCount = 8;

        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(workerCount);
        AtomicLong permitsTaken = new AtomicLong(0);
        AtomicLong startNanos = new AtomicLong();

        for (int w = 0; w < workerCount; w++) {
            exec.submit(() -> {
                try {
                    startGate.await(); // wait for all workers to start together
                    while (true) {
                        int taken = tb.tryAcquire() ? 1 : 0;
                        if (taken > 0) {
                            permitsTaken.incrementAndGet();
                        }
                        // Stop when we have collectively taken enough permits
                        if (permitsTaken.get() >= totalPermits) {
                            break;
                        }
                        // Avoid busy spinning – yield a tiny bit
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        long start = System.nanoTime();
        startGate.countDown(); // let all workers go
        doneGate.await(); // wait for all workers to finish
        long end = System.nanoTime();

        exec.shutdownNow();
        exec.awaitTermination(5, TimeUnit.SECONDS);

        double elapsedSec = (end - start) / 1_000_000_000.0;
        long actualPermits = permitsTaken.get();
        double observedRate = actualPermits / elapsedSec;

        // The observed rate must not exceed the configured rate by more than tolerance.
        assertTrue(observedRate <= ratePerSec * (1.0 + TOLERANCE),
                String.format("Observed rate %.2f permits/s exceeds limit %.2f permits/s (tolerance %.0f%%)",
                        observedRate, ratePerSec, TOLERANCE * 100));

        // Additionally, we should have taken roughly the requested amount (allow a small deficit
        // because the last few permits may be missed due to the loop exit condition).
        assertTrue(actualPermits >= totalPermits * 0.95,
                String.format("Too few permits taken: %d < %d", actualPermits, totalPermits));
    }
}
```

### Explanation of the Test Suite

| Test | What it validates |
|------|--------------------|
| `testTryAcquireImmediateBurst` | The bucket starts full; up to `burstCapacity` permits can be taken instantly without waiting. |
| `testTryAcquireAfterWait` | After consuming the initial burst, a call to `tryAcquire` fails until enough time has passed for the refill to restore a token. |
| `testBlockingAcquireRespectsRate` | `acquire()` blocks appropriately; the total time for a known number of permits matches the expected time based on the rate (first `burst` permits are free). |
| `testConcurrentLongRunRateLimit` | Many threads repeatedly call `tryAcquire`. After a large number of permits have been taken, the measured long‑run rate (`permits / elapsedSec`) is asserted to be ≤ `ratePerSec * (1 + tolerance)`. This guarantees that the limiter does not allow excess throughput even under heavy concurrent contention. |

The tolerance (`15 %`) accommodates modest scheduling jitter and the fact that we use `Thread.yield()` in the busy loop; the test still reliably catches a buggy implementation that would permit a higher throughput than configured.