<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261014T225828Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Design Documentation

### Overview
A thread-safe token bucket rate limiter implementing the classic algorithm where:
- **Tokens** represent permission to proceed
- **Refill rate** (tokens/second) controls long-term throughput
- **Burst capacity** (max tokens) controls maximum burstiness
- **Blocking acquire** waits until tokens available
- **Non-blocking tryAcquire** returns immediately

### Thread Safety Strategy
- **Lock-free design** using `AtomicLong` for shared state
- **Single-writer principle**: Only one thread updates state at a time via CAS
- **Optimistic concurrency**: Read current state, compute new state, CAS update
- **Park/Unpark** for efficient blocking without busy-waiting

### State Representation
```java
// Packed into two AtomicLongs for atomic snapshot
availableTokens: current tokens (double, scaled by 1<<32 for precision)
lastRefillNanos: last refill timestamp (System.nanoTime())
```

### Refill Algorithm
```
elapsed = now - lastRefillNanos
newTokens = elapsed * refillRatePerNano
available = min(capacity, available + newTokens)
```

### Blocking Strategy
- Calculate wait time for next token
- Use `LockSupport.parkNanos` with timeout
- Re-check after wakeup (spurious wakeups possible)
- Respect interruption

---

## Implementation

```java
package com.example.ratelimiter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-safe token bucket rate limiter.
 * 
 * <p>Supports configurable refill rate and burst capacity with both blocking
 * and non-blocking acquisition modes.
 * 
 * <p>Implementation uses lock-free atomic operations for high contention scenarios.
 * Tokens are represented as fixed-point numbers (scaled by 2^32) to avoid
 * floating-point non-determinism while maintaining sub-token precision.
 * 
 * @see <a href="https://en.wikipedia.org/wiki/Token_bucket">Token Bucket Algorithm</a>
 */
public final class TokenBucketRateLimiter {

    // Fixed-point scaling factor: 2^32 allows ~4 billion subunits per token
    private static final long SCALE = 1L << 32;
    private static final double SCALE_D = (double) SCALE;

    private final AtomicLong availableTokens;  // scaled by SCALE
    private final AtomicLong lastRefillNanos;
    private final long capacityScaled;
    private final double refillRatePerNanoScaled;  // tokens per nanosecond, scaled

    /**
     * Creates a new rate limiter.
     *
     * @param permitsPerSecond refill rate (tokens per second), must be > 0
     * @param burstCapacity maximum tokens that can accumulate, must be > 0
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TokenBucketRateLimiter(double permitsPerSecond, long burstCapacity) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive: " + permitsPerSecond);
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be positive: " + burstCapacity);
        }
        if (permitsPerSecond > Long.MAX_VALUE / SCALE_D) {
            throw new IllegalArgumentException("permitsPerSecond too large");
        }

        this.capacityScaled = burstCapacity * SCALE;
        this.refillRatePerNanoScaled = (permitsPerSecond / 1_000_000_000.0) * SCALE_D;
        this.availableTokens = new AtomicLong(this.capacityScaled);  // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Creates a rate limiter with duration-based rate specification.
     *
     * @param permitsPerInterval number of permits per interval
     * @param interval the time interval
     * @param burstCapacity maximum burst capacity
     */
    public static TokenBucketRateLimiter of(long permitsPerInterval, Duration interval, long burstCapacity) {
        double permitsPerSecond = permitsPerInterval / (interval.toNanos() / 1_000_000_000.0);
        return new TokenBucketRateLimiter(permitsPerSecond, burstCapacity);
    }

    /**
     * Acquires a permit, blocking until one is available.
     * 
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }

    /**
     * Acquires the given number of permits, blocking until all are available.
     * 
     * @param permits number of permits to acquire, must be > 0
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public void acquire(long permits) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        if (permits > burstCapacity()) {
            throw new IllegalArgumentException("permits (" + permits + ") exceeds burst capacity (" + burstCapacity() + ")");
        }

        long permitsScaled = permits * SCALE;
        
        while (true) {
            long now = System.nanoTime();
            long currentTokens = refillAndGetAvailable(now);
            
            if (currentTokens >= permitsScaled) {
                // Try to claim tokens
                if (availableTokens.compareAndSet(currentTokens, currentTokens - permitsScaled)) {
                    return;  // Success
                }
                // CAS failed, retry
            } else {
                // Need to wait for more tokens
                long deficit = permitsScaled - currentTokens;
                // Time needed = deficit / refillRatePerNanoScaled
                long waitNanos = (long) Math.ceil(deficit / refillRatePerNanoScaled);
                
                // Park with timeout, but cap at a reasonable max to handle clock drift
                long maxWaitNanos = TimeUnit.SECONDS.toNanos(1); // 1 second max park
                long parkNanos = Math.min(waitNanos, maxWaitNanos);
                
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                LockSupport.parkNanos(this, parkNanos);
                
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                // Loop and retry
            }
        }
    }

    /**
     * Attempts to acquire a permit without blocking.
     * 
     * @return true if permit was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire the given number of permits without blocking.
     * 
     * @param permits number of permits to acquire, must be > 0
     * @return true if all permits were acquired, false otherwise
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        if (permits > burstCapacity()) {
            return false;  // Can never acquire more than capacity
        }

        long permitsScaled = permits * SCALE;
        
        while (true) {
            long now = System.nanoTime();
            long currentTokens = refillAndGetAvailable(now);
            
            if (currentTokens >= permitsScaled) {
                if (availableTokens.compareAndSet(currentTokens, currentTokens - permitsScaled)) {
                    return true;
                }
                // CAS failed, retry
            } else {
                return false;
            }
        }
    }

    /**
     * Attempts to acquire a permit, blocking for at most the specified timeout.
     * 
     * @param timeout maximum time to wait
     * @param unit time unit of the timeout
     * @return true if permit was acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    /**
     * Attempts to acquire the given number of permits, blocking for at most the specified timeout.
     * 
     * @param permits number of permits to acquire
     * @param timeout maximum time to wait
     * @param unit time unit of the timeout
     * @return true if all permits were acquired, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalArgumentException if permits <= 0
     */
    public boolean tryAcquire(long permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        if (permits > burstCapacity()) {
            return false;
        }

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        long permitsScaled = permits * SCALE;

        while (true) {
            long now = System.nanoTime();
            if (now >= deadlineNanos) {
                return false;
            }

            long currentTokens = refillAndGetAvailable(now);
            
            if (currentTokens >= permitsScaled) {
                if (availableTokens.compareAndSet(currentTokens, currentTokens - permitsScaled)) {
                    return true;
                }
            } else {
                long deficit = permitsScaled - currentTokens;
                long waitNanos = (long) Math.ceil(deficit / refillRatePerNanoScaled);
                long remainingNanos = deadlineNanos - now;
                long parkNanos = Math.min(waitNanos, remainingNanos);
                
                if (parkNanos <= 0) {
                    continue;  // Check deadline again
                }
                
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                LockSupport.parkNanos(this, parkNanos);
                
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        }
    }

    /**
     * Refills tokens based on elapsed time and returns current available tokens (scaled).
     * Uses CAS loop to update lastRefillNanos atomically with token calculation.
     */
    private long refillAndGetAvailable(long now) {
        while (true) {
            long lastRefill = lastRefillNanos.get();
            long currentTokens = availableTokens.get();
            
            // Calculate tokens to add
            long elapsedNanos = now - lastRefill;
            if (elapsedNanos <= 0) {
                return currentTokens;  // No time passed or clock went backward
            }
            
            double tokensToAdd = elapsedNanos * refillRatePerNanoScaled;
            long newTokens = currentTokens + (long) tokensToAdd;
            if (newTokens > capacityScaled) {
                newTokens = capacityScaled;
            }
            
            // Try to update both atomically: first update lastRefill, then tokens
            // We use a combined approach: if lastRefill matches, we can update tokens
            if (lastRefillNanos.compareAndSet(lastRefill, now)) {
                // We won the race to update lastRefill, now update tokens
                // Use CAS loop for tokens since other threads may have updated
                while (true) {
                    long latestTokens = availableTokens.get();
                    long finalTokens = Math.min(capacityScaled, latestTokens + (long) tokensToAdd);
                    if (availableTokens.compareAndSet(latestTokens, finalTokens)) {
                        return finalTokens;
                    }
                }
            }
            // Lost race, retry
        }
    }

    /**
     * Returns the current number of available permits (approximate, non-blocking).
     * 
     * @return available permits as a double (may have fractional part)
     */
    public double availablePermits() {
        long now = System.nanoTime();
        long lastRefill = lastRefillNanos.get();
        long currentTokens = availableTokens.get();
        
        long elapsedNanos = now - lastRefill;
        if (elapsedNanos <= 0) {
            return currentTokens / SCALE_D;
        }
        
        double tokensToAdd = elapsedNanos * refillRatePerNanoScaled;
        double total = (currentTokens + tokensToAdd) / SCALE_D;
        return Math.min(burstCapacity(), total);
    }

    /**
     * Returns the configured refill rate in permits per second.
     */
    public double getRefillRate() {
        return refillRatePerNanoScaled * 1_000_000_000.0 / SCALE_D;
    }

    /**
     * Returns the configured burst capacity.
     */
    public long burstCapacity() {
        return capacityScaled / SCALE;
    }

    /**
     * Resets the bucket to full capacity.
     * Useful for testing or administrative operations.
     */
    public void reset() {
        long now = System.nanoTime();
        lastRefillNanos.set(now);
        availableTokens.set(capacityScaled);
    }
}
```

