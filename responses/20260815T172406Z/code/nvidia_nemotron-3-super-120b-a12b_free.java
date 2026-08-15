<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260815T172406Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
**Token Bucket Rate Limiter – Design Overview**

The classic token‑bucket algorithm works as follows:

* A bucket holds a number of *tokens* (permits).  
* Tokens are added at a constant **refill rate** (tokens / second) up to a **burst capacity**.  
* Each request consumes one token. If no token is available the request must wait (blocking) or fail immediately (non‑blocking).

The implementation below is **thread‑safe** and uses Java’s intrinsic monitor (`synchronized` + `Object.wait/notify`) so that many threads can call `acquire()` or `tryAcquire()` concurrently without external synchronization.

* **State**
  * `rate` – tokens added per second (double, allows fractional rates).  
  * `capacity` – maximum number of tokens the bucket can hold (double).  
  * `tokens` – current number of tokens in the bucket (double).  
  * `lastNano` – timestamp (nanoseconds) of the last refill operation.  
  * `lock` – private monitor used for all state changes and waiting.

* **Refill** (`refill()`)  
  * Called at the start of every public method.  
  * Computes elapsed time since `lastNano`, adds `elapsed * rate` tokens, caps at `capacity`, and updates `lastNano`.  
  * Because it is executed while holding the monitor, the bucket’s view of time is always consistent.

* **Blocking acquire** (`acquire()`)  
  * Loop: refill → if enough tokens, consume and return.  
  * Otherwise compute the exact time needed to obtain one token, wait on the monitor for that duration (with a minimum of 1 ns to avoid spurious wake‑ups).  
  * When the wait expires (or is interrupted) the loop repeats, re‑checking the token count.

* **Non‑blocking tryAcquire** (`tryAcquire()`)  
  * Refill once, then consume a token if available; otherwise return `false` immediately.  
  * No waiting is performed.

* **Correctness & Fairness**  
  * The monitor guarantees mutual exclusion; only one thread can update the bucket at a time.  
  * Threads waiting in `acquire()` are FIFO‑ordered by the JVM’s monitor implementation (though strict FIFO is not required for correctness).  
  * The algorithm never permits more than `capacity` tokens to be stored, and the long‑run average consumption cannot exceed `rate` tokens per second (plus at most one burst of `capacity` tokens at the start).

* **Complexity**  
  * Refill: **O(1)** time.  
  * `tryAcquire`: **O(1)**.  
  * `acquire`: **O(1)** amortized; the wait time is calculated directly, so there is no busy‑spinning.

---

## TokenBucket.java

