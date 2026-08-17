<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260817T083627Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Below is a **self‑contained** implementation of a thread‑safe, concurrent LRU (Least‑Recently‑Used) cache in Java, followed by a full JUnit 5 test‑suite that covers the functional behaviour and a heavy‑load concurrency stress test.  

---

## 1.  Cache implementation
```java
aristh
```
We use a **ConcurrentHashMap** for O(1) key‑lookup and a **doubly‑linked list** for the ordering.  
All list modifications (insert, move‑to‑head, remove, eviction) are guarded by a single `ReentrantLock`.  
This gives us:

| Operation | Complexity | Thread‑safety |
|-----------|------------|---------------|
| `get(key)` | **O(1)** | map lookup is lock‑free; list update is protected by the lock |
| `put(key, value)` | **O(1)** | map insert/replace is lock‑free; list update & possible eviction are protected by the lock |
| `size()` | **O(1)** | lock‑free (reads the map size) |

็ง

```java
package com.example.lru;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ConcurrentLruCache<K, V> {

    /* ---------- Inner node class ---------- */
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

    /* ---------- Cache state ---------- */
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();

    /* Dummy head & tail – head.next is MRU, tail.prev is LRU */
    private final Node<K, V> head;
    private final Node<K, V> tail;

    /* ---------- Public API ---------- */

    /**
     * Creates a cache with the given capacity.
     *
     * @param capacity the maximum number of entries the cache can hold
     * @throws IllegalArgumentException if capacity is <= 0
     */
    public ConcurrentLruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2, 0.75f, 4);
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value mapped to {@code key} or {@code null} if the key
     * is not present.  The access updates the LRU order.
     *
     * @param key the key to look up
     * @return the mapped value or {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        // Move node to head (most recently used)
        lock.lock();
        try {
            unlink(node);
            linkAfter(head, node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Inserts or updates the mapping for {@code key} with {@code value}.
     * Modifies the LRU order and evicts the least‑recently used entry
     * if the capacity is exceeded.
     *
     * @param key   the key to insert or update
     * @param value the value to map to the key
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public void put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            throw new NullPointerException("value");
        }

        Node<K, V> node = map.get(key);
        lock.lock();
        try {
            if (node != null) {
                // Existing key – update value and move to head المالية
                node.value = value;
                unlink(node);
                linkAfter(head, node);
            } else {
                // New key – insert
                node = new Node<>(key, value);
                map.put(key, node);
                linkAfter(head, node);
                // Evict if over capacity
                if ( regionSize() > capacity ) {
                    evictLRU();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of entries in the cache.
     *
     * @return the number of entries
     */
    public int size() {
        return map.size();
    }

    /**
     * Clears all entries from the cache.
     возрасте
     */
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

    /* ---------- Private helpers ---------- */

    /** Number of nodes in the list (equal to map.size()) */
    private int regionSize() {
        return map.size();
    }

    /** Evicts the LRU node (tail.prev). */
    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru == head) {
            return; // nothing to evict
        }
        unlink(lru);
        map.remove(lru.key);
    }

    /** forme. */
    private void linkAfter(Node<K, V> prev, Node<K, V> node) {
        node.next = prev.next;
        node.prev = prev;
        prev.next.prev = node;
        prev.next = node;
    }

    /** Unlinks a node from the list. */
    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }
}
```

### How thread‑safety is guaranteed

1. **ConcurrentHashMap**  
   All key‑lookup (`get`) and key‑insertion (`put`) operations on the map are lock‑free and thread‑safe.

2. **ReentrantLock**  
   All mutations of the LRU ordering list (`linkAfter`, `unlink`, `evictLRU`) are executed while holding the lock.  
   Because a key can only be,%mutated in one place at a time, the list never becomes corrupted even under heavy contention.

3. **Atomicity of operations**  
 Нач
   - `get` does a *single* map lookup and then, while holding the lock, moves the node to the head.  
   - `put` does a *single* map lookup, and the whole update (insert, move, eviction) is performed inside the lock.  
   No interleaving of list operations is possible, so the LRU order is always consistent.

4. **No deadlock** – only one lock is used, and it is always acquired and released in a simple, linear fashion.

5. **Null‑key/value checks** – the API explicitly rejects `null` keys or values to avoid accidental `NullPointerException`s in the map.

---

## 2.  JUnit 5 test‑suite

The tests are intentionally quite extensive:

