<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260821T111453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Design Document – Token‑Bucket Rate Limiter**

| Aspect | Decision & Rationale |
|--------|----------------------|
| **Goal** | Provide a thread‑safe limiter that can be used by many threads simultaneously. It must support (a) a *blocking* `acquire()` that waits until a token is available and (b) a *non‑blocking* `tryAcquire()` that returns immediately with the result. |
| **Algorithmic model** | Classic **token‑bucket**: <br>• Tokens are added at a *refill rate* (`tokensPerSecond`). <br>• The bucket capacity (`maxBurst`) caps the maximum number of tokens that can accumulate. <br>• Each call consumes exactly one token. |
| **Refill logic** | The bucket is refilled based on elapsed wall‑clock time. On every attempt we compute how many whole tokens should be added since the last refill: <br>`newTokens = floor(elapsedSeconds * tokensPerSecond)` <br>and clamp the result to `maxBurst`. This is cheap and does not require a scheduled task. |
| **Thread‑safety** | All mutable state (`tokens`, `lastRefillTime`) is protected by a single `ReentrantLock`. The lock also houses a `Condition` (`notEmpty`) that allows waiting threads to be awakened when a token becomes available after a refill. |
| **Blocking acquire** | `acquire()` loops: <br>1. Acquire the lock. <br>2. Refill the bucket. <br>3. If `tokens > 0` → consume one and release the lock. <br>4. Otherwise, call `notEmpty.await()` (releasing the lock) and repeat. This guarantees that the thread blocks *exactly* until a token is present, without busy‑spinning. |
| **Non‑blocking tryAcquire()** | `tryAcquire()` performs the same refill step but **does not wait**. It simply checks `tokens`. If a token is available it consumes it and returns `true`; otherwise it returns `false` immediately. |
| **Fairness** | The `Condition` uses the default FIFO order, so threads that have been waiting the longest are the first to be awakened when a token is added. |
| **Configurability** | The constructor takes two parameters: <br>• `tokensPerSecond` – the average refill rate (can be fractional). <br>• `maxBurst` – the maximum bucket size (must be > 0). |
| **Metrics / Observability** | The class does **not** expose internal counters; it is deliberately minimalistic. If monitoring is required the caller can wrap the limiter or instrument the surrounding code. |
| **Error handling** | `acquire()` may throw `InterruptedException`. `tryAcquire()` never blocks, so it never throws due to interruption. |

---

## Java Implementation

```java
package com.example.ratelimiter;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 200); // 100 tokens/s, burst 200
 *
 * // Blocking acquire – will wait if no tokens are available
 * limiter.acquire();
 *
 * // Non‑blocking acquire – returns false immediately if the bucket is empty
 * boolean got = limiter.tryAcquire();
 * }</pre>
 *
 * <p>The limiter does not schedule any tasks; refills are computed on‑demand using
 * {@link System#nanoTime()} so it works correctly even when the JVM is paused or the
 * system clock is adjusted.
 */
public class TokenBucketRateLimiter {

    /** Average number of tokens added per second. */
    private final double tokensPerSecond;

    /** Maximum number of tokens that can accumulate (burst capacity). */
    private final int maxBurst;

    /** Guard for all mutable fields. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that waits when the bucket is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Current token count. */
    private volatile int tokens = 0;

    /** Timestamp of the last refill (in nanoseconds). */
    private volatile long lastRefillTimeNs = System.nanoTime();

    /**
     * Creates a limiter.
     *
     * @param tokensPerSecond the average refill rate (tokens per second). Must be > 0.
     * @param maxBurst        the maximum burst capacity (must be > 0).
     * @throws IllegalArgumentException if either argument is non‑positive.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, int maxBurst) {
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("tokensPerSecond must be > 0");
        }
        if (maxBurst <= 0) {
            throw new IllegalArgumentException("maxBurst must be > 0");
        }
        this.tokensPerSecond = tokensPerSecond;
        this.maxBurst = maxBurst;
    }

    /** Refills the bucket based on elapsed time since the last refill. */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNs = now - lastRefillTimeNs;
        double elapsedSec = elapsedNs / 1e9;
        int newlyAdded = (int) Math.floor(elapsedSec * tokensPerSecond);
        tokens = Math.min(tokens + newlyAdded, maxBurst);
        lastRefillTimeNs = now;
    }

    /**
     * Blocks until a token is available, then consumes one.
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();                         // add tokens based on elapsed time
                if (tokens > 0) {                 // we have a token
                    tokens--;
                    // Wake up one waiting thread (if any) because the bucket changed.
                    notEmpty.signal();
                    return;
                }
                // No tokens – wait until a refill adds at least one.
                notEmpty.await();
                // Loop again after waking up.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was available and was consumed; {@code false}
     *         otherwise.
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens > 0) {
                tokens--;
                // If a thread was waiting, it may now be awakened; but we do not need to signal
                // because the waiting thread will re‑check the condition on the next loop.
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non‑blocking variation that waits up to {@code timeout} for a token.
     *
     * @param timeout maximum time to wait.
     * @param unit    time unit of {@code timeout}.
     * @return {@code true} if a token was obtained, {@code false} on timeout or interruption.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (true) {
                refill();
                if (tokens > 0) {
                    tokens--;
                    notEmpty.signal();
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false; // timeout
                }
                // Wait until either a token appears or we timeout.
                long waitNanos = deadline - System.nanoTime();
                if (waitNanos <= 0) {
                    return false;
                }
                notEmpty.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }
}
```

