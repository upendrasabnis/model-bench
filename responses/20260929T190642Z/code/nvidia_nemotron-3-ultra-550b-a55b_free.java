<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260929T190642Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here is a complete, production-ready implementation of a thread-safe Token Bucket Rate Limiter in Java 17+, including design documentation and comprehensive JUnit 5 tests.

---

### 1. Design Documentation

#### **Algorithm: Token Bucket**
The Token Bucket algorithm controls the rate of operations by maintaining a "bucket" of tokens.
*   **Capacity (`maxTokens`)**: The maximum burst size. The bucket cannot hold more tokens than this.
*   **Refill Rate (`tokensPerSecond`)**: Tokens are added continuously (conceptually) at this rate.
*   **Acquisition**: A request consumes `n` tokens (usually 1). If enough tokens exist, it succeeds immediately. If not, the request either blocks until tokens accumulate (`acquire`) or fails immediately (`tryAcquire`).

#### **Concurrency Strategy: Lock-Free / Optimistic CAS (Compare-And-Swap)**
Instead of a coarse-grained `synchronized` block or `ReentrantLock`, this implementation uses `AtomicLong` for state and a **single 128-bit state word** (packed into a `long` array or handled via `AtomicReference` of an immutable state object) to ensure atomicity of the *check-refill-consume* cycle.

**State Representation (Packed into `long`):**
We pack two values into a single `long` (64 bits) to allow atomic updates via `AtomicLong.compareAndSet`:
*   **Bits 0-31 (Lower 32 bits)**: `availableTokens` (Fixed-point integer, scaled by `PRECISION = 1_000_000` to handle fractional tokens).
*   **Bits 32-63 (Upper 32 bits)**: `lastRefillNanos` (Timestamp of last refill calculation, truncated to 32 bits — safe because we only care about *deltas* < ~4 seconds; see "Timestamp Truncation" below).

**Why Packing?**
Java's `AtomicLong` supports CAS on 64 bits. `AtomicReference<StateObject>` allocates a new object on every refill attempt (GC pressure). Packing avoids allocation and provides a single atomic swap.

**Timestamp Truncation (32-bit nanos):**
`System.nanoTime()` returns 64 bits. We store lower 32 bits.
*   Max delta representable: $2^{32} \text{ ns} \approx 4.29 \text{ seconds}$.
*   **Safety**: If a thread stalls > 4s (GC pause, debugger), the calculated delta wraps negative. The code detects `elapsedNanos < 0` and treats it as a "large positive delta" (full refill), ensuring correctness after long pauses.

**Blocking Strategy (`acquire`):**
1.  Fast path: Try CAS to consume tokens.
2.  Slow path: Calculate exact sleep time required for tokens to arrive.
3.  `LockSupport.parkNanos(sleepTime)` (responds to interrupts, no `InterruptedException` boilerplate).
4.  Loop until success.

**Fairness:** This is a **non-fair** limiter. Threads waking up compete equally with new arriving threads (barging). This maximizes throughput under contention.

---

