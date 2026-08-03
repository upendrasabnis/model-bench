<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260803T111708Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Java Implementation**

```java
package com.example.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket starts full (i.e. {@code tokens == capacity}).  Tokens are
 * added continuously at a configured {@code refillRatePerSecond}.  When a
 * caller wants to {@code acquire} permits, the implementation first refills
 * the bucket based on the elapsed time since the last update, then checks
 * whether enough tokens are available.</p>
 *
 * <p>Two acquisition modes are provided:</p>
 * <ul>
 *   <li>{@link #tryAcquire(int)} – non‑blocking; returns {@code true} iff
 *       the requested permits are immediately available.</li>
 *   <li>{@link #acquire(int)} – blocking; waits (with interruption support)
 *       until the permits can be granted.</li>
 * </ul>
 *
 * <p>The implementation uses a single {@link ReentrantLock} together with a
 * {@link Condition} to make the blocking acquire efficient and to avoid
 * busy‑waiting.  All state updates happen while holding the lock, guaranteeing
 * thread‑safety.</p>
 *
 * <p>The algorithm tolerates spurious wake‑ups (the condition await is placed
 * inside a loop that re‑checks the token count).  Fractional tokens are used
 * internally to avoid losing precision when the refill rate is not an integer
 * number of tokens per nanosecond.</p>
 *
 * @author  Generated for the answer
 */
public final class TokenBucketRateLimiter {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /** Maximum number of tokens the bucket can hold (burst capacity). */
    private final double capacity;

    /** Rate at which tokens are added, expressed as tokens per second. */
    private final double refillRatePerSecond;

    /** Nanosecond timestamp of the last refill operation. */
    private volatile long lastRefillNanos;

    /** Current number of tokens in the bucket (may be fractional). */
    private volatile double tokens;

    /**
     * Creates a new rate limiter.
     *
     * @param burstCapacity      the maximum number of tokens the bucket can hold.
     *                           Must be > 0.
     * @param refillRatePerSecond the average number of tokens added each second.
     *                           Must be > 0.
     */
    public TokenBucketRateLimiter(int burstCapacity, double refillRatePerSecond) {
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        this.capacity = burstCapacity;
        this.refillRatePerSecond = refillRatePerSecond;
        // start with a full bucket
        this.tokens = burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * Called while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return; // no time passed
        }
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRatePerSecond;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire; must be > 0.
     * @return {@code true} if the permits were granted, {@code false} otherwise.
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
     * Acquires the given number of permits, blocking until they are available
     * or the current thread is interrupted.
     *
     * @param permits number of permits to acquire; must be > 0.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return; // permits granted
                }
                // Not enough tokens – compute how long we need to wait.
                double deficit = permits - tokens; // > 0
                double waitSeconds = deficit / refillRatePerSecond;
                long waitNanos = (long) (waitSeconds * 1_000_000_000.0);
                // Guard against overflow / negative wait due to rounding.
                if (waitNanos <= 0) {
                    waitNanos = 1L;
                }
                // awaitNanos returns remaining time; we loop to re‑check.
                long remaining = notEmpty.awaitNanos(waitNanos);
                // If remaining > 0 we were awakened early (spurious or signal);
                // loop will refill again and re‑evaluate.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens in the bucket (mainly for testing).
     * The value is a snapshot and may be stale by the time it is read.
     *
     * @return current token count (fractional).
     */
    double getTokens() {
        return tokens;
    }

    /**
     * Returns the configured burst capacity.
     *
     * @return burst capacity as a double (identical to the int supplied in the ctor).
     */
    double getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     *
     * @return refill rate.
     */
    double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
```

---

### Design Documentation

| Aspects
1. **Token Bucket Algorithm**  
   *Tokens* represent permission to proceed. The bucket holds at most `capacity` tokens (the burst). Tokens are added continuously at `refillRatePerSecond`. When a thread wants to run, it consumes the required number of tokens; if none are available it must wait until enough have been added.

2. **Thread‑Safety**  
   All mutable state (`tokens`, `lastRefillNanos`) is accessed only while holding a `ReentrantLock`. The lock also guards the `Condition` used for blocking waits, guaranteeing that only one thread can refill or modify the bucket at a time.

3. **Blocking Acquire**  
   After refilling, if insufficient tokens are present we compute the exact wait time needed to accumulate the deficit (`deficit / rate`). The thread then waits on the condition variable for that duration (using `awaitNanos`). Spurious wake‑ups are handled by looping and re‑checking the token count.

4. **Non‑Blocking TryAcquire**  
   Simply refill and test; if enough tokens exist, consume them and return `true`. No waiting, no lock contention beyond the brief critical section.

5. **Fractional Tokens**  
   Storing tokens as `double` prevents loss of precision when the refill rate does not divide evenly into nanoseconds. The bucket is considered full when `tokens == capacity` (within floating‑point tolerance, which is acceptable for rate‑limiting purposes).

6. **Correctness Guarantees**  
   *Safety*: The number of permits granted in any interval cannot exceed `capacity + rate * interval length`.  
   *Liveness*: A thread calling `acquire` will eventually proceed unless it is interrupted, because time always advances and tokens are continuously added.

