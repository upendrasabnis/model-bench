<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260827T160153Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
### Design Documentation: `TokenBucketRateLimiter`

#### 1. Algorithm Overview
The **Token Bucket** algorithm controls the rate of consumption by maintaining a bucket of tokens.
*   **Capacity (Burst):** Maximum tokens the bucket can hold (`maxTokens`). Allows short bursts of traffic up to this limit.
*   **Refill Rate:** Tokens added per second (`tokensPerSecond`). Defines the long-term average rate limit.
*   **Consumption:** A request consumes $N$ tokens (usually 1). If insufficient tokens exist, the request is either rejected (`tryAcquire`) or queued/waited (`acquire`).

#### 2. Concurrency Strategy: Lock-Free CAS Loop
To achieve high throughput under contention without `synchronized` blocks or `ReentrantLock` (which cause kernel context switches), we use a **Compare-And-Swap (CAS)** loop on a single `AtomicLong` state.

**State Encoding (64-bit `long`):**
| Bits | Field | Description |
| :--- | :--- | :--- |
| 0–31 (Low 32 bits) | `tokens` | Current available tokens $\times$ `PRECISION` (Fixed-point integer). |
| 32–63 (High 32 bits) | `lastRefillNanos` | Timestamp (nanoseconds) of the last successful refill calculation. |

**Why Fixed Point (Precision = 1000)?**
Floating-point math is non-deterministic across architectures and slower for CAS retries. We store tokens as `long` representing `tokens * 1000`. Refill rate is pre-calculated as `tokensPerSecond * 1000 / 1_000_000_000` (tokens-per-nano $\times$ precision).

**Refill Logic (Lazy, On-Demand):**
Refill happens *only* when a thread attempts acquisition (`tryAcquire` or `acquire`).
1.  Read current `state`.
2.  Decode `tokens` and `lastRefillNanos`.
3.  Calculate `elapsedNanos = now - lastRefillNanos`.
4.  Calculate `newTokens = min(maxTokens, tokens + elapsedNanos * refillRatePerNano)`.
5.  Attempt CAS `state` from `old` to `new` (updated tokens + `now` timestamp).
6.  **Retry on CAS failure** (another thread updated state).

#### 3. Blocking `acquire()` Implementation
Uses `LockSupport.parkNanos` for efficient waiting.
1.  Calculate `deficit` (tokens needed - tokens available).
2.  Calculate `waitTimeNanos = deficit / refillRatePerNano`.
3.  Cap wait time at `maxWaitNanos` (time to fill empty bucket) to handle clock drift/spurious wakeups.
4.  `LockSupport.parkNanos(waitTimeNanos)`.
5.  Loop and retry CAS acquisition.

#### 4. Fairness & Starvation
This is a **non-fair** limiter. Threads compete via CAS. Under high contention, a thread might retry many times. This favors throughput over strict FIFO ordering. For strict fairness, a queue-based approach (e.g., `Semaphore` + refill thread) is needed, but adds latency overhead.

---

### Implementation

