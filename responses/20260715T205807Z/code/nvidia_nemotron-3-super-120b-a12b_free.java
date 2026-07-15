<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260715T205807Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *thread‑safe* LRU (Least‑Recently‑Used) cache that guarantees **O(1)** average time for `get` and `put`.  
The classic LRU algorithm uses a hash table for fast key lookup and a doubly‑linked list to keep the usage order.  
To make it safe under concurrent access we protect **all** mutations of the two structures (the map and the list) with a single `java.util.concurrent.locks.ReentrantLock`.  
Reads (`get`) also need the lock because they may move a node to the head of the list (the “recently used” position).  
The lock gives us a *happens‑before* relationship, guaranteeing visibility of updates across threads without the need for volatile fields or more complex lock‑stripping.

The cache is generic (`<K,V>`), capacity is configurable at construction time, and `null` keys/values are rejected (mirroring the behaviour of `ConcurrentHashMap`).

---

## 1. Implementation

```java
package com.example.cache;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * <p>All mutating operations (get, put, eviction) are guarded by a single
 * {@link ReentrantLock}. The lock provides mutual exclusion and a
 * happens‑before edge, so every thread sees a consistent view of the
 * internal {@link ConcurrentHashMap} and the doubly‑linked list.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list. */
    private static class Node<K, V> {
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
    private final ConcurrentHashMap<K, Node<K, V>> map; // O(1) lookup
    private Node<K, V> head; // most recently used
    private Node<K, V> tail; // least recently used
    private final ReentrantLock lock = new ReentrantLock(); // guards map + list

    /**
     * Creates a cache with the given maximum number of entries.
     *
     * @param capacity maximum number of entries; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2); // initial size, load factor default
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this cache contains no mapping for the key.
     *
     * <p>The operation moves the accessed node to the head of the list
     * (most‑recently used) while holding the lock.</p>
     *
     * @param key key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null}
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
     * Associates the specified value with the specified key in this cache.
     * If the key already exists, its value is replaced and the node is moved
     * to the head (most‑recently used). If the cache is at capacity, the
     * least‑recently used entry is evicted before inserting the new one.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null}
     *         if there was no mapping for {@code key}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update in‑place and move to head
                V old = existing.value;
                existing.value = value;
                moveToHead(existing);
                return old;
            }

            // possibly evict LRU entry
            if (map.size() >= capacity) {
                evictTail();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            return null; // no previous value
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries in the cache. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the configured capacity. */
    public int capacity() {
        return capacity;
    }

    /* ------------------------------------------------------------------ *
     *  Internal helper methods – all assume the lock is held by the caller.
     * ------------------------------------------------------------------ */

    /** Inserts node right after the head (most‑recently used position). */
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = null;
        if (head != null) {
            head.next = node;
        }
        head = node;
        if (tail == null) { // first element
            tail = node;
        }
    }

    /** Removes a node from the linked list. */
    private void removeNode(Node<K, V> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            // node is head
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            // node is tail
            tail = node.prev;
        }
        node.prev = node.next = null; // help GC
    }

    /** Moves an existing node to the head (most‑recently used). */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already at front
        }
        removeNode(node);
        addToHead(node);
    }

    /** Evicts the least‑recently used node (tail) from the cache. */
    private void evictTail() {
        Node<K, V> oldTail = tail;
        if (oldTail == null) {
            return; // should not happen when size >= capacity
        }
        removeNode(oldTail);
        map.remove(oldTail.key);
        // oldTail is now eligible for GC
    }
}
```

### How Thread‑Safety is Guaranteed

| Aspect | Mechanism |
|--------|-----------|
| **Mutual exclusion** | All public methods (`get`, `put`, `size`) and the private helpers that mutate the map or the list acquire the same `ReentrantLock`. Only one thread can hold the lock at a time, preventing concurrent modifications that could corrupt the list or the map. |
| **Visibility** | `ReentrantLock` provides a *happens‑before* guarantee: the unlock in one thread synchronizes‑with the subsequent lock in another thread. Consequently, any updates to the `ConcurrentHashMap` entries or the linked‑list pointers performed while the lock is held become visible to the next thread that acquires the lock. |
| **Atomicity of compound operations** | Moving a node to the head (`moveToHead`) consists of several pointer updates. Because the lock is held for the entire operation, other threads cannot observe a partially‑reordered list. |
| **Safe eviction** | Eviction (`evictTail`) removes the tail node from the list **and** deletes the corresponding entry from the map while the lock is held, guaranteeing that the size never exceeds the configured capacity. |
| **Null‑safety** | The implementation rejects `null` keys/values upfront (mirroring `ConcurrentHashMap`), avoiding the subtle race where a `null` key could be inserted by one thread and cause a `NullPointerException` in another. |

The combination of a single lock protecting both the hash table and the usage list yields a simple, correct, and easy‑to‑reason‑about concurrent LRU cache while preserving O(1) average time complexity for the core operations.

---

## 2. JUnit 5 Tests

The test suite contains:

* **Basic functional tests** – correctness of `get`, `put`, eviction, and size.
* **Concurrent stress test** – many threads perform random `get`/`put` operations for a short period; after stopping the threads we verify the internal invariants:
  1. `size() ≤ capacity`.
  2. Every node in the linked list is present in the map with the same value.
  3. The map contains no extra entries that are not reachable from the list.
  4. No `null` keys or values appear in either structure.

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int TEST_CAPACITY = 1_000;
    private static final int STRESS_THREADS = 32;
    private static final long STRESS_DURATION_MS = 2_000; // run stress for 2 seconds
    private static final int KEY_SPACE = 5_000; // larger than capacity to cause evictions

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(TEST_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        cache = null;
    }

    /* ------------------- Basic functional tests ------------------- */

    @Test
    void getReturnsNullForAbsentKey() {
        assertNull(cache.get(1));
    }

    @Test
    void putAndGetWorks() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void putReplacesExistingValue() {
        cache.put(1, "first");
        cache.put(1, "second");
        assertEquals("second", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionRespectsLRUOrder() {
        // Fill cache to capacity
        for (int i = 0; i < TEST_CAPACITY; i++) {
            cache.put(i, String.valueOf(i));
        }
        // Access key 0 to make it MRU
        cache.get(0);
        // Insert one more -> should evict key 1 (LRU)
        cache.put(TEST_CAPACITY, "new");
        assertNull(cache.get(1)); // evicted
        assertNotNull(cache.get(0)); // still present because we accessed it
        assertEquals(TEST_CAPACITY, cache.size());
    }

    @Test
    void sizeNeverExceedsCapacity() {
        for (int i = 0; i < TEST_CAPACITY * 2; i++) {
            cache.put(i, String.valueOf(i));
            assertTrue(cache.size() <= TEST_CAPACITY,
                    "size exceeded capacity after inserting key " + i);
        }
    }

    /* ------------------- Concurrent stress test ------------------- */

    @Test
    void concurrentStressMaintainsInvariants() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(STRESS_THREADS);
        AtomicBoolean stop = new AtomicBoolean(false);
        Random rand = new Random();

        // Worker that repeatedly does random gets/puts
        Runnable worker = () -> {
            while (!stop.get()) {
                int key = rand.nextInt(KEY_SPACE);
                // 70% puts, 30% gets (adjustable)
                if (rand.nextInt(10) < 7) {
                    cache.put(key, "value-" + key);
                } else {
                    cache.get(key);
                }
                // tiny pause to reduce contention a bit (optional)
                // Thread.yield();
            }
        };

        // Start all workers
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < STRESS_THREADS; i++) {
            futures.add(executor.submit(worker));
        }

        // Let them run for the configured duration
        Thread.sleep(STRESS_DURATION_MS);
        stop.set(true); // signal workers to exit

        // Wait for all workers to finish
        for (Future<?> f : futures) {
            f.get(); // propagate any exception
        }
        executor.shutdownNow();

        // ---------- Invariant checks after the stress run ----------
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= TEST_CAPACITY,
                "Size exceeded capacity after stress test: " + cache.size());

        // 2. Every node in the list is present in the map with matching value
        //    and there are no extra map entries.
        Set<Integer> keysInMap = newHashSet();
        cache.map.forEach((k, v) -> keysInMap.add(k));

        Set<Integer> keysInList = newHashSet();
        int listSize = 0;
        for (Node<Integer, String> n = cache.head; n != null; n = n.prev) {
            assertNotNull(n.key, "List node with null key");
            assertNotNull(n.value, "List node with null value");
            assertEquals(n.value, cache.map.get(n.key).value,
                    "Mismatch between list node value and map value for key " + n.key);
            keysInList.add(n.key);
            listSize++;
        }

        assertEquals(listSize, cache.size(),
                "List size does not match cache.size()");
        assertEquals(keysInMap, keysInList,
                "Map key set differs from list key set");

        // 3. No null keys/values in map (defensive)
        cache.map.forEach((k, v) -> {
            assertNotNull(k, "Null key found in map");
            assertNotNull(v, "Null value found in map");
        });
    }

    /** Helper to create a HashSet with initial capacity hint. */
    private <T> HashSet<T> newHashSet() {
        return new HashSet<>(Math.max(16, TEST_CAPACITY));
    }
}
```

### Explanation of the Stress Test

* **Workload** – Each thread repeatedly picks a random key from a range larger than the cache capacity (`KEY_SPACE = 5 000 > 1 000`). Roughly 70 % of the operations are `put`s (which may cause evictions) and 30 % are `get`s (which may move nodes to the head). This mix exercises both insertion eviction and access‑reordering paths under high concurrency.
* **Synchronization** – The only coordination between threads is the `AtomicBoolean stop` flag; the cache itself handles all internal synchronization via its internal lock.
* **Invariant verification** – After the test stops the workers, we:
  1. Confirm that the reported size never exceeds the configured capacity.
  2. Walk the doubly‑linked list from `head` to `tail` (using the `prev` pointers because we insert new nodes *after* the current head) and collect the keys.
  3. Ensure that each list node’s key/value pair matches the entry stored in the `ConcurrentHashMap`.
  4. Ensure that the key sets of the list and the map are identical (no missing or extra entries).
  5. Scan the map for any `null` keys or values (should never happen).

If any invariant is violated, the test fails, indicating a bug in the concurrent LRU ordering mistake, a lost update, or a visibility problem under concurrent access.

---

### Summary

* The `ConcurrentLRUCache` class provides a **thread‑safe**, **O(1)** average‑time LRU cache with a configurable capacity.
* Thread‑safety is achieved by guarding **all** mutations of the internal hash map and doubly‑linked list with a single `ReentrantLock`, which also guarantees visibility of updates.
* The accompanying JUnit 5 test suite validates correct sequential behavior and, crucially, checks that the cache maintains its structural invariants under heavy parallel load. This gives confidence that the implementation is safe for production‑grade concurrent use.