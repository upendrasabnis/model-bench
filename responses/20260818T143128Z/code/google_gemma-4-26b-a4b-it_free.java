<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260818T143128Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
This implementation uses a "lazy refill" strategy. Instead of using a background thread to add tokens (which is resource-intensive), we calculate how many tokens should have been added since the last request based on the elapsed time.

### 1. Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Token Bucket rate limiter.
 * 
 * Design:
 * 1. Lazy Refill: Tokens are replenished only when a thread attempts to acquire them.
 *    This avoids the overhead of a background maintenance thread.
 * 2. Locking: Uses a ReentrantLock to ensure atomicity of the refill and consumption logic.
 * 3. Burst Capacity: Defined by 'capacity'. The bucket can hold up to this many tokens.
 * 4. Refill Rate: Defined by 'tokensPerSecond'.
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double tokensPerSecond;
    private final long tokensPerNanosecond;