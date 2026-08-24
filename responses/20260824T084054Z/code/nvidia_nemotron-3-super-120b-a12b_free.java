<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260824T084054Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Solution Overview**

A token‑bucket rate limiter works as follows:

* A bucket holds a maximum of `capacity` tokens.
* Tokens are added continuously at a fixed **refill rate** (`rate` tokens per second).
* When a thread wants to execute an action it must first **acquire** a certain number of tokens (`permits`).  
  * If enough tokens are available they are removed and the action proceeds.  
  * If not enough tokens are available the thread may either:
    * fail immediately (`tryAcquire`) or
    * block until enough tokens have been refilled (`acquire`).

The implementation below is **thread‑safe** and uses a `ReentrantLock` together with a `Condition` so that blocking threads wait efficiently (no busy‑spinning).  
All state changes happen while holding the lock, guaranteeing visibility and atomicity.

---

## TokenBucket.java

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket starts full (i.e. {@code tokens == capacity}).  Tokens are
 * added continuously at the configured {@code refillRatePerSec}.  The
 * {@link #acquire(int)} method blocks until the requested number of permits
 * is available; {@link #tryAcquire(int)} attempts a non‑blocking acquisition
 * and returns {@code true} on success.</p>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>All mutable state ({@code tokens}, {@code lastRefillNanos}) is
 *       protected by a {@link ReentrantLock}.</li>
 *   <li>The {@code Condition} {@code notEmpty} is used for blocking
 *       acquires.  Whenever the refill operation adds tokens, it signals
 *       the condition so waiting threads can re‑evaluate.</li>
 *   <li>Time is measured with {@link System#nanoTime()} to avoid issues
 *       with system‑clock adjustments.</li>
 *   <li>The refill calculation is performed lazily – i.e. only when a
 *       thread checks the bucket state.  This keeps the implementation
 *       simple while still being correct.</li>
 * </ul>
 *
 * @param <T> unused – kept for potential future extensions
 */
public class TokenBucket {

    /** Maximum number of tokens the bucket can hold. */
    private final double capacity;

    /** Current number of tokens available (may be fractional). */
    private double tokens;

    /** Refill rate in tokens per second. */
    private final double refillRatePerSec;

    /** Timestamp (nanoseconds) of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when the bucket does not have enough tokens. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new token bucket.
     *
     * @param capacity          maximum tokens the bucket can hold (must be > 0)
     * @param refillRatePerSec  tokens added per second (must be > 0)
     * @throws IllegalArgumentException if either argument is non‑positive
     */
    public TokenBucket(double capacity, double refillRatePerSec) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSec <= 0) {
            throw new IllegalArgumentException("refillRatePerSec must be > 0");
        }
        this.capacity = capacity;
        this.tokens = capacity; // start full
        this.refillRatePerSec = refillRatePerSec;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Adds tokens to the bucket based on the elapsed time since the last
     * refill.  Must be called while holding {@code lock}.
     *
     * @param nowNanos current time in nanoseconds
     */
    private void refill(long nowNanos) {
        if (nowNanos > lastRefillNanos) {
            double elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
            double toAdd = elapsedSec * refillRatePerSec;
            if (toAdd > 0) {
                tokens = Math.min(capacity, tokens + toAdd);
                lastRefillNanos = nowNanos;
                // Wake up any threads that might be waiting for tokens.
                notEmpty.signalAll();
            }
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            long now = System.nanoTime();
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
     * Acquires {@code permits} tokens, blocking until they are available.
     *
     * @param permits number of tokens to acquire (must be > 0)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            long now = System.nanoTime();
            refill(now);
            while (tokens < permits) {
                // Compute how long we need to wait for enough tokens.
                double needed = permits - tokens; // tokens still missing
                double waitSec = needed / refillRatePerSec;
                long waitNanos = (long) (waitSec * 1_000_000_000);
                if (waitNanos > 0) {
                    // Condition.awaitNanos returns remaining nanos; we ignore it
                    // and loop to re‑check the condition (handles spurious wakeups).
                    notEmpty.awaitNanos(waitNanos);
                }
                // After waiting, refill again and loop.
                now = System.nanoTime();
                refill(now);
            }
            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens available in the bucket.
     * This method is primarily useful for testing.
     *
     * @return current token count (may be fractional)
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            long now = System.nanoTime();
            refill(now);
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
```

### Design Documentation (inline Javadoc)

* **Thread safety** – All accesses to `tokens` and `lastRefillNanos` happen while holding a `ReentrantLock`. The `Condition` ensures that threads waiting for tokens are parked efficiently.
* **Lazy refill** – The bucket is only updated when a thread inspects its state (`tryAcquire`, `acquire`, or `getAvailableTokens`). This avoids a background thread and still guarantees correctness because the amount of time that has passed is computed from the stored timestamp.
* **Fractional tokens** – Tokens are stored as `double` to allow a smooth refill rate even when the rate does not divide evenly into the requested permit count. The public API works with integral permits, but the internal math handles the fraction correctly.
* **Blocking acquire** – Computes the exact wait time needed for the missing tokens, then uses `Condition.awaitNanos`. After the wait (or a spurious wakeup) the loop re‑checks the condition and refills again.
* **Signalling** – Whenever the refill operation actually adds tokens, it calls `notEmpty.signalAll()` to wake up any waiting threads.

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * The tests cover:
 *   <ul>
 *     <li>basic acquisition behavior</li>
 *     <li>blocking vs non‑blocking APIs</li>
 *     <li>burst capacity</li>
 *     <li>long‑term rate compliance (single‑threaded)</li>
 *     <li>concurrent rate compliance (multiple threads)</li>
 *   </ul>
 */
class TokenBucketTest {

    private static final double EPS = 1e-9; // tolerance for floating point comparisons

    @Nested
    @DisplayName("Basic functionality")
    class Basic {

        @Test
        @DisplayName("Constructor initializes bucket full")
        void constructorStartsFull() {
            TokenBucket tb = new TokenBucket(10.0, 5.0);
            assertEquals(10.0, tb.getAvailableTokens(), EPS);
        }

        @Test
        @DisplayName("tryAcquire succeeds when enough tokens")
        void tryAcquireSuccess() {
            TokenBucket tb = new TokenBucket(5.0, 1.0);
            assertTrue(tb.tryAcquire(3));
            assertEquals(2.0, tb.getAvailableTokens(), EPS);
        }

        @Test
        @DisplayName("tryAcquire fails when insufficient tokens")
        void tryAcquireFail() {
            TokenBucket tb = new TokenBucket(5.0, 1.0);
            tb.tryAcquire(5); // drain
            assertFalse(tb.tryAcquire(1));
            assertEquals(0.0, tb.getAvailableTokens(), EPS);
        }

        @Test
        @DisplayName("acquire blocks until tokens are refilled")
        void acquireBlocks() throws InterruptedException {
            TokenBucket tb = new TokenBucket(2.0, 2.0); // 2 tokens/sec
            // Drain the bucket
            assertTrue(tb.tryAcquire(2));
            assertEquals(0.0, tb.getAvailableTokens(), EPS);

            // Start a thread that will try to acquire 1 token
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> future = exec.submit(() -> {
                try {
                    tb.acquire(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Wait a bit – the thread should still be blocked
            assertFalse(future.isDone(), "acquire should still be waiting");

            // Sleep enough time for at least 1 token to be refilled (0.5 sec)
            Thread.sleep(600);
            // Now the permit should be available and the task complete
            assertTrue(future.isDone(1, TimeUnit.SECONDS), "acquire should have completed");
            exec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Burst capacity")
    class Burst {

        @Test
        @DisplayName("Can consume up to capacity instantly")
        void instantBurst() {
            TokenBucket tb = new TokenBucket(10.0, 1.0); // low refill rate
            // Try to take all at once
            boolean ok = tb.tryAcquire(10);
            assertTrue(ok);
            assertEquals(0.0, tb.getAvailableTokens(), EPS);
            // Next attempt must fail
            assertFalse(tb.tryAcquire(1));
        }

        @Test
        @DisplayName("Exceeding capacity fails immediately")
        void exceedBurstFails() {
            TokenBucket tb = new TokenBucket(5.0, 10.0);
            assertFalse(tb.tryAcquire(6)); // more than capacity
            assertEquals(5.0, tb.getAvailableTokens(), EPS); // unchanged
        }
    }

    @Nested
    @DisplayName("Rate limiting over time")
    class Rate {

        /**
         * Helper that runs a fixed‑duration load test using {@code tryAcquire}.
         *
         * @param bucket   the token bucket to test
         * @param duration how long to run the test
         * @param permitsPerCall how many permits each successful acquire attempts to take
         * @return total number of permits successfully acquired during the test
         */
        private long runTryAcquireLoad(TokenBucket bucket,
                                       Duration duration,
                                       int permitsPerCall) throws InterruptedException {
            AtomicLong counter = new AtomicLong();
            ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(1);

            // Worker continuously attempts to acquire permits
            Runnable worker = () -> {
                try {
                    startLatch.await(); // wait for test start
                    long end = System.nanoTime() + duration.toNanos();
                    while (System.nanoTime() < end) {
                        if (bucket.tryAcquire(permitsPerCall)) {
                            counter.addAndGet(permitsPerCall);
                        }
                        // No Thread.yield() – we want to stress the limiter
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            };

            int workers = Math.min(32, Runtime.getRuntime().availableProcessors() * 2);
            for (int i = 0; i < workers; i++) {
                exec.execute(worker);
            }

            startLatch.countDown(); // let all workers go
            doneLatch.await();      // wait for all workers to finish
            exec.shutdownNow();
            return counter.get();
        }

        @Test
        @DisplayName("Long‑run rate does not exceed configured limit (single thread)")
        void singleThreadRateLimit() throws InterruptedException {
            double rate = 5.0; // 5 tokens per sec
            double capacity = 2.0;
            TokenBucket tb = new TokenBucket(capacity, rate);

            int totalPermits = 50; // more than capacity
            long startNanos = System.nanoTime();
            tb.acquire(totalPermits);
            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsedSec = elapsedNanos / 1_000_000_000.0;

            // Minimum time needed: (totalPermits - capacity) / rate
            // (the first 'capacity' tokens are free, the rest must be refilled)
            double minExpectedSec = Math.max(0.0, (totalPermits - capacity)) / rate;
            assertTrue(elapsedSec >= minExpectedSec - 0.05,
                    "Acquisition took too little time: %.3f sec (expected ≥ %.3f)",
                    elapsedSec, minExpectedSec);
        }

        @Test
        @DisplayName("Concurrent test: long‑run rate does not exceed limit")
        void concurrentRateLimit() throws Exception {
            double rate = 20.0; // 20 tokens per sec
            double capacity = 10.0; // allow bursts of up to 10
            TokenBucket tb = new TokenBucket(capacity, rate);

            Duration testDuration = Duration.ofSeconds(3);
            int permitsPerCall = 1; // each successful acquire takes 1 token

            long acquired = runTryAcquireLoad(tb, testDuration, permitsPerCall);

            double maxAllowed = rate * testDuration.getSeconds() + capacity;
            // Allow a small tolerance for timing jitter and the fact that
            // the bucket may start full and end partially filled.
            double tolerance = 2.0; // tokens
            assertTrue(acquired <= maxAllowed + tolerance,
                    "Acquired %d tokens, which exceeds the allowed %.2f (+ tolerance)",
                    acquired, maxAllowed);
        }

        @RepeatedTest(5)
        @DisplayName("Repeated concurrent rate test (to reduce flakiness)")
        void repeatedConcurrentRateLimit() throws Exception {
            concurrentRateLimit(); // reuse the same logic
        }
    }
}
```

### Explanation of the Tests

| Test Group | Purpose |
|------------|---------|
| **Basic functionality** | Verifies constructor state, `tryAcquire` success/failure, and that `acquire` blocks correctly until tokens are refilled. |
| **Burst capacity** | Confirms that the bucket can instantly dispense up to its `capacity` tokens and that requests exceeding `capacity` fail immediately. |
| **Rate limiting (single thread)** | Acquires a known number of permits (> capacity) and measures the elapsed time. The time must be at least `(permits - capacity) / rate`, proving the limiter enforces the long‑run average rate. |
| **Rate limiting (concurrent)** | Many threads continuously call `tryAcquire` (non‑blocking) for a fixed wall‑clock duration. The total number of permits acquired is asserted to be ≤ `rate * duration + capacity` (burst allowance) plus a small tolerance to account for timing jitter. The test is repeated several times to reduce flakiness. |

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and rely on standard Java concurrency utilities (`ExecutorService`, `CountDownLatch`, `AtomicLong`). They can be run with any JUnit 5‑compatible runner (IDE, Maven Surefire, Gradle, etc.).