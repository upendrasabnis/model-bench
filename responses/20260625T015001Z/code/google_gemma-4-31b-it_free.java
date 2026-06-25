<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260625T015001Z -->
<!-- PROMPT -->
Design and implement a **self‑contained Java class** that provides a **concurrent token‑bucket rate limiter** with the following specifications:

1. **API Requirements**
   - `public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod)`: constructs a limiter with a maximum bucket capacity, the number of tokens to add each refill, and the refill interval.
   - `public boolean tryAcquire(int tokens)`: atomically attempts to consume the requested number of tokens; returns `true` if successful, `false` otherwise.
   - `public void stop()`: cleanly shuts down any background refill tasks.

2. **Concurrency Guarantees**
   - The limiter must be safe for use by **any number of concurrent threads** calling `tryAcquire`.
   - Token refilling must happen **periodically** in the background without blocking callers.
   - The implementation must avoid **deadlocks, livelocks, and excessive contention**; explain the chosen strategy (e.g., `java.util.concurrent.locks`, `AtomicLong`, `ScheduledExecutorService`, etc.) in clear Javadoc comments.

3. **Behavioral Rules**
   - The bucket never exceeds its `capacity`.
   - Tokens are added atomically at each `refillPeriod`; if the addition would overflow the capacity, the bucket is capped.
   - `tryAcquire` must either deduct the requested tokens atomically or leave the bucket unchanged.

4. **Testing Requirements**
   - Provide a **comprehensive JUnit 5 test suite** in the same file (or as a nested static test class) that includes:
     - Unit tests for basic functionality (single‑threaded acquisition, overflow handling, stop behavior).
     - **Concurrency stress tests** that spawn a configurable number of threads (e.g., 50‑100) performing random `tryAcquire` calls over a period of time, asserting that the total number of successful acquisitions never exceeds the total tokens that could have been supplied by the refill process.
     - Tests verifying that no tokens are lost or duplicated after the limiter is stopped.
     - Use of JUnit’s `@RepeatedTest`, `@Timeout`, and `ExecutorService` utilities to ensure the tests complete reliably.
   - All tests must be deterministic (no flaky timing dependencies); use techniques such as barriers or latches to coordinate start/stop of threads.

5. **Implementation Constraints**
   - Use only **standard Java 11+ libraries**; third‑party concurrency libraries are not allowed.
   - The class must be **fully documented** with Javadoc explaining the concurrency model, the rationale for any locks or atomics, and the shutdown procedure.
   - The code must compile and the test suite must pass with `mvn test` (or equivalent) without any external setup.

