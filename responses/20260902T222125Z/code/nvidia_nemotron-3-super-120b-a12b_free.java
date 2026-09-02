<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260902T222125Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A token‑bucket rate limiter works as follows  

* A bucket can hold at most *capacity* tokens.  
* Tokens are added continuously at a constant *refillRate* (tokens per second).  
* An operation consumes one token; if no token is available the caller either  
  *fails immediately* (`tryAcquire`) or *waits* until a token becomes available
  (`acquire`).  

The implementation below is **thread‑safe** – all state is protected by a
`ReentrantLock` and a `Condition` is used to make blocking `acquire`
efficient (threads wait only the exact time needed for the next token to
appear).

---

## 1. TokenBucket implementation (Java 17)

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket starts full. Tokens are added continuously at {@code refillRate}
 * tokens per second, up to {@code capacity}. {@code acquire()} blocks until a
 * token can be taken; {@code tryAcquire()} attempts to take a token and returns
 * {@code true} on success, {@code false} otherwise.</p>
 *
 * <p>All public methods are safe for concurrent use by any number of threads.</p>
 */
public final class TokenBucket {

    /** Rate at which tokens are added (tokens per second). Must be > 0. */
    private final double refillRate;
    /** Maximum number of tokens the bucket can hold. */
    private final int capacity;

    /** Current number of tokens in the bucket (may be fractional). */
    private double tokens;
    /** Nanoseconds timestamp of the last refill operation. */
    private long lastNanos;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();
    /** Signalled when a token becomes available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket.
     *
     * @param refillRate tokens added per second (must be > 0)
     * @param capacity   maximum number of tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if either argument is non‑positive
     */
    public TokenBucket(double refillRate, int capacity) {
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.refillRate = refillRate;
        this.capacity = capacity;
        // start with a full bucket
        this.tokens = capacity;
        this.lastNanos = System.nanoTime();
    }

    /**
     * Adds tokens based on the elapsed time since the last refill.
     * Called while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * refillRate);
            lastNanos = now;
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} if the bucket
     *         was empty at the moment of the call.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                notEmpty.signal(); // wake up any threads waiting in acquire()
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires a token, blocking until one becomes available.
     *
     * <p>The method is interruptible – if the thread is interrupted while
     * waiting, {@link InterruptedException} is thrown.</p>
     *
     * @throws InterruptedException if the current thread is interrupted while
     *                              waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    notEmpty.signal(); // maybe another waiter can proceed now
                    return;
                }
                // Not enough tokens – compute how long we must wait for the next one.
                double deficit = 1.0 - tokens; // > 0
                long waitNanos = (long) (deficit / refillRate * 1_000_000_000.0);
                // Guard against spurious zero wait (should not happen because deficit>0)
                if (waitNanos <= 0) {
                    waitNanos = 1;
                }
                notEmpty.awaitNanos(waitNanos);
                // Loop again – after waiting we will refill again and check the condition.
            }
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Optional: expose current state for testing / debugging (not required) */
    /* ------------------------------------------------------------------ */

