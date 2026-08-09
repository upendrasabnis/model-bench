<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: `TokenBucketRateLimiter`

#### 1. Algorithm Overview
The Token Bucket algorithm controls the rate of operations by requiring a "token" for each operation.
*   **Bucket Capacity (`capacity`)**: Maximum tokens the bucket holds (burst allowance).
*   **Refill Rate (`refillRatePerSecond`)**: Tokens added per second (sustained rate).
*   **State**: Current available tokens (`availableTokens`) and last refill timestamp (`lastRefillNanos`).

#### 2. Concurrency Strategy: Lock-Free (CAS Loop)
To achieve high throughput under contention without `synchronized` blocks or `ReentrantLock`, we use an **optimistic locking** strategy via `AtomicLong` and `compareAndSet (CAS)`.

**State Packing:**
We pack two `double` values (`availableTokens`, `lastRefillSeconds`) into a single `long` (64 bits) using `Double.doubleToRawLongBits`. This allows atomic snapshot and update of both variables simultaneously.

*   **Bits 0-31**: `availableTokens` (float precision is sufficient for tokens, but we use double bits for simplicity of packing two doubles into one long? Actually, 2 doubles = 128 bits. We only have 64 bits in `AtomicLong`).
*   **Correction**: A single `AtomicLong` (64 bits) cannot hold two `double`s (128 bits).
*   **Solution**: Use **`AtomicReference<State>`** where `State` is an immutable object holder, OR pack `availableTokens` (double) + `lastRefillNanos` (long) -> 128 bits.
*   **Optimized Packing (Single `AtomicLong`)**:
    *   Store `lastRefillNanos` (long, 64 bits) in an `AtomicLong`.
    *   Store `availableTokens` (double) in a separate `AtomicLong` (using `doubleToRawLongBits`).
    *   *Problem*: Updating two atomics isn't atomic. Race condition between reading tokens and reading time.
*   **Best Practical Lock-Free Approach**: **Single `AtomicReference<State>`**.
    *   `State` is a small immutable record: `record State(double tokens, long lastRefillNanos)`.
    *   CAS loop on the reference. Modern JVMs handle short-lived immutable objects extremely well (Escape Analysis / Scalar Replacement), making allocation overhead negligible.

#### 3. Refill Logic (Continuous)
Tokens are not added in discrete chunks per second. Instead, we calculate the exact elapsed time since `lastRefillNanos` and add `elapsedSeconds * refillRatePerSecond`.
`newTokens = min(capacity, currentTokens + elapsed * rate)`

#### 4. Blocking `acquire(permits)`
1.  **Fast Path**: Try `tryAcquire(permits)`. If success, return.
2.  **Calculate Deficit**: If tokens insufficient, calculate `deficit = permits - availableTokens`.
3.  **Calculate Wait Time**: `waitNanos = (deficit / refillRatePerSecond) * 1_000_000_000`.
4.  **Park**: Use `LockSupport.parkNanos(this, waitNanos)`.
5.  **Retry Loop**: Upon wakeup, loop back to step 1.
    *   *Spurious Wakeups*: Handled naturally by retry loop.
    *   *Interrupts*: `Thread.interrupt()` causes `parkNanos` to return immediately. We check `Thread.interrupted()` and throw `InterruptedException`.

#### 5. Non-Blocking `tryAcquire(permits)` / `tryAcquire(permits, timeout)`
*   `tryAcquire(permits)`: Single CAS attempt. Returns `true`/`false` immediately.
*   `tryAcquire(permits, timeout, unit)`: Calculates deadline. Loops with `tryAcquire`. If fails, parks for `min(remainingTime, calculatedWaitTime)`. Returns `true`/`false`.

#### 6. Fairness & Starvation
*   This implementation is **not strictly fair** (FIFO). Threads race for tokens via CAS.
*   Under high contention, a thread might fail CAS repeatedly (livelock risk).
*   *Mitigation*: `LockSupport.parkNanos` in `acquire` yields CPU, reducing contention. For `tryAcquire` (spin), we rely on CAS hardware efficiency.

---

