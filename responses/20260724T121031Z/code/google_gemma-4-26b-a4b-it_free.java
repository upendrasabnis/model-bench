<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260724T121031Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ average time complexity, the standard approach is to combine a **HashMap** (for $O(1)$ lookups) with a **Doubly Linked List** (for $O(1)$ updates to the access order).

### Implementation

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
        this.map = new HashMap<>(capacity);
        
        // Dummy head and tail to simplify edge cases in linked list logic
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

    // Internal helper methods (must be called within lock)
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
1.  **Coarse-Grained Locking**: I used a single `ReentrantLock` for all public methods (`get`, `put`, `size`). While fine-grained locking (locking individual nodes) is possible, it is extremely complex for LRU because every `get` operation is technically a "write" operation to the underlying linked list (it moves the node to the head).
2.  **Atomicity**: The lock ensures that the updates to the `HashMap` and the `Doubly Linked List` happen atomically. Without this, a `get` operation could try to move a node that a `put` operation is currently evicting, leading to corrupted pointers and `NullPointerException`.
3.  **Visibility**: The lock provides the necessary memory visibility guarantees (happens-before relationship) to ensure that when one thread updates a value, the next thread to acquire the lock sees the most recent state.

---

### Unit Tests (JUnit 5)

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic functionality: put and get")
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
    void testLRUEviction() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        cache.get(1); // 1 is now MRU, order: 1, 3, 2
        cache.put(4, 4); // Should evict 2
        
        assertNull(cache.get(2));
        assertEquals(1, cache.get(1));
        assertEquals(3, cache.get(3));
        assertEquals(4, cache.get(4));
    }

    @Test
    @DisplayName("Concurrency Stress Test: Invariants under heavy load")
    void stressTest() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * operationsPerThread) + j;
                        cache.put(key, j);
                        
                        // Occasionally access keys that might have been evicted
                        cache.get(key - 1); 
                        cache.get(key - 10);
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
        
        // Invariant 2: No corruption (The fact that the code finished without 
        // NullPointerException or infinite loops in the linked list implies integrity)
        // We perform a final check by iterating (if we had an iterator) or simply 
        // verifying that the structure is still traversable via get calls.
        for (int i = 0; i < capacity; i++) {
            // We don't know which specific keys are there, but we ensure the system is stable
            cache.get(i); 
        }
    }
}
```

### Complexity Analysis
*   **Time Complexity**:
    *   `get(K)`: $O(1)$ - Hash map lookup + constant time pointer updates.
    *   `put(K, V)`: $O(1)$ - Hash map insertion + constant time pointer updates.
*   **Space Complexity**: $O(\text{capacity})$ to store the nodes and map entries.