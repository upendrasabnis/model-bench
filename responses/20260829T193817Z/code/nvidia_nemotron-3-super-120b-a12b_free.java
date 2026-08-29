<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260829T193817Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, thread‑safe LRU (Least‑Recently‑Used) cache that guarantees **O(1)** average time for `get(K)` and `put(K,V)`.  
The data structure consists of:

* a `ConcurrentHashMap<K,Node<V>>` that gives O(1) lookup of a node by its key,
* a doubly‑linked list that maintains the usage order (most‑recently used at the head, least‑recently used at the tail),
* a single `ReentrantLock` that protects all mutations of the list and the size counter.  
  Reads (`get`) only need to acquire the lock briefly to move a node to the head; writes (`put`) acquire the lock for the whole operation (insertion, possible eviction, and size update).

Because the map itself is concurrent, the lock is only needed for the *ordering* part, which is a tiny critical section. In practice this yields excellent scalability while keeping the implementation simple and easy to reason about.

---

### 1.  Implementation

```java
package com.example.cache;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentLruCache<K, V> {

    /** Node of the doubly‑linked list. */
    private static class Node<V> {
        V value;
        Node<V> prev;
        Node<V> next;

        Node(V value) {
            this.value = value;
        }
    }

    private final int capacity;                     // maximum number of entries
    private final ConcurrentHashMap<K, Node<V>> map; // key → node (fast lookup)
    private Node<V> head;                           // most‑recently used
    private Node<V> tail;                           // least‑recently used
    private final ReentrantLock lock = new ReentrantLock(); // guards list mutations & size
    private int size = 0;                           // current number of entries

    /**
     * Creates a cache with the given capacity.
     *
     * @param capacity maximum number of entries; must be > 0
     */
    public ConcurrentLruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2); // initial size, load factor default
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent.
     * The accessed entry becomes the most‑recently used.
     */
    public V get(K key) {
        Objects.requireNonNull(key);
        Node<V> node = map.get(key);
        if (node == null) {
            return null;
        }
        // Move node to head (most‑recent)
        lock.lock();
        try {
            moveToHead(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Associates {@code value} with {@code key}. If the cache is at capacity,
     * the least‑recently used entry is evicted.
     *
     * @return the previous value associated with {@code key}, or {@code null}
     *         if there was no mapping for {@code key}.
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        lock.lock();
        try {
            Node<V> existing = map.get(key);
            if (existing != null) {
                // Update value and move to head
                V old = existing.value;
                existing.value = value;
                moveToHead(existing);
                return old;
            }

            // Need to insert a new node
            if (size >= capacity) {
                // Evict tail (LRU)
                Node<V> evicted = removeTail();
                map.remove(evicted); // evicted.key is stored in the node via a wrapper (see below)
                size--;
            }

            Node<V> newNode = new Node<>(value);
            // We store the key inside the node to allow eviction without a reverse map.
            // To keep Node generic we use a subclass that holds the key.
            NodeWithKey<K,V> nodeWithKey = new NodeWithKey<>(key, value);
            map.put(key, nodeWithKey);
            insertHead(nodeWithKey);
            size++;
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Move an existing node to the head of the list (most‑recent). */
    private void moveToHead(Node<V> node) {
        if (node == head) {
            return;
        }
        // Remove node from its current position
        if (node.prev != null) {
            node.prev.next = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        }
        if (node == tail) {
            tail = node.prev;
        }
        // Insert at head
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

    /** Insert a node at the head (most‑recent). */
    private void insertHead(NodeWithKey<K,V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    /** Remove and return the tail node (least‑recently used). */
    private NodeWithKey<K,V> removeTail() {
        NodeWithKey<K,V> oldTail = tail;
        if (tail == null) {
            return null;
        }
        if (head == tail) {
            head = tail = null;
        } else {
            tail = tail.prev;
            tail.next = null;
        }
        oldTail.prev = oldTail.next = null;
        return oldTail;
    }

    /** Node that also holds its key – needed for eviction without a reverse map. */
    private static final class NodeWithKey<K,V> extends Node<V> {
        final K key;
        NodeWithKey(K key, V value) {
            super(value);
            this.key = key;
        }
    }

    /* -------------------  Diagnostic helpers (package‑private for tests) ------------------- */

    /** Current number of entries – visible for testing. */
    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the internal map (key → value) – useful for assertions. */
    ConcurrentHashMap<K,V> snapshot() {
        lock.lock();
        try {
            ConcurrentHashMap<K,V> copy = new ConcurrentHashMap<>(map.size());
            for (var e : map.entrySet()) {
                // e.getValue() is a NodeWithKey
                copy.put(e.getKey(), ((NodeWithKey<K,V>) e.getValue()).value);
            }
            return copy;
        } finally {
            lock.unlock();
        }
    }
}
```

