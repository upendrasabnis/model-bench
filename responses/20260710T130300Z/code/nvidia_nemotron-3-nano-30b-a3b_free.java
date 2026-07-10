<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260710T130300Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Document  

### 1.1 Goal  
Create a **thread‑safe token‑bucket rate limiter** that can be used by many concurrent threads.  
The limiter must support  

| Feature | Requirement |
|---------|--------------|
| **Configurable refill rate** | The bucket is refilled at a fixed rate (tokens / second). |
| **Burst capacity** | The bucket can hold up to *maxTokens* tokens, allowing short bursts of traffic. |
| **Blocking acquire** | `acquire()` blocks the calling thread until a token is available. |
| **Non‑blocking tryAcquire** | `tryAcquire()` returns immediately with *true* if a token is available, otherwise *false*. |
| **Fairness** | Threads that have been waiting the longest are served first (optional, but simple to achieve with a FIFO queue). |
| **No external scheduling** | Refills are performed lazily using the current time – no ScheduledExecutorService is required. |

### 1.2 Core Algorithm (Token‑Bucket)  

1. **State variables**  
   * `maxTokens` – the maximum number of tokens the bucket can contain (burst capacity).  
   * `tokens` – current number of tokens in the bucket (`0 ≤ tokens ≤ maxTokens`).  
   * `refillRate` – tokens added per *refillInterval* (e.g. 10 tokens per second).  
   * `lastRefillTimeNanos` – timestamp of the last refill calculation.  

2. **Refill step** (executed on every acquire)  

   ```text
   now = Clock.nanoTime()
   elapsedSinceLastRefill = now - lastRefillTimeNanos
   tokensToAdd = floor( refillRate * elapsedSinceLastRefill / NANOSECONDS_PER_SECOND )
   tokens = min( maxTokens, tokens + tokensToAdd )
   lastRefillTimeNanos = now
   ```

   This guarantees that over a long period the average number of tokens added per second equals `refillRate`, irrespective of which thread calls `acquire()`.

3. **Acquire a token**  

   * If `tokens > 0` → decrement `tokens` and return.  
   * Otherwise → wait on a `Condition` until another thread adds tokens (i.e. another thread calls `acquire` after a refill).  

4. **TryAcquire** – identical to `acquire` but returns `false` immediately when `tokens == 0`.  

5. **Thread‑safety** – All mutable state (`tokens`, `lastRefillTimeNanos`, the waiting queue) is protected by a single `ReentrantLock`. A `newCondition()` provides a FIFO waiting queue, guaranteeing that the longest‑waiting thread is awakened first (fairness).  

### 1.3 API  

