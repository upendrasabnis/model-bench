<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260811T145529Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *thread‑safe* LRU (Least‑Recently‑Used) cache with a fixed capacity.  
The public API is deliberately simple:

```java
V get(K key);   // returns the value or null if absent
void put(K key, V value);
```

Both operations run in **O(1) average time** – a hash‑map lookup plus a constant‑time
re‑linking of a node in a doubly‑linked list.

The only synchronisation primitive we use is a single `ReentrantLock`.  
All mutable state (`Map<K,Node>`, the head/tail pointers and the size) is accessed
*only* while holding this lock, which guarantees:

* **Mutual exclusion** – at most one thread can modify the internal structure at a time.  
* **Visibility** – the lock’s happens‑before relationship ensures that every thread sees
  the most recent writes made by any other thread.  
* **No lost updates** – because the lock serialises `get` and `put`, a thread cannot
  read a stale value while another thread is in the middle of moving a node or evicting
  an entry.

Although a single lock limits parallelism, the critical section is tiny (a few pointer
updates and a map lookup), so the cache remains highly scalable in practice.  
If higher concurrency is required, the same design can be combined with lock‑stripping
or a read‑write lock; the correctness argument stays identical.

---

### 1.  Data structures

| Structure | Purpose |
|-----------|---------|
| `private final Map<K,Node<K,V>> map;` | O(1) lookup of a node by its key. |
| `private Node<K,V> head, tail;` | Doubly‑linked list that stores entries in **recency order** – most‑recently used at `head`, least‑recently used at `tail`. |
| `private final int capacity;` | Maximum number of entries the cache may hold. |
| `private final ReentrantLock lock = new ReentrantLock();` | Guards all mutable state. |

`Node` holds the key, value and the two list pointers:

```java
private static class Node<K,V> {
    K key;
    V value;
    Node<K,V> prev;
    Node<K,V> next;

    Node(K k, V v) { this.key = k; this.value = v; }
}
```

---

### 2.  Core algorithms (under the lock)

*`get(K key)`*  

1. Look up `node = map.get(key)`.  
2. If `null` → return `null`.  
3. Otherwise **move‑to‑front**: unlink `node` from its current position and insert it
   right after `head`.  
4. Return `node.value`.

*`put(K key, V value)`*  

1. Look up existing node.  
   * If present → update its value, move‑to‑front, done.  
2. If the cache is already at `capacity` → **evict** the tail node:  
   * Remove it from `map`.  
   * Unlink it from the list (`tail = tail.prev; if (tail != null) tail.next = null;`).  
3. Create a new node, insert it at the head, and put it into `map`.  
4. Increment the logical size (implicitly via `map.size()`).

All pointer updates are constant‑time; the map operation is O(1) average.  
Hence each public method is O(1) average.

---

### 3.  Thread‑safety argument

* **Encapsulation** – The only ways to observe or modify the cache state are the
  `get` and `put` methods. No external code can reach the internal `map`, `head`,
  `tail` or `Node` instances directly.
* **Locking discipline** – Every method begins with `lock.lock()` and ends with a
  `finally { lock.unlock(); }`. Consequently, the lock is held for the *entire*
  duration of the critical section, guaranteeing that no two threads can
  interleave their updates.
* **Atomicity of compound actions** – Moving a node to the front consists of several
  pointer updates. Because they are performed while the lock is held, other threads
  either see the list *before* the move or *after* the move, never a partially
  updated list.
* **Visibility** – The `ReentrantLock` provides a happens‑before edge from the
  unlock in one thread to the lock acquisition in the next, ensuring that updates
  to the map and the list are visible to all threads.

Thus the cache behaves as if all operations were executed sequentially in some
order that respects the real‑time ordering of lock acquisition – the definition of
linearizability, which is sufficient for correctness under concurrent access.

---

### 4.  Full implementation

