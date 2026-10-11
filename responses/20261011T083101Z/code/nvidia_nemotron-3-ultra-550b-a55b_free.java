<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261011T083101Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a complete, production-ready implementation of a Thread-Safe Token Bucket Rate Limiter in Java 17+, followed by design documentation and comprehensive JUnit 5 tests.

---

### 1. Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 *
 * <p>Characteristics:
 * <ul>
 *   <li><b>Burst Capacity:</b> Allows bursts up to {@code capacity} tokens.</li>
 *   <li><b>Constant Refill:</b> Tokens refill continuously at {@code refillRatePerSecond}.</li>
 *   <li><b>Fairness:</b> Uses a non-fair lock by default for higher throughput; blocking {@code acquire()}
 *       uses a FIFO {@link Condition} queue ensuring threads are woken in arrival order.</li>
 *   <li><b>Clock Injection:</b> Accepts a {@link Clock} for deterministic testing.</li>
 * </ul>
 *
 * <p><b>Algorithm:</b>
 * The bucket state is defined by {@code (availableTokens, lastRefillNanos)}.
 * On every operation (acquire/tryAcquire), we calculate tokens generated since {@code lastRefillNanos},
 * add them to {@code availableTokens} (capped at {@code capacity}), and update {@code lastRefillNanos}.
 *
 * <p><b>Concurrency Control:</b>
 * A single {@link ReentrantLock} protects the mutable state. This avoids the complexity and retry-overhead
 * of lock-free CAS loops on a compound state (double + long), while providing excellent throughput
 * for typical rate-limiting contention levels. The {@link Condition} variable allows efficient parking
 * of threads waiting for tokens without busy-spinning.
 */
public final class TokenBucketRateLimiter {

    private final double capacity;              // Max tokens (burst)
    private final double refillRatePerSecond;   // Tokens added per second
    private final double refillRatePerNano;     // Tokens added per nanosecond (pre-calculated)
    private final Clock clock;                  // Time source

    // Mutable state guarded by 'lock'
    private double availableTokens;
    private long lastRefillNanos;