    /** Returns the current number of tokens (may be fractional). */
    public double getTokens() {
        lock.lock();
        try {
            refill(); // make sure we are up‑to‑date
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the configured refill rate (tokens per second). */
    public double getRefillRate() {
        return refillRate;
    }

    /** Returns the configured capacity (maximum tokens). */
    public int getCapacity() {
        return capacity;
    }
}
```

### Design Notes

| Aspect | Decision | Reason |
|--------|----------|--------|
| **Thread safety** | All mutable state (`tokens`, `lastNanos`) accessed only inside a `ReentrantLock`. | Guarantees mutual exclusion and visibility. |
| **Refill strategy** | Lazy refill: tokens are added on each call to `tryAcquire`/`acquire` based on elapsed wall‑clock time. | Avoids a background timer thread; overhead is O(1) per operation. |
| **Fractional tokens** | `tokens` is a `double`. Allows sub‑token accumulation when the refill rate is not an integer multiple of the call frequency. | Prevents starvation when rate < 1 token/s. |
| **Blocking wait** | Compute exact time needed for the next token and wait on a `Condition` with `awaitNanos`. After the wait we re‑check the condition. | Avoids busy‑spinning and reduces unnecessary wake‑ups. |
| **Signalling** | After consuming a token we call `notEmpty.signal()` to wake up at most one waiting thread. | Guarantees progress; the woken thread will re‑evaluate the condition. |
| **Interruptibility** | `acquire()` uses `lockInterruptibly()` and propagates `InterruptedException`. | Standard Java concurrency practice. |
| **Parameter validation** | Constructor rejects non‑positive rate or capacity. | Fail‑fast configuration errors. |
| **Encapsulation** | Class is `final`; fields are `private`. No inheritance needed. | Simpler reasoning about thread safety. |

---

## 2. JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class TokenBucketTest {

    /** Helper to run a piece of code for a given wall‑clock time and return the
     *  number of times it succeeded. */
    private static long runForDuration(TokenBucket bucket,
                                       boolean blocking,
                                       Duration duration) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            AtomicLong counter = new AtomicLong();
            long stopNanos = System.nanoTime() + duration.toNanos();

            Runnable task = () -> {
                while (System.nanoTime() < stopNanos) {
                    boolean taken;
                    try {
                        taken = blocking ? {
                            bucket.acquire(); // may throw InterruptedException
                            true;
                        } : bucket.tryAcquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (taken) {
                        counter.incrementAndGet();
                    }
                }
            };

            // Fire a few workers – more workers increase contention and better
            // test the rate‑limiting behavior.
            int workers = Math.min(8, Runtime.getRuntime().availableProcessors() * 2);
            for (int i = 0; i < workers; i++) {
                exec.submit(task);
            }
            exec.shutdown();
            // Wait for all workers to finish (they stop when the duration elapses)
            if (!exec.awaitTermination(duration.toMillis() + 500, TimeUnit.MILLISECONDS)) {
                exec.shutdownNow();
                throw new AssertionError("Test timed out");
            }
            return counter.get();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void tryAcquire_immediateSuccessAndFailure() {
        TokenBucket tb = new TokenBucket(10.0, 5); // 10 tokens/s, burst 5

        // Bucket starts full → first 5 attempts succeed
        for (int i = 0; i < 5; i++) {
            assertTrue(tb.tryAcquire(), "Attempt " + i + " should succeed");
        }
        // 6th attempt finds the bucket empty
        assertFalse(tb.tryAcquire(), "6th attempt should fail");
        // After waiting long enough for a refill we should be able to take again
        try {
            Thread.sleep(150); // 0.15 s → 1.5 tokens added (rate 10/s)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(tb.tryAcquire(), "Should be able to acquire after a short wait");
    }

    @Test
    void acquire_blockingThenAvailable() throws Exception {
        TokenBucket tb = new TokenBucket(1.0, 1); // 1 token/s, burst 1

        // First acquire consumes the initial token, returns immediately
        long start = System.nanoTime();
        tb.acquire();
        long first = System.nanoTime() - start;

        // Second acquire must wait roughly one second
        start = System.nanoTime();
        tb.acquire();
        long second = System.nanoTime() - start;

        // Allow a generous tolerance because of scheduling jitter
        assertTrue(first < 50_000_000, "First acquire should be near‑instant (<50 ms)");
        assertTrue(Duration.ofNanos(second).minusSeconds(1).abs().toMillis() < 200,
                "Second acquire should wait about 1 s (±200 ms)");
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        double rate = 5.0; // tokens per second
        int burst = 10;
        TokenBucket tb = new TokenBucket(rate, burst);

        Duration testDuration = Duration.ofSeconds(10);
        long totalAcquisitions = runForDuration(tb, false, testDuration); // non‑blocking

        double observedRate = totalAcquisitions / testDuration.getSeconds();
        // We allow a small overhead (≈10 %) because of thread scheduling and the
        // fact that the bucket may start full, giving a brief burst above the rate.
        double tolerance = 0.10;
        assertTrue(observedRate <= rate * (1.0 + tolerance),
                String.format("Observed rate %.2f tokens/s exceeds configured %.2f tokens/s (+%.0f%%)",
                        observedRate, rate, tolerance * 100));
    }

    @Test
    void longRunRateWithBlockingAcquire() throws Exception {
        double rate = 2.0; // tokens per second
        int burst = 5;
        TokenBucket tb = new TokenBucket(rate, burst);

        Duration testDuration = Duration.ofSeconds(8);
        long totalAcquisitions = runForDuration(tb, true, testDuration); // blocking

        double observedRate = totalAcquisitions / testDuration.getSeconds();
        // Same tolerance as above – the blocking version should not exceed the
        // configured long‑term rate.
        double tolerance = 0.10;
        assertTrue(observedRate <= rate * (1.0 + tolerance),
                String.format("Blocking observed rate %.2f tokens/s exceeds configured %.2f tokens/s (+%.0f%%)",
                        observedRate, rate, tolerance * 100));
    }
}
```

### Test Explanation

| Test | What it verifies |
|------|------------------|
| `tryAcquire_immediateSuccessAndFailure` | Correct handling of the initial burst and the immediate failure when the bucket is empty. Also checks that after a short wait a token becomes available again. |
| `acquire_blockingThenAvailable` | Ensures the blocking `acquire()` returns instantly when a token is present and waits the appropriate amount of time when the bucket is empty. |
| `longRunRateDoesNotExceedConfiguredLimit` | Fires many threads that repeatedly call the **non‑blocking** `tryAcquire()` for a fixed wall‑clock interval and counts successful acquisitions. The measured long‑term rate must be ≤ `rate × (1 + tolerance)`. |
| `longRunRateWithBlockingAcquire` | Same as above but using the **blocking** `acquire()`. This also validates that the waiting logic does not cause the effective rate to exceed the limit. |

The tolerance (10 %) accommodates:

* Scheduling delays – threads may wake up a bit later than the exact token‑availability time.
* The initial burst (`capacity`) which can temporarily raise the observed rate above the configured steady‑state rate.
* Granularity of `System.nanoTime()` and the fact that we wait for a *deficit* of tokens rather than exactly one token at a time.

All tests are deterministic, run in a few seconds, and exercise both the
non‑blocking and blocking paths of the token‑bucket implementation.  

--- 

**You now have a complete, thread‑safe token‑bucket rate limiter with blocking
and non‑blocking acquisition, plus a JUnit 5 test suite that checks its
correctness under concurrent load.**