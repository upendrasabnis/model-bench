<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260917T094528Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

The token bucket algorithm is a classic way to limit the average rate of requests while allowing short bursts up to a configured capacity.

* **State**
  * `rate` – tokens added per second (refill rate).
  * `capacity` – maximum number of tokens the bucket can hold (burst size).
  * `tokens` – current number of tokens available (fractional, because the refill rate may be non‑integral).
  * `lastRefillNanos` – the moment (in nanoseconds) when the bucket was last “refilled”.  
    All time‑based calculations are performed relative to this instant.

* **Refill**
  Whenever a thread checks the bucket we first bring the state up‑to‑date:
  ```
  elapsed = now - lastRefillNanos   // seconds
  tokens = min(capacity, tokens + elapsed * rate)
  lastRefillNanos = now
  ```
  This is O(1) and guarantees that `tokens` always reflects the exact amount that should be available at `now`.

* **Consuming tokens**
  * If `tokens >= requested` we deduct the tokens, update `lastRefillNanos = now` (we have now accounted for all time up to `now`) and return success.
  * Otherwise we compute how long we must wait for enough tokens to appear:
    ```
    deficit   = requested - tokens          // tokens still needed
    waitSec   = deficit / rate              // seconds to wait
    waitNanos = round(waitSec * 1e9)
    ```
    The thread then waits on the bucket’s intrinsic lock for that timeout (using `Object.wait(long millis, int nanos)`).  
    When the wait returns (either timeout or spurious wake‑up) we loop again – the refill step will have added the newly‑available tokens.

* **Thread‑safety**
  All mutable state (`tokens`, `lastRefillNanos`) is protected by the intrinsic lock of the instance (`synchronized(this)`).  
  The lock also serves as the condition variable for blocking `acquire`.  
  Because every public method holds the lock for the entirety of its critical section, the implementation is safe for an arbitrary number of concurrent threads.

* **Blocking vs. non‑blocking**
  * `acquire(int permits)` – blocks until the requested permits are available (throws `InterruptedException` if the thread is interrupted while waiting).
  * `tryAcquire(int permits)` – attempts once, returns `true` if successful, `false` otherwise, without blocking.
  * (Optional) `tryAcquire(int permits, long timeout, TimeUnit unit)` can be built on top of the same wait‑loop but is omitted for brevity.

* **Correctness notes**
  * The algorithm guarantees the **long‑term average rate** never exceeds `rate` tokens per second.
  * In any interval of length `t` seconds the maximum number of tokens that can be consumed is `capacity + rate * t`.  
    This property is used in the concurrent test to verify that the limiter respects the configured limit even under heavy contention.

---

## Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimiter;

