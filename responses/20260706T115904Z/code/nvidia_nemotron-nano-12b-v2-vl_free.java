<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260706T115904Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `ConcurrentTokenBucketRateLimiter` implementing a thread‑safe token‑bucket rate limiter with per‑client configurable refill rates and burst capacities. The class must expose methods `boolean tryAcquire(String clientId, int permits)` and `void addClient(String clientId, double permitsPerSecond, int burstCapacity)`. Use only low‑level concurrency primitives (e.g., `java.util.concurrent.locks`, `java.time.Clock`, `java.util.concurrent.atomic`), without relying on existing rate‑limiter libraries. Document the chosen concurrency strategy, explaining how you avoid race conditions and ensure fairness. Additionally, provide a comprehensive JUnit 5 test suite in the same file that includes:
- Unit tests for basic token consumption and refill behavior.
- Tests for concurrent acquisition from multiple threads simulating high request rates.
- Stress tests that spawn at least 50 threads performing random `tryAcquire` calls over a period of 30 seconds, asserting no negative token counts and reasonable throughput.
- Tests verifying correct per‑client isolation and dynamic addition of new clients at runtime.
The entire program (class and tests) must compile and run without external dependencies beyond JDK 17 and JUnit 5.
<!-- RESPONSE -->
```java
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentTokenBucketRateLimiter {
    private static final Clock clock = Clock.systemUTC();
    private final ConcurrentMap<String, TokenBucket> clientBuckets = new ConcurrentHashMap<>();
    private final Lock globalLock = new ReentrantLock();

    public void addClient(String clientId, double permitsPerSecond, int burstCapacity) {
        TokenBucket bucket = clientBuckets.get(clientId);
        if (bucket == null) {
            globalLock.lock();
            try {
                bucket = clientBuckets.get(clientId);
                if (bucket == null) {
                    bucket = new TokenBucket(permits_per_second, burst_capacity, clock);
                    clientBuckets.put(clientId, bucket);
                }
            } finally {
                globalLock.unlock();
            }
        }
        bucket.refresh();
    }

    public boolean tryAcquire(String clientId, int permits) {
        TokenBucket bucket = clientBuckets.get(clientId);
        if (bucket == null) {
            return false;
        }
        return bucket.tryAcquire(permits);
    }

    static class TokenBucket {
        private final double permits_per_second;
        private final int burst_capacity;
        private final Clock clock;
        private final AtomicLong last_refill_time = new AtomicLong(0);
        private final AtomicInteger current_tokens = new AtomicInteger(0);

        TokenBucket(double permits_per_second, int burst_capacity, Clock clock) {
            this.permits_per_second = permits_per_second;
            this.burst_capacity = burst_capacity;
            this.clock = clock;
            current_tokens.set(burst_capacity);
            last_refill_time.set(clock.millis());
        }

        void refresh() {
            long current_time = clock.millis();
            long delta_ms = current_time - last_refill_time.get();
            long delta_seconds = delta_ms / 1000;
            int added_tokens = (int) (delta_seconds * permits_per_second);
            int new_tokens = current_tokens.get() + added_tokens;
            if (new_tokens > burst_capacity) {
                new_tokens = burst_capacity;
            }
            current_tokens.set(new_tokens);
            last_refill_time.set(current_time);
        }

        boolean tryAcquire(int permits) {
            if (permits <= 0) {
                return true;
            }
            int current = current_tokens.get();
            if (current >= permits) {
                current_tokens.set(current - permits);
                return true;
            }
            return false;
        }
    }

    public static void main(String[] args) {
        // Example usage
        ConcurrentTokenBucketRateLimiter limiter = new ConcurrentTokenBucketRateLimiter();
        limiter.addClient("client1", 2, 10);
        System.out.println(limiter.tryAcquire("client1", 3)); // true
        limiter.addClient("client2", 1, 5);
        System.out.println(limiter.tryAcquire("client2", 1)); // true
        System.out.println(limiter.tryAcquire("client2", 1)); // true
        System.out.println(limiter.tryAcquire("client2", 1)); // false (burst_capacity=5, after 3 acquires, 2 left)
    }
}

// JUnit 5 tests
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentTokenBucketRateLimiterTest {
    private static final Clock TEST_CLOCK = Clock.systemUTC();
    private ConcurrentTokenBucketRateLimiter limiter;
    private static final int NUM_THREADS = 50;
    private static final int TEST_DURATION_SEC = 30;

    @BeforeEach
    void setUp() {
        limiter = new ConcurrentTokenBucketRateLimiter();
        limiter.addClient("client1", 2.0, 10);
        limiter.addClient("client2", 1.0, 5);
    }

    @Test
    void testBasicAcquisition() {
        assertTrue(limiter.tryAcquire("client1", 3));
        assertTrue(limiter.tryAcquire("client1", 7));
        assertFalse(limiter.tryAcquire("client1", 1));
        limiter.addClient("client1", 2.0, 10);
        assertTrue(limiter.tryAcquire("client1", 10));
    }

    @Test
    void testClientIsolation() {
        assertTrue(limiter.tryAcquire("client1", 10));
        assertTrue(limiter.tryAcquire("client2", 5));
        assertFalse(limiter.tryAcquire("client2", 1));
        assertTrue(limiter.tryAcquire("client1", 1));
    }

    @Test
    void testDynamicClientAddition() {
        limiter.addClient("client3", 3.0, 20);
        assertTrue(limiter.tryAcquire("client3", 20));
        limiter.addClient("client4", 0.5, 1);
        assertTrue(limiter.tryAcquire("client4", 1));
    }

    @Test
    void testConcurrentAcquisition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        Callable<Void> task = () -> {
            for (int i = 0; i < 10000; i++) {
                String clientId = "client1";
                if (ThreadLocalRandom.current().nextInt(0, 2) == 0) {
                    clientId = "client2";
                }
                boolean result = limiter.tryAcquire(clientId, 1);
                if (result) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
                // Check for negative tokens
                TokenBucket bucket = limiter.clientBuckets.get(clientId);
                if (bucket != null && bucket.current_tokens.get() < 0) {
                    errorOccurred.set(true);
                    return null;
                }
            }
            return null;
        };

        Future<?>[] futures = new Future[NUM_THREADS];
        for (int i = 0; i < NUM_THREADS; i++) {
            futures[i] = executor.submit(task);
        }

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();

        assertFalse(errorOccurred.get());
        assertTrue(successCount.get() + failureCount.get() > 0);
    }

    @Test
    void testStressTestNoNegativeTokens() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        Callable<Void> task = () -> {
            for (int i = 0; i < 10000; i++) {
                String clientId = ThreadLocalRandom.current().nextInt(0, 2) == 0 ? "client1" : "client2";
                int permits = ThreadLocalRandom.current().nextInt(1, 10);
                boolean result = limiter.tryAcquire(clientId, permits);
                if (result) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
                // Check for negative tokens
                TokenBucket bucket = limiter.clientBuckets.get(clientId);
                if (bucket != null && bucket.current_tokens.get() < 0) {
                    errorOccurred.set(true);
                    return null;
                }
            }
            return null;
        };

        Future<?>[] futures = new Future[NUM_THREADS];
        for (int i = 0; i < NUM_THREADS; i++) {
            futures[i] = executor.submit(task);
        }

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();

        assertFalse(errorOccurred.get());
        assertTrue(successCount.get() > 0);
    }

    @Test
    void testRefillBehavior() throws InterruptedException {
        Clock mockClock = Clock.systemUTC();
        TokenBucket bucket = new TokenBucket(1.0, 10, mockClock);

        bucket.refresh(); // initial tokens = 10
        assertEquals(10, bucket.current_tokens.get());

        mockClock = Clock.offset(mockClock.instant(), Duration.ofSeconds(10));
        bucket.refresh();
        assertEquals(10 + 10 * 1.0, bucket.current_tokens.get());

        mockClock = Clock.offset(mockClock.instant(), Duration.ofSeconds(5));
        bucket.refresh();
        assertEquals(20 + 5 * 1.0, bucket.current_tokens.get());
    }
}
```
