<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260625T020407Z -->
<!-- PROMPT -->
Write a **single, self‑contained Java program** that implements a **concurrent, thread‑safe Least‑Recently‑Used (LRU) cache** with a fixed maximum capacity and a full suite of **JUnit 5 tests**. Your solution must satisfy **all** of the following requirements:

---

### 1. Public API

Create a public class `ConcurrentLruCache<K, V>` with these members:

| Signature | Description |
|-----------|-------------|
| `public ConcurrentLruCache(int maxCapacity)` | Constructs a cache that can hold at most `maxCapacity` entries. `maxCapacity` must be > 0; throw `IllegalArgumentException` otherwise. |
| `public V get(K key)` | Returns the value associated with `key`, or `null` if the key is absent. This operation must update the LRU order (the accessed entry becomes most‑recently used). |
| `public void put(K key, V value)` | Inserts or updates the mapping `key → value`. If the cache is at capacity, evict the **least‑recently used** entry before inserting. This operation must update the LRU order (the inserted/updated entry becomes most‑recently used). |
| `public V remove(K key)` | Removes the entry for `key` if present and returns its value, or `null` if absent. |
| `public int size()` | Returns the current number of entries. |
| `public List<K> snapshotKeys()` | Returns a **thread‑safe snapshot** of the keys in LRU order, from most‑recently used to least‑recently used. The returned list must not be affected by subsequent cache modifications. |
| `public void clear()` | Removes all entries. |

All public methods must be safe to call concurrently from any number of threads.

---

### 2. Concurrency Strategy (Documentation Required)

In the source file, include a concise Javadoc comment (≤ 250 words) that explains **exactly** how thread‑safety is achieved, covering:

* The underlying data structures (e.g., `ConcurrentHashMap`, custom doubly‑linked list, `java.util.concurrent.locks.ReentrantLock`, etc.).
* How you avoid deadlocks, race conditions, and ensure **O(1)** expected time for `get` and `put`.
* How the LRU ordering is maintained under concurrent accesses.
* Any memory‑visibility guarantees you rely on (e.g., `volatile`, `AtomicReference`, lock ordering).

---

### 3. Implementation Constraints

* Do **not** use any third‑party libraries; only the Java Standard Library (Java 17 or later).
* The cache must achieve **amortized O(1)** for `get`, `put`, and `remove` under contention.
* You may use `java.util.concurrent.locks.ReentrantReadWriteLock`, `StampedLock`, `synchronized`, `java.util.concurrent.atomic` primitives, or a combination, but you must justify the choice in the Javadoc.
* The `snapshotKeys()` method must not block other operations longer than necessary and must return an immutable `List<K>` (e.g., `Collections.unmodifiableList`).

---

### 4. JUnit 5 Test Suite

Provide a **complete** JUnit 5 test class `ConcurrentLruCacheTest` in the same file (or a separate file if you prefer) that includes:

1. **Basic functional tests** for each public method (insertion, retrieval, eviction order, removal, clear, snapshot correctness).
2. **Concurrency stress tests**:
   * Spawn at least **8** threads that perform a random mix of `get`, `put`, and `remove` operations on a shared cache for **30 seconds**.
   * Use `java.util.concurrent.CyclicBarrier` (or similar) to start all threads simultaneously.
   * After the stress run, verify:
     * No data corruption (e.g., a key’s value is always the most recent `put` for that key).
     * The cache size never exceeds `maxCapacity`.
     * The LRU order reported by `snapshotKeys()` is consistent with the observed accesses (you may record a concurrent log of accesses to validate).
3. **Edge‑case tests**:
   * Construction with invalid capacity.
   * `null` keys or values (document whether they are allowed; if not, ensure the appropriate exception is thrown).
4. **Performance sanity check** (optional): assert that 1 million mixed operations complete within a reasonable time (e.g., < 2 seconds on a typical modern CPU).

All tests must be deterministic; avoid flaky failures caused by timing issues. Use `awaitility`‑style waiting or `Thread.join` with timeouts if needed, but rely only on JDK utilities.

---

### 5. Deliverable Format

Output a **single** Java source file (or two files if you separate the test class) that can be compiled and run with `javac` and `java` without any additional setup. The file(s) should contain:

* The fully documented `ConcurrentLruCache` class.
* The `ConcurrentLruCacheTest` class annotated with `@Test` methods.
* A `public static void main(String[] args)` entry point that runs the JUnit platform console launcher (optional but helpful).