import java.util.Objects;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled at a constant {@code rate} (tokens per second) up to a maximum
 * {@code capacity}.  Threads can either block until permits are available ({@link #acquire})
 * or attempt a non‑blocking acquisition ({@link #tryAcquire}).
 *
 * <p>All operations are {@code O(1)} and safe for use by many concurrent threads.
 */
public class TokenBucketRateLimiter {

    /** Refill rate in tokens per second. Must be > 0. */
    private final double rate;

    /** Maximum number of tokens the bucket can hold (burst capacity). Must be > 0. */
    private final int capacity;

    /** Current number of tokens available (may be fractional). */
    private double tokens;

    /** Nanosecond timestamp of the last refill operation. */
    private long lastRefillNanos;

    /**
     * Creates a new rate limiter.
     *
     * @param rate      tokens added per second (must be > 0)
     * @param capacity  maximum tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if rate <= 0 or capacity <= 0
     */
    public TokenBucketRateLimiter(double rate, int capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.rate = rate;
        this.capacity = capacity;
        // start with a full bucket – allows an initial burst up to capacity
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since {@code lastRefillNanos}.
     *
     * @param nowNanos current time in nanoseconds
     */
    private void refill(long nowNanos) {
        if (nowNanos > lastRefillNanos) {
            double elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
            double newTokens = tokens + elapsedSec * rate;
            if (newTokens > capacity) {
                newTokens = capacity;
            }
            tokens = newTokens;
            lastRefillNanos = nowNanos;
        }
    }

    /**
     * Acquires the given number of permits, blocking until they are available.
     *
     * @param permits number of permits to acquire (must be >= 0)
     * @throws IllegalArgumentException if permits < 0
     * @throws InterruptedException     if the current thread is interrupted while waiting
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be >= 0");
        }
        synchronized (this) {
            long now;
            while (true) {
                now = System.nanoTime();
                refill(now);
                if (tokens >= permits) {
                    tokens -= permits;
                    // we have now accounted for time up to 'now'
                    lastRefillNanos = now;
                    return;
                }
                // Not enough tokens – compute how long to wait.
                double deficit = permits - tokens; // > 0
                double waitSec = deficit / rate;
                long waitNanos = (long) (waitSec * 1_000_000_000.0);
                // Object.wait takes millis and extra nanos.
                long waitMillis = waitNanos / 1_000_000;
                int waitNanosExtra = (int) (waitNanos % 1_000_000);
                this.wait(waitMillis, waitNanosExtra);
                // Loop again – a spurious wakeup or timeout will re‑check the condition.
            }
        }
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     *
     * @param permits number of permits to acquire (must be >= 0)
     * @return {@code true} if the permits were acquired, {@code false} otherwise
     * @throws IllegalArgumentException if permits < 0
     */
    public boolean tryAcquire(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be >= 0");
        }
        synchronized (this) {
            long now = System.nanoTime();
            refill(now);
            if (tokens >= permits) {
                tokens -= permits;
                lastRefillNanos = now;
                return true;
            }
            return false;
        }
    }

    /**
     * Returns the current number of tokens available (for debugging / monitoring).
     * The value is a snapshot and may be outdated immediately after the call.
     *
     * @return current token count (fractional)
     */
    public double getAvailableTokens() {
        synchronized (this) {
            long now = System.nanoTime();
            refill(now);
            return tokens;
        }
    }

    /**
     * Returns the configured refill rate (tokens per second).
     */
    public double getRate() {
        return rate;
    }

    /**
     * Returns the configured burst capacity (maximum tokens).
     */
    public int getCapacity() {
        return capacity;
    }
}
```

---

## JUnit 5 Tests (`TokenBucketRateLimiterTest.java`)

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    private static final double TEST_RATE = 5.0;   // 5 tokens per second
    private static final int   TEST_CAPACITY = 10; // burst of 10 tokens

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(TEST_RATE, TEST_CAPACITY);
    }

    @Test
    void tryAcquire_succeedsWhenTokensAvailable() {
        assertTrue(limiter.tryAcquire(3));
        assertEquals(TEST_CAPACITY - 3, limiter.getAvailableTokens(), 1e-9);
    }

    @Test
    void tryAcquire_failsWhenNotEnoughTokens() {
        // consume all tokens
        assertTrue(limiter.tryAcquire(TEST_CAPACITY));
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void acquire_blocksUntilTokensAreAvailable() throws Exception {
        // drain the bucket
        limiter.acquire(TEST_CAPACITY);
        // next acquire should block for roughly 1/rate seconds per token
        long start = System.nanoTime();
        limiter.acquire(1); // need 1 token
        long elapsedNs = System.nanoTime() - start;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        // allow 20% tolerance for scheduling jitter
        assertTrue(elapsedSec >= 0.9 / TEST_RATE && elapsedSec <= 1.5 / TEST_RATE);
    }

    @Test
    void acquire_respectsInterrupt() throws Exception {
        limiter.acquire(TEST_CAPACITY); // fill then drain
        Thread waiter = new Thread(() -> {
            try {
                limiter.acquire(1);
                fail("Expected InterruptedException");
            } catch (InterruptedException ignored) {
                // expected
            }
        });
        waiter.start();
        Thread.sleep(50); // let waiter start waiting
        waiter.interrupt();
        waiter.join();
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        int numThreads = 20;
        Duration testDuration = Duration.ofSeconds(12); // long enough to wash out burst
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        AtomicLong permitsAcquired = new AtomicLong(0);
        CountDownGate startGate = new CountDownGate(numThreads + 1); // +1 for main thread
        CountDownGate doneGate   = new CountDownGate(numThreads);

        for (int i = 0; i < numThreads; i++) {
            exec.submit(() -> {
                startGate.await(); // wait for all threads to start together
                long localCount = 0;
                long endTime = System.nanoTime() + testDuration.toNanos();
                while (System.nanoTime() < endTime) {
                    if (limiter.tryAcquire(1)) {
                        localCount++;
                    }
                    // spin – we want to test the limiter under contention
                }
                permitsAcquired.addAndGet(localCount);
                doneGate.arrive();
            });
        }

        startGate.arrive(); // release workers
        startGate.await();  // main thread waits until all workers have started
        doneGate.await();   // wait for all workers to finish
        exec.shutdownNow();

        long total = permitsAcquired.get();
        double maxAllowed = TEST_CAPACITY + TEST_RATE * testDuration.getSeconds();
        // small epsilon to account for occasional overshoot due to timing granularity
        double epsilon = 1e-6 * maxAllowed + 1.0;
        assertTrue(total <= maxAllowed + epsilon,
                String.format("Acquired %d permits, but max allowed is %.2f (rate=%.2f, cap=%d, duration=%ds)",
                        total, maxAllowed, TEST_RATE, TEST_CAPACITY, testDuration.getSeconds()));
    }

    /** Simple reusable barrier based on Phaser (Java 8+). */
    private static class CountDownGate {
        private final Phaser phaser;

        CountDownGate(int parties) {
            this.phaser = new Phaser(parties + 1); // +1 for the register call below
            this.phaser.register(); // the party that creates the gate
        }

        void arrive() {
            phaser.arriveAndAwaitAdvance();
        }

        void await() {
            // wait for all registered parties to arrive
            phaser.arriveAndAwaitAdvance();
        }
    }
}
```

### Explanation of the Tests

| Test | What it verifies |
|------|------------------|
| `tryAcquire_succeedsWhenTokensAvailable` | Basic successful consumption and token accounting. |
| `tryAcquire_failsWhenNotEnoughTokens` | Non‑blocking behavior when the bucket is empty. |
| `acquire_blocksUntilTokensAreAvailable` | Blocking `acquire` waits the correct amount of time (≈ `permits / rate`). |
| `acquire_respectsInterrupt` | Waiting thread reacts to `interrupt()` by throwing `InterruptedException`. |
| `longRunRateDoesNotExceedConfiguredLimit` | **Concurrent stress test** – many threads repeatedly call `tryAcquire(1)` for a fixed duration. The total number of permits acquired is bounded by `capacity + rate × duration`, confirming that the long‑term average rate never exceeds the configured limit, even under heavy contention. |

The test uses a simple `CountDownGate` barrier (built on `java.util.concurrent.Phaser`) to start all worker threads at nearly the same moment, eliminating ramp‑up bias. After the test window, the main thread asserts that the observed throughput respects the theoretical maximum allowed by the token bucket.

--- 

**Usage Example**

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 20); // 10 tokens/sec, burst 20

// Non‑blocking
if (limiter.tryAcquire(5)) {
    // process request
}

// Blocking (waits as needed)
limiter.acquire(3);
```

The limiter is lightweight, has no external dependencies, and is safe for use by any number of concurrent threads.