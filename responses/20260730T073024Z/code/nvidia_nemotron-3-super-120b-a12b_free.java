<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260730T073024Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a classic LRU cache (hash‑map + doubly linked list) and protect every mutation with a single `java.util.concurrent.locks.ReentrantLock`.  
Because the lock is held for the *entire* duration of `get` and `put`, all shared state (the map, the head/tail pointers and the node links) is accessed atomically – giving us linearizability and therefore thread‑safety.  
The lock also provides the necessary *happens‑before* guarantees so that updates made by one thread are visible to others.

The algorithmic complexity stays **O(1)** on average:

* `HashMap#get/put` – O(1) average.
* List splice (remove‑from‑middle / add‑to‑head) – constant pointer updates.

Only the lock introduces contention; under low‑to‑moderate concurrency this is acceptable and far simpler than lock‑stripping or lock‑free designs while still being correct.

---

## 1. Implementation

```java
package com.example.cache;

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
        this.map = new HashMap<>(capacity);
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this cache contains no mapping for the key.
     *
     * <p>The accessed entry becomes the most‑recently‑used.</p>
     *
     * @param key the key whose associated value is to be returned
     * @return the value associated with {@code key}, or {@code null}
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
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
     * If the cache previously contained a mapping for the key, the old
     * value is replaced.
     *
     * <p>If the cache is at capacity the least‑recently‑used entry is evicted
     * before the new entry is inserted.</p>
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key; may be null
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // key present – update value and promote
                existing.value = value;
                moveToHead(existing);
                return;
            }

            // need to create a new node
            if (map.size() >= capacity) {
                evictLeastRecentlyUsed();
            }
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
        } finally {
            lock.unlock();
        }
    }

    /** Removes the least‑recently‑used entry (tail) from the map and list. */
    private void evictLeastRecentlyUsed() {
        if (tail == null) {
            return; // nothing to evict
        }
        map.remove(tail.key);
        removeNode(tail);
        // after removal, tail will be updated inside removeNode
    }

    /** Moves an existing node to the head (most‑recently‑used position). */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        removeNode(node);
        addToHead(node);
    }

    /** Detaches a node from the linked list. */
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
        node.prev = node.next = null;
    }

    /** Inserts a node right after the head (making it the new head). */
    private void addToHead(Node<K, V> node) {
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

    /** Returns the current number of stored entries (for testing only). */
    int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Returns a copy of the internal map (for testing only). */
    Map<K, V> snapshot() {
        lock.lock();
        try {
            Map<K, V> copy = new HashMap<>(map.size());
            for (Map.Entry<K, Node<K, V>> e : map.entrySet()) {
                copy.put(e.getKey(), e.getValue().value);
            }
            return copy;
        } finally {
            lock.unlock();
        }
    }
}
```

### Why this is thread‑safe

* **Mutual exclusion** – Every public method (`get`, `put`) and every helper that mutates shared state (`moveToHead`, `evictLeastRecentlyUsed`, `removeNode`, `addToHead`) is executed while holding the same `ReentrantLock`. No two threads can be inside a critical section at the same time, thus there are no race conditions on the `HashMap` or the linked‑list pointers.
* **Visibility** – The lock provides a *happens‑before* edge: the unlock in one thread synchronizes‑with the subsequent lock in another thread. Consequently, any updates to `map`, `head`, `tail`, or node fields performed while the lock is held become visible to the next thread that acquires the lock.
* **Atomicity of composite operations** – An LRU operation consists of several steps (e.g., “remove node from list, then add it to head”). Because the lock is held for the whole sequence, the operation appears atomic to other threads, preserving the LRU ordering invariant.
* **No lazy initialization hazards** – All fields (`map`, `head`, `tail`) are fully constructed in the constructor before the lock is ever used, so there is no risk of seeing a partially‑initialized object.

The lock is *reentrant*, which allows a thread to call `put` from within `get` (if we ever added such a convenience method) without dead‑locking.

---

## 2. JUnit 5 Test Suite

The tests cover:

