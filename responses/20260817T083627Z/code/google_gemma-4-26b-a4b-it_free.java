<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260817T083627Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ complexity, we combine a `HashMap` (for $O(1)$ lookups) with a `DoublyLinkedList` (for $O(1)$ updates to the usage order).

### Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

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

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

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
            if (node == null) {
                return null;
            }
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

    // Internal helpers (Must be called while holding lock)
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

### How Thread-Safety is Guaranteed
1.  **Coarse-Grained Locking:** I used a single `ReentrantLock` to protect the entire state of the cache. While `ConcurrentHashMap` allows for high concurrency, an LRU cache requires **atomic updates across two different data structures** (the Map and the Linked List). If we used a `ConcurrentHashMap` and a separate synchronized list, a race condition could occur between checking if a key exists and updating its position in the list.
2.  **Atomicity:** The `lock.lock()` ensures that the `get` (which modifies the list order) and `put` (which modifies both the map and the list) operations are atomic. No thread can see the cache in an inconsistent state (e.g., a node present in the map but disconnected from the list).
3.  **Visibility:** The use of `ReentrantLock` establishes a *happens-before* relationship. Any write performed by thread A before releasing the lock is guaranteed to be visible to thread B after it acquires the same lock.

---

### Unit Tests (JUnit 5)

To run this, add `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` to your dependencies.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic functionality: Put and Get")
    void testBasicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        assertEquals("B", cache.get(2));
        
        cache.put(3, "C"); // Evicts 1
        assertNull(cache.get(1));
        assertEquals("C", cache.get(3));
    }

    @Test
    @DisplayName("Eviction policy: LRU order")
    void testEvictionOrder() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        // Access 1, making 2 the least recently used
        cache.get(1); 
        
        cache.put(4, 4); // Should evict 2
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertNotNull(cache.get(1), "Key 1 should still be present");
        assertNotNull(cache.get(3), "Key 3 should still be present");
        assertNotNull(cache.get(4), "Key 4 should still be present");
    }

    @Test
    @DisplayName("Concurrency Stress Test: Maintain Invariants")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 10;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets with overlapping keys
                        int key = (threadId * opsPerThread + j) % 100; 
                        cache.put(key, key);
                        cache.get(key);
                        
                        // Invariant check: size should never exceed capacity
                        if (cache.size() > capacity) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "Concurrency errors or invariant violations detected!");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
    }
}
```

### Complexity Analysis
*   **Time Complexity:**
    *   `get(K)`: $O(1)$ - Hash map lookup is $O(1)$, and moving a node in a doubly linked list is $O(1)$.
    *   `put(K, V)`: $O(1)$ - Hash map insertion/update is $O(1)$, and adding/removing nodes from the linked list is $O(1)$.
*   **Space Complexity:** $O(C)$ where $C$ is the capacity, as we store at most $C$ nodes in the map and the linked list.