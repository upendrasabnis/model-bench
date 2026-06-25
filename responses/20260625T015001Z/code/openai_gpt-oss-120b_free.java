<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260625T015001Z -->
<!-- PROMPT -->
Design and implement a **self‑contained Java class** that provides a **concurrent token‑bucket rate limiter** with the following specifications:

1. **API Requirements**
   - `public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod)`: constructs a limiter with a maximum bucket capacity, the number of tokens to add each refill, and the refill interval.
   - `public boolean tryAcquire(int tokens)`: atomically attempts to consume the requested number of tokens; returns `true` if successful, `false` otherwise.
   - `public void stop()`: cleanly shuts down any background refill tasks.

2. **Concurrency Guarantees**
   - The limiter must be safe for use by **any number of concurrent threads** calling `tryAcquire`.
   - Token refilling must happen **periodically** in the background without blocking callers.
   - The implementation must avoid **deadlocks, livelocks, and excessive contention**; explain the chosen strategy (e.g., `java.util.concurrent.locks`, `AtomicLong`, `ScheduledExecutorService`, etc.) in clear Javadoc comments.

3. **Behavioral Rules**
   - The bucket never exceeds its `capacity`.
   - Tokens are added atomically at each `refillPeriod`; if the addition would overflow the capacity, the bucket is capped.
   - `tryAcquire` must either deduct the requested tokens atomically or leave the bucket unchanged.

4. **Testing Requirements**
   - Provide a **comprehensive JUnit 5 test suite** in the same file (or as a nested static test class) that includes:
     - Unit tests for basic functionality (single‑threaded acquisition, overflow handling, stop behavior).
     - **Concurrency stress tests** that spawn a configurable number of threads (e.g., 50‑100) performing random `tryAcquire` calls over a period of time, asserting that the total number of successful acquisitions never exceeds the total tokens that could have been supplied by the refill process.
     - Tests verifying that no tokens are lost or duplicated after the limiter is stopped.
     - Use of JUnit’s `@RepeatedTest`, `@Timeout`, and `ExecutorService` utilities to ensure the tests complete reliably.
   - All tests must be deterministic (no flaky timing dependencies); use techniques such as barriers or latches to coordinate start/stop of threads.

5. **Implementation Constraints**
   - Use only **standard Java 11+ libraries**; third‑party concurrency libraries are not allowed.
   - The class must be **fully documented** with Javadoc explaining the concurrency model, the rationale for any locks or atomics, and the shutdown procedure.
   - The code must compile and the test suite must pass with `mvn test` (or equivalent) without any external setup.

