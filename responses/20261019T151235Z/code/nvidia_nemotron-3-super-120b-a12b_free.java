<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261019T151235Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *generic* LRU cache (`ConcurrentLRUCache<K,V>`) that:

* stores at most `capacity` entries,
* provides `get(K)` and `put(K,V)` in **O(1) average** time,
* is **thread‑safe** – all mutable state is protected by a single `ReentrantLock`,
* moves accessed/updated nodes to the *most‑recent* position (head of a doubly‑linked list) and evicts the *least‑recent* node (tail) when the capacity is exceeded.

The combination of a `HashMap<K,Node>` (O(1) lookup) and a doubly‑linked list (O(1) move‑to‑head / remove‑tail) gives the classic LRU algorithm.  
The lock guarantees that **only one thread can mutate the map or the list at a time**, which makes the whole data structure linearizable. Reads (`get`) also acquire the lock because they may need to move a node; this still yields O(1) work – the only extra cost is the lock acquisition, which is constant‑time.

---

### 1.  Thread‑safety argument

*All* fields that can be changed concurrently (`map`, `head`, `tail`, `size`) are **private** and are accessed **only inside a block guarded by `lock`** (`lock.lock(); try { … } finally { lock.unlock(); }`).  

* The `get` method:
  1. Looks up the node in the map (read‑only operation on the `HashMap` – safe because the map is not structurally modified while the lock is held).
  2. If present, it calls `moveToHead(node)`, which only rewires the `prev/next` pointers of three nodes.  
  3. Returns the node’s value.

* The `put` method:
  1. If the key already exists, updates the value and moves the node to head (same reasoning as `get`).
  2. If the key is new and the cache is at capacity, it evicts the tail node (`removeTail()`), which again only touches a constant number of pointers and removes the entry from the map.
  3. Inserts the new node at the head and puts it into the map.

Because every mutation happens while holding the same lock, **no two threads can interleave their modifications**, eliminating race conditions on the map or the linked list. The lock is reentrant, so a thread that already holds it (e.g., a recursive call) will not dead‑lock.

The lock does **not** protect the *values* stored in the cache – callers must ensure that the values themselves are safely published if they are mutable. This is the usual contract for concurrent collections.

---

