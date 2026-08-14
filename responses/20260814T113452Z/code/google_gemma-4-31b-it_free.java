<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260814T113452Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU cache with $O(1)$ operations, the standard approach is to combine a `ConcurrentHashMap` for fast lookups and a custom doubly-linked list to track access order.

Since the linked list must be updated on every `get` and `put` operation, the list operations must be synchronized.

### Implementation

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final Lock lock = new ReentrantLock();

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        
        // Dummy head and tail to avoid null checks during pointer updates
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
        
        lock.lock();
        try {
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                if (map.size() >= capacity) {
                    evict();
                }
                addToHead(newNode);
                map.put(key, newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    // --- Internal linked list helpers (Must be called within lock) ---

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

1.  **ConcurrentHashMap**: Provides thread-safe access to the nodes. This allows multiple threads to check if a key exists without blocking.
2.  **ReentrantLock**: The doubly-linked list is not thread-safe. Since moving a node to the head or evicting a node involves updating multiple pointers (`prev` and `next`), these operations must be atomic. I used a `ReentrantLock` to wrap all mutations of the list.
3.  **Atomic Put Logic**: The `put` operation is fully locked. This prevents a race condition where two threads might see the cache is under capacity, both insert a node, and exceed the capacity.
4.  **Complexity**: 
    *   `get`: $O(1)$ (Map lookup + Lock for pointer swap).
    *   `put`: $O(1)$ (Map lookup + Lock for pointer swap/eviction).

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put/Get and Eviction")
    void testBasicLRU() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1)); // 1 becomes MRU
        cache.put(3, "C");              // 2 should be evicted
        
        assertNull(cache.get(2));
        assertEquals("A", cache.get(1));
        assertEquals("C", cache.get(3));
    }

    @Test
    @DisplayName("Stress Test: Concurrent Access and Invariants")
    void testConcurrency() throws InterruptedException {
        int capacity = 100;
        int threadCount = 16;
        int operationsPerThread = 10000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger collisions = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of gets and puts across a range wider than capacity
                        int key = (threadId * 1000) + (j % 200); 
                        if (j % 2 == 0) {
                            cache.put(key, j);
                        } else {
                            cache.get(key);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Invariant 1: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, 
            "Cache size " + cache.size() + " exceeded capacity " + capacity);
    }

    @Test
    @DisplayName("Ensure no data corruption under heavy contention")
    void testContention() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(1);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // All threads fighting over the same single slot
        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> {
                cache.put(1, 1);
                cache.get(1);
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertEquals(1, cache.size());
        assertEquals(1, cache.get(1));
    }
}
```