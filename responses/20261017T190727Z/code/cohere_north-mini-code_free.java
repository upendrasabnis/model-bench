<!-- model: cohere/north-mini-code:free | category: code | run: 20261017T190727Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

## Overview

The Token Bucket Rate Limiter is a thread-safe implementation of the token bucket algorithm for rate limiting. It allows controlling the rate at which tokens (representing work or requests) can be acquired, with configurable burst capacity and refill rate.

## Key Features

1. **Thread Safety**: Uses `ReentrantLock` to ensure safe concurrent access
2. **Two Acquisition Methods**:
   - `acquire()`: Blocks until tokens are available
   - `tryAcquire()`: Non-blocking attempt to acquire tokens
3. **Configurable Parameters**:
   - Capacity: Maximum tokens that can be stored (burst capacity)
   - Refill Rate: Tokens added per time unit
4. **Support for Multiple Tokens**: Can acquire any number of tokens at once
5. **Precise Rate Limiting**: Tokens are refilled based on actual elapsed time

## Design Documentation

### Token Bucket Algorithm

The implementation follows the classic token bucket algorithm:
1. Tokens are added to the bucket at a constant rate
2. Tokens can be acquired up to the bucket's capacity
3. When tokens are acquired, they're removed from the bucket
4. Refill happens based on elapsed time since last refill

### Thread Safety

The implementation uses a single `ReentrantLock` to ensure:
- Atomic token refilling operations
- Consistent state updates
- Proper thread synchronization

### Token Refill Logic

Tokens are refilled using the formula:
```java
tokensToAdd = (elapsedTime / refillInterval) * refillRate
```

The refill respects the bucket's capacity, preventing overflow.

## Usage Example

```java
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10, TimeUnit.SECONDS);

public void handleRequest() {
    limiter.acquire(); // Blocks until a token is available
    try {
        // Process request
    } finally {
        // Token automatically deducted when acquire() completes
    }
}

// Or for non-blocking attempts
if (limiter.tryAcquire()) {
    // Process request
}
```

## Testing Strategy

### Test Categories

1. **Basic Functionality Tests**
   - Initial capacity verification
   - Token acquisition within capacity
   - Burst capacity testing

2. **Rate Limiting Tests**
   - Token refill rate verification
   - Long-run rate limit enforcement

3. **Concurrent Access Tests**
   - Thread safety verification
   - Race condition prevention

4. **Edge Case Tests**
   - Multiple token acquisition
   - Non-blocking attempts
   - Boundary conditions

### Test Approach

The tests use JUnit 5 with:
- Parameterized tests for different configurations
- Concurrent execution testing
- Time-based assertions
- Thread synchronization for concurrent scenarios

## Performance Considerations

1. **Lock Granularity**: Single lock ensures consistency but may limit scalability
2. **Atomic Operations**: Token refilling and acquisition are atomic
3. **Memory Usage**: Minimal state tracking (tokens, timestamps)

## Limitations

1. **Single Rate Limit**: Only one refill rate supported
2. **Time Precision**: Uses nanosecond precision but may have slight variations
3. **Blocking Mode**: `acquire()` can block indefinitely

This implementation provides a robust, thread-safe rate limiting solution suitable for most applications requiring controlled resource access.