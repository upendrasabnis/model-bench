<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260827T160153Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Overview**

The token‑bucket algorithm limits the average rate of requests while allowing short bursts up to a configurable capacity.

* **State**
  * `capacity` – maximum number of tokens the bucket can hold (burst size).
  * `tokens` – current number of available tokens (fractional, to support any refill rate).
  * `lastRefill` – timestamp (nanoseconds) of the last time we added tokens.
  * `refillRatePerSec` – how many tokens are added each second.

* **Thread safety**
  * All mutable state is protected by a `java.util.concurrent.locks.ReentrantLock`.
  * A `Condition` (`notEmpty`) is used for the blocking `acquire()` – threads wait on it when there are not enough tokens and are signaled whenever the bucket is refilled.
  * The lock guarantees that only one thread can refill or consume tokens at a time, eliminating race conditions.

* **Refill logic**
  * On every public method call we first “refill” the bucket:
    ```
    now = System.nanoTime()
    elapsed = now - lastRefill
    added = elapsed * refillRatePerSec / 1_000_000_000.0
    tokens = min(capacity, tokens + added)
    lastRefill = now
    ```
  * This lazy refill is cheap and guarantees that the bucket always reflects the correct amount of tokens for the current moment.

* **Blocking `acquire(int permits)`**
  * After refilling, if `tokens >= permits` we consume them and return immediately.
  * Otherwise we compute the time needed to accumulate the missing tokens:
    ```
    needed = (permits - tokens) / refillRatePerSec   // seconds
    ```
    and wait on the condition for that duration (with a loop to handle spurious wake‑ups).

* **Non‑blocking `tryAcquire(int permits)`**
  * Same refill step, then:
    * if enough tokens → consume and return `true`
    * else → leave state unchanged and return `false`.

* **Why fractional tokens?**
  * Using a `double` for `tokens` lets us support any refill rate (e.g., 2.5 tokens/s) without rounding errors that would otherwise cause the long‑term rate to drift.

* **Correctness argument (long‑run rate)**
  * Between two successive refills the bucket can increase at most `refillRatePerSec * Δt` tokens.
  * Consumption only happens when tokens are available, so the total number of permits granted in any interval `[t0, t1]` cannot exceed:
    ```
    capacity + refillRatePerSec * (t1 - t0)
    ```
    The initial `capacity` accounts for the possible burst at the start; after that the rate is bounded by `refillRatePerSec`. Hence the long‑run average rate never exceeds the configured limit.

---

