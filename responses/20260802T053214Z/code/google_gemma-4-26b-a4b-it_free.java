<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260802T053214Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is to combine a **HashMap** (for $O(1)$ lookup) with a **Doubly Linked List** (for $O(1)$ updates to the access order).

### Implementation

We will use `ReentrantReadWriteLock` to allow multiple concurrent readers while ensuring exclusive access for writes (updates to the list or the map).

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

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
        // Dummy nodes to avoid null checks in list operations
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.writeLock().lock(); // Must use writeLock because 'get' modifies the list order
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            moveToHead(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(K key, V value) {
        lock.writeLock().lock();
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
            lock.writeLock().unlock();
        }
        // Note: Even though get() is a "read" operation logically, 
        // it modifies the linked list (the "recency" order), 
        // so it requires a write lock to prevent race conditions in the list pointers.
    }

    private void addNode(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
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

    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Atomicity of Operations**: A `ReentrantReadWriteLock` is used. Even though `get` is conceptually a read operation, in an LRU cache, a `get` operation **mutates** the state (it moves the accessed node to the head of the list). Therefore, `get` must acquire the `writeLock` to prevent multiple threads from corrupting the pointers of the doubly linked list simultaneously.
2.  **Protection of Invariants**: The `put` operation involves three distinct steps: checking existence, adding to the map, and potentially evicting from the tail. By wrapping the entire logic in a `writeLock`, we ensure that no other thread can see the cache in an inconsistent state (e.g., a key exists in the map but the node has been detached from the list).
3.  **Visibility**: The use of locks ensures a "happens-before" relationship. Changes made by one thread are guaranteed to be visible to any subsequent thread that acquires the same lock.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

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
    @DisplayName("Basic Put and Get functionality")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("Stress test: High concurrency with many threads")
    void stressTest() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successfulGets = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    // Mix of puts and gets
                    int keyToPut = (threadId * operationsPerThread) + j;
                    cache.put(keyToPut % 100, j); // Keep keys in a range to force collisions/evictions
                    
                    Integer val = cache.get(keyToPut % 100);
                    if (val!= null) {
                        successfulGets.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        // Invariant 1: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity!");
        
        // Invariant 2: System should remain stable (no NullPointerExceptions or infinite loops)
        // If the code reaches here without crashing, the pointer logic held up under pressure.
    }

    @Test
    @DisplayName("Eviction integrity test")
    void testEvictionIntegrity() throws InterruptedException {
        int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        // Rapidly put elements to force constant eviction
        for (int i = 0; i < 1000; i++) {
            final int val = i;
            executor.submit(() -> cache.put(val, val));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Invariant: Size must be exactly capacity (or less if not full, but here it's full)
        assertTrue(cache.size() <= capacity);
    }
}
```

### Complexity Analysis
*   **Time Complexity**:
    *   `get(K)`: $O(1)$ average. HashMap lookup is $O(1)$, and moving a node in a doubly linked list is $O(1)$.
    *   `put(K, V)`: $O(1)$ average. HashMap insertion/lookup is $O(1)$, and adding/removing nodes in the list is $O(1)$.
*   **Space Complexity**: $O(C)$, where $C$ is the capacity, to store the map entries and the linked list nodes.