```java
package com.example.ratelimiter;

import java.util.Objects;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>The bucket is refilled at a constant {@code rate} (tokens per second) up to a
 * {@code capacity}. {@code acquire()} blocks until a token is available,
 * {@code tryAcquire()} attempts to consume a token without waiting.
 *
 * <p>All operations are {@code O(1)} and safe for concurrent use by any number
 * of threads.
 */
public class TokenBucket {

    /** Number of tokens added per second. */
    private final double rate;

    /** Maximum number of tokens the bucket can hold. */
    private final double capacity;

    /** Current token count. */
    private volatile double tokens;

    /** Nanosecond timestamp of the last refill. */
    private volatile long lastNano;

    /** Monitor used for all state changes and waiting. */
    private final Object lock = new Object();

    /**
     * Creates a new token bucket.
     *
     * @param rate      tokens added per second (must be > 0)
     * @param capacity  maximum tokens the bucket can hold (must be > 0)
     * @throws IllegalArgumentException if rate or capacity is not positive
     */
    public TokenBucket(double rate, double capacity) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be > 0");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.rate = rate;
        this.capacity = capacity;
        // Start with a full bucket – allows an initial burst up to capacity.
        this.tokens = capacity;
        this.lastNano = System.nanoTime();
    }

    /**
     * Refills the bucket based on the elapsed time since the last refill.
     * <p>This method must be called while holding {@code lock}.
     */
    private void refill() {
        long now = System.nanoTime();
        if (now > lastNano) {
            double elapsedSec = (now - lastNano) / 1_000_000_000.0;
            double added = elapsedSec * rate;
            tokens = Math.min(capacity, tokens + added);
            lastNano = now;
        }
    }

    /**
     * Acquires a single token, blocking until one is available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                // Not enough tokens – compute how long to wait for one token.
                double deficit = 1.0 - tokens; // tokens needed
                long waitNanos = (long) (deficit / rate * 1_000_000_000.0);
                if (waitNanos <= 0) {
                    waitNanos = 1L; // avoid zero‑wait which could spin
                }
                lock.wait(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                // Loop again; spurious wake‑ups are handled by the condition check.
            }
        }
    }

    /**
     * Attempts to acquire a single token without blocking.
     *
     * @return {@code true} if a token was consumed, {@code false} otherwise
     */
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** For testing / diagnostics – returns the current approximate token count. */
    double getTokens() {
        synchronized (lock) {
            refill();
            return tokens;
        }
    }

    /** For testing / diagnostics – returns the configured refill rate (tokens/sec). */
    double getRate() {
        return rate;
    }

    /** For testing / diagnostics – returns the burst capacity. */
    double getCapacity() {
        return capacity;
    }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>The tests cover basic functionality, blocking behavior, and a concurrent
 * stress test that verifies the long‑run rate never exceeds the configured limit.
 */
class TokenBucketTest {

    /** Helper to wait a short time without throwing checked exceptions. */
    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    @DisplayName("Basic behavior")
    class BasicBehavior {

        @Test
        @DisplayName("tryAcquire consumes tokens up to capacity then fails")
        void tryAcquireRespectsCapacity() {
            TokenBucket tb = new TokenBucket(10.0, 5.0); // 10 tok/s, burst 5

            // Should be able to take 5 tokens immediately
            for (int i = 0; i < 5; i++) {
                assertTrue(tb.tryAcquire(), "Token " + i + " should be available");
            }
            // Sixth attempt should fail because bucket is empty
            assertFalse(tb.tryAcquire(), "No token should be left");
        }

        @Test
        @DisplayName("acquire blocks until a token is refilled")
        void acquireBlocksUntilRefill() throws Exception {
            TokenBucket tb = new TokenBucket(1.0, 1.0); // 1 tok/s, burst 1

            // Consume the only token
            assertTrue(tb.tryAcquire());
            assertFalse(tb.tryAcquire());

            // Start a thread that will try to acquire; it should block ~1 sec
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<Boolean> future = exec.submit(() -> {
                try {
                    tb.acquire();
                    return true; // acquired
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

            // Not enough time has passed yet – future should not be complete
            assertFalse(future.isCompletedExceptionally());
            assertFalse(future.isDone(), "Future should not be done before refill");

            // Wait a little more than the refill interval
            sleepMillis(1500);
            assertTrue(future.isDone(), "Future should complete after refill");
            assertTrue(future.get(), "Acquire should have succeeded");

            exec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Concurrent / rate‑limit verification")
    class ConcurrentRateLimit {

        /**
         * Launches many threads that repeatedly call {@code tryAcquire()} for a fixed
         * duration.  The total number of successful acquisitions must not exceed
         * <em>rate × duration + capacity</em> (the burst allowance).
         *
         * <p>The test is tolerant of timing jitter; we allow a 10 % slack.
         */
        @Test
        @DisplayName("Long‑run rate does not exceed configured limit")
        @Timeout(value = 10) // safeguard against deadlocks
        void longRunRateRespectsLimit() throws Exception {
            double ratePerSec = 5.0;   // 5 tokens per second
            double capacity   = 20.0;  // allow a burst of 20 tokens
            TokenBucket tb = new TokenBucket(ratePerSec, capacity);

            int workerCount = 50;
            Duration testDuration = Duration.ofSeconds(4); // run for 4 seconds

            ExecutorService pool = Executors.newFixedThreadPool(workerCount);
            List<Future<Long>> futures = new ArrayList<>(workerCount);
            AtomicLong successCount = new AtomicLong(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(workerCount);

            for (int i = 0; i < workerCount; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        startLatch.await(); // wait for all threads to start together
                        long local = 0;
                        long endTime = System.nanoTime() + testDuration.toNanos();
                        while (System.nanoTime() < endTime) {
                            if (tb.tryAcquire()) {
                                local++;
                            }
                            // No Thread.yield() – we want to stress the limiter.
                        }
                        return local;
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }

            // Release all workers at once to avoid ramp‑up bias
            startLatch.countDown();

            // Collect results
            long totalAcquired = 0;
            for (Future<Long> f : futures) {
                totalAcquired += f.get(); // get() throws if the task threw
            }

            pool.shutdownNow();
            // Ensure all workers have finished
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS),
                    "Some workers did not finish in time");

            // Theoretical maximum: initial burst + rate * time
            double maxAllowed = capacity + ratePerSec * testDuration.getSeconds();
            double tolerance = maxAllowed * 0.10; // 10 % slack for timing variance
            assertTrue(totalAcquired <= maxAllowed + tolerance,
                    String.format("Acquired %d tokens, but expected ≤ %.2f (+%.2f slack)",
                            totalAcquired, maxAllowed, tolerance));
        }
    }
}
```

### How to run the tests

* **Build tool** – Maven or Gradel (JUnit 5 is the default test engine in recent versions).  
* **Maven example**

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

* Execute: `mvn test` or `gradle test`.

The tests validate:

1. Correct token consumption and blocking behavior of a single bucket.  
2. That under heavy concurrent load the observed acquisition rate never exceeds the configured refill rate (plus the allowed burst), confirming the limiter’s long‑term correctness.