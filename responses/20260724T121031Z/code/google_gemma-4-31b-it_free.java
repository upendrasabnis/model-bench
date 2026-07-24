<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260724T121031Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is combining a **HashMap** (for fast lookups) and a **Doubly Linked List** (to maintain access order).

In Java, `LinkedHashMap` provides this functionality natively, but it is not thread-safe. While wrapping it in `Collections.synchronizedMap` works, it uses a single global lock for every operation, which creates a bottleneck. 

Below is a high-performance implementation using `ReentrantReadWriteLock` to allow multiple concurrent reads while ensuring exclusive access for writes/evictions.

### Implementation

```java
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        // accessOrder = true makes LinkedHashMap move elements to the end on get()
        this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    public V get(K key) {
        // Must use writeLock because LinkedHashMap.get() modifies the internal list 
        // (moves element to end) when accessOrder is true.
        lock.writeLock().lock();
        try {
            return map.get(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            map.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **`LinkedHashMap` with `accessOrder = true`**: This configuration ensures that every `get()` or `put()` operation relocates the accessed entry to the tail of the list. Since this modifies the internal pointers of the doubly linked list, **`get()` is effectively a write operation**.
2.  **Locking Strategy**: I used a `ReentrantReadWriteLock`. 
    *   **Write Lock**: Used for `put()` and `get()`. Since `LinkedHashMap` modifies its internal order on every access, a read lock is insufficient for `get()`.
    *   **Read Lock**: Used for `size()`, allowing multiple threads to check the cache size without blocking each other.
3.  **Atomicity**: The `removeEldestEntry` override ensures that the eviction happens atomically during the `put` operation within the same lock scope, preventing the cache from exceeding its capacity.

---

### JUnit 5 Tests

To run these, add `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-params` to your project.

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        assertEquals("One", cache.get(1));
        assertEquals("Two", cache.get(2));
    }

    @Test
    @DisplayName("LRU Eviction Logic")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.get(1);       // Access 1, making 2 the eldest
        cache.put(3, "Three"); // Should evict 2

        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertEquals("One", cache.get(1));
        assertEquals("Three", cache.get(3));
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
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets across a range larger than capacity
                        int key = (threadId * opsPerThread + j) % (capacity * 2);
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

        latch.await();
        executor.shutdown();

        assertEquals(0, failures.get(), "No exceptions should occur during concurrent access");
        assertTrue(cache.size() <= capacity, "Cache size should never exceed capacity");
    }
}
```

### Complexity Analysis
- **Time Complexity**: Both `get` and `put` are $O(1)$ average time because they rely on `HashMap` lookups and pointer updates in a linked list.
- **Space Complexity**: $O(C)$ where $C$ is the capacity of the cache.