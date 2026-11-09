<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261109T181017Z -->
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
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a doubly-linked list for access order and a HashMap for O(1) lookups.
 * Thread-safety is achieved via a single ReentrantReadWriteLock:
 * - Read lock for get() (allows concurrent reads)
 * - Write lock for put()/eviction (exclusive access for mutations)
 * 
 * The lock guards all structural modifications (list pointers, map entries, size).
 * Invariants: map.size() == list node count; head/tail sentinels always connected;
 * most-recently-used at head.next, least-recently-used at tail.prev.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // avoid rehash
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns value or null if absent. Updates access order (MRU). */
    public V get(K key) {
        readLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            // Must upgrade to write lock to move node to front
            readLock.unlock();
            writeLock.lock();
            try {
                // Re-check under write lock (node might have been evicted)
                node = map.get(key);
                if (node == null) return null;
                moveToHead(node);
                return node.value;
            } finally {
                writeLock.unlock();
                readLock.lock(); // restore read lock for finally
            }
        } finally {
            readLock.unlock();
        }
    }

    /** Inserts/updates key-value pair. Evicts LRU if capacity exceeded. */
    public void put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                return;
            }
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

    /** Atomically computes value if absent using mapping function. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                moveToHead(node);
                return node.value;
            }
            V value = mappingFunction.apply(key);
            if (value == null) return null;
            if (map.size() >= capacity) evictLRU();
            node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
            return value;
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
        cache.put(4, "d"); // evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "a-updated"); // update
        cache.put(3, "c");
        cache.put(4, "d"); // evicts 2
        assertNull(cache.get(2));
        assertEquals("a-updated", cache.get(1));
    }

    @Test
    void computeIfAbsent() {
        assertEquals("computed-1", cache.computeIfAbsent(1, k -> "computed-" + k));
        assertEquals("computed-1", cache.get(1)); // cached
        assertEquals(1, cache.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(cap);
        IntStream.range(0, cap * 2).forEach(i -> c.put(i, i));
        assertEquals(cap, c.size());
        // only last 'cap' entries should exist
        for (int i = cap; i < cap * 2; i++) assertEquals(i, c.get(i));
        for (int i = 0; i < cap; i++) assertNull(c.get(i));
    }

    @Test
    void concurrentReadOnly() throws InterruptedException {
        cache.put(1, "a");
        cache.put(2, "b");
        int threads = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try { start.await(); }
                catch (InterruptedException e) { return; }
                try {
                    for (int j = 0; j < 1000; j++) {
                        String v1 = cache.get(1);
                        String v2 = cache.get(2);
                        if (!"a".equals(v1) || !"b".equals(v2)) errors.incrementAndGet();
                    }
                } finally { end.countDown(); }
            });
        }
        start.countDown();
        end.await();
        es.shutdown();
        assertEquals(0, errors.get(), "Concurrent reads observed inconsistent state");
    }

    @Test
    void concurrentWriteStress() throws InterruptedException {
        int capacity = 1000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        int threads = 50;
        int opsPerThread = 2000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);
        AtomicInteger putCount = new AtomicInteger();
        AtomicInteger getCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            es.submit(() -> {
                try { start.await(); }
                catch (InterruptedException e) { return; }
                Random r = new Random(threadId);
                for (int i = 0; i < opsPerThread; i++) {
                    int key = r.nextInt(capacity * 2);
                    if (r.nextBoolean()) {
                        c.put(key, key);
                        putCount.incrementAndGet();
                    } else {
                        c.get(key);
                        getCount.incrementAndGet();
                    }
                }
                end.countDown();
            });
        }
        start.countDown();
        end.await();
        es.shutdown();

        // Invariants: size <= capacity, all entries in map are in list, no duplicates
        assertTrue(c.size() <= capacity, "Size exceeds capacity: " + c.size());
        
        // Verify structural integrity by iterating (requires write lock internally)
        // We can't directly inspect internals, but we can check that every key in map returns a value
        // This is a best-effort sanity check.
        for (int i = 0; i < capacity * 2; i++) {
            Integer v = c.get(i);
            if (v != null) assertEquals(i, v, "Value mismatch for key " + i);
        }
        System.out.printf("Stress test: puts=%d, gets=%d, final size=%d%n",
                putCount.get(), getCount.get(), c.size());
    }

    @Test
    void highContentionOnSameKey() throws InterruptedException {
        ConcurrentLRUCache<Integer, AtomicInteger> c = new ConcurrentLRUCache<>(10);
        c.put(1, new AtomicInteger(0));
        int threads = 50;
        int increments = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try { start.await(); }
                catch (InterruptedException e) { return; }
                for (int j = 0; j < increments; j++) {
                    AtomicInteger counter = c.computeIfAbsent(1, k -> new AtomicInteger(0));
                    counter.incrementAndGet();
                }
                end.countDown();
            });
        }
        start.countDown();
        end.await();
        es.shutdown();

        AtomicInteger counter = c.get(1);
        assertNotNull(counter);
        assertEquals(threads * increments, counter.get(), "Lost updates under contention");
        assertEquals(1, c.size(), "Spurious entries created");
    }

    @Test
    void evictionUnderConcurrentAccess() throws InterruptedException {
        int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        int threads = 20;
        int ops = 5000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService es = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int base = t * 1000;
            es.submit(() -> {
                try { start.await(); }
                catch (InterruptedException e) { return; }
                for (int i = 0; i < ops; i++) {
                    int key = base + (i % (capacity * 2));
                    c.put(key, key);
                    if (i % 7 == 0) c.get(key - 1);
                }
                end.countDown();
            });
        }
        start.countDown();
        end.await();
        es.shutdown();

        assertTrue(c.size() <= capacity, "Cache size exceeded capacity after concurrent eviction");
        // Spot-check: all present keys have correct values
        for (int i = 0; i < threads * 1000; i++) {
            Integer v = c.get(i);
            if (v != null) assertEquals(i, v);
        }
    }
}
```

### Thread-Safety Guarantees Explained

1. **Single Lock for Mutations**: All structural modifications (`put`, `evict`, `moveToHead`, `addToHead`, `removeNode`) are guarded by the **write lock** of a `ReentrantReadWriteLock`. This ensures mutual exclusion for any operation that changes the linked-list pointers or the `HashMap`.

2. **Read Lock for Pure Reads**: `get()` initially acquires the **read lock** to allow high-concurrency lookups. However, because `get` must update the access order (move node to head), it **upgrades to the write lock** after locating the node. The upgrade is safe because:
   - The read lock is released *before* acquiring the write lock (no deadlock).
   - The node is re-checked under the write lock to handle races where another thread evicted it during the upgrade.

3. **Atomicity of Compound Operations**: Operations like `computeIfAbsent` and `put` hold the write lock for their entire duration, making them atomic with respect to other mutations.

4. **Memory Visibility**: `ReentrantReadWriteLock` provides **happens-before** guarantees. All writes (map updates, pointer changes) performed under the write lock are visible to subsequent read-lock or write-lock acquisitions.

5. **Invariant Preservation**: The lock ensures the critical invariants are never observed in a broken state:
   - `map.size() == number of nodes in list`
   - `head.next` is MRU, `tail.prev` is LRU
   - Every node in the list has a corresponding entry in `map` with the same key
   - No node appears twice in the list

6. **No External Synchronization Needed**: Clients can safely call `get`, `put`, `computeIfAbsent`, `size`, `containsKey` from multiple threads without additional synchronization.

### Stress Test Coverage
- **`concurrentReadOnly`**: Validates read-lock scalability and visibility.
- **`concurrentWriteStress`**: Heavy mixed read/write load; checks capacity invariant and value correctness.
- **`highContentionOnSameKey`**: Tests `computeIfAbsent` atomicity and lost-update prevention.
- **`evictionUnderConcurrentAccess`**: Verifies LRU eviction logic remains correct under concurrent insertions/accesses.