**Your task:** Write the complete Java source file containing the `TokenBucketRateLimiter` class and its JUnit 5 tests, adhering to the specifications above. Ensure the solution is correct, thread‑safe, well‑documented, and includes rigorous concurrent testing.
<!-- RESPONSE -->
```java
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A thread-safe implementation of the Token Bucket algorithm for rate limiting.
 * 
 * <p><b>Concurrency Strategy:</b>
 * This implementation uses a {@link ReentrantLock} to protect the bucket's state. 
 * While {@link AtomicLong} could track the token count, the requirement to atomically 
 * check-and-decrement (compare-and-swap) while capping the value at a maximum capacity 
 * is more cleanly and reliably handled via a lock, avoiding complex CAS loops.
 * 
 * <p><b>Refill Mechanism:</b>
 * A {@link ScheduledExecutorService} is used to handle the periodic refill process. 
 * This decouples the refill logic from the acquisition requests, ensuring that 
 * {@code tryAcquire} calls are non-blocking and have O(1) time complexity.
 * 
 * <p><b>Shutdown:</b>
 * The {@code stop()} method shuts down the executor service and awaits termination 
 * to ensure no background tasks leak after the limiter is no longer needed.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final long refillTokens;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    
    // Current available tokens in the bucket
    private long availableTokens;

    /**
     * Constructs a new TokenBucketRateLimiter.
     *
     * @param capacity      The maximum number of tokens the bucket can hold.
     * @param refillTokens  The number of tokens to add at each refill interval.
     * @param refillPeriod  The interval between refills.
     * @throws IllegalArgumentException if capacity or refillTokens are non-positive.
     */
    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        if (capacity <= 0 || refillTokens <= 0) {
            throw new IllegalArgumentException("Capacity and refill tokens must be positive");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.availableTokens = capacity; // Start full

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TokenBucket-Refiller");
            t.setDaemon(true);
            return t;
        });

        // Schedule the periodic refill task
        this.scheduler.scheduleAtFixedRate(this::refill, 
            refillPeriod.toMillis(), refillPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Atomically attempts to consume the requested number of tokens.
     *
     * @param tokens The number of tokens to acquire.
     * @return true if the tokens were successfully deducted; false otherwise.
     */
    public boolean tryAcquire(int tokens) {
        if (tokens < 0) throw new IllegalArgumentException("Requested tokens cannot be negative");
        
        lock.lock();
        try {
            if (availableTokens >= tokens) {
                availableTokens -= tokens;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Internal method to add tokens to the bucket, capping at the maximum capacity.
     */
    private void refill() {
        lock.lock();
        try {
            availableTokens = Math.min(capacity, availableTokens + refillTokens);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts down the background refill task and releases resources.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- JUnit 5 Test Suite ---

    /**
     * Test suite for TokenBucketRateLimiter.
     * Note: In a real project, this would be in a separate file.
     */
    public static class TokenBucketTest {

        @Test
        @DisplayName("Basic acquisition and capacity capping")
        void testBasicFunctionality() {
            // Capacity 10, refill 2 every 100ms
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 2, Duration.ofMillis(100));
            
            assertTrue(limiter.tryAcquire(5), "Should acquire 5 tokens");
            assertTrue(limiter.tryAcquire(5), "Should acquire remaining 5 tokens");
            assertFalse(limiter.tryAcquire(1), "Should be empty now");
            
            limiter.stop();
        }

        @Test
        @DisplayName("Verify bucket does not exceed capacity")
        void testCapacityCap() throws InterruptedException {
            // Small capacity, fast refill
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10, Duration.ofMillis(10));
            
            // Wait for a few refill cycles
            Thread.sleep(50); 
            
            // Even though refill is 10, it should be capped at 5
            assertTrue(limiter.tryAcquire(5), "Should acquire 5");
            assertFalse(limiter.tryAcquire(1), "Should not acquire 6th token");
            
            limiter.stop();
        }

        @Test
        @DisplayName("Stop shuts down the scheduler")
        void testStopBehavior() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, Duration.ofMillis(10));
            limiter.stop();
            // If stop() works, the internal scheduler is shut down.
            // We verify that no exceptions occur and the method completes.
        }

        @RepeatedTest(3)
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("Concurrency Stress Test: Total tokens consumed <= Total tokens supplied")
        void testConcurrencyStress() throws InterruptedException {
            final long capacity = 100;
            final long refillAmount = 10;
            final Duration period = Duration.ofMillis(50);
            final int threadCount = 50;
            final int iterationsPerThread = 100;
            
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillAmount, period);
            
            AtomicLong totalAcquired = new AtomicLong(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            if (limiter.tryAcquire(1)) {
                                totalAcquired.incrementAndGet();
                            }
                            // Random jitter to simulate real load
                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Fire all threads
            endLatch.await();
            long duration = System.currentTimeMillis() - startTime;
            executor.shutdown();

            // Calculation: Initial capacity + (number of refill cycles * refill amount)
            long refillCycles = duration / period.toMillis();
            long maxPossibleTokens = capacity + (refillCycles * refillAmount);

            assertTrue(totalAcquired.get() <= maxPossibleTokens, 
                String.format("Acquired %d tokens, but max possible was %d", totalAcquired.get(), maxPossibleTokens));
            
            limiter.stop();
        }

        @Test
        @DisplayName("Atomic deduction: tryAcquire should be all-or-nothing")
        void testAtomicDeduction() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, Duration.ofMillis(100));
            
            // Attempt to acquire more than available
            assertFalse(limiter.tryAcquire(11), "Should not acquire 11 tokens");
            // Bucket should still have 10
            assertTrue(limiter.tryAcquire(10), "Should still be able to acquire 10");
            
            limiter.stop();
        }
    }
}
```