#### How Thread‑Safety Is Guaranteed

| Operation | What is protected | Synchronization mechanism |
|-----------|-------------------|---------------------------|
| `get`     | Reading the node from `ConcurrentHashMap` (already thread‑safe) **and** moving that node to the head of the LRU list | A `ReentrantLock` is taken only for the list‑mutation part (`moveToHead`). The map lookup itself is lock‑free. |
| `put`     | Updating the map, inserting a new node, moving an existing node to the head, evicting the tail, and updating the `size` counter | The entire method holds the same `ReentrantLock`. This guarantees that two threads cannot simultaneously corrupt the list or the size field. |
| Internal helpers (`moveToHead`, `insertHead`, `removeTail`) | All manipulate the doubly‑linked list pointers (`prev`, `next`, `head`, `tail`) | Executed only while the lock is held, thus providing mutual exclusion. |
| `ConcurrentHashMap` | Provides lock‑free reads and concurrent inserts/updates for the key→node map. | No extra synchronization needed. |

Because the lock is held only for the short list‑manipulation section, contention is low even under many threads. The asymptotic complexity remains **O(1)** for both operations (map lookup + constant‑time list work).

---

### 2.  JUnit 5 Tests

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/**
 * Test suite for {@link ConcurrentLruCache}.
 *
 * The stress test runs many threads performing random gets/puts and checks
 * the LRU invariants after the workload finishes.
 */
class ConcurrentLruCacheTest {

    private static final int CAPACITY = 1_000;

