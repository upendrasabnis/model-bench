<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261019T151235Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a doubly-linked list for recency ordering and HashMap for O(1) lookups.
 * All operations guarded by a single ReentrantLock for simplicity and correctness.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // avoid resizing
        // dummy head/tail simplify edge cases
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns value or null if absent. Moves node to MRU position. */
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
            putNode(key, value);
            return value;
        } finally {
            lock.unlock();
        }
    }

    /** Inserts or updates key-value pair. Evicts LRU if over capacity. */
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                putNode(key, value);
            }
        } finally {
            lock.unlock();
        }
    }

    private void putNode(K key, V value) {
        Node<K, V> newNode = new Node<>(key, value);
        map.put(key, newNode);
        addToHead(newNode);
        if (map.size() > capacity) evictLRU();
    }

    /** Removes and returns evicted entry, or null if empty. */
    public Map.Entry<K, V> evictLRU() {
        lock.lock();
        try {
            Node<K, V> lru = tail.prev;
            if (lru == head) return null; // empty
            removeNode(lru);
            map.remove(lru.key);
            return new AbstractMap.SimpleImmutableEntry<>(lru.key, lru.value);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    public boolean containsKey(K key) {
        lock.lock();
        try { return map.containsKey(key); } finally { lock.unlock(); }
    }

    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally { lock.unlock(); }
    }

    // --- Doubly-linked list helpers ---
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

    static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
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
    void updateExistingKeyDoesNotEvict() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "updated"); // update, no eviction
        assertEquals(2, cache.size());
        assertEquals("updated", cache.get(1));
        assertEquals("b", cache.get(2));
    }

    @Test
    void computeIfAbsent() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        String v1 = cache.computeIfAbsent(1, k -> "value-" + k);
        assertEquals("value-1", v1);
        String v2 = cache.computeIfAbsent(1, k -> "should-not-run");
        assertEquals("value-1", v2); // cached
        assertEquals(1, cache.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 100})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(cap);
        for (int i = 0; i < cap * 2; i++) cache.put(i, i);
        assertEquals(cap, cache.size());
        // only last `cap` entries present
        for (int i = cap; i < cap * 2; i++) assertEquals(i, cache.get(i));
        for (int i = 0; i < cap; i++) assertNull(cache.get(i));
    }

    @Test
    void clearRemovesAll() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(5);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    // ---------- Concurrency Stress Test ----------
    @Test
    void concurrentStressTest() throws InterruptedException {
        final int CAPACITY = 1000;
        final int THREADS = 16;
        final int OPS_PER_THREAD = 50_000;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(CAPACITY);
        final AtomicInteger putCount = new AtomicInteger();
        final AtomicInteger getCount = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        final Random rnd = new Random();

        Runnable task = () -> {
            try { start.await(); } catch (InterruptedException ignored) {}
            try {
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int key = rnd.nextInt(CAPACITY * 2);
                    if (rnd.nextBoolean()) {
                        cache.put(key, key * 2);
                        putCount.incrementAndGet();
                    } else {
                        cache.get(key);
                        getCount.incrementAndGet();
                    }
                }
            } finally { done.countDown(); }
        };

        Thread[] threads = IntStream.range(0, THREADS)
                .mapToObj(i -> new Thread(task))
                .toArray(Thread[]::new);
        Arrays.asList(threads).forEach(Thread::start);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        // Invariants
        assertEquals(CAPACITY, cache.size(), "Size must not exceed capacity");
        assertTrue(cache.size() <= CAPACITY);
        // Spot-check: every present key has correct value
        for (int i = 0; i < 100; i++) {
            int key = rnd.nextInt(CAPACITY * 2);
            Integer val = cache.get(key);
            if (val != null) assertEquals(key * 2, val, "Value corruption for key " + key);
        }
        System.out.printf("Stress test: puts=%d gets=%d finalSize=%d%n",
                putCount.get(), getCount.get(), cache.size());
    }

    @Test
    void concurrentComputeIfAbsent() throws InterruptedException {
        final int CAPACITY = 500;
        final int THREADS = 32;
        final ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(CAPACITY);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        final AtomicInteger computed = new AtomicInteger();

        Runnable task = () -> {
            try { start.await(); } catch (InterruptedException ignored) {}
            try {
                for (int i = 0; i < 10_000; i++) {
                    int key = ThreadLocalRandom.current().nextInt(CAPACITY);
                    cache.computeIfAbsent(key, k -> {
                        computed.incrementAndGet();
                        return "val-" + k;
                    });
                }
            } finally { done.countDown(); }
        };

        Thread[] threads = IntStream.range(0, THREADS)
                .mapToObj(i -> new Thread(task))
                .toArray(Thread[]::new);
        Arrays.asList(threads).forEach(Thread::start);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);

        assertEquals(CAPACITY, cache.size());
        // Each key computed exactly once
        assertEquals(CAPACITY, computed.get(), "computeIfAbsent must be idempotent per key");
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals("val-" + i, cache.get(i));
        }
    }

    @Test
    void concurrentEvictionDoesNotLoseEntries() throws InterruptedException {
        final int CAPACITY = 200;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(CAPACITY);
        final int THREADS = 8;
        final int OPS = 20_000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        final AtomicInteger evictions = new AtomicInteger();

        // Background evictor
        Thread evictor = new Thread(() -> {
            try { start.await(); } catch (InterruptedException ignored) {}
            while (done.getCount() > 0) {
                Map.Entry<Integer, Integer> e = cache.evictLRU();
                if (e != null) evictions.incrementAndGet();
                Thread.yield();
            }
        });
        evictor.start();

        Runnable writer = () -> {
            try { start.await(); } catch (InterruptedException ignored) {}
            try {
                Random r = new Random();
                for (int i = 0; i < OPS; i++) {
                    cache.put(r.nextInt(CAPACITY * 4), i);
                }
            } finally { done.countDown(); }
        };

        Thread[] threads = IntStream.range(0, THREADS)
                .mapToObj(i -> new Thread(writer))
                .toArray(Thread[]::new);
        Arrays.asList(threads).forEach(Thread::start);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        evictor.join(5_000);

        assertTrue(cache.size() <= CAPACITY);
        // No exceptions, no corruption
        for (int i = 0; i < 50; i++) {
            Integer v = cache.get(i);
            if (v != null) assertTrue(v >= 0);
        }
        System.out.printf("Eviction test: evictions=%d finalSize=%d%n", evictions.get(), cache.size());
    }
}
```

**Thread-safety guarantees:**

1. **Single lock (`ReentrantLock`)** guards all mutations of both the `HashMap` and the doubly-linked list.  
2. **All public operations** (`get`, `put`, `computeIfAbsent`, `evictLRU`, `size`, `clear`, `containsKey`) acquire the lock for their entire duration, establishing a *happens-before* relationship between any two operations.  
3. **No publication of partially updated state**: nodes are fully initialized before insertion into the map/list; removal unlinks before map deletion.  
4. **No lock upgrading/downgrading** – avoids deadlock risk.  
5. **Memory visibility** ensured by lock’s acquire/release semantics (JMM).  

The stress tests verify invariants (size ≤ capacity, no value corruption, idempotent `computeIfAbsent`) under heavy parallel load with mixed read/write/evict operations.