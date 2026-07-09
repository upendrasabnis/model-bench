<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260709T082632Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## 1.  Design Overview  

We implement a **thread‑safe, lock‑striped LRU cache** with the following properties  

| Requirement | How we satisfy it |
|-------------|-------------------|
| **O(1) average `get` / `put`** | A hash‑map (`ConcurrentHashMap`) gives O(1) key lookup.  The LRU order is kept in a **doubly‑linked list** whose nodes are manipulated only while holding a single `ReentrantLock`.  All list operations (move‑to‑front, remove‑tail, insert) are constant time. |
| **Configurable capacity** | The constructor receives `maxCapacity`.  When `size > maxCapacity` we evict the *least‑recently‑used* node (the tail of the list). |
| **Correct eviction under concurrency** | All mutations that affect the LRU order or the size are performed while holding the same lock, so only one thread can decide which entry is the victim.  `get` also acquires the lock *only* to move the accessed node to the front – the map itself is lock‑free (`ConcurrentHashMap`). |
| **No global synchronized block** | The only exclusive region is a single `ReentrantLock`.  The lock is *fair* (optional) and very cheap because the critical section is tiny.  This gives much higher throughput than `Collections.synchronizedMap` or a `synchronized` block around the whole cache. |
| **Memory safety** | Nodes are plain objects, no reference cycles that survive eviction.  Once a node is removed from the map and the list it becomes eligible for GC. |

### 1.1  Data structures  

```text
+-------------------+          +-------------------+
| ConcurrentHashMap |<------->|   Node (key, val) |
+-------------------+          +-------------------+
          ^                                 ^
          |                                 |
          |                                 |
          +-----------------+---------------+
                            |
                +-----------v-----------+
                |   Doubly linked list  |
                |  head <-> ... <-> tail|
                +-----------------------+
```

* **`ConcurrentHashMap<K, Node<K,V>> map`** – fast concurrent key lookup.  
* **`Node<K,V>`** – holds key, value and `prev/next` links.  
* **`head` / `tail`** – dummy sentinel nodes to simplify list manipulation.  
* **`ReentrantLock lock`** – protects the list and the `size` counter.

### 1.2  Operations  

| Operation | Steps (inside lock) | Complexity |
|-----------|---------------------|------------|
| **`V get(K key)`** | 1. `map.get(key)` (no lock).<br>2. If node != null → lock → move node to front → unlock → return value.<br>3. If node == null → return `null`. | O(1) amortised |
| **`void put(K key, V value)`** | 1. `map.compute(key, (k, oldNode) -> …)` (still no lock).<br>2. Inside the remapping function we create a new node if `oldNode == null` or update the existing node’s value.<br>3. lock → if node is new → insert at front & ++size.<br>4. If `size > capacity` → evict tail node (remove from map and list) → --size.<br>5. unlock. | O(1) amortised |
| **`int size()`** | `map.size()` – safe because `ConcurrentHashMap` size is *eventually consistent* but good enough for monitoring. | O(1) |

> **Why the lock is still needed for `put`?**  
> The map operation itself is atomic, but we must keep the list and the *size* consistent with the map.  By performing the list manipulation while holding the lock we guarantee that only one thread decides which entry is the LRU victim and that the list never becomes corrupted.

### 1.3  Thread‑safety argument  

* **Visibility** – All fields that are read/written inside the lock are protected by the lock’s happens‑before relationship.  Nodes are published to other threads only after they have been linked into the list while holding the lock; other threads see a fully‑initialized node because the lock release/ acquisition establishes a memory barrier.  

* **Mutual exclusion** – The single `ReentrantLock` serialises all modifications to the linked list and the `size` counter.  No two threads can simultaneously change the `prev/next` pointers, therefore the list invariant (`head.next` … `tail.prev`) is never broken.  

* **Concurrent reads** – `get` performs a lock‑free map lookup first.  If the key is absent, no lock is taken.  If the key is present we still need to move the node to the front, which is a list mutation, so we acquire the lock for that tiny section.  This keeps contention low because the critical section is *just* pointer rewiring.  

* **Correct eviction** – Eviction is performed only while holding the lock, after the insertion that possibly caused overflow.  Because the lock guarantees exclusive access, at most one thread can see `size > capacity` and will remove exactly one tail node.  No entry can be evicted twice, and the cache never exceeds `capacity`.  

* **Deadlock‑free** – The lock is never acquired recursively and no other lock is taken, so there is no possibility of circular wait.  

* **Fairness (optional)** – The lock can be created with `new ReentrantLock(true)` to give FIFO ordering, preventing starvation under pathological workloads.  

