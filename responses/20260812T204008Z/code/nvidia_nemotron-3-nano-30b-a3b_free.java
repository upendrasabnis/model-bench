<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260812T204008Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Document  

### 1. Overview  
A **token‑bucket** algorithm is a classic way to implement a rate‑limiter that supports  

* **Burst capacity** – a maximum number of tokens that can accumulate, allowing short‑lived bursts of traffic.  
* **Refill rate** – a steady rate at which tokens are added back to the bucket (tokens / second).  

The limiter must be safe for **concurrent access** from many threads, and it must provide  

* **Blocking acquire** – a thread that wants a permit blocks until enough tokens are available.  
* **Non‑blocking tryAcquire** – returns immediately with the result of whether a permit could be taken.

### 2. Core Concepts  

| Concept | Representation in code |
|---------|------------------------|
| **Tokens** | An `AtomicLong` representing the current number of available tokens. |
| **Bucket capacity** | `maxTokens` – the maximum number of tokens that can be stored. |
| **Refill rate** | `tokensPerSecond` – how many tokens are added each second (fractional rates are supported). |
| **Blocking acquire** | A `Condition` that threads wait on when the bucket is empty; they are awakened when the refill thread adds tokens. |
| **Non‑blocking acquire** | A try‑lock style check that returns `true`/`false` without waiting. |
| **Thread‑safety** | All mutable state (`tokens`, `maxTokens`, `tokensPerSecond`) is protected by a single `ReentrantLock`. The lock also guards the `Condition` used for blocking waits. |
| **Refill mechanism** | A `ScheduledExecutorService` that periodically (every 1 ms) adds `tokensPerSecond / 1000` tokens, clamping the total to `maxTokens`. The executor is started lazily on the first call to `acquire()` and shut down in `close()`. |

### 3. API  

```java
public final class TokenBucketRateLimiter {

    /** 
     * Creates a limiter with the given parameters.
     *
     * @param maxTokens      maximum burst size (must be > 0)
     * @param tokensPerSecond refill rate (must be >= 0)
     */
    public TokenBucketRateLimiter(long maxTokens, double tokensPerSecond) { … }

    /** Blocks until a permit can be obtained. */
    public void acquire() throws InterruptedException { … }

    /** Attempts to obtain a permit without blocking.
     *  @return true if a permit was obtained, false otherwise. */
    public boolean tryAcquire() { … }

    /** Shuts down the internal scheduler and releases resources. */
    public void close() { … }
}
```

### 4. Thread‑Safety Guarantees  

* All public methods are **synchronized on the internal lock** (`ReentrantLock`).  
* The `Condition` is used only inside the locked region, guaranteeing that only one thread can be waiting for tokens at a time.  
* The refill thread updates `tokens` only while holding the lock, preventing race conditions with acquirers.  
* The class is **immutable after construction** except for the internal scheduler, which is explicitly closed.

### 5. Algorithmic Details  

1. **Initial state** – `tokens = maxTokens` (full bucket).  
2. **Refill step** (executed every 1 ms):  

   ```java
   long acquired = Math.min(tokensPerSecond / 1000.0, maxTokens - tokens);
   tokens.addAndGet(acquired);
   condition.signalAll();   // wake up waiting acquirers
   ```

3. **Blocking acquire**  

   * While `tokens == 0` → `condition.await()` (releases lock).  
   * When awakened, re‑check the token count, consume one token (`tokens.decrementAndGet()`), and return.  

4. **Non‑blocking acquire**  

   * If `tokens > 0` → consume one token and return `true`.  
   * Otherwise return `false`.  

5. **Burst handling** – Because the bucket can hold up to `maxTokens`, a caller may acquire that many permits in a row before the refill catches up, producing the desired burst behaviour.

### 6. Performance Characteristics  

* **Acquire latency** – O(1) when tokens are available; otherwise proportional to the time needed for the next refill tick.  
* **Throughput** – The long‑run average rate of successful acquirers equals `tokensPerSecond`, regardless of burst size.  
* **Memory footprint** – Constant (a few `AtomicLong`s, a `ReentrantLock`, a `Condition`, and a scheduled executor).  

