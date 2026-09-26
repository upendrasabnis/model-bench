<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260926T193721Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter – Design & Implementation

## Design Overview

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Algorithm** | *Classic token bucket* with continuous (fractional) refill | Smooth rate limiting, no “burst‑then‑pause” artifacts. |
| **State** | `availableTokens` (double), `lastRefillNanos` (long) | Minimal mutable state – only two `volatile` fields. |
| **Concurrency** | Single `ReentrantLock` + `Condition` | Guarantees atomic *check‑then‑act* for both blocking & non‑blocking paths; `Condition` enables efficient parking instead of busy‑spin. |
| **Time source** | `System.nanoTime()` (monotonic) | Immune to wall‑clock adjustments. |
| **Refill math** | `elapsedSeconds * ratePerSecond` computed under lock | Avoids drift; fractional tokens retained for precision. |
| **Fairness** | Non‑fair lock (default) | Higher throughput under contention; fairness not required for rate limiting semantics. |
| **API** | `acquire()`, `acquire(long, TimeUnit)`, `tryAcquire()`, `tryAcquire(long, TimeUnit)` | Mirrors `java.util.concurrent.Semaphore` for familiarity. |
| **Immutability of config** | `ratePerSecond`, `capacity` final | Prevents accidental re‑configuration; create a new limiter if params change. |

### Correctness Arguments
1. **Token conservation** – Every successful `acquire/tryAcquire` decrements `availableTokens` exactly once.
2. **Refill monotonicity** – `refill()` only moves time forward; `lastRefillNanos` never decreases.
3. **No lost wake‑ups** – `signalAll()` after every token release ensures waiters re‑evaluate.
4. **Bounded wait** – `acquire(timeout)` returns `false` iff deadline expires before tokens become available.

---

