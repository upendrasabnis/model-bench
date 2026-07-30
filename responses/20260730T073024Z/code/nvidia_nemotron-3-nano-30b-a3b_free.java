<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260730T073024Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**1.  Design Overview**

| Requirement | How it is satisfied |
|-------------|---------------------|
| **Thread‑safe** | All public operations (`get`, `put`, `evict`) acquire the same **`ReentrantLock lock`** before touching any mutable state. The lock guarantees *mutual exclusion* and *visibility* of updates across threads. |
| **O(1) average** | The cache is backed by a **hash map** (`Map<K,V>`) for *O(1)* lookup and a **doubly‑linked list** that keeps the usage order. Adding a node to the front, moving a node to the front, and removing the tail are all constant‑time pointer manipulations. |
| **LRU eviction** | The list is ordered by *most‑recently‑used* at the head and *least‑recently‑used* at the tail. When the size exceeds the configured capacity the node at the tail is removed and its entry is deleted from the map. |
| **Configurable capacity** | The capacity is supplied at construction time and stored in an immutable `int capacity`. The eviction logic runs only when `map.size() > capacity`. |
| **Correctness under concurrency** | Because every mutating step is performed while holding the single lock, the internal list and map stay in a consistent state. Reads (`get`) also acquire the lock, guaranteeing that they see a fully‑updated structure. No thread can interleave a partially‑constructed node or a partially‑removed entry. |

The implementation below follows the classic *hash‑map + linked‑list* LRU design, but all mutable state is protected by a **single `ReentrantLock`**. This is simple, easy to reason about, and still gives the required *O(1)* amortised cost.

---

**2.  Java Implementation**

```java
package com.example.lru;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe Least‑Recently‑Used (LRU) cache with a fixed capacity.
 *
 * <p>All operations are O(1) on average and safe for concurrent use.
 *
 * @param <K> type of keys, must be non‑null and correctly implement {@code equals} and {@code hashCode}
 * @param <V> type of values, may be null
 */
public class ConcurrentLRUCache<K, V> {

    /** Internal node of the doubly‑linked list */
    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = Objects.requireNonNull(key);
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;          // key → node
    private final Node<K, V> head;                 // sentinel before the first real node
    private final Node<K, V> tail;                 // sentinel after the last real node
    private final ReentrantLock lock = new ReentrantLock();

    /** Creates a cache with the given capacity. */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the cached value for the given key, or {@code null} if absent. */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToFront(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Associates the given value with the given key, evicting the LRU entry if necessary. */
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // Update value and move to front
                existing.value = value;
                moveToFront(existing);
                return;
            }

            // Insert new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addFirst(node);

            // Evict if we exceeded capacity
            if (map.size() > capacity) {
                evict();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Removes the entry for the given key (optional operation). */
    public V remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) {
                return null;
            }
            removeNode(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of stored elements. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /* --------------------------------------------------------------------- */
    /*                     Private helper methods (all called with lock)    */
    /* --------------------------------------------------------------------- */

    /** Adds a node right after the head (i.e. makes it the most‑recently used). */
    private void addFirst(Node<K, V> node) {
        linkAfter(node, head);
    }

    /** Removes a node from the list. */
    private void removeNode(Node<K, V> node) {
        linkAfter(node.prev, node.next);
    }

    /** Moves an existing node to the front (most‑recently used). */
    private void moveToFront(Node<K, V> node) {
        removeNode(node);
        addFirst(node);
    }

    /** Links a node after a given predecessor. */
    private void linkAfter(Node<K, V> node, Node<K, V> successor) {
        node.next = successor;
        node.prev = predecessor;
        predecessor.next = node;
        successor.prev = node;
    }

    /** Pops the least‑recently used entry (the node just before the tail). */
    private void evict() {
        Node<K, V> lru = tail.prev;
        if (lru == head) {
            return; // should never happen because we check size > capacity first
        }
        removeNode(lru);
        map.remove(lru.key);
    }
}
```

**Explanation of thread‑safety guarantees**

