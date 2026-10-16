<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261016T150237Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *thread‑safe* LRU (Least‑Recently‑Used) cache with a fixed capacity.  
The cache provides the classic `get` and `put` operations in **O(1)** average time
by keeping:

* a `HashMap<K,Node>` for fast key → node lookup,
* a doubly‑linked list that orders nodes from **most‑recently used** (head) to
  **least‑recently used** (tail).

All mutating actions (map updates, list insertions/removals, size changes) are
protected by a single `ReentrantLock`.  
Because every public method acquires the lock before touching any shared state
and releases it afterwards, the Java Memory Model guarantees:

* **Mutual exclusion** – only one thread can modify the internal structures at a
  time.
* **Visibility** – changes made while holding the lock become visible to any
  thread that subsequently acquires the same lock.

Thus the cache is linearizable and safe under arbitrary concurrent access.

The lock is *reentrant* so a thread that already holds it can call `get` or
`put` recursively without dead‑locking (useful for wrappers or debugging).

A `snapshot()` method (package‑private for testing) returns a shallow copy of
the current entries ordered from most‑ to least‑recent, allowing tests to
verify invariants without exposing the internal data structures in production
code.

---

## 1. Implementation (`ConcurrentLRUCache.java`)

```java
package com.example.cache;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type (must not be null)
 * @param <V> value type (must not be null)
 */
public final class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list. */
    private static final class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map; // key → node
    private Node<K, V> head; // most recently used
    private Node<K, V> tail; // least recently used
    private final ReentrantLock lock = new ReentrantLock();
    private int size = 0;

    /**
     * Creates a cache that can hold at most {@code capacity} entries.
     *
     * @param capacity maximum number of entries; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2); // initial size, load factor 0.75
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if the key
     * is not present. The accessed entry becomes the most‑recently used.
     *
     * @param key the key whose value is to be returned
     * @return the value or {@code null}
     * @throws NullPointerException if key is null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node); // mark as recently used
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates {@code value} with {@code key} in the cache.
     * If the key already exists, its value is replaced and the entry becomes
     * most‑recently used. If the cache exceeds its capacity, the least‑recently
     * used entry is evicted.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @throws NullPointerException if key or value is null
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to head
                existing.value = value;
                moveToHead(existing);
                return;
            }

            // insert new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addNodeToHead(node);
            ++size;

            // evict if over capacity
            if (size > capacity) {
                removeTailNode();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries in the cache. */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot of the cache contents ordered from most‑ to
     * least‑recently used. The returned list is independent of the cache
     * and can be safely inspected without further synchronization.
     *
     * <p>Package‑private – intended for unit tests only.</p>
     */
    List<Map.Entry<K, V>> snapshot() {
        lock.lock();
        try {
            List<Map.Entry<K, V>> list = new ArrayList<>(size);
            for (Node<K, V> x = head; x != null; x = x.next) {
                // AbstractMap.SimpleEntry is immutable enough for a snapshot
                list.add(new AbstractMap.SimpleEntry<>(x.key, x.value));
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Internal linked‑list helpers – all assume the lock is held.
     * ------------------------------------------------------------------ */

    /** Insert node right after the head (as the new most‑recent). */
    private void addNodeToHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) { // list was empty
            tail = node;
        }
    }

    /** Remove node from the linked list. */
    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;

        if (prev != null) {
            prev.next = next;
        } else {
            // node is head
            head = next;
        }
        if (next != null) {
            next.prev = prev;
        } else {
            // node is tail
            tail = prev;
        }
        node.prev = node.next = null; // help GC
    }

    /** Move an existing node to the head (most‑recent position). */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        removeNode(node);
        addNodeToHead(node);
    }

    /** Remove the tail node (least‑recently used) and evict it from the map. */
    private void removeTailNode() {
        if (tail == null) {
            return; // should never happen when size > capacity
        }
        map.remove(tail.key);
        removeNode(tail);
        --size;
    }
}
```

### How Thread‑Safety Is Guaranteed

| Aspect | Mechanism |
|--------|-----------|
| **Mutual exclusion** | Every public method (`get`, `put`, `size`, `snapshot`) acquires the same `ReentrantLock` before touching any shared field (`map`, `head`, `tail`, `size`). The lock is released in a `finally` block, ensuring it is always released even if an exception occurs. |
| **Visibility** | The lock provides a *happens‑before* edge: all writes performed while holding the lock become visible to any thread that subsequently acquires the lock. Consequently, readers see a consistent view of the map and the linked list. |
| **Atomicity of compound operations** | Operations that consist of multiple steps (e.g., `put` when the cache is full: insert → possibly evict) are executed while the lock is held, so no other thread can interleave and observe an intermediate state. |
| **No data races on internal structures** | The `HashMap` and the doubly‑linked list are only accessed while the lock is held, eliminating concurrent modification races. |
| **Reentrancy** | Using `ReentrantLock` allows a thread that already holds the lock to re‑enter it (e.g., if a wrapper calls `get` which in turn calls `put`). This prevents accidental self‑dead‑locks while preserving safety. |

Because the lock is *coarse‑grained* (single lock for the whole cache), the implementation is simple and easy to reason about while still offering O(1) average time complexity for `get` and `put` (the lock acquisition/release is O(1) and does not affect asymptotic complexity).

---