#### `TokenBucketRateLimiter.java`

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * <p>
 * Uses a single {@link AtomicLong} with bit-packing (tokens + timestamp) and CAS loops
 * for lock-free synchronization. Supports blocking and non-blocking acquisition.
 * </p>
 *
 * <h3>State Encoding (64-bit long)</h3>
 * <ul>
 *   <li>Low 32 bits: Available tokens * PRECISION (Fixed point integer)</li>
 *   <li>High 32 bits: Last refill timestamp (System.nanoTime)</li>
 * </ul>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    // --- Constants ---
    private static final long PRECISION = 1000L; // Fixed point precision (milli-tokens)
    private static final int TOKEN_BITS = 32;
    private static final long TOKEN_MASK = (1L << TOKEN_BITS) - 1L;
    private static final long MAX_TOKENS_RAW = (Long.MAX_VALUE / PRECISION); // Safety cap

    // --- Fields ---
    private final AtomicLong state; // Packed: (lastRefillNanos << 32) | (tokens * PRECISION)
    private final long maxTokensRaw; // maxTokens * PRECISION
    private final long refillRatePerNanoRaw; // (tokensPerSecond * PRECISION) / 1_000_000_000
    private final long maxWaitNanos; // Time to refill from 0 to max (approx)

    // --- Constructors ---

    /**
     * Creates a new limiter.
     *
     * @param tokensPerSecond Refill rate (tokens/second). Must be > 0.
     * @param maxTokens       Burst capacity (max bucket size). Must be > 0.
     * @throws IllegalArgumentException if params invalid.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, long maxTokens) {
        if (tokensPerSecond <= 0.0) throw new IllegalArgumentException("Rate must be positive");
        if (maxTokens <= 0) throw new IllegalArgumentException("Max tokens must be positive");
        if (maxTokens > MAX_TOKENS_RAW) throw new IllegalArgumentException("Max tokens too large");

        this.maxTokensRaw = maxTokens * PRECISION;
        // Calculate fixed-point refill rate per nanosecond: (rate * PRECISION) / 1e9
        this.refillRatePerNanoRaw = Math.max(1, Math.round((tokensPerSecond * PRECISION) / 1_000_000_000.0));

        // Initial state: Full bucket at current time
        long now = System.nanoTime();
        long initialTokensRaw = this.maxTokensRaw;
        this.state = new AtomicLong((now << TOKEN_BITS) | initialTokensRaw);

        // Max wait: time to generate maxTokens at current rate + small buffer
        this.maxWaitNanos = (long) ((maxTokens / tokensPerSecond) * 1_000_000_000.0) + 1_000_000; // +1ms buffer
    }

    // --- Public API ---

    /**
     * Acquires {@code permits} tokens, blocking until available.
     * Equivalent to {@code acquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)}.
     *
     * @param permits Number of tokens to acquire (typically 1).
     * @throws InterruptedException if interrupted while waiting.
     * @throws IllegalArgumentException if permits <= 0 or > maxTokens.
     */
    public void acquire(long permits) throws InterruptedException {
        if (!tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
            // Should not happen with infinite timeout unless interrupted
            throw new InterruptedException("Acquire interrupted");
        }
    }

    /**
     * Attempts to acquire {@code permits} tokens immediately (non-blocking).
     *
     * @param permits Number of tokens to acquire.
     * @return {@code true} if acquired, {@code false} if insufficient tokens.
     * @throws IllegalArgumentException if permits <= 0 or > maxTokens.
     */
    public boolean tryAcquire(long permits) {
        return tryAcquire(permits, 0, TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to acquire {@code permits} tokens, waiting up to {@code timeout}.
     *
     * @param permits  Number of tokens to acquire.
     * @param timeout  Max time to wait.
     * @param unit     Time unit for timeout.
     * @return {@code true} if acquired, {@code false} if timeout elapsed.
     * @throws InterruptedException if interrupted while waiting.
     * @throws IllegalArgumentException if permits <= 0 or > maxTokens.
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (permits > maxTokensRaw / PRECISION) throw new IllegalArgumentException("Permits exceeds bucket capacity");

        long permitsRaw = permits * PRECISION;
        long deadlineNanos = (timeout <= 0) ? 0 : System.nanoTime() + unit.toNanos(timeout);

        while (true) {
            long currentState = state.get();
            long newState = attemptAcquire(currentState, permitsRaw, deadlineNanos);

            if (newState == -1L) {
                return false; // Timeout (non-blocking or deadline passed)
            }
            if (newState == -2L) {
                // Need to wait (blocking path)
                parkUntilNextRefill(deadlineNanos);
                continue; // Retry CAS after wakeup
            }

            // Attempt CAS commit
            if (state.compareAndSet(currentState, newState)) {
                return true; // Success
            }
            // CAS failed: contention, retry immediately (spin)
        }
    }

    /**
     * Returns the current estimated available tokens (approximate, no synchronization).
     * Note: Value is stale the moment it is returned.
     */
    public double getAvailableTokens() {
        long currentState = state.get();
        long tokensRaw = currentState & TOKEN_MASK;
        long lastRefillNanos = currentState >>> TOKEN_BITS;
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        long refillRaw = elapsed * refillRatePerNanoRaw;
        long estimatedRaw = Math.min(maxTokensRaw, tokensRaw + refillRaw);
        return estimatedRaw / (double) PRECISION;
    }

    // --- Core Logic ---

    /**
     * Calculates next state for acquisition.
     * @return New packed state, -1L if timeout/failed, -2L if needs parking.
     */
    private long attemptAcquire(long currentState, long permitsRaw, long deadlineNanos) {
        long tokensRaw = currentState & TOKEN_MASK;
        long lastRefillNanos = currentState >>> TOKEN_BITS;
        long now = System.nanoTime();

        // 1. Refill Calculation
        long elapsedNanos = now - lastRefillNanos;
        // Handle potential time warp (clock adjustment backward) -> treat as 0 elapsed
        if (elapsedNanos < 0) elapsedNanos = 0;

        long refillRaw = elapsedNanos * refillRatePerNanoRaw;
        long newTokensRaw = Math.min(maxTokensRaw, tokensRaw + refillRaw);

        // 2. Check Availability
        if (newTokensRaw >= permitsRaw) {
            // Success: Consume tokens, update timestamp to NOW
            long updatedTokensRaw = newTokensRaw - permitsRaw;
            return (now << TOKEN_BITS) | updatedTokensRaw;
        }

        // 3. Insufficient Tokens
        if (deadlineNanos == 0) {
            return -1L; // Non-blocking tryAcquire failed
        }

        // 4. Calculate Wait Time
        long deficitRaw = permitsRaw - newTokensRaw;
        // Time needed = deficit / rate_per_nano
        long waitNanos = deficitRaw / refillRatePerNanoRaw;
        if (waitNanos <= 0) waitNanos = 1; // Minimum spin/park granularity

        // Cap wait to max bucket refill time or remaining deadline
        long maxAllowedWait = Math.min(maxWaitNanos, deadlineNanos - now);
        if (maxAllowedWait <= 0) {
            return -1L; // Deadline passed
        }
        long actualWait = Math.min(waitNanos, maxAllowedWait);

        // Return special sentinel indicating "Park for actualWait nanos"
        // We encode the wait time in the low bits of a negative number? No, simpler:
        // Return -2L to signal park, but we need to pass wait duration.
        // Hack: Store wait duration in a ThreadLocal? No, just return -2 and calculate again? 
        // Better: Return packed "wait instruction" or just re-calc after park.
        // Simplest correct approach: Return -2, park externally using calculated wait.
        // But we calculated wait HERE. We must park HERE or return wait time.
        // Let's return the wait time encoded. Since state is positive, negative is free.
        // Return ~actualWait (bitwise complement) to distinguish from -1, -2.
        return ~actualWait; // Negative value != -1, != -2
    }

    private void parkUntilNextRefill(long deadlineNanos) throws InterruptedException {
        // We need the wait time. Since attemptAcquire returned encoded wait, we have to re-calc or pass it.
        // Refactoring: attemptAcquire returns long. 
        // >=0 : New State
        // -1  : Fail/Timeout
        // < -1: Encoded Wait Time (~waitNanos)
        
        // Actually, simpler: attemptAcquire does NOT park. It returns the required wait time.
        // The caller (tryAcquire loop) parks.
        // But attemptAcquire needs 'now' for refill calc. If we park, 'now' changes.
        // So loop: Calc -> Park -> Loop (Re-calc).
    }

    // --- Revised Core Logic for Clean Parking ---

    /**
     * Tries to acquire. Returns:
     *  >= 0 : New State (Success)
     *  -1   : Failure (Timeout/Non-blocking)
     *  > 0 (WaitNanos) : Wait required. 
     * Wait, state is positive. 
     * Let's use a Result object or just loop in tryAcquire.
     */
    
    // Let's rewrite the loop in tryAcquire to be self-contained for clarity.
    // The previous split was awkward. Merging back into tryAcquire loop is standard for CAS loops.
    
    // Actually, the previous `attemptAcquire` returning `~waitNanos` was clever.
    // Let's stick to that pattern.
    
    // Wait, `attemptAcquire` above returns `~actualWait`. 
    // `tryAcquire` loop:
    //   res = attemptAcquire(...)
    //   if res == -1 -> false
    //   if res == -2 -> park (but we lost wait time)
    //   if res < -2 -> waitNanos = ~res; park(waitNanos); continue
    //   if res >= 0 -> CAS(res)
    
    // Let's fix the sentinel values.
    // SENTINEL_FAIL = -1
    // SENTINEL_PARK = -2 (Not used if we encode wait)
    // ENCODED_WAIT = ~waitNanos (which is <= -3 usually, since waitNanos >= 1 -> ~1 = -2. Collision!)
    // ~1 = -2. ~2 = -3.
    // So if waitNanos=1, encoded=-2.
    // Let's define:
    // FAIL = -1
    // WAIT_ENCODED_BASE = -2 (meaning waitNanos = ~encoded)
    // So waitNanos = 1 -> encoded = -2. waitNanos = 2 -> encoded = -3.
    
    // This works perfectly.
}
```

**Corrected & Complete `TokenBucketRateLimiter.java`**

```java
package com.example.ratelimit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * <p>
 * Uses a single {@link AtomicLong} with bit-packing (tokens + timestamp) and CAS loops
 * for lock-free synchronization.
 * </p>
 */