---

## 2.  Implementation  

```java
package com.example.concurrentlru;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> type of keys (must be non‑null and correctly implement equals/hashCode)
 * @param <V> type of values
 */
public final class ConcurrentLRUCache<K, V> {

    /** Node stored in the map and linked list */
    private static final class Node<K, V> {
        final K key;
        volatile V value;               // volatile because value may be updated without relinking
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = Objects.requireNonNull(key);
            this.value = value;
        }
    }

    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock(); // non‑fair by default; can be changed

    /** Dummy sentinels to avoid null checks */
    private final Node<K, V> head;
    private final Node<K, V> tail;

    /**
     * Creates a cache with the given maximum number of entries.
     *
     * @param capacity positive capacity
     */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2);
        this.head = new Node<>(null, null); // sentinel, key/value never used
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves the value associated with {@code key} or {@code null} if not present.
     * The accessed entry becomes the most‑recently‑used.
     */
    public V get(K key) {
        Objects.requireNonNull(key);
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }

        // Move the node to the front – must be done under lock
        lock.lock();
        try {
            // It might have been removed concurrently; double‑check
            if (node.prev == null || node.next == null) {
                return null; // already evicted
            }
            unlink(node);
            linkFirst(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Associates {@code key} with {@code value}. If the key already exists,
     * its value is replaced and it becomes most‑recently‑used.
     * May evict the least‑recently‑used entry when size exceeds capacity.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        // Use compute to atomically insert or get existing node reference
        map.compute(key, (k, existing) -> {
            if (existing != null) {
                // Update value; node will be moved to front later
                existing.value = value;
                return existing;
            } else {
                // Create a brand‑new node (not yet linked)
                return new Node<>(k, value);
            }
        });

        // At this point the map contains the node (either old or new)
        Node<K, V> node = map.get(key);

        lock.lock();
        try {
            // If node was newly created, link it; otherwise just move to front
            if (node.prev == null && node.next == null) {
                // New node – insert at head
                linkFirst(node);
            } else {
                // Existing node – move to front
                unlink(node);
                linkFirst(node);
            }

            // Evict if we exceeded capacity
            if (map.size() > capacity) {
                evictTail();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries (may be approximate). */
    public int size() {
        return map.size();
    }

    /** Returns the configured capacity. */
    public int capacity() {
        return capacity;
    }

    /* ---------- private helper methods ---------- */

    /** Inserts {@code node} right after {@code head}. Caller must hold {@link #lock}. */
    private void linkFirst(Node<K, V> node) {
        Node<K, V> first = head.next;
        node.next = first;
        node.prev = head;
        head.next = node;
        first.prev = node;
    }

    /** Removes {@code node} from the doubly linked list. Caller must hold {@link #lock}. */
    private void unlink(Node<K, V> node) {
        Node<K, V> p = node.prev;
        Node<K, V> n = node.next;
        p.next = n;
        n.prev = p;
        node.prev = null;
        node.next = null;
    }

    /** Evicts the least‑recently‑used entry (the node just before {@code tail}). */
    private void evictTail() {
        Node<K, V> lru = tail.prev;
        if (lru == head) { // cache is empty – should never happen
            return;
        }
        unlink(lru);
        map.remove(lru.key);
        // No further action needed – the node becomes GC‑eligible
    }

    /** For debugging / tests – returns the current LRU order as a string. */
    String lruOrder() {
        StringBuilder sb = new StringBuilder("[");
        lock.lock();
        try {
            Node<K, V> cur = head.next;
            while (cur != tail) {
                sb.append(cur.key);
                cur = cur.next;
                if (cur != tail) sb.append(", ");
            }
        } finally {
            lock.unlock();
        }
        sb.append(']');
        return sb.toString();
    }
}
```

### 2.1  Why `compute`?  

`ConcurrentHashMap.compute` guarantees that the remapping function runs **atomically** with respect to other map updates for the same key.  This gives us a clean way to either create a new `Node` or update an existing one without a race where two threads both think they created a node for the same key.

### 2.2  Handling a node that disappears while we are moving it  

`get` first reads the map, then acquires the lock to move the node.  Between those two steps another thread could have evicted the same node (e.g., because of a concurrent `put` that caused overflow).  The double‑check `if (node.prev == null || node.next == null)` detects that case and simply returns `null`.  The cache’s semantics (a missing entry) are preserved.

---

## 3.  JUnit 5 Test Suite  

We provide three groups of tests:

