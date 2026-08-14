<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260814T113452Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ performance, we must combine a **HashMap** (for fast lookups) with a **Doubly Linked List** (to track usage order).

### Implementation Strategy

To guarantee thread-safety while maintaining $O(1)$ performance:
1.  **Granular Locking:** We use `ReentrantLock` to protect the internal state. While `ConcurrentHashMap` is thread-safe, it doesn't allow us to atomically update both the map and the linked list. Therefore, we wrap the operations in a single lock to ensure the "Map + List" invariant is never broken.
2.  **The Invariant:** Every time an item is accessed (`get`) or added (`put`), it must move to the "head" of the linked list. If the size exceeds capacity, the "tail" must be removed from both the list and the map.

### Java Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        
        // Dummy head and tail to avoid null checks in list operations
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
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
            Node<K, V> node = map.get(key);
            if (node!= null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addNode(newNode);
                
                if (map.size() > capacity) {
                    Node<K, V> lru = popTail();
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

    // Internal List Operations (Must be called within lock)
    private void addNode(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    private Node<K, V> popTail() {
        Node<K, V> res = tail.prev;
        removeNode(res);
        return res;
    }
}
```

---

### JUnit 5 Test Suite

This test suite includes standard functional tests and a high-concurrency stress test using `ExecutorService`.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        assertEquals("B", cache.get(2));
        
        // Eviction test: Adding 3 should evict 1 (since 2 was recently accessed)
        cache.put(3, "C");
        assertNull(cache.get(1));
        assertEquals("C", cache.get(3));
        assertEquals("B", cache.get(2));
    }

    @Test
    @DisplayName("Concurrency Stress Test")
    void stressTest() throws InterruptedException {
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
                        // Mix of puts and gets
                        cache.put(j % (capacity * 2), j);
                        cache.get(j % (capacity * 2));
                        
                        // Invariant Check: Size must never exceed capacity
                        if (cache.size() > capacity) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, errors.get(), "Invariants were violated during concurrent execution!");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity");
    }

    @Test
    @DisplayName("Race condition test for same key")
    void testConcurrentSameKey() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        // Multiple threads hammering the same key
        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> cache.put(1, 1));
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // If locking fails, the linked list pointers might corrupt, 
        // resulting in null or wrong size.
        assertEquals(1, cache.size());
        assertEquals(1, cache.get(1));
    }
}
```

### Explanation of Guarantees

1.  **Atomicity (Thread-Safety):** I used a `ReentrantLock`. While `ConcurrentHashMap` handles thread-safety for the map itself, an LRU cache requires the **Map** and the **Doubly Linked List** to be updated as a single atomic unit. If we used a `ConcurrentHashMap` with a standard `synchronized` block only on the list, a thread could find a node in the map that was simultaneously being removed from the list by another thread, leading to a `NullPointerException`. The single lock ensures that no thread sees the cache in an inconsistent intermediate state.
2.  **Complexity:** 
    *   `get(K)`: $O(1)$ because `HashMap.get` is $O(1)$ and moving a node to the head of a doubly linked list is $O(1)$.
    *   `put(K, V)`: $O(1)$ because `HashMap.put` is $O(1)$ and adding/removing nodes from the linked list is $O(1)$.
3.  **Invariants Maintained:**
    *   **Size Invariant:** `map.size() <= capacity` is always true because eviction happens within the same locked transaction as the insertion.
    *   **Connectivity Invariant:** The `head` and `tail` dummy nodes ensure that `removeNode` and `addNode` never encounter `null` pointers, preventing the most common cause of corruption in concurrent linked lists.