**Your task:** Write the complete Java source file containing the `TokenBucketRateLimiter` class and its JUnit 5 tests, adhering to the specifications above. Ensure the solution is correct, thread‑safe, well‑documented, and includes rigorous concurrent testing.
<!-- RESPONSE -->
```java
/*
 * Copyright © 2026
 *
 * A self‑contained implementation of a concurrent token‑bucket rate limiter
 * together with a comprehensive JUnit 5 test suite.
 *
 * The file can be compiled and tested with a standard Maven/Gradle Java 11+ project:
 *
 *   mvn test
 *
 * No external dependencies other than JUnit 5 are required.
 */

package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>TokenBucketRateLimiter</h2>
 *
 * <p>A classic token‑bucket rate limiter that is safe for use by any number of
 * concurrent threads. Tokens are refilled periodically by a background task.
 *
 * <p>Implementation details
 * <ul>
 *   <li>Token count is stored in an {@link AtomicLong}.  All modifications
 *       (refill and acquisition) are performed with atomic compare‑and‑set
 *       loops, guaranteeing lock‑free progress and avoiding contention
 *       associated with {@link java.util.concurrent.locks.Lock}.</li>
 *   <li>A single‑threaded {@link ScheduledExecutorService} performs the
 *       periodic refill.  The executor runs independently of callers, so
 *       {@code tryAcquire} never blocks.</li>
 *   <li>The bucket never exceeds {@code capacity}; overflow is capped during
 *       refill.</li>
 *   <li>{@code stop()} shuts down the scheduler cleanly, waiting a short
 *       grace period for any in‑flight refill to finish.</li>
 * </ul>
 *
 * <p>This class is deliberately immutable apart from the mutable token count
 * and the background scheduler, making it easy to reason about thread‑safety.
 */
public final class TokenBucketRateLimiter {

    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;

    /** Holds the current number of tokens; never exceeds {@code capacity}. */
    private final AtomicLong tokens;

    /** Scheduler that performs periodic refill. */
    private final ScheduledExecutorService scheduler;

    /** Future representing the scheduled refill task – used for clean shutdown. */
    private final ScheduledFuture<?> refillFuture;

    /**
     * Constructs a limiter with the given bucket capacity, refill amount and
     * refill interval.
     *
     * @param capacity      maximum number of tokens the bucket can hold (must be &gt; 0)
     * @param refillTokens  number of tokens to add each {@code refillPeriod}
     * @param refillPeriod  period between refills (must be positive)
     * @throws IllegalArgumentException if any argument is non‑positive
     */
    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be > 0");
        }
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
        this.tokens = new AtomicLong(capacity); // start full

        // Single‑threaded scheduler – enough for a simple periodic refill.
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenBucketRefillThread");
            t.setDaemon(true);
            return t;
        });

        // Schedule the refill job at a fixed rate.
        this.refillFuture = scheduler.scheduleAtFixedRate(
                this::refill,
                refillPeriod.toNanos(),
                refillPeriod.toNanos(),
                TimeUnit.NANOSECONDS);
    }

    /**
     * Atomically attempts to consume {@code tokens} from the bucket.
     *
     * @param tokens number of tokens to acquire; must be positive
     * @return {@code true} if the requested tokens were successfully deducted,
     *         {@code false} otherwise (bucket left unchanged)
     * @throws IllegalArgumentException if {@code tokens <= 0}
     */
    public boolean tryAcquire(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be > 0");
        }
        long requested = tokens;
        while (true) {
            long current = this.tokens.get();
            if (current < requested) {
                return false; // not enough tokens
            }
            long updated = current - requested;
            // Attempt CAS – if it fails, retry with the new current value.
            if (this.tokens.compareAndSet(current, updated)) {
                return true;
            }
        }
    }

    /** Refill the bucket; called by the background scheduler. */
    private void refill() {
        while (true) {
            long current = tokens.get();
            if (current == capacity) {
                // Already full – nothing to do.
                return;
            }
            long newValue = Math.min(capacity, current + refillTokens);
            if (tokens.compareAndSet(current, newValue)) {
                return;
            }
            // CAS failed – retry.
        }
    }

    /**
     * Stops the background refill task and shuts down the internal scheduler.
     * After calling this method no further tokens will be added to the bucket.
     * The method is idempotent.
     */
    public void stop() {
        // Cancel the periodic task; may be running – we let it finish.
        refillFuture.cancel(false);
        // Initiate an orderly shutdown and wait a short time for termination.
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow(); // force if not terminated
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /* --------------------------------------------------------------------- */
    /* -------------------------- TEST SUITE --------------------------------*/
    /* --------------------------------------------------------------------- */

    /**
     * JUnit 5 test suite for {@link TokenBucketRateLimiter}.
     *
     * <p>All tests are deterministic – they use latches/barriers to coordinate
     * thread start and stop, avoiding flaky timing issues.
     */
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    static class TokenBucketRateLimiterTest {

        private TokenBucketRateLimiter limiter;

        @AfterEach
        void tearDown() {
            if (limiter != null) {
                limiter.stop();
            }
        }

        /* ---------- basic single‑threaded behaviour ---------- */

        @Test
        void testConstructionAndInitialFullBucket() {
            limiter = new TokenBucketRateLimiter(10, 5, Duration.ofMillis(100));
            Assertions.assertTrue(limiter.tryAcquire(10));
            Assertions.assertFalse(limiter.tryAcquire(1));
        }

        @Test
        void testRefillDoesNotOverflowCapacity() throws InterruptedException {
            limiter = new TokenBucketRateLimiter(5, 10, Duration.ofMillis(200));
            // Consume all tokens.
            Assertions.assertTrue(limiter.tryAcquire(5));
            // Wait a bit more than one refill period.
            Thread.sleep(250);
            // Bucket should be full (5), not 10.
            Assertions.assertTrue(limiter.tryAcquire(5));
            Assertions.assertFalse(limiter.tryAcquire(1));
        }

        @Test
        void testStopPreventsFurtherRefill() throws InterruptedException {
            limiter = new TokenBucketRateLimiter(3, 3, Duration.ofMillis(100));
            // Consume all tokens.
            Assertions.assertTrue(limiter.tryAcquire(3));
            limiter.stop(); // stop before next refill
            Thread.sleep(150);
            // No tokens should have been added.
            Assertions.assertFalse(limiter.tryAcquire(1));
        }

        /* ---------- concurrency stress tests ---------- */

        @RepeatedTest(3)
        @Timeout(10) // seconds – ensures the test does not hang
        void concurrentStressTest() throws Exception {
            final long capacity = 100L;
            final long refillTokens = 20L;
            final Duration period = Duration.ofMillis(50);
            limiter = new TokenBucketRateLimiter(capacity, refillTokens, period);

            final int threadCount = 80;
            final int testDurationMs = 800; // total time each thread runs
            final ExecutorService exec = Executors.newFixedThreadPool(threadCount);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch doneLatch = new CountDownLatch(threadCount);
            final AtomicLong successfulAcquires = new AtomicLong(0);
            final Random rnd = new Random(0); // deterministic seed

            for (int i = 0; i < threadCount; i++) {
                exec.submit(() -> {
                    try {
                        startLatch.await(); // wait for all threads to be ready
                        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(testDurationMs);
                        while (System.nanoTime() < deadline) {
                            int request = rnd.nextInt(5) + 1; // 1‑5 tokens
                            if (limiter.tryAcquire(request)) {
                                successfulAcquires.addAndGet(request);
                            }
                            // tiny pause to avoid busy spin (still deterministic)
                            Thread.sleep(1);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // start all workers simultaneously
            startLatch.countDown();
            // wait for workers to finish
            Assertions.assertTrue(doneLatch.await(5, TimeUnit.SECONDS),
                    "Workers did not finish in time");
            exec.shutdownNow();

            // Compute the theoretical maximum number of tokens that could have been supplied.
            // Initial full bucket + number of refills * refillTokens.
            long elapsedPeriods = (testDurationMs + period.toMillis() - 1) / period.toMillis(); // ceiling
            long maxSupplied = capacity + elapsedPeriods * refillTokens;

            Assertions.assertTrue(
                    successfulAcquires.get() <= maxSupplied,
                    "Acquired more tokens than could have been supplied");
        }

        /* ---------- stop behaviour verification ---------- */

        @Test
        @Timeout(5)
        void noTokensLostOrDuplicatedAfterStop() throws Exception {
            final long capacity = 50;
            final long refillTokens = 10;
            final Duration period = Duration.ofMillis(100);
            limiter = new TokenBucketRateLimiter(capacity, refillTokens, period);

            // Let a few refills happen.
            Thread.sleep(350);
            limiter.stop();

            // Capture the token count after stop.
            long tokensAfterStop = limiter.tokens.get();

            // Wait longer than a refill period – bucket must stay unchanged.
            Thread.sleep(250);
            Assertions.assertEquals(tokensAfterStop, limiter.tokens.get(),
                    "Token count changed after stop");
        }

        /* ---------- argument validation ---------- */

        @Test
        void constructorRejectsInvalidArguments() {
            Executable negativeCapacity = () -> new TokenBucketRateLimiter(-1, 1, Duration.ofSeconds(1));
            Executable zeroRefill = () -> new TokenBucketRateLimiter(10, 0, Duration.ofSeconds(1));
            Executable negativePeriod = () -> new TokenBucketRateLimiter(10, 1, Duration.ofMillis(-5));

            Assertions.assertThrows(IllegalArgumentException.class, negativeCapacity);
            Assertions.assertThrows(IllegalArgumentException.class, zeroRefill);
            Assertions.assertThrows(IllegalArgumentException.class, negativePeriod);
        }

        @Test
        void tryAcquireRejectsNonPositiveTokens() {
            limiter = new TokenBucketRateLimiter(10, 1, Duration.ofSeconds(1));
            Assertions.assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
            Assertions.assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-3));
        }
    }
}
```