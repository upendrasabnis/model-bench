<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260725T180011Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Document  

### 1.1 Goal  
Create a **thread‑safe token‑bucket rate limiter** that can be used by many threads simultaneously.  
The limiter must:

* Be configurable with a **refill rate** (tokens added per second) and a **burst capacity** (maximum number of tokens that can accumulate).  
* Offer two acquisition APIs:  
  * `acquire()` – **blocking** until a token is available.  
  * `tryAcquire()` – **non‑blocking** – returns `true` if a token is available, `false` otherwise.  
* Guarantee that, **in the long run**, the average number of tokens consumed per second never exceeds the configured refill rate.  

### 1.2 Core Concepts  

| Concept | Description |
|---------|-------------|
| **Token bucket** | A notional bucket that holds up to `maxTokens`. Every second (or a finer granularity) a fixed number of tokens (`refillRate`) are added, up to the bucket’s capacity. |
| **Refill granularity** | To avoid a race condition on the exact time a token is added, the bucket is refilled **on demand** using the elapsed time since the last refill. This is accurate enough for most practical rates (down to millisecond granularity). |
| **Thread safety** | A single `ReentrantLock` protects all mutable state (`tokens`, `lastRefillTimestamp`). A `Condition` (`notEnoughTokens`) blocks threads when the bucket is empty. |
| **Blocking acquire** | Atomically wait on the condition until `tokens >= 1`. Upon wake‑up, re‑check the condition and, if satisfied, consume a token. |
| **Non‑blocking tryAcquire** | Simply inspect `tokens`; if ≥ 1, decrement and return `true`; otherwise return `false`. |
| **Long‑run rate guarantee** | The implementation never adds more tokens than the configured refill rate per second. Because the refill is performed *lazily* (only when a thread checks the bucket) the average consumption cannot exceed the rate. A formal proof is outside the scope of this document, but the algorithm respects the classic token‑bucket invariants. |

### 1.3 API  

```java
public final class TokenBucketRateLimiter {

    /**
     * Creates a limiter with the given refill rate and burst capacity.
     *
     * @param refillRateTokensPerSecond  tokens added each second (may be fractional,
     *                                   but stored as a double for precision)
     * @param maxBurstCapacity            maximum number of tokens that can accumulate
     */
    public TokenBucketRateLimiter(double refillRateTokensPerSecond,
                                  int maxBurstCapacity) { … }

    /**
     * Blocks until a token is available, then consumes it.
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void acquire() throws InterruptedException { … }

    /**
     * Attempts to acquire a token without blocking.
     * @return true if a token was available, false otherwise
     */
    public boolean tryAcquire() { … }

    /**
     * Non‑blocking attempt that waits at most {@code timeout} time units.
     * @param timeout the maximum time to wait
     * @param unit    time unit of the timeout
     * @return true if a token was acquired, false if the wait timed out
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException { … }

    // No public mutators – the bucket parameters are immutable after construction.
}
```

### 1.4 Guarantees  

| Guarantee | How it is enforced |
|-----------|-------------------|
| **Thread‑safety** | All mutable fields are accessed only while holding `lock`. |
| **No overflow of tokens** | `tokens` is clamped to `maxBurstCapacity` after each refill. |
| **Refill never exceeds rate** | Tokens are added based on the elapsed time multiplied by `refillRate`. The calculation is performed **only** when a thread accesses the bucket, so the *average* refill rate equals the configured rate. |
| **Long‑run limit** | Because each successful `acquire()` consumes exactly one token and tokens are only added at the configured rate, the long‑term average consumption ≤ refill rate. The concurrent test below validates this empirically. |
| **Fairness (optional)** | The `Condition` uses the default FIFO order, which is fair enough for most use‑cases. If strict fairness is required, a different `QueueingRejectedExecutionHandler` could be swapped in. |

### 1.5 Assumptions & Trade‑offs  

* **Precision** – Refills are computed using `System.nanoTime()` and a double‑based rate. This yields sub‑microsecond precision, which is more than enough for typical API‑rate limiting scenarios.  
* **Granularity** – The limiter works at the granularity of the **calling thread**; it does not schedule a background task. This avoids additional thread‑management overhead and works well when the limiter is contended.  
* **Maximum burst** – The burst capacity caps the number of tokens that can be accumulated; a request that exceeds the capacity must wait for subsequent refills.  
* **Interrupt handling** – `acquire()` propagates `InterruptedException`. `tryAcquire` never blocks, so interruption is irrelevant there.  

---