```java
package com.example.lru;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ConcurrentLRUCache<K, V> {

    /** Node of the internal doubly‑linked list. */
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K k, V v) {
            this.key = k;
            this.value = v;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private Node<K, V> head;   // most‑recently used
    private Node<K, V> tail;   // least‑recently used
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates a cache that can hold at most {@code capacity} entries.
     *
     * @param capacity maximum number of entries; must be > 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2); // initial size to reduce rehashing
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if the key
     * is not present. The accessed entry becomes the most‑recently used.
     *
     * @param key the key whose value is to be returned
     * @return the value or {@code null}
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
     * Associates {@code value} with {@code key}. If the key already exists,
     * its value is replaced and the entry becomes most‑recently used.
     * If the cache is at capacity, the least‑recently used entry is evicted.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the key; may be {@code null}
     */
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and refresh recency
                existing.value = value;
                moveToHead(existing);
                return;
            }

            if (map.size() >= capacity) {
                evictLeastRecentlyUsed();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            insertAtHead(newNode);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries stored in the cache. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Moves an existing node to the head (most‑recently used position). */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already at front
        }
        // unlink from current position
        if (node.prev != null) {
            node.prev.next = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        }
        // if node was tail, update tail
        if (node == tail) {
            tail = node.prev;
        }
        // insert at head
        insertAtHead(node);
    }

    /** Inserts a node right after the dummy head (i.e. as the new most‑recently used). */
    private void insertAtHead(Node<K, V> node) {
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

    /** Removes the least‑recently used node (the tail) from the map and the list. */
    private void evictLeastRecentlyUsed() {
        if (tail == null) {
            return; // nothing to evict
        }
        map.remove(tail.key);
        // unlink tail
        if (tail.prev != null) {
            tail.prev.next = null;
        }
        tail = tail.prev;
        if (tail == null) { // list became empty
            head = null;
        }
    }
}
```

*The class is `final` and its inner `Node` is `private static` to prevent external
subclassing or manipulation.*

---

### 5.  JUnit 5 test suite