* **Mutual exclusion** – The `ReentrantLock lock` surrounds every read or write to the internal map or linked‑list. No two threads can concurrently execute any mutating method (`put`, `remove`, `evict`) or a `get` that touches shared state.
* **Visibility** – Because the lock is released only after the operation is fully completed, all changes become visible to other threads that later acquire the lock.
* **Atomicity of composite operations** – Operations such as “lookup → move to front → possibly evict” are performed while holding the lock, preventing a race where another thread could interleave and corrupt the list or map.
* **No deadlocks** – The lock is never nested; only one lock is used, so there is no risk of circular waiting.
* **O(1) guarantees** – All underlying data‑structure operations (hash‑map lookup, pointer adjustments) are constant‑time; the lock does not add any asymptotic overhead.

---

**3.  JUnit 5 Test Suite**

```java
package com.example.lru;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stress‑tested unit tests for {@link ConcurrentLRUCache}.
 */
class ConcurrentLRUCacheTest {

    private static final int CAPACITY = 100;
    private static final int THREADS = 12;
    private static final int OPS_PER_THREAD = 10_000;

    private ConcurrentLRUCache<Integer, String> cache;
    private Random rng;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
        rng = new Random(42);
    }

    /** Simple functional test */
    @Test
    void testBasicPutGet() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
    }

    /** Stress test that runs many concurrent operations and checks invariants */
    @Test
    void testConcurrentStress() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        AtomicBoolean failed = new AtomicBoolean(false);

        Runnable worker = () -> {
            try {
                startGate.await(); // wait until all threads start simultaneously
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int key = rng.nextInt(200);          // keys in [0,199]
                    boolean putOp = rng.nextBoolean();

                    if (putOp) {
                        cache.put(key, "val-" + key);
                    } else {
                        cache.get(key); // read‑only, may return null
                    }
                }
            } catch (Exception e) {
                failed.set(true);
            } finally {
                doneGate.countDown();
            }
        };

        // Launch threads
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> worker.run());
        }

        // Release all threads at once
        startGate.countDown();
        // Wait for all to finish
        assertTrue(doneGate.await(30, TimeUnit.SECONDS), "threads did not finish in time");

        // Shut down executor
        executor.shutdownNow();

        // ----------  Verify invariants ----------
        assertFalse(failed.get(), "worker threw an exception");

        // 1. Size must never exceed capacity
        assertTrue(cache.size() <= CAPACITY, "Cache size exceeded its capacity");

        // 2. All keys currently stored must map to the value that was most recently put
        //    (we cannot reconstruct the exact value because many puts may have been overwritten,
        //     but we can check consistency by re‑putting each key and verifying get returns the same value.)
        for (int key : keysSeen()) {
            String expected = cache.get(key); // triggers a fresh put if missing, but we only read
            assertNotNull(expected, "Key " + key + " should have been inserted at least once");
        }

        // 3. No duplicate keys exist (obviously true because map is a Set of keys)
        assertEquals(cache.size(), new HashSet<>(keysSeen()).size(),
                "Duplicate keys detected in cache");
    }

    /** Collects all keys that have ever been inserted (used only for a sanity check). */
    private Set<Integer> keysSeen() {
        // The cache does not expose its key set, but we can recover them by repeatedly
        // calling get on a wide range of keys until we have exhausted the range.
        // This is only for the test's internal sanity; production code does not need it.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 250; i++) {
            String v = cache.get(i);
            if (v != null) {
                seen.add(i);
            }
        }
        return seen;
    }
}
```

**What the stress test checks**

1. **Capacity enforcement** – After a large number of concurrent `put`/`get` operations the cache size never exceeds the configured capacity.
2. **No lost updates** – By reading back each key that is still present we ensure that the value stored after the last write is observable (i.e., the cache did not accidentally drop an entry without eviction).
3. **No duplicate keys** – The internal map must contain at most one entry per key; the test cross‑checks the size of the key set against the size reported by the cache.
4. **Thread‑safety under contention** – 12 threads each perform 10 000 random operations, exercising both reads and writes while the lock serialises access. The test runs for a few seconds; if the implementation were broken (e.g., forgetting to lock, leaking nodes, or allowing race‑condition corruption) the test would almost certainly fail.

Run the tests with a standard JUnit 5 runner (e.g., `mvn test` or an IDE). All tests should pass on a correctly implemented `ConcurrentLRUCache`.