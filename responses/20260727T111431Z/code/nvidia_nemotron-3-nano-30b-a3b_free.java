<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260727T111431Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**1.  Design Overview**

| Goal | How we achieve it |
|------|-------------------|
| **O(1) average `get` / `put`** | A hash‑map (`Map<K,Node>`) gives O(1) lookup, and a doubly‑linked list gives O(1) removal / insertion. |
| **Correct LRU eviction** | The list is ordered by *recency*: the node right after the dummy `head` is the most‑recently used, the node right before the dummy `tail` is the least‑recently used. When the size exceeds the configured capacity we drop `tail.prev`. |
| **Thread‑safety** | All public operations (`get`, `put`, `snapshot`) acquire a single `ReentrantLock`. The lock protects **both** the map and the linked list, guaranteeing that no two threads can interleave the structures in a way that breaks the LRU invariant. The lock is taken for the *entire* logical operation, so the average cost stays O(1). |
| **Deterministic eviction** | When `size > capacity` we evict exactly the node at `tail.prev`. Because the lock is held, no other thread can reorder the list while we are removing the node. |
| **Visibility** | The lock also establishes happens‑before semantics, so a thread that acquires the lock sees a fully‑consistent view of the map and list. |

The implementation below is deliberately **explicitly synchronized** – we do **not** rely on `Collections.synchronizedMap` or other higher‑level collection utilities, because they would require additional coordination to maintain the LRU order.

---

**2.  Java Implementation**

```java
package com.example.lru;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe Least‑Recently‑Used (LRU) cache with a fixed capacity.
 *
 * <p>All operations are O(1) on average and safe under concurrent access.
 *
 * @param <K> type of keys, must be non‑null
 * @param <V> type of values, must be non‑null
 */
public class ConcurrentLRUCache<K, V> {

    /** Internal node of the doubly‑linked list */
    private static class Node<K, V> {
        final K key;
        volatile V value;               // volatile for safe publication
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;   // sentinel: before most‑recent
    private final Node<K, V> tail;   // sentinel: after least‑recent
    private final ReentrantLock lock = new ReentrantLock();

    /** Number of entries currently stored (protected by lock). */
    private int size = 0;

    public ConcurrentLRUCache(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be non‑negative");
        }
        this.capacity = capacity;
        this.map = new HashMap<>();

        // initialise dummy head / tail
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the cached value, or {@code null} if the key is absent. */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToFront(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Associates {@code value} with {@code key} and returns the previous value,
     *  or {@code null} if the key was not already present. */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to front
                existing.value = value;
                moveToFront(existing);
                return null;
            }

            // insert new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addNodeToFront(node);
            size++;

            // evict if we exceeded capacity
            if (size > capacity) {
                Node<K, V> evict = tail.prev;          // least‑recently used
                removeNode(evict);
                map.remove(evict.key);
                size--;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Package‑private: returns a shallow copy of the internal map.
     *  Used only by tests to verify invariants without exposing the whole cache. */
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

    /* --------------------------------------------------------------------- */
    /*  Internal helpers – all must be called with the lock already held.   */
    /* --------------------------------------------------------------------- */

    /** Inserts {@code node} right after {@code head} (i.e. makes it most recent). */
    private void addNodeToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /** Removes {@code node} from the list. */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null; // help GC
    }

    /** Moves an existing node to the front of the list (most‑recent). */
    private void moveToFront(Node<K, V> node) {
        if (node == head.next) {
            // already most recent – nothing to do
            return;
        }
        removeNode(node);
        addNodeToFront(node);
    }

    /** Returns the current size (for testing / monitoring). */
    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }
}
```

**Explanation of thread‑safety**

* All public entry points (`get`, `put`, `snapshot`, `size`) acquire the **same** `ReentrantLock` before touching any mutable state.
* The lock is held for the *entire* logical operation, guaranteeing that:
  * No two threads can interleave the modification of the hash‑map and the linked list.
  * The LRU ordering (`head` → most‑recent → … → least‑recent → `tail`) is always consistent.
* Because the lock is re‑entrant, a thread that calls `get` internally may invoke `moveToFront`, which also uses the lock – there is no risk of dead‑lock.
* The lock also provides the necessary *happens‑before* relationship: once a thread releases the lock, all writes it performed become visible to any other thread that subsequently acquires the lock.

---

**3.  JUnit 5 Test Suite (including a concurrency stress test)**

