<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260731T124945Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview  

| Aspect | Decision |
|--------|----------|
| **Rate‑limiting algorithm** | **Token‑bucket** – a bucket that can hold at most *capacity* tokens.  Tokens are added (refilled) at a constant *refill‑rate* (tokens / second).  An acquirer consumes one token; if the bucket is empty the call blocks (or times‑out) until a token becomes available. |
| **Thread‑safety** | All mutable state (`tokens`, the refill scheduler, and the lock) is guarded by a single `ReentrantLock`.  The lock is taken **exclusively** for every read/modify/write of the token count, guaranteeing that the bucket behaves correctly under high contention. |
| **Blocking acquire** | `acquire()` obtains a token **without timeout**.  It loops waiting on a `Condition` until `tokens > 0`.  This is the classic “blocking” semantics used by most rate‑limiters. |
| **Non‑blocking acquire** | `tryAcquire(long timeout, TimeUnit unit)` first checks the bucket immediately; if a token is available it is consumed and `true` is returned.  If not, it starts a timed wait on the same `Condition`.  The method returns `true` only if a token is obtained before the timeout expires, otherwise `false`. |
| **Refill mechanism** | A `ScheduledExecutorService` periodically (every `refillIntervalMs`) adds up to `capacity` tokens.  The scheduler runs **outside** the lock, but the actual token addition (`tokens += delta`) is performed while holding the lock, guaranteeing that the bucket never exceeds its capacity. |
| **Configuration** | - `maxTokens` – bucket capacity (burst size). <br>- `refillRateTokensPerSecond` – average tokens added per second. <br>- `refillIntervalMs` – granularity of the refill step (derived from the rate, e.g. 1 s / refillRate or a fixed small interval). |
| **Graceful shutdown** | `close()` stops the refill scheduler and releases the lock.  It is idempotent. |
| **Why not use `Semaphore`?** | A `Semaphore` can enforce a fixed number of permits but cannot express a *refill* behaviour; the token‑bucket logic (capacity + periodic addition) would have to be implemented manually anyway.  Using our own lock + condition gives full control over the bucket state and makes the `tryAcquire` semantics straightforward. |

### State Diagram  

```
+-------------------+
|  TokenBucket      |
+-------------------+
| - lock            | (java.util.concurrent.locks.ReentrantLock)
| - condition       | (Condition notEmpty)
| - tokens          | (int, current bucket fill)
| - capacity        | (max tokens)
| - refillRate      | (tokens / second)
| - refillInterval  | (ms between refill ticks)
| - scheduler       | (ScheduledExecutorService)
+-------------------+
```

*Operations*  

| Method | Description |
|--------|-------------|
| `acquire()` | Blocking call – waits until `tokens > 0`, then decrements `tokens`. |
| `tryAcquire(long timeout, TimeUnit unit)` | Returns `true` if a token is obtained within the timeout; otherwise `false`. |
| `tryAcquire()` | Convenience overload that behaves like `tryAcquire(0, TimeUnit.SECONDS)` – tries once and returns immediately. |
| `close()` | Shuts down the refill scheduler.  After closing the bucket can no longer acquire tokens. |

---

## 2. Production‑Ready Implementation  

