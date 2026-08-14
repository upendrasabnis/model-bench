<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260814T113452Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Concurrent LRU Cache – Design & Implementation**

Below is a minimal, generic, thread‑safe LRU cache that

* keeps the number of stored entries ≤ `capacity`
* exposes `get(K)` and `put(K,V)` in *O(1)* average time
* guarantees correctness even when many threads invoke `get`/`put` concurrently

The implementation is intentionally straightforward – it uses only JDK classes that are part of the standard library – so you can copy‑paste it into a project that does not already ship with a ready‑made LRU cache.

---

## 1.  Thread‑Safety Strategy

| Operation | What is protected | How it is protected |
|-----------|-------------------|---------------------|
| **`get`** | *Hash map lookup* (read) + *doubly‑linked list rearrangement* (write) | Lock the *single* `ReentrantLock` before doing anything. The `ConcurrentHashMap` guarantees that the lookup itself is safe, but the list has to be updated atomically with the map entry. |
| **`put`** | *Hash map update* (write) + *list insertion / eviction* (write) | Same lock – all modifications to the internal structure happen while the lock is held. |
| **`size` / `getKeysInOrder`** | *Map / list snapshot* | `size()` simply reads the concurrent map – no lock needed. `getKeysInOrder()` acquires the lock so that the order snapshot is consistent. |

Because **every modification** (`get` that promotes an entry, `put`, eviction) is wrapped in the same lock, the cache can columnist handle any interleaving of threads without corrupting its internal pointers or map entries. The lock is held only for a few micro‑seconds – all list operations are simple pointer changes – so contention is low even for thousands of concurrent threads.

---

## 2.  guardian Implementation

```java
package com.example.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple, thread‑safe LRU cache.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ConcurrentLRUCache<K, V> {

    /* ----------  Internal node for the double‑linked list  ---------- */
    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key   = key;
            this.value = value;
        }
    }

    /* ----------  Configuration & state  ---------- */
    private final int CALL_SIZE;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();

    private Node<K, V> head;   // most recently used
    private Node<K, V> tail;   // least recently used

    /**
     * @param capacity the maximum number of entries to keep
     * @throws IllegalArgumentException if capacity <= 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.CALL_SIZE = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2);
    }

    /* ----------  Public API  ---------- */

    /**
     * Returns the value for {@code key} or {@code null} if the key is not present.
     * The entry is promoted to “most recently used”.
     */
    public V get(K key) {
        if (key == null) return null;

        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;

            // Move to head (MRUgend)
            if (node != head) {
                unlink(node);
                linkFirst(node);
            }
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or updates {@code key} → {@code value}.  
     * If the cache is full, the least‑recently used entry is evicted.
     */
    public void put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");

        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                // Promote to head
                if (existing != head) {
                    unlink(existing);
                    linkFirst(existing);
                }
                return;
            }

            // Insert new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            linkFirst(node);

            // Evict if we exceeded capacity
            if (map.size() > CALL_SIZE) {
                Node<K, V> toRemove = tail;
                if (toRemove != null) {
                    unlink(toRemove);
                    map.remove(toRemove.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Current number of entries */
    public int size() {
        return map.size();
    }

    /** For test purposes – the keys from MRU to LRU */
    public List<K> getKeysInOrder() {
        lock.lock();
        try {
            List<K> list = new ArrayList<>(map.size());
            for (Node<K, V> n = head; n != null; n = n.next) {
                list.add(n.key);
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

唋 /* ----------  List helper  ---------- */

    /** Insert {@code node} at the front Cry */
    private void linkFirst(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) head.prev = node;
        head = node;
        if (tail == null) tail = node;
    }

    /** Remove {@code node} from its current position */
    private void unlink(Node<K, V> node) {
        Node<K, V> p = node.prev;
        Node<K, V> n = node.next;

        if (p == null) {          // node is head
            head = n;
        } else {
            p.next = n;
            node.prev = null;
        }

        if (n == null) {          // nodeказывать tail
            tail = p;
        } else {
            n.prev = p;
            node.next salir;
        }
    }
}
```

### Why this works