7. **Performance**  
   The critical section is tiny (a few arithmetic operations and a condition wait). Contention is low because threads spend most of their time either sleeping (in the condition wait) or doing work outside the limiter.

---

## JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * The tests cover:
 *   * basic tryAcquire behavior,
 *   * blocking acquire with timing,
 *   * concurrent usage and long‑run rate verification.
 */
class TokenBucketRateLimiterTest {

    /** Helper to wait roughly a given duration while tolerating JVM scheduling jitter. */
    private static void sleepFor(Duration d) throws InterruptedException {
        Thread.sleep(d.toMillis());
        // nanosleep for the remainder (if any)
        long remainingNanos = d.minus(Duration.ofMillis(d.toMillis())).toNanos();
        if (remainingNanos > 0) {
            Thread.sleep(0, (int) remainingNanos);
        }
    }

    @Test
    void tryAcquire_immediateAndExhausted() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10); // 5 burst, 10 tokens/s
        assertTrue(limiter.tryAcquire(3), "First acquire should succeed");
        assertTrue(limiter.tryAcquire(2), "Second acquire should succeed (bucket empty)");
        assertFalse(limiter.tryAcquire(1), "Third acquire should fail – no tokens left");
        // Wait for tokens to refill: need 1 token @ 10 per/s => 0.1 s
        sleepFor(Duration.ofMillis(150));
        assertTrue(limiter.tryAcquire(1), "After refill, one token should be available");
    }

    @Test
    void acquire_blocksUntilTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1); // 2 burst, 1 token/s
        // Drain the bucket
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(1));

        long start = System.nanoTime();
        limiter.acquire(1); // should block ~1 second
        long elapsedNs = System.nanoTime() - start;
        Duration elapsed = Duration.ofNanos(elapsedNs);
        // Allow 20% tolerance for scheduling jitter
        assertTrue(elapsed.compareTo(Duration.ofMillis(800)) >= 0,
                "acquire should have waited at least ~0.8s");
        assertTrue(elapsed.compareTo(Duration.ofMillis(1200)) <= 0,
                "acquire should not have waited excessively long");
    }

    @Test
    @Timeout(value = 30) // safeguard against a deadlock
    void concurrentLongRunRateDoesNotExceedLimit() throws Exception {
        final double ratePerSec = 5.0;   // 5 tokens per second
        final int burst = 10;            // allow bursts of up to 10
        final Duration testDuration = Duration.ofSeconds(10);
        final int permitsPerCall = 1;

        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(burst, ratePerSec);

        ExecutorService exec = Executors.newFixedThreadPool(20);
        AtomicLong successCount = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(20);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                startLatch.await(); // wait for all threads to start together
                long localSuccess = 0;
                long endTime = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < endTime) {
                    if (limiter.tryAcquire(permitsPerCall)) {
                        localSuccess++;
                    }
                    // tiny pause to avoid hogging the CPU; not required for correctness
                    Thread.sleep(0, 100); // 100 ns sleep (practically a yield)
                }
                successCount.addAndGet(localSuccess);
                doneLatch.countDown();
                return null;
            });
        }
        List<Future<Void>> futures = exec.invokeAll(tasks);
        startLatch.countDown(); // let all threads go at once
        doneLatch.await();      // wait for all threads to finish
        exec.shutdownNow();

        long totalAcquired = successCount.get();
        double maxAllowed = burst + ratePerSec * testDuration.getSeconds();
        // Allow a small epsilon for floating point rounding and scheduling overshoot
        double epsilon = 1e-9;
        assertTrue(totalAcquired <= Math.ceil(maxAllowed + epsilon),
                String.format("Acquired %d tokens, but limit is %.2f (burst + rate*time)",
                        totalAcquired, maxAllowed));
    }
}
```

### Test Explanation

1. **`tryAcquire_immediateAndExhausted`**  
   Verifies that the limiter starts full, that `tryAcquire` correctly consumes tokens, and that it returns `false` when the bucket is empty. After a short sleep, tokens are refilled and the next attempt succeeds.

2. **`acquire_blocksUntilTokensAvailable`**  
   Checks the blocking path: after draining the bucket, a call to `acquire(1)` must wait roughly the time needed to generate one token at the configured rate. The test measures the elapsed time and asserts it falls within a reasonable tolerance window.

3. **`concurrentLongRunRateDoesNotExceedLimit`**  
   *Spawns 20 threads* that repeatedly call `tryAcquire(1)` for a fixed interval (10 seconds).  
   The total number of successful acquisitions across all threads is counted.  
   For a token bucket with rate = 5 tokens/s and burst = 10, the theoretical maximum number of tokens that can be granted in 10 seconds is  

   ```
   burst + rate * duration = 10 + 5 * 10 = 60 tokens
   ```

   The test asserts that the observed count never exceeds this bound (allowing a tiny epsilon for floating‑point rounding). This validates that, even under heavy concurrent contention, the limiter respects the long‑run rate limit.

All tests use JUnit 5 (`org.junit.jupiter.api`) and are self‑contained; they can be run with any standard JVM 8+ build tool (Maven, Gradle, etc.). The `@Timeout` annotation guards against a potential deadlock in the concurrent test.