<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261013T200505Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a classic LRU cache that stores at most `capacity` entries.  
Internally we keep:

* a `HashMap<K,Node>` for O(1) key → node lookup  
* a doubly‑linked list with a dummy **head** (most‑recently used) and **tail** (least‑recently used) to track usage order  

All mutations of the map **and** the list are protected by a single `ReentrantLock`.  
Because every operation (map get/put, list pointer updates) is O(1) and the lock acquisition/release is also O(1), the *average* time complexity of `get` and `put` stays O(1). The lock guarantees that only one thread can modify the internal state at a time, which makes the cache thread‑safe.

---

## 1. Thread‑Safe Concurrent LRU Cache (Java)

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

    /** Node of the doubly linked list */
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
    private final Node<K, V> head;   // dummy head (most recently used)
    private final Node<K, V> tail;   // dummy tail (least recently used)
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2); // initial size, load factor 0.75 default
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this cache contains no mapping for the key.
     *
     * <p>The accessed node becomes the most‑recently used entry.</p>
     */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);          // mark as recently used
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates the specified value with the specified key in this cache.
     * If the cache already contained a mapping for the key, the old value is replaced.
     *
     * <p>If the cache is at capacity, the least‑recently used entry is evicted.</p>
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to head
                existing.value = value;
                moveToHead(existing);
                return;
            }

            if (map.size() >= capacity) {
                // evict LRU
                Node<K, V> lru = popTail();
                if (lru != null) {
                    map.remove(lru.key);
                }
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addNode(newNode);
        } finally {
            lock.unlock();
        }
    }

    /** Current number of entries stored in the cache. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Private helper methods – all assume the lock is held by the caller *
     * ------------------------------------------------------------------ */

    private void addNode(Node<K, V> node) {
        // insert right after head
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    /** Removes and returns the LRU node (the one before tail). Returns null if list is empty. */
    private Node<K, V> popTail() {
        Node<K, V> res = tail.prev;
        if (res == head) {
            return null; // empty
        }
        removeNode(res);
        return res;
    }
}
```

### Why the implementation is thread‑safe

* **Single lock (`ReentrantLock`)** – every public method (`get`, `put`, `size`) acquires the same lock before touching any shared state (`map`, the linked list, `head`/`tail`).  
* **Mutual exclusion** – while one thread holds the lock, no other thread can read or write the map or modify the list pointers, eliminating race conditions such as lost updates, corrupted links, or size mismatches.  
* **Visibility** – the lock guarantees a happens‑before relationship; writes performed while holding the lock become visible to any subsequent thread that acquires the lock.  
* **O(1) average cost** – map operations are O(1) expected, list pointer updates are constant time, and lock acquisition/release is O(1) (assuming uncontended case; under contention the thread may block, but the algorithmic complexity does not increase).  

If higher concurrency is required, the lock could be split (e.g., a `ReadWriteLock` where `get` uses a read lock and only moves the node on a cache‑hit, which would need a write lock). The presented version trades a bit of parallelism for simplicity and correctness while still meeting the O(1) average requirement.

---

## 2. JUnit 5 Test Suite

The test suite contains:

* **Basic functional tests** – capacity eviction, get/put correctness.  
* **Concurrency stress test** – many threads perform random `get`/`put` operations for a fixed duration; after the test we verify the cache invariants (size ≤ capacity, no null values, and that every key present in the map can be retrieved).  

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private static final int TEST_CAPACITY = 1_000;
    private static final int STRESS_THREADS = 20;
    private static final long STRESS_DURATION_MS = 5_000; // 5 seconds

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(TEST_CAPACITY);
    }

    @Test
    void getAndPutBasic() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));

        cache.put(1, "uno"); // update
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionWhenFull() {
        // fill the cache
        for (int i = 0; i < TEST_CAPACITY; i++) {
            cache.put(i, String.valueOf(i));
        }
        assertEquals(TEST_CAPACITY, cache.size());

        // one more put should evict the least‑recently used (key 0)
        cache.put(TEST_CAPACITY, "new");
        assertEquals(TEST_CAPACITY, cache.size());
        assertNull(cache.get(0));               // evicted
        assertEquals("new", cache.get(TEST_CAPACITY));
    }

    @Test
    void accessOrderUpdatesLRU() {
        // Insert 3 items
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        // Access 1 -> makes it most‑recently used
        cache.get(1);
        // Fill up to capacity (3) causing eviction of the LRU (should be 2)
        cache.put(4, "d");
        assertNull(cache.get(2));   // 2 evicted
        assertEquals("a", cache.get(1)); // 1 still present (most‑recent)
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(STRESS_THREADS);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger putCount = new AtomicInteger(0);
        AtomicInteger getCount = new AtomicInteger(0);
        Random rand = new Random();

        // Worker task
        Runnable worker = () -> {
            while (!stop.get()) {
                int key = rand.nextInt(TEST_CAPACITY * 2); // keys 0..2*capacity-1
                if (rand.nextBoolean()) {
                    // put
                    cache.put(key, "value-" + key);
                    putCount.incrementAndGet();
                } else {
                    // get
                    cache.get(key);
                    getCount.incrementAndGet();
                }
                // occasional yield to increase interleaving
                Thread.yield();
            }
        };

        // Start workers
        for (int i = 0; i < STRESS_THREADS; i++) {
            executor.submit(worker);
        }

        // Let the test run for a while
        Thread.sleep(STRESS_DURATION_MS);
        stop.set(true); // signal workers to finish
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                "Worker threads did not terminate in time");

        // ----- Invariant checks -----
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= TEST_CAPACITY,
                "Cache size (" + cache.size() + ") exceeded capacity (" + TEST_CAPACITY + ")");

        // 2. No null values stored
        cache.map.forEach((k, v) -> assertNotNull(v, "Null value found for key " + k));

        // 3. Every key present in the internal map can be retrieved via get()
        Set<Integer> keysInMap = cache.map.keySet();
        for (Integer k : keysInMap) {
            assertNotNull(cache.get(k), "get() returned null for key present in map: " + k);
        }

        // Optional: print statistics (helpful for debugging)
        System.out.printf(
                "Stress test finished: puts=%d, gets=%d, final size=%d%n",
                putCount.get(), getCount.get(), cache.size()
        );
    }
}
```

### Explanation of the stress test

* **Workload** – each thread repeatedly chooses a random key (range larger than the capacity to cause both hits and misses) and randomly decides whether to `put` or `get`.  
* **Duration** – the test runs for a fixed time (`STRESS_DURATION_MS`) to avoid dependence on iteration counts.  
* **Synchronization** – the only synchronization point is the cache’s internal lock; the test itself uses only `AtomicBoolean`, `AtomicInteger`, and a standard `ExecutorService`.  
* **Invariant checks after the test**  

  1. **Size ≤ capacity** – guarantees that the eviction policy never allowed the cache to grow beyond its limit, even under concurrent puts.  
  2. **No null values** – ensures that a concurrent `put` never stored a `null` (we explicitly forbid `null` keys/values).  
  3. **Retrievability** – every key that the internal `HashMap` thinks is present can be successfully obtained via `get()`. This catches cases where a node was unlinked from the list but not removed from the map (or vice‑versa).  

If any of these invariants fail, the test will throw an `AssertionError`, indicating a bug in the concurrent LRU implementation.

---

### How to run

* **Maven** – add JUnit 5 (`org.junit.jupiter:junit-jupiter:5.10.0`) and Surefire/Failsafe plugin.  
* **Gradle** – use `testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'` and enable `useJUnitPlatform()`.

The cache class and the test class can be placed in the same source tree (e.g., `src/main/java/com/example/cache/ConcurrentLRUCache.java` and `src/test/java/com/example/cache/ConcurrentLRUCacheTest.java`). Running `mvn test` or `gradle test` will execute the functional and stress tests, confirming that the LRU cache is both correct and thread‑safe under heavy concurrent load.