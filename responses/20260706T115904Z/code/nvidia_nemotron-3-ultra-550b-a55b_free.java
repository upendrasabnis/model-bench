<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260706T115904Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `ConcurrentTokenBucketRateLimiter` implementing a thread‑safe token‑bucket rate limiter with per‑client configurable refill rates and burst capacities. The class must expose methods `boolean tryAcquire(String clientId, int permits)` and `void addClient(String clientId, double permitsPerSecond, int burstCapacity)`. Use only low‑level concurrency primitives (e.g., `java.util.concurrent.locks`, `java.time.Clock`, `java.util.concurrent.atomic`), without relying on existing rate‑limiter libraries. Document the chosen concurrency strategy, explaining how you avoid race conditions and ensure fairness. Additionally, provide a comprehensive JUnit 5 test suite in the same file that includes:
- Unit tests for basic token consumption and refill behavior.
- Tests for concurrent acquisition from multiple threads simulating high request rates.
- Stress tests that spawn at least 50 threads performing random `tryAcquire` calls over a period of 30 seconds, asserting no negative token counts and reasonable throughput.
- Tests verifying correct per‑client isolation and dynamic addition of new clients at runtime.
The entire program (class and tests) must compile and run without external dependencies beyond JDK 17 and JUnit 5.
<!-- RESPONSE -->
```java
/**
 * ConcurrentTokenBucketRateLimiter
 *
 * A thread‑safe, per‑client token‑bucket rate limiter implemented with only
 * low‑level concurrency primitives (ReentrantLock, ReentrantReadWriteLock,
 * AtomicLong, Clock). No external libraries are used.
 *
 * Concurrency strategy:
 *  - A {@link java.util.concurrent.locks.ReentrantReadWriteLock} protects the
 *    client‑to‑bucket map. Readers ({@code tryAcquire}) acquire the read lock
 *    only long enough to retrieve the bucket reference; writers ({@code addClient})
 *    acquire the write lock to insert or replace a bucket. This allows high
 *    concurrency for the common read path while keeping map mutations safe.
 *  - Each {@code Bucket} owns a {@link java.util.concurrent.locks.ReentrantLock}
 *    that guards its mutable state ({@code tokens}, {@code lastRefillNanos}).
 *    The {@code tryAcquire} operation atomically refills the bucket based on the
 *    elapsed time (using the injected {@link java.time.Clock}) and then attempts
 *    to deduct the requested permits. Because the refill and deduction happen
 *    under the same lock, no race condition can cause a negative token count
 *    or lost permits.
 *  - Fairness: The {@code ReentrantLock} instances are created with the default
 *    non‑fair policy, which provides high throughput. The read‑write lock is
 *    also non‑fair, but since readers only hold the lock for a few nanoseconds,
 *    writer starvation is practically impossible. If strict fairness is required,
 *    both locks can be instantiated with {@code true}.
 *
 * The implementation is fully self‑contained and compiles/runs on JDK 17+ with
 * JUnit 5 on the classpath.
 */

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public rate‑limiter class.
 */
public class ConcurrentTokenBucketRateLimiter {

    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final ReadWriteLock mapLock = new ReentrantReadWriteLock();

    /**
     * Creates a limiter using {@link Clock#systemUTC()}.
     */
    public ConcurrentTokenBucketRateLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a limiter with a custom {@link Clock} (useful for deterministic testing).
     */
    public ConcurrentTokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Registers a new client (or replaces an existing one) with the given refill
     * rate and burst capacity.
     *
     * @param clientId         unique identifier for the client
     * @param permitsPerSecond number of permits added per second (can be fractional)
     * @param burstCapacity    maximum number of permits the bucket can hold
     */
    public void addClient(String clientId, double permitsPerSecond, int burstCapacity) {
        Bucket bucket = new Bucket(permitsPerSecond, burstCapacity, clock);
        mapLock.writeLock().lock();
        try {
            buckets.put(clientId, bucket);
        } finally {
            mapLock.writeLock().unlock();
        }
    }

    /**
     * Attempts to acquire the given number of permits for the client.
     *
     * @param clientId client identifier
     * @param permits  number of permits to acquire (must be > 0)
     * @return {@code true} if the permits were granted, {@code false} otherwise
     */
    public boolean tryAcquire(String clientId, int permits) {
        Bucket bucket;
        mapLock.readLock().lock();
        try {
            bucket = buckets.get(clientId);
        } finally {
            mapLock.readLock().unlock();
        }
        if (bucket == null) {
            return false; // unknown client
        }
        return bucket.tryAcquire(permits);
    }

    /**
     * Internal bucket state. All mutable fields are guarded by {@code lock}.
     */
    private static final class Bucket {
        private final double permitsPerSecond;
        private final int burstCapacity;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();

        private double tokens;
        private long lastRefillNanos;

        Bucket(double permitsPerSecond, int burstCapacity, Clock clock) {
            this.permitsPerSecond = permitsPerSecond;
            this.burstCapacity = burstCapacity;
            this.clock = clock;
            this.tokens = burstCapacity;               // start full
            this.lastRefillNanos = clock.nanoTime();
        }

        /**
         * Tries to consume {@code permits} tokens. Refills the bucket first.
         */
        boolean tryAcquire(int permits) {
            lock.lock();
            try {
                refill();
                if (tokens >= permits) {
                    tokens -= permits;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        /** Refills tokens based on elapsed time since last refill. */
        private void refill() {
            long now = clock.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                double newTokens = tokens + elapsedSeconds * permitsPerSecond;
                tokens = Math.min(newTokens, burstCapacity);
                lastRefillNanos = now;
            }
        }

        /** Returns current token count (after refill) for testing. */
        double getTokens() {
            lock.lock();
            try {
                refill();
                return tokens;
            } finally {
                lock.unlock();
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* JUnit 5 test suite (package‑private, discovered by JUnit)                  */
/* -