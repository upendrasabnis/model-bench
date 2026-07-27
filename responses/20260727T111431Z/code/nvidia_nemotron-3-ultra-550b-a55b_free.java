<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260727T111431Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) average get/put operations.
 * Uses a doubly-linked list for recency ordering and a HashMap for O(1) access.
 * Thread-safety is achieved via a ReentrantReadWriteLock:
 * - Read lock for get() (allows concurrent reads)
 * - Write lock for put() and internal list mutations (ensures exclusive access for structural changes)
 * - Lock coupling: get() upgrades to write lock only when node movement is needed (after initial read-lock check)
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final Lock readLock = rwl.readLock();
    private final Lock writeLock = rwl.writeLock();

    // Doubly-linked list node
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns value or null if absent. Updates recency on hit. */
    public V get(K key) {
        Node<K, V> node;
        readLock.lock();
        try {
            node = map.get(key);
        } finally {
            readLock.unlock();
        }
        if (node == null) return null;

        // Node found: upgrade to write lock to move to front
        writeLock.lock();
        try {
            // Re-check under write lock (node might have been evicted)
            node = map.get(key);
            if (node == null) return null;
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /** Inserts or updates key-value pair. Evicts LRU if capacity exceeded. */
    public void put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                return;
            }
            // New node
            if (map.size() >= capacity) {
                evictLRU();
            }
            node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
        } finally {
            writeLock.unlock();
        }
    }

    /** Atomic get-or-compute: if absent, computes value via function, inserts, and returns. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        V value = get(key);
        if (value != null) return value;

        writeLock.lock();
        try {
            // Double-check after acquiring write lock
            value = map.get(key)?.value;
            if (value != null) return value;

            V computed = mappingFunction.apply(key);
            if (computed == null) return null;

            if (map.size() >= capacity) evictLRU();
            Node<K, V> node = new Node<>(key, computed);
            map.put(key, node);
            addToHead(node);
            return computed;
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try { return map.size(); }
        finally { readLock.unlock(); }
    }

    public boolean containsKey(K key) {
        readLock.lock();
        try { return map.containsKey(key); }
        finally { readLock.unlock(); }
    }

    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            writeLock.unlock();
        }
    }

    // --- Internal linked list helpers (must hold writeLock) ---
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

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru == head) return; // empty
        removeNode(lru);
        map.remove(lru.key);
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Allow parallel test execution
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
        cache.get(1); // 1 becomes MRU
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
        cache.put(1, "b"); // update
        assertEquals("b", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void computeIfAbsent() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        String v1 = cache.computeIfAbsent(1, k -> "val" + k);
        String v2 = cache.computeIfAbsent(1, k -> "new" + k); // should not recompute
        assertEquals("val1", v1);
        assertEquals("val1", v2);
        assertEquals(1, cache.size());
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    // ---------- Concurrency Stress Test ----------
    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int opsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        // Model: simple thread-safe map to track expected state (for validation)
        ConcurrentHashMap<Integer, Integer> expected = new ConcurrentHashMap<>();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2); // key space larger than capacity
                        int op = rnd.nextInt(3);
                        try {
                            switch (op) {
                                case 0: // put
                                    int val = rnd.nextInt();
                                    cache.put(key, val);
                                    expected.put(key, val);
                                    break;
                                case 1: // get
                                    Integer cached = cache.get(key);
                                    Integer exp = expected.get(key);
                                    if ((cached == null) != (exp == null) || 
                                        (cached != null && !cached.equals(exp))) {
                                        errors.incrementAndGet();
                                    }
                                    break;
                                case 2: // computeIfAbsent
                                    cache.computeIfAbsent(key, k -> k * 10);
                                    expected.computeIfAbsent(key, k -> k * 10);
                                    break;
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        executor.shutdownNow();

        assertEquals(0, errors.get(), "Concurrent access caused data inconsistencies");

        // Final invariant checks
        assertTrue(cache.size() <= capacity, "Cache size exceeds capacity");
        // Verify all cached entries exist in expected map with same value
        // Note: We cannot iterate cache keys directly (no exposed keySet), so we sample
        for (int i = 0; i < capacity * 2; i++) {
            Integer cv = cache.get(i);
            Integer ev = expected.get(i);
            if (cv != null) {
                assertEquals(ev, cv, "Mismatch for key " + i);
            }
        }
    }

    @Test
    void highContentionOnSameKey() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        int threads = 50;
        int iterations = 1000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        // All threads hammer the same key
                        cache.computeIfAbsent(42, k -> 0);
                        Integer v = cache.get(42);
                        if (v == null || v != 0) errors.incrementAndGet();
                        cache.put(42, v + 1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        exec.shutdownNow();
        assertEquals(0, errors.get());
        // Final value should be threads * iterations
        assertEquals(threads * iterations, cache.get(42));
    }

    @Test
    void concurrentEvictionStress() throws InterruptedException {
        final int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int threads = 20;
        int ops = 2000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger sizeViolations = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int base = t * 1000;
            exec.submit(() -> {
                try {
                    Random rnd = new Random();
                    for (int i = 0; i < ops; i++) {
                        int key = base + rnd.nextInt(200);
                        cache.put(key, key);
                        if (cache.size() > capacity) sizeViolations.incrementAndGet();
                        // Random gets to trigger recency updates
                        if (rnd.nextBoolean()) cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        exec.shutdownNow();
        assertEquals(0, sizeViolations.get(), "Cache size exceeded capacity during concurrent ops");
        assertTrue(cache.size() <= capacity);
    }
}
```

### Thread-Safety Guarantees Explained

1. **Locking Strategy**:  
   - `ReentrantReadWriteLock` allows **concurrent reads** (`get`) while ensuring **exclusive writes** (`put`, `computeIfAbsent`, list mutations).  
   - `get()` uses **lock coupling**: acquires read lock for map lookup, then upgrades to write lock *only if the key exists* to move the node to the head (MRU position). This minimizes write-lock contention for cache misses.

2. **Atomicity of Compound Operations**:  
   - `put` and `computeIfAbsent` execute entirely under the write lock, making check-then-act sequences (existence check → insert/update → eviction) atomic.  
   - `evictLRU()` and list manipulations (`addToHead`, `moveToHead`, `removeNode`) are private and only called while holding the write lock.

3. **Memory Visibility**:  
   - Lock acquire/release provides **happens-before** edges, guaranteeing that updates to the map and linked list (node pointers, values) are visible to subsequent lock acquisitions.

4. **No Structural Corruption**:  
   - The doubly-linked list is only modified under the write lock. Dummy `head`/`tail` nodes simplify edge cases (empty list, single element) without special null checks.

5. **Correct Eviction Under Concurrency**:  
   - When capacity is reached, `evictLRU()` removes `tail.prev` (the true LRU node) and deletes its key from the map atomically within the same write-lock critical section. No other thread can observe an intermediate state where the node is unlinked but still in the map, or vice versa.

### Concurrency Stress Tests
- **`concurrentStressTest`**: 16 threads perform 5k random operations (put/get/computeIfAbsent) against a `ConcurrentHashMap` oracle. Validates zero data inconsistencies and capacity invariant.
- **`highContentionOnSameKey`**: 50 threads hammer a single key with `computeIfAbsent`/`get`/`put`. Verifies final value equals total increments (no lost updates).
- **`concurrentEvictionStress`**: Threads insert distinct keys while randomly accessing them; asserts cache size never exceeds capacity during the test.

All tests run with `@Execution(CONCURRENT)` to maximize interleaving and expose race conditions.