### 2. Implementation (`TokenBucketRateLimiter.java`)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * <p>
 * Uses a single {@link AtomicLong} packing {tokens, timestamp} for lock-free state updates.
 * Supports fractional tokens via fixed-point arithmetic (micro-precision).
 * </p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    // --- Constants ---
    private static final long PRECISION = 1_000_000L; // Micro-precision (1 token = 1,000,000 units)
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int TIMESTAMP_SHIFT = 32;
    private static final long TOKEN_MASK = 0xFFFFFFFFL; // Lower 32 bits
    private static final long MAX_SLEEP_NANOS = TimeUnit.SECONDS.toNanos(1); // Cap spin/park duration

    // --- Configuration ---
    private final double tokensPerSecond;
    private final long maxTokens; // Scaled by PRECISION
    private final long refillRatePerNano; // Scaled by PRECISION (tokens per nanosecond * PRECISION)

    // --- State ---
    // Packed: Upper 32 bits = lastRefillNanos (truncated), Lower 32 bits = availableTokens (scaled)
    private final AtomicLong state;

    /**
     * Creates a new rate limiter.
     *
     * @param tokensPerSecond Refill rate (e.g., 100.0 for 100 req/s). Must be > 0.
     * @param maxBurst        Maximum burst capacity (bucket size). Must be >= 1.
     * @throws IllegalArgumentException if params invalid.
     */
    public TokenBucketRateLimiter(double tokensPerSecond, long maxBurst) {
        if (tokensPerSecond <= 0) throw new IllegalArgumentException("Rate must be > 0");
        if (maxBurst < 1) throw new IllegalArgumentException("Burst capacity must be >= 1");

        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxBurst * PRECISION;
        // Pre-calculate fixed-point refill rate per nanosecond: (rate * PRECISION) / 1e9
        this.refillRatePerNano = Math.round((tokensPerSecond * PRECISION) / (double) NANOS_PER_SECOND);
        
        // Initial state: Full bucket, timestamp = now (truncated)
        long initialTokens = this.maxTokens;
        long initialTimestamp = truncateTimestamp(System.nanoTime());
        this.state = new AtomicLong(packState(initialTokens, initialTimestamp));
    }

    // --- Public API ---

    /**
     * Acquires a permit (1 token), blocking until available.
     * Responds to thread interruption by returning immediately (does not throw InterruptedException).
     *
     * @return true if acquired, false if interrupted.
     */
    public boolean acquire() {
        return acquire(1, Long.MAX_VALUE, null);
    }

    /**
     * Attempts to acquire a permit without blocking.
     *
     * @return true if acquired immediately, false otherwise.
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire {@code permits} tokens without blocking.
     *
     * @param permits number of tokens to consume (must be > 0).
     * @return true if acquired immediately, false otherwise.
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be > 0");
        long required = permits * PRECISION;
        if (required > maxTokens) return false; // Cannot ever satisfy

        while (true) {
            long currentState = state.get();
            long newState = tryConsume(currentState, required, System.nanoTime());
            if (newState == -1L) return false; // Not enough tokens
            if (state.compareAndSet(currentState, newState)) return true;
            // CAS failed -> retry
        }
    }

    /**
     * Acquires {@code permits} tokens, blocking up to {@code timeout}.
     *
     * @param permits  number of tokens.
     * @param timeout  max time to wait.
     * @param unit     time unit.
     * @return true if acquired, false if timeout elapsed or interrupted.
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be > 0");
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        return acquire(permits, deadlineNanos, unit);
    }

    // --- Core Logic ---

    private boolean acquire(int permits, long deadlineNanos, TimeUnit unit) {
        long required = permits * PRECISION;
        if (required > maxTokens) return false; // Impossible request

        while (true) {
            long now = System.nanoTime();
            long currentState = state.get();
            long newState = tryConsume(currentState, required, now);

            if (newState != -1L) {
                // Tokens available, try CAS
                if (state.compareAndSet(currentState, newState)) return true;
                continue; // CAS failed, retry immediately
            }

            // --- Not enough tokens ---
            // Check deadline/interrupt
            if (now >= deadlineNanos || Thread.currentThread().isInterrupted()) {
                return false;
            }

            // Calculate sleep time needed to accumulate 'required' tokens
            // currentTokens + (elapsed * rate) >= required
            // elapsed >= (required - currentTokens) / rate
            long currentTokens = unpackTokens(currentState);
            long deficit = required - currentTokens; // > 0
            
            // Fixed point division: deficit / refillRatePerNano
            // refillRatePerNano is (rate * PRECISION) / 1e9.
            // So nanos = deficit * 1e9 / (rate * PRECISION)
            // But we have refillRatePerNano = (rate * PRECISION) / 1e9 (integer division rounded).
            // Better: nanos = (deficit * NANOS_PER_SECOND) / (tokensPerSecond * PRECISION)
            // Use double for sleep calculation precision to avoid integer overflow/underflow.
            double deficitTokens = (double) deficit / PRECISION;
            long sleepNanos = (long) Math.ceil(deficitTokens / tokensPerSecond * NANOS_PER_SECOND);

            // Clamp sleep to avoid busy spinning on tiny deficits or huge sleeps
            if (sleepNanos < 1000) sleepNanos = 1000; // Min 1 microspin/park
            if (sleepNanos > MAX_SLEEP_NANOS) sleepNanos = MAX_SLEEP_NANOS;
            
            // Don't sleep past deadline
            long timeUntilDeadline = deadlineNanos - now;
            if (sleepNanos > timeUntilDeadline) sleepNanos = timeUntilDeadline;

            LockSupport.parkNanos(sleep