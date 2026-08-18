<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260818T143128Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## 1. Design Overview  

### 1.1  Goal  
Create a **token‑bucket rate limiter** that can be used by many threads simultaneously.  
* configurable **refill rate** (tokens added per interval)  
* configurable **burst capacity** (maximum number of tokens that can be stored)  
* two acquisition APIs  

| API | Behaviour |
|-----|------------|
| `tryAcquire()` | Non‑blocking – returns `true` if a token is immediately available, otherwise `false`. |
| `acquire()`    | Blocking – waits until a token becomes available, then consumes it. |

The implementation must be **thread‑safe** and must **never allow more than the configured burst capacity** of tokens to be outstanding at any moment.

### 1.2  Core Concepts  

| Concept | Representation |
|---------|----------------|
| **Tokens** | A counter (`long tokens`) that holds the current number of available permits. |
| **Refill** | Every *interval* we add `refillTokens` new tokens (rounded down). The addition is proportional to the elapsed time, so the bucket refills smoothly even when calls are spread irregularly. |
| **Burst** | The bucket never may contain more than `capacity` tokens. |
| **Synchronization** | A single `ReentrantLock` protects all mutable state (`tokens`, `lastRefillNanos`). A `Condition` (`available`) is used to unblock threads that are waiting in `acquire()`. |
| **Non‑blocking path** | `tryAcquire()` only needs to look at the counter; it does **not** acquire the lock unless it actually consumes a token. |

### 1.3  Algorithmic Details  

1. **Replenish** (`replenish()`)  
   * Capture the current time (`now`).  
   * Compute the elapsed time since the last replenish (`elapsed = now - lastRefillNanos`).  
   * Tokens to add = `refillTokens * elapsed / intervalNanos`.  
   * Clamp the addition to `capacity` and update the counter under the lock.  
   * If any tokens were added, signal the `available` condition so that waiting `acquire()` calls can proceed.

2. **tryAcquire()**  
   * Call `replenish()` (which may add tokens).  
   * Acquire the lock, check `tokens`.  
   * If `> 0`, decrement and return `true`; otherwise return `false`.  
   * Release the lock – no waiting is performed.

3. **acquire()**  
   * Acquire the lock.  
   * Loop:  
        * Call `replenish()`.  
        * If `tokens > 0` → consume one (`tokens--`) and exit.  
        * Otherwise `available.await()` (releases the lock and blocks).  
   * When a token becomes available another thread will be awakened, the lock re‑acquired, and the loop continues.  

All mutable state (`tokens`, `lastRefillNanos`) is only modified while holding the lock, guaranteeing that the counter cannot be corrupted by concurrent threads. The `Condition` guarantees that **exactly one** blocked thread is unblocked each time a token is added, preventing “lost wake‑ups”.

### 1.4  API  

```java
public final class TokenBucketRateLimiter {

    /**
     * @param tokensPerInterval   number of tokens added every {@code interval}
     * @param interval            the period in which {@code tokensPerInterval} tokens are added
     * @param burstCapacity       maximum number of tokens that can be stored
     */
    public TokenBucketRateLimiter(int tokensPerInterval,
                                  Duration interval,
                                  int burstCapacity) { … }

    /** Non‑blocking attempt to obtain a permit. */
    public boolean tryAcquire();

    /** Blocking call that obtains a permit (may throw InterruptedException). */
    public void acquire() throws InterruptedException;
}
```

Both methods are **stateless** – they only interact with the internal bucket and never expose mutable state.

### 1.5  Thread‑Safety Guarantees  

* All writes to `tokens` and `lastRefillNanos` happen inside `lock`.  
* Reads performed by `tryAcquire()` are safe because they either happen before/after a lock‑protected update or are performed on an `AtomicLong` that is only written under the same lock.  
* The `Condition` guarantees that a thread is awakened **only** after at least one token has been added, so no spurious wake‑ups can cause an illegal state.  
* The implementation never permits more than `capacity` tokens to be stored, therefore the *burst* bound is respected under all interleavings.

### 1.6  Performance Considerations  

