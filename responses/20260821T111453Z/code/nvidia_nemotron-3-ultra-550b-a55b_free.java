<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260821T111453Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
## Design Document: Thread-Safe Token Bucket Rate Limiter

### 1. Overview
This document describes the design of a **Token Bucket Rate Limiter** implemented in Java. It is designed for high-concurrency environments, providing both blocking and non-blocking acquisition strategies with precise rate limiting semantics.

### 2. Algorithm: Token Bucket
The Token Bucket algorithm controls the rate of operations by maintaining a "bucket" of tokens.
*   **Capacity (Burst):** Maximum tokens the bucket can hold (`capacity`). Allows bursting up to this limit.
*   **Refill Rate:** Tokens added per second (`refillRatePerSecond`).
*   **Consumption:** Each `acquire(permits)` call removes `permits` tokens. If insufficient tokens exist, the caller waits (blocking) or fails immediately (non-blocking).

### 3. Concurrency Strategy: Lock-Free (CAS) with `AtomicLong`
To achieve high throughput under contention, we avoid `synchronized` blocks or `ReentrantLock`. Instead, we use a **Single-Writer / Compare-And-Swap (CAS)** pattern on a single `AtomicLong` representing the **next available timestamp** (in nanoseconds) when the requested permits will be available.

#### State Representation
Instead of storing `currentTokens` (which requires reading time, calculating refill, and updating atomically — a classic ABA problem source), we store **`nextFreeTicketMicros`** (or nanos).
*   **Variable:** `AtomicLong nextFreeTicketNanos` — The earliest nanosecond timestamp when the bucket will have enough tokens for the *next* request (assuming no other requests intervene).
*   **Initialization:** `0` (immediately available).

#### Core Logic (`reserveAndGetWaitTime`)
1.  **Snapshot:** Read current `nextFreeTicketNanos` (`oldValue`).
2.  **Calculate Now:** `now = System.nanoTime()`.
3.  **Determine Ready Time:** `newValue = max(oldValue, now)`. This handles the "idle refill" — if the bucket was idle (`oldValue < now`), tokens have refilled fully, so we start from `now`.
4.  **Calculate Wait:** `waitNanos = newValue - now`. Time until tokens are ready.
5.  **Advance State:** `newValue += permits * nanosPerToken`. This reserves tokens for the current request and pushes the "free time" forward for the next requester.
6.  **CAS Loop:** `compareAndSet(oldValue, newValue)`. If fails, retry (spin).
7.  **Return:** `waitNanos`.

*Why this works:* The variable `nextFreeTicketNanos` monotonically increases. It implicitly encodes the token count: `tokensAvailable = (now - nextFreeTicketNanos) / nanosPerToken` (clamped to capacity). By reserving future time slots, we serialize access logically without a mutex.

### 4. Handling Burst Capacity
Strict "next free ticket" logic allows unlimited burst if the bucket was idle (because `max(oldValue, now)` resets the clock).
To enforce **strict burst capacity (`capacity`)**:
*   We calculate the **theoretical earliest time** the bucket *could* have been full: `now - capacity * nanosPerToken`.
*   During the `max(oldValue, now)` step, we clamp the lower bound: `newValue = max(oldValue, now - capacity * nanosPerToken)`.
*   This ensures that even after a long idle period, the "virtual queue" cannot extend further back than the burst capacity allows.

### 5. API Design
```java
public interface RateLimiter {
    /** Blocks until permits acquired. Returns time slept (nanos). */
    long acquire(int permits) throws InterruptedException;

    /** Non-blocking. Returns true if acquired immediately. */
    boolean tryAcquire(int permits);

    /** Non-blocking with timeout. */
    boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException;
    
    // Getters for config
}
```

### 6. Blocking vs Non-Blocking
*   **`tryAcquire`**: Calls `reserveAndGetWaitTime`. If `waitNanos <= 0`, returns `true`. If `waitNanos > 0`, **rolls back** the reservation (CAS loop to subtract the added time) and returns `false`.
    *   *Optimization Note:* Rolling back CAS on contention is expensive. Alternative: Peek current state without CAS for `tryAcquire`. Implemented here: **Peek-only for `tryAcquire`** (read `nextFreeTicketNanos`, calculate if `wait <= 0`, if so CAS to reserve). This avoids "reserve-then-rollback" contention.