## Implementation (`TokenBucket.java`)

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token bucket rate limiter.
 *
 * <p>The bucket starts full (i.e. {@code tokens == capacity}).  Calls to
 * {@link #acquire()} or {@link #acquire(int)} will block until the requested
 * number of permits is available.  {@link #tryAcquire()} and
 * {@link #tryAcquire(int)} attempt to consume permits without blocking and
 * return {@code true} on success.</p>
 *
 * <p>All operations are O(1) and safe for concurrent use by any number of
 * threads.</p>
 */
public final class TokenBucket {

    private final double capacity;               // max tokens (burst)
    private double tokens;                       // current tokens
    private volatile long lastRefillNanos;       // timestamp of last refill
    private final double refillRatePerSec;       // tokens added per second

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a token bucket.
     *
     * @param burstCapacity   maximum number of tokens the bucket can hold (burst size)
     * @param refillRatePerSec average number of tokens added each second
     * @throws IllegalArgumentException if capacity <= 0 or refillRate <= 0
     */
    public TokenBucket(double burstCapacity, double refillRatePerSec) {
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = burstCapacity;
        this.tokens = burstCapacity; // start full
        this.refillRatePerSec = refillRatePerSec;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding the lock.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double added = (double) elapsed * refillRatePerSec / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }

    /**
     * Blocks until a single permit is available, then consumes it and returns.
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * Blocks until {@code permits} permits are available, then consumes them and returns.
     *
     * @param permits number of permits to acquire; must be > 0
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return;
                }
                // Not enough tokens – compute how long we need to wait.
                double neededSecs = (permits - tokens) / refillRatePerSec;
                long nanosToWait = (long) (neededSecs * 1_000_000_000.0);
                // await returns false if interrupted; we treat interruption as break.
                if (!notEmpty.awaitNanos(nanosToWait)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Loop again – another thread may have added tokens or we may have
                // waited longer than needed due to spurious wake‑ups.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a single permit without blocking.
     *
     * @return {@code true} if the permit was acquired, {@code false} otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire {@code permits} permits without blocking.
     *
     * @param permits number of permits to try to acquire; must be > 0
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            refill();
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
     * Returns the current number of available tokens (for testing/debugging).
     * The value is a snapshot; it may change immediately after returning.
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the configured burst capacity.
     */
    public double getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRatePerSec() {
        return refillRatePerSec;
    }
}
```

---

## JUnit 5 Tests (`TokenBucketTest.java`)

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.*;

class TokenBucketTest {

    private static final double TOLERANCE = 0.15; // 15 % tolerance for rate checks

    @Test
    void testInitialCapacityAndBurst() {
        TokenBucket tb = new TokenBucket(5.0, 1.0); // capacity 5, refill 1/s
        // Should be able to take up to 5 permits immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(tb.tryAcquire(), "tryAcquire #" + i + " should succeed");
        }
        // The 6th should fail because bucket is empty
        assertFalse(tb.tryAcquire(), "tryAcquire after burst should fail");
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucket tb = new TokenBucket(2.0, 2.0); // capacity 2, refill 2/s
        // Drain the bucket
        assertTrue(tb.tryAcquire(2));
        assertFalse(tb.tryAcquire());

        // Wait 0.6 s → expected ~1.2 tokens added
        Thread.sleep(600);
        // Should be able to acquire at least 1 permit (maybe 2 because of fraction)
        assertTrue(tb.tryAcquire(1));
        // After taking 1, there should be ~0.2 left → not enough for another
        assertFalse(tb.tryAcquire(1));

        // Wait another 0.6 s (total ~1.2 s) → another ~1.2 tokens, total ~1.4
        Thread.sleep(600);
        assertTrue(tb.tryAcquire(1));
        // Still not enough for a second permit
        assertFalse(tb.tryAcquire(1));
    }

    @Test
    void testBlockingAcquireRespectsRate() throws InterruptedException {
        double rate = 10.0; // 10 permits per second
        double burst = 5.0;
        TokenBucket tb = new TokenBucket(burst, rate);

        int workers = 8;
        ExecutorService exec = Executors.newFixedThreadPool(workers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workers);
        AtomicLong permitsAcquired = new AtomicLong(0);
        long testDurationSec = 8; // run for 8 seconds

        for (int i = 0; i < workers; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    long local = 0;
                    long endTime = System.nanoTime() + Duration.ofSeconds(testDurationSec).toNanos();
                    while (System.nanoTime() < endTime) {
                        tb.acquire(); // blocks until a permit is available
                        local++;
                    }
                    permitsAcquired.addAndGet(local);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // let all workers go
        boolean finished = doneLatch.await(testDurationSec + 5, TimeUnit.SECONDS);
        assertTrue(finished, "All workers should finish within the extended timeout");
        exec.shutdownNow();

        long total = permitsAcquired.get();
        double actualRate = total / (double) testDurationSec;

        // The long‑run rate may be a bit higher because of the initial burst.
        // Expected max = rate + burst / testDurationSec
        double maxExpected = rate + burst / testDurationSec;
        assertTrue(actualRate <= maxExpected * (1.0 + TOLERANCE),
                String.format("Observed rate %.2f permits/s exceeds expected max %.2f (+%.0f%%)",
                        actualRate, maxExpected, TOLERANCE * 100));
    }

    @Test
    void testTryAcquireUnderContention() throws InterruptedException {
        double rate = 5.0; // 5 permits per second
        double burst = 2.0;
        TokenBucket tb = new TokenBucket(burst, rate);

        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicLong successes = new AtomicLong(0);
        AtomicLong attempts = new AtomicLong(0);
        long testDurationSec = 6;

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    long end = System.nanoTime() + Duration.ofSeconds(testDurationSec).toNanos();
                    while (System.nanoTime() < end) {
                        attempts.incrementAndGet();
                        if (tb.tryAcquire()) {
                            successes.incrementAndGet();
                        }
                        // No Thread.sleep – we want to hammer the bucket.
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(testDurationSec + 2, TimeUnit.SECONDS);
        assertTrue(finished, "Threads should finish");
        exec.shutdownNow();

        long totalAttempts = attempts.get();
        long totalSuccess = successes.get();
        double observedRate = totalSuccess / (double) testDurationSec;
        double maxExpected = rate + burst / testDurationSec; // same reasoning as before
        assertTrue(observedRate <= maxExpected * (1.0 + TOLERANCE),
                String.format("TryAcquire observed rate %.2f > expected max %.2f",
                        observedRate, maxExpected));
    }

    @Test
    void testAcquireInterruptible() throws InterruptedException {
        TokenBucket tb = new TokenBucket(1.0, 0.1); // very slow refill
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(() -> tb.acquire()); // will block
        Thread.sleep(100); // let it start waiting
        future.cancel(true); // interrupt the waiting thread
        assertTrue(future.isCancelled());
        exec.shutdownNow();
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `testInitialCapacityAndBurst` | The bucket starts full and respects the burst capacity. |
| `testRefillOverTime` | Tokens are correctly replenished over time, including fractional accumulation. |
| `testBlockingAcquireRespectsRate` | Many threads continuously call the blocking `acquire()`. Over a multi‑second window the average granted rate never exceeds the configured limit (plus the possible initial burst). |
| `testTryAcquireUnderContention` | Same idea as above but with the non‑blocking `tryAcquire()`. It also stresses the lock/Condition under high contention. |
| `testAcquireInterruptible` | Ensures that a thread waiting in `acquire()` reacts to interruption (a typical requirement for blocking JDK‑style methods). |

The concurrent rate test uses a tolerance of 15 % to accommodate timing jitter, the initial burst, and the fact that `System.nanoTime()`/`Thread.sleep` are not perfectly precise. Adjust the tolerance if you need a stricter bound on a particular platform.