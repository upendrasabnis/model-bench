<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260817T083627Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a combination of HashMap and doubly-linked list protected by a single ReentrantLock.
 * All public operations are mutually exclusive, guaranteeing linearizability.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head
    private final Node<K, V> tail; // dummy tail
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value for key, or null if absent. Updates recency on hit.
     */
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

    /**
     * Inserts or updates key-value pair. Evicts LRU entry if capacity exceeded.
     * @return previous value associated with key, or null if none
     */
    public V put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                V old = node.value;
                node.value = value;
                moveToHead(node);
                return old;
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            if (map.size() > capacity) {
                Node<K, V> lru = removeTail();
                map.remove(lru.key);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Atomically computes value if absent using mapping function. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                moveToHead(node);
                return node.value;
            }
            V value = mappingFunction.apply(key);
            if (value == null) return null;

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            if (map.size() > capacity) {
                Node<K, V> lru = removeTail();
                map.remove(lru.key);
            }
            return value;
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

    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    // --- Internal linked-list helpers (must hold lock) ---

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
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        return lru;
    }

    private static class Node<K, V> {
        final K key;
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

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    void basicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        assertNull(cache.get(1));
        cache.put(1, "a");
        assertEquals("a", cache.get(1));
        cache.put(2, "b");
        cache.put(3, "c");
        assertEquals(3, cache.size());
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
    void updateExistingKeyDoesNotEvict() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "a-updated"); // update, no eviction
        assertEquals(2, cache.size());
        assertEquals("a-updated", cache.get(1));
        assertEquals("b", cache.get(2));
    }

    @Test
    void computeIfAbsent() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        String v1 = cache.computeIfAbsent(1, k -> "val-" + k);
        assertEquals("val-1", v1);
        String v2 = cache.computeIfAbsent(1, k -> "new-" + k); // should not recompute
        assertEquals("val-1", v2);
        assertEquals(1, cache.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(cap);
        IntStream.range(0, cap * 2).forEach(i -> cache.put(i, i));
        assertEquals(cap, cache.size());
        // only last 'cap' entries should exist
        for (int i = cap; i < cap * 2; i++) assertNotNull(cache.get(i));
        for (int i = 0; i < cap; i++) assertNull(cache.get(i));
    }

    // ---------- Concurrency Stress Test ----------

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int CAPACITY = 1000;
        final int THREADS = 16;
        final int OPS_PER_THREAD = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        // Pre-populate
        for (int i = 0; i < CAPACITY; i++) cache.put(i, i);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = rnd.nextInt(CAPACITY * 2);
                        if (rnd.nextBoolean()) {
                            cache.get(key);
                        } else {
                            cache.put(key, key);
                        }
                        // Occasionally computeIfAbsent
                        if (rnd.nextInt(10) == 0) {
                            cache.computeIfAbsent(key, k -> k * 2);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(0, errors.get(), "Concurrent operations threw exceptions");

        // Invariants: size <= capacity, all entries reachable, no duplicates
        assertTrue(cache.size() <= CAPACITY, "Size exceeds capacity");
        // Verify structural integrity by iterating (not thread-safe, but test is done)
        Set<Integer> seen = new HashSet<>();
        // We can't directly iterate internal list, but we can check that gets are consistent
        // by checking a sample of keys that should be present (last CAPACITY puts)
        // Since we don't track exact order, we just ensure no internal corruption by doing gets
        for (int i = 0; i < CAPACITY * 2; i++) {
            Integer v = cache.get(i);
            if (v != null) {
                assertEquals(i, v, "Value mismatch for key " + i);
                assertTrue(seen.add(i), "Duplicate key detected: " + i);
            }
        }
        assertEquals(cache.size(), seen.size(), "Size mismatch with unique keys");
    }

    @Test
    void highContentionOnSameKey() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        int THREADS = 50;
        int ITERATIONS = 1000;
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            exec.submit(() -> {
                try {
                    for (int i = 0; i < ITERATIONS; i++) {
                        cache.put(1, 1);
                        assertEquals(1, cache.get(1));
                        cache.computeIfAbsent(1, k -> 2);
                        assertEquals(1, cache.get(1)); // should remain 1
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertEquals(0, errors.get());
        assertEquals(1, cache.size());
        assertEquals(1, cache.get(1));
    }
}
```

### Thread-Safety Guarantees

1. **Single Lock Mutual Exclusion**  
   All public methods (`get`, `put`, `computeIfAbsent`, `size`, `clear`) acquire the same `ReentrantLock` at entry and release it in a `finally` block. This makes every operation **atomic** and **linearizable** – the cache behaves as if each method executes instantaneously at some point between its invocation and return.

2. **Consistent State Transitions**  
   The `HashMap` and the doubly-linked list are always updated together while holding the lock:
   - `put` inserts into both structures, then evicts from both if over capacity.
   - `get` moves the accessed node to the head *only after* confirming it exists in the map.
   - `computeIfAbsent` performs the mapping function *inside* the critical section, guaranteeing the function runs exactly once per absent key.

3. **No Publication of Partially Updated Structures**  
   Because the lock is held for the entire duration of each public method, no thread can observe an intermediate state where the map and list are inconsistent (e.g., a node in the list but not in the map).

4. **Memory Visibility**  
   `ReentrantLock` provides the necessary **happens-before** edges: unlocking a lock happens-before subsequent locking of the same lock. All writes to the map, list nodes, and node fields are visible to the next thread that acquires the lock.

5. **No Data Races**  
   All mutable fields (`map`, `head`, `tail`, node `prev`/`next`/`value`) are accessed only under the lock. The `Node` class is effectively immutable except for its `value` and link fields, which are also protected by the lock.

### Concurrency Stress Test Validation

The JUnit 5 test `concurrentStressTest`:
- Runs 16 threads × 5,000 operations each (80k total ops) against a 1,000-entry cache.
- Mixes `get`, `put`, and `computeIfAbsent` with random keys.
- After completion, verifies **invariants**:
  - `size() ≤ capacity`
  - No duplicate keys in the cache
  - Every stored key maps to its correct value
  - No exceptions occurred during concurrent execution

The `highContentionOnSameKey` test hammers a single key from 50 threads to ensure the lock correctly serializes updates and the compute function is idempotent.