### Implementation: `TokenBucketRateLimiter.java`

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread-safe, high-throughput Token Bucket Rate Limiter.
 * <p>
 * Features:
 * <ul>
 *   <li>Configurable refill rate (tokens/second) and burst capacity.</li>
 *   <li>Lock-free implementation using {@link AtomicReference} and CAS loops.</li>
 *   <li>Blocking {@link #acquire(long)} and non-blocking {@link #tryAcquire(long)}.</li>
 *   <li>Supports acquiring multiple permits at once.</li>
 * </ul>
 *
 * @implNote Uses an immutable {@link State} holder updated via CAS. Relies on JVM Escape Analysis
 * to eliminate allocation overhead of State objects in the hot path.
 */
public final class TokenBucketRateLimiter {

    private final double refillRatePerSecond;
    private final double capacity;
    private final AtomicReference<State> stateRef;

    /**
     * Immutable snapshot of bucket state.
     */
    private record State(double availableTokens, long lastRefillNanos) {}

    /**
     * Creates a new rate limiter.
     *
     * @param refillRatePerSecond tokens added per second (must be > 0)
     * @param capacity            maximum bucket size / burst allowance (must be >= refillRatePerSecond)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (capacity < refillRatePerSecond) {
            throw new IllegalArgumentException("Capacity must be >= refill rate");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        // Start full
        this.stateRef = new AtomicReference<>(new State(capacity, System.nanoTime()));
    }

    /**
     * Acquires the given number of permits from this limiter, blocking until available.
     * Equivalent to {@code tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)}.
     *
     * @param permits the number of permits to acquire (must be > 0 and <= capacity)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed bucket capacity");

        if (tryAcquire(permits)) {
            return;
        }

        long deadline = Long.MAX_VALUE; // Infinite wait
        awaitPermits(permits, deadline);
    }

    /**
     * Acquires permits if available immediately, without blocking.
     *
     * @param permits the number of permits to acquire (must be > 0 and <= capacity)
     * @return {@code true} if permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed bucket capacity");

        return tryAcquireImpl(permits, 0); // 0 timeout = non-blocking
    }

    /**
     * Attempts to acquire permits within the given timeout.
     *
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if permits were acquired, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0 or permits > capacity
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed bucket capacity");
        if (timeout <= 0) return tryAcquire(permits);

        long deadline = System.nanoTime() + unit.toNanos(timeout);
        return awaitPermits(permits, deadline);
    }

    /**
     * Core acquisition logic with deadline.
     * Returns true if acquired, false if deadline passed (only for tryAcquire with timeout).
     * Throws InterruptedException if interrupted (only for acquire/tryAcquire with timeout).
     */
    private boolean awaitPermits(long permits, long deadline) throws InterruptedException {
        while (true) {
            // 1. Try fast path
            if (tryAcquireImpl(permits, deadline)) {
                return true;
            }

            // 2. Check interruption (for blocking acquire / timed acquire)
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // 3. Check deadline (for timed acquire)
            long now = System.nanoTime();
            if (now >= deadline) {
                return false; // Timeout
            }

            // 4. Calculate wait time based on current deficit
            State current = stateRef.get(); // Volatile read
            double deficit = permits - current.availableTokens();
            // Time needed to generate 'deficit' tokens
            long waitNanos = (long) ((deficit / refillRatePerSecond) * 1_000_000_000L);

            // Clamp wait time to remaining deadline
            long remainingNanos = deadline - now;
            if (waitNanos > remainingNanos) {
                waitNanos = remainingNanos;
            }

            // Minimum park granularity ~ microseconds. If wait < 1ms, spin/yield might be better,
            // but parkNanos handles small values reasonably on modern OS/JVM.
            if (waitNanos > 0) {
                LockSupport.parkNanos(this, waitNanos);
            } else {
                // Yield if wait time calculated as 0 or negative (shouldnt happen if deficit>0)
                Thread.yield();
            }
        }
    }

    /**
     * Attempts to consume permits via CAS loop.
     *
     * @param permits  permits to take
     * @param deadline 0 for non-blocking (fail fast), >0 for deadline nanos (used to calc refill)
     * @return true if success
     */
    private boolean tryAcquireImpl(long permits, long deadline) {
        while (true) {
            State current = stateRef.get();
            long now = System.nanoTime();

            // Refill calculation
            double elapsedSeconds = (now - current.lastRefillNanos()) / 1_000_000_000.0;
            double refilled = Math.min(capacity, current.availableTokens() + elapsedSeconds * refillRatePerSecond);

            if (refilled >= permits) {
                State next = new State(refilled - permits, now);
                if (stateRef.compareAndSet(current, next)) {
                    return true;
                }
                // CAS failed: contention, retry loop
            } else {
                // Not enough tokens
                if (deadline == 0) {
                    return false; // Non-blocking fast fail
                }
                // For blocking/timed: we return false to trigger park/wait logic in caller
                return false;
            }
        }
    }

    // --- Getters for Monitoring/Testing ---

    public double getRefillRatePerSecond() { return refillRatePerSecond; }
    public double getCapacity() { return capacity; }

    /**
     * Returns current available tokens (approximate, no synchronization guarantee on exact instant).
     */
    public double getAvailableTokens() {
        State s = stateRef.get();
        long now = System.nanoTime();
        double elapsed = (now - s.lastRefillNanos()) / 1_000_000_000.0;
        return Math.min(capacity, s.availableTokens() + elapsed * refillRatePerSecond);
    }
}
```

---

### JUnit 5 Tests: `TokenBucketRateLimiterTest.java`

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final double RATE = 1000.0; // 1000 tokens/sec
    private static final double CAPACITY = 2000.0; // Burst 2000
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(RATE, CAPACITY);
    }

    // ---------------------------------------------------------
    // Basic Functionality
    // ---------------------------------------------------------

    @Test
    void testInitialBurst() {
        // Should allow full capacity immediately
        assertTrue(limiter.tryAcquire((long) CAPACITY));
        assertFalse(limiter.tryAcquire(1)); // Empty
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        limiter.tryAcquire((long) CAPACITY); // Drain
        assertFalse(limiter.tryAcquire(1));

        // Wait for 1 token (1ms at 1000/s)
        Thread.sleep(2);
        assertTrue(limiter.tryAcquire(1));
    }

    @Test
    void testAcquireBlocking() throws InterruptedException {
        limiter.tryAcquire((long) CAPACITY); // Drain

        Thread t = new Thread(() -> {
            try { Thread.sleep(10); limiter.tryAcquire(1); } catch (InterruptedException ignored) {}
        });
        t.start();

        // Should block until token available (~1ms)
        long start = System.nanoTime();
        limiter.acquire(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 5 && elapsedMs < 100, "Should have waited for refill");
        t.join();
    }

    @Test
    void testTryAcquireTimeoutSuccess() throws InterruptedException {
        limiter.tryAcquire((long) CAPACITY); // Drain
        // Wait 5ms -> 5 tokens. Timeout 100ms.
        assertTrue(limiter.tryAcquire(5, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTryAcquireTimeoutFailure() {
        limiter.tryAcquire((long) CAPACITY); // Drain
        // Need 10 tokens (10ms), but timeout 1ms
        assertFalse(limiter.tryAcquire(10, 1, TimeUnit.MILLISECONDS));
    }

    @Test
    void testInterruptDuringAcquire() {
        limiter.tryAcquire((long) CAPACITY); // Drain
        Thread t = Thread.currentThread();
        new Thread(() -> { try { Thread.sleep(10); t.interrupt(); } catch (InterruptedException ignored) {} }).start();

        assertThrows(InterruptedException.class, () -> limiter.acquire(1));
        assertTrue(Thread.interrupted()); // Clear flag
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void testInvalidPermitsArgument(long permits) {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(permits));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(permits));
    }

    @Test
    void testPermitsExceedCapacity() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire((long) CAPACITY + 1));
    }

    // ---------------------------------------------------------
    // Concurrency & Correctness
    // ---------------------------------------------------------

    @Test
    void testConcurrentAccessThreadSafety() throws InterruptedException {
        int threads = 100;
        int permitsPerThread = 100;
        long totalPermits = (long) threads * permitsPerThread;

        // Use a limiter with huge capacity so we don't block, just test CAS contention
        TokenBucketRateLimiter stressLimiter = new TokenBucketRateLimiter(1_000_000, 1_000_000);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try { startLatch.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < permitsPerThread; j++) {
                    if (stressLimiter.tryAcquire(1)) successCount.incrementAndGet();
                }
                endLatch.countDown();
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(totalPermits, successCount.get(), "All permits should be acquired under contention");
    }

    // ---------------------------------------------------------
    // Long-Run Rate Limit Verification (The Critical Test)
    // ---------------------------------------------------------

    /**
     * Verifies that over a sustained period, the limiter does not allow throughput
     * exceeding the configured refill rate (within statistical tolerance).
     * <p>
     * Strategy: Run N threads hammering {@code acquire(1)} for a fixed duration.
     * Measure total acquired. Expected ~= Rate * Duration.
     * Burst capacity allows initial spike, but long-run average must converge to Rate.
     */
    @Test
    void testLongRunRateLimit() throws InterruptedException {
        // Config: 10,000 tokens/sec, Capacity 10,000 (1 sec burst)
        double rate = 10_000.0;
        double capacity = 10_000.0;
        TokenBucketRateLimiter testLimiter = new TokenBucketRateLimiter(rate, capacity);

        int threadCount = 16; // High contention
        int durationSeconds = 3; // Long enough to smooth out burst
        long durationNanos = TimeUnit.SECONDS.toNanos(durationSeconds);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        LongAdder totalAcquired = new LongAdder();
        AtomicLong startTime = new AtomicLong(-1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        Runnable worker = () -> {
            try {
                startLatch.await(); // Synchronized start
                if (startTime.get() == -1) startTime.set(System.nanoTime());

                long deadline = startTime.get() + durationNanos;
                while (System.nanoTime() < deadline) {
                    // acquire(1) blocks if necessary, perfectly simulating load
                    testLimiter.acquire(1);
                    totalAcquired.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) executor.submit(worker);

        startLatch.countDown(); // GO
        boolean finished = endLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        executor.shutdownNow(); // Force stop any stragglers

        assertTrue(finished, "Test timed out");

        long acquired = totalAcquired.sum();
        double expected = rate * durationSeconds;
        // Tolerance: Allow burst (capacity) + small scheduling overhead.
        // Max theoretical = Rate * Time + Capacity.
        double maxAllowed = expected + capacity + 100; // +100 for edge effects

        System.out.printf("Long Run Test: Acquired=%d, Expected~=%.0f, MaxAllowed=%.0f, Rate=%.2f/sec%n",
                acquired, expected, maxAllowed, acquired / (double) durationSeconds);

        // 1. Must not exceed hard theoretical max (Rate * Time + Capacity)
        assertTrue(acquired <= maxAllowed,
                String.format("Rate limit exceeded! Acquired %d > Max Allowed %.0f (Rate*Time + Capacity)", acquired, maxAllowed));

        // 2. Should be reasonably close to expected (not starved)
        // Allow 10% undershoot due to thread scheduling/park granularity at end of window
        assertTrue(acquired >= expected * 0.90,
                String.format("Throughput too low (starvation?). Acquired %d < Expected %.0f", acquired, expected * 0.90));
    }

    @Test
    void testBurstThenSustained() throws InterruptedException {
        // Verify burst works, then rate limits
        double rate = 1000.0;
        double cap = 1000.0;
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(rate, cap);

        // 1. Burst
        assertTrue(l.tryAcquire((long) cap), "Initial burst failed");
        assertEquals(0, l.getAvailableTokens(), 0.1);

        // 2. Sustained: Try to acquire 2000 over 2 seconds.
        // Should take ~2 seconds (1000 burst + 1000 refilled over 1s + 1000 refilled over next 1s? No.)
        // Logic: Bucket empty. Need 2000 tokens. Rate 1000/s. Takes 2 seconds.
        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) l.acquire(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Should be very close to 2000ms. Allow 100ms slack for scheduling.
        assertTrue(elapsedMs >= 1900 && elapsedMs <= 2200,
                "Sustained rate incorrect. Elapsed: " + elapsedMs + "ms");
    }
}
```

### Key Implementation Details Summary

1.  **`AtomicReference<State>`**: Avoids `synchronized` bottlenecks. `State` is a `record` (immutable, compact).
2.  **Continuous Refill**: `elapsedSeconds * rate` calculated on every CAS attempt. No background thread needed (avoids "timer drift" and thread management).
3.  **`LockSupport.parkNanos`**: Efficient blocking. Responds to `interrupt()`.
4.  **CAS Loop in `tryAcquireImpl`**: Handles contention. If CAS fails, it re-reads state (which includes latest time) and recalculates. This naturally handles the "time passed while spinning" case.
5.  **Test `testLongRunRateLimit`**:
    *   Uses `LongAdder` for high-throughput counting.
    *   Uses `CountDownLatch` for synchronized start (removes ramp-up bias).
    *   Asserts **Hard Upper Bound**: `Acquired <= Rate * Time + Capacity`. This is the mathematical guarantee of Token Bucket.
    *   Asserts **Liveness**: `Acquired >= 0.9 * Rate * Time` (prevents deadlock/starvation bugs).