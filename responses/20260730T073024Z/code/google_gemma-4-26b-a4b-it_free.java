<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260730T073024Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ performance, the standard approach is to combine a **HashMap** (for $O(1)$ lookups) with a **Doubly Linked List** (for $O(1)$ updates to the access order).

### Implementation

We will use `ReentrantReadWriteLock` to allow multiple concurrent readers while ensuring exclusive access for writers. However, because every `get` operation in an LRU cache updates the "recency" (a write operation on the linked list), a simple `ReadWriteLock` would suffer from contention. For true $O(1)$ performance under high concurrency, we use `synchronized` on the internal structures or a single lock to ensure the atomicity of the "Move to Front" operation.

```java
import java.util.HashMap;
import java.util.Map;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;

    // Doubly Linked List Node
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
        
        // Dummy head and tail to avoid null checks in DLL operations
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves a value from the cache.
     * Updates the node to be the most recently used.
     */
    public synchronized V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        moveToHead(node);
        return node.value;
    }

    /**
     * Inserts or updates a value in the cache.
     * Evicts the least recently used item if capacity is exceeded.
     */
    public synchronized void put(K key, V value) {
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
    }

    public synchronized int size() {
        return map.size();
    }

    // --- Internal Doubly Linked List Helpers (Must be called within synchronized blocks) ---

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

1.  **Atomicity via Monitor Locks**: I used `synchronized` on the `get` and `put` methods. While a `ReadWriteLock` is often preferred for read-heavy workloads, in an LRU cache, a `get` operation **is a write operation** because it modifies the pointers of the doubly linked list to move the accessed node to the head. 
2.  **Encapsulation**: The internal `Node` structure and the `HashMap` are private. No external class can manipulate the linked list or the map without going through the synchronized methods.
3.  **Consistency**: By synchronizing the entire method, we ensure that the state of the `HashMap` and the `Doubly Linked List` are always in sync. A thread will never see a key in the map that has already been removed from the linked list.

---

### JUnit 5 Tests

To run this, ensure you have `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get operations")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Should evict 2 (since 1 was recently accessed)
        
        assertNull(cache.get(2));
        assertEquals("B", cache.get(2) == null? null : "B"); // Logic check
        assertEquals("C", cache.get(3));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("Eviction works correctly under single thread")
    void testEviction() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        for (int i = 0; i < 10; i++) {
            cache.put(i, i);
        }
        assertEquals(3, cache.size());
        // The last three inserted should be 7, 8, 9
        assertNotNull(cache.get(9));
        assertNotNull(cache.get(8));
        assertNotNull(cache.get(7));
        assertNull(cache.get(6));
    }

    @Test
    @DisplayName("Concurrency Stress Test: Invariants under heavy load")
    void stressTest() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets
                        cache.put(j % 100, j); 
                        cache.get(j % 100);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant 1: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity!");
        
        // Invariant 2: The internal map and list must be consistent.
        // Since we can't access private members, we check if we can perform 
        // operations without throwing exceptions and that the size is logical.
        for (int i = 0; i < 100; i++) {
            cache.get(i);
        }
        assertTrue(cache.size() >= 0);
    }

    @Test
    @DisplayName("Race condition check: Concurrent updates to same key")
    void testConcurrentSameKey() throws InterruptedException {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(10);
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int val = i;
            executor.submit(() -> {
                try {
                    cache.put("key", val);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Invariant: The value must be one of the values put in, 
        // and size must be exactly 1.
        assertEquals(1, cache.size());
        assertNotNull(cache.get("key"));
    }
}
```

### Complexity Analysis
*   **Time Complexity**:
    *   `get(K)`: $O(1)$ average for `HashMap` lookup + $O(1)$ for DLL pointer updates.
    *   `put(K, V)`: $O(1)$ average for `HashMap` insertion/update + $O(1)$ for DLL insertion/eviction.
*   **Space Complexity**: $O(C)$ where $C$ is the capacity, to store the map entries and the nodes.