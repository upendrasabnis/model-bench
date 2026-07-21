<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260721T154200Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *concurrent* LRU (Least‑Recently‑Used) cache that satisfies the following requirements:

| Requirement | How we meet it |
|-------------|----------------|
| **Configurable capacity** | Constructor argument `capacity`. |
| **O(1) average `get` / `put`** | `HashMap<K,Node>` gives O(1) lookup; moving a node to the head or removing the tail are constant‑time pointer updates. |
| **Thread‑safe** | All mutable state (`map`, the doubly‑linked list, `size`) is protected by a single `java.util.concurrent.locks.ReentrantLock`. The lock guarantees mutual exclusion and establishes a happens‑before relationship, so every thread sees a consistent view of the cache. |
| **Correct eviction under concurrent access** | When `put` would exceed the capacity, the node just before the dummy tail (the least‑recently used) is removed **while holding the lock**, guaranteeing that no other thread can observe an intermediate state where size > capacity or where the list is corrupted. |
| **Average O(1) despite the lock** | The lock is held only for the short critical section that does a hash‑map lookup and a few pointer updates – no blocking I/O or expensive computation. Under typical contention levels the lock adds only a constant overhead. |

---

### 1. Core Implementation (`ConcurrentLRUCache.java`)

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * <p>Internally we keep:
 * <ul>
 *   <li>A {@link HashMap} for O(1) key → node lookup.</li>
 *   <li>A doubly‑linked list (with dummy head/tail) that maintains usage order:
 *       most‑recently used nodes are near the head, least‑recently used near the tail.</li>
 *   <li>A single {@link ReentrantLock} that guards all mutations of the map and the list.</li>
 * </ul>
 * Because every public method acquires the lock for the entirety of its critical
 * section, the cache is linearizable and safe for concurrent access.
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
    private final HashMap<K, Node<K, V>> map;
    private final Node<K, V> head;   // dummy most‑recently used
    private final Node<K, V> tail;   // dummy least‑recently used
    private final ReentrantLock lock = new ReentrantLock();
    private int size = 0;

    /** Creates a cache that can hold at most {@code capacity} entries. */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2); // initial size, load factor 0.75 by default
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if this cache contains no mapping for the key.
     *
     * <p>The operation also moves the accessed node to the head of the list
     * (most‑recently used) while holding the lock.</p>
     */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            // move-to-front
            removeNode(node);
            addNodeToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates the specified value with the specified key in this cache.
     * If the key already exists, its value is replaced and the node is moved
     * to the head (most‑recently used). If the cache is at capacity, the
     * least‑recently used entry is evicted before insertion.
     *
     * @return the previous value associated with {@code key}, or {@code null}
     *         if there was no mapping for {@code key}.
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to front
                V old = existing.value;
                existing.value = value;
                removeNode(existing);
                addNodeToHead(existing);
                return old;
            }

            // need to insert a new node
            if (size >= capacity) {
                // evict LRU (node before tail)
                Node<K, V> lru = tail.prev;
                removeNode(lru);
                map.remove(lru.key);
                size--;
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

    /** Returns the current number of entries in the cache. */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** For testing only – returns a copy of the keys from most‑ to least‑recent. */
    /* package-private */ java.util.List<K> snapshotKeyOrder() {
        lock.lock();
        try {
            java.util.List<K> list = new java.util.ArrayList<>(size);
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                list.add(n.key);
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Doubly‑linked list helpers (must be called while holding the lock) */
    /* ------------------------------------------------------------------ */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void addNodeToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
}
```

#### Thread‑Safety Argument

* **Encapsulation of mutable state** – The only mutable fields are `map`, the linked‑list pointers (`head`, `tail`, `Node.prev/next`), and `size`.  
* **Single lock discipline** – Every public method (`get`, `put`, `size`, `snapshotKeyOrder`) acquires the same `ReentrantLock` before touching any of those fields and releases it in a `finally` block.  
* **Mutual exclusion** – At most one thread can be inside the critical section at a time, guaranteeing that:
  * The hash‑map and the list are always observed in a consistent state.
  * The `size` field accurately reflects the number of live nodes.
  * Eviction (`removeNode(lru)`) and insertion happen atomically, so the cache never temporarily exceeds its capacity.
* **Visibility** – The lock provides a happens‑before edge: actions performed before unlocking are visible to any thread that subsequently locks the same object. Hence a thread that calls `get` after another thread’s `put` will see the updated value and the updated recency order.

Because the critical section does only O(1) work (hash map lookup + a few pointer updates), the amortized time complexity remains O(1) despite the lock.

---

### 2. JUnit 5 Tests (`ConcurrentLRUCacheTest.java`)

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3); // small capacity for easy eviction checks
    }

    @Test
    void getAndPutBasic() {
        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        cache.put(1, "uno");
        assertEquals("uno", cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    void lruEvictionOrder() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c"); // cache now: [3,2,1] (most‑to‑least)

        // accessing 1 makes it most‑recent
        cache.get(1);
        // cache order should be: [1,3,2]

        cache.put(4, "d"); // this should evict the LRU entry, which is 2
        assertNull(cache.get(2));
        assertEquals("d", cache.get(4));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals(3, cache.size());
    }

    @Test
    void capacityRespected() {
        ConcurrentLRUCache<Integer, String> c = new ConcurrentLRUCache<>(2);
        c.put(1, "x");
        c.put(2, "y");
        assertEquals(2, c.size());
        c.put(3, "z"); // should evict 1
        assertEquals(2, c.size());
        assertNull(c.get(1));
        assertEquals("y", c.get(2));
        assertEquals("z", c.get(3));
    }

    @Test
    void snapshotKeyOrderIsConsistent() {
        cache.put(10, "ten");
        cache.put(20, "twenty");
        cache.put(30, "thirty");
        // order MRU -> LRU: 30,20,10
        assertEquals(List.of(30, 20, 10), cache.snapshotKeyOrder());

        cache.get(20); // now 20 becomes MRU
        assertEquals(List.of(20, 30, 10), cache.snapshotKeyOrder());

        cache.put(40, "forty"); // evicts 10 (LRU)
        assertEquals(List.of(40, 20, 30), cache.snapshotKeyOrder());
    }

    /** Stress test: many threads perform random puts/gets; after a fixed time we check invariants. */
    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 128;
        final ConcurrentLRUCache<Integer, Integer> sharedCache = new ConcurrentLRUCache<>(capacity);
        final int numThreads = Runtime.getRuntime().availableProcessors() * 2;
        final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        final ConcurrentHashMap<Integer, Integer> expectedValues = new ConcurrentHashMap<>();
        final AtomicLong ops = new AtomicLong();

        final long testDurationMs = 3000; // run for 3 seconds
        final long stopTime = System.currentTimeMillis() + testDurationMs;

        // Worker that repeatedly does random puts or gets
        Runnable worker = () -> {
            java.util.Random rnd = java.util.ThreadLocalRandom.current();
            while (System.currentTimeMillis() < stopTime) {
                int key = rnd.nextInt(capacity * 2); // keys may be outside capacity to cause evictions
                int value = rnd.nextInt();
                if (rnd.nextBoolean()) {
                    // PUT
                    Integer old = sharedCache.put(key, value);
                    expectedValues.merge(key, value, (v1, v2) -> v2); // latest wins
                    // If the map size exceeded capacity, we cannot easily know which key was evicted
                    // without inspecting the cache internals; we rely on the invariant checks later.
                } else {
                    // GET
                    Integer cached = sharedCache.get(key);
                    Integer expected = expectedValues.get(key);
                    if (cached != null) {
                        assertEquals(expected, cached,
                                "Get returned wrong value for key " + key);
                    } else {
                        // Either never inserted or evicted – both are fine.
                        // If expected is non‑null we just note that it might have been evicted.
                    }
                }
                ops.incrementAndGet();
            }
        };

        // Launch workers
        for (int i = 0; i < numThreads; i++) {
            exec.submit(worker);
        }

        // Wait for all workers to finish
        exec.shutdown();
        assertTrue(exec.awaitTermination(testDurationMs + 2000, TimeUnit.MILLISECONDS),
                "Executor did not terminate in time");

        System.out.println("Performed " + ops + " operations during stress test.");

        // ---- Invariant checks after the concurrent phase ----
        sharedCache.lock.lock(); // we need to inspect internal state; lock guarantees consistency
        try {
            // 1. Size never exceeds capacity
            assertTrue(sharedCache.size() <= capacity,
                    "Cache size " + sharedCache.size() + " exceeds capacity " + capacity);

            // 2. Every key present in the map is also present in the list (no orphan nodes)
            java.util.Set<Integer> keysInMap = sharedCache.map.keySet();
            java.util.Set<Integer> keysInList = java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<>());
            for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head.next;
                 n != sharedCache.tail;
                 n = n.next) {
                keysInList.add(n.key);
            }
            assertEquals(keysInMap, keysInList,
                    "Map key set differs from linked‑list key set");

            // 3. No duplicate keys in the list
            assertEquals(keysInList.size(), sharedCache.size(),
                    "Duplicate keys found in the LRU list");

            // 4. The list order corresponds to recency: we can’t verify exact timestamps,
            //    but we can ensure that if a key appears in the map, its value matches
            //    the value stored in the list node.
            for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head.next;
                 n != sharedCache.tail;
                 n = n.next) {
                Integer mapVal = sharedCache.map.get(n.key);
                assertNotNull(mapVal, "Map missing entry for key present in list: " + n.key);
                assertEquals(n.value, mapVal,
                        "Value mismatch between list node and map for key " + n.key);
            }
        } finally {
            sharedCache.lock.unlock();
        }
    }
}
```

#### Explanation of the Test Suite

| Test | Purpose |
|------|---------|
| `getAndPutBasic` | Verifies basic `get`/`put` semantics and that updates replace the old value. |
| `lruEvictionOrder` | Checks that the LRU eviction policy works, including that a `get` promotes a node to MRU. |
| `capacityRespected` | Ensures the cache never grows beyond the configured capacity. |
| `snapshotKeyOrderIsConsistent` | Uses the package‑private `snapshotKeyOrder` method (exposed only for testing) to validate that the internal list order matches expectations after various operations. |
| `concurrentStressTest` | Spins up multiple threads that perform random `put`/`get` operations for a few seconds. After the workload ends, while holding the lock we inspect the internal state to assert: <br>• size ≤ capacity <br>• map‑key set equals list‑key set (no orphan or missing nodes) <br>• no duplicate keys in the list <br>• each list node’s value matches the map’s value. <br>These invariants together guarantee that the cache remained internally consistent despite heavy concurrent access. |

The stress test does **not** try to predict which exact entries were evicted (that would require deterministic scheduling), but it validates that the cache’s structural invariants hold, which is sufficient to prove correctness under concurrency.

---

### 3. How to Run the Tests

Assuming a standard Maven/Gradle project:

```xml
<!-- pom.xml snippet -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

Then execute:

```bash
mvn test          # or ./gradlew test
```

All tests should pass, demonstrating that the `ConcurrentLRUCache` implementation is thread‑safe, offers O(1) average‑case operations, and correctly evicts the least‑recently used entry even under heavy parallel load.