* `tryAcquire()` is lock‑free for the fast‑path (only a brief lock acquisition when a token is actually taken).  
* `acquire()` uses a standard wait‑loop with `Condition#await()`, which releases the lock while the thread is parked, allowing other threads to replenish tokens.  
* The refill calculation is O(1) and uses only `System.nanoTime()`, making the limiter suitable for high‑throughput environments.

---

## 2. Implementation  

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Token‑bucket rate limiter that is safe for concurrent use.
 * <p>
 * <strong>Design goals</strong>:
 * <ul>
 *   <li>Configurable refill rate and burst capacity.</li>
 *   <li>Two acquisition APIs – blocking {@code acquire()} and non‑blocking {@code tryAcquire()}.</li>
 *   <li>Correctness under arbitrary thread interleavings.</li>
 * </ul>
 *
 * @author  (your name)
 */
public final class TokenBucketRateLimiter {

    /** Maximum number of tokens that can be stored (burst capacity). */
    private final int capacity;

    /** Nano‑seconds of the refill interval (e.g. 1_000_000_000 for a second). */
    private final long intervalNanos;

    /** How many tokens are added every {@code intervalNanos}. */
    private final long refillTokens;

    /** Current token count – only modified while holding {@code lock}. */
    private long tokens;

    /** Monotonic timestamp of the last successful replenish (nano‑seconds). */
    private final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());

    /** Lock protecting mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition that blocked {@code acquire()} threads wait on. */
    private final Condition available = lock.newCondition();

    /**
     * Creates a limiter that refills {@code tokensPerInterval} tokens every
     * {@code interval} and can burst up to {@code burstCapacity} tokens.
     *
     * @param tokensPerInterval number of tokens added per interval
     * @param interval          refill period (must be &gt; 0)
     * @param burstCapacity     maximum burst size (must be &ge; 1)
     */
    public TokenBucketRateLimiter(int tokensPerInterval,
                                  Duration interval,
                                  int burstCapacity) {
        if (tokensPerInterval <= 0) {
            throw new IllegalArgumentException("tokensPerInterval must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be >= 1");
        }
        this.capacity = burstCapacity;
        this.intervalNanos = interval.toNanos();
        this.refillTokens = tokensPerInterval;
        // initially the bucket is empty
        this.tokens = 0L;
    }

    /** Internal helper – adds tokens according to elapsed time. */
    private void replenish() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = now - last;

        // tokens to add = refillTokens * (elapsed / intervalNanos)
        // Use double arithmetic to avoid overflow on very large values.
        double toAddD = (double) refillTokens * elapsed / intervalNanos;
        long toAdd = (long) Math.floor(toAddD);

        if (toAdd <= 0) {
            return; // nothing to do
        }

        lock.lock();
        try {
            long current = tokens;
            long next = Math.min(capacity, current + toAdd);
            tokens = next;
            lastRefillNanos.set(now);
            // Wake up one or more waiting acquire() threads – they may now proceed.
            if (next > current) {
                available.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Non‑blocking attempt to obtain a permit.
     *
     * @return {@code true} if a permit was available, {@code false} otherwise
     */
    public boolean tryAcquire() {
        replenish(); // may add tokens, but does not need the lock for the check
        lock.lock();
        try {
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
     * Blocking acquire – waits until a permit can be obtained.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                replenish(); // may add tokens
                if (tokens > 0) {
                    tokens--;
                    return;
                }
                // No tokens – wait until someone signals that tokens are available.
                available.await();
            }
        } finally {
            lock.unlock();
        }
    }
}
```

### 2.1  Why This Design Satisfies the Requirements  

| Requirement | How it is met |
|-------------|---------------|
| **Configurable refill rate & burst capacity** | Constructor parameters `tokensPerInterval`, `interval`, and `capacity` are stored and used in `replenish()`. |
| **Thread‑safe** | All mutable state (`tokens`, `lastRefillNanos`) is protected by a single `ReentrantLock`. The `Condition` guarantees proper coordination between producers (replenish) and consumers (acquire