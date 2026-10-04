<!-- model: google/gemma-4-31b-it:free | category: code | run: 20261004T075703Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU cache with $O(1)$ operations, the standard approach is combining a **HashMap** (for fast lookups) and a **Doubly Linked List** (to maintain access order).

To ensure thread-safety under heavy concurrency, we use a `ReentrantLock`. While `ConcurrentHashMap` handles map safety, the linked list is not thread-safe; since any `get` operation modifies the list (moving an item to the head), the entire operation must be atomic.

### Implementation

```java
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantLock lock = new ReentrantLock();

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
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
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                if (map.size() >= capacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                addToHead(newNode);
                map.put(key, newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void evict() {
        Node<K, V> last = tail.prev;
        if (last != head) {
            removeNode(last);
            map.remove(last.key);
        }
    }
}
```

### Thread-Safety Guarantee
1. **Atomicity**: The `ReentrantLock` ensures that the `HashMap` and the `Doubly Linked List` are updated as a single atomic unit. This is critical because a `get` operation is technically a "write" to the linked list (reordering nodes).
2. **Visibility**: The lock creates a *happens-before* relationship. Any thread acquiring the lock is guaranteed to see the most recent updates made by the previous thread that held the lock.
3. **Invariant Protection**: By locking the entire `put` and `get` methods, we prevent race conditions where two threads might try to evict the same tail node or corrupt the linked list pointers.

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ConcurrentLRUCacheTest {

    @Test
    void testBasicLRULogic() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.get(1);       // 1 becomes most recent
        cache.put(3, "C");  // 2 should be evicted
        
        assertNull(cache.get(2));
        assertEquals("A", cache.get(1));
        assertEquals("C", cache.get(3));
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 10;
        int threads = 50;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets across a range larger than capacity
                        int key = (threadId * operationsPerThread + j) % 100;
                        if (j % 2 == 0) {
                            cache.put(key, j);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, failures.get(), "Exceptions occurred during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
    }

    @Test
    void testCapacityInvariant() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(5);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        assertEquals(5, cache.size());
    }
}
```

### Complexity Analysis
- **Time Complexity**: `get` and `put` are $O(1)$ because `HashMap` lookup is $O(1)$ and updating pointers in a doubly linked list is $O(1)$.
- **Space Complexity**: $O(C)$ where $C$ is the capacity.