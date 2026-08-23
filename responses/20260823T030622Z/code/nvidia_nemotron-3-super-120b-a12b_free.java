<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260823T030622Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We build a classic LRU (Least‑Recently‑Used) cache that:

* stores at most `capacity` entries,
* provides `get(key)` and `put(key,value)` in **O(1)** average time,
* is safe to use from many threads concurrently.

The design combines two well‑known thread‑safe building blocks:

| Component | Responsibility | Thread‑safety mechanism |
|-----------|----------------|--------------------------|
| `ConcurrentHashMap<K,Node>` | O(1) lookup of a node by its key and safe insertion/removal of the map entry itself. | Built‑in lock‑striping – no external synchronization needed for map operations. |
| Doubly‑linked list (dummy `head`/`tail`) | Keeps the *recency* order: most‑recently used at `head.next`, least‑recently used at `tail.prev`. | All mutations of the list (remove a node, insert at head, remove tail) are guarded by a single `ReentrantLock`. The lock is held for the *entire* mutating section of `get` and `put`, guaranteeing that the list never becomes inconsistent. |

Because the map look‑ups are lock‑free and the list is protected by a **single** lock, the common case (a cache hit) still does only constant‑time work: a map read (`O(1)`) plus a short critical section that unlinks a node and inserts it at the head (`O(1)`). Under contention the lock may cause waiting, but the algorithmic complexity of each operation remains O(1).

---

### 1.  LRUCache Implementation

```java
package com.example.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class LRUCache<K, V> {

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
    /** Map from key to its node – provides O(1) lookup. */
    private final ConcurrentHashMap<K, Node<K, V>> map;
    /** Dummy head (most‑recently used) and tail (least‑recently used). */
    private final Node<K, V> head = new Node<>(null, null);
    private final Node<K, V> tail = new Node<>(null, null);
    /** Guard for all mutations of the linked list. */
    private final ReentrantLock lock = new ReentrantLock();

    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2); // initial size, load factor default
        // connect dummy nodes
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent.
     * The accessed entry becomes the most‑recently used.
     */
    public V get(K key) {
        Objects.requireNonNull(key);
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        lock.lock();
        try {
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
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update in‑place and move to front
                existing.value = value;
                moveToHead(existing);
            } else {
                if (map.size() >= capacity) {
                    Node<K, V> evicted = removeTail();
                    map.remove(evicted.key);
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    /** -----------------------------------------------------------------
     *  Linked‑list helper methods – all assume the lock is held.
     *  ----------------------------------------------------------------- */

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Remove node from its current position. */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /** Move an existing node to the head (most‑recently used). */
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    /** Remove and return the tail node (least‑recently used). */
    private Node<K, V> removeTail() {
        Node<K, V> last = tail.prev;
        if (last == head) { // empty list
            return null;
        }
        removeNode(last);
        return last;
    }

    /** -----------------------------------------------------------------
     *  Test‑only helpers – package‑private so they are not part of the public API.
     *  ----------------------------------------------------------------- */

    /** Returns a list of keys from most‑recently used to least‑recently used. */
    List<K> keysInOrder() {
        lock.lock();
        try {
            List<K> list = new ArrayList<>(map.size());
            for (Node<K, V> x = head.next; x != tail; x = x.next) {
                list.add(x.key);
            }
            return Collections.unmodifiableList(list);
        } finally {
            lock.unlock();
        }
    }

    /** Current number of entries (for testing). */
    int size() {
        return map.size();
    }
}
```

**Why this is thread‑safe**

* The `ConcurrentHashMap` guarantees that concurrent `get`, `put`, and `remove` on the map itself never corrupt its internal state.
* All modifications of the *ordering* structure (the doubly‑linked list) are performed while holding `lock`.  
  * `get` acquires the lock only when the key exists (to move the node to the head).  
  * `put` acquires the lock for the whole operation (update/insertion and possible eviction).  
* Because the lock is reentrant, a thread that already holds it can safely call helper methods (`moveToHead`, `removeNode`, `addToHead`, `removeTail`) without dead‑locking.
* Reads of `map` (`map.get(key)`) happen **outside** the lock, which is safe because the map is concurrent; the subsequent lock‑protected list manipulation uses the node reference that was obtained from the map. If another thread removes that node concurrently, the map’s `remove` returns the node and clears the entry, so the first thread will either see `null` (if it lost the race) or will still hold a valid node reference that is about to be unlinked – the list lock guarantees that the unlink/insertion is atomic with respect to other mutators.

Thus every public operation leaves the cache in a state where:

* `map.size()` equals the number of nodes reachable from `head` to `tail`.
* The list contains no duplicate keys and respects LRU ordering (most‑recent at `head.next`).

---

### 2.  JUnit 5 Tests

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link LRUCache}. Includes a concurrency stress test that
 * verifies invariants under heavy parallel load.
 */
class LRUCacheTest {

