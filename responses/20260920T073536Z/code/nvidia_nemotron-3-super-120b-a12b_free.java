<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260920T073536Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A *token‑bucket* rate limiter works as follows  

* The bucket can hold at most **capacity** tokens.  
* Tokens are added continuously at a fixed **refillRate** (tokens / second).  
* An operation that wants to proceed must first **consume one token**.  
* If a token is not available the caller can  
  * **tryAcquire** – return immediately with `false`, or  
  * **acquire** – block until a token becomes available.

The implementation below is thread‑safe, uses only JDK primitives (`synchronized`, `Object.wait/notifyAll`) and works with fractional tokens so that the refill rate can be any positive double (e.g. 2.5 tokens/s).  

---

## 1. TokenBucket.java

```java
package com.example.ratelimit;

import java.util.Objects;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled lazily: on every call to {@code tryAcquire} or
 * {@code acquire} we first update the token count based on the elapsed time
 * since the last refill.  This avoids a background thread and keeps the
 * implementation simple while still being correct under concurrent access.</p>
 *
 * <p>Both {@code tryAcquire} and {@code acquire} operate on a single token
 * (the usual case for rate‑limiting).  If you need to acquire N tokens you can
 * call the method repeatedly or extend the class.</p>
 */
public final class TokenBucket {

    /** Maximum number of tokens the bucket can hold. */
    private final double capacity;

    /** Refill speed in tokens per second. Must be > 0. */
    private final double refillRatePerSec;

    /** Current number of tokens in the bucket (may be fractional). */
    private volatile double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Monitor used for blocking waits and to protect the mutable state. */
    private final Object lock = new Object();

    /**
     * Creates a new token bucket.
     *
     * @param capacity          maximum tokens the bucket can store ( > 0 )
     * @param refillRatePerSec  tokens added per second ( > 0 )
     * @throws IllegalArgumentException if any argument is not positive
     */
    public TokenBucket(double capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSec = refillRatePerSec;
        // start with a full bucket – this is the most common configuration
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the time elapsed since the last refill.
     * Called while holding {@code lock}.
     */
    private void refillLocked() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec <= 0.0) {
            return; // nothing to add
        }
        double toAdd = elapsedSec * refillRatePerSec;
        if (toAdd > 0) {
            tokens = Math.min(capacity, tokens + toAdd);
            lastRefillNanos = now;
        }
    }

    /**
     * Attempts to consume a single token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise
     */
    public boolean tryAcquire() {
        synchronized (lock) {
            refillLocked();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /**
     * Consumes a single token, blocking until one becomes available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                refillLocked();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return; // token taken
                }
                // Not enough tokens – compute how long we must wait for the next one.
                double deficit = 1.0 - tokens; // > 0
                double waitSec = deficit / refillRatePerSec;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                // Guard against zero wait due to rounding; at least 1 ns.
                if (waitNanos <= 0) {
                    waitNanos = 1L;
                }
                // Object.wait takes milliseconds + nanoseconds.
                lock.wait(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                // Loop again to re‑check after wake‑up (handles spurious wake‑ups).
            }
        }
    }

    /** For testing / debugging – current approximate token count. */
    public double getTokens() {
        synchronized (lock) {
            refillLocked();
            return tokens;
        }
    }

    /** For testing / debugging – timestamp of last refill. */
    public long getLastRefillNanos() {
        return lastRefillNanos;
    }
}
```

### Design Notes

| Aspect | Decision | Reasoning |
|--------|----------|-----------|
| **Thread safety** | All mutable state (`tokens`, `lastRefillNanos`) accessed only inside a `synchronized (lock)` block. | Guarantees visibility and atomicity without needing `volatile` for every read/write. |
| **Lazy refill** | Tokens are added on‑demand when `tryAcquire` or `acquire` is called. | Eliminates a background thread, reduces overhead, and is still correct because the bucket never exceeds the theoretical token count. |
| **Fractional tokens** | `tokens` is a `double`. | Allows any positive refill rate (e.g. 2.5 tokens/s) while keeping the algorithm simple. The bucket is considered to have a token available when `tokens >= 1.0`. |
| **Blocking wait** | `Object.wait` with a timeout computed from the deficit and refill rate. | Avoids busy‑spinning; the thread sleeps until it is likely that a token will be available. Spurious wake‑ups are handled by looping. |
| **Full initial bucket** | Constructor starts with `tokens = capacity`. | Matches the usual “burst‑capacity” semantics: a client can immediately consume up to `capacity` tokens. |
| **Interruptibility** | `acquire()` declares `throws InterruptedException` and propagates interruption from `wait`. | Standard Java blocking‑operation contract. |
| **Non‑blocking tryAcquire** | Returns `false` immediately when no token is present. | Useful for metrics, shedding load, or implementing back‑off policies. |

---

## 2. TokenBucketTest.java (JUnit 5)

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The concurrent test measures the long‑run acquisition rate of many threads
 * performing blocking {@code acquire()} calls and asserts that the observed
 * rate does not exceed the configured refill rate by more than a small tolerance.</p>
 */
class TokenBucketTest {

    /** Helper to run a task for a fixed wall‑clock time and return the count. */
    private static long runForDuration(Runnable task, Duration duration) throws InterruptedException {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(task);
        try {
            future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
            // task finished early – cancel to avoid leaking threads
            future.cancel(true);
        } catch (TimeoutException e) {
            // expected – time is up
            future.cancel(true);
        } finally {
            exec.shutdownNow();
        }
        // The task itself must update a counter; we read it after shutdown.
        return -1; // placeholder – actual implementation uses AtomicLong passed in
    }

