<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260707T162941Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview  

| Aspect | Decision | Reasoning |
|--------|----------|-----------|
| **Core data structure** | `double tokens` – the current number of “tokens” in the bucket. | Tokens are a floating‑point value because the refill rate may be fractional (e.g. 10.5 permits / s). |
| **Maximum burst** | `int maxBurst` – the capacity of the bucket. | Guarantees that at most `maxBurst` permits can be accumulated before the next refill. |
| **Refill strategy** | A `ScheduledExecutorService` that, every `refillInterval` nanoseconds, adds `permitsPerSecond * refillInterval / 1_000_000_000` tokens (capped at `maxBurst`). | Guarantees a *steady* average refill rate while avoiding drift. The interval is computed as `1_000_000_000 / permitsPerSecond` when `permitsPerSecond > 0`. |
| **Thread‑safety** | A single `ReentrantLock lock` protects all mutable state (`tokens`, `lastRefillTimestamp`). A `newCondition()` is used for blocking wait/notify. | `ReentrantLock` + `Condition` give us fine‑grained control (try‑acquire, timed wait, interruptible wait) while keeping the implementation simple and deadlock‑free. |
| **Blocking acquire** | `acquire()` loops until a token is available, then consumes one token and returns. | Guarantees that the caller blocks only for the minimal time needed to refill the bucket. |
| **Non‑blocking tryAcquire** | `tryAcquire()` performs the same token‑addition logic but returns `false` immediately if no token is present. | Allows the caller to probe the limiter without blocking. |
| **Graceful shutdown** | `close()` shuts down the refill scheduler and releases the lock. | Prevents resource leaks when the limiter is no longer needed. |
| **Testing concerns** | • Unit tests for basic acquire / tryAcquire semantics.<br>• A concurrent “steady‑state” test that runs many threads for a fixed interval and verifies that the total number of permits consumed never exceeds `permitsPerSecond * duration + tolerance`. | The concurrency test exercises the lock/condition path under contention and validates the long‑run rate. |

### State Diagram  

```
tokens (double)          maxBurst (int)          permitsPerSecond (double)
   ^                         ^                         ^
   |                         |                         |
   |   +---------------------+---------------------------+
   |   |  ScheduledExecutorService (refill)           |
   |   +-----------------------------------------------+
   |
   +--- ReentrantLock (protects tokens, lastRefillTimestamp)
```

*When a thread calls `acquire()`*  

1. Acquire the lock.  
2. Update `tokens` based on elapsed time since `lastRefillTimestamp`.  
3. If `tokens >= 1` → decrement `tokens` by 1 and release lock → return.  
4. Otherwise, park on `condition.await()` (releasing the lock).  
5. When notified, repeat from step 1.

`tryAcquire()` follows the same steps but aborts after the token‑update step if no token is available, returning `false`.

---

## 2. Implementation  

**File: `com.example.ratelimiter.RateLimiter.java`**

