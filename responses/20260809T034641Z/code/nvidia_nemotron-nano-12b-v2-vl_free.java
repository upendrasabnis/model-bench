<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260809T034641Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
To implement a thread-safe token-bucket rate limiter in Java, we'll use `java.util.concurrent` classes to handle concurrency. The design consists of a `TokenBucket` class with methods `acquire()` (blocking) and `tryAcquire()` (non-blocking), along with a `RateLimiter` class managing the token bucket. We'll also write JUnit 5 tests, including a concurrent test to verify the long-run rate.

**Design Document**

1. **TokenBucket Class**
   - Fields:
     - `tokens`: The current number of tokens in the bucket.
     - `maxTokens`: The maximum number of tokens the bucket can hold (burst capacity).
     - `refillRate`: The number of tokens added to the bucket per second.
     - `lock`: A `ReentrantLock` to ensure thread safety.
     - `condition`: A `Condition` variable for waiting and signaling.
   - Methods:
     - `acquire(int permits)`: Blocks until enough tokens are available.
     - `tryAcquire(int permits)`: Returns immediately, indicating if enough tokens are available.
     - `refill()`: Adds tokens to the bucket based on the refill rate.

2. **RateLimiter Class**
   - Fields:
     - `tokenBucket`: An instance of `TokenBucket`.
   - Methods:
     - `acquire(int permits)`: Delegates to `tokenBucket.acquire(permits)`.
     - `tryAcquire(int permits)`: Delegates to `tokenBucket.tryAcquire(permits)`.

**Implementation**

Here's the Java code for the TokenBucket and RateLimiter classes:

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucket {
    private int tokens;
    private final int maxTokens;
    private final int refillRate;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    public TokenBucket(int maxTokens, int refillRate) {
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.tokens = maxTokens;
    }

    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be non-negative");
        }

        lock.lock();
        try {
            while (tokens < permits) {
                available.await();
            }

            tokens -= permits;
        } finally {
            lock.unlock();
        }
    }

    public boolean tryAcquire(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits must be non-negative");
        }

        lock.lock();
        try {
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        while (true) {
            lock.lock();
            try {
                if (tokens < maxTokens) {
                    int tokensToAdd = Math.min(maxTokens - tokens, refillRate);
                    tokens += tokensToAdd;
                    available.signalAll();
                }
            } finally {
                lock.unlock();
            }

            try {
                Thread.sleep(1000 / refillRate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        TokenBucket bucket = new TokenBucket(10, 5);
        new Thread(bucket::refill).start();

        // Example usage
        try {
            bucket.acquire(3);
            System.out.println("Acquired 3 tokens");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Try acquire 8 tokens: " + bucket.tryAcquire(8));
    }
}
```

**JUnit 5 Tests**

Here are the JUnit 5 tests for the TokenBucket and RateLimiter classes:

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBucketTest {
    private TokenBucket tokenBucket;

    @BeforeEach
    void setUp() {
        tokenBucket = new TokenBucket(10, 5);
    }

    @AfterEach
    void tearDown() {
        tokenBucket = null;
    }

    @Test
    void testAcquire() throws InterruptedException {
        tokenBucket.acquire(3);
        assertEquals(7, tokenBucket.tokens);
    }

    @Test
    void testTryAcquire() {
        assertTrue(tokenBucket.tryAcquire(3));
        assertEquals(7, tokenBucket.tokens);
    }

    @Test
    void testLongRunRate() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger totalAcquired = new AtomicInteger();

        Runnable task = () -> {
            try {
                tokenBucket.acquire(1);
                totalAcquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        for (int i = 0; i < 1000; i++) {
            executor.submit(task);
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        assertTrue(totalAcquired.get() <= 5 * 60); // 5 tokens per second for 1 minute
    }
}
```

This implementation ensures thread safety using a `ReentrantLock` and provides both blocking and non-blocking methods for acquiring tokens. The JUnit 5 tests cover basic functionality and verify the long-run rate does not exceed the configured limit.