*   **`acquire`**: Calls `reserveAndGetWaitTime`. If `waitNanos > 0`, parks thread (`LockSupport.parkNanos`).
*   **`tryAcquire(timeout)`**: Calculates deadline. Loops `reserveAndGetWaitTime`. If `waitNanos > remainingTime`, rolls back (or peeks first) and returns false. Else parks for `min(waitNanos, remainingTime)`.

### 7. Correctness Guarantees
1.  **Rate Limit:** Long-term rate $\le$ `refillRatePerSecond`.
2.  **Burst Limit:** Max instantaneous permits $\le$ `capacity`.
3.  **Thread Safety:** Linearizable operations via `AtomicLong` CAS.
4.  **Fairness:** Approximate FIFO (threads queue on `nextFreeTicketNanos` timeline). Not strict OS-scheduler fairness, but "ticket" fairness.
5.  **Interruptibility:** `acquire` and `tryAcquire(timeout)` respect `Thread.interrupt()`.

---

## 2. Implementation

### `TokenBucketRateLimiter.java`

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * 
 * Uses a lock-free CAS algorithm on a single AtomicLong representing the 
 * "next free ticket" timestamp (nanoseconds).
 * 
 * Guarantees:
 * 1. Long-term rate <= refillRatePerSecond
 * 2. Max burst <= capacity
 * 3. Linearizable acquire/tryAcquire
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final double refillRatePerSecond;
    private final long capacity;
    private final long nanosPerToken;       // 1_000_000_000 / rate
    private final long maxBurstNanos;       // capacity * nanosPerToken
    
    // The timestamp (nanos) when the next token (or batch of tokens) becomes available.
    // Monotonically increasing.
    private final AtomicLong nextFreeTicketNanos = new AtomicLong(0L);

    /**
     * @param refillRatePerSecond Tokens added per second (e.g., 1000.0 for 1k QPS). Must be > 0.
     * @param capacity Max bucket size (burst allowance). Must be >= 1.
     */
    public TokenBucketRateLimiter(double refillRatePerSecond, long capacity) {
        if (refillRatePerSecond <= 0.0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be >= 1");
        }
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        this.nanosPerToken = (long) (1_000_000_000L / refillRatePerSecond);
        this.maxBurstNanos = capacity * nanosPerToken;
    }

    @Override
    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    @Override
    public long getCapacity() {
        return capacity;
    }

    /**
     * Acquires permits, blocking until available.
     * @param permits Number of permits to acquire (must be > 0).
     * @return Time waited in nanoseconds.
     * @throws InterruptedException If thread interrupted while waiting.
     */
    @Override
    public long acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        
        long waitNanos = reserveAndGetWaitTime(permits);
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
            // Re-check interrupt status after park
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while waiting for rate limiter permit");
            }
        }
        return waitNanos;
    }

    /**
     * Tries to acquire permits immediately without blocking.
     * @param permits Number of permits to acquire.
     * @return true if acquired, false otherwise.
     */
    @Override
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        
        // Fast path: Peek current state without CAS contention.
        // We calculate if we *would* wait. If wait <= 0, we attempt CAS to reserve.
        long now = System.nanoTime();
        long oldValue = nextFreeTicketNanos.get();
        
        // Calculate theoretical earliest start time respecting burst capacity
        long earliestAllowedStart = now - maxBurstNanos;
        
        // The time the bucket is actually ready for us (ignoring this request)
        long readyTime = Math.max(oldValue, earliestAllowedStart);
        
        // If readyTime <= now, tokens are available immediately.
        if (readyTime <= now) {
            // Attempt to reserve: new ticket = now + cost
            long newValue = now + (long) permits * nanosPerToken;
            // CAS: Only succeed if state hasn't changed (still <= now effectively, or specifically oldValue)
            // Note: We must CAS against oldValue. If another thread slipped in, we fail and return false.
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return true;
            }
            // CAS failed: contention. Fall through to slow path (reserveAndGetWaitTime) 
            // or just return false. 
            // Returning false here is safe (conservative) but might cause spurious failures under high contention.
            // Better: Fall back to reserveAndGetWaitTime which handles contention via spin.
        }
        
        // Slow path / Contended path: Use the full reservation logic which spins on CAS.
        // If it returns waitNanos <= 0, we got it. 
        // If waitNanos > 0, we must NOT park (non-blocking), so we need to rollback.
        // However, reserveAndGetWaitTime *commits* the reservation.
        // For strict non-blocking tryAcquire, we should not commit if we have to wait.
        
        // Optimization: Use a "Peek then Reserve" loop for tryAcquire to avoid rollback.
        return tryAcquireNonBlocking(permits, now);
    }

    /**
     * Non-blocking acquire with timeout.
     */
    @Override
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        if (timeout <= 0) return tryAcquire(permits);

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        
        while (true) {
            long waitNanos = reserveAndGetWaitTime(permits);
            
            if (waitNanos <= 0) {
                return true; // Acquired immediately
            }
            
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                // Timeout: We reserved a spot in the future but can't wait. 
                // We MUST rollback the reservation.
                rollbackReservation(permits);
                return false;
            }
            
            // Wait for the shorter of: time until permits ready, or deadline
            long parkNanos = Math.min(waitNanos, remainingNanos);
            LockSupport.parkNanos(parkNanos);
            
            if (Thread.interrupted()) {
                // If interrupted during park, we might have woken up early.
                // The reservation is still valid (we own the ticket).
                // Standard behavior: Throw InterruptedException.
                // But we already modified state. We should probably rollback? 
                // Java's Semaphore/RateLimiter usually throws IE and leaves state acquired.
                // However, for a rate limiter, "acquire" implies consumption. 
                // If interrupted, we treat as "cancel wait", rollback, throw.
                rollbackReservation(permits);
                throw new InterruptedException("Interrupted while waiting for permit");
            }
            // Loop continues: reserveAndGetWaitTime will now return <=0 (or small remainder) 
            // because we advanced time via park. 
            // Actually, reserveAndGetWaitTime re-reads 'now'. If we parked exactly waitNanos, 
            // next call returns ~0.
        }
    }

    // ---------------------------------------------------------
    // Internal Mechanics
    // ---------------------------------------------------------

    /**
     * Core CAS loop. Reserves 'permits' and returns wait time in nanos.
     * Returns negative or zero if available immediately.
     * Modifies state (commits reservation).
     */
    private long reserveAndGetWaitTime(int permits) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long oldValue = nextFreeTicketNanos.get();
            long now = System.nanoTime();
            
            // 1. Calculate when bucket is ready for us (accounting for idle refill & burst cap)
            // If oldValue < now - maxBurstNanos, the bucket was idle long enough to fill to capacity.
            // We clamp the "virtual queue start" to now - maxBurstNanos.
            long earliestAllowedStart = now - maxBurstNanos;
            long readyTime = Math.max(oldValue, earliestAllowedStart);
            
            // 2. Calculate wait time
            long waitNanos = readyTime - now;
            
            // 3. Calculate new ticket time (when next requester can start)
            long newValue = readyTime + costNanos;
            
            // 4. CAS
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return waitNanos;
            }
            // CAS failed: Retry immediately (spin). Contention is usually low duration.
        }
    }

    /**
     * Attempts non-blocking acquire using a Peek-CAS loop to avoid rollback.
     */
    private boolean tryAcquireNonBlocking(int permits, long now) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long oldValue = nextFreeTicketNanos.get();
            long earliestAllowedStart = now - maxBurstNanos;
            long readyTime = Math.max(oldValue, earliestAllowedStart);
            
            if (readyTime > now) {
                // Not enough tokens right now.
                return false;
            }
            
            // Tokens available. Try to reserve starting at 'now'.
            long newValue = now + costNanos;
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return true;
            }
            // CAS failed: Another thread modified state. Retry loop (re-read now).
            now = System.nanoTime(); // Update time for next iteration
        }
    }

    /**
     * Rolls back a reservation made by reserveAndGetWaitTime.
     * Used when tryAcquire(timeout) times out or is interrupted.
     * Adds 'permits' worth of time back to the bucket (subtracts from nextFreeTicket).
     * Note: This is a best-effort rollback. If other requests have already reserved 
     * slots *after* ours, we cannot simply subtract because we'd push their ready-time 
     * into the past. 
     * 
     * Correct Rollback Logic: We can only rollback if we are the "last" reservation 
     * (i.e., nextFreeTicketNanos == our_reservation_end). 
     * Since we don't track "our reservation end" separately easily without extra state, 
     * a safe conservative rollback: 
     * CAS loop: if current value == my_ticket_end, set to my_ticket_start. 
     * Else: do nothing (tokens effectively "lost" / given to subsequent waiters). 
     * 
     * However, in this design, reserveAndGetWaitTime returns waitNanos. 
     * We don't store 'my_ticket_end' easily. 
     * 
     * Alternative Design for Timeout: 
     * Don't use reserveAndGetWaitTime (which commits). 
     * Use a "Peek" loop similar to tryAcquireNonBlocking but with parking.
     * 
     * REVISED tryAcquire(timeout) LOGIC (Implemented above):
     * It calls reserveAndGetWaitTime. If wait > remaining, it calls rollbackReservation.
     * rollbackReservation attempts to subtract costNanos from nextFreeTicketNanos 
     * ONLY if the current value matches what we expect (our reservation end).
     * If not, it means someone else queued behind us; we leave them be (our permits 
     * effectively transferred to them). This is safe and standard for ticket locks.
     */
    private void rollbackReservation(int permits) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long current = nextFreeTicketNanos.get();
            // We assume 'current' is the end of our reservation (start + cost).
            // We want to set it back to 'start' (current - cost).
            // But only if no one else has appended after us.
            // Since we don't know our 'start' exactly here (lost in previous call), 
            // we have a problem.
            
            // FIX: reserveAndGetWaitTime should return the 'newValue' (ticket end) 
            // or we pass it in. 
            // Let's change reserveAndGetWaitTime to return a Reservation object or 
            // handle timeout logic differently.
        }
    }
    
    // --- REVISED INTERNAL API FOR CORRECT TIMEOUT/ROLLBACK ---

    /**
     * Result of a reservation attempt.
     */
    private static final class Reservation {
        final long waitNanos;
        final long reservationEndNanos; // The value written to nextFreeTicketNanos
        
        Reservation(long waitNanos, long reservationEndNanos) {
            this.waitNanos = waitNanos;
            this.reservationEndNanos = reservationEndNanos;
        }
    }

    /**
     * Reserves permits. Returns Reservation object containing wait time and the 
     * exact timestamp written to the atomic (end of this reservation).
     */
    private Reservation reserve(int permits) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long oldValue = nextFreeTicketNanos.get();
            long now = System.nanoTime();
            long earliestAllowedStart = now - maxBurstNanos;
            long readyTime = Math.max(oldValue, earliestAllowedStart);
            long waitNanos = readyTime - now;
            long newValue = readyTime + costNanos; // This is the end of our reservation
            
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return new Reservation(waitNanos, newValue);
            }
        }
    }

    /**
     * Rolls back a specific reservation identified by its end timestamp.
     * Only succeeds if the atomic still holds that exact end timestamp 
     * (meaning no one else queued after us).
     */
    private void rollback(Reservation reservation) {
        // Attempt to revert nextFreeTicketNanos from reservationEnd -> reservationEnd - cost
        // But we need cost. We can derive cost: reservationEnd - readyTime. 
        // We don't have readyTime stored. 
        // Store readyTime in Reservation.
        // Actually, simpler: We know permits. Cost = permits * nanosPerToken.
        long costNanos = (long) (reservation.reservationEndNanos - (reservation.reservationEndNanos - costNanos)); // dummy
        // Let's add cost to Reservation.
    }
    
    // Let's restart the implementation cleanly below.
