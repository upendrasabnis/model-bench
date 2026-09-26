<!-- model: cohere/north-mini-code:free | category: code | run: 20260926T193721Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Overview

This implementation provides a thread-safe token bucket rate limiter that allows for configurable refill rates and burst capacity. The token bucket algorithm is widely used for rate limiting and ensures fair distribution of tokens while allowing for bursts of activity up to the specified capacity.

## Key Features

1. **Thread Safety**: Uses atomic operations and locks to ensure thread safety
2. **Configurable Refill Rate**: Allows setting tokens per second
3. **Burst Capacity**: Supports a maximum number of tokens that can be stored
4. **Blocking Acquire**: Supports blocking acquisition with optional timeout
5. **Non-blocking tryAcquire**: Allows immediate acquisition attempts
6. **Fair Distribution**: Earlier requests get priority (FIFO)

## Implementation Details

The implementation uses:
- `AtomicLong` for current token count to avoid race conditions
- `ReentrantLock` for synchronizing token refilling
- `Semaphore` to implement blocking acquire functionality

The algorithm works by:
1. Adding tokens to the bucket at a constant rate (refill rate)
2. When a request arrives, it tries to acquire tokens from the bucket
3. If there are enough tokens, the request is granted and tokens are consumed
4. If there aren't enough tokens, the request waits (for blocking acquire)
5. Tokens continue to accumulate in the bucket at the refill rate

## Usage Example

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100); // 10 tokens/sec, 100 capacity

// Blocking acquire
limiter.acquire();

// Non-blocking tryAcquire
if (limiter.tryAcquire()) {
    // Token acquired
}

// Acquire with timeout
try {
    if (limiter.acquire(Duration.ofMillis(100))) {
        // Token acquired within timeout
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

## Testing

The test suite includes:
- Basic functionality tests (initial tokens, acquire, tryAcquire)
- Burst capacity tests
- Refill rate tests
- Timeout tests
- Concurrent access tests
- Rate limit enforcement tests
- Long-run rate limit verification tests
- Invalid parameter tests

The concurrent test verifies that the long-run rate does not exceed the configured limit by running multiple threads trying to acquire tokens simultaneously and checking that the total acquired tokens don't exceed the expected rate over time.

## Design Decisions

1. **Atomic Operations**: Using `AtomicLong` for token count ensures thread safety without the overhead of locks for simple read-modify-write operations.

2. **Separate Lock for Refilling**: The `ReentrantLock` is used specifically for token refilling to minimize contention during normal operations.

3. **Semaphore for Blocking**: The `Semaphore` is used to implement blocking acquire, which allows multiple threads to wait efficiently for tokens.

4. **Fair Distribution**: The implementation uses a FIFO approach where earlier requests get priority, ensuring fairness.

5. **Immediate Refill Check**: Tokens are refilled before checking availability to ensure up-to-date token counts.

6. **Timeout Handling**: The `acquire` methods with timeout use the semaphore's `tryAcquire` with timeout, providing efficient timeout handling.

This implementation provides a robust, thread-safe rate limiter that can be used in multi-threaded applications to control access rates and prevent system overload.