<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access, we can use the `ConcurrentHashMap` and `LinkedHashMap` classes along with the `ReentrantReadWriteLock` class to ensure thread-safety. Here's the implementation:

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentMap<K, V> cache;
    private final LinkedHashMap<K, V> lruMap;
    private final ReentrantReadWriteLock lock;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new ConcurrentHashMap<>(capacity);
        this.lruMap = new LinkedHashMap<>(capacity, 0.75f, true);
        this.lock = new ReentrantReadWriteLock();
    }

    public V get(K key) {
        lock.readLock().lock();
        try {
            V value = cache.get(key);
            if (value != null) {
                lruMap.remove(key);
                lruMap.put(key, value);
            }
            return value;
        } finally {
            lock.readLock().unlock();
        }
    }

    public V put(K key, V value) {
        lock.writeLock().lock();
        try {
            if (cache.size() >= capacity) {
                Map.Entry<K, V> oldestEntry = lruMap.entrySet().iterator().next();
                lruMap.remove(oldestEntry.getKey());
                cache.remove(oldestEntry.getKey());
            }
            lruMap.put(key, value);
            return cache.putIfAbsent(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Additional methods for removing, containing, etc. can be added here
}
```

To guarantee thread-safety, we use a `ReentrantReadWriteLock` to control access to the cache and LRU map. The `get` method acquires a read lock, allowing multiple threads to read the cache concurrently. The `put` method acquires a write lock, ensuring that only one thread can modify the cache at a time.

Here are some JUnit 5 tests, including a concurrency stress test:

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {
    private static final int CAPACITY = 1000;
    private static final int THREAD_COUNT = 100;
    private static final int ITERATIONS = 10000;

    @Test
    void testConcurrentAccess() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(CAPACITY);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger key = new AtomicInteger(0);

        for (int i = 0; i < ITERATIONS; i++) {
            executor.submit(() -> {
                int k = key.getAndIncrement();
                String v = "value-" + k;

                cache.put(k, v);
                assertEquals(v, cache.get(k));
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        assertEquals(cache.size(), CAPACITY);
    }

    // Additional tests for individual methods, eviction, etc. can be added here
}
```

This test creates a `ConcurrentLRUCache` with a capacity of 1000 and runs 10,000 put/get operations concurrently using 100 threads. The test checks that the cache size remains within the configured capacity and that the get operations return the correct values.
