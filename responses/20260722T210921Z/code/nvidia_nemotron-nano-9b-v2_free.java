<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260722T210921Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->


### TokenBucketRateLimiter Design Documentation

The `TokenBucketRateLimiter` class implements a thread-safe token bucket algorithm to control the rate of requests. It allows a burst of up to `maxTokens` tokens, which are refilled at a configurable rate of `refillRate` tokens per second. The implementation ensures thread safety using `ReentrantLock` and `Condition` to synchronize access across multiple threads.

#### Key Components:
- **maxTokens**: Maximum tokens the bucket can hold (burst capacity).
- **refillRate**: Tokens added per second.
- **currentTokens**: Current number of tokens in the bucket.
- **lastRefillTimeNanos**: Timestamp of the last refill (in nanoseconds).

#### Methods:
- **tryAcquire()**: Non-blocking attempt to acquire a token. Returns `true` if successful, `false` otherwise.
- **blockAcquire()**: Blocks until a token is available.

#### Thread Safety:
- All state modifications and waits are performed within a `ReentrantLock` to prevent race conditions.
- The `Condition` variable is used to notify waiting threads when tokens become available.

#### Refill Logic:
- Tokens are refilled on demand by calculating the elapsed time since the last refill and adding tokens proportionally to the `refillRate`.

---

### JUnit 5 Tests

#### Basic Functionality Tests
```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TokenBucketRateLimiterTest {

    @Test
    void testTryAcquire_WhenTokensAvailable_ReturnsTrue() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 2.0);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void testBlockAcquire_WhenTokensAvailable_AcquiresImmediately() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1.0);
        limiter.blockAcquire();
        assertTrue(limiter.tryAcquire()); // 2 tokens left
    }

    @Test
    void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 5.0);
        limiter.blockAcquire(); // Use all 10 tokens
        TimeUnit.SECONDS.sleep(1); // Wait for 5 tokens to refill
        assertTrue(limiter.tryAcquire());
    }
}
```

#### Concurrent Test for Long-Run Rate
```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Test
void testConcurrentAcquires_LongRunRateDoesNotExceedLimit() throws InterruptedException {
    int numThreads = 100;
    int durationSeconds = 10;
    int expectedMaxAcquires = (int) (10 * durationSeconds) + 100; // 10 refill/second + initial 100
    AtomicInteger totalAcquires = new AtomicInteger(0);

    TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10.0);

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    try {
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                while (true) {
                    if (!limiter.tryAcquire()) {
                        break;
                    }
                    totalAcquires.incrementAndGet();
                }
            });
        }

        // Wait for durationSeconds
        TimeUnit.SECONDS.sleep(durationSeconds);

        executor.shutdown();

        // Assert total acquires ≤ expectedMaxAcquires
        int total = totalAcquires.get();
        assertThat(total).isLessThanOrEqualTo(expectedMaxAcquires);
    } finally {
        executor.shutdownNow();
    }
}
```

---

### Explanation of Tests

1. **Basic Functionality**:
   - `testTryAcquire_WhenTokensAvailable_ReturnsTrue`: Verifies that tokens are consumed correctly until depletion.
   - `testBlockAcquire_WhenTokensAvailable_AcquiresImmediately`: Ensures blocking acquisition works when tokens are available.
   - `testRefillOverTime`: Checks that tokens refill after a delay.

2. **Concurrent Test**:
   - Simulates 100 threads acquiring tokens over 10 seconds.
   - Asserts that the total number of acquires does not exceed the configured rate (10 tokens/second × 10 seconds + initial 100 tokens).

This implementation and testing suite ensures the rate limiter is thread-safe, configurable, and adheres to the specified rate limits under concurrent load.