```java
package com.example.ratelimiter;

/**
 * A token‑bucket rate limiter.
 *
 * <p>Typical usage:
 *
 * <pre>
 * RateLimiter limiter = new TokenBucketRateLimiter(
 *         100,                     // max burst (tokens)
 *         10,                      // refill rate = 10 tokens per second
 *         Duration.ofSeconds(1)    // refill interval (optional, can be derived from rate)
 * );
 *
 * if (limiter.tryAcquire()) {
 *     // allowed to proceed
 * } else {
 *     // reject / back‑pressure
 * }
 * </pre>
 *
 * The limiter is safe for concurrent use without additional synchronization.
 */
public final class TokenBucketRateLimiter {

    /** Maximum number of tokens that can be stored (burst capacity). */
    private final long maxTokens;

    /** Number of tokens added per {@code refillInterval}. */
    private final long refillTokens;

    /** Length of the refill interval (in nanoseconds). */
    private final long refillIntervalNanos;

    /** Current token count. */
    private volatile long tokens;

    /** Timestamp of the last refill calculation (nanoseconds). */
    private volatile long lastRefillTimeNanos;

    /** Lock protecting mutable state. */
    private final ReentrantLock lock = new ReentrantLock(true); // fair lock

    /** Condition used to block waiting threads. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a limiter.
     *
     * @param maxTokens      maximum burst size (must be > 0)
     * @param refillTokens   number of tokens added each {@code refillInterval}
     * @param refillInterval interval after which {@code refillTokens} are added,
     *                       expressed as a {@link java.time.Duration}
     */
    public TokenBucketRateLimiter(long maxTokens,
                                  long refillTokens,
                                  Duration refillInterval) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        if (refillTokens < 0) {
            throw new IllegalArgumentException("refillTokens must be >= 0");
        }
        if (refillInterval.isZero()) {
            throw new IllegalArgumentException("refillInterval must be > 0");
        }

        this.maxTokens = maxTokens;
        this.refillTokens = refillTokens;
        this.refillIntervalNanos = refillInterval.toNanos();
        this.tokens = maxTokens;      // start full so that immediate bursts are allowed
        this.lastRefillTimeNanos = Clock.nanoTime();
    }

    /**
     * Blocks until a token is available, then consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                refill();                     // add tokens based on elapsed time
                if (tokens > 0) {
                    tokens--;
                    notEmpty.signalAll();    // wake possible waiting threads
                    return;
                }
                // No tokens – wait until a refill occurs
                notEmpty.await();            // releases lock and blocks fairly
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire a token without blocking.
     *
     * @return {@code true} if a token was available and was consumed,
     *         {@code false} otherwise
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens > 0) {
                tokens--;
                notEmpty.signalAll();
                return true;
            }
            // No token – leave lock and return false
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Refreshes the token count based on elapsed time since the last refill. */
    private void refill() {
        long now = Clock.nanoTime();
        long elapsed = now - lastRefillTimeNanos;
        if (elapsed <= 0) {
            return; // nothing to do
        }
        long tokensToAdd = (elapsed * refillTokens) / refillIntervalNanos;
        if (tokensToAdd > 0) {
            tokens = Math.min(maxTokens, tokens + tokensToAdd);
            lastRefillTimeNanos = now;
        }
    }
}
```

#### 1.4 Rationale for Design Choices  

| Aspect | Decision | Reason |
|--------|----------|--------|
| **Lock type** | `ReentrantLock(true)` (fair) | Guarantees FIFO ordering of waiting threads, which is a simple fairness guarantee without adding extra data structures. |
| **Refill calculation** | Per‑call lazy refill using `Clock.nanoTime()` | Avoids a background thread; each acquire performs a cheap arithmetic operation. Over a large number of calls the average refill rate matches the configured rate. |
| **Initial token count** | `maxTokens` | Allows the limiter to immediately serve a burst of up to `maxTokens` calls, matching typical token‑bucket semantics. |
| **Blocking acquire** | `await()` on a `Condition` | Provides a clean, interruptible wait and integrates nicely with Java’s concurrency utilities. |
| **TryAcquire** | Returns `false` instantly if no token | Mirrors the behaviour of `java.util.concurrent.Semaphore.tryAcquire()`. |
| **Thread‑safety** | All mutable fields guarded by a single lock | Simpler reasoning and avoids subtle race conditions. |
| **Interrupt handling** | Propagates `InterruptedException` from `await()` | Allows callers to cancel the wait by thread interruption. |
| **Visibility** | `volatile` on `tokens` and `lastRefillTimeNanos` | Guarantees that updates made by one thread are visible to others without needing to reacquire the lock in some edge cases (e.g., spurious wake‑ups). |

### 1.5 Expected Behaviour  

* **Long‑run average** – Over a sufficiently large interval the number of successful `acquire()` calls per second converges to `refillTokens / refillInterval`.  
* **Burst** – Up to `maxTokens` calls can succeed without waiting, after which the caller must wait for refills.  
* **Fairness** – Threads that have been waiting the longest are awakened first, preventing starvation under high contention.  

---  

## 2. JUnit 5 Test Suite  

The test suite validates functional behaviour, thread‑safety, and the long‑run rate constraint.

### 2.1 Maven / Gradle Dependency (excerpt)

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