public final class TokenBucketRateLimiter {

    private static final long PRECISION = 1000L; // Milli-tokens
    private static final int TOKEN_BITS = 32;
    private static final long TOKEN_MASK = (1L << TOKEN_BITS) - 1L;
    private static final long MAX_TOKENS_RAW = Long.MAX_VALUE / PRECISION;

    // Sentinels for attemptAcquire return values
    private static final long FAIL = -1L;
    // Encoded wait: waitNanos = ~encoded. waitNanos=1 -> -2, waitNanos=2 -> -3 ...
    
    private final AtomicLong state;
    private final long maxTokensRaw;
    private final long refillRatePerNanoRaw;
    private final long maxWaitNanos;

    public TokenBucketRateLimiter(double tokensPerSecond, long maxTokens) {
        if (tokensPerSecond <= 0.0) throw new IllegalArgumentException("Rate must be positive");
        if (maxTokens <= 0) throw new IllegalArgumentException("Max tokens must be positive");
        if (maxTokens > MAX_TOKENS_RAW) throw new IllegalArgumentException("Max tokens too large");

        this.maxTokensRaw = maxTokens * PRECISION;
        this.refillRatePerNanoRaw = Math.max(1, Math.round((tokensPerSecond * PRECISION) / 1_000_000_000.0));
        
        long now = System.nanoTime();
        this.state = new AtomicLong((now << TOKEN_BITS) | this.maxTokensRaw);
        
        // Max time to wait for a full refill (0 -> maxTokens)
        this.maxWaitNanos = (long) ((maxTokens / tokensPerSecond) * 1_000_000_000.0) + 1_000_000; 
    }

