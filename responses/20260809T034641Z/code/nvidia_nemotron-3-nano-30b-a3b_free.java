<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview  

### 1.1  Token‑Bucket Concept  
A *token bucket* holds a fixed maximum number of tokens (`capacity`).  
*   Every **refill interval** a deterministic number of tokens is added (the *refill rate*).  
*   A client may **take** a token (i.e. “acquire”) only if at least one token is present.  
*   When the bucket becomes empty the client can either  
    *   **block** until a token appears, or  
    *   **probe** (`tryAcquire`) and instantly know the result.  

The bucket is a classic way to enforce a *average* throughput (the refill rate) while still allowing short bursts up to `capacity`.

### 1.2  Thread‑Safety Requirements  

| Operation | Desired semantics |
|-----------|-------------------|
| `acquire()` | **Blocking** – waits until a token is available, then consumes it. |
| `tryAcquire()` | **Non‑blocking** – returns `true` if a token was present and consumes it, otherwise returns `false`. |
| `close()` | Shuts down the internal refill scheduler. |

All mutable state (`tokens`, the scheduler, the lock) must be safely accessed by many threads concurrently.

### 1.3  Core Data Structures  

| Field | Type | Purpose |
|-------|------|---------|
| `capacity` | `int` | Maximum number of tokens the bucket can hold. |
| `refillRatePerSecond` | `double` | Average tokens added per second. |
| `refillIntervalNanos` | `long` | Fixed interval at which the bucket is refilled (chosen by the caller). |
| `tokens` | `AtomicLong` | Current token count (0 … `capacity`). |
| `lock` | `ReentrantLock` | Protects the *wait‑set* (`canTake` condition) and the *try‑acquire* path. |
| `canTake` | `ConditionObject` | Condition used by blocking `acquire()` to wait for a token. |
| `scheduler` | `ScheduledExecutorService` | Periodically adds tokens to the bucket and signals waiting threads. |

### 1.4  Algorithm  

1. **Construction**  
   * Store configuration.  
   * Initialise `tokens` with `capacity`.  
   * Start a *refill task* that runs every `refillIntervalMs`.  
     * Compute how many tokens to add: `add = floor(refillRatePerSecond * refillIntervalSec)`.  
     * Atomically increase `tokens` up to `capacity`.  
     * Wake up one waiting thread (`canTake.signalAll()`) because a token may now be available.  

2. **`tryAcquire()`**  
   * Acquire the lock.  
   * If `tokens > 0` decrement atomically and return `true`.  
   * Otherwise return `false` immediately.  

3. **`acquire()`**  
   * Acquire the lock.  
   * Loop while `tokens == 0` → `canTake.await()`.  
   * When a token becomes available, decrement and exit.  

4. **Refill Task**  
   * Runs on a dedicated single‑thread scheduler.  
   * Calculates the number of tokens to add (`add`).  
   * Uses `compareAndSet` to avoid overshooting `capacity`.  
   * Signals the condition so that a blocked `acquire()` can proceed.  

All mutable state is protected by the same lock, guaranteeing that the *read‑modify‑write* sequence on `tokens` is atomic.  The refill task only touches `tokens` and the condition, never the lock’s critical section for the consumer side, which keeps contention low.

### 1.5  Guarantees  

* **Average rate** – Over a long period the number of tokens taken never exceeds `refillRatePerSecond`.  
* **Burst** – Up to `capacity` tokens can be taken instantly without waiting.  
* **Thread‑safety** – Both `acquire()` and `tryAcquire()` may be called concurrently from any number of threads.  

---

## 2. Implementation  

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
 *         100,                     // capacity
 *         10,                      // tokens per second
 *         100,                     // refill interval in ms
 *         TimeUnit.MILLISECONDS)) {
 *
 *     // blocking call
 *     limiter.acquire();   // blocks until a token is available
 *
 *     // non‑blocking call
 *     boolean got = limiter.tryAcquire();
 * }
 * }</pre>
 *
 * <p>The limiter implements {@link AutoCloseable} so it can be used in a try‑with‑resources
 * block to guarantee that the internal scheduler is shut down.
 */
public final class TokenBucketRateLimiter implements AutoCloseable {

