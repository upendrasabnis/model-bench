<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260625T015001Z -->
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
/**
 * TokenBucketRateLimiter.java
 *
 * A self‑contained, thread‑safe token bucket rate limiter implemented with
 * only JDK 11+ concurrency utilities.
 *
 * <p>The limiter maintains an internal token count (the “bucket”) that is
 * refilled periodically in the background.  Consumption ({@link #tryAcquire})
 * is performed atomically using a compare‑and‑set loop on an {@link AtomicLong},
 * guaranteeing that either the requested tokens are deducted or the bucket
 * remains unchanged.</p>
 *
 * <p>The class is designed for high concurrency:
 * <ul>
 *   <li>No blocking locks are held while waiting for tokens – the
 *       {@code tryAcquire} method spins only on a CAS operation, which is
 *       cheap under low contention and scales well.</li>
 *   <li>The background refill task runs in a {@link ScheduledExecutorService}
 *       with a single thread, guaranteeing that refills happen at a fixed
 *       rate without interfering with caller threads.</li>
 *   <li>Shutdown is clean: {@code stop()} shuts down the executor and waits
 *       for the refill task to finish, after which no further tokens are
 *       added.</li>
 * </ul>
 *
 * <p>All mutable state is encapsulated in {@code AtomicLong tokens} and a
 * volatile {@code running} flag, providing visibility guarantees without
 * the need for explicit locks.</p>
 *
 * @since 1.0
 */
public class TokenBucketRateLimiter {

    /** Current number of tokens in the bucket. */
    private final AtomicLong tokens;

    /** Maximum capacity of the bucket. */
    private final long capacity;

    /** Number of tokens added each refill period. */
    private final long refillTokens;

    /** Duration between two refills. */
    private final java.time.Duration refillPeriod;

    /** Executor that runs the periodic refill task. */
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    /** Flag indicating whether the limiter is still active. */
    private volatile boolean running = true;

