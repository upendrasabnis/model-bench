<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260727T111431Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU cache with $O(1)$ complexity, the standard approach is combining a **HashMap** (for fast lookup) with a **Doubly Linked List** (to maintain access order). 

In Java, the simplest way to achieve this is by extending `LinkedHashMap`, which already implements the LRU logic via `removeEldestEntry`. To make it thread-safe, we wrap it in a synchronization layer.

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
    // ensuring exclusive access for writes (puts and LRU updates).
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        // accessOrder = true enables LRU behavior (moves accessed element to end)
        this.internalMap = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    public Optional<V> get(K key) {
        // IMPORTANT: In a LinkedHashMap with accessOrder=true, get() 
        // modifies the list structure. Therefore, it requires a WRITE lock.
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
}
```

### Thread-Safety Guarantee
1. **Atomic Structural Changes**: `LinkedHashMap` is not thread-safe. Since both `put` and `get` (with `accessOrder=true`) modify the internal doubly linked list to track the "Least Recently Used" item, they are structural modifications.
2. **Exclusive Locking**: I used a `ReentrantReadWriteLock`. While `readLock` is used for the `size()` method, both `get` and `put` use the `writeLock`. This prevents race conditions where two threads might attempt to rewire the same node in the linked list simultaneously, which would otherwise lead to infinite loops or `NullPointerException`.
3. **Complexity**: The `HashMap` provides $O(1)$ lookup and the `DoublyLinkedList` provides $O(1)$ removal/insertion, meeting the complexity requirement.

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your dependencies.

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
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1).orElse(null));
        assertEquals("B", cache.get(2).orElse(null));
    }

    @Test
    @DisplayName("LRU Eviction Policy")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.get(1);       // Access 1, making 2 the LRU
        cache.put(3, "C");  // Should evict 2

        assertTrue(cache.get(1).isPresent());
        assertTrue(cache.get(3).isPresent());
        assertTrue(cache.get(2).isEmpty(), "Key 2 should have been evicted");
    }

    @Test
    @DisplayName("Concurrency Stress Test")
    void testConcurrency() throws InterruptedException {
        int capacity = 100;
        int threadCount = 50;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets across a wider range than capacity
                        int key = (threadId * opsPerThread + j) % (capacity * 2);
                        if (j % 2 == 0) {
                            cache.put(key, j);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "Concurrent operations threw exceptions");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
    }
}
```

### Analysis of Stress Test
- **Parallelism**: 50 threads perform 50,000 operations total.
- **Contention**: By using a key range of `capacity * 2`, we force frequent evictions and collisions, stressing the `writeLock` and the internal `LinkedHashMap` structural updates.
- **Invariants**: The test verifies that no `ConcurrentModificationException` occurs and that the `size()` never exceeds the configured capacity, confirming the atomicity of the eviction logic.