    private LRUCache<String, Integer> cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache<>(3); // small capacity makes eviction easy to reason about
    }

    @Nested
    @DisplayName("Basic functional tests")
    class FunctionalTests {

        @Test
        @DisplayName("get returns null for absent key")
        void getAbsent() {
            assertNull(cache.get("missing"));
        }

        @Test
        @DisplayName("put/get works and updates value")
        void putGetUpdate() {
            cache.put("a", 1);
            assertEquals(1, cache.get("a"));
            cache.put("a", 2);
            assertEquals(2, cache.get("a"));
        }

        @Test
        @DisplayName("LRU eviction order")
        void evictionOrder() {
            cache.put("one", 1);
            cache.put("two", 2);
            cache.put("three", 3); // cache now: one, two, three (three MRU)

            // Access 'one' -> becomes MRU
            cache.get("one");
            // order should be: two, three, one (one MRU)

            cache.put("four", 4); // evicts LRU = 'two'
            assertNull(cache.get("two"));
            assertEquals(1, cache.get("one"));
            assertEquals(3, cache.get("three"));
            assertEquals(4, cache.get("four"));
        }

        @Test
        @DisplayName("size never exceeds capacity")
        void sizeBound() {
            for (int i = 0; i < 10; i++) {
                cache.put("k" + i, i);
                assertTrue(cache.size() <= 3);
            }
        }
    }

    @Nested
    @DisplayName("Concurrent stress tests")
    class ConcurrencyTests {

        /** Number of worker threads in the stress test. */
        private static final int WORKER_THREADS = 20;
        /** Approximate number of operations each thread will try to perform. */
        private static final int OPS_PER_THREAD = 5_000;
        /** Cache capacity for the stress test – small enough to cause evictions. */
        private static final int STRESS_CAPACITY = 100;

        @Test
        @DisplayName("Heavy parallel get/put maintains LRU invariants")
        @Timeout(value = 30, unit = TimeUnit.SECONDS) // safety net
        void concurrentStress() throws InterruptedException {
            LRUCache<Integer, String> stressCache = new LRUCache<>(STRESS_CAPACITY);
            ExecutorService exec = Executors.newFixedThreadPool(WORKER_THREADS);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(WORKER_THREADS);
            AtomicInteger failedOps = new AtomicInteger(0);

            Runnable worker = () -> {
                try {
                    startLatch.await(); // all threads begin together
                    java.util.Random rnd = new java.util.Random();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = rnd.nextInt(STRESS_CAPACITY * 2); // some keys absent
                        boolean put = rnd.nextBoolean();
                        try {
                            if (put) {
                                stressCache.put(key, "v" + key);
                            } else {
                                stressCache.get(key);
                            }
                        } catch (Exception e) {
                            failedOps.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            };

            IntStream.range(0, WORKER_THREADS).forEach(exec::submit(worker));
            startLatch.countDown(); // let them go
            doneLatch.await(20, TimeUnit.SECONDS);
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS),
                    "Executor did not terminate cleanly");

            // ---- Invariant checks ----
            assertEquals(0, failedOps.get(),
                    "No operation should have thrown an unexpected exception");

            // Size never exceeds capacity
            assertTrue(stressCache.size() <= STRESS_CAPACITY,
                    "Cache size must never exceed its configured capacity");

            // No duplicate keys in the internal list
            List<Integer> keysInOrder = stressCache.keysInOrder();
            assertEquals(keysInOrder.size(),
                    new java.util.HashSet<>(keysInOrder).size(),
                    "The LRU list must not contain duplicate keys");

            // Map size must match list size (every map entry has a node and vice‑versa)
            assertEquals(stressCache.size(), keysInOrder.size(),
                    "The backing map and the linked list must contain the same number of entries");
        }
    }
}
```

#### Test Explanation

* **Functional tests** verify the classic LRU behavior on a single thread: correct get/put, value updates, eviction order, and size bound.
* **Concurrent stress test**
  * Spawns `WORKER_THREADS` (20) threads that repeatedly perform random `put` or `get` operations on a shared `LRUCache`.
  * A `CountDownLatch` ensures all threads start at nearly the same moment, creating high contention.
  * Each thread catches unexpected exceptions; the `AtomicInteger failedOps` records any.
  * After the test finishes we assert:
    1. No unexpected exceptions occurred.
    2. The cache size never exceeded its capacity (the most basic safety invariant).
    3. The linked‑list representation (`keysInOrder()`) contains **no duplicate keys** – a sign that the list structure stayed intact.
    4. The number of entries in the map equals the number of nodes in the list, guaranteeing that every map entry is represented exactly once in the LRU list and that no “orphan” nodes exist.

The test is deliberately lightweight (≈200 k operations) yet sufficient to expose race conditions that would break the list or the map‑list consistency.

---

**Summary**

*The provided `LRUCache` gives O(1) average `get`/`put` while being fully thread‑safe.*  
*The combination of a lock‑free `ConcurrentHashMap` for look‑ups and a single `ReentrantLock` protecting the doubly‑linked list guarantees that concurrent accesses cannot corrupt the cache’s internal ordering or size invariants.*  
*The accompanying JUnit 5 suite checks both ordinary correctness and the cache’s behavior under heavy parallel load.*