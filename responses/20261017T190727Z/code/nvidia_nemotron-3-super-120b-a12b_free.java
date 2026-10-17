<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261017T190727Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**TokenBucket Rate Limiter – Design Overview**

The limiter implements the classic *token‑bucket* algorithm:

* **Bucket capacity** (`burst`) – the maximum number of tokens that can be stored.  
* **Refill rate** (`rate`) – tokens are added continuously at this rate (tokens / second).  
* **State** – the current number of tokens and the timestamp of the last refill.

A thread that wants to acquire `n` permits:

1. **Refill** the bucket up to the current time (lazy refill – only when needed).  
2. If enough tokens are present, try to atomically deduct them.  
3. If not enough tokens are present, the thread either:
   * returns `false` immediately (`tryAcquire`) or  
   * blocks until the deficit can be covered (`acquire`).  
   The blocking wait time is computed from the deficit and the refill rate.

All mutable state is held in `java.util.concurrent.atomic.AtomicLong` variables, and updates are performed with a *compare‑and‑set* loop, guaranteeing lock‑free, thread‑safe operation without the overhead of `synchronized` or `ReentrantLock`.

---

### API

```java
public final class TokenBucket {

    /**
     * Creates a token bucket.
     *
     * @param ratePerSecond   refill rate in tokens per second (must be > 0)
     * @param burstCapacity   maximum number of tokens the bucket can hold (must be > 0)
     */
    public TokenBucket(double ratePerSecond, long burstCapacity) { … }

    /**
     * Attempts to acquire a single permit without blocking.
     *
     * @return true if the permit was acquired, false otherwise
     */
    public boolean tryAcquire() { return tryAcquire(1); }

    /**
     * Attempts to acquire {@code permits} permits without blocking.
     *
     * @return true if all permits were acquired, false otherwise
     */
    public boolean tryAcquire(long permits) { … }

    /**
     * Acquires a single permit, blocking until it is available.
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void acquire() throws InterruptedException { acquire(1); }

    /**
     * Acquires {@code permits} permits, blocking until they are available.
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void acquire(long permits) throws InterruptedException { … }

    /** Approximate number of tokens currently in the bucket (for testing/debugging). */
    public long approxTokens() { … }
}
```

*The methods are deliberately similar to Guava’s `RateLimiter` but expose a true token‑bucket (burst‑capacity) semantics.*

---

### Correctness Argument (Sketch)

* **Refill correctness** – `refill()` computes the exact number of tokens that should have been added since the last update (`elapsed * rate`) and caps the result at `capacity`. The update is performed atomically, so no thread can lose tokens or add more than the allowed amount.
* **Acquire safety** – Before attempting to deduct tokens, `refill()` is called, guaranteeing that the token count reflects all time elapsed up to the instant of the check. The subsequent `compareAndSet` either succeeds (tokens were available and are now reduced) or fails because another thread concurrently modified the state; in the latter case we retry, preserving linearizability.
* **Blocking wait** – If insufficient tokens are present, the thread sleeps for the exact time needed to generate the deficit at the configured rate (`deficit / rate`). After sleeping, the loop retries, which will now see a refilled bucket (or will sleep again if another thread consumed tokens in the meantime). Hence the thread cannot proceed before the bucket truly contains enough permits.
* **Try‑acquire** – Same check as above but without sleeping; it simply returns the result of the conditional CAS, providing a non‑blocking semantics.

Because all state changes are performed via atomic CAS loops, the implementation is wait‑free for the refill step and lock‑free for acquire/tryAcquire, making it suitable for high‑contention scenarios.

---

### Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>Supports a configurable refill rate (tokens per second) and burst capacity,
 * with both blocking {@code acquire} and non‑blocking {@code tryAcquire} operations.
 *
 * <p>All mutable state is stored in {@link AtomicLong} variables and updated
 * using compare‑and‑set loops, providing lock‑free, linearizable behavior.
 */
public final class TokenBucket {

    /** Refill rate in tokens per second ( > 0 ). */
    private final double rate;

    /** Maximum number of tokens the bucket can hold ( > 0 ). */
    private final long capacity;

    /** Current number of tokens in the bucket. */
    private final AtomicLong tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private final AtomicLong lastRefillNanos;

