<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261121T220455Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a doubly-linked list for ordering and HashMap for O(1) access.
 * All operations are guarded by a single ReentrantLock for simplicity and correctness.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final Lock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
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
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
                if (map.size() > capacity) {
                    Node<K, V> tailNode = removeTail();
                    map.remove(tailNode.key);
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

    public boolean containsKey(K key) {
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    // --- Internal linked list operations ---
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

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        removeNode(node);
        return node;
    }

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }
}
```

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {

    @Test
    void basicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1); // access 1 -> MRU
        cache.put(4, "d"); // evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "updated"); // update and move to front
        cache.put(3, "c"); // evicts 2
        assertNull(cache.get(2));
        assertEquals("updated", cache.get(1));
        assertEquals("c", cache.get(3));
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2);
                        if (rnd.nextBoolean()) {
                            cache.put(key, key * 2);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        executor.shutdownNow();

        assertEquals(0, errors.get(), "Concurrent errors occurred");

        // Invariants: size <= capacity, all entries reachable
        assertTrue(cache.size() <= capacity, "Size exceeds capacity: " + cache.size());
        // No internal corruption: iterate via get on all keys in map (not directly accessible, but we can trust size)
        // Since map is private, we rely on size() and no exceptions during operations.
    }

    @Test
    void concurrentReadWriteCorrectness() throws InterruptedException {
        final int capacity = 100;
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity);
        // Pre-fill
        for (int i = 0; i < capacity; i++) cache.put(i, "val" + i);

        int numThreads = 8;
        int opsPerThread = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);
        AtomicInteger mismatches = new AtomicInteger();

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2);
                        if (rnd.nextDouble() < 0.7) { // 70% reads
                            String val = cache.get(key);
                            if (val != null && !val.equals("val" + key)) {
                                mismatches.incrementAndGet();
                            }
                        } else { // 30% writes
                            cache.put(key, "val" + key);
                        }
                    }
                } catch (Exception e) {
                    mismatches.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(0, mismatches.get(), "Data corruption detected");
        assertTrue(cache.size() <= capacity);
    }
}
```

**Thread-Safety Guarantees:**

1. **Single Lock (`ReentrantLock`)**: All public methods (`get`, `put`, `size`, `containsKey`) acquire the same lock before accessing or modifying internal state (`map` and linked list). This serializes all operations, making them atomic.

2. **No Publication of Internal State**: The `Node` class is private and never exposed. The `HashMap` and linked list are fully encapsulated.

3. **Consistent Ordering Updates**: Both `get` (moves node to head) and `put` (adds/updates node) modify the linked list under the same lock, ensuring the LRU order is always consistent.

4. **Capacity Invariant**: The eviction logic (`removeTail` + `map.remove`) executes atomically within `put`, guaranteeing `size() <= capacity` at all observable points.

5. **Visibility**: `ReentrantLock` provides happens-before guarantees, ensuring all threads see the latest state after lock release.

**Trade-offs:**  
Coarse-grained locking limits scalability under high contention. For higher throughput, a striped-lock or lock-free design (e.g., `ConcurrentHashMap` + concurrent linked list) could be used, but at significantly increased complexity and relaxed LRU strictness. This implementation prioritizes correctness and simplicity.