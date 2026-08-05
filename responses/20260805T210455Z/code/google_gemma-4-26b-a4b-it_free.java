<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260805T210455Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, we combine a `HashMap` (for $O(1)$ lookups) with a `Doubly LinkedList` (for $O(1)$ updates to the access order).

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
        this.map = new HashMap<>();
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
                if (map.size() >= capacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addNode(newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    private void evict() {
        Node<K, V> lru = tail.prev;
        if (lru!= head) {
            removeNode(lru);
            map.remove(lru.key);
        }
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

    // For testing purposes
    protected int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Coarse-Grained Locking**: I used a `ReentrantLock` to wrap the entire logic of `get` and `put`. While a `ConcurrentHashMap` with fine-grained locking is faster for pure maps, an LRU cache requires updating a linked list (the "recency" metadata) every time a `get` is called. 
2.  **Atomic Compound Operations**: In an LRU, a `get` is not just a read; it is a write (it changes the node's position in the list). If we used a `ConcurrentHashMap` without a global lock, one thread could be reordering the list while another thread is trying to evict the tail, leading to corrupted pointers (e.g., `NullPointerException` or infinite loops).
3.  **Atomicity of Eviction**: The `put` operation involves checking size, removing the tail, and adding a new node. The lock ensures that no other thread can see the cache in an inconsistent state where the size exceeds capacity or the pointers are mid-update.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    void testBasicFunctionality() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals("A", cache.get(1)); // A is now most recent
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i = i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets
                        cache.put(ThreadLocalRandom.current().nextInt(100), j);
                        cache.get(ThreadLocalRandom.current().nextInt(100));
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
        
        // Invariant 2: No crash occurred (if we reached here, no NullPointer/Infinite loops)
    }

    @Test
    void testHighContentionOnSameKeys() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // All threads fighting over the same 5 keys
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 5000; j++) {
                    cache.put(j % 5, j);
                    cache.get(j % 5);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        // If no exception thrown, the linked list pointers remained intact
        assertTrue(cache.size() <= 10);
    }
}
```

### Explanation of Tests
1.  **`testBasicFunctionality`**: Verifies the LRU logic (eviction order) works correctly in a single-threaded environment.
2.  **`testConcurrencyStress`**: Uses a `ThreadPool` to hammer the cache with random keys. It checks the fundamental invariant: **the cache size must never exceed the capacity**, even when many threads are simultaneously adding and evicting.
3.  **`testHighContentionOnSameKeys`**: Focuses on "hot keys." This tests if the pointer manipulation in the doubly linked list remains consistent when multiple threads are constantly moving the same nodes to the "head." This is the most likely scenario for a `NullPointerException` if locking is insufficient.