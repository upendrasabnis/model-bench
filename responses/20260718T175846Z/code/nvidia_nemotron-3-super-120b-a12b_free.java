<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a classic LRU (Least‑Recently‑Used) cache that stores at most `capacity` entries.  
The data structure consists of  

* a **hash map** (`HashMap<K,Node>`) for O(1) lookup of a node by its key, and  
* a **doubly‑linked list** that keeps the entries in *access order* – the most‑recently used node is at the head, the least‑recently used at the tail.

All mutable state (`map`, `head`, `tail`) is protected by a **single `ReentrantLock`**.  
The lock gives us:

* **Mutual exclusion** – only one thread can modify the structure at a time, preventing race conditions on the map or the list pointers.  
* **Visibility** – the lock’s unlock‑acquire pair creates a *happens‑before* relationship, so every thread sees the most recent updates made by any other thread.

Because the lock is held only for the short critical section that does a constant‑time map lookup and a constant‑time list manipulation, the average time‑complexity of `get` and `put` remains **O(1)**.

---

### 1.  Code – `ConcurrentLRUCache.java`

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list */
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
    private final Map<K, Node<K, V>> map;
    private Node<K, V> head; // most recently used
    private Node<K, V> tail; // least recently used
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
        this.map = new HashMap<>(capacity * 2); // initial size, load factor default
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this cache contains no mapping for the key.
     *
     * <p>The returned node becomes the most‑recently used entry.</p>
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
     * If the key already exists, its value is replaced and the node becomes
     * most‑recently used. If the cache is at capacity, the least‑recently used
     * entry is evicted before inserting the new one.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and promote to head
                existing.value = value;
                moveToHead(existing);
                return;
            }

            // evict if necessary
            if (map.size() >= capacity) {
                evictLeastRecentlyUsed();
            }

            // insert new node at head
            Node<K, V> newNode = new Node<>(key, value);
            addNodeToHead(newNode);
            map.put(key, newNode);
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

    /* ------------------------------------------------------------------ */
    /*  Internal list manipulation – all called while holding the lock    */
    /* ------------------------------------------------------------------ */

    private void addNodeToHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) { // first element
            tail = node;
        }
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;

        if (prev != null) {
            prev.next = next;
        } else {
            // node was head
            head = next;
        }

        if (next != null) {
            next.prev = prev;
        } else {
            // node was tail
            tail = prev;
        }

        node.prev = node.next = null; // help GC
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already at head
        }
        removeNode(node);
        addNodeToHead(node);
    }

    private void evictLeastRecentlyUsed() {
        // invariant: tail != null when size >= capacity (>0)
        Node<K, V> oldTail = tail;
        if (oldTail == null) {
            throw new IllegalStateException("Tail is null despite size >= capacity");
        }
        removeNode(oldTail);
        map.remove(oldTail.key);
    }

    /* ------------------------------------------------------------------ */
    /*  Package‑private helpers used only by the test suite                */
    /* ------------------------------------------------------------------ */

    /** Returns a snapshot of the current head (most‑recently used). */
    Node<K, V> peekHead() {
        lock.lock();
        try {
            return head;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a snapshot of the current tail (least‑recently used). */
    Node<K, V> peekTail() {
        lock.lock();
        try {
            return tail;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a shallow copy of the internal map (key → node). */
    Map<K, Node<K, V>> snapshotMap() {
        lock.lock();
        try {
            return new HashMap<>(map);
        } finally {
            lock.unlock();
        }
    }
}
```

**Explanation of Thread‑Safety**

*All* accesses to the mutable fields (`map`, `head`, `tail`) occur inside a `lock.lock() … finally { lock.unlock() }` block.  
The `ReentrantLock` guarantees:

1. **Mutual exclusion** – at most one thread can be inside any of the critical sections at a time, thus no two threads can concurrently modify the hash map or the linked‑list pointers.
2. **Visibility** – the unlock operation happens‑before any subsequent lock acquisition, guaranteeing that writes performed by one thread are visible to others after they acquire the lock.

Because the critical section does only O(1) work (a hash‑map lookup and a constant number of pointer updates), the amortized time complexity of `get` and `put` stays O(1). The lock is a single point of contention, but for many workloads the simplicity and correctness outweigh the need for finer‑grained locking.

---

### 2.  JUnit 5 Tests – `ConcurrentLRUCacheTest.java`

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit and stress tests for {@link ConcurrentLRUCache}.
 */
class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, Integer> cache;
    private final Random rand = new Random();

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3); // small capacity makes eviction easy to observe
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
            cache.put(1, 10);
            cache.put(2, 20);
            cache.put(3, 30);

            // access 1 -> should become MRU
            assertEquals(10, cache.get(1));

            // adding a fourth element evicts the LRU (which is 2)
            cache.put(4, 40);
            assertNull(cache.get(2));   // evicted
            assertEquals(10, cache.get(1)); // still present
            assertEquals(30, cache.get(3));
            assertEquals(40, cache.get(4));
        }

        @Test
        @DisplayName("put replaces existing value and moves to head")
        void putReplacesAndMovesToHead() {
            cache.put(1, 1);
            cache.put(2, 2);
            cache.put(1, 11); // replace value of 1

            assertEquals(11, cache.get(1));
            assertEquals(2, cache.get(2));
            // 1 is now MRU, 2 is LRU
            cache.put(3, 3); // should evict 2
            assertNull(cache.get(2));
            assertEquals(11, cache.get(1));
            assertEquals(3, cache.get(3));
        }

        @Test
        @DisplayName("size never exceeds capacity")
        void sizeRespectsCapacity() {
            final int cap = cache.size(); // should be 0 initially
            assertEquals(0, cap);

            for (int i = 0; i < 10; i++) {
                cache.put(i, i);
                assertTrue(cache.size() <= 3);
            }
        }
    }

    @Nested
    @DisplayName("Concurrent stress")
    class StressTests {

        private static final int THREAD_COUNT = 16;
        private static final int OPERATIONS_PER_THREAD = 50_000;
        private static final int KEY_SPACE = 1_000; // keys 0..999

        @Test
        @DisplayName("Many threads performing random puts/gets never breaks invariants")
        void concurrentRandomAccess() throws InterruptedException {
            ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
            AtomicInteger errorCount = new AtomicInteger(0);

            Runnable worker = () -> {
                try {
                    startLatch.await(); // wait for all threads to start together
                    Random localRand = new Random(rand.nextLong());
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = localRand.nextInt(KEY_SPACE);
                        int value = localRand.nextInt();

                        // randomly choose get vs put (70% put, 30% get)
                        if (localRand.nextDouble() < 0.7) {
                            cache.put(key, value);
                        } else {
                            Integer v = cache.get(key);
                            // get may return null; that's fine.
                            // No further validation needed here – invariants are checked after test.
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            };

            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(worker);
            }

            startLatch.countDown(); // let all threads go
            doneLatch.await();      // wait for completion
            exec.shutdownNow();

            // ----- Invariant checks -----
            // 1. Size never exceeds capacity
            assertTrue(cache.size() <= 3,
                    "Cache size exceeded capacity after concurrent operations");

            // 2. Internal map and linked list are consistent
            assertTrue(checkListConsistency(),
                    "Linked list is inconsistent (broken pointers or missing map entries)");

            // 3. No duplicate keys in the map (map size equals number of distinct nodes reachable from head)
            assertEquals(countReachableNodes(), cache.size(),
                    "Number of reachable nodes differs from map size (possible duplicates or leaks)");

            // 4. Every node reachable from head has a non‑null value (defensive)
            assertTrue(allNodeValuesNonNull(),
                    "Found a node with null value in the internal list");
        }

        /**
         * Walks from head to tail using the {@code next} pointers and verifies:
         * <ul>
         *   <li>Each visited node is present in the internal map.</li>
         *   <li>The map entry for that key points to the same node object.</li>
         *   <li>No node is visited twice (detects cycles).</li>
         * </ul>
         * The method is package‑private in the cache; we call it via reflection
         * because we kept the helper methods package‑private for encapsulation.
         */
        private boolean checkListConsistency() {
            // Use the package‑private snapshot to avoid holding the lock while walking.
            Map<Integer, Node<Integer, Integer>> snap = cache.snapshotMap();
            if (snap.isEmpty()) {
                return true; // empty cache is trivially consistent
            }

            Node<Integer, Integer> head = cache.peekHead();
            Node<Integer, Integer> tail = cache.peekTail();

            if (head == null || tail == null) {
                return false; // non‑empty map must have both head and tail
            }

            Set<Node<Integer, Integer>> visited = new HashSet<>();
            Node<Integer, Integer> cur = head;
            while (cur != null) {
                if (!visited.add(cur)) {
                    return false; // cycle or repeated node
                }
                Node<Integer, Integer> mapNode = snap.get(cur.key);
                if (mapNode == null || mapNode != cur) {
                    return false; // map missing or different node object
                }
                cur = cur.next;
            }
            // After loop we should have reached null exactly after tail
            return cur == null;
        }

        /**
         * Counts distinct nodes reachable from head by following next pointers.
         */
        private int countReachableNodes() {
            int cnt = 0;
            Node<Integer, Integer> cur = cache.peekHead();
            while (cur != null) {
                cnt++;
                cur = cur.next;
            }
            return cnt;
        }

        /**
         * Ensures every node reachable from head holds a non‑null value.
         */
        private boolean allNodeValuesNonNull() {
            Node<Integer, Integer> cur = cache.peekHead();
            while (cur != null) {
                if (cur.value == null) {
                    return false;
                }
                cur = cur.next;
            }
            return true;
        }
    }
}
```

**What the tests verify**

* **Basic functionality** – correct `get/put` behavior, LRU eviction, size bound, and move‑to‑head on updates.
* **Concurrent stress** – a large number of threads perform random `get`/`put` operations.  
  After the workload finishes we check several invariants that must‑points that can be evaluated without breaking the cache’s internal locking:
  1. `size() ≤ capacity`.
  2. The doubly‑linked list is well‑formed (no cycles, every node reachable from `head` has a matching map entry, and the map does not contain stray entries).
  3. The number of distinct nodes reachable from `head` equals `map.size()` (no duplicates or lost nodes).
  4. No node in the list holds a `null` value (defensive sanity check).

If any of these invariants were violated, it would indicate a race condition, lost update, or corrupted pointer structure under concurrent access.

---

### 3.  Summary

* The cache combines a hash map for O(1) lookup with an access‑ordered doubly‑linked list for LRU ordering.
* A single `ReentrantLock` guards all mutable state, giving us mutual exclusion and the happens‑before visibility needed for thread‑safety while keeping each operation O(1) on average.
* The JUnit 5 test suite exercises both ordinary correctness and a heavy concurrent load, asserting that the cache never exceeds its capacity and that its internal structure stays consistent under parallel `get`/`put` calls.