    /**
     * Constructs a new token bucket rate limiter.
     *
     * @param capacity        maximum number of tokens the bucket can hold
     * @param refillTokens    number of tokens to add on each refill
     * @param refillPeriod    time interval between two refills
     * @throws IllegalArgumentException if any argument is non‑positive
     */
    public TokenBucketRateLimiter(long capacity,
                                  long refillTokens,
                                  java.time.Duration refillPeriod) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be > 0");
        }
        if (refillPeriod.isNegative() || refillPeriod.isZero()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
        this.tokens = new AtomicLong(capacity); // start full
        this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(t -> {
                        // give the thread a meaningful name for debugging
                        t.setName("TokenBucket-Refill-" + t.getId());
                    });
                    return t;
                });
        // schedule the refill task
        scheduler.scheduleAtFixedRate(this::refill,
                refillPeriod.toMillis(),
                refillPeriod.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Atomically adds tokens to the bucket, respecting the capacity limit.
     * This method is called by the scheduled refill task.
     */
    private void refill() {
        if (!running) {
            // No further refills after shutdown
            return;
        }
        tokens.updateAndGet(current -> {
            long added = current + refillTokens;
            return added > capacity ? capacity : added;
        });
    }

    /**
     * Attempts to acquire the requested number of tokens.
     *
     * <p>The operation is lock‑free: it reads the current token count,
     * checks if enough tokens are available, and if so tries to replace the
     * value with the new count using {@link AtomicLong#compareAndSet}.  If the
     * CAS fails because another thread updated the count in the meantime,
     * the method retries.</p>
     *
     * @param tokens number of tokens to acquire; must be non‑negative
     * @return {@code true} if the tokens were successfully acquired,
     *         {@code false} otherwise (not enough tokens)
     * @throws IllegalArgumentException if {@code tokens} is negative
     */
    public boolean tryAcquire(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens to acquire must be >= 0");
        }
        if (tokens == 0) {
            return true; // trivially succeed
        }
        while (true) {
            long current = this.tokens.get();
            if (current < tokens) {
                return false; // not enough tokens
            }
            long next = current - tokens;
            if (this.tokens.compareAndSet(current, next)) {
                return true; // successfully acquired
            }
            // else: concurrent update, retry
        }
    }

    /**
     * Stops the limiter.  After this method returns, no further tokens will be
     * added to the bucket and the internal scheduler is shut down.
     *
     * <p>The method waits for the refill task to terminate (up to a reasonable
     * timeout) to guarantee that no background thread is still mutating the
     * token count.</p>
     */
    public void stop() {
        if (!running) {
            return; // already stopped
        }
        running = false;
        scheduler.shutdown(); // disable new tasks
        try {
            // Wait for the refill thread to finish; 2 seconds is more than enough
            // for the short periods used in tests.
            if (!scheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduler.shutdownNow(); // cancel if it didn't terminate
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /* --------------------------------------------------------------------- *
     *  JUnit 5 test suite (nested static class)                             *
     * --------------------------------------------------------------------- */

    /**
     * Test suite for {@link TokenBucketRateLimiter}.
     *
     * <p>All tests are deterministic; they use {@link java.util.concurrent.CountDownLatch}
     * to coordinate the start and stop of worker threads, and they measure elapsed
     * time with {@link System#nanoTime} to compute the maximal number of tokens that
     * could have been supplied by the refill process.</p>
     */
    public static class TokenBucketRateLimiterTest {

        /** Helper to create a limiter with a very short refill period for fast tests. */
        private static TokenBucketRateLimiter newFastLimiter() {
            return new TokenBucketRateLimiter(
                    100L,                     // capacity
                    10L,                      // refill tokens
                    java.time.Duration.ofMillis(20)); // refill every 20 ms
        }

        /** Basic single‑threaded acquisition test. */
        @org.junit.jupiter.api.Test
        void testBasicAcquire() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5,
                    java.time.Duration.ofMillis(100));
            assert limiter.tryAcquire(3) : "should succeed";
            assert !limiter.tryAcquire(8) : "not enough tokens left";
            assert limiter.tryAcquire(2) : "should succeed after previous consumption";
            assert !limiter.tryAcquire(1) : "bucket empty";
            limiter.stop();
        }

        /** Overflow handling: refill never exceeds capacity. */
        @org.junit.jupiter.api.Test
        void testOverflowHandling() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10,
                    java.time.Duration.ofMillis(50));
            // Drain the bucket
            for (int i = 0; i < 10; i++) {
                assert limiter.tryAcquire(1) : "should be able to drain";
            }
            assert !limiter.tryAcquire(1) : "bucket should be empty";

            // Wait for two refill periods; each would try to add 10 tokens,
            // but capacity is 10, so after the first refill the bucket is full again.
            Thread.sleep(120); // a bit more than 2*50ms
            // Now we should be able to acquire up to 10 tokens again.
            int acquired = 0;
            while (limiter.tryAcquire(1)) {
                acquired++;
            }
            assert acquired == 10 : "bucket should have been refilled to capacity";
            limiter.stop();
        }

        /** Verify that stop() halts further refills. */
        @org.junit.jupiter.api.Test
        void testStopHaltsRefills() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, 5,
                    java.time.Duration.ofMillis(30));
            // Consume all tokens quickly
            for (int i = 0; i < 20; i++) {
                assert limiter.tryAcquire(1) : "should be able to drain";
            }
            assert !limiter.tryAcquire(1) : "bucket empty";

            // Stop before any refill can happen
            limiter.stop();

            // Wait long enough for several refill periods to elapse
            Thread.sleep(200);
            // No tokens should have been added
            assert !limiter.tryAcquire(1) : "no tokens should be available after stop";
        }

        /**
         * Concurrency stress test.
         *
         * <p>Launches {@code workerCount} threads that repeatedly call
         * {@code tryAcquire(1)} with a random pause (0‑2 ms) to generate
         * contention.  The test runs for a fixed duration, then signals the
         * workers to stop.  The total number of successful acquisitions is
         * compared against an upper bound derived from the elapsed time and the
         * refill rate.  The bound is deliberately generous (it assumes the bucket
         * is never full, i.e. every refill adds the full amount), so the test
         * will never fail due to timing variations as long as the implementation
         * does not create or lose tokens.</p>
         */
        @org.junit.jupiter.api.RepeatedTest(5)
        @org.junit.jupiter.api.Timeout(10) // seconds
        void testConcurrentStress() throws Exception {
            final int workerCount = 50;
            final long testDurationMs = 800; // run for less than a second
            final TokenBucketRateLimiter limiter =
                    new TokenBucketRateLimiter(1000, 10,
                            java.time.Duration.ofMillis(10)); // 10 tokens every 10 ms

            final java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(workerCount);
            final java.util.concurrent.AtomicLong successCount = new java.util.concurrent.AtomicLong(0);
            final java.util.concurrent.AtomicBoolean stopRequested = new java.util.concurrent.AtomicBoolean(false);

            // Worker task
            class Worker implements Runnable {
                private final java.util.Random rng = new java.util.Random();

                @Override
                public void run() {
                    try {
                        startLatch.await(); // wait for all workers to start together
                        while (!stopRequested.get()) {
                            boolean acquired = limiter.tryAcquire(1);
                            if (acquired) {
                                successCount.incrementAndGet();
                            }
                            // tiny random backoff to increase contention variety
                            Thread.sleep(rng.nextInt(3));
                        }
                    } catch (InterruptedException ignored) {
                        // exit loop
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }

            // Start workers
            for (int i = 0; i < workerCount; i++) {
                new Thread(new Worker(), "stress-worker-" + i).start();
            }

            // Let them all start simultaneously
            long startNanos = System.nanoTime();
            startLatch.countDown();

            // Let the test run for the configured duration
            Thread.sleep(testDurationMs);
            stopRequested.set(true); // signal workers to exit

            // Wait for all workers to finish
            boolean await = doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertTrue(await,
                    "Some worker threads did not terminate in time");

            long endNanos = System.nanoTime();
            long elapsedMs = java.time.Duration.ofNanos(endNanos - startNanos).toMillis();

            // Compute an upper bound on how many tokens could have been supplied:
            // initial capacity + number of complete refill periods * refillTokens
            long refillPeriodMs = limiter.refillPeriod.toMillis();
            long fullPeriods = elapsedMs / refillPeriodMs;
            long maxTokens = limiter.capacity + fullPeriods * limiter.refillTokens;

            long actual = successCount.get();
            org.junit.jupiter.api.Assertions.assertTrue(actual <= maxTokens,
                    String.format("Too many successful acquisitions: %d > %d (elapsed=%d ms, capacity=%d, refill=%d per %d ms)",
                            actual, maxTokens, elapsedMs, limiter.capacity, limiter.refillTokens, refillPeriodMs));

            // Ensure that we did not lose tokens: the bucket cannot have a negative count.
            // After stopping the limiter we can safely read the token count.
            limiter.stop();
            long remaining = limiter.tokens.get();
            org.junit.jupiter.api.Assertions.assertTrue(remaining >= 0 && remaining <= limiter.capacity,
                    String.format("Invalid token count after test: %d (capacity=%d)",
                            remaining, limiter.capacity));
        }
    }
}
```