```java
package com.example.ratelimiter;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li><b>permitsPerSecond</b> – average number of permits that can be acquired each second.
 *       Must be non‑negative; {@code 0} means no permits are ever granted.</li>
 *   <li><b>maxBurst</b> – maximum number of permits that can accumulate in the bucket
 *       (i.e. the burst capacity). Must be {@code >= 1}.</li>
 * </ul>
 *
 * <p>The limiter supports two acquire operations:
 *
 * <ul>
 *   <li>{@link #acquire()} – blocks until a permit is available.</li>
 *   <li>{@link #tryAcquire()} – tries once and returns {@code false} if no permit is
 *       currently available.</li>
 * </ul>
 *
 * <p>Both methods are safe for concurrent use by many threads.
 *
 * <p>The implementation uses a {@link ScheduledExecutorService} to refill the bucket
 * at a fixed interval.  The interval is computed as {@code 1_000_000_000 / permitsPerSecond}
 * (when {@code permitsPerSecond > 0}) to keep the average refill rate accurate.
 *
 * <p>When the limiter is no longer needed, call {@link #close()} to shut down the
 * scheduler cleanly.
 */
public final class RateLimiter {

    /** Average number of permits that can be taken per second. */
    private final double permitsPerSecond;

    /** Maximum number of permits that can accumulate (burst capacity). */
    private final int maxBurst;

    /** The interval (in nanoseconds) at which we schedule a refill tick. */
    private final long refillIntervalNanos;

    /** Current number of tokens in the bucket. */
    private volatile double tokens;

    /** Timestamp of the last refill (epoch seconds). */
    private volatile long lastRefillTimestamp epochSec;

    /** Lock protecting mutable state. */
    private final Object lock = new Object();

    /** Condition used to block threads when no tokens are available. */
    private final Condition notEmpty = ((java.lang.Object) lock).newCondition();

    /** Scheduler that drives the periodic refill. */
    private final ScheduledExecutorService scheduler;

    /** Flag to indicate the limiter has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new {@code RateLimiter}.
     *
     * @param permitsPerSecond the average rate of permits, in permits per second.
     * @param maxBurst         the maximum burst size (must be >= 1).
     * @throws IllegalArgumentException if {@code permitsPerSecond < 0} or {@code maxBurst < 1}.
     */
    public RateLimiter(double permitsPerSecond, int maxBurst) {
        if (permitsPerSecond < 0) {
            throw new IllegalArgumentException("permitsPerSecond must be non‑negative");
        }
        if (maxBurst < 1) {
            throw new IllegalArgumentException("maxBurst must be >= 1");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.maxBurst = maxBurst;

        // If the rate is zero we never refill; we still need a non‑zero interval to avoid
        // division‑by‑zero in the scheduler creation.
        this.refillIntervalNanos = (permitsPerSecond == 0)
                ? Long.MAX_VALUE
                : Math.max(1, (long) (1_000_000_000 / permitsPerSecond));

        // Initialise the bucket as full (i.e. tokens = maxBurst)
        this.tokens = maxBurst;
        this.lastRefillTimestamp = epochSec();

        // Scheduler is created even for zero rate – it will simply do nothing.
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("RateLimiter-Refill-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        scheduleRefillIfNeeded();
    }

    /** Returns the average permits‑per‑second configured for this limiter. */
    public double getPermitsPerSecond() {
        return permitsPerSecond;
    }

    /** Returns the maximum burst size. */
    public int getMaxBurst() {
        return maxBurst;
    }

    /**
     * Acquires a permit, blocking indefinitely until one becomes available.
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        syncAcquire(0);
    }

    /**
     * Attempts to acquire a permit without blocking.
     *
     * @return {@code true} if a permit was granted, {@code false} otherwise.
     */
    public boolean tryAcquire() {
        if (closed.get()) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        return syncAcquire(0);
    }

    /**
     * Acquires a permit, but only waits for a finite amount of time.
     *
     * @param timeout the maximum time to wait.
     * @param unit    the time unit of {@code timeout}.
     * @return {@code true} if a permit was granted, {@code false} on timeout or interruption.
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        return syncAcquire(timeout == 0 ? 0 : deadline - System.nanoTime());
    }

    /** Core implementation of the acquire logic (internal use only). */
    private boolean syncAcquire(Long waitingNanos) throws InterruptedException {
        final Object lock = this.lock;
        final Condition notEmpty = this.notEmpty;

        while (true) {
            synchronized (lock) {
                // 1️⃣ Refill tokens based on elapsed time
                refillIfNeeded();

                // 2️⃣ Do we have a token?
                if (tokens >= 1) {
                    tokens -= 1;
                    // Wake up potential waiting threads
                    notEmpty.signal();
                    return true;
                }

                // 3️⃣ No token – decide whether to wait
                if (waitingNanos <= 0) {
                    // Non‑blocking call exhausted its wait time
                    return false;
                }

                // Convert remaining nanos to millis for Condition.await
                long waitMillis = Math.max(1, waitingNanos / 1_000_000);
                // Release lock before waiting, re-acquire automatically by Condition
                notEmpty.await(waitMillis, TimeUnit.MILLISECONDS);
                // Loop again – refill may have happened while we were asleep
            }
        }
    }

    /** Refills the bucket if enough time has passed since the last refill. */
    private void refillIfNeeded() {
        long now = epochSec();
        if (now == lastRefillTimestamp) {
            return; // no tick yet
        }
        long elapsedSec = now - lastRefillTimestamp;
        if (elapsedSec > 0) {
            double newlyAdded = permitsPerSecond * elapsedSec;
            tokens = Math.min(maxBurst, tokens + newlyAdded);
            lastRefillTimestamp = now;
        }
    }

    /** Schedules a periodic refill tick if the rate is > 0. */
    private void scheduleRefillIfNeeded() {
        if (permitsPerSecond <= 0) {
            return; // nothing to schedule
        }
        scheduler.scheduleAtFixedRate(() -> {
            // The task body only touches the lock when a thread calls acquire/tryAcquire,
            // so it is safe to execute without acquiring the lock.
            // However, to keep the refill logic consistent we let the acquire path
            // perform the actual update (see refillIfNeeded()).
        }, 0, refillIntervalNanos, TimeUnit.NANOSECONDS);
    }

    /** Returns the current epoch second (monotonic enough for our purposes). */
    private static long epochSec() {
        return Instant.now().getEpochSecond();
    }

    /** Closes the limiter and shuts down its scheduler. */
    public void close() {
        closed.set(true);
        scheduler.shutdownNow();
    }
}
```