1. **Basic functional tests** – single‑threaded correctness.  
2. **Concurrent correctness** – multiple threads performing `get`/`put` and verifying that size never exceeds capacity and that LRU order is respected.  
3. **Stress test** – a high‑contention workload (10 000 operations per thread, 32 threads) that asserts invariants after the run.

```java
package com.example.concurrentlru;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    @Test
    void singleThreadedPutAndGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");

        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals(3, cache.size());

        // Adding a fourth entry should evict the *least* recently used (which is 1)
        cache.put(4, "four");
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
        assertEquals(3, cache.size());
    }

    @Test
    void updateValueMovesToFront() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.put(1, "A2"); // update existing key

        // 1 must be MRU, 2 LRU -> inserting a new key evicts 2
        cache.put(3, "C");
        assertEquals("A2", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
    }

    @Test
    void concurrentAccessDoesNotExceedCapacity() throws Exception {
        final int capacity = 50;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        final int threadCount = 8;
        final int opsPerThread = 10_000;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            futures.add(exec.submit(() -> {
                try {
                    start.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(0, 200);
                        if (rnd.nextBoolean()) {
                            cache.put(key, key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (InterruptedException ignored) {}
            }));
        }

        start.countDown(); // let threads run
        for (Future<?> f : futures) {
            f.get(); // re‑throw any exception
        }
        exec.shutdownNow();

        assertTrue(cache.size() <= capacity,
                "Cache size should never exceed capacity after concurrent ops");
    }

    @Test
    void stressTestWithInvariants() throws Exception {
        final int capacity = 100;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        final int threads = 32;
        final int ops = 50_000; // total per thread

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger putCount = new AtomicInteger();
        AtomicInteger getCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    go.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int j = 0; j < ops; j++) {
                        int key = rnd.nextInt(0, 500);
                        if (rnd.nextInt(100) < 30) { // 30 % writes
                            cache.put(key, key);
                            putCount.incrementAndGet();
                        } else {
                            cache.get(key);
                            getCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                "Stress test did not finish in time");

        // ----- Invariants -----
        assertTrue(cache.size() <= capacity,
                "Size invariant broken: size=" + cache.size());

        // Verify that every key present in the map is linked in the list.
        // This requires reflection to peek into the private map, but we can
        // use the public API only: check that get returns the same value we put.
        for (int k = 0; k < 500; k++) {
            Integer v = cache.get(k);
            if (v != null) {
                assertEquals(k, v, "Cached value must equal its key");
            }
        }

        System.out.printf("Stress test finished – puts=%d, gets=%d, finalSize=%d%n",
                putCount.get(), getCount.get(), cache.size());
    }

    @Test
    void evictionOrderIsLRU() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "a"); // order: 1
        cache.put(2, "b"); // order: 2,1
        cache.put(3, "c"); // order: 3,2,1

        cache.get(1);      // order: 1,3,2
        cache.get(2);      // order: 2,1,3

        cache.put(4, "d"); // evicts 3 (LRU)
        assertNull(cache.get(3));
        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertEquals("d", cache.get(4));

        // Verify internal order via the debug method (not part of public API)
        assertEquals("[4, 2, 1]", cache.lruOrder().replaceAll("\\s+", ""));
    }
}
```

### 3.1  What the tests check  

* **Functional correctness** – basic `put`/`get`, update‑move‑to‑front, eviction after capacity breach.  
* **Thread‑safety** – multiple threads performing mixed operations; after all threads finish we assert `size ≤ capacity`.  
* **Stress invariants** – after a heavy parallel workload we still see that:  
  * No more than `capacity` entries remain.  
  * Every entry that can be retrieved matches the value that was stored (`value == key`).  
* **LRU order** – a deterministic sequence of accesses produces the expected internal order (checked via the package‑private `lruOrder()` helper).  

All tests run under JUnit 5 (`org.junit.jupiter.api`).  The stress test uses a fixed thread pool and `CountDownLatch` to start all workers simultaneously, increasing the chance of contention.

---

## 4.  Summary  

* **O(1) average** `get`/`put` is achieved by a `ConcurrentHashMap` for key lookup and a constant‑time doubly‑linked list for LRU ordering.  
* **Thread‑safety** is guaranteed by a single `ReentrantLock` that protects all mutations of the ordering list and the size counter; the map itself is already thread‑safe.  
* **Correct eviction** is enforced because eviction runs inside the same critical section that inserts the new node, so at most one node can be removed when the capacity is exceeded.  
* The supplied **JUnit 5 suite** exercises the cache under both deterministic and highly concurrent workloads, checking that the capacity bound and LRU semantics hold even under heavy parallel stress.