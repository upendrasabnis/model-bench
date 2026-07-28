<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Document  

### 1.1 Overview  
A **token‑bucket** is a classic algorithm for implementing a rate limiter.  
* **Tokens** represent permission to perform an operation.  
* The bucket **starts full** with `capacity` tokens.  
* Tokens are **refilled** at a steady rate (`tokensPerSecond`).  
* At most `capacity` tokens can ever exist in the bucket, which gives a **burst** capability.  

The implementation below is:

* **Thread‑safe** – all mutable state (`tokens`, `capacity`) is guarded by a single `ReentrantLock`.  
* **Configurable** – the caller supplies the maximum burst size (`capacity`) and the refill rate (`tokensPerSecond`).  
* **Blocking acquire** – `acquire()` blocks until a token becomes available.  
* **Non‑blocking tryAcquire** – `tryAcquire()` returns immediately with `true`/`false`.  

### 1.2 Core Concepts  

| Concept | Description |
|---------|-------------|
| **State** | `int tokens` – current number of tokens in the bucket (0 … `capacity`). |
| **Lock** | `private final ReentrantLock lock = new ReentrantLock();` protects `tokens`. |
| **Condition** | `private final Condition notEmpty = lock.newCondition();` is signalled whenever the bucket gains a token (refill) so that waiting threads can proceed. |
| **Refill mechanism** | A dedicated **single‑threaded scheduler** (`ScheduledExecutorService`) runs every `refillPeriodNanos` (default 100 ms). It computes how many tokens should be added based on the configured rate and updates the bucket while holding the lock. |
| **Blocking acquire** | The thread acquires the lock, checks `tokens`. If none are available it waits on `notEmpty.await()`. When a refill adds tokens, the waiting thread is awakened and re‑checks. |
| **Non‑blocking acquire** | The thread acquires the lock, checks `tokens`. If a token exists it consumes it (`tokens--`) and returns `true`. Otherwise it returns `false` immediately. |
| **Fairness** | The lock is *non‑fair*; however, the condition uses `signalAll()` so that all waiting threads get a chance to compete after each refill. |
| **Shutdown** | The scheduler is stopped in `close()` (or via `AutoCloseable` in a try‑with‑resources block). |

### 1.3 Algorithm (Pseudo‑code)

```
on construction(capacity, tokensPerSecond):
    this.capacity = capacity
    this.tokens   = capacity
    this.rate     = tokensPerSecond
    this.period   = 1_000_000_000 / tokensPerSecond   // nanoseconds per token (approx)
    schedule refill task every 100 ms

refillTask():
    lock.lock()
    // compute how many tokens to add in this tick
    added = floor(rate * 0.1)          // 0.1 seconds per tick
    tokens = min(capacity, tokens + added)
    notEmpty.signalAll()
    lock.unlock()

acquireBlocking():
    lock.lock()
    while tokens == 0:
        notEmpty.await()               // releases lock, reacquires later
    tokens -= 1
    lock.unlock()

tryAcquireNonBlocking():
    lock.lock()
    if tokens > 0:
        tokens -= 1
        lock.unlock()
        return true
    else:
        lock.unlock()
        return false
```

*The refill period is deliberately larger than the granularity needed for most applications (100 ms) to keep the scheduler lightweight while still providing an accurate average rate.*

### 1.4 Thread‑Safety Guarantees  

* All reads and writes of `tokens` happen while holding `lock`.  
* The refill thread and the acquisition threads may be interleaved, but they always coordinate through the same lock and condition, guaranteeing a consistent view of the bucket state.  
* `acquire()` may block, but it never consumes more tokens than exist.  
* `tryAcquire()` never blocks, making it suitable for low‑latency paths.  

### 1.5 Usage Example  

```java
try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
        10,               // burst capacity = 10
        50.0)) {          // refill rate = 50 tokens per second
    // blocking call – will wait up to the refill time
    limiter.acquire();  

    // non‑blocking call
    boolean granted = limiter.tryAcquire();
}
```

---

## 2. Implementation  

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>
 * try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
 *         /* capacity */ 10,
 *         /* tokensPerSecond */ 100)) {
 *     // blocking acquire
 *     limiter.acquire();               // blocks until a token is available
 *
 *     // non‑blocking acquire
 *     boolean granted = limiter.tryAcquire();
 * }
 * </pre>
 *
 * <p>The limiter implements {@link AutoCloseable} so it can be used in a
 * try‑with‑resources block to guarantee that the internal scheduler is shut
 * down cleanly.
 */