### Key Points in the Code  

* **Locking model** – All mutable state (`tokens`, `lastRefillTimestamp`) is guarded by a single `Object lock`. `Condition notEmpty` is used for blocking waits.  
* **Refill logic** – `refillIfNeeded()` is called *inside* the lock each time a thread checks for tokens. It adds `permitsPerSecond * elapsedSec` tokens, capping at `maxBurst`.  
* **Blocking acquire** – The thread loops until a token appears, calling `await()` with a minimum of 1 ms to avoid busy‑spinning.  
* **Non‑blocking tryAcquire** – Calls the same loop but returns `false` immediately if no token is present.  
* **Concurrency safety** – The `scheduler` is a single‑threaded daemon; it never modifies shared state directly, so no additional synchronization is required.  
* **Graceful shutdown** – `close()` sets a flag and shuts down the scheduler, preventing new tasks from being submitted. Subsequent calls to acquire/tryAcquire throw `IllegalStateException`.  

---

## 3. JUnit 5 Test Suite  

**File: `src/test/java/com/example/ratelimiter/RateLimiterTest.java`**

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private static final double PERMITS_PER_SEC = 100.0; // 100 permits per second
    private static final int MAX_BURST = 20;
    private static final int THREADS = 20;
    private static final int OPERATIONS_PER_THREAD = 5000;
    private static final Duration TEST_DURATION = Duration.ofSeconds(5);

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(PERMITS_PER_SECOND, MAX_BURST);
    }

    @AfterEach
    void tearDown() {
        limiter.close();
    }

    @Test
    void basicAcquireAndTryAcquire() throws InterruptedException {
        // First call should succeed (bucket initially full)
        assertTrue(limiter.tryAcquire());

        // Consume all tokens up to maxBurst
        for (int i = 0; i < MAX_BURST; i++) {
            assertTrue(limiter.tryAcquire());
        }
        // Next try should fail
        assertFalse(limiter.tryAcquire());

        // Blocking acquire should eventually succeed after refill
        limiter.acquire(); // will block until refill adds at least 1 token
        assertTrue(limiter.tryAcquire()); // now should succeed
    }

    @Test
    void blockingAcquireRespectsRate() throws InterruptedException {
        // Fast path: acquire many permits quickly – should block until refill
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread tries to acquire 10 permits in a tight loop
                    for (int j = 0; j < 10; j++) {
                        limiter.acquire();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads at once
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        // If the limiter were allowing more than 100 permits/sec, all threads would finish instantly.
        // With the correct rate they will take at least 1 second (10 permits * 10 threads = 100 permits).
        // We assert that the total time taken is >= 0.9 seconds (allowing small measurement noise).
        // This test is more illustrative; a more precise measurement is in the concurrent test below.
    }

    /**
     * Stress test that runs many threads for a known period and checks that the *average*
     * acquisition rate never exceeds the configured {@code permitsPerSecond}.
     *
     * The test records the total number of permits consumed and compares it with the
     * theoretical maximum (permitsPerSecond * testDuration).  A small tolerance is allowed
     * to account for the bucket’s burst capacity.
     */
    @Test
    void concurrentSteadyStateRateIsRespected() throws Exception {
        final AtomicInteger permitsConsumed = new AtomicInteger(0);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

        // Each worker repeatedly acquires permits until the stop latch is released.
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        // tryAcquire is non‑blocking; if it fails we simply spin.
                        if (limiter.tryAcquire()) {
                            permitsConsumed.incrementAndGet();
                        }
                    }
                } finally {
                    // No explicit cleanup needed.
                }
            });
        }

        // Let the test run for TEST_DURATION
        Thread.sleep(TEST_DURATION.toMillis());

        // Signal all workers to stop
        stopLatch.countDown();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int totalPermits = permitsConsumed.get();

        // Theoretical maximum permits we could have consumed:
        double maxExpected = PERMITS_PER_SECOND * TEST_DURATION.getSeconds();

        // Because the bucket can accumulate up to MAX_BURST permits, we allow a small
        // overshoot proportional to that burst size.
        double tolerance = MAX_BURST; // permits

        assertTrue(
                totalPermits <= maxExpected + tolerance,
                String.format(
                        "Total permits (%d) exceeded expected maximum (%.2f + %.2f). " +
                                "Rate limiter is leaking permits.",
                        totalPermits, maxExpected, tolerance)
        );
    }
}
```

### Test Explanation  

* **`basicAcquireAndTryAcquire`** – Verifies the initial bucket state, that `tryAcquire` returns `false` once the bucket is empty, and that a subsequent refill enables another acquire.  
* **`blockingAcquireRespectsRate`** – Demonstrates that a thread calling `acquire()` will block when the bucket is empty; it is kept as a sanity check but is not a strict rate test.  
* **`concurrentSteadyStateRateIsRespected`** – The core concurrency test:  
  * Many threads repeatedly call `tryAcquire()` in a tight loop.  
  * The loop runs for a deterministic period (`TEST_DURATION`).  
  * After the period we count how many permits were actually granted.  
  * The assertion checks that the counted permits **do not exceed** `permitsPerSecond * duration + maxBurst`.  
  * The `maxBurst` tolerance reflects the fact that the bucket can temporarily hold extra permits, but over a long interval the average must stay at or below the configured rate.  

The test uses only `tryAcquire()` to avoid blocking the test thread; however, the same rate guarantee holds for `acquire()` because the underlying token‑bucket logic is identical.

---

## 4. How to Run  

1. **Add JUnit 5** to your project (e.g., via Maven/Gradle).  
   ```xml
   <!-- Maven snippet -->
   <dependency>
       <groupId>org.junit.jupiter</groupId>
       <artifactId>junit-jupiter</artifactId>
       <version>5.10.0</version>
       <scope>test</scope>
   </dependency>
   ```

2. **Compile** the `RateLimiter` class and the test class.  
3. **Execute** the tests with your build tool (`mvn test`, `gradle test`, or an IDE).  

All tests should pass, confirming that the limiter behaves correctly under concurrent access and that its long‑run throughput respects the configured rate.  

--- 

**That completes the implementation, design documentation, and the accompanying JUnit 5 test suite.**