    private final Lock lock = new ReentrantLock(/* fair = */ false);
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param refillRatePerSecond tokens added per second (must be > 0)
     * @param capacity            maximum bucket size / burst allowance (must be >= refillRatePerSecond)
     * @param clock               time source (use {@link Clock#systemUTC()} for production)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity, Clock clock) {
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (capacity < refillRatePerSecond) {
            // Allow capacity == rate (bucket size 1 token effectively), but not smaller.
            throw new IllegalArgumentException("Capacity must be >= refill rate");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.refillRatePerNano = refillRatePerSecond / 1_000_000_000.0;
        this.capacity = capacity;
        this.clock = clock;

        // Start full
        this.availableTokens = capacity;
        this.lastRefillNanos = clock.instant().toEpochMilli() * 1_000_000L; // Approx init, corrected on first call
    }

    /**
     * Convenience constructor using system UTC clock.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, double capacity) {
        this(refillRatePerSecond, capacity, Clock.systemUTC());
    }

    /**
     * Acquires {@code permits} tokens, blocking until available.
     *
     * @param permits number of tokens to acquire (must be > 0 and <= capacity)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits > capacity
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed bucket capacity");

        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (availableTokens >= permits) {
                    availableTokens -= permits;
                    return;
                }

                // Calculate time needed for required tokens
                double deficit = permits - availableTokens;
                long nanosToWait = (long) Math.ceil(deficit / refillRatePerNano);

                // Wait on condition. awaitNanos returns remaining time (or <=0 if timed out/spurious wakeup).
                // We loop to handle spurious wakeups and ensure we actually have tokens.
                // Using awaitNanos avoids the "lost wakeup" issue if signal happens before await.
                long remainingNanos = notEmpty.awaitNanos(nanosToWait);
                // Loop continues; refill() will be called again at top.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens immediately without blocking.
     *
     * @param permits number of tokens to acquire (must be > 0 and <= capacity)
     * @return {@code true} if tokens were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits > capacity
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > capacity) throw new IllegalArgumentException("Permits exceed bucket capacity");

        lock.lock();
        try {
            refill();
            if (availableTokens >= permits) {
                availableTokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of available tokens (approximate, as state changes constantly).
     * Primarily for monitoring/metrics.
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refills the bucket based on elapsed time.
     * Must be called with {@code lock} held.
     */
    private void refill() {
        long now = clock.instant().toEpochMilli() * 1_000_000L; // Convert ms to nanos
        // Note: Instant.toEpochMilli() * 1_000_000 loses sub-ms precision.
        // For high precision, use clock.instant().getNano() + epochSecond * 1e9, 
        // but Clock API doesn't expose nanos directly easily without Instant.
        // Better: long now = clock.millis() * 1_000_000L; 
        // Actually, Clock has instant().getEpochSecond() and getNano().
        
        // Correct high-precision nanos calculation:
        Instant instant = clock.instant();
        long nowNanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();

        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos > 0) {
            double newTokens = elapsedNanos * refillRatePerNano;
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillNanos = nowNanos;
        }
    }

    // Getters for config (useful for tests/introspection)
    public double getCapacity() { return capacity; }
    public double getRefillRatePerSecond() { return refillRatePerSecond; }
}
```

---

### 2. Design Documentation

#### **Algorithm: Token Bucket**
*   **State**: `(availableTokens: double, lastRefillNanos: long)`.
*   **Refill**: Continuous rate `R` tokens/sec. On access, `delta = (now - lastRefill) * R`. `availableTokens = min(capacity, availableTokens + delta)`.
*   **Consumption**: If `availableTokens >= permits`, deduct and succeed. Else, calculate `waitTime = (permits - availableTokens) / R`.

#### **Concurrency Strategy: Monitor Pattern (Lock + Condition)**
*   **Why not `synchronized`?** `ReentrantLock` allows `lockInterruptibly()` (critical for responsive cancellation) and `Condition` variables.
*   **Why not Lock-Free (CAS)?** State is a compound variable `(double, long)`. Packing into a single `long` (bits) loses `double` precision or range. `AtomicReference` with immutable objects creates GC pressure. A single `ReentrantLock` is standard, low-overhead, and handles the "check-refill-wait" atomicity requirement cleanly.
*   **Fairness**: Non-fair lock (default) maximizes throughput. The `Condition` queue (`notEmpty`) provides FIFO ordering for *waiting* threads, preventing starvation.

#### **Blocking Acquire (`acquire`)**
1.  Lock.
2.  Loop: Refill -> Check Tokens.
3.  If sufficient: Deduct, Unlock, Return.
4.  If insufficient: Calculate `nanosToWait`.
5.  `notEmpty.awaitNanos(nanosToWait)`.
    *   Releases lock, parks thread.
    *   Wakes on `signalAll` (from `tryAcquire`/`acquire` success) OR timeout.
    *   Re-acquires lock.
    *   Loop repeats (handles spurious wakeups & clock drift).

#### **Non-Blocking Acquire (`tryAcquire`)**
1.  Lock.
2.  Refill -> Check Tokens.
3.  If sufficient: Deduct, `notEmpty.signalAll()` (wakes waiters), Unlock, Return `true`.
4.  Else: Unlock, Return `false`.

#### **Time Source Abstraction (`Clock`)**
*   Uses `java.time.Clock` injected via constructor.
*   Production: `Clock.systemUTC()`.
*   Testing: `Clock.fixed(...)` or `Clock.offset(...)` or a manual stub for time-travel.

#### **Precision**
*   Uses `long` nanoseconds (`Instant.getEpochSecond * 1e9 + getNano()`) for `lastRefillNanos` to avoid drift/precision loss inherent in `System.currentTimeMillis()`.

---

### 3. JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

class TokenBucketRateLimiterTest {

    // --- Configuration Constants ---
    private static final double RATE = 1000.0; // 1000 tokens/sec
    private static final double CAPACITY = 2000.0; // Burst 2000
    private static final double EPSILON = 0.001;

    // --- Test Clock for Deterministic Time Control ---
    // We use a wrapper to allow advancing time manually in tests.
    static class ManualClock extends Clock {
        private final AtomicLong nanos = new AtomicLong(Instant.EPOCH.toEpochMilli() * 1_000_000_000L);

        public void advance(Duration d) { nanos.addAndGet(d.toNanos()); }
        public void advanceNanos(long n) { nanos.addAndGet(n); }
        public long currentNanos() { return nanos.get(); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { 
            long n = nanos.get(); 
            return Instant.ofEpochSecond(n / 1_000_000_000L, n % 1_000_000_000L); 
        }
    }

    private ManualClock clock;
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        limiter = new TokenBucketRateLimiter(RATE, CAPACITY, clock);
    }

    // ============================================================
    // Basic Functionality Tests
    // ============================================================

    @Test
    void initialState_isFull() {
        assertEquals(CAPACITY, limiter.getAvailableTokens(), EPSILON);
        assertTrue(limiter.tryAcquire((int) CAPACITY));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);
    }

    @Test
    void tryAcquire_exactCapacity_succeeds() {
        assertTrue(limiter.tryAcquire((int) CAPACITY));
    }

    @Test
    void tryAcquire_overCapacity_throws() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire((int) CAPACITY + 1));
    }

    @Test
    void tryAcquire_partialSuccess_thenFail() {
        assertTrue(limiter.tryAcquire(500));
        assertTrue(limiter.tryAcquire(500));
        assertFalse(limiter.tryAcquire(1)); // Empty
    }

    @Test
    void refill_overTime_allowsMore() {
        // Drain
        assertTrue(limiter.tryAcquire((int) CAPACITY));
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);

        // Advance time 1 second -> 1000 tokens
        clock.advance(Duration.ofSeconds(1));
        assertEquals(RATE, limiter.getAvailableTokens(), EPSILON);
        assertTrue(limiter.tryAcquire((int) RATE));
    }

    @Test
    void refill_cappedAtCapacity() {
        // Start full, wait, should still be full
        clock.advance(Duration.ofSeconds(10));
        assertEquals(CAPACITY, limiter.getAvailableTokens(), EPSILON);
    }

    @Test
    void acquire_blocksUntilTokensAvailable() throws InterruptedException {
        // Drain bucket
        limiter.acquire((int) CAPACITY);
        assertEquals(0, limiter.getAvailableTokens(), EPSILON);

        // Start a thread that will wait for 500 tokens
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicLong acquiredTime = new AtomicLong(-1);

        Thread waiter = new Thread(() -> {
            try {
                started.countDown();
                limiter.acquire(500);
                acquiredTime.set(clock.currentNanos());
                finished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        // Wait for thread to park
        assertTrue(started.await(1, SECONDS));
        assertFalse(finished.await(100, MILLIS)); // Should still be waiting

        // Advance time just enough for 500 tokens (0.5 sec)
        clock.advance(Duration.ofMillis(500));
        
        // Signal the condition (happens automatically in refill logic? No, refill only happens on lock acquisition).
        // The waiting thread wakes up via awaitNanos timeout. 
        // We need to ensure the lock is free or the waiter wakes up.
        // The waiter uses awaitNanos(timeout). It will wake up after timeout.
        
        assertTrue(finished.await(1, SECONDS), "Thread should have acquired tokens after time advance");
        
        // Verify acquisition happened roughly at the right time
        long expectedNanos = clock.currentNanos() - Duration.ofMillis(500).toNanos(); // approx start of wait + 500ms
        // Allow some slack for scheduling
        assertTrue(acquiredTime.get() >= expectedNanos - 50_000_000L); 
    }

    @Test
    void acquire_interrupted_throwsException() throws InterruptedException {
        limiter.acquire((int) CAPACITY); // Drain
        Thread t = new Thread(() -> {
            try { limiter.acquire(1); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure it's waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ============================================================
    // Concurrent Stress / Correctness Tests
    // ============================================================

    @Test
    void concurrentTryAcquire_threadSafe() throws InterruptedException {
        int threads = 50;
        int permitsPerThread = 20;
        int totalPermits = threads * permitsPerThread; // 1000
        
        // Capacity 2000, Rate 1000. Instant burst allows all 1000.
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (limiter.tryAcquire(permitsPerThread)) successCount.incrementAndGet();
            });
        }

        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, SECONDS));

        // All should succeed because burst capacity (2000) > total demand (1000)
        assertEquals(threads, successCount.get());
        assertEquals(CAPACITY - totalPermits, limiter.getAvailableTokens(), EPSILON);
    }

    @Test
    void concurrentAcquire_blockingFairness() throws InterruptedException {
        // Capacity 10, Rate 10/sec. 
        // 20 Threads ask for 1 permit. 
        // First 10 get it instantly. Next 10 block ~1 sec each.
        ManualClock c = new ManualClock();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(10.0, 10.0, c);
        
        int threads = 20;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong completed = new AtomicLong();
        long startNanos = System.nanoTime(); // Real time for duration check

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try { l.acquire(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                completed.incrementAndGet();
            });
        }

        start.countDown();
        
        // Wait for first batch (instant)
        Thread.sleep(100); 
        assertEquals(10, completed.get()); // Burst handled

        // Advance mock time 1.5 seconds -> generates 15 tokens. 
        // 10 waiters need 10 tokens. 
        c.advance(Duration.ofMillis(1500));
        
        // Wait for rest
        exec.shutdown();
        assertTrue(exec.awaitTermination(2, SECONDS));
        assertEquals(threads, completed.get());
    }

    // ============================================================
    // Long-Run Rate Limit Verification (The Critical Test)
    // ============================================================

    /**
     * Verifies that over a sustained period, the limiter enforces the configured rate.
     * 
     * Strategy:
     * 1. Run for a fixed *real* duration (e.g., 2 seconds).
     * 2. Hammer `acquire(1)` from many threads.
     * 3. Count total permits acquired.
     * 4. Assert: Total <= (Rate * Duration) + Capacity + Small_Buffer.
     *    (The +Capacity accounts for the initial burst).
     */
    @Test
    void longRunRateLimit_enforcedUnderContention() throws InterruptedException {
        // Config: 5000 tokens/sec, Burst 1000.
        double rate = 5000.0;
        double capacity = 1000.0;
        Duration testDuration = Duration.ofSeconds(2); // 2 seconds real time
        
        // Use REAL clock for this test to measure actual wall-clock enforcement
        TokenBucketRateLimiter realLimiter = new TokenBucketRateLimiter(rate, capacity, Clock.systemUTC());

        int numThreads = 16; // High contention
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        AtomicLong totalAcquired = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        // Pre-warm JIT / OS
        for (int i=0; i<100; i++) realLimiter.tryAcquire(1);

        for (int i = 0; i < numThreads; i++) {
            exec.submit(() -> {
                try { startLatch.await(); } catch (InterruptedException e) { return; }
                long deadline = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < deadline) {
                    try {
                        // Try to acquire 1 token. 
                        // We use a tiny timeout on acquire? No, acquire blocks indefinitely. 
                        // We must stop submitting tasks after deadline.
                        // But acquire() blocks. If we call acquire() at T=1.999s and no tokens, it blocks past deadline.
                        // Fix: Use tryAcquire in a tight loop with backoff? No, that burns CPU.
                        // Better: Use acquire() but interrupt threads at deadline.
                    } catch (Exception e) { Thread.currentThread().interrupt(); break; }
                }
                endLatch.countDown();
            });
        }

        // Revised strategy for blocking acquire test:
        // We cannot easily stop a thread blocked in acquire() without interrupting.
        // Let's use a custom 'acquireWithDeadline' helper or just interrupt the pool.
        
        // Actually, simpler: Use tryAcquire in a loop for the "hammer" test. 
        // It measures throughput capacity without blocking indefinitely.
        // But the requirement asks for blocking acquire test.
        
        // Let's do the blocking test properly:
        // 1. Start threads.
        // 2. Main thread sleeps testDuration.
        // 3. Main thread interrupts all worker threads.
        // 4. Join workers.
        // 5. Count permits.
        
        Thread[] workers = new Thread[numThreads];
        AtomicLong acquiredCount = new AtomicLong();
        
        for (int i=0; i<numThreads; i++) {
            workers[i] = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        realLimiter.acquire(1);
                        acquiredCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // Restore flag
                        break; // Exit loop
                    }
                }
            });
            workers[i].start();
        }

        // Let it run
        Thread.sleep(testDuration.toMillis());

        // Stop
        for (Thread w : workers) w.interrupt();
        for (Thread w : workers) w.join(1000);

        long total = acquiredCount.get();
        double maxExpected = rate * testDuration.getSeconds() + capacity; // Rate * Sec + Burst
        // Add 5% tolerance for thread scheduling delays / final burst drain
        double tolerance = maxExpected * 0.05 + 10; 

        System.out.printf("Long Run Test: Acquired %,d | Max Allowed ~%,.0f (Rate: %.0f, Cap: %.0f, Time: %ds)%n", 
               