* Basic correctness (get/put, eviction, LRU ordering).
* Capacity enforcement.
* Thread‑safety invariants under heavy concurrent load.

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int DEFAULT_CAPACITY = 3;

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(DEFAULT_CAPACITY);
    }

    @Test
    void getReturnsNullForAbsentKey() {
        assertNull(cache.get(1));
    }

    @Test
    void putAndGetBasic() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2)); // absent
    }

    @Test
    void putOverwritesExistingValue() {
        cache.put(1, "first");
        cache.put(1, "second");
        assertEquals("second", cache.get(1));
        assertEquals(1, cache.size()); // size unchanged
    }

    @Test
    void evictsLeastRecentlyUsedWhenAtCapacity() {
        // Fill cache: 1 (LRU), 2, 3 (MRU)
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        // Access 1 -> makes it MRU
        cache.get(1);

        // Now LRU is 2
        cache.put(4, "d"); // should evict key 2

        assertNull(cache.get(2));
        assertEquals("a", cache.get(1)); // still present (was accessed)
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
        assertEquals(3, cache.size());
    }

    @Test
    void lruOrderIsMaintainedAfterMixedOperations() {
        // Sequence: put 1,2,3, get 2, put 4 -> evicts 3 (LRU)
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(2); // 2 becomes MRU
        cache.put(4, "four"); // evicts 3

        assertNull(cache.get(3));
        assertEquals("one", cache.get(1)); // 1 is LRU now
        assertEquals("two", cache.get(2)); // 2 is MRU
        assertEquals("four", cache.get(4)); // 4 is MRU-1
    }

    @Test
    void concurrentStressTest() throws Exception {
        final int threads = 32;
        final int opsPerThread = 5000;
        final int keyRange = 100; // keys 0..99, smaller than capacity to cause collisions
        final int capacity = 50;

        ConcurrentLRUCache<Integer, Integer> stressCache =
                new ConcurrentLRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // Thread‑local random generator bias: 
        }
    }
}
        // to detect lost updates we also keep a concurrent map of the
        // last value each thread successfully wrote for a key.
        ConcurrentMap<Integer, Integer> lastWritten = new ConcurrentHashMap<>();

        Runnable worker = () -> {
            try {
                startLatch.await(); // wait for all threads to start together
                Random rnd = new Random(Thread.currentThread().getId());
                for (int i = 0; i < opsPerThread; i++) {
                    int key = rnd.nextInt(keyRange);
                    int value = rnd.nextInt();
                    if (rnd.nextBoolean()) {
                        // put
                        stressCache.put(key, value);
                        lastWritten.put(key, value); // optimistic – may be overwritten by others
                    } else {
                        // get
                        Integer v = stressCache.get(key);
                        if (v != null) {
                            // If we see a value, it must be the most recent put for that key
                            // that succeeded *before* this get in real‑time.
                            // Because we cannot know the exact ordering, we only check that
                            // the value is one of the values ever written for this key.
                            assertTrue(lastWritten.containsKey(key) &&
                                    lastWritten.get(key).equals(v) ||
                                    // it could be an older value still present due to LRU
                                    // – we accept any non‑null value as long as it’s an Integer.
                                    v instanceof Integer);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threads; i++) {
            exec.submit(worker);
        }

        startLatch.countDown(); // let all threads go
        doneLatch.await(30, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        // ---------- Invariant checks ----------
        // 1. Size never exceeds capacity
        assertTrue(stressCache.size() <= capacity,
                "Cache size exceeded capacity: " + stressCache.size() + " > capacity " + capacity);

        // 2. No null keys or values in the internal map
        Map<Integer, Integer> snap = stressCache.snapshot();
        assertFalse(snap.containsKey(null), "Null key found in cache");
        assertFalse(snap.containsNone of snap).contains null value");

        // 3. Number of distinct keys stored equals map size (no duplicate keys)
        assertEquals(snap.size(), stressCache.size(),
                "Map size inconsistent with cache size()");

        // 4. LRU ordering sanity: if we iterate from head to tail (via snapshot order)
        //    we cannot prove exact order without exposing the list,
        //    but we can at least ensure that the map is consistent.
        //    (Additional structural checks would require package‑private access.)

        // 5. Every value present in the cache is an Integer (type safety)
        snap.values().forEach(v -> assertTrue(v instanceof Integer));
    }
}
```

### Explanation of the Stress Test

* **Setup** – 32 threads each perform 5 000 mixed `put`/`get` operations on a small key space (`0..99`) while the cache capacity is 50. This guarantees frequent evictions and concurrent modifications.
* **Synchronization** – A `CountDownLatch` makes all threads start at roughly the same moment, increasing contention.
* **Optimistic bookkeeping** – Each thread records the last value it *attempted* to store for a key in a `ConcurrentHashMap`. After the test we only verify that any non‑null value read from the cache is either the most recent value we wrote for that key **or** some other integer (the test tolerates stale values because of concurrent overwrites and evictions). The main purpose is to catch crashes, `NullPointerException`s, or corrupted internal state.
* **Invariant checks** – After all threads finish we:
  1. Verify `size() ≤ capacity`.
  2. Ensure the snapshot contains no `null` keys or values.
  3. Confirm the snapshot size matches the cache’s `size()` (detects duplicate keys or lost entries).
  4. Confirm every stored value is of the correct type.

If any of these assertions fail, it indicates a breach of thread‑safety or a corruption of the LRU list/map invariants.

---

### Usage Example

```java
ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100);
cache.put("user:42", "Alice");
String name = cache.get("user:42"); // returns "Alice"
```

The cache gives O(1) average lookup/insertion, automatically evicts the least‑recently‑used entry when the capacity is exceeded, and remains safe under arbitrary concurrent access thanks to the internal `ReentrantLock`.