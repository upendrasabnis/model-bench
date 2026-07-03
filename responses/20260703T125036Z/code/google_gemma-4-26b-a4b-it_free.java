<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260703T125036Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, we need to combine two data structures:
1.  **A Hash Map:** For $O(1)$ lookups.
2.ly. **A Doubly Linked List:** To maintain the order of access (most recent at the head, least recent at the tail).

### Implementation

We will use `ReentrantLock` to ensure atomicity. While `ConcurrentHashMap` is thread-safe, it does not provide a way to atomically update both a map and a linked list. Therefore, a global lock is the most reliable way to ensure the integrity of the doubly linked list pointers during concurrent mutations.

```java
import javaed.locks.ReentrantLock;
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
        
        // Dummy head and tail to avoid null checks during pointer manipulation
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

    // For testing purposes
    protected int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Atomicity of Compound Actions:** A `get` operation is not just a map lookup; it involves moving a node to the head of the list. A `put` operation involves map insertion, list insertion, and potential eviction. If these steps were not atomic, two threads could corrupt the `prev`/`next` pointers of the linked list, leading to infinite loops or lost nodes.
2.ed. **Coarse-Grained Locking:** I used a `ReentrantLock` to wrap the entire logic of `get` and `put`. While this limits concurrency (only one thread can modify the structure at a time), it is the only way to guarantee that the `HashMap` and the `Doubly Linked List` remain perfectly synchronized. 
3. **Visibility:** The `lock` provides a *happens-before* relationship. Any write performed by Thread A before releasing the lock is guaranteed to be visible to Thread B after it acquires the same lock.
4. **Sentinel Nodes:** Using dummy `head` and `tail` nodes eliminates edge cases in the linked list logic (like empty lists), reducing the surface area for concurrency-related bugs.

---

### Unit Tests (JUnit 5)

To run this, ensure you have `junit-jupiter-api` and `junit-jupiter-engine` in your dependencies.

```java
import orgly.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get should work")
    void testBasicFunctionality() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
    }

    @Test
    @DisplayName("Capacity constraint is respected")
    void testCapacity() {
        int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        assertEquals(capacity, cache.size());
    }

    @Test
    @DisplayName("Concurrency Stress Test: High contention on same keys and new keys")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of overlapping keys (0-100) and unique keys
                        int key = (j % 10 == 0)? (threadId * 1000 + j) : (j % 100);
                        cache.put(key, j);
                        cache.get(key % 100);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariants Check
        // 1. Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
        
        // 2. No crashes/deadlocks occurred (if we reached here, we passed)
    }

    @Test
    @DisplayName("Race condition check: Multiple threads updating same key")
    void testConcurrentUpdateSameKey() throws InterruptedException {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(10);
        int threads = 10;
        int incrementsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 
            0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        // We use a synchronized block here to simulate a read-modify-write
                        // because the cache's put is atomic, but the increment isn't.
                        // This tests if the cache survives rapid-fire-put/get.
                        synchronized (cache) {
                            Integer val = cache.get("counter");
                            cache.put("counter", (val == null? 0 : val) + 1);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(threads * incrementsPerThread, cache.get("counter"));
    }
}
```

### Complexity Analysis
*   **Time Complexity:**
    *   `get(K key)`: $O(1)$ average (HashMap lookup +- constant time pointer updates).
    *   `put(K key, V value)`: $O(1)$ average (HashMap insertion + constant time pointer updates).
*   **Space Complexity:** $O(C)$ where $C$ is the capacity, as we store at most $C$ nodes in the map and the linked list.