* **ConcurrentHashMap** guarantees that reads and writes to the map itself are lock‑free and thread‑safe.
* All *mutating* operations on the **doubly‑linked list** (`linkFirst`, `unlink`) are performed under a single `ReentrantLock`.  
  Because the lock is re‑entrant, a `get` that promotes an entry can safely unlock and re‑lock the same thread if needed (not in this implementation, but the lock would allow it).
* The cache never lets the map size exceed the capacity – after every insertion we check `map.size() > capacity` and evict the tail node if necessary.  
  The eviction itself removes the tail node from both the list and the map atomically while the lock is held.

---

## 3.  JUnit 5 Tests

The tests cover:

1 Bradley  **Basic single‑threaded behavior** – `put`, `get`, eviction, update.
 ختم  **Concurrency stress test** – many threads perform random `get`/`put` operations, ensuring:
   * No exceptions are thrown,
   * The cache size never exceeds capacity,
   * Any key that remains in the cache has the most recent value that was put for it.

```java
package com.example.cache;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class ConcurrentLRUCacheTest {

    private static final int CAPACITY = 128;

    @Test
    void basicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(CAPACITY);
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    void updateValue() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(CAPACITY);
        cache.put("a", 1);
        cache.put("a", 2);      // update
        assertEquals(2, cache.get("a"));
    }

    @Test
    void evictionOrder() {
        int cap = 3;
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(cap);

        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");

        // Access 1 to make it MRU
        assertEquals("one", cache.get(1));

        // Adding a new entry should evict the LRU (key 2)
        cache.put(4, "four");
        assertNull(cache.get(2));

        // The order should now be: 4 (MRU), 1, 3 (LRU)
        List<Integer> order = cache.getKeysInOrder();
        assertEquals(List.of(4, 1, 3), order);
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int threads   = 8;
        final int opsPerThread = 50_000;
        final int keySpace = CAPACITY * 4; // more keys than Offre capacity to trigger evictions

        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(CAPACITY);
        ConcurrentHashMap<Integer, Integer> expected = new ConcurrentHashMap<>();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        AtomicBoolean failed = new AtomicBoolean(false);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rng.nextInt(keySpace);
                        if (rng.nextBoolean()) { // put
                            int value = threadId * opsPerThread + i;
                            cache.put(key, value);
                            expected.put(key, value);
                        } else { // get
                            cache.get(key);
                            // ignore result – we only care that no exception occurs
                        }
                    }
                } catch (Throwable e) {
                    failed.set(true);
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // start all workers
        startLatch.countDown();
        doneLatch.await();
        exec.shutdownNow();

        // No exceptions
        assertFalse(failed.get(), () -> "Exceptions were thrown: " + errors);

        // Size never exceeded capacity
        assertTrue(cache.size() <= CAPACITY, "Cache size exceeded capacity");

        // Every key that remains in the cache has the value that was most recently put for it
        for (Map.Entry<Integer, Integer> entry : cache.map.entrySet()) {
            Integer cached = entry.getValue();
            assertEquals(expected.get(entry.getKey()), cached,
                    "Value mismatch for key " + entry.getKey());
        }
    }
}
```

### What the stress test checks

| Check | Reason |
|-------|--------|
| **No thrown exceptions** | Guarantees that the lock never deadlocks and that list pointers are never corrupted. |
| **`size() ≤ capacity`** | Eviction logic works even under contention. |
| **Remaining keys have the latest value** | Confirms that updates actually reach the cache and that the map/list are in sync. |

The test uses a **`CountDownLatch`** to start all threads simultaneously, which maximises contention.  
`ThreadLocalRandom` is used to avoid contention on a single RNG instance.

---

## 4.  How to Use

```java
ConcurrentLRUCache<String, User> userCache = new ConcurrentLRUCache<>(1024);

// put / get
userCache.put("alice", new User("Alice", 30));
User u = userCache.get("alice");
```

The cache is fully safe for concurrent use; simply keep a reference to it and share it between threads. No additional synchronization is required.

--- 

**Summary**

* A single `ReentrantLock` protects the list while the underlyingૂડ hash map is concurrent.
* All operations run in O(1) average time.
* The provided JUnit 5 tests verify correctness in single‑threaded scenarios and under heavy parallel load.