## 2. JUnit 5 Tests (`ConcurrentLRUCacheTest.java`)

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int DEFAULT_CAPACITY = 10;

    /** Helper to create a fresh cache for each test. */
    private ConcurrentLRUCache<Integer, String> newCache() {
        return new ConcurrentLRUCache<>(DEFAULT_CAPACITY);
    }

    @Test
    void getAndPutBasics() {
        ConcurrentLRUCache<Integer, String> c = newCache();

        assertNull(c.get(1));
        c.put(1, "one");
        assertEquals("one", c.get(1));
        assertEquals(1, c.size());

        c.put(1, "uno"); // replace
        assertEquals("uno", c.get(1));
        assertEquals(1, c.size());

        assertNull(c.get(999));
    }

    @Test
    void lruEviction() {
        ConcurrentLRUCache<Integer, String> c = newCache();

        // Fill the cache
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            c.put(i, String.valueOf(i));
        }
        assertEquals(DEFAULT_CAPACITY, c.size());

        // Access 0 -> make it most‑recent
        c.get(0);

        // Add one more element -> least‑recent (1) should be evicted
        c.put(DEFAULT_CAPACITY, "new");
        assertEquals(DEFAULT_CAPACITY, c.size());

        assertNull(c.get(1));          // evicted
        assertNotNull(c.get(0));       // still present because we accessed it
        assertEquals("new", c.get(DEFAULT_CAPACITY));
    }

    @Test
    void snapshotOrderReflectsRecency() {
        ConcurrentLRUCache<Integer, String> c = newCache();

        // Insert 0..4
        for (int i = 0; i < 5; i++) {
            c.put(i, String.valueOf(i));
        }
        // Access 2 and 0 to change recency
        c.get(2);
        c.get(0);

        List<Map.Entry<Integer, String>> snap = c.snapshot();
        assertEquals(5, snap.size());

        // Expected order: most recent first -> 0,2,4,3,1
        List<Integer> expectedOrder = List.of(0, 2, 4, 3, 1);
        List<Integer> actualOrder = new ArrayList<>();
        for (Map.Entry<Integer, String> e : snap) {
            actualOrder.add(e.getKey());
        }
        assertEquals(expectedOrder, actualOrder);
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int threadCount = 20;
        int opsPerThread = 5_000;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        AtomicInteger putCount = new AtomicInteger(0);
        AtomicInteger getCount = new AtomicInteger(0);
        CyclicBarrier startBarrier = new CyclicBarrier(threadCount + 1); // +1 for main thread
        CyclicBarrier endBarrier   = new CyclicBarrier(threadCount + 1);

        for (int t = 0; t < threadCount; t++) {
            exec.submit(() -> {
                Random rnd = new Random();
                try {
                    startBarrier.await(); // wait for all threads to start together
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < opsPerThread; i++) {
                    if (rnd.nextBoolean()) {
                        int key = rnd.nextInt(capacity * 2); // some keys outside capacity
                        int val = rnd.nextInt();
                        cache.put(key, val);
                        putCount.incrementAndGet();
                    } else {
                        int key = rnd.nextInt(capacity * 2);
                        cache.get(key);
                        getCount.incrementAndGet();
                    }
                }
                try {
                    endBarrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Let all threads start
        try {
            startBarrier.await();
            endBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
            fail("Barrier error: " + e);
        } finally {
            exec.shutdownNow();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException ex) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // ----- Invariants after the concurrent workload -----
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= capacity,
                "Cache size (" + cache.size() + ") exceeded capacity (" + capacity + ")");

        // 2. Snapshot size equals internal size (map consistency)
        List<Map.Entry<Integer, Integer>> snap = cache.snapshot();
        assertEquals(cache.size(), snap.size(),
                "Snapshot size (" + snap.size() + ") differs from reported size (" + cache.size() + ")");

        // 3. No null keys or values in snapshot
        for (Map.Entry<Integer, Integer> e : snap) {
            assertNotNull(e.getKey(), "Null key found in snapshot");
            assertNotNull(e.getValue(), "Null value found in snapshot");
        }

        // 4. All keys in snapshot are unique (map property)
        Set<Integer> keys = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : snap) {
            assertTrue(keys.add(e.getKey()), "Duplicate key " + e.getKey() + " in snapshot");
        }

        // Optional sanity check: total operations roughly match expectations
        System.out.printf("Stress test completed: %d puts, %d gets%n",
                putCount.get(), getCount.get());
    }
}
```

### Test Explanation

| Test | Purpose |
|------|---------|
| `getAndPutBasics` | Verifies simple `get`, `put`, replacement, and size tracking. |
| `lruEviction` | Checks that the least‑recently used entry is removed when capacity is exceeded and that a recent access protects an entry from eviction. |
| `snapshotOrderReflectsRecency` | Uses the package‑private `snapshot()` to confirm that the internal list order matches the expected recency after a mix of inserts and gets. |
| `concurrentStressTest` | Launches many threads that perform random `get`/`put` operations on a shared cache. After all threads finish, it asserts the core invariants: size ≤ capacity, snapshot consistency, absence of nulls, and no duplicate keys. The test also prints the total number of puts/gets for visibility. |

The stress test uses `CyclicBarrier` to ensure all worker threads start roughly simultaneously, creating high contention and exposing any race conditions. The assertions are deliberately lightweight (they only rely on the public API plus the test‑only `snapshot`) so they remain fast even after hundreds of thousands of operations.

---

**Summary**

* The `ConcurrentLRUCache` class provides a correct, thread‑safe LRU cache with O(1) average `get`/`put` using a single `ReentrantLock` for mutual exclusion and visibility.
* The accompanying JUnit 5 test suite validates both functional correctness and safety under heavy concurrent load, ensuring that the cache never violates its capacity limit and that its internal structures stay consistent.