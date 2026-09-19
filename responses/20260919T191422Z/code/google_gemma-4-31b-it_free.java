<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260919T191422Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here is a professional implementation of a thread-safe Concurrent LRU Cache.

### Implementation

To achieve $O(1)$ operations, we combine a `ConcurrentHashMap` for fast lookups and a custom doubly-linked list to maintain access order. To ensure thread-safety without locking the entire map (which would kill performance), we use a `ReentrantLock` specifically to protect the linked list operations.

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();
    
    // Dummy head and tail to avoid null checks in the linked list
    private final Node<K, V> head;
    private final Node<K, V> tail;

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
        this.map = new ConcurrentHashMap<>(capacity);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;

        lock.lock();
        try {
            moveToHead(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    public void put(K key, V value) {
        Node<K, V> node = map.get(key);

        if (node != null) {
            lock.lock();
            try {
                node.value = value;
                moveToHead(node);
            } finally {
                lock.unlock();
            }
        } else {
            Node<K, V> newNode = new Node<>(key, value);
            lock.lock();
            try {
                // Double-check if another thread inserted it while we waited for lock
                if (map.containsKey(key)) {
                    Node<K, V> existing = map.get(key);
                    existing.value = value;
                    moveToHead(existing);
                    return;
                }

                if (map.size() >= capacity) {
                    Node<K, V> lru = tail.prev;
                    removeNode(lru);
                    map.remove(lru.key);
                }
                
                addNode(newNode);
                map.put(key, newNode);
            } finally {
                lock.unlock();
            }
        }
    }

    public int size() {
        return map.size();
    }

    // Internal helper methods - MUST be called within lock
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    private void addNode(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}
```

---

### Thread-Safety Guarantee

1.  **ConcurrentHashMap**: Handles the $O(1)$ lookup and ensures that the structural integrity of the map is maintained during concurrent reads and writes.
2.  **Fine-Grained Locking**: While the map is thread-safe, the **ordering** (the linked list) is not. I used a `ReentrantLock` to wrap all modifications to the doubly-linked list (`addNode`, `removeNode`, `moveToHead`).
3.  **Double-Checked Locking**: In the `put` method, we check if the key exists before locking. However, once the lock is acquired, we check again. This prevents a race condition where two threads try to insert the same key simultaneously, which would otherwise lead to duplicate nodes in the list or exceeding the capacity.
4.  **Sentinel Nodes**: The use of dummy `head` and `tail` nodes eliminates the need to check for `null` during node removal/addition, reducing the critical section's complexity and potential for `NullPointerException` under race conditions.

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

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
        cache.put(3, "C"); // Evicts 2 (since 1 was accessed)
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
    }

    @Test
    @DisplayName("Stress Test: Concurrent access and invariant check")
    void testConcurrency() throws InterruptedException {
        int capacity = 100;
        int threadCount = 16;
        int operationsPerThread = 10000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
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
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "Exceptions occurred during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
    }

    @Test
    @DisplayName("Ensure LRU eviction order is maintained")
    void testLRUEviction() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        cache.get(1); // 1 is now MRU, 2 is LRU
        cache.put(4, 4); // Should evict 2
        
        assertNotNull(cache.get(1));
        assertNull(cache.get(2));
        assertNotNull(cache.get(3));
        assertNotNull(cache.get(4));
    }
}
```

### Complexity Analysis
- **Time Complexity**: 
    - `get(K key)`: $O(1)$ average. Map lookup is $O(1)$, and moving a node in a linked list is $O(1)$.
    - `put(K key, V value)`: $O(1)$ average. Map insertion/update is $O(1)$, and list maintenance is $O(1)$.
- **Space Complexity**: $O(C)$ where $C$ is the capacity.