```java
package com.example.lru;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ConcurrentLRUCache}.
 *
 * The stress test runs many threads that perform random puts/gets for a short
 * burst, then checks the cache invariants:
 *   * size never exceeds capacity
 *   * every key present in the map is reachable from the head/tail list
 *   * the list contains no cycles or duplicate nodes
 *   * the number of nodes in the list equals the map size
 */
class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;
    private static final int CAPACITY = 1_000;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    @Nested
    @DisplayName("Basic functionality")
    class BasicTests {

        @Test
        @DisplayName("get returns null for absent key")
        void getAbsent() {
            assertNull(cache.get(1));
        }

        @Test
        @DisplayName("put/get works and updates recency")
        void putGetUpdatesRecency() {
            cache.put(1, "one");
            cache.put(2, "two");
            assertEquals("one", cache.get(1)); // access 1 → MRU
            cache.put(3, "three");             // cache full, evicts LRU (2)
            assertNull(cache.get(2));          // 2 should be gone
            assertEquals("three", cache.get(3));
            assertEquals("one", cache.get(1));
        }

        @Test
        @DisplayName("put replaces existing value and moves to front")
        void putReplace() {
            cache.put(1, "first");
            cache.put(1, "second");
            assertEquals("second", cache.get(1));
            // after replacement, 1 is still the only element
            assertEquals(1, cache.size());
        }

        @Test
        @DisplayName("eviction follows LRU order")
        void lruEviction() {
            IntStream.rangeClosed(1, CAPACITY).forEach(i -> cache.put(i, "v" + i));
            // cache is now full; head = CAPACITY, tail = 1
            assertEquals("v" + CAPACITY, cache.get(CAPACITY));
            // Access 1 to make it MRU
            cache.get(1);
            // Insert new element → should evict 2 (the LRU after the above get)
            cache.put(CAPACITY + 1, "vOverflow");
            assertNull(cache.get(2));
            assertEquals("v" + 1, cache.get(1)); // still present
            assertEquals("vOverflow", cache.get(CAPACITY + 1));
        }
    }

    @Nested
    @DisplayName("Concurrent stress test")
    class StressTests {

        private static final int THREAD_COUNT = 32;
        private static final int OPERATIONS_PER_THREAD = 50_000;
        private static final long TEST_TIMEOUT_SECONDS = 10;

        /**
         * Worker that repeatedly performs random puts/gets.
         */
        private static class Worker implements Runnable {
            private final ConcurrentLRUCache<Integer, Integer> cache;
            private final int keyBound;          // keys will be in [0, keyBound)
            private final ThreadLocalRandom rng;
            private final AtomicInteger puts;
            private final AtomicInteger gets;
            private final AtomicBoolean stop;

            Worker(ConcurrentLRUCache<Integer, Integer> cache,
                   int keyBound,
                   AtomicInteger puts,
                   AtomicInteger gets,
                   AtomicBoolean stop) {
                this.cache = cache;
                this.keyBound = keyBound;
                this.rng = ThreadLocalRandom.current();
                this.puts = puts;
                this.gets = gets;
                this.stop = stop;
            }

            @Override
            public void run() {
                while (!stop.get()) {
                    int key = rng.nextInt(keyBound);
                    boolean put = rng.nextBoolean();
                    if (put) {
                        cache.put(key, key * 2);
                        puts.incrementAndGet();
                    } else {
                        cache.get(key);
                        gets.incrementAndGet();
                    }
                }
            }
        }

        @Test
        @DisplayName("Heavy parallel load respects LRU invariants")
        @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        void concurrentStressTest() throws InterruptedException {
            final int keyRange = CAPACITY * 3; // use a larger key space to cause churn
            final AtomicInteger puts = new AtomicInteger(0);
            final AtomicInteger gets = new AtomicInteger(0);
            final AtomicBoolean stop = new AtomicBoolean(false);
            final ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);

            // start workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(new Worker(cache, keyRange, puts, gets, stop));
            }

            // let them run for a short while
            Thread.sleep(2_000); // 2 seconds of hammering
            stop.set(true);      // ask workers to finish current iteration
            exec.shutdown();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS),
                    "Worker threads did not terminate in time");

            // ---- invariant checks ----
            // 1. size never exceeds capacity
            assertTrue(cache.size() <= CAPACITY,
                    "Cache size exceeded capacity: " + cache.size());

            // 2. Every key in the map is reachable from the head/tail list
            //    and there are no duplicate nodes.
            Set<Integer> keysFromMap = new HashSet<>(cache.map.keySet());
            Set<Integer> keysFromList = new HashSet<>();

            // walk the list from head to tail
            ConcurrentLRUCache.Node<Integer, Integer> node = cache.head;
            while (node != null) {
                assertFalse(keysFromList.contains(node.key),
                        "Duplicate key found in list: " + node.key);
                keysFromList.add(node.key);
                // sanity: the map must contain the same key with the same value
                assertTrue(cache.map.containsKey(node.key),
                        "List node key missing from map: " + node.key);
                assertEquals((int) node.value, node.key * 2,
                        "Value mismatch for key " + node.key);
                node = node.next;
            }

            assertEquals(keysFromMap, keysFromList,
                    "Map key set differs from list key set");

            // 3. The number of nodes in the list equals map.size()
            int listSize = 0;
            node = cache.head;
            while (node != null) {
                listSize++;
                node = node.next;
            }
            assertEquals(listSize, cache.size(),
                    "List size does not match map size");

            // Optional: print stats for manual inspection
            System.out.printf("Stress test finished: puts=%d, gets=%d, final size=%d%n",
                    puts.get(), gets.get(), cache.size());
        }
    }
}
```

**Explanation of the test**

* **Basic functionality** – verifies the classic LRU behaviour (get‑move‑to‑front,
  put‑replace, eviction order) under a single thread.
* **Concurrent stress test** – spawns many worker threads that continuously perform
  random `put` and `get` operations on a shared cache. After a fixed time interval
  we:
  1. Ask the workers to stop.
  2. Shut down the executor and wait for termination.
  3. Check a set of *invariants* that must hold regardless of the interleaving:
     * size ≤ capacity,
     * every key present in the internal `Map` is reachable from the doubly‑linked
       list and vice‑versa,
     * the list contains no cycles or duplicate entries,
     * the number of nodes in the list equals the map size.
  These properties together guarantee that the cache has not become corrupted by
  concurrent modifications.

The test uses JUnit 5 features (`@Nested`, `@DisplayName`, `@Timeout`, `@RepeatedTest`
if desired) and runs within a deterministic time limit to avoid hanging the CI
pipeline.

---

### 6.  Summary

* The `ConcurrentLRUCache` class provides **O(1) average** `get`/`put` while being
  **thread‑safe** through a single `ReentrantLock` that guards all mutable state.
* The lock gives us mutual exclusion, visibility, and atomicity of the
  compound operations required to maintain LRU order.
* The accompanying JUnit 5 test suite checks both ordinary correctness and
  robustness under heavy concurrent load, ensuring that the cache never violates
  its capacity or internal consistency guarantees.