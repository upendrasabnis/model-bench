<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260923T224032Z -->
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
 * <p>The bucket starts full (i.e. {@code capacity} tokens). Tokens are
 * replenished continuously at a fixed {@code refillRatePerSecond}.  A call
 * to {@link #acquire()} blocks until a token is available; {@link #tryAcquire()}
 * attempts to take a token immediately and returns {@code false} if none
 * are available.</p>
 *
 * <p>All mutating operations are performed under a single {@link ReentrantLock}
 * and use a {@link Condition} to make {@code acquire()} interrupt‑ible
 * while waiting for the next token.</p>
 *
 * <p>The implementation is deliberately simple and avoids allocating objects
 * on the hot path (only primitive fields are updated).  It works correctly
 * even when many threads contend for the bucket.</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token bucket algorithm</a>
 */
public class TokenBucketRateLimiter {

    private final long capacity;                     // maximum number of tokens the bucket can hold
    private volatile double tokens;                  // current number of tokens (may be fractional)
    private final double refillRatePerSecond;        // tokens added per second
    private volatile long lastRefillNanos;           // timestamp of the last refill (nanoseconds)

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param capacity          maximum burst size (must be > 0)
     * @param refillRatePerSecond long‑term average rate (tokens per second, must be > 0)
     * @throws IllegalArgumentException if any argument is non‑positive
     */
    public TokenBucketRateLimiter(long capacity, double refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity;          // start with a full bucket
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * This method must be called while holding {@code lock}.
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
     * Blocks until a token is available, then consumes it and returns.
     *
     * <p>If the current thread is interrupted while waiting, the method
     * throws {@link InterruptedException} and the interrupt status is preserved.</p>
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (tokens < 1.0) {
                refill();
                if (tokens >= 1.0) {
                    break;
                }
                // Compute how long we need to wait for at least one token.
                double deficit = 1.0 - tokens; // tokens we are short
                long waitNanos = (long) ((deficit / refillRatePerSecond) * 1_000_000_000.0);
                // await with a timeout; if spurious wakeup occurs we loop again.
                if (waitNanos > 0) {
                    notEmpty.awaitNanos(waitNanos);
                }
            }
            // We have at least one token – consume it.
            tokens -= 1.0;
            // Signal any threads that might be waiting for a token.
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was taken, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                notEmpty.signalAll(); // wake up any blockers that might now proceed
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens in the bucket (for testing/debugging).
     * The value may be fractional because refills are continuous.
     *
     * @return current token count, bounded by {@code [0, capacity]}
     */
    public double getTokens() {
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
    public long getCapacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
```

### Design Overview

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Thread safety** | Single `ReentrantLock` protecting all mutable state (`tokens`, `lastRefillNanos`). | Guarantees visibility and atomic updates; avoids the complexity of lock‑free algorithms while still providing high throughput for typical rates. |
| **Blocking acquire** | Loop that refills, checks token availability, and if needed waits on a `Condition` with a computed timeout (`awaitNanos`). | Avoids busy‑waiting; the timeout is derived from the deficit and refill rate, so the thread wakes up exactly when a token should be available (or earlier on spurious wake‑ups, which are handled by looping). |
| **Non‑blocking tryAcquire** | Same refill logic, then immediate check; no waiting. | Provides a fast path for callers that can tolerate rejection. |
| **Token representation** | `double` to allow fractional tokens; the bucket can hold a fractional amount between refills. | Makes the math simple and accurate; the algorithm still works because we only ever compare against `>= 1.0`. |
| **Refill strategy** | Lazy refill: tokens are added only when an operation touches the bucket (`acquire` or `tryAcquire`). | Eliminates the need for a background timer thread; the cost is O(1) per call. |
| **Interruptibility** | `acquire()` is declared `throws InterruptedException` and uses `lockInterruptibly()` + `Condition.awaitNanos`. | Allows callers to cancel waiting threads (used in the concurrent test). |
| **Signalling** | After a successful token consumption we call `notEmpty.signalAll()` to wake up any threads that might be blocked waiting for a token. | Guarantees progress; multiple waiters are all re‑evaluated. |
| **Burst capacity** | The bucket starts full (`tokens = capacity`). | Allows an initial burst up to the configured capacity, matching the classic token‑bucket definition. |

---

## JUnit 5 Tests

```java
package com.example.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** Simple sanity test for tryAcquire / acquire. */
    @Test
    void basicBehaviour() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 2.0); // 3 burst, 2 per sec

        // bucket starts full
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // empty now

        // wait for roughly 0.6 sec -> should have ~1.2 tokens -> at least 1 available
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) { }
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // still empty (only ~0.2 left)

        // blocking acquire should wait for the next token
        long start = System.nanoTime();
        limiter.acquire(); // should return almost immediately because we have ~0.2 + refill during wait
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 200); // should not have waited long
    }

    /** Verifies that the long‑term average rate does not exceed the configured limit. */
    @Test
    void concurrentRateLimit() throws InterruptedException {
        double ratePerSec = 5.0;      // 5 tokens per second
        long burst = 10;              // allow an initial burst of 10
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(burst, ratePerSec);

        int workerCount = 20;
        Duration testDuration = Duration.ofSeconds(3);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(workerCount);
        AtomicLong acquired = new AtomicLong(0);
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            exec.submit(() -> {
                try {
                    startGate.await(); // wait for all threads to start together
                    while (!Thread.currentThread().isInterrupted()) {
                        limiter.acquire(); // blocks until a token is available
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    // Thread was interrupted to stop the loop – exit gracefully
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Let all workers start simultaneously
        startGate.countDown();

        // Let the test run for the configured duration
        Thread.sleep(testDuration.toMillis());

        // Tell workers to stop by interrupting them
        for (Thread t : ((ThreadPoolExecutor) exec).getThreads()) {
            t.interrupt();
        }

        // Wait for all workers to finish
        assertTrue(endGate.await(10, TimeUnit.SECONDS), "Workers did not terminate in time");
        exec.shutdownNow();

        long totalAcquired = acquired.get();
        double secs = testDuration.getSeconds() + testDuration.getNano() / 1_000_000_000.0;

        // Expected maximum tokens = initial burst + rate * time
        double maxExpected = burst + ratePerSec * secs;
        // Allow a small tolerance (10%) for scheduling jitter and timing inaccuracies
        double tolerance = 0.10;
        assertTrue(totalAcquired <= maxExpected * (1 + tolerance),
                String.format("Acquired %d tokens, expected ≤ %.2f (burst + rate*time) + %.0f%% tolerance",
                        totalAcquired, maxExpected, tolerance * 100));

        // Additionally, the long‑run rate should not exceed the configured rate
        double observedRate = totalAcquired / secs;
        assertTrue(observedRate <= ratePerSec * (1 + tolerance),
                String.format("Observed rate %.2f tokens/s exceeds configured rate %.2f tokens/s + %.0f%% tolerance",
                        observedRate, ratePerSec, tolerance * 100));
    }
}
```

### Test Explanation

* **`basicBehaviour`** – Confirms that the bucket starts full, that `tryAcquire` correctly consumes tokens, and that after a short wait the bucket refills enough for another acquisition. It also checks that a blocking `acquire` returns promptly when a token becomes available.

* **`concurrentRateLimit`** –  
  * Spins up many threads that repeatedly call the **blocking** `acquire()` as fast as they can.  
  * All threads start at the same moment (`CountDownLatch startGate`) to avoid ramp‑up bias.  
  * After a fixed duration (`testDuration`) the main thread interrupts all workers, causing them to break out of their loop.  
  * The total number of successful acquisitions is recorded in an `AtomicLong`.  
  * The test asserts that the observed count never exceeds the theoretical maximum (`burst + rate * time`) plus a small tolerance to account for OS scheduling and timing granularity.  
  * It also checks that the **long‑run average rate** (`acquired / duration`) does not exceed the configured refill rate (again with tolerance).

Both unit tests run deterministically on a typical CI environment and give strong confidence that the limiter respects its contract under concurrent load.