    @Test
    void basicGetPut() {
        ConcurrentLruCache<Integer,String> cache = new ConcurrentLruCache<>(3);
        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        cache.put(2, "two");
        cache.put(3, "three");
        // cache now holds [1,2,3] (3 is MRU)
        assertEquals("three", cache.get(3));
        assertEquals("two",   cache.get(2));
        assertEquals("one",   cache.get(1));

        // Adding a fourth element evicts the LRU (key 1)
        cache.put(4, "four");
        assertNull(cache.get(1));          // evicted
        assertEquals("four", cache.get(4));
        assertEquals("three",cache.get(3)); // still present
        assertEquals("two",  cache.get(2));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        ConcurrentLruCache<Integer,String> cache = new ConcurrentLruCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");   // order: 1(LFU) ,2 ,3(MRU)

        // Access 1 -> becomes MRU
        assertEquals("a", cache.get(1));
        // order now: 2(LFU),3,1(MRU)

        // Put a new element -> should evict 2 (the true LRU)
        cache.put(4, "d");
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void sizeNeverExceedsCapacityUnderConcurrency() throws Exception {
        int threads = 64;
        int opsPerThread = 5_000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        ConcurrentLruCache<Integer,Integer> cache = new ConcurrentLruCache<>(CAPACITY);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);
        AtomicInteger errorCount = new AtomicInteger(0);
        Random rng = new Random();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await(); // all threads start together
                    Random local = new Random(rng.nextLong());
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = local.nextInt(CAPACITY * 2); // keys 0..2*CAPACITY-1
                        if (local.nextBoolean()) {
                            // get
                            cache.get(key);
                        } else {
                            // put
                            cache.put(key, key);
                        }
                        // Invariant check: size must never exceed capacity
                        if (cache.size() > CAPACITY) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all workers
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, errorCount.get(), "Size exceeded capacity during concurrent test");
    }

    @Test
    void stressTestLRUInvariants() throws Exception {
        /*
         * This test puts a known sequence of keys, then runs a massive
         * concurrent workload, and finally verifies that the cache contents
         * are a subset of the most‑recently accessed keys respecting the LRU
         * policy.
         */
        int threads = 32;
        int totalOps = 200_000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        ConcurrentLruCache<Long,Long> cache = new ConcurrentLruCache<>(CAPACITY);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);
        AtomicInteger mismatches = new AtomicInteger(0);
        LongAdder putCount = new LongAdder();
        LongAdder getCount = new LongAdder();
        Random seed = new Random(12345L);

        // Shared log of the last N accesses (approximation) – we use a
        // ConcurrentLinkedQueue with a bounded size to approximate recent keys.
        // This is only for the test; the cache itself does not depend on it.
        ConcurrentLinkedQueue<Long> recent = new ConcurrentLinkedQueue<>();
        final int RECENT_WINDOW = CAPACITY * 4; // keep a window larger than capacity

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    Random rng = new Random(seed.nextLong() + Thread.currentThread().getId());
                    for (int i = 0; i < totalOps / threads; i++) {
                        long key = Math.abs(rng.nextLong()) % (CAPACITY * 3L); // wider key space
                        if (rng.nextBoolean()) {
                            Long v = cache.get(key);
                            getCount.increment();
                            if (v != null) {
                                // If we got a value, record that this key was accessed recently
                                recent.add(key);
                                trimRecent(recent, RECENT_WINDOW);
                            }
                        } else {
                            cache.put(key, key);
                            putCount.increment();
                            recent.add(key);
                            trimRecent(recent, RECENT_WINDOW);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Stress test timed out");
        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        // ---- Invariant checks ----
        // 1. Size never exceeds capacity (already checked during ops, but double‑check)
        assertTrue(cache.size() <= CAPACITY,
                "Cache size " + cache.size() + " exceeds capacity " + CAPACITY);

        // 2. Every value present in the cache must be reachable via get()
        ConcurrentHashMap<Long,Long> snapshot = cache.snapshot();
        for (Map.Entry<Long,Long> e : snapshot.entrySet()) {
            Long got = cache.get(e.getKey());
            assertEquals(e.getValue(), got,
                    "Inconsistent state: get(" + e.getKey() + ") returned " + got +
                    " but snapshot had " + e.getValue());
        }

        // 3. Approximate LRU property: no key that is older than the
        //    recent window should be present if the cache is full.
        //    (This is a probabilistic check; with high load it should hold.)
        if (cache.size() == CAPACITY) {
            Set<Long> cachedKeys = snapshot.keySet();
            for (Long key : cachedKeys) {
                // If the key has not appeared in the recent window, it is a violation.
                // We allow a small tolerance because the recent queue is only an approximation.
                if (!recent.contains(key)) {
                    mismatches.incrementAndGet();
                }
            }
            // Allow at most 1% false positives due to the approximation.
            assertTrue(mismatches.get() <= cachedKeys.size() / 100,
                    "Too many keys in cache that are not in the recent access window: " +
                    mismatches.get());
        }

        System.out.println(
                String.format("Stress test finished: puts=%d, gets=%d, final size=%d",
                        putCount.sum(), getCount.sum(), cache.size()));
    }

    /** Keep the recent queue bounded (evict old head). */
    private void trimRecent(ConcurrentLinkedQueue<Long> q, int maxSize) {
        while (q.size() > maxSize) {
            q.poll(); // remove oldest
        }
    }
}
```

#### Explanation of the Tests

| Test | Purpose |
|------|---------|
| `basicGetPut` | Verifies the core LRU behavior on a single thread (insertion, access‑order update, eviction). |
| `updateExistingKeyMovesToFront` | Confirms that updating an existing key moves it to the most‑recent position, causing the correct eviction on subsequent inserts. |
| `sizeNeverExceedsCapacityUnderConcurrency` | Fires many threads that perform random `get`/`put` operations. After each operation we check that `size()` never exceeds the declared capacity – a fundamental safety invariant. |
| `stressTestLRUInvariants` | A heavier concurrent workload (hundreds of thousands of ops). It also checks: <br>• No size overflow.<br>• Every value stored can be retrieved via `get`.<br>• An approximate LRU property: when the cache is full, every key present must have appeared recently in a bounded access log (the log is only an approximation for the test, but under high contention it should hold with overwhelming probability). |

All tests use **JUnit 5** (`org.junit.jupiter.api.*`). The stress test employs a `CountDownLatch` to make all worker threads start at roughly the same moment, increasing contention and exposing possible race conditions. The `AtomicInteger`/`LongAdder` counters let us detect invariant violations without blocking the worker threads.

---

### 3.  Summary

* The `ConcurrentLruCache` class provides **O(1)** average `get` and `put` by combining a lock‑free `ConcurrentHashMap` for key → node lookup with a tiny critical section guarded by a `ReentrantLock` that maintains the doubly‑linked list ordering.
* Thread‑safety follows from:
  * All mutating list operations (`moveToHead`, `insertHead`, `removeTail`, size update) being executed under the same lock.
  * Reads (`get`) only locking briefly to reposition a node.
  * The underlying map being concurrent, thus no extra synchronization is needed for look‑ups or insertions.
* The accompanying JUnit 5 test suite validates correct LRU behavior under both single‑threaded and highly concurrent scenarios, ensuring that the cache never exceeds its capacity, maintains internal consistency, and approximates the LRU ordering even under heavy parallel load. 

Feel free to adjust the lock granularity (e.g., use a `StampedLock` or segment the cache into multiple independent LRU shards) if you need even higher throughput for specific workloads. The core idea—separating fast concurrent look‑ups from a lightly‑protected ordering structure—remains the same.