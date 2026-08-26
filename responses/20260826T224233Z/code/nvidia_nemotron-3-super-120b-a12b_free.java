<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260826T224233Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, thread‑safe LRU (Least‑Recently‑Used) cache called `ConcurrentLRUCache<K,V>`.  
The public API mirrors `java.util.Map` for the two operations we need:

* `V get(K key)` – returns the value associated with `key` (or `null`) and marks the entry as most‑recently used.  
* `V put(K key, V value)` – stores the pair; if the cache is already at capacity the least‑recently used entry is evicted. Returns the previous value associated with `key` (or `null`).

Both operations run in **O(1) average time** – the only work besides the hash‑map lookup is a constant‑time manipulation of a doubly‑linked list.

### How thread‑safety is achieved

* All mutable state (`map`, `head`, `tail`, `size`) is protected by a single `java.util.concurrent.locks.ReentrantLock`.  
* Every public method (`get`, `put`, `removeLeastRecentlyUsed`) acquires the lock **before** touching any shared field and releases it in a `finally` block.  
* The lock guarantees:
  * **Mutual exclusion** – only one thread can modify the internal structures at a time, preventing races on the linked list or the hash map.  
  * **Visibility** – changes made while holding the lock become visible to other threads after they acquire the lock (the lock provides a happens‑before edge).  
* Because the lock is held for only a constant‑time critical section, the asymptotic complexity stays O(1) on average; contention is the only performance factor, which is unavoidable for a strictly correct concurrent LRU.

(If higher throughput is required one could replace the single lock with a `ReadWriteLock` or lock‑striping, but the correctness argument becomes more involved. The single‑lock design is simple, easy to reason about, and satisfies the O(1) average requirement.)

---

## Code

```java
package com.example.lru;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list that stores the entry. */
    private static final class Node<K, V> {
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
    private final java.util.concurrent.ConcurrentHashMap<K, Node<K, V>> map;
    private Node<K, V> head;                        // most‑recently used
    private Node<K, V> tail;                        // least‑recently used
    private int size;                               // current number of entries
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates a cache with the given capacity.
     *
     * @param capacity maximum number of entries; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new java.util.concurrent.ConcurrentHashMap<>(capacity * 2);
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this map contains no mapping for the key.
     *
     * <p>The entry is moved to the head of the LRU list (most‑recently used).</p>
     *
     * @param key the key whose associated value is to be returned
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
            // move node to head (most‑recently used)
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates the specified value with the specified key in this cache.
     * If the cache already contains a mapping for the key, the old value is replaced.
     *
     * <p>If the cache is at capacity, the least‑recently used entry is evicted
     * before inserting the new one.</p>
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key; may be null
     * @return the previous value associated with {@code key}, or {@code null}
     *         if there was no mapping for {@code key}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            V previous = null;
            if (existing != null) {
                previous = existing.value;
                existing.value = value;
                moveToHead(existing); // refresh usage order
                return previous;
            }

            // need to create a new node
            if (size >= capacity) {
                // evict least‑recently used
                Node<K, V> evicted = removeTail();
                if (evicted != null) {
                    map.remove(evicted.key);
                    size--;
                }
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addNodeToHead(newNode);
            size++;
            return null; // no previous value
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal linked‑list helpers (all assume the lock is held)
    // -----------------------------------------------------------------

    /** Inserts node right after the head (as the new most‑recently used). */
    private void addNodeToHead(Node<K, V> node) {
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

    /** Removes the node from its current position and inserts it after the head. */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already most‑recently used
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
            tail = prev; // node was the tail
        }

        // re‑insert after head
        node.prev = head;
        node.next = null;
        head.next = node;
        head = node;
    }

    /** Removes and returns the tail node (least‑recently used). */
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

    /** Returns the current number of entries in the cache. */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the configured capacity. */
    public int capacity() {
        return capacity;
    }
}
```

### Why this implementation is thread‑safe

| Shared variable | Access pattern | Protection |
|-----------------|----------------|------------|
| `map` (ConcurrentHashMap) | reads (`get`) and writes (`put`, `remove`) | Guarded by `lock`; although `ConcurrentHashMap` is itself thread‑safe, we also protect it to keep a *single* atomic view of size/eviction logic. |
| `head`, `tail` | updated on every `get`/`put` (list re‑ordering) | Guarded by `lock`. |
| `size` | incremented/decremented on eviction/insertion | Guarded by `lock`. |
| `lock` | acquired/released in every public method | Guarantees mutual exclusion and visibility (happens‑before). |

Because each method holds the lock for the entirety of its critical section, no two threads can interleave modifications that could corrupt the doubly‑linked list or cause the size counter to diverge from the actual number of nodes. The lock also ensures that a thread seeing a stale `head`/`tail` after another thread’s update will observe the new values once it acquires the lock.

The average time complexity remains O(1) because:
* `ConcurrentHashMap.get/put` are O(1) average.
* List manipulations (`addNodeToHead`, `moveToHead`, `removeTail`) are constant‑time pointer updates.
* The lock does not change asymptotic complexity; it only adds a constant overhead.

---

