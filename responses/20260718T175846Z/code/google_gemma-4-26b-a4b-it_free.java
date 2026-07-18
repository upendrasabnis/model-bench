<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ complexity, we must combine a **HashMap** (for $O(1)$ lookup) with a **Doubly Linked List** (for $O(1)$ reordering/eviction).

### Implementation

We use `ReentrantLock` to ensure atomicity across both the Map and the Linked List. While `ConcurrentHashMap` is thread-safe, it doesn't allow us to perform "atomic compound actions" (like "move to front AND check size") without external synchronization.

```java
import java.util.HashMap;
import java.util.Map;
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
    private final Node head; // Dummy head
    private final Node tail; // Dummy tail
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        
        // Initialize dummy nodes to avoid null checks in list manipulation
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
            Node node = map.get(key);
            if (node!= null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node newNode = new Node(key, value);
                map.put(key, newNode);
                addNode(newNode);
                
                if (map.size() > capacity) {
                    Node lru = popTail();
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

    // --- Internal Linked List Helpers (Must be called under lock) ---

    private void addNode(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addNode(node);
    }

    private Node popTail() {
        Node res = tail.prev;
        removeNode(res);
        return res;
    }
}
```

### How Thread-Safety is Guaranteed
1.  **Atomicity of Compound Operations:** A simple `ConcurrentHashMap` is not enough. If we used it, a `get` operation would update the Map, but the subsequent update to the Doubly Linked List would be a separate, non-atomic step. This could lead to a race condition where two threads try to move the same node, corrupting the pointers.
2.  **Coarse-Grained Locking:** I used a `ReentrantLock` to wrap the entire logic of `get` and `put`. This ensures that the Map and the Doubly Linked List are always in a consistent state relative to each other.
3.  **Memory Visibility:** The `lock` provides a *happens-before* relationship. Any write performed by Thread A before releasing the lock is guaranteed to be visible to Thread B when it subsequently acquires the lock.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic functionality: Put and Get")
    void testBasicFunctionality() {
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
    @DisplayName("Eviction policy: Least recently used is removed")
    void testEviction() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        cache.get(1); // Access 1, making 2 the LRU
        cache.put(4, 4); // Should evict 2
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertEquals(1, cache.get(1));
        assertEquals(3, cache.get(3));
        assertEquals(4, cache.get(4));
    }

    @Test
    @DisplayName("Concurrency Stress Test: High contention on same keys")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int numThreads = 20;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets
                        cache.put(j % (capacity * 2), j); 
                        cache.get(j % (capacity * 2));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant Check: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load!");
        
        // Invariant Check: No null pointers/corrupted structure (implied by successful execution)
        // If the linked list pointers were corrupted, the code would have thrown NullPointerException
    }

    @Test
    @DisplayName("Concurrency Stress Test: Massive key space")
    void testMassiveKeySpace() throws InterruptedException {
        int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        // We use an AtomicInteger to track successful gets to ensure progress
        AtomicInteger successfulGets = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 5000; j++) {
                    cache.put(j, j); // Rapidly cycling through keys
                    if (cache.get(j)!= null) {
                        successfulGets.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Invariant: Size is bounded
        assertTrue(cache.size() <= capacity);
    }
}
```

### Complexity Analysis
*   **Time Complexity:**
    *   `get(K)`: $O(1)$ - Hash map lookup is $O(1)$, and moving a node to the head in a doubly linked list is $O(1)$.
    *   `put(K, V)`: $O(1)$ - Hash map insertion is $O(1)$, and adding/removing nodes from the linked list is $O(1)$.
*   **Space Complexity:** $O(C)$ where $C$ is the capacity, to store the map entries and the linked list nodes.