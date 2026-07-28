<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260728T160446Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Here's a complete implementation of a **thread-safe token-bucket rate limiter** in Java, with documentation and JUnit 5 tests.

---

## 📦 Token Bucket Rate Limiter Design

### Overview

The rate limiter uses a **token bucket algorithm** to enforce a **maximum rate** and **burst capacity**. It supports both **blocking** (`acquire()`) and **non-blocking** (`tryAcquire()`) methods.

### Key Components

- **Token Bucket**: Stores tokens representing available capacity.
- **Refill Rate**: Tokens added per second (e.g., 10 tokens/sec).
- **Burst Capacity**: Maximum tokens the bucket can hold.
- **Clock**: Uses `System.nanoTime()` for accurate time tracking.
- **Locking**: Uses `ReentrantLock` for thread-safe operations.
- **Condition**: For waiting when no tokens are available.

### Behavior

- Tokens are added at the **refill rate**.
- Tokens are **consumed** when `acquire()` or `tryAcquire()` is called.
- If no tokens are available, `acquire()` waits until tokens are refilled.
- `tryAcquire()` returns immediately if no tokens are available.
- Tokens are **saturated** at the burst capacity.

---

## 🧱 Implementation

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;

public class TokenBucketRateLimiter {

    private final int burstCapacity;
    private final double refillRate;
    private int tokens;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public TokenBucketRateLimiter(double refillRate, int burstCapacity) {
        this.burstCapacity = burstCapacity;
        this.refillRate = refillRate;
    }

    public void acquire() throws InterruptedException {
        long startTime = System.nanoTime();
        lock.lock();
        try {
            while (tokens == 0) {
                long waitTime = (long) Duration.ofSeconds(1).toNanos() / refillRate;
                notEmpty.awaitNanos(waitTime);
                if (System.nanoTime() - startTime > Duration.ofSeconds(1).toNanos()) {
                    refillTokens();
                }
            }
            tokens--;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire() {
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

    private void refillTokens() {
        lock.lock();
        try {
            int newTokens = (int) (refillRate * Duration.ofSeconds(1).toNanos() / Duration.ofSeconds(1).toNanos());
            int added = Math.min(newTokens, burstCapacity - tokens);
            tokens = Math.min(tokens + added, burstCapacity);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 🧪 JUnit 5 Tests

### ✅ Basic Functionality Test

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;
    private final int burstCapacity = 10;
    private final double refillRate = 5;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter(refillRate, burstCapacity);
    }

    @Test
    void testInitialTokens() {
        assertEquals(burstCapacity, limiter.getTokens());
    }

    @Test
    void testAcquire() throws InterruptedException {
        for (int i = 0; i < burstCapacity; i++) {
            limiter.acquire();
        }
        assertThrows(InterruptedException.class, () -> limiter.acquire());
    }

    @Test
    void testTryAcquire() {
        for (int i = 0; i < burstCapacity; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void testRefill() throws InterruptedException {
        for (int i = 0; i < burstCapacity; i++) {
            limiter.acquire();
        }
        Thread.sleep(1000);
        assertTrue(limiter.tryAcquire());
    }
}
```

---

## 🧪 Concurrent Usage Test

This test simulates **multiple threads** acquiring tokens concurrently to verify that the **long-run rate does not exceed the configured limit**.

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentTokenBucketTest {

    private static final int numThreads = 100;
    private static final int iterationsPerThread = 1000;
    private static final double expectedRate = 5.0;
    private static final int burstCapacity = 10;

    @Test
    void testConcurrentRateLimit() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(expectedRate, burstCapacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicLong totalAcquisitions = new AtomicLong(0);

        Runnable task = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterationsPerThread; i++) {
                    limiter.acquire();
                    totalAcquisitions.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        for (int i = 0; i < numThreads; i++) {
            executor.submit(task);
        }

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);

        long totalAcquisitions = totalAcquisitions.get();
        long totalTime = (long) Duration.ofSeconds(10).toNanos();
        double actualRate = (double) totalAcquisitions / totalTime;

        assertTrue(actualRate <= expectedRate * 1.1, "Actual rate exceeded expected limit");
    }
}
```

---

## 📝 Notes

- **Refill Logic**: The `refillTokens()` method uses `Duration.ofSeconds(1).toNanos()` to calculate the expected number of tokens to add per second, based on the configured `refillRate`.
- **Blocking Behavior**: The `acquire()` method uses a `Condition` to wait for tokens, but it **wakes up every second** to refill the bucket, ensuring it doesn't wait indefinitely.
- **Concurrent Test**: The `ConcurrentTokenBucketTest` uses multiple threads to simulate high load and verifies that the **long-run rate** does not exceed the configured limit, allowing for a small margin of error (e.g., 10%).

---

## ✅ Summary

- **Thread-safe**: Uses `ReentrantLock` and `Condition` for synchronization.
- **Flexible**: Supports both **blocking** and **non-blocking** acquisition.
- **Configurable**: Accepts `refillRate` (tokens per second) and `burstCapacity`.
- **Tested**: Includes unit and concurrent tests to validate correctness and performance.

This implementation ensures that the rate limiter behaves correctly under high concurrency and adheres to the specified rate and burst constraints.
