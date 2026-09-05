<!-- model: cohere/north-mini-code:free | category: code | run: 20260905T185434Z -->
<!-- PROMPT -->
Implement a token-bucket rate limiter in Java that is safe for use by many threads, supporting a configurable refill rate and burst capacity, with both blocking acquire and non-blocking tryAcquire. Document the design. Then write JUnit 5 tests, including a concurrent test verifying the long-run rate does not exceed the configured limit.
<!-- RESPONSE -->
# Token Bucket Rate Limiter Implementation

I'll implement a thread-safe token bucket rate limiter in Java with the following features:
- Configurable refill rate and burst capacity
- Thread-safe operations using `ReentrantLock`
- Both blocking `acquire()` and non-blocking `tryAcquire()` methods
- Comprehensive JUnit 5 tests including concurrent rate verification

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBucket {
    private final Lock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    
    private double tokens; // Current number of tokens
    private final double capacity; // Maximum capacity (burst limit)
    private final double refillRate; // Tokens per second
    private long lastRefillTimestamp; // Last time tokens were refilled
    
    /**
     * Creates a new token bucket with specified capacity and refill rate.
     * 
     * @param capacity Maximum number of tokens that can be accumulated (burst capacity)
     * @param refillRate Number of tokens to add per second
     * @throws IllegalArgumentException if capacity or refillRate is negative
     */
    public TokenBucket(double capacity, double refillRate) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity cannot be negative");
        }
        if (refillRate < 0) {
            throw new IllegalArgumentException("Refill rate cannot be negative");
        }
        
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }
    
    /**
     * Ac