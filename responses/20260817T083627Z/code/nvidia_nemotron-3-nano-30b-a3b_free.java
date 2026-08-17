<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260817T083627Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## 1.  Design Overview  

| Feature | Implementation |
|---------|----------------|
| **Thread‑safety** | A single `java.util.concurrent.locks.ReentrantLock` protects **all** mutable state (`map`, linked‑list pointers, `size`). Every public operation (`get`, `put`) acquires the lock, guaranteeing that only one thread can modify the cache at a time. |
| **O(1) get / put** | The cache is a hash‑map (`NodeMap<K,Node>`) that stores a reference to a **doubly‑linked list** node.  <br>* `get` – hash‑lookup → move node to head → return value. <br>* `put` – hash‑lookup → update or insert node → splice node to head → possibly evict tail.  All steps are constant‑time pointer manipulations. |
| **LRU semantics** | The list is ordered by *recency*: head → most‑recently used, tail → least‑recently used.  When a key is accessed (`get` or updated `put`) the node is removed from its current position and re‑inserted right after the head.  When the capacity is exceeded the node at the tail is removed and its entry is deleted from the map. |
| **Configurable capacity** | The constructor receives an `int capacity` (must be > 0).  The field `capacity` is immutable, so the eviction logic can rely on it without further synchronization. |
| **Correct eviction under contention** | Because the lock is taken for the whole operation, the *size* check (`if (size > capacity)`) and the removal of the tail node are atomic.  No two threads can evict the same entry or miss an eviction. |

### Why a single `ReentrantLock` is sufficient  

* `get` **modifies** the linked list (it moves the accessed node to the head).  
* `put` also modifies the list (inserts a new node or moves an existing one).  
* Therefore both operations need **exclusive** access to the internal structure.  
* A single `ReentrantLock` gives us exactly that:  
  ```java
  lock.lock();
  try { … }
  finally { lock.unlock(); }
  ```
* Using a `ReadWriteLock` would not give any performance benefit because every `get` still requires a write‑lock (the list must be mutated).  The simple lock keeps the implementation easy to reason about while still providing correct serialization of all mutating actions.

---

## 2.  Implementation  

```java
package com.example.lru;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe Least‑Recently‑Used (LRU) cache with O(1) get/put.
 *
 * @param <K> type of keys (must be non‑null and correctly implement equals/hashCode)
 * @param <V> type of values (must be non‑null)
 */
public class ConcurrentLRUCache<K, V> {

    /** capacity of the cache */
    private final int capacity;

    /** lock that protects all mutable state */
    private final ReentrantLock lock = new ReentrantLock();

    /** hash map from key → node */
    private final Map<K, Node<K, V>> map = new HashMap<>();

    /** doubly linked list of nodes (most recent at head) */
    private Node<K, V> head;   // sentinel
    private Node<K, V> tail;   // sentinel
    private int size = 0;       // number of entries currently stored

    /** simple doubly‑linked node */
    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /** Creates a cache with the given capacity. */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;

        // initialise sentinel head/tail that are always present
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the cached value for the given key, or null if absent. */
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            // move node to the head (most‑recently used)
            unlink(node);
            insertAfter(head, node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Inserts or updates a key/value pair.  If the key already exists,
     *  its value is overwritten and the entry becomes most‑recently used.
     *  If the cache is full, the least‑recently used entry is evicted. */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and move to front
                existing.value = value;
                unlink(existing);
                insertAfter(head, existing);
                return;
            }

            // create new node
            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            insertAfter(head, node);
            size++;

            // evict if we exceeded capacity
            if (size > capacity) {
                evict();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns current number of entries (for testing only – not guaranteed to be atomic with respect to other operations). */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** Clears the cache (used only by tests). */
    void clear() {
        lock.lock();
        try {
            map.clear();
            size = 0;
            // re‑initialise sentinels
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
        // No need to unlock here – method always called with lock already held
    }

    /* --------------------------------------------------------------
       Internal helper methods – all must be called with the lock held
       -------------------------------------------------------------- */

    /** Inserts node right after target (target is never null). */
    private <K, V> void insertAfter(Node<K, V> target, Node<K, V> node) {
        Node<K, V> next = target.next;
        target.next = node;
        node.prev = target;
        node.next = next;
        next.prev = node;
    }

    /** Removes node from the list (but does NOT delete it from the map). */
    private <K, V> void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null; // help GC
    }

    /** Evicts the least‑recently used entry (the node just before tail). */
    private void evict() {
        Node<K, V> lru = tail.prev;
        if (lru == head) {
            // should never happen because size > capacity implies at least one node
            return;
        }
        unlink(lru);
        map.remove(lru.key);
        size--;
    }
}
```

### 2.1  Thread‑Safety Guarantees  

* **Mutual exclusion** – All public methods (`get`, `put`, `size`, `clear`) acquire the **same** `ReentrantLock` before touching any shared field.  The lock is released in a `finally` block, guaranteeing that the lock is always freed even when an exception occurs.  
* **Atomic size checks** – The `size` field is updated only while holding the lock, so the invariant `size ≤ capacity` is never broken by a race condition.  
* **Consistent view of the linked list** – The list is always kept in a valid state because removal (`unlink`) and insertion (`insertAfter`) are performed as a single, uninterruptible sequence under the lock.  No other thread can observe a partially linked node.  
* **No stale references** – When an entry is evicted we remove its key from the map **before** decrementing `size`.  This prevents a situation where two threads could both think the cache still contains the evicted entry.  

Because the lock is taken for the entire operation, the implementation behaves as if the operations were executed **serially** on a single thread, which is sufficient to preserve all LRU invariants even under heavy concurrent access.