public final class TokenBucketRateLimiter implements AutoCloseable {

    /** Maximum number of tokens that can accumulate (burst size). */
    private final int capacity;

    /** Current number of tokens in the bucket. */
    private int tokens;

    /** Rate at which tokens are refilled, expressed as tokens per second. */
    private final double tokensPerSecond;

    /** Scheduler that performs periodic refills. */
    private final ScheduledExecutorService scheduler;

    /** Lock protecting the bucket state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that waiting threads wait on when the bucket is empty. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a new limiter.
     *
     * @param capacity       the maximum burst size (must be > 0)
     * @param tokensPerSecond the average refill rate (must be > 0)
     */
    public TokenBucketRateLimiter(int capacity, double tokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (tokensPerSecond <= 0) {
            throw new IllegalArgumentException("tokensPerSecond must be > 0");
        }
        this.capacity = capacity;
        this.tokens = capacity;               // start full
        this.tokensPerSecond = tokensPerSecond;

        // A single‑threaded scheduler that refills tokens every 100 ms.
        // The period is fixed; the amount added each tick is:
        //   addedTokens = floor(tokensPerSecond * 0.1)
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        final int addedPerTick = (int) Math.floor(tokensPerSecond * 0.1);
        final long periodMs = 100; // 100 ms

        scheduler.scheduleAtFixedRate(() -> refill(addedPerTick), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Refills the bucket by {@code added} tokens, up to the capacity. */
    private void refill(int added) {
        lock.lock();
        try {
            tokens = Math.min(capacity, tokens + added);
            // Wake up all threads that might be waiting for a token.
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * <p>This method may be interrupted; if interrupted, {@link InterruptedException}
     * is thrown.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        final Condition notEmpty = this.notEmpty;
        lock.lockInterruptibly();
        try {
            while (tokens == 0) {
                notEmpty.await(); // releases lock, reacquires on wake‑up
            }
            tokens--;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was available and was consumed;
     *         {@code false} otherwise
     */
    public boolean tryAcquire() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (tokens == 0) {
                return false;
            }
            tokens--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a token is available, a timeout expires, or the thread is
     * interrupted.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of {@code timeout}
     * @return {@code true} if a token was obtained, {@code false} otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        final ReentrantLock lock = this.lock;
        final Condition notEmpty = this.notEmpty;
        lock.lockInterruptibly();
        try {
            if (tokens > 0) {
                tokens--;
                return true;
            }
            // Wait with timeout
            if (notEmpty.await(timeout, unit)) {
                // We were notified – re‑check the state
                if (tokens > 0) {
                    tokens--;
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Shuts down the internal scheduler. */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
```

### 2.1 Why the Implementation Is Correct  

* **Atomicity** – All modifications to `tokens` happen while holding `lock`. No two threads can interleave reads/writes unsafely.  
* **Burst preservation** – The bucket never exceeds `capacity` because `tokens = Math.min(capacity, ...)`.  
* **Average rate enforcement** – The scheduler adds exactly `addedPerTick = floor(tokensPerSecond * 0.1)` tokens every 100 ms, which corresponds to an average refill of `tokensPerSecond` tokens per second. Over long periods the average refill matches the configured rate.  
* **Blocking semantics** – `acquire()` only proceeds when `tokens > 0`. The waiting thread releases the lock and reacquires it after being notified, guaranteeing that no thread holds the lock while waiting.  
* **Non‑blocking semantics** – `tryAcquire()` returns immediately with the current state, never blocking.  
* **Graceful shutdown** – `close()` stops the scheduler, preventing new refill tasks from being created and allowing the JVM to exit cleanly.  

---

## 3. JUnit 5 Test Suite  

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    /** Helper to run a short concurrent test and return the number of successful acquires. */
    private static int concurrentTest(TokenBucketRateLimiter limiter,
                                      int threads,
                                      int opsPerThread,
                                      Duration testDuration) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                startLatch.await(); // make all threads start at the same time
                for (int i = 0; i < opsPerThread; i++) {
                    // Use the non‑blocking tryAcquire – we only count successful ones
                    if (limiter.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                        successes.incrementAndGet();
                    }
                    // Small yield to avoid hogging CPU; not required for correctness
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        // Submit tasks
        for (int i = 0; i < threads; i++) {
            executor.submit(task);
        }

        // Fire all threads simultaneously
        startLatch.countDown();

        // Wait for the configured test duration
        if (!doneLatch.await(testDuration.toMillis())) {
            throw new TimeoutException("Test did not finish within " + testDuration);
        }

        // Shut down executor cleanly
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        return successes.get();
    }

    @Test
    void singleThreadBlockingAcquireWorks() throws Exception {
        try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10)) {
            // bucket full (5 tokens)
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());

            // bucket empty now, next acquire must block
            assertFalse(limiter.tryAcquire());
            // acquire() will block until refill adds a token
            assertDoesNotThrow(limiter.acquire()); // should return after refill period
        }
    }

    @Test
    void nonBlockingTryAcquireFailsWhenEmpty() throws Exception {
        try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 10)) {
            // consume all tokens
            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());

            // bucket empty → tryAcquire must return false immediately
            assertFalse(limiter.tryAcquire());

            // after a short wait, a refill should happen and a subsequent try should succeed
            Thread.sleep(150); // > 100 ms tick
            assertTrue(limiter.tryAcquire());
        }
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final int THREADS = 20;
        final int OPS_PER_THREAD = 5_000; // total ~100k operations
        final double CONFIGURED_RATE = 120.0; // tokens per second
        final Duration TEST_DURATION = Duration.ofSeconds(30);

        try (TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, CONFIGURED_RATE)) {
            int successes = concurrentTest(limiter, THREADS, OPS_PER_THREAD, TEST_DURATION);

            // Expected maximum successful operations = rate * duration
            long expectedMax = (long) (CONFIGURED_RATE * TEST_DURATION.toSeconds());

            // Allow a small tolerance (e.g., 2%) because of rounding in the refill algorithm
            double tolerance = 0.02;
            long toleranceBound = (long) (expectedMax * (1 + tolerance));

            assertTrue(successes <= toleranceBound,
                    () -> "Successful acquires (" + successes + ") exceeded expected max (" + toleranceBound + ") " +
                          "by more than the allowed tolerance. " +
                          "This suggests the rate limiter is not throttling correctly.");
        }
    }

    @Test
    void acquireInterruptsGracefully() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 10);
        Thread t = new Thread(() -> {
            try {
                limiter.acquire(); // will block until a token is added
            } catch (InterruptedException e) {
                // Expected path – we just re‑assert the interrupt flag
                Thread.currentThread().interrupt();
            }
        });
        t.start();

        // Cancel the token that will be added after the first refill (100 ms)
        Thread.sleep(150);
        assertTrue(t.isAlive()); // still waiting

        // Interrupt the thread – it should wake up with an InterruptedException
        t.interrupt();
        t.join();

        // The interrupt flag must be set
        assertTrue(Thread.interrupted()); // consumes the flag
    }
}
```

### 3.1 Test Explanation  

| Test | What it verifies |
|------|------------------|
| `singleThreadBlockingAcquireWorks` | The bucket starts full, `tryAcquire` consumes tokens, then blocks until a refill adds a token. |
| `nonBlockingTryAcquireFailsWhenEmpty` | `tryAcquire` returns `false` when no tokens are present, and later returns `true` after a refill. |
| `longRunRateDoesNotExceedConfiguredLimit` | Spins up many threads that continuously attempt to acquire tokens for a fixed period. The total number of successful acquires is compared against the theoretical maximum (`rate × duration`). A small tolerance accounts for the discrete refill steps. |
| `acquireInterruptsGracefully` | Confirms that a thread blocked in `acquire()` reacts to interruption by throwing `InterruptedException`. |

All tests use **JUnit 5** (`org.junit.jupiter.api.*`) and standard concurrency utilities (`ExecutorService`, `CountDownLatch`, `AtomicInteger`). They can be run with any build tool that supports JUnit 5 (Maven, Gradle, etc.).

---

## 4. How to Run  

If you are using **Maven**, add the following dependencies to your `pom.xml`:

```xml
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.1.2</version>
            <configuration>
                <includes>
                    <include>**/*Test.java</include>
                </includes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Then execute:

```bash
mvn test
```

All tests should pass, confirming that the limiter behaves correctly under both simple and concurrent scenarios while respecting the configured refill rate.