    public void acquire(long permits) throws InterruptedException {
        if (!tryAcquire(permits, Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
            throw new InterruptedException("Acquire interrupted");
        }
    }

    public boolean tryAcquire(long permits) {
        return tryAcquire(permits, 0, TimeUnit.NANOSECONDS);
    }

    public boolean tryAcquire(long permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        long permitsRaw = permits * PRECISION;
        if (permitsRaw > maxTokensRaw) throw new IllegalArgumentException("Permits exceeds bucket capacity");

        long deadlineNanos = (timeout <= 0) ? 0 : System.nanoTime() + unit.toNanos(timeout);

        while (true) {
            long currentState = state.get();
            long result = attemptAcquire(currentState, permitsRaw, deadlineNanos);

            if (result == FAIL) {
                return false;
            }
            if (result < 0) { // Encoded wait time
                long waitNanos = ~result;
                if (Thread.interrupted()) throw new InterruptedException();
                LockSupport.parkNanos(this, waitNanos);
                continue;
            }

            // Result >= 0 is the new proposed state
            if (state.compareAndSet(currentState, result)) {
                return true;
            }
            // CAS failed: retry loop immediately (spin)
        }
    }

    /**
     * Calculates next state.
     * @return New State (>=0), FAIL (-1), or Encoded Wait (<0, != -1).
     */
    private long attemptAcquire(long currentState, long permitsRaw, long deadlineNanos) {
        long tokensRaw = currentState & TOKEN_MASK;
        long lastRefillNanos = currentState >>> TOKEN_BITS;
        long now = System.nanoTime();

        // 1. Refill
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos < 0) elapsedNanos = 0; // Clock warp protection
        
        long refillRaw = elapsedNanos * refillRatePerNanoRaw;
        long newTokensRaw = Math.min(maxTokensRaw, tokensRaw + refillRaw);

        // 2. Try Consume
        if (newTokensRaw >= permitsRaw) {
            long updatedTokensRaw = newTokensRaw - permitsRaw;
            return (now << TOKEN_BITS) | updatedTokensRaw;
        }

        // 3. Insufficient Tokens
        if (deadlineNanos == 0) {
            return FAIL; // Non-blocking
        }

        // 4. Calculate Wait
        long deficitRaw = permitsRaw - newTokensRaw;
        long waitNanos = deficitRaw / refillRatePerNanoRaw;
        if (waitNanos <= 0) waitNanos = 1;

        // Cap wait by deadline and max theoretical refill time
        long remainingTime = deadlineNanos - now;
        if (remainingTime <= 0) return FAIL;
        
        long cappedWait = Math.min(waitNanos, Math.min(maxWaitNanos, remainingTime));
        
        return ~cappedWait; // Encode wait time
    }