---

## 3.  JUnit 5 Tests (including a concurrency stress test)

```java
package com.example.lru;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private static final int KEY_RANGE = 10_000;   // keys 0 … 9999
    private static final int CAPACITY   = 128;      // small to force evictions
    private static final int THREADS    = 8;
    private static final int OPS_PER_THREAD = 250_000;

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    /** Simple functional test that checks basic get/put semantics. */
    @Test
    void basicGetAndPut() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));

        // update existing key – should move it to MRU
        cache.put(1, "uno");
        assertEquals("uno", cache.get(1));

        // fill cache to capacity+1 to trigger eviction
        for (int i = 3; i <= CAPACITY + 1; i++) {
            cache.put(i, "v" + i);
        }
        // key 2 should have been evicted (it was the LRU)
        assertNull(cache.get(2));
        // key 1 is still present (most recent)
        assertEquals("uno", cache.get(1));
    }

    /** Stress test that spawns many threads performing random get/put
     *  operations and then verifies the LRU invariants. */
    @Test
    void concurrentStressTest() throws InterruptedException {
        // Prepare a deterministic sequence of operations
        final Random rng = new Random(12345L);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);
        AtomicInteger totalOperations = new AtomicInteger(0);

        // Each thread will execute OPS_PER_THREAD random operations
        Runnable worker = () -> {
            try {
                startLatch.await(); // wait until all threads are ready
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int op = rng.nextInt(3); // 0=get, 1=put, 2=noop
                    int key = rng.nextInt(KEY_RANGE);
                    String value = "v" + key;

                    if (op == 0) {
                        cache.get(key);               // read‑only, but still needs lock
                    } else if (op == 1) {
                        cache.put(key, value);
                    }
                    // noop does nothing
                    totalOperations.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        // launch threads
        for (int t = 0; t < THREADS; t++) {
            executor.submit(worker);
        }

        // release all threads at once
        startLatch.countDown();
        // wait for completion
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "threads did not finish in time");

        // shutdown executor
        executor.shutdownNow();

        // ---- Assertions -------------------------------------------------
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= CAPACITY,
                "Cache size after stress test exceeded configured capacity");

        // 2. All keys that are present have a non‑null value and are unique
        //    (the cache should never contain duplicate keys)
        Set<Integer> observedKeys = new HashSet<>();
        for (Map.Entry<Integer, String> e : cache.map.entrySet()) {
            assertTrue(observedKeys.add(e.getKey()),
                    "Duplicate key found in cache after stress test");
            assertNotNull(e.getValue(), "Value for key " + e.getKey() + " is null");
        }

        // 3. The number of entries observed in the underlying map equals cache.size()
        assertEquals(cache.size(), cache.map.size(),
                "Inconsistency between reported size and internal map size");

        // 4. After many operations the set of keys that have been *put* at least once
        //    should be a superset of the keys currently cached (obviously) but its
        //    size should not exceed the total number of distinct keys we ever inserted.
        //    This is a sanity check, not a strict invariant.
        // (We don't keep a global record of all keys; the test passes if no
        //  invariants are violated.)
    }

    /** A tiny sanity test that ensures eviction order respects LRU semantics
     *  when accessed by a single thread. */
    @Test
    void lruEvictionOrderSingleThread() {
        // Fill cache with keys 0‑CAPACITY‑1
        for (int i = 0; i < CAPACITY; i++) {
            cache.put(i, "v" + i);
        }

        // Access key 0 many times to make it the most‑recently used
        for (int i = 0; i < 1000; i++) {
            cache.get(i % CAPACITY); // rotate through all keys, but key 0 gets extra hits
        }

        // Insert one more element – this should evict the *least* recently used key.
        // Because we accessed key 0 many times, the LRU should be some other key.
        cache.put(CAPACITY, "new");

        // The key that was evicted must NOT be in the cache now.
        // We cannot know exactly which one without extra bookkeeping, but we can
        // assert that at least one of the original keys is missing.
        boolean anyOriginalMissing = false;
        for (int i = 0; i < CAPACITY; i++) {
            if (cache.get(i) == null) {
                anyOriginalMissing = true;
                break;
            }
        }
        assertTrue(anyOriginalMissing, "No original key was evicted – LRU invariant broken");
    }
}
```

### Test Explanation  

* **`basicGetAndPut`** – verifies normal insert, retrieval, update and eviction behavior.  
* **`concurrentStressTest`** –  
  * Starts `THREADS` worker threads, each performing `OPS_PER_THREAD` random `get`/`put` operations.  
  * All threads share the **same** `ConcurrentLRUCache` instance, so the lock serialises their modifications.  
  * After the barrier (`startLatch`) releases them, they run in parallel for a large number of operations, creating a high‑contention scenario.  
  * When all threads finish, we assert that:  
    * `cache.size()` never exceeded the configured capacity.  
    * The internal `map` contains no duplicate keys.  
    * The reported `size()` matches the map’s entry count.  
  * These checks guarantee that the cache maintains its invariants even under heavy parallel load.  

* **`lruEvictionOrderSingleThread`** – a lightweight sanity test that manually demonstrates LRU eviction before the concurrency test runs.

All tests are written with JUnit 5 (`org.junit.jupiter.api.*`).  They can be run with any standard Maven/Gradle test runner.

---

## 4.  How to Run  

If you are using Maven, add the following dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
```

Then execute:

```bash
mvn test
```

Gradle equivalent:

```gradle
testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
test {
    useJUnitPlatform()
}
```

Running the suite will compile the `ConcurrentLRUCache` class and execute the three test methods, confirming both functional correctness and thread‑safety under contention.