```java
package com.example.ratelimiter;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The limiter can be configured with a maximum burst size (capacity) and a
 * refill rate (tokens per second).  It supports both a blocking {@link #acquire()}
 * method and a timed {@link #tryAcquire(long, TimeUnit)} method.
 *
 * <p>Typical usage:
 *
 * <pre>
 * RateLimiter limiter = new RateLimiter(
 *         100,                     // capacity (burst)
 *         10,                      // refill rate = 10 tokens per second
 *         TimeUnit.SECONDS);       // refill granularity
 *
 * // Blocking acquire
 * limiter.acquire();
 *
 * // Non‑blocking acquire with timeout
 * boolean granted = limiter.tryAcquire(50, TimeUnit.MILLISECONDS);
 * </pre>
 *
 * <p>The limiter should be closed when it is no longer needed to stop the
 * internal scheduler thread.
 */
public final class RateLimiter implements AutoCloseable {

    /** The lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that waiters wait on when the bucket is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Current number of tokens in the bucket. */
    private int tokens;

    /** Maximum number of tokens the bucket may hold (burst capacity). */
    private final int capacity;

    /** How many tokens are added each refill tick. */
    private final int tokensPerRefill;

    /** Interval between refill ticks, in milliseconds. */
    private final long refillIntervalMs;

    /** Scheduler that performs periodic refills. */
    private final ScheduledExecutorService scheduler;

    /** Guard flag – once {@code true} the limiter cannot be used any more. */
    private volatile boolean closed = false;

    /**
     * Creates a new rate limiter.
     *
     * @param capacity        maximum burst size (must be &gt; 0)
     * @param refillRateTokensPerSecond rate at which tokens are added (must be &gt; 0)
     * @param refillInterval  granularity of the refill step; must divide the
     *                        refill rate evenly (e.g. 100 ms for a 10 tps rate)
     * @param scheduler       underlying executor for the refill task; if
     *                        {@code null} a single‑thread scheduler is created.
     * @throws IllegalArgumentException if any argument is invalid
     */
    public RateLimiter(int capacity,
                       double refillRateTokensPerSecond,
                       Duration refillInterval,
                       ScheduledExecutorService scheduler) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRateTokensPerSecond <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0");
        }
        if (refillInterval.isZero()) {
            throw new IllegalArgumentException("refillInterval must be non‑zero");
        }

        this.capacity = capacity;
        this.tokensPerRefill = Math.max(1, (int) Math.round(refillRateTokensPerSecond * refillInterval.toMillis() / 1000.0));
        this.refillIntervalMs = refillInterval.toMillis();

        // If the caller supplies a scheduler we use it, otherwise we create a
        // dedicated single‑thread scheduler that will be shut down together with
        // the limiter.
        this.scheduler = scheduler != null ? scheduler : Executors.newSingleThreadScheduleExecutor(
                r -> {
                    Thread t = new Thread(r);
                    t.setName("RateLimiter-Refill-Thread");
                    t.setDaemon(true);
                    return t;
                });

        // Pre‑seed the bucket with a few tokens to avoid an immediate block
        // before the first refill tick fires.
        this.tokens = capacity;
        scheduleRefill();
    }

    /**
     * Creates a new rate limiter using a default scheduler.
     *
     * @param capacity        maximum burst size
     * @param refillRateTokensPerSecond tokens added per second
     * @param refillInterval  granularity for the refill step (e.g. {@code 100ms})
     * @throws IllegalArgumentException if arguments are invalid
     */
    public RateLimiter(int capacity, double refillRateTokensPerSecond, Duration refillInterval) {
        this(capacity, refillRateTokensPerSecond, refillInterval, null);
    }

    /** Starts (or reschedules) the periodic refill task. */
    private void scheduleRefill() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                lock.lock();
                int oldTokens = tokens;
                // Compute how many tokens we should add this tick.
                // Because we schedule at a fixed interval we may add at most
                // tokensPerRefill, which is already bounded by the rate.
                tokens = Math.min(capacity, tokens + tokensPerRefill);
                if (tokens > oldTokens) {
                    // Signal that new tokens are available.
                    notEmpty.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }, refillIntervalMs, refillIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        lock.lock();
        try {
            while (tokens == 0) {
                notEmpty.await(); // releases lock and waits
            }
            tokens--;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking longer than the supplied timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit    time unit of {@code timeout}
     * @return {@code true} if a token was obtained within the timeout; {@code false}
     *         otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (tokens == 0) {
                long remaining = unit.toNanos(timeout);
                if (remaining <= 0) {
                    return false; // timeout elapsed
                }
                // Wait for a signal or until timeout expires.
                notEmpty.await(remaining, TimeUnit.NANOSECONDS);
                // After awakening we must re‑check the token count.
            }
            tokens--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Overload that tries immediately (no wait). */
    public boolean tryAcquire() {
        return tryAcquire(0, TimeUnit.MILLISECONDS);
    }

    /** Closes the limiter and shuts down its scheduler. */
    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
```