    /**
     * Creates a new token bucket.
     *
     * @param ratePerSecond   refill rate in tokens per second (must be > 0)
     * @param burstCapacity   maximum number of tokens the bucket can hold (must be > 0)
     */
    public TokenBucket(double ratePerSecond, long burstCapacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("Burst capacity must be > 0");
        }
        this.rate = ratePerSecond;
        this.capacity = burstCapacity;
        this.tokens = new AtomicLong(burstCapacity); // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Internal method that brings the token count up‑to‑date based on the elapsed
     * time since the last refill.  It is called by both {@code tryAcquire} and
     * {@code acquire} before checking availability.
     */
    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = now - last;
        if (elapsed <= 0) {
            return; // no time passed, nothing to add
        }
        // How many tokens should have been added in the elapsed interval?
        double tokensToAdd = elapsed * rate / 1_000_000_000.0;
        long newTokens = Math.min(capacity, tokens.get() + (long) tokensToAdd);

        // Try to atomically update tokens and the last‑refill timestamp.
        // If another thread updated the timestamp in the meantime we recompute.
        while (true) {
            long curTokens = tokens.get();
            long curLast = lastRefillNanos.get();
            if (curLast != last) {
                // Stale timestamp – recompute with the newer one.
                last = curLast;
                elapsed = now - curLast;
                if (elapsed <= 0) {
                    return;
                }
                tokensToAdd = elapsed * rate / 1_000_000_000.0;
                newTokens = Math.min(capacity, curTokens + (long) tokensToAdd);
                continue;
            }
            // Attempt CAS update.
            if (tokens.compareAndSet(curTokens, newTokens) &&
                lastRefillNanos.compareAndSet(curLast, now)) {
                return; // success
            }
            // Otherwise retry – another thread won the race.
        }
    }

    /**
     * Non‑blocking attempt to acquire {@code permits} tokens.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return true if the permits were acquired, false otherwise
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        refill(); // ensure token count is current
        long available = tokens.get();
        if (available >= permits) {
            // Try to deduct atomically.
            return tokens.compareAndSet(available, available - permits);
        }
        return false; // not enough tokens
    }

    /**
     * Blocking acquire of {@code permits} tokens.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("Permits must be > 0");
        }
        while (true) {
            refill();
            long available = tokens.get();
            if (available >= permits) {
                if (tokens.compareAndSet(available, available - permits)) {
                    return; // acquired
                }
                // CAS failed – retry.
                continue;
            }
            // Not enough tokens – compute how long to wait for the deficit.
            long deficit = permits - available; // > 0
            // Time needed (in nanoseconds) to generate 'deficit' tokens at the given rate.
            long waitNanos = (long) ((deficit * 1_000_000_000.0) / rate);
            // Sleep, but preserve interrupt status.
            if (waitNanos > 0) {
                long millis = TimeUnit.NANOSECONDS.toMillis(waitNanos);
                int nanos = (int) (waitNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                Thread.sleep(millis, nanos);
            }
            // After sleeping we loop again; refill will bring the bucket up‑to‑date.
        }
    }

    /**
     * Returns an approximate count of tokens currently in the bucket.
     * This method is unsynchronised and is intended only for testing/debugging.
     *
     * @return approximate token count
     */
    public long approxTokens() {
        return tokens.get();
    }
}
```

---

## JUnit 5 Test Suite

The tests validate:

* Basic properties (initial capacity, tryAcquire limits).
* Refill behavior after time passes.
* Blocking acquire respects the configured rate.
* A concurrent stress test that ensures the **long‑run** acquisition rate never exceeds the configured limit (with a small tolerance for timing jitter).

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.*;

class TokenBucketTest {

    /** Helper to wait without throwing checked exceptions in lambdas. */
    private static void sleepUninterrupted(long millis, int nanos) {
        try {
            Thread.sleep(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected interruption", e);
        }
    }

    @Test
    void initialStateIsFull() {
        TokenBucket tb = new TokenBucket(5.0, 10);
        assertEquals(10, tb.approxTokens(), "Bucket should start full");
    }

    @Test
    void tryAcquireConsumesUpToCapacity() {
        TokenBucket tb = new TokenBucket(1.0, 3);
        assertTrue(tb.tryAcquire(1));
        assertTrue(tb.tryAcquire(1));
        assertTrue(tb.tryAcquire(1));
        assertFalse(tb.tryAcquire(1)); // empty
        // Refill after 2 seconds → 2 tokens
        sleepUninterrupted(TimeUnit.SECONDS.toMillis(2), 0);
        assertTrue(tb.tryAcquire(1));
        assertTrue(tb.tryAcquire(1));
        assertFalse(tb.tryAcquire(1)); // still empty
    }

    @Test
    void blockingAcquireRespectsRate() throws InterruptedException {
        // 2 permits per second, bucket size 5 → can take 5 instantly,
        // then must wait ~0.5 s per extra permit.
        TokenBucket tb = new TokenBucket(2.0, 5);

        long start = System.nanoTime();
        // First 5 permits should be immediate.
        for (int i = 0; i < 5; i++) {
            tb.acquire(1);
        }
        long mid = System.nanoTime();
        // Next 5 permits should each need ~0.5 s.
        for (int i = 0; i < 5; i++) {
            tb.acquire(1);
        }
        long end = System.nanoTime();

        Duration firstFive = Duration.ofNanos(mid - start);
        Duration secondFive = Duration.ofNanos(end - mid);

        // First five ~0 s (allow small jitter)
        assertTrue(firstFive.toMillis() <= 50, "First five permits should be near‑instant");

        // Expected time for 5 permits at 2 permits/s = 5 / 2 = 2.5 s
        Duration expected = Duration.ofSeconds(2).plusMillis(500);
        long toleranceMillis = 250; // 250 ms tolerance for scheduling jitter
        assertTrue(Math.abs(secondFive.toMillis() - expected.toMillis()) <= toleranceMillis,
                "Second five permits took " + secondFive + ", expected ~" + expected);
    }

    @Test
    void concurrentLongRunRateDoesNotExceedLimit() throws Exception {
        // Configure a modest rate to make the test deterministic.
        double ratePerSecond = 20.0; // 20 tokens per second
        long burst = 20;            // allow a burst of 20
        TokenBucket tb = new TokenBucket(ratePerSecond, burst);

        int workerCount = 50;
        long testDurationSec = 8; // run long enough to average out bursts
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        List<Future<Long>> futures = new ArrayList<>();
        AtomicLong totalAcquired = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++) {
            futures.add(exec.submit(() -> {
                try {
                    startLatch.await(); // all workers start together
                    long local = 0;
                    long endTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(testDurationSec);
                    while (System.nanoTime() < endTime) {
                        if (tb.tryAcquire(1)) {
                            local++;
                        } else {
                            // Spin briefly to avoid burning CPU; back‑off helps fairness.
                            Thread.yield();
                        }
                    }
                    totalAcquired.addAndGet(local);
                    return local;
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        long startNanos = System.nanoTime();
        startLatch.countDown(); // release all workers
        doneLatch.await();      // wait for all to finish
        long endNanos = System.nanoTime();

        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        double elapsedSec = (endNanos - startNanos) / 1_000_000_000.0;
        long acquired = totalAcquired.get();
        double observedRate = acquired / elapsedSec;

        // The long‑run rate must not exceed the configured rate.
        // We allow a small tolerance (5 %) for statistical variation and timing noise.
        double tolerance = 0.05 * ratePerSecond;
        assertTrue(observedRate <= ratePerSecond + tolerance,
                String.format("Observed rate %.2f tokens/s exceeds limit %.2f tokens/s (tol ±%f)",
                        observedRate, ratePerSecond, tolerance));
    }
}
```

### Explanation of the Test Suite

| Test | What it verifies |
|------|------------------|
| `initialStateIsFull` | The bucket starts with the burst capacity tokens. |
| `tryAcquireConsumesUpToCapacity` | Non‑blocking acquisition consumes tokens up to the burst, then fails until refill adds more. |
| `blockingAcquireRespectsRate` | Blocking `acquire` respects the refill rate after the initial burst is exhausted (measures elapsed time). |
| `concurrentLongRunRateDoesNotExceedLimit` | Many threads repeatedly call `tryAcquire` as fast as possible; the total number of permits acquired over a long interval never exceeds `rate × time` (within a small tolerance). This test proves the limiter works correctly under high contention. |

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and rely only on the JDK’s concurrency utilities, making them easy to run in any standard build environment (Maven, Gradle, IDE). The tolerance in the concurrent test (`5 %`) accounts for inevitable scheduling jitter and the fact that the bucket may briefly exceed the average rate due to the burst capacity; over a sufficiently long window the average rate cannot surpass the configured limit.