    public double getAvailableTokens() {
        long currentState = state.get();
        long tokensRaw = currentState & TOKEN_MASK;
        long lastRefillNanos = currentState >>> TOKEN_BITS;
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed < 0) elapsed = 0;
        long refillRaw = elapsed * refillRatePerNanoRaw;
        long estimatedRaw = Math.min(maxTokensRaw, tokensRaw + refillRaw);
        return estimatedRaw / (double) PRECISION;
    }
}
```

---

### JUnit 5 Tests

#### `TokenBucketRateLimiterTest.java`

```java
package com.example.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    // --- Configuration Tests ---

    @Test
    void constructor_InvalidRate_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
    }

    @Test
    void constructor_InvalidCapacity_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
    }

    @Test
    void acquire_InvalidPermits_Throws() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(11)); // > capacity
    }

    // --- Functional Tests ---

    @Test
    void tryAcquire_BurstCapacity_AllowsBurstUpToMax() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10); // Rate high, burst 10
        
        // Should acquire full burst immediately
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1), "Burst acquisition " + i + " failed");
        }
        // Next should fail (bucket empty)
        assertFalse(limiter.tryAcquire(1), "Should reject when bucket empty");
    }

    @Test
    void tryAcquire_RefillOverTime_AllowsEventualAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 1); // 1000 tokens/sec, burst 1
        
        assertTrue(limiter.tryAcquire(1));
        assertFalse(limiter.tryAcquire(1)); // Empty
        
        // Wait for refill (1 token takes 1ms)
        Thread.sleep(2); 
        
        assertTrue(limiter.tryAcquire(1), "Should acquire after refill");
    }

    @Test
    void acquire_Blocking_WaitsForTokens() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1); // 100 tokens/sec -> 10ms per token
        
        assertTrue(limiter.tryAcquire(1)); // Take the only token
        
        long start = System.nanoTime();
        // This should block ~10ms
        limiter.acquire(1); 
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        
        // Allow some slack for scheduler granularity (typically 1-2ms on standard OS)
        assertTrue(elapsedMs >= 8 && elapsedMs <= 50, "Blocking wait took " + elapsedMs + "ms, expected ~10ms");
    }

    @Test
    void tryAcquire_Timeout_RespectsDeadline() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1); // Slow refill: 100ms per token
        
        assertTrue(limiter.tryAcquire(1)); // Consume
        
        long start = System.nanoTime();
        // Timeout 50ms < 100ms needed
        boolean acquired = limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        
        assertFalse(acquired, "