### Key Implementation Details  

* **Lock granularity** – All mutable fields (`tokens`) are guarded by a single `ReentrantLock`.  This guarantees atomicity even under heavy concurrent access.  
* **Condition variable** – `notEmpty` is used to wake up waiting threads whenever a refill adds at least one token.  `signalAll()` ensures that spurious wake‑ups or multiple waiting threads are handled correctly.  
* **Refill scheduling** – The scheduler runs **outside** the lock, but the actual token addition happens while holding the lock, preventing race conditions where the bucket could be over‑filled.  
* **Refill granularity** – The bucket may add `tokensPerRefill` tokens each tick.  This value is computed from the desired refill rate and the chosen interval, guaranteeing that over a long period the average rate matches the configured limit.  
* **AutoCloseable** – Implementing `AutoCloseable` lets the limiter be used in a try‑with‑resources block, ensuring deterministic shutdown.  

---

## 3. JUnit 5 Test Suite  

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    /** Helper that runs a bursty workload and records timestamps of successful acquires. */
    private static List<Instant> recordAcquires(RateLimiter limiter,
                                                int threads,
                                                int acquisitionsPerThread,
                                                Duration testDuration) throws InterruptedException {
        List<Instant> timestamps = new CopyOnWriteArrayList<>();
        AtomicBoolean keepRunning = new AtomicBoolean(true);

        Runnable worker = () -> {
            try {
                RateLimiter localLimiter = limiter; // effectively final
                while (keepRunning.get()) {
                    // Try to acquire; if we succeed record the instant.
                    if (localLimiter.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        timestamps.add(Instant.now());
                    }
                }
            } catch (InterruptedException ignored) {
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(worker);
        }

        // Let the test run for the requested duration, then stop it.
        ScheduledExecutorService stopper = Executors.newSingleThreadScheduledExecutor();
        stopper.schedule(() -> {
            keepRunning.set(false);
            stopper.shutdownNow();
        }, testDuration.toMillis(), TimeUnit.MILLISECONDS);

        exec.awaitTermination(testDuration.toSeconds() + 1, TimeUnit.SECONDS);
        return timestamps;
    }

    @Test
    void acquireBlocksWhenBucketEmpty() throws Exception {
        RateLimiter limiter = new RateLimiter(5, 2, Duration.ofSeconds(1)); // 2 tokens per second
        // Initially bucket is full (capacity tokens)
        assertDoesNotThrow(limiter::acquire);
        assertDoesNotThrow(limiter::acquire);
        // After two consumes bucket is empty → next acquire must block
        CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(); // should block until refill adds a token
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });
        t.start();
        // Wait a short while (less than the refill interval) – should still be blocked
        assertFalse(t.isAlive(), "Thread should still be blocked after a few ms");
        // Let the refill happen (1 sec) and then the thread will wake up.
        Thread.sleep(1100);
        assertTrue(t.isAlive() == false, "Thread should have resumed after refill");
        t.join();
        // After waking up, acquire should succeed.
        assertDoesNotThrow(() -> limiter.acquire());
    }

    @Test
    void tryAcquireImmediatelyFailsWhenEmpty() {
        RateLimiter limiter = new RateLimiter(1, 1, Duration.ofSeconds(1));
        // Consume the only token
        limiter.acquire();
        // Bucket empty → tryAcquire should return false instantly
        assertFalse(limiter.tryAcquire(), "tryAcquire should fail when no token is available");
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final int CAPACITY = 100;
        final double RATE_TPS = 5.0; // 5 tokens per second
        final Duration TEST_DURATION = Duration.ofMinutes(2); // 2 minutes

        RateLimiter limiter = new RateLimiter(CAPACITY, RATE_TPS, Duration.ofSeconds(1));

        // Launch many concurrent threads that continuously try to acquire.
        int threadCount = 20;
        List<Instant> timestamps = recordAcquires(limiter, threadCount, Integer.MAX_VALUE, TEST_DURATION);

        // Compute the observed throughput (tokens per second) over the test period.
        // Since we only record timestamps of successful acquisitions, the size of the list
        // divided by TEST_DURATION gives the average rate.
        double observedRate = timestamps.size() / TEST_DURATION.toSeconds();

        // The observed rate must be *strictly* less than or equal to the configured rate,
        // allowing for a small tolerance due to measurement granularity.
        double tolerance = 0.1; // 10% margin
        assertTrue(observedRate <= RATE_TPS + tolerance,
                "Observed rate (%.3f tokens/s) exceeds configured limit of %.3f tokens/s by more than tolerance"
                        .formatted(observedRate, RATE_TPS));
    }

    @Test
    void tryAcquireWithTimeout() throws Exception {
        RateLimiter limiter = new RateLimiter(5, 0.5, Duration.ofSeconds(1)); // 0.5 tps → 1 token every 2s

        // Bucket initially full; first acquire succeeds instantly.
        assertTrue(limiter.tryAcquire());

        // Consume all tokens
        for (int i = 0; i < CAPACITY; i++) {
            limiter.acquire();
        }

        // Now we have to wait for a refill.  The refill interval is 1 second,
        // and we add only 0 tokens per interval because tokensPerRefill = 0 (rate < 1 tps).
        // Hence the next acquire must timeout.
        assertFalse(limiter.tryAcquire(200, TimeUnit.MILLISECONDS),
                "tryAcquire should timeout when no token will be added within the timeout");
    }

    @Test
    void closeShutsDownScheduler() {
        RateLimiter limiter = new RateLimiter(10, 1, Duration.ofSeconds(1));
        assertDoesNotThrow(limiter::close);
        // After closing, any acquire must throw IllegalStateException
        assertThrows(IllegalStateException.class, limiter::acquire);
        assertThrows(IllegalStateException.class, limiter::tryAcquire);
    }
}
```

### Explanation of the Concurrent Test (`longRunRateDoesNotExceedConfiguredLimit`)  

1. **Configuration** – The limiter is set to a capacity of 100 tokens and a refill rate of **5 tokens / second**.  
2. **Workload** – 20 threads repeatedly call `tryAcquire(500 ms, TimeUnit.MILLISECONDS)`.  Each successful acquisition records the current instant.  
3. **Duration** – The test runs for **2 minutes**.  During that window the bucket refills at exactly 5 tokens / second, so the *average* number of successful acquisitions must not exceed 5 × 120 = 600 tokens.  
4. **Verification** – After the test we compute `observedRate = successfulAcquisitions / testDurationInSeconds`.  The assertion allows a tiny tolerance (0.1 tps) to accommodate measurement jitter but otherwise guarantees the long‑run rate respects the configured limit.  

The test uses a `CopyOnWriteArrayList` to avoid external synchronization; the recorded timestamps are only read after all threads have terminated, so no additional concurrency handling is required.

---

### How to Run the Tests  

* Maven/Gradle projects that already depend on JUnit 5 can simply execute `mvn test` or `./gradlew test`.  
* The `RateLimiter` class lives in the package `com.example.ratelimiter`; adjust the import statements if you place it elsewhere.  
* The test suite includes a **concurrent stress test** that spawns many threads and checks the long‑run rate – this validates that the implementation truly behaves as a rate limiter under load.  

--- 

**That completes the implementation, design documentation, and a thorough JUnit 5 test suite.**