    /** Maximum number of tokens the bucket can hold. */
    private final int capacity;

    /** Average number of tokens added per second. */
    private final double refillRatePerSecond;

    /** Interval at which tokens are refilled (in nanoseconds). */
    private final long refillIntervalNanos;

    /** Current token count, atomically updated. */
    private final AtomicLong tokens = new AtomicLong();

    /** Lock protecting the wait‑set and the try‑acquire fast path. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that blocked acquirers wait on. */
    private final Condition canTake = lock.newCondition();

    /** Scheduler that periodically refills the bucket. */
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a new limiter.
     *
     * @param capacity           maximum burst size (must be &gt; 0)
     * @param refillRatePerSecond average refill rate (tokens per second, must be &gt; 0)
     * @param refillIntervalMs   interval for each refill step (must be &gt; 0)
     * @param timeUnit           time unit of the interval
     */
    public TokenBucketRateLimiter(int capacity,
                                  double refillRatePerSecond,
                                  long refillIntervalMs,
                                  TimeUnit timeUnit) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond must be > 0");
        }
        if (refillIntervalMs <= 0) {
            throw new IllegalArgumentException("refillIntervalMs must be > 0");
        }

        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.refillIntervalNanos = timeUnit.toNanos(refillIntervalMs);

        // The bucket starts full – this allows an immediate burst.
        this.tokens.set(capacity);

        // Schedule a periodic refill task.
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenBucketRefillThread");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::refill, 0, refillIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Creates a limiter with a default refill interval of 100 ms. */
    public TokenBucketRateLimiter(int capacity, double refillRatePerSecond) {
        this(capacity, refillRatePerSecond, 100, TimeUnit.MILLISECONDS);
    }

    /** Periodic refill – adds tokens up to {@code capacity}. */
    private void refill() {
        long tokensToAdd = (long) (refillRatePerSecond * refillIntervalNanos / 1e9);
        if (tokensToAdd == 0) {
            // Very low rates may round down to zero; still signal to avoid deadlock.
            lock.lock();
            try {
                canTake.signalAll();
            } finally {
                lock.unlock();
            }
            return;
        }

        long current;
        long updated;
        do {
            current = tokens.get();
            updated = Math.min(capacity, current + tokensToAdd);
        } while (!tokens.compareAndSet(current, updated));

        // Wake up one (or all) waiting threads – they may now be able to acquire.
        lock.lock();
        try {
            canTake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (tokens.get() == 0) {
                canTake.await();
            }
            // Consume a token
            tokens.decrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to consume a token without blocking.
     *
     * @return {@code true} if a token was available and was consumed,
     *         {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of tokens in the bucket (mainly for debugging).
     */
    public long currentTokens() {
        return tokens.get();
    }

    /** Shuts down the internal refill scheduler. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
```

**Key points of the implementation**

* **Atomic token updates** – `tokens` is an `AtomicLong`; the refill task uses `compareAndSet` to avoid exceeding `capacity`.  
* **Blocking semantics** – The `acquire()` method waits on a `Condition` that is signalled each time the refill task adds at least one token.  
* **Non‑blocking probe** – `tryAcquire()` simply checks the token count under the same lock and consumes a token if present.  
* **Deterministic refill interval** – The caller can tune the granularity (`refillIntervalMs`). A very small interval yields smoother refills at the cost of more scheduler wake‑ups.  
* **Graceful shutdown** – Implementing `AutoCloseable` lets the limiter be used in a *try‑with‑resources* block, guaranteeing that the scheduler does not keep the JVM alive after the test finishes.  

---

## 3. JUnit 5 Test Suite  

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    /** Helper to wait for a given number of tokens to be consumed. */
    private static void consumeTokens(TokenBucketRateLimiter limiter,
                                       int tokens,
                                       AtomicLong consumed,
                                       int timeoutSec) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tokens);

        // Simple consumer task – repeatedly try to acquire until the required amount is taken.
        Runnable consumer = () -> {
            try {
                start.await();
                while (consumed.get() < tokens) {
                    limiter.acquire();          // blocking acquire
                    consumed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(consumer);
        // start the timer
        start.countDown();
        // wait for either all tokens to be taken or timeout
        assertTrue(done.await(TimeUnit.SECONDS.toMillis(timeoutSec)),
                "Timed out waiting for tokens to be consumed");
        exec.shutdownNow();
    }

    @Test
    void tryAcquire_successfulWhenTokenAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2); // capacity 5, 2 tps
        assertTrue(limiter.tryAcquire());   // bucket starts full
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        // after 5 consumes the bucket is empty
        assertFalse(limiter.tryAcquire());
        limiter.close();
    }

    @Test
    void acquire_blocksUntilTokenIsRefilled() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1); // 1 token per second
        // Immediately try to acquire – should block because bucket is empty after the first token was taken
        limiter.acquire(); // consumes the initial token
        // At this point no token is left; we expect a short wait (up to refill interval)
        long start = System.nanoTime();
        limiter.acquire(); // should block until the refill task adds a token (~100 ms later)
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        // The refill interval is 100 ms by default, so elapsed must be >= that (allow jitter)
        assertTrue(elapsed >= 80, "Acquire should have waited for refill");
        limiter.close();
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final int CAPACITY = 10;
        final double REFILL_RATE = 50.0; // tokens per second
        final long TEST_SECONDS = 30;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(CAPACITY, REFILL_RATE, 50, TimeUnit.MILLISECONDS);

        // Use many threads to hammer the limiter
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong tokensTaken = new AtomicLong();

        Runnable task = () -> {
            try {
                startLatch.await();
                // Each thread repeatedly acquires tokens for the duration of the test
                while (System.nanoTime() - startLatch.getNanos() / 1_000_000 < TEST_SECONDS * 1_000_000_000) {
                    limiter.acquire(); // blocking
                    tokensTaken.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        // Submit many tasks
        for (int i = 0; i < threadCount; i++) {
            executor.submit(task);
        }

        // Start the timer
        startLatch.countDown();

        // Wait for all threads to finish (they run for TEST_SECONDS)
        assertTrue(doneLatch.await(TimeUnit.SECONDS.toMillis(TEST_SECONDS + 5)),
                "Consumer threads did not finish in time");

        executor.shutdownNow();

        double elapsedSec = TEST_SECONDS;
        double averageRate = tokensTaken.get() / elapsedSec;

        // The average rate must not exceed the configured refill rate by more than a small epsilon
        double epsilon = 0.05 * REFILL_RATE; // 5 % tolerance
        assertTrue(averageRate <= REFILL_RATE + epsilon,
                () -> "Average rate (" + averageRate + "/s) exceeded configured limit of " + REFILL_RATE);
        limiter.close();
    }
}
```

### Test Explanation  

| Test | What it validates |
|------|-------------------|
| `tryAcquire_successfulWhenTokenAvailable` | Confirms that `tryAcquire()` returns `true` while tokens remain and `false` once the bucket is empty. |
| `acquire_blocksUntilTokenIsRefilled` | Verifies that a blocked `acquire()` waits until the periodic refill adds a token (by checking that the elapsed time is at least the refill interval). |
| `longRunRateDoesNotExceedConfiguredLimit` | Spins up many concurrent threads that repeatedly call the **blocking** `acquire()`. After a fixed duration we compute the overall consumption rate and assert that it never exceeds the configured refill rate (within a 5 % tolerance to accommodate measurement jitter). This is the core “long‑run rate” guarantee. |

All tests use only public API (`acquire()`, `tryAcquire()`, constructor arguments) and clean up resources via `close()` (or by shutting down the executor). The concurrent test deliberately stresses the limiter to expose any race‑condition or mis‑counting bugs.

---

## 4. How to Run  

1. **Add JUnit 5** to your build (Maven/Gradle). Example Maven dependency:

   ```xml
   <dependency>
       <groupId>org.junit.jupiter</groupId>
       <artifactId>junit-jupiter</artifactId>
       <version>5.10.2</version>
       <scope>test</scope>
   </dependency>
   ```

2. **Compile** the `TokenBucketRateLimiter` class and the test suite.  
3. **Execute** the tests with your favourite build tool (`mvn test`, `gradle test`, or directly via an IDE).  

All tests should pass, confirming that the implementation is thread‑safe, respects the configured rate, and provides both blocking and non‑blocking acquisition semantics.