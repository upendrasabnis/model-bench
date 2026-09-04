<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260904T143601Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is combining a **HashMap** (for fast lookup) and a **Doubly Linked List** (to maintain usage order).

In Java, `LinkedHashMap` provides this functionality out of the box, but it is not thread-safe. To make it concurrent while maintaining the LRU contract, we must synchronize access to the entire map because both `get` and `put` operations modify the internal linked list (reordering elements).

### Implementation

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, V> cache;
    private final Lock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        
        // 'true' for access-order: moves accessed elements to the end of the list
        this.cache = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > ConcurrentLRUCache.this.capacity;
            }
        };
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            cache.put(key, value);
        } finally {
            lock.unlock();
        }
    }

    public Optional<V> get(K key) {
        lock.lock();
        try {
            return Optional.ofNullable(cache.get(key));
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }
}
```

### Thread-Safety Explanation

1.  **The Challenge**: A standard `ConcurrentHashMap` does not track access order. While `LinkedHashMap` tracks order, it is not thread-safe. Crucially, in an LRU cache, a `get()` operation is actually a **write** operation to the internal doubly linked list (it moves the accessed node to the "most recently used" position).
2.  **The Solution**: I used a `ReentrantLock` to wrap every access to the `LinkedHashMap`. 
3.  **Why not `Collections.synchronizedMap`?**: Using an explicit `Lock` is generally more flexible and performant than `synchronized` blocks in high-contention scenarios, though for this specific wrapper, they behave similarly.
4.  **Complexity**: 
    *   `put`: $O(1)$ average (HashMap insertion + List append).
    *   `get`: $O(1)$ average (HashMap lookup + List re-ordering).
    *   **Space**: $O(N)$ where $N$ is the capacity.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("LRU Eviction Policy")
    void testEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "One");
        cache.put(2, "Two");
        
        // Access 1 to make it Most Recently Used
        cache.get(1); 
        
        // Put 3, should evict 2 (Least Recently Used)
        cache.put(3, "Three");

        assertTrue(cache.get(1).isPresent());
        assertTrue(cache.get(3).isPresent());
        assertFalse(cache.get(2).isPresent(), "Key 2 should have been evicted");
    }

    @Test
    @DisplayName("Concurrency Stress Test")
    void testConcurrency() throws InterruptedException {
        int capacity = 100;
        int threads = 16;
        int operationsPerThread = 10000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger collisions = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
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
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Invariant Check: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, 
            "Cache size " + cache.size() + " exceeded capacity " + capacity);
    }
}
```

### Key Testing Strategies used:
1.  **Functional Test**: Verifies that basic `put`/`get` works.
2.  **LRU Logic Test**: Ensures that accessing an element prevents it from being evicted when a new element is added.
3.  **Stress Test**: 
    *   Uses a `FixedThreadPool` to simulate heavy parallel load.
    *   Uses a `CountDownLatch` to ensure the main thread waits for all workers.
    *   **Invariant Verification**: The most critical check in a concurrent cache is ensuring that race conditions didn't cause the size to drift beyond the `capacity` limit.