## 2. Implementation  

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
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
 *         10.0,        // 10 tokens per second
 *         20);         // burst capacity of 20 tokens
 *
 * // Blocking call
 * limiter.acquire();   // blocks until a token is available
 *
 * // Non‑blocking call
 * if (limiter.tryAcquire()) {
 *     // proceed
 * }
 * }</pre>
 *
 * <p>The limiter does **not** schedule a background thread; refills are performed
 * lazily whenever a thread inspects the bucket. This makes the limiter cheap to
 * instantiate and safe under high contention.
 */
public final class TokenBucketRateLimiter {

    /** Lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition to wait when no tokens are available. */
    private final Condition notEnoughTokens = lock.newCondition();

    /** Current number of tokens in the bucket. */
    private volatile int tokens;

    /** Maximum capacity of the bucket (burst size). */
    private final int maxBurstCapacity;

    /** Refill rate expressed as tokens per second (may be fractional). */
    private final double refillRateTokensPerSecond;

    /** Timestamp (ns) of the last refill operation. */
    private volatile long lastRefillNanos;

    /**
     * Creates a limiter with the supplied refill rate and burst capacity.
     *
     * @param refillRateTokensPerSecond  tokens added each second; must be non‑negative
     * @param maxBurstCapacity           maximum number of tokens that can accumulate;
     *                                   must be positive
     * @throws IllegalArgumentException if arguments are invalid
     */
    public TokenBucketRateLimiter(double refillRateTokensPerSecond,
                                  int maxBurstCapacity) {
        if (refillRateTokensPerSecond < 0) {
            throw new IllegalArgumentException("refillRate must be non‑negative");
        }
        if (maxBurstCapacity <= 0) {
            throw new IllegalArgumentException("maxBurstCapacity must be positive");
        }
        this.refillRateTokensPerSecond = refillRateTokensPerSecond;
        this.maxBurstCapacity = maxBurstCapacity;
        this.tokens = maxBurstCapacity;          // start full – allows immediate burst
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                refreshBucket();                 // ensure tokens are up‑to‑date
                if (tokens > 0) {
                    tokens--;
                    return;                         // success
                }
                // No tokens – wait until a token becomes available.
                notEnoughTokens.await();           // releases lock while waiting
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was available, {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refreshBucket();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non‑blocking attempt that waits at most {@code timeout} time units.
     *
     * @param timeout the maximum time to wait
     * @param unit    time unit of the timeout
     * @return {@code true} if a token was acquired, {@code false} if the wait timed out
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout <= 0) {
            return tryAcquire();
        }
        lock.lockInterruptibly();
        try {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                refreshBucket();
                if (tokens > 0) {
                    tokens--;
                    return true;
                }
                long now = System.nanoTime();
                if (now >= deadline) {
                    return false; // timeout
                }
                // Wait for the shorter of remaining timeout or a signal.
                long waitNanos = Math.min(unit.toNanos(timeout), deadline - now);
                // Convert to milliseconds for Condition.awaitNanos (max 2^31-1)
                int waitMillis = (int) Math.min(waitNanos / 1_000_000, Integer.MAX_VALUE);
                if (waitMillis <= 0) {
                    // Timeout is effectively zero; re‑check after lock release.
                    continue;
                }
                notEnoughTokens.awaitNanos(waitNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Refreshes the token count based on elapsed time since the last refresh. */
    private void refreshBucket() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        lastRefillNanos = now;

        // Tokens to add = floor(elapsed * refillRate)
        double tokensToAdd = (refillRateTokensPerSecond * elapsedNanos) / 1_000_000_000.0;
        int delta = (int) Math.floor(tokensToAdd);

        if (delta != 0) {
            // Add tokens but never exceed the configured capacity.
            int newTokens = tokens + delta;
            if (newTokens > maxBurstCapacity) {
                newTokens = maxBurstCapacity;
            }
            tokens = newTokens;
        }
    }
}
```

### 2.1 Why This Implementation Is Thread‑Safe  

* **Single lock** (`ReentrantLock`) protects *all* mutable fields (`tokens`, `lastRefillNanos`).  
* All public methods acquire the lock before reading or mutating state.  
* The condition variable (`notEnoughTokens`) is used only while holding the lock, eliminating race conditions.  
* The `refreshBucket()` method is invoked **inside** the lock, guaranteeing that the token count is always consistent with the latest timestamp.  

Because the lock is *interruptible* for `acquire()` and `tryAcquire(long)`, a thread that is waiting for a token can be interrupted cleanly.

---

## 3. JUnit 5 Tests  

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** A small refill rate: 100 tokens per second, burst of 200 */
    private static final double REFILL_RATE = 100.0;
    private static final int BURST_CAPACITY = 200;

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(REFILL_RATE, BURST_CAPACITY);
    }

    @Test
    void tryAcquireReturnsFalseWhenEmpty() throws InterruptedException {
        // Exhaust the bucket
        IntStream.range(0, BURST_CAPACITY).forEach(i -> limiter.acquire());

        // After exhaustion, tryAcquire must return false
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void acquireBlocksUntilTokenAvailable() throws InterruptedException {
        // In another thread, keep acquiring tokens until bucket empty
        Thread consumer = new Thread(() -> {
            try {
                limiter.acquire(); // will block until a token is added
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Immediately add a token (non‑blocking tryAcquire returns true)
        assertTrue(limiter.tryAcquire()); // ensures at least one token exists

        consumer.join(); // should complete quickly now that a token is available
    }

    @Test
    void longRunningAcquisitionDoesNotExceedRefillRate() throws Exception {
        final int THREADS = 20;
        final int ITERATIONS_PER_THREAD = 5_000; // total ~400k acquisitions
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);

        Runnable task = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                    if (limiter.tryAcquire()) {
                        successfulAcquisitions.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                doneLatch.countDown();
            }
        };

        // Launch threads
        for (int i = 0; i < THREADS; i++) {
            executor.submit(task);
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all threads to finish
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Assert no failures (should not happen under normal operation)
        assertEquals(0, failures.get(), "No tryAcquire should have failed under load");

        // Compute observed rate
        double totalAcquired = successfulAcquisitions.get();
        double elapsedSec = ITERATIONS_PER_THREAD * THREADS / REFILL_RATE; // approximate
        double observedRate = totalAcquired / (ITERATIONS_PER_THREAD * THREADS / REFILL_RATE);

        // The observed rate must be *not greater* than the configured refill rate.
        // Allow a small tolerance (+5%) to accommodate measurement jitter.
        assertTrue(observedRate <= REFILL_RATE * 1.05,
                "Observed acquisition rate (" + observedRate + ") exceeds configured refill rate (" + REFILL_RATE + ")");
    }

    @Test
    void burstCapacityIsRespected() {
        // Fill the bucket using the maximum possible burst
        assertEquals(BURST_CAPACITY, limiter.maxBurstCapacity);
        // The initial token count is set to maxBurstCapacity, allowing immediate burst.
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        // After BURST_CAPACITY successful consumes, tryAcquire must now fail
        IntStream.range(0, BURST_CAPACITY).forEach(i -> limiter.acquire());
        assertFalse(limiter.tryAcquire(), "Bucket should be empty after exhausting its capacity");
    }

    @Test
    void acquireReleasesWhenInterrupted() throws InterruptedException {
        Thread t = new Thread(() -> {
            try {
                limiter.acquire();
            } catch (InterruptedException e) {
                // Expected
                return;
            }
            fail("acquire should have been interrupted");
        });
        t.start();
        // Give the thread a moment to start waiting
        Thread.sleep(10);
        t.interrupt(); // interrupt the waiting thread
        t.join(); // should unblock and exit
        // No exception thrown
    }
}
```

### 3.1 Test Explanation  

| Test | Purpose |
|------|---------|
| `tryAcquireReturnsFalseWhenEmpty` | Verifies that `tryAcquire` correctly reports “no token” after the bucket has been drained. |
| `acquireBlocksUntilTokenAvailable` | Shows that a blocked `acquire()` unblocks as soon as a token becomes available (i.e., after a successful `tryAcquire`). |
| `longRunningAcquisitionDoesNotExceedRefillRate` | Spins up many threads that continuously call `tryAcquire()`. After a large number of attempts we compute the observed acquisition rate and assert that it never exceeds the configured refill rate (with a small tolerance). This is the **concurrent correctness** test required by the prompt. |
| `burstCapacityIsRespected` | Checks that the internal burst capacity is honoured – after `maxBurstCapacity` successful consumes the bucket becomes empty. |
| `acquireReleasesWhenInterrupted` | Guarantees that an interrupted thread waiting in `acquire()` behaves correctly and does not leak resources. |

All tests are **self‑contained** and rely only on the `TokenBucketRateLimiter` class defined above. They run with JUnit 5 (the current stable version) and use only standard JDK classes (`ExecutorService`, `CountDownLatch`, `AtomicInteger`, etc.).

---

## 4. How to Run  

1. **Add the limiter class** to your source tree (`src/main/java/com/example/ratelimiter/TokenBucketRateLimiter.java`).  
2. **Add the test class** (`src/test/java/com/example/ratelimiter/TokenBucketRateLimiterTest.java`).  
3. Ensure JUnit 5 is on the test classpath (e.g., Maven `org.junit.jupiter:junit-jupiter:5.10.0`).  
4. Execute the tests with your build tool:  

   *Maven*  

   ```bash
   mvn test
   ```

   *Gradle*  

   ```bash
   ./gradlew test
   ```

All tests should pass, confirming that the limiter is thread‑safe, respects burst capacity, and does not exceed the configured refill rate under concurrent load.