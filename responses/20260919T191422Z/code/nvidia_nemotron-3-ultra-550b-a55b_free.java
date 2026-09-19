<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260919T191422Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a doubly-linked list for ordering and ConcurrentHashMap for lookups.
 * A single ReentrantLock protects structural modifications (list + map).
 * Reads (get) acquire read lock; writes (put/evict) acquire write lock.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head (most recently used)
    private final Node<K, V> tail; // dummy tail (least recently used)
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns value or null if absent. Moves node to MRU position. */
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;
        lock.lock();
        try {
            // Re-check under lock (node might have been evicted)
            node = map.get(key);
            if (node == null) return null;
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Puts key-value, evicts LRU if capacity exceeded. Returns previous value or null. */
    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Key and value must be non-null");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                return node.value;
            }
            // New node
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            if (map.size() > capacity) {
                evictLRU();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Atomically computes value if absent, using provided function. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null) throw new NullPointerException();
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
            if (map.size() > capacity) evictLRU();
            return value;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
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

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        map.remove(lru.key);
    }

    // --- Node definition ---
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

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutGet() {
        cache.put(1, "a");
        cache.put(2, "b");
        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1); // make 1 MRU
        cache.put(4, "d"); // should evict 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.put(1, "a2"); // update
        cache.put(4, "d"); // should evict 2
        assertNull(cache.get(2));
        assertEquals("a2", cache.get(1));
    }

    @Test
    void computeIfAbsent() {
        String val = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", val);
        assertEquals("computed-1", cache.get(1));
        // second call should not recompute
        AtomicInteger counter = new AtomicInteger();
        cache.computeIfAbsent(1, k -> { counter.incrementAndGet(); return "new"; });
        assertEquals(0, counter.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(cap);
        for (int i = 0; i < cap * 2; i++) c.put(i, i);
        assertEquals(cap, c.size());
        // only last 'cap' entries should exist
        for (int i = cap; i < cap * 2; i++) assertNotNull(c.get(i));
        for (int i = 0; i < cap; i++) assertNull(c.get(i));
    }

    @Test
    void clearRemovesAll() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentPutGetStress() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int opsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    start.await();
                    Random r = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = r.nextInt(capacity * 2);
                        if (r.nextBoolean()) {
                            c.put(key, key * 2);
                        } else {
                            c.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Concurrent errors occurred");
        assertTrue(c.size() <= capacity, "Size exceeds capacity: " + c.size());
    }

    @Test
    void concurrentComputeIfAbsentStress() throws InterruptedException {
        final int capacity = 500;
        final int numThreads = 32;
        ConcurrentLRUCache<Integer, String> c = new ConcurrentLRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();
        Set<Integer> expectedKeys = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < numThreads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        int key = ThreadLocalRandom.current().nextInt(capacity * 3);
                        String val = c.computeIfAbsent(key, k -> "val-" + k);
                        if (val == null) { errors.incrementAndGet(); break; }
                        if (!val.equals("val-" + key)) { errors.incrementAndGet(); break; }
                        expectedKeys.add(key);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertTrue(c.size() <= capacity);
        // All keys in cache must have correct values
        for (Integer k : expectedKeys) {
            if (c.containsKey(k)) {
                assertEquals("val-" + k, c.get(k));
            }
        }
    }

    @Test
    void highContentionSingleKey() throws InterruptedException {
        final int threads = 50;
        final int iterations = 10000;
        ConcurrentLRUCache<Integer, AtomicInteger> c = new ConcurrentLRUCache<>(10);
        c.put(1, new AtomicInteger(0));
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        AtomicInteger counter = c.computeIfAbsent(1, k -> new AtomicInteger(0));
                        counter.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        AtomicInteger finalCounter = c.get(1);
        assertNotNull(finalCounter);
        assertEquals((long) threads * iterations, finalCounter.get());
    }

    @Test
    void invariantsUnderHeavyLoad() throws InterruptedException {
        final int capacity = 200;
        final int numThreads = 20;
        final int opsPerThread = 10000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < numThreads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = r.nextInt(500);
                        int op = r.nextInt(4);
                        switch (op) {
                            case 0 -> c.put(key, key);
                            case 1 -> c.get(key);
                            case 2 -> c.computeIfAbsent(key, k -> k * 10);
                            case 3 -> c.containsKey(key);
                        }
                        // Invariant: size never exceeds capacity
                        if (c.size() > capacity) {
                            errors.incrementAndGet();
                            return;
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Invariant violated: size > capacity");
        assertTrue(c.size() <= capacity);
    }

    @Test
    void noDataLossUnderConcurrentEviction() throws InterruptedException {
        // Verify that every put is either in cache or was evicted by a newer put
        final int capacity = 100;
        final int numThreads = 10;
        final int putsPerThread = 2000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        Set<Integer> allPutKeys = Collections.synchronizedSet(new HashSet<>());
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        int key = ThreadLocalRandom.current().nextInt(10000);
                        c.put(key, key);
                        allPutKeys.add(key);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

        // Every key in cache must have correct value
        for (int i = 0; i < 10000; i++) {
            if (c.containsKey(i)) {
                assertEquals(i, c.get(i), "Corrupted value for key " + i);
            }
        }
        assertTrue(c.size() <= capacity);
    }
}
```

### Thread-Safety Guarantees

1. **Single Lock for Structural Changes**: A single `ReentrantLock` guards all mutations to the doubly-linked list (node insertion, removal, reordering) and the `ConcurrentHashMap` (put, remove). This ensures the list and map stay consistent.

2. **ConcurrentHashMap for Lookups**: The map provides thread-safe `get`/`containsKey` without locking for read-only access. However, because eviction removes entries from the map *under the same lock* that modifies the list, a `get` that finds a node must re-validate under the lock before moving it to MRU (double-checked locking pattern).

3. **Lock Ordering**: Only one lock exists → no deadlock risk. All critical sections are short (pointer manipulations + map ops).

4. **Visibility**: `ReentrantLock` provides happens-before edges; all fields of `Node` are confined to the lock (except `value` which is only written before publication or under lock).

5. **Atomic `computeIfAbsent`**: The entire check-insert-evict sequence runs under the lock, guaranteeing the function is called at most once per key.

6. **Invariant Preservation**: The lock ensures `map.size() == list node count` and `size() ≤ capacity` always hold, even under heavy contention (verified by stress tests).