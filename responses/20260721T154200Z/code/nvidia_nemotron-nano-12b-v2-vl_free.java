<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260721T154200Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with O(1) average time complexity and correct eviction under concurrent access, we use a combination of `LinkedHashMap` for efficient LRU management and `ReentrantReadWriteLock` for thread safety. Here's the implementation, explanation, and tests:

### Java Implementation
```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, V> cache;
    private final ReentrantReadWriteLock lock;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
        this.lock = new ReentrantReadWriteLock();
    }

    public V get(K key) {
        lock.readLock().lock();
        try {
            return cache.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    public V put(K key, V value) {
        lock.writeLock().lock();
        try {
            return cache.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

### Explanation of Thread Safety
1. **ReentrantReadWriteLock**: 
   - **Read Operations (get, size)**: Allowed to proceed concurrently using read locks.
   - **Write Operations (put)**: Exclusive write lock ensures atomic modifications.
2. **LinkedHashMap with LRU Policy**:
   - Maintains insertion order and allows O(1) eviction via `removeEldestEntry` override.
   - The `true` access-order parameter ensures LRU behavior.

### JUnit 5 Tests
```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ConcurrentLRUCacheTest {
    private static final int CAPACITY = 5;
    private ConcurrentLRRUCache<String, Integer> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    @Test
    void testBasicLRU() {
        cache.put("1", 1);
        cache.put("2", 2);
        cache.put("3", 3);
        assertEquals(1, cache.get("1"));
        assertEquals(null, cache.get("4"));
        cache.put("4", 4);
        assertEquals(null, cache.get("1")); // Evicted
    }

    @Test
    void testConcurrentReads() {
        ExecutorService es = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        Runnable task = () -> {
            try {
                latch.await();
                int val = cache.get("test");
                assertEquals(0, val); // Assuming initial value is null
                results.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < 100; i++) {
            es.submit(task);
        }
        latch.countDown();
        es.shutdown();
        assertEquals(100, results.get());
    }

    @Test
    void testConcurrentWrites() {
        ExecutorService es = Executors.newFixedSizeThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1);
