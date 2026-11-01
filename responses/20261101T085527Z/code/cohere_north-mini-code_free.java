<!-- model: cohere/north-mini-code:free | category: code | run: 20261101T085527Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
Let me explain the design of the TokenBucketRateLimiter:

## Design Documentation

### Core Concept
The token bucket rate limiter is a classic algorithm for controlling the rate of requests. It uses a "bucket" that holds tokens, which are added at a steady rate (refill rate). When a request comes in, it consumes a token if available.

### Key Components

1. **Token Bucket**:
   - Has a maximum capacity (burst size)
   - Tokens are refilled at a constant rate (tokens per second)
   - Tokens accumulate up to the capacity limit

2. **Thread Safety**:
   - Uses `ReentrantLock` for exclusive access to shared state
   - Uses `AtomicLong` for atomic operations on timestamps and token counts
   - Provides both blocking and non-blocking acquire methods

3. **Operations**:
   - `acquire()`: Blocks until a token is available
   - `tryAcquire()`: Non-blocking, returns immediately with boolean
   - `tryAcquire(tokens, timeout, unit)`: Non-blocking with timeout

### Implementation Details

1. **Token Refill Logic**:
   - Tokens are calculated based on elapsed time since last refill
   - Uses atomic operations to ensure thread safety
   - Refill happens in bulk (multiple tokens at once)

2. **Concurrency Handling**:
   - Lock protects critical sections
   - Condition variable for waiting threads
   - Atomic operations for read-modify-write sequences

3. **Error Handling**:
   - Validates input parameters
   - Proper thread interruption handling

### Testing Strategy

1. **Unit Tests**:
   - Basic functionality tests
   - Rate limit accuracy tests
   - Capacity limit tests

2. **Concurrent Tests**:
   - Multiple threads accessing the limiter
   - Verification of thread safety

3. **Long-Run Tests**:
   - Extended duration testing
   - Rate limit verification over time

The implementation ensures that:
- The rate never exceeds the configured limit
- Thread safety is maintained
- Both blocking and non-blocking operations work correctly
- The design is efficient and scalable

Let me fix some issues in the tests and provide a cleaner implementation.I'll implement a comprehensive token bucket rate limiter with proper thread safety, documentation, and comprehensive tests. Let me start with the main implementation:Now let me create the comprehensive test suite: