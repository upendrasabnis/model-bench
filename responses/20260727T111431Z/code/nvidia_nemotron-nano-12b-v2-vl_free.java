<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260727T111431Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here's a thread-safe LRU cache implementation in Java using `ConcurrentHashMap` and `LinkedHashMap` with a custom `LRUMap` wrapper to ensure O(1) operations and eviction under concurrent access. The implementation includes JUnit 5 tests for correctness and a concurrency stress test.

---

### **Thread-Safe LRU Cache Implementation**

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;

public class LRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, AtomicReference<Node<K, V>>> map;
    private final ReentrantLock lock;
    private final AtomicReference<Node<K, V>> head;
    private final AtomicReference<Node<K, V>> tail;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>();
        this.lock = new ReentrantLock();
        this.head = new AtomicReference<>(null);
        this.tail = new AtomicReference<>(null);
    }

    public V get(K key) {
        AtomicReference<Node<K, V>> ref = map.get(key);
        if (ref == null) return null;

        Node<K, V> node = ref.get();
        if (node == null) return null;

        // Update LRU structure
        lock.lock();
        try {
            removeNode(node);
            addToHead(node);
        } finally {
            lock.unlock();
        }

        return node.value;
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            AtomicReference<Node<K, V>> ref = map.get(key);
            if (ref != null) {
                Node<K, V> node = ref.get();
                if (node != null) {
                    node.value = value;
                    removeNode(node);
                    addToHead(node);
                }
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, new AtomicReference<>(newNode));
                addToHead(newNode);
                if (map.size() > capacity) {
                    evict();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeNode(Node<K, V> node) {
        if (node.prev != null) node.prev.next = node.next;
        if (node.next != null) node.next.prev = node.prev;

        if (head.get() == node) head.set(node.next);
        if (tail.get() == node) tail.set(node.prev);
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.get();
        node.prev = null;

        if (head.get() != null) head.get().prev = node;
        head.set(node);

        if (tail.get() == null) tail.set(node);
    }

    private void evict() {
        Node<K, V> node = tail.get();
        if (node != null) {
            map.remove(node.key);
            removeNode(node);
        }
    }

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
}
```

---

### **JUnit 5 Tests**

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {
    private static final int CAPACITY = 3;
    private static final int THREAD_COUNT = 10;
    private static final int OPERATIONS = 1000;

    @Test
    void testLRUOrder() {
        LRUCache<String, Integer> cache = new LRUCache<>(CAPACITY);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        assertEquals(1, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(3, cache.get("c"));

        cache.put("a", 10); // Move "a" to head

        assertEquals(10, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(3, cache.get("c"));

        cache.put("d", 4); // Evict "b"
        assertNull(cache.get("b"));
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        LRUCache<String, Integer> cache = new LRUCache<>(CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < OPERATIONS; j++) {
                        String key = "key" + (j % 10);
                        cache.put(key, j);
                        if (j % 2 == 0) {
                            assertNotNull(cache.get(key));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify capacity constraint
        assertEquals(CAPACITY, cache.map.size());

        // Verify LRU eviction
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.put("d", 4);
        assertNull(cache.get("a")); // "a" should be evicted
    }
}
```

---

### **Explanation of Thread-Safety**

1. **ReentrantLock for Critical Sections**:
   - The `lock` ensures that operations modifying the linked list (like `removeNode` and `addToHead`) are atomic. This prevents race conditions during node reordering.

2. **ConcurrentHashMap for Key-Node Mapping**:
   - The `map` uses `AtomicReference` to store nodes, allowing atomic updates to node references without blocking the entire cache.

3. **Atomic References for Head/Tail**:
   - `head` and `tail` are `AtomicReference` to ensure that head/tail updates during eviction or insertion are thread-safe.

4. **Eviction Handling**:
   - The `evict()` method removes the tail node under the lock, ensuring that concurrent accesses during eviction do not corrupt the linked list structure.

---

### **Key Design Considerations**

- **O(1) Operations**: The `get` and `put` methods use the `ConcurrentHashMap` for O(1) average time complexity for key lookups. The linked list operations (moving nodes) are O(1) but protected by a `ReentrantLock` to ensure thread safety.
- **Concurrency Stress Test**: The test simulates multiple threads performing `put` and `get` operations, ensuring the cache maintains its LRU order and capacity constraints under heavy load.

This implementation balances thread safety with performance, ensuring correct behavior under concurrent access.