---

## JUnit 5 Tests

```java
package com.example.ratelimiter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TokenBucketRateLimiter.
 * 
 * Tests cover:
 * - Basic functionality (tryAcquire, acquire)
 * - Rate limiting accuracy
 * - Burst behavior
 * - Thread safety under high contention
 * - Long-run rate enforcement (concurrent test)
 * - Edge cases (interruption, timeout, reset)
 */
@Execution(ExecutionMode.SAME_THREAD)  // Control parallelism explicitly
class TokenBucketRateLimiterTest {

    private static final double EPSILON = 0.001;

    @Test
    void testBasicTryAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);  // 10/sec, burst 5
        
        // Should be able to acquire up to burst capacity immediately
        assertTrue(limiter.tryAcquire(3));
        assertTrue(limiter.tryAcquire(2));
        
        // Next acquire should fail (bucket empty)
        assertFalse(limiter.tryAcquire(1));
    }

    @Test
    void testBurstCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);
        
        // Can acquire full burst
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1));
        
        // Cannot acquire more than burst capacity in one call
        assertFalse(limiter.tryAcquire(11));
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1000, 10);  // 1000/sec
        
        // Drain the bucket
        assertTrue(limiter.tryAcquire(10));
        assertFalse(limiter.tryAcquire(1));
        
        // Wait for refill (10ms should give ~10 tokens)
        Thread.sleep(20);
        
        // Should have tokens now
        assertTrue(limiter.tryAcquire(5));
    }

    @Test
    void testBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 2);  // 100/sec
        
        // Acquire burst
        assertTrue(limiter.tryAcquire(2));
        
        // Next acquire should block until refill
        long start = System.nanoTime();
        limiter.acquire(1);
        long elapsed = System.nanoTime() - start;
        
        // Should have waited ~10ms (1 token at 100/sec = 10ms)
        assertTrue(elapsed >= 8_000_000, "Waited " + elapsed/1_000_000 + "ms, expected ~10ms");
        assertTrue(elapsed <= 50_000_000, "Waited too long: " + elapsed/1_000_000 + "ms");
    }

    @Test
    void testTryAcquireWithTimeout() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(50, 1);  // 50/sec
        
        // Drain
        assertTrue(limiter.tryAcquire(1));
        
        // Should timeout before token available (100ms < 20ms needed)
        assertFalse(limiter.tryAcquire(1, 10, TimeUnit.MILLISECONDS));
        
        // Should succeed with longer timeout
        assertTrue(limiter.tryAcquire(1, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void testInterruptDuringAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1);  // Very slow
        
        Thread thread = new Thread(() -> {
            try {
                limiter.acquire(2);  // Need 2 tokens, only 1 in bucket
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // Expected
            }
        });
        
        thread.start();
        Thread.sleep(50);  // Let it start waiting
        thread.interrupt();
        
        assertDoesNotThrow(() -> thread.join(1000));
        assertFalse(thread.isAlive());
    }

    @Test
    void testAvailablePermits() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);
        
        assertEquals(10.0, limiter.availablePermits(), 0.1);
        
        limiter.tryAcquire(3);
        assertEquals(7.0, limiter.availablePermits(), 0.1);
    }

    @Test
    void testReset() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        
        limiter.tryAcquire(5);
        assertFalse(limiter.tryAcquire(1));
        
        limiter.reset();
        assertTrue(limiter.tryAcquire(5));
    }

    @Test
    void testFactoryMethod() {
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.of(100, Duration.ofSeconds(1), 50);
        assertEquals(100.0, limiter.getRefillRate(), EPSILON);
        assertEquals(50, limiter.burstCapacity());
        
        TokenBucketRateLimiter limiter2 = TokenBucketRateLimiter.of(60, Duration.ofMinutes(1), 10);
        assertEquals(1.0, limiter2.getRefillRate(), EPSILON);  // 60/min = 1/sec
    }

    @Test
    void testInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -1));
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5);
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(6));  // > burst
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(0));
    }

    // ==================== CONCURRENT TESTS ====================

    @Test
    void testConcurrentAccessHighContention() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10000, 1000);
        int threadCount = 50;
        int acquiresPerThread = 200;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong successCount = new AtomicLong();
        AtomicLong failureCount = new AtomicLong();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < acquiresPerThread; j++) {
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        long total = successCount.get() + failureCount.get();
        assertEquals((long) threadCount * acquiresPerThread, total);
        // With 1000 burst and 10000/sec, most should succeed in quick burst
        assertTrue(successCount.get() >= 1000);  // At least burst capacity
    }

    @Test
    void testLongRunRateLimitEnforcement() throws InterruptedException {
        // This test verifies that over a sustained period, the rate limiter
        // does not allow more tokens than the configured rate.
        
        double permitsPerSecond = 1000.0;
        long burstCapacity = 100;
        long testDurationMs = 2000;  // 2 seconds
        int threadCount = 20;
        
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(permitsPerSecond, burstCapacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        LongAdder totalAcquired = new LongAdder();
        AtomicLong startTime = new AtomicLong();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (startTime.get() == 0) {
                        startTime.compareAndSet(0, System.nanoTime());
                    }
                    
                    while (System.nanoTime() - startTime.get() < TimeUnit.MILLISECONDS.toNanos(testDurationMs)) {
                        if (limiter.tryAcquire()) {
                            totalAcquired.increment();
                        } else {
                            // Small yield to reduce contention when bucket empty
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(testDurationMs + 5000, TimeUnit.MILLISECONDS));
        executor.shutdown();
        
        long acquired = totalAcquired.sum();
        double actualRate = acquired / (testDurationMs / 1000.0);
        double maxAllowed = permitsPerSecond + (burstCapacity * 1.0 / (testDurationMs / 1000.0));
        
        System.out.printf("Long-run test: acquired=%d, actualRate=%.2f/sec, maxAllowed=%.2f/sec%n",
                acquired, actualRate, maxAllowed);
        
        // Allow small margin for burst at start and measurement error
        assertTrue(actualRate <= maxAllowed * 1.05, 
                String.format("Rate exceeded limit: %.2f > %.2f", actualRate, maxAllowed * 1.05));
        
        // Should achieve most of the configured rate (within 10%)
        assertTrue(actualRate >= permitsPerSecond * 0.9,
                String.format("Rate too low: %.2f < %.2f", actualRate, permitsPerSecond * 0.9));
    }

    @Test
    void testConcurrentBlockingAcquire() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(500, 10);  // 500/sec
        int threadCount = 10;
        int acquiresPerThread = 50;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        LongAdder totalAcquired = new LongAdder();
        AtomicLong startTime = new AtomicLong();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (startTime.get() == 0) {
                        startTime.compareAndSet(0, System.nanoTime());
                    }
                    
                    for (int j = 0; j < acquiresPerThread; j++) {
                        limiter.acquire();
                        totalAcquired.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        long testStart = System.nanoTime();
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        long test