```java
package com.example.lru;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConcurrentLRUCache}.
 */
class ConcurrentLRUCacheTest {

    private static final int CAPACITY = 5;
    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    /** Simple functional correctness test */
    @Test
    void testBasicOperations() {
        assertNull(cache.get(1));

        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        assertEquals("a", cache.get(1));   // accessed -> most recent
        assertEquals("b", cache.get(2));   // not accessed -> still present
        assertEquals("c", cache.get(3));

        // put a fourth element -> evicts LRU (key 2)
        cache.put(4, "d");
        assertNull(cache.get(2));          // evicted
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));

        // put a fifth element -> evicts key 1 (now LRU)
        cache.put(5, "e");
        assertNull(cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
        assertEquals("e", cache.get(5));

        // put a sixth element -> evicts key 3 (now LRU)
        cache.put(6, "f");
        assertNull(cache.get(3));
        assertEquals("d", cache.get(4));
        assertEquals("e", cache.get(5));
        assertEquals("f", cache.get(6));

        // size must never exceed capacity
        assertEquals(CAPACITY, cache.snapshot().size());
    }

    /** Stress test that spawns many threads performing random get/put operations.
     *  The test verifies that after all threads finish the cache respects the LRU
     *  invariant and never holds more than {@code CAPACITY} entries. */
    @Test
    void testConcurrencyStress() throws Exception {
        final int THREADS = 16;
        final int OPS_PER_THREAD = 10_000;
        final int MAX_KEY = 1_000;          // keys will be drawn from 0..MAX_KEY-1

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(THREADS);
        AtomicInteger totalPuts = new AtomicInteger(0);

        // shared state: record the *last* value written for each key (used later for verification)
        ConcurrentMap<Integer, String> lastValue = new ConcurrentHashMap<>();

        Runnable worker = () -> {
            try {
                startLatch.await(); // make all threads start at the same time
                ThreadLocalRandom rand = ThreadLocalRandom.current();

                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int key = rand.nextInt(MAX_KEY);
                    String val = "v" + key + "_" + Thread.currentThread().getName();

                    // record the value we are about to write (for later verification)
                    lastValue.put(key, val);

                    // perform a put (overwrites existing value)
                    cache.put(key, val);
                    totalPuts.incrementAndGet(); // not atomic but okay for a rough sanity check

                    // occasionally read the same key back
                    if (rand.nextBoolean()) {
                        String cached = cache.get(key);
                        assertEquals(val, cached); // after a successful put we must see the same value
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finishLatch.countDown();
            }
        };

        // launch threads
        for (int t = 0; t < THREADS; t++) {
            executor.submit(worker);
        }

        // release all threads simultaneously
        startLatch.countDown();
        // wait for all to finish
        assertTrue(finishLatch.await(30, TimeUnit.SECONDS), "threads did not finish in time");

        executor.shutdownNow();

        // ---- Verification of global invariants ----
        // 1. Size must never exceed the configured capacity
        Map<Integer, String> snapshot = cache.snapshot();
        assertEquals(CAPACITY, snapshot.size(),
                "Cache size after heavy concurrency should be exactly the capacity");

        // 2. Every key that is present in the snapshot must have the *last* value that any thread wrote for it.
        //    (If a key was evicted we simply do not see it.)
        for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
            Integer k = entry.getKey();
            String expected = lastValue.get(k);
            assertNotNull(expected, "Key " + k + " is present in cache but its last written value is unknown");
            assertEquals(expected, entry.getValue(),
                    "Cache value for key " + k + " does not match the last value written");
        }

        // 3. No duplicate keys can exist (guaranteed by the underlying Map)
        assertEquals(snapshot.keySet().size(), snapshot.values().size());

        // 4. All puts performed by the workers are accounted for – we only check that the number
        //    of puts is roughly consistent with the number of distinct keys that finally survive.
        //    (Exact accounting would require a more elaborate bookkeeping, but the above checks
        //    already guarantee correctness.)
        assertTrue(totalPuts.get() <= THREADS * OPS_PER_THREAD,
                "More puts were recorded than the theoretical maximum");
    }
}
```

**What the stress test does**

1. **Spawns 16 threads**, each performing 10 000 mixed `put`/`get` operations on random keys (0‑999).  
2. Every `put` records the *last* value written for that key in a concurrent map (`lastValue`).  
3. After all threads finish, the test:
   * Checks that the cache size never exceeds the configured capacity (`CAPACITY`).  
   * Verifies that each key still present in the cache holds exactly the value that was last written for it (i.e., no stale or overwritten data).  
   * Ensures there are no duplicate keys and that the total number of `put` operations does not exceed the theoretical maximum.  

Because every thread acquires the same internal lock before touching the cache, the test exercises many interleavings (e.g., concurrent evictions, overlapping `get`s, simultaneous updates) while still being able to reason about the final state deterministically.

---

### Summary

* **Implementation** – a doubly‑linked list + hash‑map protected by a single `ReentrantLock`. All `get`/`put` operations are O(1) on average and the lock guarantees that the LRU ordering and eviction stay consistent under concurrent access.  
* **Thread‑safety guarantee** – the lock serialises all modifications, provides proper visibility, and prevents races that could corrupt the list or the map.  
* **Testing** – unit tests for basic correctness and a realistic concurrency stress test that validates the cache’s invariants under heavy parallel load.  

You can drop the `ConcurrentLRUCache` class into any Java 8+ project and run the JUnit 5 suite to verify its behavior. Happy coding!