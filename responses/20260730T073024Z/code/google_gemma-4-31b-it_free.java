<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260730T073024Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is combining a **HashMap** (for fast lookup) and a **Doubly Linked List** (to maintain access order).

In Java, `LinkedHashMap` provides this functionality out of the box, but it is not thread-safe. While `Collections.synchronizedMap` provides thread safety, it locks the entire map for every operation, which limits scalability. For a production-grade concurrent LRU, using a `ReentrantReadWriteLock` or a synchronized wrapper around a `LinkedHashMap` is the most reliable way to ensure the integrity of the linked list pointers during concurrent mutations.

### Implementation

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, V> internalMap;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        // 'true' for access-order: moves accessed elements to the end of the list
        this.internalMap = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    public Optional<V> get(K key) {
        // Note: LinkedHashMap.get() modifies the list order in access-order mode.
        // Therefore, we MUST use a write lock even for 'get' operations.
        writeLock.lock();
        try {
            return Optional.ofNullable(internalMap.get(key));
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

### Thread-Safety Guarantees

1.  **Atomic Mutations**: The `LinkedHashMap` is not thread-safe. Specifically, the `get` operation in access-order mode modifies the internal doubly linked list (moving the accessed node to the tail). I used a `ReentrantReadWriteLock` to ensure that no two threads modify the pointers of the linked list simultaneously, preventing memory corruption or infinite loops.
2.  **Write Lock for Get**: Usually, `get` operations use a read lock. However, because `LinkedHashMap(..., true)` modifies the structure on every read, the `get` method requires a **Write Lock**.
3.  **Visibility**: The use of locks creates a *happens-before* relationship between the thread that puts a value and the thread that subsequently reads it, ensuring memory visibility across CPU cores.
4.  **Eviction Integrity**: The `removeEldestEntry` method is called inside `put`. Since `put` is wrapped in a write lock, the eviction happens atomically with the insertion.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Optional;
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

        assertEquals(Optional.of("One"), cache.get(1));
        assertEquals(Optional.of("Two"), cache.get(2));
    }

    @Test
    @DisplayName("LRU Eviction policy test")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        // Access 1 to make it most recently used
        cache.get(1); 
        
        // Put 3, should evict 2 (the least recently used)
        cache.put(3, "Three");

        assertTrue(cache.get(1).isPresent());
        assertTrue(cache.get(3).isPresent());
        assertFalse(cache.get(2).isPresent(), "Key 2 should have been evicted");
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
                        // Mix of puts and gets across a wider range than capacity
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

        assertEquals(0, failures.get(), "Concurrency exceptions occurred");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
    }
}
```

### Complexity Analysis
- **Time Complexity**: `get` and `put` are $O(1)$ because they rely on a `HashMap` lookup and a constant-time pointer update in the linked list.
- **Space Complexity**: $O(C)$ where $C$ is the capacity, as we store at most $C$ entries in both the map and the list.