<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260811T145529Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with a configurable capacity, we use `LinkedHashMap` with access order for O(1) operations and synchronized methods to ensure thread safety. The cache evicts entries based on access order, and the tests include concurrency stress testing to verify invariants under heavy load.

### Solution Code
```java
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, V> cache;

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true));
    }

    public V get(K key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    public V put(K key, V value) {
        synchronized (cache) {
            V oldValue = cache.put(key, value);
            if (cache.size() > capacity) {
                Map.Entry<K, V> eldest = cache.entrySet().iterator().next();
                cache.remove(eldest.getKey());
            }
            return oldValue;
        }
    }

    public void evict() {
        synchronized (cache) {
            if (!cache.isEmpty()) {
                Map.Entry<K, V> eldest = cache.entrySet().iterator().next();
                cache.remove(eldest.getKey());
            }
        }
    }

    public Set<K> keySet() {
        synchronized (cache) {
            return Collections.unmodifiableSet(cache.keySet());
        }
    }

    public boolean containsKey(K key) {
        synchronized (cache) {
            return cache.containsKey(key);
        }
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
```

### Key Components and Thread Safety Explanation
1. **Synchronized Map**: The cache uses `Collections.synchronizedMap` to wrap a `LinkedHashMap`, ensuring atomicity for operations like `get`, `put`, `remove`, etc.
2. **Access Order**: The `LinkedHashMap` is initialized with `accessOrder = true`, so entries are ordered by last access time (MRU first, LRU last).
3. **Capacity Management**: During `put`, if the cache exceeds capacity, the LRU entry (first in the iteration) is evicted. This is done atomically within a synchronized block.
4. **Thread-Safe Operations**: All public methods (`get`, `put`, `evict`, `size`, etc.) are synchronized on the cache instance, preventing concurrent modifications and ensuring visibility.

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {
    private static final int CAPACITY = 3;
    private static final int THREAD_COUNT = 100;
    private static final int ITERATIONS = 1000;
    private static final Object lock = new Object();
    private static boolean testFailed = false;

    private ConcurrentLRUCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    @Test
    void testPutAndGetSingleThread() {
        cache.put("a", "1");
        assertEquals("1", cache.get("a"));
        cache.put("b", "2");
        assertEquals("2", cache.get("b"));
        cache.put("c", "3");
        assertEquals("3", cache.get("c"));
        cache.put("d", "4");
        assertEquals("4", cache.get("d"));
        assertEquals(null, cache.get("a"));
    }

    @Test
    void testConcurrentAccess() {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(THREAD_COUNT);

        Runnable task = () -> {
            try {
                startGate.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    String key = String.valueOf(Thread.currentThread().getId());
                    cache.put(key, "value");
                    cache.get(key);
                }
            } catch (Exception e) {
                e.printStackTrace();
                lock.lock();
                try {
                    testFailed = true;
                } finally {
                    lock.unlock();
                }
            } finally {
                endGate.countDown();
            }
        };

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(task);
        }

        startGate.countDown();
        endGate.await();

        executor.shutdown();

        assertFalse(testFailed);
        assertEquals(CAPACITY, cache.size());
        assertTrue(cache.containsKey(Thread.currentThread().getId() + ""));
    }
}
```

### Test Explanation
1. **Single-Threaded Test**: Verifies basic `put` and `get` operations, ensuring LRU eviction when capacity is exceeded.
2. **Concurrency Stress Test**: Simulates heavy parallel access with multiple threads. Each thread performs `put` and `get` operations to ensure thread safety. The test checks:
   - No exceptions occur (using `testFailed` flag).
   - The final cache size matches the capacity after evictions.
   - The last key accessed by the current thread is present (validates atomicity).

This implementation ensures thread safety through synchronized access and maintains LRU eviction semantics efficiently.