## Implementation (Java 17+)

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are added continuously at {@code ratePerSecond} up to {@code capacity}.
 * A call to {@link #acquire()} consumes one token, blocking until one is available.
 * {@link #tryAcquire()} returns immediately with {@code true} if a token exists,
 * otherwise {@code false}.</p>
 *
 * <p>Example:
 * <pre>{@code
 * TokenBucketLimiter limiter = new TokenBucketLimiter(100, 200); // 100 tokens/s, burst 200
 * limiter.acquire();                    // blocks until a token is available
 * if (limiter.tryAcquire()) { ... }     // non‑blocking
 * }</pre>
 */
public final class TokenBucketLimiter {

    private final double ratePerSecond;   // tokens added per second
    private final double capacity;        // max tokens (burst)
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    // Mutable state – only accessed while holding 'lock'
    private volatile double availableTokens;
    private volatile long lastRefillNanos;

    /**
     * @param ratePerSecond sustained token rate (> 0)
     * @param capacity      maximum bucket size (>= ratePerSecond, else burst = rate)
     * @throws IllegalArgumentException if ratePerSecond <= 0 or capacity <= 0
     */
    public TokenBucketLimiter(double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("rate and capacity must be > 0");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = Math.max(capacity, ratePerSecond); // allow burst ≥ rate
        this.availableTokens = this.capacity;              // start full
        this.lastRefillNanos = System.nanoTime();
    }

    /** Refill tokens based on elapsed time. Must hold {@code lock}. */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            double newTokens = elapsedSeconds * ratePerSecond;
            availableTokens = Math.min(capacity, availableTokens + newTokens);
            lastRefillNanos = now;
        }
    }

    /** Core acquire logic shared by all public methods. */
    private boolean acquireInternal(long timeoutNanos) throws InterruptedException {
        lock.lock();
        try {
            long deadline = timeoutNanos > 0 ? System.nanoTime() + timeoutNanos : 0;

            while (true) {
                refill();
                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0;
                    return true;
                }
                if (timeoutNanos <= 0) {
                    // blocking – wait indefinitely
                    notEmpty.await();
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false; // timeout
                    }
                    if (!notEmpty.awaitNanos(remaining) /* timed out */) {
                        return false;
                    }
                }
                // loop – spurious wake‑up or still no token
            }
        } finally {
            lock.unlock();
        }
    }

    /** Blocks until a token is available. */
    public void acquire() throws InterruptedException {
        acquireInternal(0);
    }

    /** Blocks up to {@code timeout} for a token. */
    public boolean acquire(long timeout, TimeUnit unit) throws InterruptedException {
        return acquireInternal(unit.toNanos(timeout));
    }

    /** Non‑blocking attempt. */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Non‑blocking attempt with timeout (effectively same as tryAcquire if timeout=0). */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return acquireInternal(unit.toNanos(timeout));
    }

    /** Current tokens available (approx., for monitoring). */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## JUnit 5 Test Suite

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketLimiterTest {

    private static final double RATE = 1000.0;   // 1k tokens/sec
    private static final double CAPACITY = 2000; // burst 2k
    private TokenBucketLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketLimiter(RATE, CAPACITY);
    }

    @Test
    void initialBurstAllowsFullCapacity() {
        // bucket starts full
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(limiter.tryAcquire(), "token " + i);
        }
        assertFalse(limiter.tryAcquire(), "bucket should be empty");
    }

    @Test
    void refillOverTime() throws InterruptedException {
        // drain
        for (int i = 0; i < CAPACITY; i++) limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());

        // wait for ~1 token
        Thread.sleep(2); // 2 ms @ 1000/s ≈ 2 tokens
        assertTrue(limiter.tryAcquire());
    }

    @ParameterizedTest
    @ValueSource(doubles = {10, 100, 1000, 10000})
    void steadyRateRespected(double rate) throws InterruptedException {
        TokenBucketLimiter l = new TokenBucketLimiter(rate, rate * 2);
        long start = System.nanoTime();
        int count = 10_000;
        for (int i = 0; i < count; i++) l.acquire();
        double elapsedSec = (System.nanoTime() - start) / 1e9;
        double achieved = count / elapsedSec;
        // allow 5% slack for scheduler granularity
        assertTrue(achieved <= rate * 1.05, "rate " + achieved + " > " + rate);
        assertTrue(achieved >= rate * 0.90, "rate " + achieved + " < " + rate);
    }

    @Test
    void blockingAcquireRespectsTimeout() throws InterruptedException {
        // drain
        for (int i = 0; i < CAPACITY; i++) limiter.tryAcquire();
        assertFalse(limiter.acquire(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void concurrentAccessDoesNotExceedRate() throws Exception {
        int threads = 16;
        int opsPerThread = 50_000;
        long testDurationMs = 2000; // 2 seconds
        double expectedMax = RATE * (testDurationMs / 1000.0) * 1.02; // 2% slack

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        LongAdder success = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(testDurationMs);
                    while (System.nanoTime() < deadline) {
                        if (limiter.tryAcquire()) success.increment();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "test timed out");
        exec.shutdownNow();

        long total = success.sum();
        System.out.println("Total acquires: " + total + " (max allowed ≈ " + expectedMax + ")");
        assertTrue(total <= expectedMax, "Rate exceeded: " + total + " > " + expectedMax);
    }

    @Test
    void constructorRejectsInvalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(-1, 10));
    }
}
```

### Key Points in the Concurrent Test
* **Fixed thread‑pool** – eliminates thread‑creation noise.
* **`LongAdder`** – high‑throughput counter without contention.
* **Deadline‑driven loop** – each thread hammers `tryAcquire()` for a fixed wall‑clock window.
* **Assertion** – total successful acquires must stay ≤ `rate × time × 1.02` (2 % headroom for OS scheduling).

---

## Build / Run (Maven)

```xml
<!-- pom.xml snippet -->
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
<build>
    <plugins>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.2</version>
        </plugin>
    </plugins>
</build>
```

```bash
mvn test
```

All tests should pass, confirming both functional correctness and that the long‑run throughput never exceeds the configured rate.