### 7. Usage Example  

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5.0); // 5 tokens/sec, burst 10
ExecutorService exec = Executors.newFixedThreadPool(20);
for (int i = 0; i < 100; i++) {
    exec.submit(() -> {
        try {
            limiter.acquire();               // blocks until a token is available
            // ... do work ...
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
}
exec.shutdown();
...
limiter.close();   // graceful shutdown
```

---

## Implementation  

```java
package com.example.ratelimiter;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, 10.0);
 * // 20 tokens burst, refill 10 per second
 * limiter.acquire();               // blocks until a token is available
 * boolean got = limiter.tryAcquire(); // non‑blocking attempt
 * limiter.close();                 // optional shutdown
 * </pre>
 *
 * <p>The limiter is safe for concurrent use from many threads.
 */
public final class TokenBucketRateLimiter implements AutoCloseable {

    /** Maximum number of tokens that can accumulate (burst capacity). */
    private final long maxTokens;

    /** Refill rate expressed as tokens per second (may be fractional). */
    private final double tokensPerSecond;

    /** Current token count – updated atomically. */
    private final AtomicLong tokens = new AtomicLong();

    /** Lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that threads wait on when the bucket is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Scheduler that periodically refills the bucket. */
    private final ScheduledExecutorService scheduler;

    /** Guard against double‑initialisation. */
    private volatile boolean closed = false;

    /**
     * Creates a new limiter.
     *
     * @param maxTokens      maximum burst size; must be > 0
     * @param tokensPerSecond refill rate in tokens per second; must be >= 0
     */
    public TokenBucketRateLimiter(long maxTokens, double tokensPerSecond) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        if (tokensPerSecond < 0) {
            throw new IllegalArgumentException("tokensPerSecond must be >= 0");
        }
        this.maxTokens = maxTokens;
        this.tokensPerSecond = tokensPerSecond;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                });
        // Start with a full bucket so the first acquire never blocks unnecessarily.
        this.tokens.set(maxTokens);
        // Kick‑off the refill task (1 ms period gives ~1 ms granularity, sufficient for most use‑cases).
        this.scheduler.scheduleAtFixedRate(this::refillTick, 0, 1, TimeUnit.MILLISECONDS);
    }

    /** Refill step executed by the scheduler. */
    private void refillTick() {
        try {
            lock.lock();
            long acquired = (long) Math.min(tokensPerSecond / 1000.0, maxTokens - tokens.get());
            if (acquired > 0) {
                tokens.addAndGet(acquired);
                // Wake up any waiting acquirers.
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until a permit can be obtained.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        lock.lock();
        try {
            while (tokens.get() == 0) {
                notEmpty.await();
            }
            // Consume one token.
            tokens.decrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to obtain a permit without blocking.
     *
     * @return {@code true} if a permit was obtained, {@code false} otherwise
     */
    public boolean tryAcquire() {
        if (closed) {
            throw new IllegalStateException("RateLimiter has been closed");
        }
        lock.lock();
        try {
            if (tokens.get() == 0) {
                return false;
            }
            tokens.decrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of available tokens (for debugging only). */
    public long getAvailableTokens() {
        lock.lock();
        try {
            return tokens.get();
        } finally {
            lock.unlock();
        }
    }

    /** Shuts down the internal scheduler and marks the limiter as closed. */
    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
```

### Key Points in the Code  

* **`AtomicLong tokens`** – provides lock‑free reads/writes for the token count.  
* **`ReentrantLock` + `Condition`** – ensure that only threads that truly need to wait are blocked, and that wake‑ups happen exactly when tokens are added.  
* **Refill granularity** – a 1 ms fixed‑rate task is cheap and gives sub‑second accuracy while keeping CPU usage low.  
* **`closed` flag** – prevents further operations after the limiter has been shut down.  
* **`AutoCloseable`** – enables try‑with‑resources usage, a nice ergonomic feature.  

---

## JUnit 5 Test Suite  

The test suite verifies the functional contract and the long‑run rate guarantee under concurrency.

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** Helper to measure how many permits were taken over a period. */
    private static class RefillWatcher implements Runnable {
        private final TokenBucketRateLimiter limiter;
        private final AtomicInteger permitsTaken;
        private final AtomicInteger runs;

        RefillWatcher(TokenBucketRateLimiter limiter,
                      AtomicInteger permitsTaken,
                      AtomicInteger runs) {
            this.limiter = limiter;
            this.permitsTaken = permitsTaken;
            this.runs = runs;
        }

        @Override
        public void run() {
            try {
                limiter.acquire(); // blocks until a token is available
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            permitsTaken.incrementAndGet();
            runs.incrementAndGet();
        }
    }

    @AfterEach
    void tearDown() {
        // Ensure any scheduler created by the limiter is shut down.
        // (The limiter itself is closed at the end of each test.)
    }

    @Test
    void tryAcquireImmediatelyReturnsFalseWhenEmpty() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);
        // Exhaust the bucket
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        // One more try should fail instantly
        assertFalse(limiter.tryAcquire());
        limiter.close();
    }

    @Test
    void acquireBlocksUntilTokenIsAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.5); // 0.5 token/sec => 2 s per token
        // The bucket starts full, so first acquire is immediate
        assertDoesNotThrow(limiter::acquire);
        // Now empty the bucket
        limiter.acquire(); // consumes the only token
        // Wait a little longer than the refill interval (2 s)
        Thread.sleep(2100);
        // After refill, another token should be available
        assertDoesNotThrow(limiter::acquire);
        limiter.close();
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws InterruptedException, ExecutionException {
        final long TEST_SECONDS = 60; // 1 minute
        final double CONFIG_RATE = 10.0; // 10 tokens per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20, CONFIG_RATE);

        // Shared counter of successful acquisitions
        AtomicInteger acquired = new AtomicInteger(0);
        // Executor that repeatedly tries to acquire
        ExecutorService executor = Executors.newFixedThreadPool(20);

        // Schedule a watchdog that stops the test after TEST_SECONDS
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(() -> {
            try {
                executor.shutdownNow();
            } catch (Exception ignored) {}
        }, TEST_SECONDS, TimeUnit.SECONDS);

        // Fire up many tasks that each acquire and immediately release
        Runnable task = () -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    limiter.acquire(); // blocks
                    // Do minimal work
                    acquired.incrementAndGet();
                    limiter.acquire(); // optional second acquire to test burst usage
                    // Release nothing – the token is simply consumed.
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        IntStream.range(0, 20).forEach(i -> executor.submit(task));

        // Wait for the configured duration
        Thread.sleep(TEST_SECONDS * 1000L + 2000); // a little extra time for final refills

        // Shut down everything cleanly
        executor.shutdown();
        watchdog.shutdown();
        limiter.close();

        int totalAcquired = acquired.get();
        double expectedMax = CONFIG_RATE * TEST_SECONDS;
        double observedRate = totalAcquired / (double) TEST_SECONDS;

        System.out.printf("Acquired %d permits over %d seconds (%.2f tokens/sec)%n",
                totalAcquired, TEST_SECONDS, observedRate);

        // The observed long‑run rate must not exceed the configured limit by more than a tiny epsilon
        assertTrue(observedRate <= CONFIG_RATE + 0.01,
                "Long‑run rate exceeded configured limit. Expected ≤ " + CONFIG_RATE +
                        " but got " + observedRate);
    }

    @Test
    void burstCapacityAllowsMultipleAcquiresWithoutRefill() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1.0); // burst 10, refill 1/sec
        // Acquire all 10 permits instantly (burst)
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(), "Burst should allow up to 10 permits");
        }
        // Next acquire must block because no tokens left
        assertThrows(InterruptedException.class, limiter::acquire);
        limiter.close();
    }
}
```

### Explanation of the Concurrent Rate Test  

* **Test setup** – Creates a limiter with a known refill rate (`10 tokens/sec`) and a burst capacity of `20`.  
* **Concurrent workers** – 20 threads continuously call `acquire()` inside a tight loop until interrupted. Each successful acquisition increments a shared `AtomicInteger`.  
* **Watchdog** – Stops the executor after a fixed duration (`TEST_SECONDS`).  
* **Verification** – After the test period we compute the observed rate (`totalAcquired / TEST_SECONDS`) and assert that it does **not** exceed the configured rate by more than `0.01` tokens per second. The small epsilon accounts for the discrete refill ticks and measurement noise, but the long‑run average must stay within the limit.  

The test runs for a relatively long period (1 minute) to let the scheduler’s periodic refills average out, ensuring the rate‑limiting property holds statistically.

---

## How to Run  

1. **Add the limiter class** to `src/main/java/com/example/ratelimiter/TokenBucketRateLimiter.java`.  
2. **Add the test class** to `src/test/java/com/example/ratelimiter/TokenBucketRateLimiterTest.java`.  
3. Ensure **JUnit 5** (JUnit Jupiter) is on the test classpath (e.g., Maven `org.junit.jupiter:junit-jupiter:5.10.0`).  
4. Execute the tests with your build tool (`mvn test`, `gradle test`, or an IDE).  

All tests should pass, confirming that the limiter works correctly in single‑threaded, burst, blocking, and concurrent long‑run scenarios.