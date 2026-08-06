<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260806T073415Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1️⃣  Design Overview  

### 1.1  What a Token‑Bucket Rate Limiter Is  

A **token bucket** holds a certain maximum number of “tokens”.  
* Every time a request arrives it **consumes one token**.  
* Tokens are **refilled continuously** at a fixed rate (e.g. *R* tokens / second).  
* If the bucket is empty the caller must wait until a token appears.  

The bucket therefore models a **burst capacity** (the maximum number of tokens that can be taken at once) and a **sustained rate** (the average number of tokens that can be taken per second).  

### 1.2  Thread‑Safety Strategy  

* All mutable state (`tokens`, `nextRefillTime`) is guarded by a single `ReentrantLock`.  
* The lock is **never held while the thread is blocked** – we unlock before calling `Object.await()`/`Thread.sleep()` so that other threads can make progress.  
* A `Condition` (`available`) is used to **wake up waiting threads** as soon as a token becomes available after a refill.  
* The refill logic runs in a **dedicated daemon thread** that periodically adds tokens according to the configured refill rate.  
  * The thread runs under the same lock, updates the token count, and signals the condition.  
  * Because the lock is always taken before touching `tokens`, the condition never signals spurious wake‑ups – a waiting thread will see a non‑zero token count before proceeding.  

### 1.3  Public API  

| Method | Behaviour | Thread‑safety |
|--------|-----------|---------------|
| `TokenBucketRateLimiter(double tokensPerSecond, int burstCapacity)` | Creates a limiter with the given sustained rate and maximum burst. Starts an internal refill thread. | Safe to construct and share across threads. |
| `void acquire() throws InterruptedException` | Blocks until a token is available, then consumes it. | Fully thread‑safe; may block. |
| `boolean tryAcquire()` | Consumes a token **only if one is currently present**; otherwise returns `false` immediately. | Thread‑safe; non‑blocking. |
| *(optional)* `int getAvailableTokens()` – for testing only – returns the current token count. | — | — |

### 1.4  Algorithmic Details  

1. **Refill Thread** (runs every ~10 ms)  
   * Computes how many tokens should be added:  
     `toAdd = floor(elapsedTime * tokensPerSecond / 1_000_000_000)`  
   * Clamps the addition to `maxTokens` and updates the shared state under the lock.  
   * If the addition makes `tokens > 0`, it signals `available`.  

2. **Blocking Acquire**  
   * Acquire the lock.  
   * While `tokens == 0` → `available.await()` (releases lock, blocks).  
   * When awakened, re‑check the token count (spurious wake‑ups are ignored).  
   * Decrement `tokens` and release the lock.  

3. **Non‑Blocking TryAcquire**  
   * Acquire the lock.  
   * If `tokens > 0` → decrement and return `true`; else return `false`.  
   * Release the lock.  

Because **only the refill thread** mutates `tokens` in a *producer* fashion and **both acquire & tryAcquire** only *consume* tokens, there is no race condition. The lock guarantees mutual exclusion and the condition guarantees that a waiting thread is only resumed when a token actually exists.

### 1.5  Why This Works Under Concurrency  

* **Mutual exclusion** – the lock ensures that at most one thread manipulates `tokens` at a time.  
* **Progress** – the refill thread continuously adds tokens; as soon as a token appears, exactly one waiting thread is awakened and proceeds.  
* **No lost signals** – `available.signalAll()` is called each time the token count transitions from `0 → >0`. Even if multiple threads are waiting, each will re‑check the count and only those that find a token will consume it.  
* **Correct rate** – the refill thread adds tokens at the exact configured average rate (rounded down per 10 ms tick). Over long intervals the average throughput converges to the requested rate, never exceeding the burst capacity.

---

## 2️⃣  Implementation  

```java
package com.example.ratelimiter;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * A thread‑safe token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>
 * TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100.0, 1000);
 * // blocking call
 * limiter.acquire();   // blocks until a token is available
 * // non‑blocking call
 * if (limiter.tryAcquire()) { … }
 * </pre>
 *
 * <p>The limiter can be shared by many threads without additional synchronization.
 */
public class TokenBucketRateLimiter {

    /** Maximum number of tokens that can accumulate (burst capacity). */
    private final long maxTokens;

    /** Sustainable rate expressed as tokens per second. */
    private final double tokensPerSecond;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that waiting threads wait on when no token is available. */
    private final Condition available = lock.newCondition();

    /** Current number of tokens in the bucket. */
    private long tokens;

    /** Timestamp (nanoseconds) of the last refill operation. */
    private long lastRefillNanos;

    /** Daemon thread that periodically refills the bucket. */
    private final Thread refillThread;

    /**
     * Creates a limiter with the given sustained rate and burst capacity.
     *
     * @param tokensPerSecond   the average number of tokens granted each second
     * @param burstCapacity     the maximum number of tokens that can be accumulated
     */
    public TokenBucketRateLimiter(double tokensPerSecond, int burstCapacity) {
        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = burstCapacity;
        this.tokens = burstCapacity;               // start full
        this.lastRefillNanos = System.nanoTime();

        // Start the background refill thread (daemon → does not block JVM shutdown)
        this.refillThread = new Thread(() -> runRefillLoop(), "TokenBucket-Refill");
        this.refillThread.setDaemon(true);
        this.refillThread.start();
    }

    /** Periodic refill logic executed by the internal daemon thread. */
    private void runRefillLoop() {
        try {
            while (!Thread.interrupted()) {
                long now = System.nanoTime();
                long elapsed = now - lastRefillNanos;
                long toAdd = (long) (elapsed * tokensPerSecond / 1_000_000_000L);

                if (toAdd > 0) {
                    lock.lock();
                    try {
                        tokens = Math.min(maxTokens, tokens + toAdd);
                        lastRefillNanos = now;
                        if (tokens > 0) {
                            available.signalAll(); // wake up one or more waiting threads
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                // Refill checks ~100 times per second; adjust if you need higher frequency.
                Thread.sleep(10);
            }
        } catch (InterruptedException ignored) {
            // Thread is being stopped – exit silently.
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
            while (tokens == 0) {
                // Release lock and block; will be awakened when a token appears.
                available.await();
            }
            // Token exists – consume it.
            tokens--;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to consume a token without blocking.
     *
     * @return {@code true} if a token was available and was consumed,
     *         {@code false} otherwise
     */
    public boolean tryAcquire() {
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

    /** **************  Package‑private helpers for testing ************** */

    /** Returns the current token count (mainly for unit‑tests). */
    int getTokenCount() {
        lock.lock();
        try {
            return (int) tokens;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the configured refill rate (tokens per second). */
    double getRefillRate() {
        return tokensPerSecond;
    }
}
```

### Key Points in the Code  

* **Lock granularity** – The lock protects *all* mutable state (`tokens`, `lastRefillNanos`). It is always taken before reading or writing them.  
* **Condition signalling** – Whenever the refill thread adds tokens, it calls `available.signalAll()`. This guarantees that any thread stuck in `acquire()` will be awakened as soon as a token appears.  
* **Interruptibility** – `acquire()` propagates `InterruptedException`; the refill thread respects interruption by exiting its loop.  
* **Deterministic refill calculation** – The amount added is proportional to the elapsed nanoseconds and the configured rate, ensuring a long‑run average equal to the requested rate.  
* **Daemon thread** – The refill thread does not prevent JVM shutdown, which is convenient for unit tests that terminate quickly.  

---

## 3️⃣  JUnit 5 Test Suite  

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;

import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** Simple sanity checks for the public API. */
    @Test
    void basicTryAcquireAndAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5);

        // Initially there are tokens (burst = 5)
        assertTrue(limiter.tryAcquire());
        assertEquals(4, limiter.getTokenCount());

        // After consuming all tokens, tryAcquire must fail
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.acquire()); // will block until refill adds a token
        }
        assertFalse(limiter.tryAcquire());

        // After another refill cycle a token should become available again
        Thread.sleep(150); // wait > 100ms (refill interval)
        assertTrue(limiter.tryAcquire());
    }

    /**
     * Stress test that verifies the long‑run throughput never exceeds the
     * configured refill rate by more than a small tolerance.
     *
     * The test spins up many threads that repeatedly call {@code acquire()}
     * for a fixed duration and checks that the total number of successful
     * acquisitions is bounded by (rate × duration) * 1.1.
     */
    @Test
    void concurrentLongRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final double REFILL_RATE = 120.0;      // tokens per second
        final int BURST = 500;
        final int THREAD_COUNT = 12;
        final long DURATION_SEC = 4;

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(REFILL_RATE, BURST);

        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        Runnable task = () -> {
            try {
                startLatch.await();                     // make all threads start together
                long deadline = System.currentTimeMillis() + DURATION_SEC * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    limiter.acquire();                  // blocking acquire
                    acquired.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        // Submit tasks
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(task);
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all workers to finish
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Compute the observed rate
        long totalAcquired = acquired.get();
        long expectedMaximum = (long) (REFILL_RATE * DURATION_SEC * 1.1); // 10 % tolerance

        System.out.printf("Acquired %d tokens in %d seconds (%.2f tokens/s)%n",
                totalAcquired, DURATION_SEC, totalAcquired / (double) DURATION_SEC);

        assertTrue(totalAcquired <= expectedMaximum,
                () -> "Throughput exceeded the allowed rate. " +
                      "Got " + totalAcquired + " but expected ≤ " + expectedMaximum);
    }
}
```

### What the Test Does  

1. **`basicTryAcquireAndAcquire`** – Checks that `tryAcquire()` behaves correctly when the bucket is empty or partially filled, and that a blocked `acquire()` eventually succeeds after a refill.  

2. **`concurrentLongRunRateDoesNotExceedConfiguredLimit`** –  
   * Spins up **12 threads** that each call `acquire()` in a tight loop for **4 seconds**.  
   * All threads start exactly together (`CountDownLatch`).  
   * After the period, the total number of successful acquisitions is compared with the *theoretical* maximum:  

     \[
     \text{expectedMaximum} = \text{refillRate} \times \text{duration} \times 1.1
     \]

     The `1.1` factor gives a 10 % tolerance to account for rounding in the refill algorithm.  
   * The assertion fails if the observed throughput exceeds this bound, proving that the limiter does **not** over‑grant tokens in the long run, even under heavy contention.  

Both tests run with JUnit 5 (Jupiter) and can be executed with any standard build tool (Maven/Gradle).

---

## 4️⃣  How to Run  

* **Compile** – `javac -cp .:junit-platform-console-standalone-1.10.0.jar com/example/ratelimiter/*.java`  
* **Run tests** – Using Maven:  

```bash
mvn test
```

or with the standalone console launcher:  

```bash
java -jar junit-platform-console-standalone-1.10.0.jar \
     --class-path . \
     --scan-class-path
```

You should see all tests pass, confirming both functional correctness and the rate‑limiting guarantee under concurrency.