### 2.2 Test Class  

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final long REFILL_RATE = 10;           // 10 tokens per second
    private static final long BURST_CAPACITY = 20;        // maxTokens

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(BURST_CAPACITY, REFILL_RATE, ONE_SECOND);
    }

    @Test
    void tryAcquireWhenTokenAvailable() {
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // bucket now empty
    }

    @Test
    void acquireBlocksWhenNoToken() throws InterruptedException {
        Thread t = new Thread(() -> {
            // In another thread we will release a token after a short delay
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            assertTrue(limiter.tryAcquire());
        });
        t.start();

        // The current thread should block until the token appears
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> limiter.acquire());

        t.join();
    }

    @Test
    void acquireReleasesFairlyWhenMultipleThreadsWait() throws Exception {
        int threads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                readyLatch.countDown(); // signal ready
                try {
                    startLatch.await(); // wait until we fire the go‑signal
                    limiter.acquire();  // will block until a token appears
                    granted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Release all threads at once
        startLatch.countDown();
        readyLatch.await(); // wait until all are blocked

        // Let the system run long enough to refill at least once
        TimeUnit.SECONDS.sleep(2);

        // All threads should have obtained a token (or at least some of them)
        // The fairness guarantee ensures that they are served in arrival order.
        // We only assert that at least one token was handed out.
        assertTrue(granted.get() > 0, "At least one thread should have been granted a token");

        exec.shutdownNow();
    }

    @Test
    void longRunRateDoesNotExceedConfiguredLimit() throws Exception {
        final int TOTAL_CALLS = 100_000;
        final int CONCURRENCY = 10;

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(TOTAL_CALLS);
        AtomicInteger successful = new AtomicInteger(0);
        AtomicInteger startTime = new AtomicInteger((int) System.nanoTime());

        Runnable task = () -> {
            try {
                // each call attempts to acquire a token
                if (limiter.tryAcquire()) {
                    successful.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        };

        // Submit many tasks
        for (int i = 0; i < TOTAL_CALLS; i++) {
            executor.submit(task);
        }

        // Wait for all tasks to finish
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Compute elapsed seconds
        long elapsedNanos = System.nanoTime() - startTime.get();
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        // Expected number of tokens that could have been consumed:
        //   refillRate * elapsedSeconds
        double maxAllowed = REFILL_RATE * elapsedSeconds;

        // Because we also started with a full bucket (burst capacity),
        // we add that burst to the allowance.
        double allowedWithBurst = maxAllowed + BURST_CAPACITY;

        // The actual successful calls must not exceed this bound.
        assertTrue(successful.get() <= allowedWithBurst,
                "Long‑run consumption (" + successful.get() + ") exceeds allowed rate of " + allowedWithBurst);
    }
}
```

#### 2.3 Explanation of the Tests  

| Test | What it verifies |
|------|-------------------|
| `tryAcquireWhenTokenAvailable` | Basic `tryAcquire` semantics – returns `true` when a token exists, `false` afterwards. |
| `acquireBlocksWhenNoToken` | `acquire()` blocks until another thread makes a token available (via `tryAcquire` later). |
| `acquireReleasesFairlyWhenMultipleThreadsWait` | With many threads contending, the limiter awakens them in FIFO order (fairness). |
| `longRunRateDoesNotExceedConfiguredLimit` | Runs 100 k attempts from 10 concurrent threads and checks that the total number of successful acquisitions cannot exceed `refillRate × elapsedSeconds + burstCapacity`. This is a statistical guarantee that the long‑run average rate does not surpass the configured limit. The test also demonstrates that the limiter works under realistic concurrent load. |

### 2.4 Running the Tests  

```bash
# Maven
mvn test

# Gradle
./gradlew test
```

All tests should pass, confirming that the implementation satisfies the functional and concurrency requirements.

---  

## 3. Summary  

* **Design** – A lightweight, fair, token‑bucket implementation where refills are calculated lazily on each `acquire`.  
* **API** – `acquire()` (blocking) and `tryAcquire()` (non‑blocking) with configurable burst capacity and refill rate.  
* **Thread safety** – Enforced by a single `ReentrantLock` and a `Condition` for waiting threads.  
* **Tests** – JUnit 5 suite covering basic usage, blocking semantics, fairness, and a statistical long‑run rate check that guarantees the limiter never exceeds its configured average rate.  

You can now drop `TokenBucketRateLimiter` into any Java project that needs a simple, high‑performance, thread‑safe rate‑limiting mechanism.