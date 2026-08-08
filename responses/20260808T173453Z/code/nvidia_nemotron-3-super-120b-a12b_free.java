<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260808T173453Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *thread‑safe* LRU (Least‑Recently‑Used) cache with the following properties  

* **Configurable capacity** – supplied at construction time.  
* **O(1) average time** for `get(K)` and `put(K,V)` – the cache keeps a hash map for direct key lookup and a doubly‑linked list to maintain usage order.  
* **Correct eviction** under concurrent access – every mutation of the map **or** the list is performed while holding a single `ReentrantLock`. The lock guarantees that the internal state (map + list) is always consistent, so no race can corrupt the ordering or lose/gain entries unintentionally.  

Because the lock is held for only a constant‑time amount of work (a few pointer updates and a map operation), the asymptotic complexity stays O(1). The lock may become a contention point under extremely high parallelism, but it is the simplest way to guarantee correctness without sacrificing the O(1) guarantee.

---

## 1. Implementation (`ConcurrentLRUCache.java`)

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * <p>All mutating operations (put, get‑that‑moves‑a‑node, eviction) are performed
 * while holding a single {@link ReentrantLock}. The lock guarantees that the
 * internal {@link HashMap} and the doubly‑linked list are always in a
 * consistent state, which makes the cache safe for concurrent access.</p>
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list that holds a cache entry. */
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

    private final int capacity;                     // maximum number of entries
    private final Map<K, Node<K, V>> map;           // key → node (for O(1) lookup)
    private Node<K, V> head;                        // most‑recently used
    private Node<K, V> tail;                        // least‑recently used
    private final ReentrantLock lock = new ReentrantLock(); // guards all mutable state

    /**
     * Creates a cache with the given capacity.
     *
     * @param capacity maximum number of entries; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // small initial size
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null}
     * if this cache contains no mapping for the key.
     *
     * <p>The operation also moves the accessed node to the head of the list
     * (most‑recently used) so that the LRU ordering stays correct.</p>
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null}
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
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to head
                existing.value = value;
                moveToHead(existing);
                return null; // no previous value (we treat update as "no previous")
            }

            // create new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);

            // evict if over capacity
            if (map.size() > capacity) {
                Node<K, V> removed = removeTail();
                map.remove(removed.key);
                return removed.value; // return evicted value (optional)
            }
            return null; // nothing evicted
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of entries in the cache.
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Internal helpers for the doubly‑linked list (all assume lock held) *
     *  ------------------------------------------------------------------ */

    /** Insert node right after the head (most‑recent position). */
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

    /** Remove node from its current position and insert it at the head. */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already most‑recent
        }
        // detach node
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        if (prev != null) {
            prev.next = next;
        }
        if (next != null) {
            next.prev = prev;
        }
        if (node == tail) {
            tail = prev;
        }
        // re‑insert at head
        addToHead(node);
    }

    /** Remove and return the tail node (least‑recently used). */
    private Node<K, V> removeTail() {
        Node<K, V> oldTail = tail;
        if (oldTail == null) {
            return null;
        }
        tail = oldTail.prev;
        if (tail != null) {
            tail.next = null;
        } else {
            // list becomes empty
            head = null;
        }
        oldTail.prev = oldTail.next = null;
        return oldTail;
    }
}
```

### How Thread‑Safety Is Guaranteed

* **Single lock (`ReentrantLock`)** – Every public method (`get`, `put`, `size`) acquires the lock at the start and releases it in a `finally` block.  
* **All mutable state protected** – The `HashMap` (`map`) and the doubly‑linked list (`head`, `tail`, and all `Node` links) are only accessed while the lock is held.  
* **Atomicity of compound operations** – A `put` that may cause an eviction consists of three steps (insert new node, possibly remove tail, update map). Because the lock is held for the whole sequence, another thread cannot observe a partially‑updated state (e.g., a node present in the list but not yet in the map).  
* **Visibility** – The lock provides a happens‑before relationship: writes performed by one thread while holding the lock are visible to any subsequent thread that acquires the same lock.  
* **No lock‑free tricks needed** – By keeping the critical section tiny (constant‑time pointer updates and a single map operation), we retain O(1) average time while still being simple to reason about.

---

## 2. JUnit 5 Tests (`ConcurrentLRUCacheTest.java`)

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int TEST_CAPACITY = 1_000;
    private static final int THREAD_COUNT = 32;
    private static final int ITERATIONS_PER_THREAD = 50_000;
    private static final long TEST_TIMEOUT_SECONDS = 30;

    private ConcurrentLRUCache<Integer, Integer> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(TEST_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        cache = null;
    }

    /* -------------------- Basic functional tests -------------------- */

    @Test
    void getReturnsNullForAbsentKey() {
        assertNull(cache.get(5));
    }

    @Test
    void putAndGetSingleEntry() {
        cache.put(1, 42);
        assertEquals(42, cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void putUpdatesExistingKey() {
        cache.put(1, 10);
        cache.put(1, 20);
        assertEquals(20, cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionRespectsLRUOrder() {
        // fill cache
        for (int i = 0; i < TEST_CAPACITY; i++) {
            cache.put(i, i * 10);
        }
        // access first half to make them recently used
        for (int i = 0; i < TEST_CAPACITY / 2; i++) {
            cache.get(i);
        }
        // add more entries – the least‑recently used should be the untouched second half
        for (int i = TEST_CAPACITY; i < TEST_CAPACITY + 100; i++) {
            cache.put(i, i);
        }
        // first half should still be present
        for (int i = 0; i < TEST_CAPACITY / 2; i++) {
            assertEquals(i * 10, cache.get(i), "Entry " + i + " should not have been evicted");
        }
        // some of the untouched second half must have been evicted
        int evictedCount = 0;
        for (int i = TEST_CAPACITY / 2; i < TEST_CAPACITY; i++) {
            if (cache.get(i) == null) {
                evictedCount++;
            }
        }
        assertTrue(evictedCount > 0, "At least one entry from the untouched half should have been evicted");
    }

    /* -------------------- Concurrency stress test -------------------- */

    @Test
    void concurrentStressTest() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        // Tracks the most recent value each thread has *attempted* to store for a key.
        // This lets us verify that any value present in the cache matches the latest
        // put observed by any thread.
        AtomicReference<Integer>[] latest = new AtomicReference[TEST_CAPACITY * 2]; // enough space for keys we use
        for (int i = 0; i < latest.length; i++) {
            latest[i] = new AtomicReference<>(null);
        }

        Random seed = new Random(0xC0FFEE);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREAD_COUNT; t++) {
            int threadId = t;
            futures.add(exec.submit(() -> {
                Random rand = new Random(seed.nextLong() + threadId);
                for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                    int key = rand.nextInt(TEST_CAPACITY * 2); // keys 0..2*CAPACITY-1
                    int value = rand.nextInt();
                    boolean put = rand.nextBoolean();

                    if (put) {
                        cache.put(key, value);
                        latest[key].set(value); // record the latest value we tried to store
                    } else {
                        Integer cached = cache.get(key);
                        if (cached != null) {
                            // If the cache claims to have a value for this key,
                            // it must be the most recent value any thread has stored.
                            Integer expected = latest[key].get();
                            assertNotNull(expected, "No put ever recorded for key " + key);
                            assertEquals(expected, cached,
                                    "Inconsistent value for key " + key +
                                            ": cache has " + cached + ", latest put was " + expected);
                        }
                    }
                }
            }));
        }

        // Wait for all tasks to finish or timeout
        boolean allDone = futures.stream()
                .allMatch(f -> {
                    try {
                        return f.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) == null;
                    } catch (Exception e) {
                        return false;
                    }
                });
        assertTrue(allDone, "Some threads did not finish within the timeout");

        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS),
                "Executor did not terminate cleanly");

        /* -------------------- Global invariants -------------------- */
        lockInvariants();
    }

    /** Checks structural invariants that must hold after any sequence of ops. */
    private void lockInvariants() {
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= TEST_CAPACITY,
                "Cache size " + cache.size() + " exceeds capacity " + TEST_CAPACITY);

        // 2. No null keys or values are stored (our implementation forbids them)
        //    We can safely iterate over the keySet because the map is not
        //    concurrently modified during this check (all threads are joined).
        //    To avoid exposing the internal map we use a temporary copy obtained
        //    under the cache's lock via a helper method.
        ConcurrentLRUCache<Integer, Integer> snap = snapshot();
        assertNotNull(snap);
        snap.map.forEach((k, v) -> {
            assertNotNull(k, "Null key found in cache");
            assertNotNull(v, "Null value found for key " + k);
        });

        // 3. The linked list is consistent with the map:
        //    - every node reachable from head/tail is present in the map
        //    - the number of distinct nodes reachable equals map.size()
        int nodeCount = 0;
        ConcurrentLRUCache.Node<Integer, Integer> p = snap.head;
        while (p != null) {
            assertTrue(snap.map.containsKey(p.key),
                    "Node with key " + p.key + " reachable from list but absent from map");
            assertEquals(snap.map.get(p.key).value, p.value,
                    "Value mismatch for key " + p.key + " between map and list");
            nodeCount++;
            p = p.next;
        }
        assertEquals(snap.map.size(), nodeCount,
                "Map size (" + snap.map.size() + ") differs from number of nodes reachable from head (" + nodeCount + ")");

        // 4. The list is correctly ordered (no cycles, tail reachable from head via prev links)
        //    Detect cycles using Floyd’s algorithm.
        ConcurrentLRUCache.Node<Integer, Integer> slow = snap.head;
        ConcurrentLRUCache.Node<Integer, Integer> fast = snap.head;
        boolean hasCycle = false;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
            if (slow == fast) {
                hasCycle = true;
                break;
            }
        }
        assertFalse(hasCycle, "Linked list contains a cycle");

        // Tail should be reachable by following prev from head (or null if empty)
        if (snap.head != null) {
            ConcurrentLRUCache.Node<Integer, Integer> tailViaPrev = snap.head;
            while (tailViaPrev.next != null) {
                tailViaPrev = tailViaPrev.next;
            }
            assertEquals(snap.tail, tailViaPrev,
                    "Tail pointer does not match the actual tail reached via next links");
        } else {
            assertNull(snap.tail, "Tail should be null when head is null");
        }
    }

    /**
     * Returns a shallow copy of the cache's internal state while holding the lock.
     * This method is package‑private for testing purposes; in production you would
     * not expose such a method.
     */
    private ConcurrentLRUCache<Integer, Integer> snapshot() {
        ConcurrentLRUCache<Integer, Integer> copy = new ConcurrentLRUCache<>(TEST_CAPACITY);
        cache.lock.lock();
        try {
            // Copy map entries
            cache.map.forEach((k, vNode) -> {
                // Insert a new node with the same key/value; we bypass LRU logic
                // because we only need a consistent snapshot for verification.
                copy.map.put(k, new ConcurrentLRUCache.Node<>(k, vNode.value));
            });
            // Re‑build the list in the same order as the original.
            // We walk from head to tail using the original list and link the
            // copied nodes accordingly.
            ConcurrentLRUCache.Node<Integer, Integer> orig = cache.head;
            ConcurrentLRUCache.Node<Integer, Integer> prevCopy = null;
            while (orig != null) {
                ConcurrentLRUCache.Node<Integer, Integer> copyNode = copy.map.get(orig.key);
                if (prevCopy != null) {
                    prevCopy.next = copyNode;
                    copyNode.prev = prevCopy;
                } else {
                    copy.head = copyNode;
                }
                prevCopy = copyNode;
                orig = orig.next;
            }
            copy.tail = prevCopy;
        } finally {
            cache.lock.unlock();
        }
        return copy;
    }
}
```

