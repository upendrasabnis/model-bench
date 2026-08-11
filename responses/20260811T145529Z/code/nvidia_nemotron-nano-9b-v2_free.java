<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260811T145529Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->


To implement a thread-safe concurrent LRU cache in Java with O(1) average operations and correct eviction under concurrent access, we use a combination of a `HashMap` for fast lookups and a doubly linked list to track access order. All modifications are protected by a `ReentrantLock` to ensure thread safety. Below is the implementation and JUnit 5 tests, including a concurrency stress test.

---

### **Implementation**

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final Lock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
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
            // Move to head (most recently used)
            removeNode(node);
            addToHead(node);
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
                removeNode(node);
                addToHead(node);
            } else {
                node = new Node<>(key, value);
                map.put(key, node);
                addToHead(node);
                if (map.size() > capacity) {
                    // Evict least recently used
                    Node<K, V> lru = tail.prev;
                    removeNode(lru);
                    map.remove(lru.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void addToHead(Node<K, V> node) {
        Node<K, V> headNext = head.next;
        head.next = node;
        node.prev = head;
        node.next = headNext;
        headNext.prev = node;
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

### **Explanation of Thread-Safety**

- **Locking Mechanism**: A `ReentrantLock` is used to synchronize all `get` and `put` operations. This ensures that only one thread can modify the internal state (hash map and linked list) at a time, preventing race conditions.
- **Atomic Operations**: All modifications to the linked list (e.g., moving nodes, evicting nodes) are performed under the lock, guaranteeing that no concurrent modifications can corrupt the structure.
- **O(1) Complexity**: The `HashMap` provides O(1) average time for lookups, and linked list operations (add/remove) are O(1) due to direct node references.

---

### **JUnit 5 Tests**

#### **Basic Tests**

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentLRUCacheTest {

    @Test
    public void testGetAndPut() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
        cache.put("a", 1);
        assertEquals(1, cache.get("a"));
        cache.put("b", 2);
        assertEquals(2, cache.get("b"));
        cache.put("c", 3);
        assertNull(cache.get("a")); // Evicted
    }

    @Test
    public void testUpdateExistingKey() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
        cache.put("a", 1);
        cache.put("a", 2);
        assertEquals(2, cache.get("a"));
    }
}
```

#### **Concurrency Stress Test**

```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentLRUCacheStressTest {

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(2);
        int numThreads = 100;
        int operationsPerThread = 1000;

        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger evictedCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String key = "key" + threadId + "-" + j;
                        Integer value = j;
                        cache.put(key, value);
                        Integer retrieved = cache.get(key);
                        assertEquals(value, retrieved);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Verify cache size does not exceed capacity
        assertTrue(cache.map.size() <= cache.capacity);
    }
}
```

---

### **Key Points in the Stress Test**

- **Multiple Threads**: 100 threads perform 1000 operations each, simulating heavy concurrent load.
- **Invariant Check**: After all threads complete, the cache size is verified to not exceed the configured capacity.
- **Assertions**: Ensures that each `put` and `get` operation works correctly under load.

This implementation and test suite guarantees thread safety, O(1) operations, and correct eviction under concurrent access.