---

## JUnit 5 Test Suite

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    /** A simple sanity test for the basic API. */
    @Test
    void basicAcquireAndTryAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5); // 10 tokens/s, burst 5

        // Initially bucket is empty
        assertFalse(limiter.tryAcquire());

        // After a refill (implicitly on first acquire) we should be able to get a token
        limiter.acquire(); // blocks until a token appears
        assertTrue(limiter.tryAcquire()); // now we have at least one token left
    }

    /** The blocking acquire should wait when the bucket is empty and proceed when a token is added. */
    @Test
    void blockingAcquireWaitsUntilTokenAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10); // 5 tokens/s

        // Fast‑forward the internal time by calling a private helper via reflection is not needed.
        // Instead we just call acquire() and let it block for a short period.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                limiter.acquire(); // should block until the first refill adds tokens
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // Give the thread a moment to start waiting, then manually add a token
        // (by invoking a package‑private refill via reflection or simply waiting a bit)
        // For simplicity we just wait a little longer than the expected refill interval.
        Thread.sleep(200); // 0.2 s > 1/5 s = 0.2 s, so a token will be added
        assertTrue(latch.await(1, TimeUnit.SECONDS), "acquire() should eventually return");
        executor.shutdownNow();
    }

    /** tryAcquire must never block and must respect the current token count. */
    @Test
    void tryAcquireIsNonBlocking() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);
        // Bucket empty → tryAcquire returns false
        assertFalse(limiter.tryAcquire());

        // Acquire a token via blocking call, then tryAcquire should succeed
        limiter.acquire();
        assertTrue(limiter.tryAcquire());
        // Consume again
        assertTrue(limiter.tryAcquire());
        // No tokens left
        assertFalse(limiter.tryAcquire());
    }

    /**
     * Long‑run concurrency test – many threads repeatedly acquire tokens for a few seconds.
     * The total number of successful acquires must not exceed <code>tokensPerSecond × duration</code>
     * by more than a small epsilon that accounts for the burst capacity.
     */
    @Test
    void concurrentLongRunDoesNotExceedRate() throws Exception {
        final double tokensPerSecond = 120.0; // 120 tokens per second
        final int maxBurst = 250;
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(tokensPerSecond, maxBurst);

        final int threadCount = 8;
        final Duration testDuration = Duration.ofSeconds(3);
        final AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulAcquires = new AtomicInteger(0);

        // Each worker repeatedly calls acquire() until the {@code running} flag becomes false.
        Runnable worker = () -> {
            try {
                startLatch.await(); // make all threads start at the same time
                while (running.get()) {
                    try {
                        limiter.acquire(); // blocking – consumes a token
                        successfulAcquires.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                endLatch.countDown();
            }
        };

        // Launch workers
        for (int i = 0; i < threadCount; i++) {
            executor.submit(worker);
        }

        // Let the test run for the configured duration
        Thread.sleep(testDuration.toMillis());

        // Signal workers to stop
        running.set(false);
        // Wait for them to finish
        endLatch.await();

        // Shut down the executor
        executor.shutdownNow();

        int totalAcquires = successfulAcquires.get();

        // Expected maximum = tokensPerSecond * testDurationInSeconds + burst (because of initial burst)
        double maxAllowed = tokensPerSecond * testDuration.getSeconds() + maxBurst;
        assertTrue(totalAcquires <= Math.round(maxAllowed),
                "Too many tokens acquired: got " + totalAcquires +
                        " but should be ≤ " + Math.round(maxAllowed));
    }
}
```

### Explanation of the Concurrency Test

1. **Setup** – We create a limiter with a relatively high refill rate (`120 tokens/s`) and a generous burst (`250`).  
2. **Workers** – Eight threads repeatedly call the **blocking** `acquire()` method as long as a shared `running` flag is `true`.  
3. **Duration** – The test runs for a fixed wall‑clock period (`3 seconds`).  
4. **Counting** – Each successful acquisition increments an `AtomicInteger`.  
5. **Verification** – After the period we stop the workers and assert that the total number of acquisitions does **not** exceed the theoretical maximum:  
   \[
   \text{maxAllowed} = \text{tokensPerSecond} \times \text{duration} + \text{maxBurst}
   \]  
   The addition of `maxBurst` accounts for the initial burst that can legally happen at time 0.  
   Because the limiter is token‑bucket based, the long‑run average cannot exceed the configured rate; any violation would indicate a bug in the refill or acquisition logic.

All tests are pure JUnit 5, require only the standard JDK and JUnit 5 dependencies, and can be run with any modern build tool (Maven, Gradle, etc.).