<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260709T082632Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ performance, we must combine a **HashMap** (for fast lookups) with a **Doubly Linked List** (to maintain access order).

### Implementation

The core challenge is that while `ConcurrentHashMap` is thread-safe, the link between the map and the linked list is not atomic. To ensure $O(1)$ and correctness, we use a `ReentrantLock` to protect the entire structure during mutations.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private class Node {
        K key;
        V value;
        Node prev;
        Node next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node> map;
    private final Node head;
    private final Node tail;
    private final Lock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        
        // Dummy head and tail to simplify edge cases (empty list logic)
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node node = map.get(key);
            if (node == null) return null;
            
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            Node existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToHead(existing);
            } else {
                Node newNode = new Node(key, value);
                map.put(key, newNode);
                addToHead(newNode);

                if (map.size() > capacity) {
                    Node lru = tail.prev;
                    removeNode(lru);
                    map.remove(lru.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    // --- Internal Doubly Linked List Helpers (Must be called under lock) ---

    private void addToHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addToHead(node);
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Atomicity of Composite Operations**: A simple `ConcurrentHashMap` only guarantees atomicity for individual map operations. However, an LRU cache requires a "check-then-act" pattern: *If key exists, move to head; else, add and potentially evict.* Without a global lock, two threads could both see the cache is at capacity and both attempt to evict the same tail node, leading to a corrupted linked list or a `NullPointerException`.
2.  **Coarse-Grained Locking**: We use a `ReentrantLock` to wrap the entire logic of `get` and `put`. This ensures that the internal state (the `HashMap` and the `Doubly Linked List`) is always consistent. While this limits throughput compared to a lock-free structure, it is the standard way to ensure $O(1)$ correctness for LRU, as the linked list pointers are highly contended.
3.  **Memory Visibility**: The `lock` provides a *happens-before* relationship. Any write performed by Thread A before releasing the lock is guaranteed to be visible to Thread B after it acquires the same lock.

---

### JUnit 5 Tests

These tests cover basic functionality and a high-concurrency stress test using `ExecutorService`.

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
        
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals("A", cache.get(1));
    }

    @Test
    @DisplayName("Concurrency Stress Test: Integrity under heavy load")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * operationsPerThread + j) % 100; 
                        cache.put(key, key);
                        
                        Integer val = cache.get(key);
                        // If value exists, it must be correct
                        if (val != null && val != key) {
                            errors.incrementAndGet();
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

        assertEquals(0, errors.get(), "Errors detected during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity");
    }

    @Test
    @DisplayName("LRU Eviction Order Test")
    void testEvictionOrder() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        // Access 1, making 2 the oldest
        cache.get(1);
        
        cache.put(4, 4); // Should evict 2
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertNotNull(cache.get(1), "Key 1 should still be present");
        assertNotNull(cache.get(3), "Key 3 should still be present");
        assertNotNull(cache.get(4), "Key 4 should still be present");
    }
}
```

### Key Test Invariants Explained:
1.  **Capacity Invariant**: In the stress test, we assert `cache.size() <= capacity`. This ensures the eviction logic doesn't "leak" nodes.
2.  **Value Consistency**: We verify `val == key`. In a race condition where pointers are corrupted, a `get` might return a value belonging to a different key.
3.  **Exception Safety**: The `try-catch` inside the threads ensures that if the internal linked list pointers become `null` (due to a race), the test fails explicitly.