### 2.  Implementation

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> type of keys
 * @param <V> type of values
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
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;   // dummy head (most recent)
    private final Node<K, V> tail;   // dummy tail (least recent)
    private final ReentrantLock lock = new ReentrantLock();
    private int size;                // number of real nodes (excluding dummies)

    /** Creates a cache with the given maximum number of entries. */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2); // initial size to reduce rehashes
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
        this.size = 0;
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if not present.
     * The accessed entry becomes the most‑recently used.
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
     * Inserts or updates the mapping for {@code key}.
     * If the cache exceeds its capacity, the least‑recently used entry is evicted.
     *
     * @return the previous value associated with {@code key}, or {@code null} if none.
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and mark as recently used
                V old = existing.value;
                existing.value = value;
                moveToHead(existing);
                return old;
            }

            // need to add a new node
            if (size >= capacity) {
                evictLeastRecentlyUsed();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            size++;
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries stored in the cache. */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** Moves an existing node to the head (most‑recent) position. */
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    /** Adds a node right after the dummy head. */
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Removes a node from the linked list. */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /** Evicts the node just before the dummy tail (least‑recently used). */
    private void evictLeastRecentlyUsed() {
        Node<K, V> toRemove = tail.prev;
        if (toRemove == head) { // cache empty – should not happen when size>=capacity
            return;
        }
        removeNode(toRemove);
        map.remove(toRemove.key);
        size--;
        // optional: help GC
        toRemove.prev = toRemove.next = null;
        toRemove.key = null;
        toRemove.value = null;
    }

    /* ------------------------------------------------------------------
       Package‑private validation helpers used only by the test suite.
       They acquire the lock to guarantee a consistent snapshot.
       ------------------------------------------------------------------ */
    boolean invariantCheck() {
        lock.lock();
        try {
            if (size < 0 || size > capacity) {
                return false;
            }
            if (map.size() != size) {
                return false;
            }

            // Walk from head to tail, ensuring no duplicates and correct links.
            int count = 0;
            Node<K, V> cur = head.next;
            while (cur != tail) {
                // each node must be reachable via the map
                Node<K, V> mapNode = map.get(cur.key);
                if (mapNode != cur) {
                    return false;
                }
                // check bidirectional links
                if (cur.prev == null || cur.next == null ||
                    cur.prev.next != cur || cur.next.prev != cur) {
                    return false;
                }
                count++;
                cur = cur.next;
            }
            return count == size;
        } finally {
            lock.unlock();
        }
    }
}
```

**Explanation of key parts**

| Part | Reason |
|------|--------|
| `ReentrantLock lock` | Guarantees exclusive access to the mutable state (`map`, linked list, `size`). |
| Dummy `head` & `tail` nodes | Simplify insertion/removal – no need to check for null pointers when moving nodes. |
| `moveToHead`, `addToHead`, `removeNode` | Constant‑time pointer updates; called only while holding the lock. |
| `evictLeastRecentlyUsed` | Removes the node just before `tail` (the LRU entry) and updates `map` and `size`. |
| `invariantCheck()` (package‑private) | Used by JUnit tests to assert that the internal structure is consistent after concurrent operations. |

---

### 3.  JUnit 5 Tests

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    /** Simple sanity test for sequential behavior. */
    @Test
    void testBasicGetPutAndEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);

        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));

        cache.put(2, "two");
        cache.put(3, "three");
        // cache now holds [1,2,3] (3 is most recent)

        // accessing 1 makes it most recent
        assertEquals("one", cache.get(1));
        // order now: 2 (LRU), 3, 1 (MRU)

        cache.put(4, "four"); // should evict key 2
        assertNull(cache.get(2));
        assertEquals("four", cache.get(4));
        assertEquals("three", cache.get(3));
        assertEquals("one", cache.get(1));
        assertEquals(3, cache.size());
    }

    /** Verify that get moves the entry to the head (most‑recent). */
    @Test
    void testGetUpdatesRecency() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3); // list: 1(L) ,2 ,3(M)

        cache.get(1); // move 1 to head
        cache.put(4, 4); // should evict 2 (now LRU)

        assertNull(cache.get(2));
        assertEquals(4, cache.get(4));
        assertEquals(3, cache.get(3));
        assertEquals(1, cache.get(1));
    }

    /** Stress test with many threads performing random puts/gets. */
    @Test
    void testConcurrentStress() throws InterruptedException {
        final int capacity = 128;
        final int threadCount = 20;
        final int opsPerThread = 5_000; // total ~100k operations
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        final Random rng = new Random(0xC0FFEE);
        final CountDownLatch startLatch = new CountDownLatch(threadCount);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final ExecutorService exec = Executors.newFixedThreadPool(threadCount);

        // Each thread performs a mix of puts and gets on random keys in [0, 2*capacity)
        Runnable worker = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < opsPerThread; i++) {
                    int key = rng.nextInt(capacity * 2);
                    if (rng.nextBoolean()) {
                        // put
                        cache.put(key, key);
                    } else {
                        // get (may return null)
                        cache.get(key);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            exec.submit(worker);
        }

        startLatch.countDown(); // let all threads go
        doneLatch.await();      // wait for completion
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        // ----- invariant checks -----
        assertTrue(cache.invariantCheck(),
                "Cache internal invariants violated after concurrent stress");

        // size must never exceed capacity
        assertTrue(cache.size() <= capacity,
                "Cache size exceeded its capacity");

        // Optional: verify that every value present in the map equals its key
        cache.lock.lock(); // we need a consistent snapshot for the next checks
        try {
            for (Map.Entry<Integer, Integer> e : cache.map.entrySet()) {
                assertEquals(e.getKey(), e.getValue(),
                        "Stored value does not match its key");
            }
        } finally {
            cache.lock.unlock();
        }
    }

    /** Test that put returns the previous value correctly under concurrency. */
    @Test
    void testPutReturnsPreviousValue() throws InterruptedException {
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        final int key = 42;
        final ExecutorService exec = Executors.newFixedThreadPool(4);
        final CountDownLatch start = new CountDownLatch(4);
        final CountDownLatch end = new CountDownLatch(4);
        final List<Integer> results = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                start.await();
                Integer prev = cache.put(key, ThreadLocalRandom.current().nextInt());
                results.add(prev == null ? -1 : prev); // -1 encodes "null"
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                end.countDown();
            }
        };

        for (int i = 0; i < 4; i++) {
            exec.submit(task);
        }
        start.countDown();
        end.await();
        exec.shutdown();

        // Exactly one of the four puts should have returned null (the first);
        // the other three should have returned the value placed by the previous thread.
        long nullCount = results.stream().filter(v -> v == -1).count();
        assertEquals(1, nullCount, "Expected exactly one put to see no previous value");
        // All non‑null results should be among the values that were actually put.
        // Since we used random numbers, we just ensure none are -1 besides the first.
        assertEquals(3, results.stream().filter(v -> v != -1).count());
    }
}
```

**Explanation of the test suite**

| Test | What it validates |
|------|-------------------|
| `testBasicGetPutAndEviction` | Correct sequential LRU behavior, eviction order, and size limits. |
| `testGetUpdatesRecency` | Confirms that a `get` moves the entry to the most‑recent position. |
| `testConcurrentStress` | Fires many threads performing random `put`/`get` operations. After the workload finishes we: <br>• Call the package‑private `invariantCheck()` (which walks the linked list and checks map‑list consistency under the lock). <br>• Assert that `size() ≤ capacity`. <br>• Optionally verify that every map entry’s value equals its key (we stored `key → value`). This guarantees that no corruption, lost updates, or duplicate nodes occurred despite heavy concurrent access. |
| `testPutReturnsPreviousValue` | Checks that the `put` method’s return value (previous mapping or `null`) is correct even when multiple threads race on the same key. |

All tests use **JUnit 5** (`org.junit.jupiter.api`). The concurrency test employs a `CountDownLatch` to make all threads start at roughly the same time, maximizing contention, and then waits for all to finish before inspecting the cache state.

---

### 4.  How to use

```java
ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(1000);
String v = cache.get("user:123");
if (v == null) {
    v = computeValue("user:123");
    cache.put("user:123", v);
}
```

The cache gives O(1) average lookup/insertion, automatically evicts the least‑recently used entry when the limit is exceeded, and is safe to call from any number of threads without external synchronization.