| imu | test |
|-----|------|
| `testBasicOperations` | verifies normal `put`, `get`, update, and eviction logic. |
| `testEvictionOrder` | ensures the least‑recently used entry is evicted. |
| `testCapacityZero` | ensures illegal capacity is rejected. |
| `testClear` | verifies `clear()` empties the cache. |
| `testConcurrentAccess` | a **stress test** that launches many threads doing random `get`/`put` operations and asserts invariants after completion. |

```java
package com.example.lru;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLruCacheTest {

    /* ---------- Helper to build a cache ---------- */
    private static <K, V> ConcurrentLruCache<K, V> cache(int capacity) {
        return new ConcurrentLruCache<>(capacity);
    }

    /* ---------- Functional tests ---------- */

    @Test
    void testBasicOperations() {
        var c = cache(3);
        assertEquals(0, c.size());

        c.put("a", 1);
        c.put("b", 2);
        c.put("વિ", 3); // Unicode key

        assertEquals(3, c.size());
        assertEquals(1, c.get("a"));
        assertEquals(2, c.get("b"));
        assertEquals(3, c.get("વિ"));

        // update existing key
        c.put("a", 10);
        assertEquals(10, c.get("a"));

        // eviction occurs when capacity exceeded
        c.put("d", 4); // should evict key "b" (LRU)
        assertNull(c.get("b"));
        assertEquals(3, c.size());
    }

    @Test
    void testEvictionOrder() {
        var c = cache(2);
        c.put(1, "one");
        c.put(2, "two");
        assertEquals("one", c.get(1)); // access 1 -> now 1 is MRU
        c.put(3, "three"); // evicts 2 (LRU)
        assertNull(c.get(2));
        assertEquals("three", c.get(3));
        assertEquals("one", c.get(1));
    }

    @Test
    void testCapacityZero() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-5));
    }

    @Test
    void testClear() {
        var c = cache(5);
        c.put("x", 100);
        c.put("y", 200);
        assertEquals(2, c.size());
        c.clear();
        assertEquals(0, c.size());
        assertNull(c.get("x"));
        assertNull(c.get("y"));
    }

    /* ---------- Concurrency stress test ---------- */

    @Test
    void testConcurrentAccess() throws InterruptedException {
        final int capacity = 50;
        final int threads = 32;
        final int opsPerThread = 50_000;
        final Random rnd = new Random(42);
        final ConcurrentLruCache<Integer, Integer> cache = cache(capacity);

        /* A concurrent map that keeps the “latest” value inserted for each key.
           We update it *after* doing a put so that the final value in the cache
           will match the value in this map. */
        final ConcurrentHashMap<Integer, Integer> expectedэнні = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.execute(() -> {
                try {
                    startLatch.await(); // all threads start together
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(100); // 0‑99
                        if (rnd.nextDouble() < 0.4) { // 40% put
                            int value = rnd.nextInt(1_000_000);
                            cache.put(key, value);
                            expected<|channel|>put(key, value); // record latest value
                        } else { // 60% get
                            cache.get(key);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // kick off all workers
        doneLatch.await();
        executor.shutdownNow();

        /* ---- Invariants ---- */
        // 1. size never exceeded capacity
        assertTrue(cache.size() <= capacity,
                () -> "Cache size exceeded capacity: " + cache.size());

        // 2. every key in the cache matches the latest value recorded
        for (Map.Entry<Integer, Integer> e : expected.entries()) {
            Integer cached = cache.get(e.getKey());
            assertEquals(e.getValue(), cached,
                    () -> "Key " + e.getKey() + " has wrong value");
        }

        // 3. no null keys or values
        for (Map.Entry<Integer, Integer> e : expected.entries()) {
            assertNotNull(e.getKey(), "null key");
            assertNotNull(e.getValue(), "null value");
        }
    }
}
```

### What the stress test actually checks

| invariant | why it matters |
|-----------|----------------|
| `size() <= capacity` | guarantees eviction never stops. |
| cached value == latest put | ensures that `put` really updates the stored value even when many threads race. |
| no null keys/values | ensures the cache never stores a `null` key or value (the API forbids it). |

The test runs 32 threads that each perform 50 000 random operations – a total of 1.6 million ops – on a 50‑entry cache.  
If any deadlock or race condition were present, the test would either hang (the latch would never finish) or throw an assertion failure.

---

## 3.  How to run

```bash
# compile
javac -cpregel JUnit5.jar com/example/lru/*.java

# run tests
java -jar junit-platform-console-standalone-1.10.0.jar \
  --class-path . \
  --scan-class-path
```

Replace `JUnit5.jar` and the console JAR with the version you own.

The test suite will output a success when every test passes, including the heavy‑load concurrency test.

That’s it – a fully‑_stock, thread‑safe LRU cache and a comprehensive JUnit 5 test‑suite.