```

### `TokenBucketRateLimiter.java` (Final Clean Version)

```java
package com.example.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance, thread-safe Token Bucket Rate Limiter.
 * Lock-free implementation using AtomicLong CAS on a "next free ticket" timeline.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final double refillRatePerSecond;
    private final long capacity;
    private final long nanosPerToken;
    private final long maxBurstNanos;
    private final AtomicLong nextFreeTicketNanos = new AtomicLong(0L);

    public TokenBucketRateLimiter(double refillRatePerSecond, long capacity) {
        if (refillRatePerSecond <= 0.0) throw new IllegalArgumentException("Rate must be > 0");
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");
        this.refillRatePerSecond = refillRatePerSecond;
        this.capacity = capacity;
        this.nanosPerToken = (long) (1_000_000_000L / refillRatePerSecond);
        this.maxBurstNanos = capacity * nanosPerToken;
    }

    @Override public double getRefillRatePerSecond() { return refillRatePerSecond; }
    @Override public long getCapacity() { return capacity; }

    @Override
    public long acquire(int permits) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits > 0 required");
        Reservation r = reserve(permits);
        if (r.waitNanos > 0) {
            LockSupport.parkNanos(r.waitNanos);
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return Math.max(0, r.waitNanos);
    }

    @Override
    public boolean tryAcquire(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits > 0 required");
        return tryAcquireNonBlocking(permits, System.nanoTime());
    }

    @Override
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) throw new IllegalArgumentException("Permits > 0 required");
        if (timeout <= 0) return tryAcquire(permits);

        long deadline = System.nanoTime() + unit.toNanos(timeout);
        
        while (true) {
            Reservation r = reserve(permits);
            if (r.waitNanos <= 0) return true; // Got it

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                rollback(r); // Give back the reservation
                return false;
            }

            long parkTime = Math.min(r.waitNanos, remaining);
            LockSupport.parkNanos(parkTime);
            
            if (Thread.interrupted()) {
                rollback(r);
                throw new InterruptedException();
            }
            // Loop: reserve() will now see time advanced and likely return wait <= 0
        }
    }

    // ----------------- Internals -----------------

    private static final class Reservation {
        final long waitNanos;
        final long reservationEndNanos; // Value written to AtomicLong
        final long costNanos;
        
        Reservation(long waitNanos, long reservationEndNanos, long costNanos) {
            this.waitNanos = waitNanos;
            this.reservationEndNanos = reservationEndNanos;
            this.costNanos = costNanos;
        }
    }

    /**
     * Atomically reserves 'permits'. Spins on CAS.
     * @return Reservation with wait time and the ticket end timestamp.
     */
    private Reservation reserve(int permits) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long oldValue = nextFreeTicketNanos.get();
            long now = System.nanoTime();
            
            // Clamp history to max burst window
            long earliestStart = now - maxBurstNanos;
            long readyTime = Math.max(oldValue, earliestStart);
            
            long waitNanos = readyTime - now;
            long newValue = readyTime + costNanos; // End of our reservation
            
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return new Reservation(waitNanos, newValue, costNanos);
            }
            // Spin retry
        }
    }

    /**
     * Non-blocking fast path: Peek then CAS.
     */
    private boolean tryAcquireNonBlocking(int permits, long now) {
        long costNanos = (long) permits * nanosPerToken;
        
        while (true) {
            long oldValue = nextFreeTicketNanos.get();
            long earliestStart = now - maxBurstNanos;
            long readyTime = Math.max(oldValue, earliestStart);
            
            if (readyTime > now) return false; // Would block
            
            long newValue = now + costNanos;
            if (nextFreeTicketNanos.compareAndSet(oldValue, newValue)) {
                return true;
            }
            // Contention: retry with fresh 'now'
            now = System.nanoTime();
        }
    }

    /**
     * Attempts to rollback a reservation. 
     * Only succeeds if the atomic value is still exactly our reservationEnd 
     * (no subsequent reservations appended).
     */
    private void rollback(Reservation r) {
        // We want to set: reservationEndNanos -> reservationEndNanos - costNanos
        // Which equals: readyTime (start of our reservation)
        long expectedEnd = r.reservationEndNanos;
        long newValue = expectedEnd - r.costNanos;
        
        // CAS: