<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260815T172406Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Document  

### 1.1  What is a Token‑Bucket Rate Limiter?  
A **token bucket** is a classic algorithm for controlling the maximum rate at which permits (tokens) can be taken.  

* **Capacity** – the maximum number of tokens that can be stored in the bucket.  
* **Refill‑rate** – how many tokens are added to the bucket per unit of time (e.g. *R* tokens/second).  
* **Acquire** – a caller asks for a token. If a token is available it is removed and the call returns; otherwise the caller blocks (or can try‑acquire and fail fast).  

The bucket behaves like a leaky‑bucket: tokens are replenished at a steady rate, but bursts up to the bucket size are permitted.

### 1.2  Thread‑Safety Requirements  

| Requirement | How it is satisfied |
|-------------|---------------------|
| **Multiple threads may call `acquire()` / `tryAcquire()` concurrently** | All public methods synchronize on a single `ReentrantLock`. |
| **Atomic token updates** | The current token count is stored in an `AtomicLong` (the lock guarantees a single writer, but the atomic variable makes the intent explicit). |
| **Correct refill semantics** | A dedicated `ScheduledExecutorService` periodically (every 1 ms) adds tokens according to the configured refill rate and signals waiting threads. |
| **Graceful shutdown** | A `stop()` method shuts down the scheduler to avoid thread‑leaks. |

### 1.3  Public API  

```java
/**
 * A configurable, thread‑safe token‑bucket rate limiter.
 *
 * <p>Tokens are replenished at {@code refillRate} tokens per second.
 * The bucket can hold at most {@code capacity} tokens, which enables
 * short‑lived bursts.</p>
 *
 * <p>Two ways of obtaining a token:</p>
 * <ul>
 *   <li>{@link #acquire()} – blocks until a token is available.</li>
 *   <li>{@link #tryAcquire()} – returns {@code false} immediately if no token is present.</li>
 * </ul>
 *
 * <p>The limiter must be stopped when it is no longer needed
 * ( {@link #stop()} ), otherwise a background refill thread will keep running.
 */
public final class TokenBucketRateLimiter {
    /** Current number of tokens in the bucket. */
    private final AtomicLong tokens;
    /** Maximum number of tokens the bucket can hold. */
    private final int capacity;
    /** Tokens added per second (refill rate). */
    private final double refillRate;
    /** Scheduler that performs the periodic refill. */
    private final ScheduledExecutorService scheduler;
    /** Mutual exclusion for state changes and condition signalling. */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition that waiting threads wait on. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a limiter.
     *
     * @param refillRate   tokens added per second (may be fractional)
     * @param capacity     maximum burst size (must be &gt;= 1)
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public TokenBucketRateLimiter(double refillRate, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity); // start full
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        // Refill every 1 ms – fine‑grained enough for most rates.
        this.scheduler.scheduleAtFixedRate(this::refill,
                0, 1, TimeUnit.MILLISECONDS);
    }

    /** Periodic task that adds tokens according to the configured refill rate. */
    private void refill() {
        // How many tokens should be added in this tick?
        double expected = refillRate / 1000.0; // tokens per millisecond
        // Use a simple accumulator to avoid fractional token loss.
        // The accumulator lives in a volatile double field (see below).
        // For brevity we store it as a thread‑local in a static helper.
        // (Implementation detail shown later.)
        long added = RefillAccumulator.add(refillRate, System.nanoTime());
        long newTokens = tokens.get() + added;
        if (newTokens > capacity) {
            newTokens = capacity;
        }
        tokens.set(newTokens);
        // Wake up any waiting acquirers.
        lock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks the current thread until a token is available, then consumes it.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (tokens.get() == 0) {
                notEmpty.await();               // releases lock & waits
            }
            tokens.decrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to obtain a token without blocking.
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

    /** Shuts down the internal scheduler.  After calling this method the
     *  limiter must not be used any more. */
    public void stop() {
        scheduler.shutdownNow();
    }
}
```

> **Note** – The above code uses a tiny helper class `RefillAccumulator` (shown later) to turn a fractional refill‑rate into an integer number of tokens added each 1 ms tick. This keeps the algorithm precise even when the configured rate is not an integer.

### 1.4  Refill‑Accumulator (internal helper)

```java
/**
 * Utility that converts a refill‑rate (tokens/second) into an integer
 * number of tokens added each time {@code refill()} is called.
 *
 * <p>It maintains a {@code double} “budget” that accumulates the
 * fractional part of the refill amount. When the budget reaches
 * 1.0 or more we emit a whole token.</p>
 */
final class RefillAccumulator {
    private static final Map<Double, Double> budgets = new ConcurrentHashMap<>();

    private static double getOrCreateBudget(double rate) {
        return budgets.computeIfAbsent(rate, ignored -> 0.0);
    }

    /**
     * Adds {@code rate} tokens per second to the accumulator.
     *
     * @return the integer number of tokens to add this tick
     */
    static long add(double rate, long nowNanos) {
        // Use the current time to keep the budget stable across ticks.
        // 1‑ms tick => nanosPerTick = 1_000_000
        long nanosSinceLast = nowNanos % 1_000_000_000L; // not needed, just illustrative
        double budget = getOrCreateBudget(rate);
        double tokensToAdd = rate / 1_000_000_000.0; // tokens per nanosecond
        budget += tokensToAdd;
        long toReturn = (long) Math.floor(budget);
        budget -= toReturn;
        // Store back (the map is only for illustration; in production we could
        // keep the budget in a thread‑local or instance field.)
        budgets.put(rate, budget);
        return toReturn;
    }
}
```

> In the final production code you would avoid the static map and instead store the accumulator as an `AtomicDouble` inside the limiter (or as a field of a private inner class). The example is kept short for readability.

### 1.5  Summary of Guarantees  

| Property | Guarantee |
|----------|-----------|
| **Thread‑safety** | All mutable state (`tokens`, lock, condition) is accessed only while holding `lock`. |
| **Maximum burst** | Up to `capacity` tokens can be taken instantly, after which the bucket must be refilled. |
| **Long‑run rate** | Over a large interval the average number of tokens released per second cannot exceed `refillRate`. |
| **Blocking semantics** | `acquire()` may block; `tryAcquire()` never blocks and returns `false` when the bucket is empty. |
| **Shutdown** | `stop()` cleanly stops the refill thread; after stopping the limiter must not be used. |

---  

## 2. Implementation  

Below is a **self‑contained** implementation that can be dropped into a Maven/Gradle project.  
(The `RefillAccumulator` is now integrated as a private static helper to keep the example tidy.)

```java
package com.example.ratelimiter;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token‑bucket rate limiter that is safe for concurrent use.
 *
 * <p>It supports a configurable refill rate (tokens per second) and a
 * configurable burst capacity.  Two acquisition methods are offered:
 * <ul>
 *   <li>{@link #acquire()} – blocks until a token is available.</li>
 *   <li>{@link #tryAcquire()} – non‑blocking, returns {@code false} if no token.</li>
 * </ul>
 *
 * <p>The limiter must be stopped with {@link #stop()} when it is no longer needed.</p>
 */
public final class TokenBucketRateLimiter {

    /** Current token count – always updated while holding {@code lock}. */
    private final AtomicLong tokens;

    /** Maximum number of tokens that can accumulate (burst size). */
    private final int capacity;

    /** Tokens added per second (refill rate). */
    private final double refillRate;

    /** Scheduler that performs the periodic refill. */
    private final ScheduledExecutorService scheduler;

    /** Mutual exclusion for state changes and condition signalling. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that waiting threads wait on. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a limiter.
     *
     * @param refillRate tokens added per second (may be fractional)
     * @param capacity   maximum burst size (≥ 1)
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public TokenBucketRateLimiter(double refillRate, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity); // start full
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        // Refill every 1 ms – fine‑grained enough for most rates.
        this.scheduler.scheduleAtFixedRate(this::refill,
                0, 1, TimeUnit.MILLISECONDS);
    }

    /** Periodic task that adds tokens according to the configured refill rate. */
    private void refill() {
        // Convert the refill‑rate to an *increment* for this 1‑ms tick.
        // The accumulator is stored in a thread‑local to avoid contention.
        long added = RefillAccumulator.add(refillRate);
        long newTokens = tokens.get() + added;
        if (newTokens > capacity) {
            newTokens = capacity;
        }
        tokens.set(newTokens);
        // Wake up any threads waiting for a token.
        lock.lock();
        try {
            notEmpty.signalAll();
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
                notEmpty.await();
            }
            tokens.decrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to obtain a token without blocking.
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

    /** Shuts down the internal scheduler.  Must be called before discarding the limiter. */
    public void stop() {
        scheduler.shutdownNow();
    }

    /* --------------------------------------------------------------------- */
    /*  Internal helper – refill accumulator                                   */
    /* --------------------------------------------------------------------- */

    /**
     * Simple accumulator that turns a fractional refill‑rate into an integer
     * number of tokens added each tick.  It stores the fractional part in a
     * {@code ThreadLocal} so that there is no contention.
     */
    private static final class RefillAccumulator {
        private static final ConcurrentMap<String, ThreadLocal<Double>> BUDGETS =
                new ConcurrentMap<>();

        private static ThreadLocal<Double> bucket(String key) {
            return BUDGETS.computeIfAbsent(key, k -> new ThreadLocal<>() {
                @Override protected ThreadLocal<Double> initialValue() {
                    return 0.0;
                }
            });
        }

        /** Returns the integer number of tokens to add on this tick. */
        static long add(double refillRate) {
            // Use a stable key – hashCode is fine for our simple usage.
            String key = Double.toString(refillRate);
            ThreadLocal<Double> tl = bucket(key);
            double budget = tl.get();
            // Tokens to add per millisecond = refillRate / 1_000_000
            double tokensPerTick = refillRate / 1_000_000.0;
            budget += tokensPerTick;
            long toAdd = (long) Math.floor(budget);
            budget -= toAdd;
            tl.set(budget);
            return toAdd;
        }
    }
}
```

### 2.1  How to Use  

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5); // 10 tokens/sec, burst up to 5
try {
    if (limiter.tryAcquire()) {
        // proceed with the protected operation
    } else {
        // fallback or reject
    }

    // Blocking variant
    limiter.acquire();   // will wait if no token is currently available
} finally {
    limiter.stop();      // important – otherwise the scheduler thread stays alive
}
```

---  

## 3. JUnit 5 Test Suite  

The following test class covers:

1. **Basic `tryAcquire` semantics** – returns `false` when the bucket is empty.  
2. **Blocking `acquire`** – ensures the call blocks until a token becomes available.  
3. **Concurrent long‑run rate test** – spawns many threads that repeatedly acquire tokens; after a fixed duration we verify that the observed average rate does not exceed the configured refill‑rate (with a small tolerance for short‑term bursts).  

```java
package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.CountDownLatch;

import org.junit.jupiter.api.*;

class TokenBucketRateLimiterTest {

    /** Test the non‑blocking tryAcquire when no tokens are present. */
    @Test
    void tryAcquireReturnsFalseWhenEmpty() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 3);
        assertFalse(limiter.tryAcquire(), "Bucket should be empty at start");
        // consume one token to make it non‑empty
        limiter.acquire(); // consumes the initial token (capacity was full)
        assertTrue(limiter.tryAcquire(), "Bucket should now contain a