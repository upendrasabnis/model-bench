<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260928T162214Z -->
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
 * Uses a single lock for simplicity and correctness; operations are fast (pointer manipulations)
 * so contention is minimal. For higher throughput, a striped-lock or lock-free design could be used.
 */
public final class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head, tail;
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
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
            if (map.size() >= capacity) {
                evictTail();
            }
            node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
            return null;
        } finally {
            lock.unlock();
        }
    }

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
            if (map.size() >= capacity) {
                evictTail();
            }
            node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
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

    public boolean containsKey(K key) {
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
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

    private void evictTail() {
        Node<K, V> last = tail.prev;
        removeNode(last);
        map.remove(last.key);
    }

    private static final class Node<K, V> {
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
        cache.get(1); // 1 becomes MRU
        cache.put(4, "d"); // evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKeyDoesNotIncreaseSize() {
        cache.put(1, "a");
        cache.put(1, "b");
        assertEquals(1, cache.size());
        assertEquals("b", cache.get(1));
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void capacityRespected(int cap) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(cap);
        IntStream.range(0, cap * 2).forEach(i -> c.put(i, i));
        assertEquals(cap, c.size());
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int CAPACITY = 1000;
        final int THREADS = 16;
        final int OPERATIONS_PER_THREAD = 5000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = rnd.nextInt(CAPACITY * 2);
                        if (rnd.nextBoolean()) {
                            c.put(key, key);
                        } else {
                            c.get(key);
                        }
                        // Occasional computeIfAbsent
                        if (rnd.nextInt(10) == 0) {
                            c.computeIfAbsent(key, k -> k * 2);
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
        exec.shutdown();

        assertEquals(0, errors.get(), "Exceptions occurred during stress test");
        assertTrue(c.size() <= CAPACITY, "Cache size exceeded capacity");

        // Verify structural integrity: every key in map is reachable in list
        // (We can't easily inspect internals, but we can check that all keys return a value)
        // This is a best-effort sanity check.
        for (int i = 0; i < CAPACITY * 2; i++) {
            Integer v = c.get(i);
            if (v != null) {
                assertEquals(i, v, "Value corruption detected for key " + i);
            }
        }
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        final int CAPACITY = 100;
        final int THREADS = 8;
        final int ITERATIONS = 2000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random();
                    for (int i = 0; i < ITERATIONS; i++) {
                        int key = rnd.nextInt(CAPACITY * 3);
                        c.put(key, key);
                        // Immediately read back to promote
                        Integer val = c.get(key);
                        if (val != null && val != key) {
                            errors.incrementAndGet();
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
        end.await(30, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(0, errors.get());
        assertTrue(c.size() <= CAPACITY);
    }

    @Test
    void computeIfAbsentAtomicity() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(10);
        ExecutorService exec = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            final int key = i;
            exec.submit(() -> {
                try {
                    Integer v = c.computeIfAbsent(key, k -> k * 10);
                    assertEquals(key * 10, v);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        exec.shutdown();
        assertEquals(10, c.size());
    }
}
```

**Thread-safety explanation**

1. **Single mutual-exclusion lock** (`ReentrantLock`) guards *all* mutations of both the `HashMap` and the doubly-linked list.  
2. Every public operation (`get`, `put`, `computeIfAbsent`, `size`, `containsKey`) acquires the lock for its entire duration, making each operation **atomic** and **linearizable**.  
3. The lock is held only for a few pointer updates and a `HashMap` lookup/insert – all *O(1)* – so contention stays low.  
4. The `HashMap` is *not* a `ConcurrentHashMap`; it is a plain `HashMap` confined to the lock, eliminating any possibility of map-level races.  
5. The doubly-linked list uses dummy `head`/`tail` sentinels, so insertion/removal never needs null checks and is always a constant-time pointer rewrite.  
6. `computeIfAbsent` runs the mapping function *inside* the lock, guaranteeing the function is applied exactly once per missing key (the standard `Map` contract).

**Why this satisfies the requirements**

* **O(1) average time** – `HashMap` lookup + a constant number of pointer changes.  
* **Correct LRU eviction** – the tail predecessor is always the least-recently-used entry; eviction removes it from both list and map atomically.  
* **Thread-safe** – the single lock serialises all structural changes; no partial updates are ever visible.  
* **Configurable capacity** – enforced on every `put`/`computeIfAbsent`.  
* **Stress tests** – the JUnit 5 suite includes a high-contention test (16 threads, 5k ops each) that verifies capacity bound, value integrity, and absence of exceptions.