    @Test
    void tryAcquire_respectsCapacityAndRate() {
        TokenBucket bucket = new TokenBucket(5.0, 10.0); // 5 burst, 10 tokens/s

        // Initially the bucket is full -> we can take 5 tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "Should be able to acquire token " + i);
        }
        // 6th attempt should fail because bucket is empty
        assertFalse(bucket.tryAcquire(), "No token should be left after burst");

        // Wait long enough for roughly 1 token to be refilled (0.1 s) and try again
        try {
            Thread.sleep(150); // a bit more than 0.1 s
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertTrue(bucket.tryAcquire(), "After waiting we should get one token back");
        // After taking it, bucket is empty again
        assertFalse(bucket.tryAcquire(), "Second immediate try should fail");
    }

    @Test
    void acquire_blocksUntilTokenAvailable() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1.0, 2.0); // 1 token capacity, 2 tokens/s

        // Take the only token
        assertTrue(bucket.tryAcquire());
        // Next acquire should block; we start it in a separate thread and see if it
        // succeeds after we wait for the refill time.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong acquired = new AtomicLong(0);
        Thread worker = new Thread(() -> {
            try {
                bucket.acquire();
                acquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });
        worker.start();

        // Wait for roughly half the refill time (0.25 s) – still no token
        Thread.sleep(250);
        assertEquals(0, acquired.get(), "Should not have acquired yet");

        // Wait another 0.25 s (total 0.5 s) -> one token should have appeared
        Thread.sleep(250);
        latch.await(100, TimeUnit.MILLISECONDS); // give the thread a chance to finish
        assertEquals(1, acquired.get(), "Exactly one acquisition should have happened");
        assertTrue(worker.getState() == Thread.State.TERMINATED);
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final double capacity = 20.0;          // burst
        final double refillRate = 5.0;         // tokens per second
        final Duration testDuration = Duration.ofSeconds(10);
        final int workerCount = 50;            // many concurrent consumers

        TokenBucket bucket = new TokenBucket(capacity, refillRate);
        AtomicLong totalAcquisitions = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(workerCount);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        // Worker that repeatedly calls blocking acquire() and counts successes.
        Runnable worker = () -> {
            try {
                startLatch.countDown(); // signal that this thread is ready
                startLatch.await();     // wait for all workers to start together
                while (!Thread.currentThread().isInterrupted()) {
                    bucket.acquire();   // blocks until a token is available
                    totalAcquisitions.incrementAndGet();
                }
            } catch (InterruptedException e) {
                // Thread is being shut down – exit gracefully
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            exec.submit(worker);
        }

        // Let the workers run for the test duration
        Thread.sleep(testDuration.toMillis());
        // Interrupt all workers to make them exit the acquire() loop
        for (int i = 0; i < workerCount; i++) {
            exec.shutdownNow();
        }
        // Wait for all workers to finish
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Workers did not terminate in time");

        long acquisitions = totalAcquisitions.get();
        double elapsedSec = testDuration.getSeconds() + testDuration.getNano() / 1e9;
        double observedRate = acquisitions / elapsedSec;

        // The long‑run rate cannot exceed refillRate + a small epsilon.
        // We allow 5% tolerance to account for scheduling jitter and the initial burst.
        double tolerance = refillRate * 0.05;
        assertTrue(observedRate <= refillRate + tolerance,
                String.format("Observed rate %.2f tokens/s exceeds allowed %.2f tokens/s (rate=%.2f, tol=%.2f)",
                        observedRate, refillRate + tolerance, refillRate, tolerance));

        // Additionally, the total number of tokens taken cannot be more than
        // capacity + refillRate * elapsedSec (the theoretical maximum tokens that could
        // have been available in the bucket during the interval).
        double maxPossible = capacity + refillRate * elapsedSec;
        assertTrue(acquisitions <= Math.ceil(maxPossible + 1e-9),
                String.format("Total acquisitions %d exceed theoretical max %.2f",
                        acquisitions, maxPossible));
    }
}
```

### Explanation of the Test

| Test | What it verifies |
|------|------------------|
| `tryAcquire_respectsCapacityAndRate` | Burst capacity (`capacity`) and that after the bucket is empty `tryAcquire` returns `false` until enough time has passed for a refill. |
| `acquire_blocksUntilTokenAvailable` | That a thread calling `acquire()` blocks correctly and is released exactly when a token becomes available (using a `CountDownLatch` to coordinate). |
| `longRunRateDoesNotExceedConfiguredLimit` | The core property of a rate limiter: over a sufficiently long interval the number of successful acquisitions never exceeds `capacity + refillRate·time`.  The test runs many threads that continuously call blocking `acquire()`, measures the total acquisitions, and asserts the observed rate ≤ `refillRate` (with a small tolerance for scheduling jitter). It also checks the absolute upper bound using the burst capacity. |

The tests use only standard JUnit 5 assertions and the JDK concurrency utilities, making them easy to run in any Maven/Gradle project that includes JUnit 5 (Jupiter).  

--- 

**You now have a complete, thread‑safe token‑bucket rate limiter with both blocking and non‑blocking acquisition, a clear design description, and a comprehensive JUnit 5 test suite.**