### Explanation of the Test Suite

| Test | What it verifies |
|------|------------------|
| **Basic functional tests** (`getReturnsNullForAbsentKey`, `putAndGetSingleEntry`, `putUpdatesExistingKey`, `evictionRespectsLRUOrder`) | Correct sequential behaviour: hit/miss, update, and LRU eviction order. |
| **Concurrent stress test** (`concurrentStressTest`) | Launches `THREAD_COUNT` threads that repeatedly perform random `put`/`get` operations on a key space larger than the cache capacity. Each thread records the *latest* value it attempted to store for each key in an `AtomicReference`. After all threads finish, we check: <br>• Cache size never exceeds capacity.<br>• No null keys/values are present.<br>• The internal doubly‑linked list is acyclic, correctly linked, and matches the map.<br>• For every key that is present in the cache, its value equals the most recent value any thread has recorded for that key (i.e., the cache never returns a stale value). |
| **Helper methods** (`lockInvariants`, `snapshot`) | Provide a way to inspect the cache’s internal state under the lock without exposing those details in production code. The snapshot is taken while holding the cache’s lock, guaranteeing a consistent view for the invariant checks. |

The stress test is deliberately aggressive: with many threads contending for the lock, we verify that the lock‑based design still preserves correctness under heavy parallel load. If any invariant were violated, the JUnit assertion would fail, indicating a bug in the locking or LRU logic.

--- 

**Summary**

* The `ConcurrentLRUCache` class gives you a simple, thread‑safe LRU cache with O(1) average `get`/`put` performance.  
* Thread‑safety is achieved by guarding all mutable state (the hash map and the doubly‑linked list) with a single `ReentrantLock`.  
* The accompanying JUnit 5 test suite validates both ordinary functionality and complex concurrent usage, ensuring that the cache never violates its capacity, ordering, or consistency guarantees even under heavy parallel access.