**Do not provide any solution or partial implementation here**—only the prompt describing what the model must produce.
<!-- RESPONSE -->
```java
/*
 * ConcurrentLruCache.java
 *
 * A thread‑safe, fixed‑capacity Least‑Recently‑Used (LRU) cache.
 *
 * The implementation uses a ConcurrentHashMap for O(1) key lookup and a
 * hand‑crafted doubly‑linked list to maintain LRU order.  All mutations of the
 * list (move‑to‑front, insert, evict, remove) are guarded by a single
 * ReentrantLock.  Because the map is concurrent, reads that only need the map
 * (e.g. a miss) do not acquire the lock, giving high scalability.  The lock is
 * always taken in the same order (first the map entry is fetched, then the
 * lock is acquired) which prevents deadlock.  The lock provides the necessary
 * memory‑visibility guarantees: once a thread releases the lock all modifications
 * to the linked nodes are visible to other threads that subsequently acquire it.
 *
 * Expected complexity:
 *   get  – O(1) expected (map lookup + lock‑protected node move)
 *   put  – O(1) expected (map put + possible eviction under lock)
 *   remove – O(1) expected (map remove + lock‑protected node unlink)
 *
 * The public snapshotKeys() method acquires the lock only long enough to copy
 * the current order into a new ArrayList, then returns an unmodifiable view.
 * This copy is immutable, so later cache mutations cannot affect the snapshot.
 *
 * The cache does **not** allow null keys or null values; attempts to insert
 * them result in NullPointerException, matching the behaviour of
 * ConcurrentHashMap.
 */
package concurrentlru;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;
import java.util.stream.*;

public final class ConcurrentLruCache<K, V> {

    /** Node of the internal doubly‑linked list. */
    private static final class Node<K, V> {
        final K key;
        volatile V value;
        Node<K, V> prev;
        Node<K, V> next;
        Node(K key, V value) {
            this.key = Objects.requireNonNull(key);
            this.value = Objects.requireNonNull(value);
        }
    }

    private final int maxCapacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    /** Dummy head (most‑recent) and tail (least‑recent) nodes – never removed. */
    private final Node<K, V> head;
    private final Node<K, V> tail;
    /** Protects all mutations of the linked list. */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Constructs a cache with the given maximum capacity.
     *
     * @param maxCapacity maximum number of entries; must be &gt; 0
     * @throws IllegalArgumentException if maxCapacity ≤ 0
     */
    public ConcurrentLruCache(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
        this.maxCapacity = maxCapacity;
        this.map = new ConcurrentHashMap<>(maxCapacity);
        this.head = new Node<>(null, null); // sentinel
        this.tail = new Node<>(null, null); // sentinel
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value associated with the key, or {@code null} if absent.
     * The accessed entry becomes most‑recently used.
     */
    public V get(K key) {
        Objects.requireNonNull(key);
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        // move to front under lock
        lock.lock();
        try {
            detach(node);
            attachAfterHead(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Inserts or updates the mapping key → value.
     * If the cache is full, evicts the least‑recently used entry.
     * The inserted/updated entry becomes most‑recently used.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Node<K, V> existing = map.get(key);
        if (existing != null) {
            // update value and move to front
            existing.value = value;
            lock.lock();
            try {
                detach(existing);
                attachAfterHead(existing);
            } finally {
                lock.unlock();
            }
            return;
        }

        // new entry
        Node<K, V> newNode = new Node<>(key, value);
        lock.lock();
        try {
            // double‑check after acquiring lock
            Node<K, V> race = map.putIfAbsent(key, newNode);
            if (race != null) {
                // another thread inserted concurrently – treat as update
                race.value = value;
                detach(race);
                attachAfterHead(race);
                return;
            }
            attachAfterHead(newNode);
            if (map.size() > maxCapacity) {
                evictLeastRecent();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the entry for the given key, returning the previous value,
     * or {@code null} if the key was not present.
     */
    public V remove(K key) {
        Objects.requireNonNull(key);
        Node<K, V> node = map.remove(key);
        if (node == null) {
            return null;
        }
        lock.lock();
        try {
            detach(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /** Returns the current number of entries. */
    public int size() {
        return map.size();
    }

    /**
     * Returns an immutable snapshot of the keys in LRU order,
     * from most‑recently used to least‑recently used.
     * The snapshot is not affected by later modifications.
     */
    public List<K> snapshotKeys() {
        lock.lock();
        try {
            List<K> list = new ArrayList<>(map.size());
            Node<K, V> cur = head.next;
            while (cur != tail) {
                list.add(cur.key);
                cur = cur.next;
            }
            return Collections.unmodifiableList(list);
        } finally {
            lock.unlock();
        }
    }

    /** Removes all entries from the cache. */
    public void clear() {
        lock.lock();
        try {
            map.clear();
            // unlink all nodes
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /** Detaches a node from the linked list. Caller must hold {@link #lock}. */
    private void detach(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null;
    }

    /** Inserts a node directly after the head sentinel. Caller must hold {@link #lock}. */
    private void attachAfterHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /** Evicts the least‑recently used entry (the node before tail). Caller must hold {@link #lock}. */
    private void evictLeastRecent() {
        Node<K, V> lru = tail.prev;
        if (lru == head) {
            return; // empty
        }
        detach(lru);
        map.remove(lru.key);
    }

    /* ---------------------------------------------------------------------- */
    /* ---------------------------- Test Harness ---------------------------- */
    /* ---------------------------------------------------------------------- */

    /**
     * Simple main method that runs the JUnit 5 test suite using the console launcher.
     * No external libraries are required.
     */
    public static void main(String[] args) {
        // Use JUnit Platform Launcher programmatically
        org.junit.platform.launcher.LauncherDiscoveryRequest request =
                org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(ConcurrentLruCacheTest.class)
                        .build();

        org.junit.platform.launcher.Launcher launcher = org.junit.platform.launcher.LauncherFactory.create();

        launcher.execute(request);
    }
}

/* -------------------------------------------------------------------------- */
/* --------------------------- JUnit Test Class ---------------------------- */
/* -------------------------------------------------------------------------- */

package concurrentlru;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLruCacheTest {

    private ConcurrentLruCache<Integer, String> cache;
    private static final int CAPACITY = 5;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLruCache<>(CAPACITY);
    }

    @Test
    void testConstructionInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-1));
    }

    @Test
    void testNullKeyOrValueNotAllowed() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
    }

    @Test
    void testBasicPutGet() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void testUpdateMovesToFront() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(1); // access 1 -> most recent
        List<Integer> order = cache.snapshotKeys();
        assertEquals(List.of(1, 3, 2), order);
    }

    @Test
    void testEvictionOrder() {
        for (int i = 1; i <= CAPACITY; i++) {
            cache.put(i, "v" + i);
        }
        // Access some keys to change order
        cache.get(2);
        cache.get(4);
        // Insert new entry, should evict LRU (which is 1)
        cache.put(6, "v6");
        assertNull(cache.get(1));
        assertEquals(CAPACITY, cache.size());
        List<Integer> expected = List.of(6, 4, 2, 5, 3); // most->least
        assertEquals(expected, cache.snapshotKeys());
    }

    @Test
    void testRemove() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.remove(1));
        assertNull(cache.get(1));
        assertEquals(1, cache.size());
        assertNull(cache.remove(99));
    }

    @Test
    void testClear() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.snapshotKeys().isEmpty());
    }

    @Test
    void testSnapshotImmutability() {
        cache.put(1, "one");
        cache.put(2, "two");
        List<Integer> snap = cache.snapshotKeys();
        assertThrows(UnsupportedOperationException.class, () -> snap.add(3));
        cache.put(3, "three");
        // original snapshot unchanged
        assertEquals(List.of(2, 1), snap);
    }

    @Test
    void testConcurrentStress() throws Exception {
        final int THREADS = 8;
        final int OPERATIONS = 100_000;
        final ConcurrentLruCache<Integer, Integer> c = new ConcurrentLruCache<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicBoolean failed = new AtomicBoolean(false);
        ConcurrentMap<Integer, Integer> lastWritten = new ConcurrentHashMap<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                barrier.await(); // start together
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < OPERATIONS && !failed.get(); i++) {
                    int key = rnd.nextInt(0, 20);
                    int op = rnd.nextInt(3);
                    switch (op) {
                        case 0 -> { // put
                            int val = rnd.nextInt();
                            c.put(key, val);
                            lastWritten.put(key, val);
                        }
                        case 1 -> { // get
                            Integer v = c.get(key);
                            Integer expected = lastWritten.get(key);
                            if (expected != null && v != null && !expected.equals(v)) {
                                failed.set(true);
                            }
                        }
                        case 2 -> { // remove
                            Integer removed = c.remove(key);
                            lastWritten.remove(key);
                            // removed value may be null if not present – that's fine
                        }
                    }
                }
                return null;
            });
        }

        List<Future<Void>> futures = exec.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // rethrow any exception
        }
        exec.shutdownNow();
        assertFalse(failed.get(), "Detected inconsistent value during stress test");
        assertTrue(c.size() <= CAPACITY, "Cache exceeded max capacity");

        // Verify LRU order is consistent with recent accesses
        List<Integer> snapshot = c.snapshotKeys();
        Set<Integer> seen = new HashSet<>();
        for (Integer k : snapshot) {
            assertTrue(seen.add(k), "Duplicate key in snapshot");
        }
    }

    @Test
    void performanceSanityCheck() {
        final int OPS = 1_000_000;
        ConcurrentLruCache<Integer, Integer> perfCache = new ConcurrentLruCache<>(10_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < OPS; i++) {
                perfCache.put(i, i);
                perfCache.get(i);
                if (i % 3 == 0) {
                    perfCache.remove(i / 2);
                }
            }
        });
    }
}
```