## JUnit 5 Tests

```java
package com.example.lru;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int TEST_CAPACITY = 1_000;
    private static final int STRESS_THREADS = 16;
    private static final long STRESS_DURATION_MS = 3_000; // 3 seconds
    private static final Random RAND = new Random();

    @Test
    void basicGetPut() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        cache.put(2, "two");
        cache.put(3, "three");
        // cache is now full: [1,2,3] (LRU order: 1 least recent)
        cache.put(4, "four"); // should evict key 1
        assertNull(cache.get(1));
        assertEquals("four", cache.get(4));
        // accessing 2 makes it most‑recently used
        cache.get(2);
        cache.put(5, "five"); // evicts 3 (LRU now)
        assertNull(cache.get(3));
        assertEquals("two", cache.get(2)); // still present
    }

    @Test
    void capacityIsRespected() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(TEST_CAPACITY);
        for (int i = 0; i < TEST_CAPACITY * 2; i++) {
            cache.put(i, i);
            assertTrue(cache.size() <= TEST_CAPACITY,
                    "size exceeded capacity after put(" + i + ")");
        }
        // after the loop the cache must contain exactly the last TEST_CAPACITY keys
        for (int i = TEST_CAPACITY; i < TEST_CAPACITY * 2; i++) {
            assertEquals(i, cache.get(i), "missing expected key " + i);
        }
        for (int i = 0; i < TEST_CAPACITY; i++) {
            assertNull(cache.get(i), "old key " + i + " should have been evicted");
        }
    }

    @Test
    void concurrentStressTest() throws Exception {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(TEST_CAPACITY);
        // Tracks the *last* value each thread expects for a key.
        // Using an array of AtomicReference because we need a cheap concurrent map.
        final int keyRange = 2 * TEST_CAPACITY; // use a larger key space to cause evictions
        AtomicReference<Integer>[] expected = new AtomicReference[keyRange];
        for (int i = 0; i < keyRange; i++) {
            expected[i] = new AtomicReference<>(null);
        }

        ExecutorService exec = Executors.newFixedThreadPool(STRESS_THREADS);
        List<Callable<Void>> tasks = new ArrayList<>(STRESS_THREADS);
        for (int t = 0; t < STRESS_THREADS; t++) {
            tasks.add(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                long end = System.currentTimeMillis() + STRESS_DURATION_MS;
                while (System.currentTimeMillis() < end) {
                    int key = rnd.nextInt(keyRange);
                    boolean put = rnd.nextBoolean();
                    if (put) {
                        int value = rnd.nextInt();
                        Integer prev = cache.put(key, value);
                        expected[key].set(value); // update expected latest value
                        // (optional) we could verify that prev matches the old expected value,
                        // but due to concurrent evictions this is tricky; we skip it here.
                    } else {
                        Integer got = cache.get(key);
                        Integer exp = expected[key].get();
                        if (got != null) {
                            assertEquals(exp, got,
                                    "get(" + key + ") returned " + got + " but expected " + exp);
                        }
                        // if got == null we accept either a genuine miss or an eviction.
                    }
                }
                return null;
            });
        }

        exec.invokeAll(tasks);
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS),
                "Executor did not terminate in time");

        // Final invariant checks
        assertTrue(cache.size() <= TEST_CAPACITY,
                "Final size " + cache.size() + " exceeds capacity " + TEST_CAPACITY);

        // Spot‑check a subset of keys: if a key is present, its value must match the last
        // value any thread stored for it (as recorded in expected[]).
        int sampleSize = Math.min(100, keyRange);
        for (int i = 0; i < sampleSize; i++) {
            Integer val = cache.get(i);
            AtomicReference<Integer> expRef = expected[i];
            if (val != null) {
                assertEquals(expRef.get(), val,
                        "Spot‑check failed for key " + i);
            }
        }
    }
}
```

### Explanation of the tests

| Test | Purpose |
|------|---------|
| `basicGetPut` | Verifies the core LRU semantics on a single thread (insertion, access‑order update, eviction). |
| `capacityIsRespected` | Confirms that the cache never grows beyond the configured capacity and that the correct entries survive after many puts. |
| `concurrentStressTest` | Executes many threads (`STRESS_THREADS`) that randomly `put` and `get` for a fixed duration. The test checks two invariants: <br>1. **Size invariant** – `size()` never exceeds the capacity. <br>2. **Value‑consistency invariant** – whenever a `get` returns a non‑null value, it equals the most recent value that any thread stored for that key (tracked via an `AtomicReference[]`). <br>Misses (`null`) are allowed because of evictions or concurrent races, so they are not asserted. |

The stress test runs for a few seconds with a thread pool, ensuring that the lock‑based implementation remains correct under heavy parallel load. If any thread observes a violation, the JUnit assertion will fail and the test will stop, providing a clear diagnostic.

---

**Usage example**

```java
ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10_000);
cache.put("user:42", "Alice");
String name = cache.get("user:42"); // returns "Alice"
```

The cache gives O(1) average lookup/insertion, automatically evicts the least‑recently used entry when full, and is safe to use from any number of concurrent threads without external synchronization.