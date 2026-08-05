<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260805T210455Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is combining a **HashMap** (for fast lookups) and a **Doubly Linked List** (to maintain access order).

In Java, `LinkedHashMap` provides this functionality out of the box. However, `LinkedHashMap` is not thread-safe. While `Collections.synchronizedMap` provides thread-safety, it uses a coarse-grained lock on the entire map, which can become a bottleneck. 

For a production-grade implementation, we wrap the `LinkedHashMap` and use a `ReentrantReadWriteLock` or a simple `synchronized` block. Since `get` operations in an LRU cache **modify** the internal list (moving the accessed item to the head), every `get` is effectively a write operation. Therefore, a standard `synchronized` wrapper is the most reliable way to ensure the integrity of the linked list.

### Implementation

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, V> internalMap;

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        
        // accessOrder = true enables LRU behavior in LinkedHashMap
        this.internalMap = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    /**
     * Retrieves an item from the cache.
     * Time Complexity: O(1)
     */
    public synchronized Optional<V> get(K key) {
        return Optional.ofNullable(internalMap.get(key));
    }

    /**
     * Adds or updates an item in the cache. 
     * If capacity is exceeded, the least recently used item is evicted.
     * Time Complexity: O(1)
     */
    public synchronized void put(K key, V value) {
        internalMap.put(key, value);
    }

    /**
     * Returns current size of the cache.
     */
    public synchronized int size() {
        return internalMap.size();
    }

    /**
     * Clears the cache.
     */
    public synchronized void clear() {
        internalMap.clear();
    }
}
```

### Thread-Safety Guarantee
1. **Atomicity**: By marking `get`, `put`, and `size` as `synchronized`, we ensure that only one thread can manipulate the internal `LinkedHashMap` at a time. This prevents race conditions during the pointer updates of the doubly linked list.
2. **Visibility**: The `synchronized` keyword establishes a *happens-before* relationship. Any write to the map by one thread is guaranteed to be visible to any subsequent thread that acquires the lock.
3. **LRU Integrity**: Because `LinkedHashMap` with `accessOrder=true` modifies the internal list during a `get()` call, a read-write lock would not be sufficient (as `get` would require a write lock). A monitor lock ensures the list order remains consistent.

---

### JUnit 5 Tests & Concurrency Stress Test

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get functionality")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        assertEquals("One", cache.get(1).orElse(null));
        assertEquals("Two", cache.get(2).orElse(null));
    }

    @Test
    @DisplayName("Eviction of least recently used item")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        // Access 1, making 2 the LRU
        cache.get(1); 
        
        // Put 3, should evict 2
        cache.put(3, "Three");
        
        assertTrue(cache.get(1).isPresent());
        assertTrue(cache.get(3).isPresent());
        assertTrue(cache.get(2).isEmpty(), "Key 2 should have been evicted");
    }

    @Test
    @DisplayName("Concurrency stress test for invariants")
    void testConcurrency() throws InterruptedException {
        int capacity = 10;
        int threadCount = 50;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets across a range larger than capacity
                        int key = (threadId * operationsPerThread + j) % 100; 
                        if (j % 2 == 0) {
                            cache.put(key, j);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, failures.get(), "No exceptions should occur during concurrent access");
        assertTrue(cache.size() <= capacity, "Cache size should never exceed configured capacity");
    }
}
```

### Complexity Analysis
- **Time Complexity**: `put` and `get` both operate in $O(1)$ average time.
- **Space Complexity**: $O(C)$ where $C$ is the capacity of the cache.
- **Lock Contention**: Under extremely high contention, the `synchronized` block may become a bottleneck. For scenarios requiring extreme throughput, one could implement a "Striped LRU" (dividing the cache into segments), similar to how `ConcurrentHashMap` was implemented prior to Java 8.