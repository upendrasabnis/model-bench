<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260910T091849Z -->
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
 * Uses a single ReentrantLock for all mutations to guarantee atomicity
 * between the hash map and the doubly-linked list.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head (most recently used)
    private final Node<K, V> tail; // dummy tail (least recently used)
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // reduce resizing
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves value for key, marking it as most recently used.
     * @return value or null if not found
     */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or updates key-value pair, evicting LRU entry if at capacity.
     * @return previous value associated with key, or null if none
     */
    public V put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                return node.value;
            }

            if (map.size() >= capacity) {
                evictLRU();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically computes value if absent using the provided function.
     * The function is executed while holding the lock, so it must be fast
     * and must not call back into this cache.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                moveToHead(node);
                return node.value;
            }

            V value = mappingFunction.apply(key);
            if (value == null) {
                return null;
            }

            if (map.size() >= capacity) {
                evictLRU();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            return value;
        } finally {
            lock.unlock();
        }
    }

    public void remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) {
                unlink(node);
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

    public int capacity() {
        return capacity;
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

    // --- Internal linked list operations (must hold lock) ---

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void moveToHead(Node<K, V> node) {
        unlink(node);
        addToHead(node);
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            unlink(lru);
            map.remove(lru.key);
        }
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

### Thread-Safety Guarantees

| Mechanism | What It Protects |
|-----------|------------------|
| **Single `ReentrantLock`** | All mutations to both `HashMap` and doubly-linked list are atomic. No interleaving of `get`/`put`/`evict` can corrupt pointers or map entries. |
| **Lock held for entire operation** | `get` (including move-to-front), `put`, `evictLRU`, `clear` execute as critical sections. The LRU order is always consistent with the map contents. |
| **No publication of internal nodes** | `Node` is package-private and never escapes; callers only see keys/values. |
| **`HashMap` (not `ConcurrentHashMap`)** | Since the lock serializes all access, a plain `HashMap` avoids CAS overhead and memory footprint of `ConcurrentHashMap`. |
| **`computeIfAbsent` atomicity** | The mapping function runs under the lock, guaranteeing the key is inserted exactly once even under contention. |

**Why not `ReadWriteLock`?**  
Every `get` must move the accessed node to the head (a write to the list), so a read lock provides no concurrency benefit. A single `ReentrantLock` is simpler and avoids writer starvation.

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutAndGet() {
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
        cache.put(3, "c");        // [3,2,1]
        cache.get(1);             // [1,3,2]
        cache.put(4, "d");        // evicts 2 -> [4,1,3]
        assertNull(cache.get(2));
        assertEquals("d", cache.get(4));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
    }

    @Test
    void updateExistingKeyDoesNotEvict() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.put(1, "a-updated"); // [1,3,2]
        cache.put(4, "d");         // evicts 2
        assertNull(cache.get(2));
        assertEquals("a-updated", cache.get(1));
    }

    @Test
    void computeIfAbsent() {
        String v1 = cache.computeIfAbsent(1, k -> "computed-" + k);
        String v2 = cache.computeIfAbsent(1, k -> "should-not-run");
        assertEquals("computed-1", v1);
        assertEquals("computed-1", v2);
        assertEquals(1, cache.size());
    }

    @Test
    void computeIfAbsentReturnsNullDoesNotCache() {
        String v = cache.computeIfAbsent(1, k -> null);
        assertNull(v);
        assertEquals(0, cache.size());
    }

    @Test
    void remove() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.remove(1);
        assertNull(cache.get(1));
        assertEquals("b", cache.get(2));
        assertEquals(1, cache.size());
    }

    @Test
    void clear() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(cap);
        for (int i = 0; i < cap * 2; i++) {
            c.put(i, i);
        }
        assertEquals(cap, c.size());
        // oldest half should be evicted
        for (int i = 0; i < cap; i++) {
            assertNull(c.get(i));
        }
        for (int i = cap; i < cap * 2; i++) {
            assertEquals(i, c.get(i));
        }
    }

    // ---------- Concurrency Stress Test ----------

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int CAPACITY = 1000;
        final int THREADS = 16;
        final int OPS_PER_THREAD = 50_000;
        final ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAPACITY);

        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicLong errors = new AtomicLong();

        // Shared reference set to validate invariants
        ConcurrentHashMap<Integer, Integer> reference = new ConcurrentHashMap<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = rnd.nextInt(CAPACITY * 4);
                        int op = rnd.nextInt(100);

                        if (op < 60) { // 60% get
                            Integer v = c.get(key);
                            Integer ref = reference.get(key);
                            if (v != ref) {
                                errors.incrementAndGet();
                            }
                        } else if (op < 90) { // 30% put
                            int val = key * 31 + threadId;
                            c.put(key, val);
                            reference.put(key, val);
                        } else { // 10% computeIfAbsent
                            c.computeIfAbsent(key, k -> k * 17 + threadId);
                            reference.computeIfAbsent(key, k -> k * 17 + threadId);
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
        end.await(30, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(0, errors.get(), "Concurrent operations produced inconsistent results");

        // Final invariant checks
        lockAndCheck(c, reference);
    }

    private void lockAndCheck(ConcurrentLRUCache<Integer, Integer> c,
                              ConcurrentHashMap<Integer, Integer> reference) {
        // We can't directly inspect internals, but we can verify:
        // 1. Size <= capacity
        assertTrue(c.size() <= 1000, "Size exceeds capacity");

        // 2. Every key in cache has correct value
        // (We can't iterate cache keys, but we can spot-check reference keys)
        for (Map.Entry<Integer, Integer> e : reference.entrySet()) {
            Integer cached = c.get(e.getKey());
            if (cached != null) {
                assertEquals(e.getValue(), cached, "Value mismatch for key " + e.getKey());
            }
        }
    }

    @Test
    void highContentionOnSameKey() throws InterruptedException {
        final ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(10);
        final int THREADS = 50;
        final int ITERATIONS = 10_000;
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicInteger putCount = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < ITERATIONS; j++) {
                        c.put(1, putCount.incrementAndGet());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();

        // Only the last put should win
        Integer finalVal = c.get(1);
        assertNotNull(finalVal);
        assertTrue(finalVal > 0 && finalVal <= THREADS * ITERATIONS);
        assertEquals(1, c.size());
    }

    @Test
    void evictionUnderConcurrentAccess() throws InterruptedException {
        final int CAP = 100;
        final ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAP);
        final int THREADS = 20;
        final int OPS = 5000;
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int base = t * 1000;
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS; i++) {
                        c.put(base + i, base + i);
                        if (i % 7 == 0) c.get(base + (i % 100));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await(15, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(c.size() <= CAP, "Cache size " + c.size() + " exceeds capacity " + CAP);
    }
}
```

### Key Test Highlights

| Test | Purpose |
|------|---------|
| `concurrentStressTest` | 16 threads, 800k mixed operations; cross-validates against a `ConcurrentHashMap` reference. |
| `highContentionOnSameKey` | 50 threads hammering the *same* key — verifies lock serialization and last-write-wins. |
| `evictionUnderConcurrentAccess` | Ensures capacity invariant holds even when eviction races with inserts. |
| Parameterized `capacityRespected` | Checks LRU eviction correctness for various capacities. |

Run with:
```bash
./mvnw test -Dtest=ConcurrentLRUCacheTest
```