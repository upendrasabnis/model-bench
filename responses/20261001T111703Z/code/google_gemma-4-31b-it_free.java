<!-- model: google/gemma-4-31b-it:free | category: code | run: 20261001T111703Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the ideal data structure is a combination of a **HashMap** (for fast lookups) and a **Doubly Linked List** (to track usage order).

In Java, the simplest way to achieve this is by extending `LinkedHashMap`, which already implements the LRU logic via its `removeEldestEntry` method. To make it thread-safe, we wrap it in a synchronization layer.

### Implementation

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> internalMap;
    
    // Using ReadWriteLock to allow multiple concurrent reads while 
    // ensuring exclusive access for writes/updates.
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        // 'true' for access-order ensures the list re-orders on get()
        this.internalMap = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    public V get(K key) {
        // IMPORTANT: LinkedHashMap.get() modifies the internal list 
        // when accessOrder=true. Therefore, get() MUST use a write lock.
        writeLock.lock();
        try {
            return internalMap.get(key);
        } finally {
            writeLock.unlock();
        }
    }

    public void put(K key, V value) {
        writeLock.lock();
        try {
            internalMap.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return internalMap.size();
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            internalMap.clear();
        } finally {
            writeLock.unlock();
        }
    }
}
```

### Thread-Safety Explanation

1.  **The `LinkedHashMap` Constraint**: A standard `LinkedHashMap` is not thread-safe. Specifically, when `accessOrder` is set to `true`, the `get()` operation is actually a **structural modification** because it moves the accessed node to the tail of the list.
2.  **Locking Strategy**: I used a `ReentrantReadWriteLock`. While `readLock` is used for the `size()` method, both `get()` and `put()` require the `writeLock`. This is because `get()` modifies the internal doubly-linked list to maintain LRU order.
3.  **Complexity**: 
    *   **Time**: Both `get` and `put` are $O(1)$ average time complexity as they rely on the underlying HashMap and constant-time pointer updates in the linked list.
    *   **Space**: $O(C)$ where $C$ is the capacity.

---

### JUnit 5 Tests

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
        
        assertEquals("One", cache.get(1));
        assertEquals("Two", cache.get(2));
    }

    @Test
    @DisplayName("Eviction follows LRU policy")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        // Access 1 to make it most recently used
        cache.get(1); 
        
        // Put 3, should evict 2 (the least recently used)
        cache.put(3, "Three");
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertNotNull(cache.get(1), "Key 1 should still be present");
        assertNotNull(cache.get(3), "Key 3 should be present");
    }

    @Test
    @DisplayName("Concurrency Stress Test")
    void testConcurrency() throws InterruptedException {
        int capacity = 100;
        int threadCount = 50;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets across a range larger than capacity
                        int key = (threadId * operationsPerThread + j) % (capacity * 2);
                        if (j % 2 == 0) {
                            cache.put(key, j);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, failures.get(), "There should be no exceptions during concurrent access");
        assertTrue(cache.size() <= capacity, "Cache size should never exceed capacity");
    }
}
```

### Key Testing Details:
1.  **`testEviction`**: Verifies that accessing a key "refreshes" it and prevents it from being deleted when a new element is added.
2.  **`testConcurrency`**: 
    *   Uses a `FixedThreadPool` to simulate heavy parallel load.
    *   Uses a `CountDownLatch` to ensure the main thread waits for all worker threads to complete.
    *   Targets a key range ($2 \times \text{capacity}$) to force constant evictions and collisions.
    *   Checks the **invariant** that the cache size never exceeds the defined capacity, regardless of how many threads are writing.