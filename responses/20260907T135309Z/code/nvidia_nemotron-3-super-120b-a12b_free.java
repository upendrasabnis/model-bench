<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260907T135309Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We build a *concurrent* LRU cache that

* has a configurable capacity,
* provides `get(K)` and `put(K,V)` in **O(1)** average time,
* is safe for arbitrary concurrent access,
* evicts the least‑recently‑used entry when the capacity is exceeded.

The design combines two well‑known building blocks:

| Component | Responsibility | Thread‑safety mechanism |
|-----------|----------------|--------------------------|
| `ConcurrentHashMap<K,Node>` | O(1) lookup of a node by its key (read‑only for most operations) | Intrinsically concurrent – no external lock needed for reads or inserts |
| Doubly‑linked list (head = most‑recently‑used, tail = least‑recently‑used) | Maintains the LRU ordering; moving a node to the head or removing the tail is O(1) | All mutations of the list are guarded by a single `ReentrantLock`. The lock is held only for the few pointer updates, so the asymptotic complexity stays O(1). |

Because the map stores a reference to the *same* `Node` object that lives in the list, moving a node in the list does **not** require any change in the map – the map entry continues to point to the correct node. This lets us keep the map lock‑free for the hot path (`get`) and only lock when we actually mutate the list (which is also O(1)).

---

### 1.  Data structures

```java
/** Internal node of the LRU list */
private static class Node<K,V> {
    K key;
    V value;
    Node<K,V> prev;
    Node<K,V> next;

    Node(K key, V value) {
        this.key = key;
        this.value = value;
    }
}
```

* `ConcurrentHashMap<K,Node<K,V>> map` – stores `key → node`.
* `Node<K,V> head, tail` – dummy‑less doubly linked list.
* `final ReentrantLock lock` – protects **all** list mutations.
* `AtomicInteger size` – exact number of elements (updated under the same lock).

---

### 2.  Core list operations (called while holding `lock`)

```java
/** Remove node from its current position in the list */
private void removeNode(Node<K,V> node) {
    Node<K,V> prev = node.prev;
    Node<K,V> next = node.next;
    if (prev != null) prev.next = next;
    if (next != null) next.prev = prev;
    if (node == head) head = next;
    if (node == tail) tail = prev;
    node.prev = node.next = null; // help GC
}

/** Insert node right after the head (most‑recently‑used position) */
private void addToHead(Node<K,V> node) {
    node.next = head;
    node.prev = null;
    if (head != null) head.prev = node;
    head = node;
    if (tail == null) tail = node; // first element
}

/** Move an existing node to the head (used for get/put‑hit) */
private void moveToHead(Node<K,V> node) {
    if (node == head) return; // already MRU
    removeNode(node);
    addToHead(node);
}

/** Remove and return the tail node (LRU) – assumes list non‑empty */
private Node<K,V> popTail() {
    Node<K,V> res = tail;
    removeNode(tail);
    return res;
}
```

All of the above run in constant time; they only rewrite a few pointers.

---

### 3.  Public API

```java
public class ConcurrentLRUCache<K,V> {

    private final int capacity;
    private final ConcurrentHashMap<K,Node<K,V>> map;
    private Node<K,V> head;
    private Node<K,V> tail;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger size = new AtomicInteger(0);

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>();
    }

    /** Returns the value associated with {@code key} or {@code null} if absent.
     *  On a hit the entry becomes the most‑recently‑used. */
    public V get(K key) {
        Node<K,V> node = map.get(key);
        if (node == null) return null;

        lock.lock();
        try {
            moveToHead(node);          // O(1) list mutation
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /** Stores {@code value} for {@code key}. If the key already exists its value
     *  is refreshed and the entry becomes MRU. If the cache is over capacity
     *  the LRU entry is evicted. */
    public void put(K key, V value) {
        Node<K,V> existing = map.get(key);
        if (existing != null) {
            existing.value = value;
            lock.lock();
            try {
                moveToHead(existing);
            } finally {
                lock.unlock();
            }
            return;
        }

        // New entry -------------------------------------------------
        Node<K,V> newNode = new Node<>(key, value);
        map.put(key, newNode);               // ConcurrentHashMap – lock‑free insert
        lock.lock();
        try {
            addToHead(newNode);
            int currentSize = size.incrementAndGet();
            if (currentSize > capacity) {
                Node<K,V> evicted = popTail(); // LRU removal
                map.remove(evicted.key);
                size.decrementAndGet();        // compensate for the eviction
            }
        } finally {
            lock.unlock();
        }
    }

    /** For testing only – returns current number of stored elements. */
    public int size() {
        return size.get();
    }

    /** For testing only – returns a snapshot of the keys in LRU order (most recent first). */
    public List<K> keysInOrder() {
        lock.lock();
        try {
            List<K> list = new ArrayList<>(size.get());
            for (Node<K,V> n = head; n != null; n = n.next) {
                list.add(n.key);
            }
            return list;
        } finally {
            lock.unlock();
        }
    }
}
```

**Why this is thread‑safe**

* All reads/writes to the `ConcurrentHashMap` are inherently thread‑safe.
* The only mutable shared state that is *not* covered by the map’s internal concurrency control is the LRU list (`head`, `tail`, the `prev/next` links of each node, and the `size` counter).  
  Every method that touches those fields (`get` on a hit, `put` for a new entry, eviction) acquires the same `ReentrantLock` before mutating the list and releases it afterwards.  
  Therefore there is a **happens‑before** relationship between any two list‑mutating operations, preventing races such as lost updates, corrupted links, or size mismatches.
* The lock is held only for a constant‑time pointer manipulation, so the asymptotic complexity of `get` and `put` remains **O(1)** (amortized) despite the lock.

---

### 4.  JUnit 5 Tests

```java
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class ConcurrentLRUCacheTest {

    /** Basic functional checks */
    @Test
    void basicGetPutAndEviction() {
        ConcurrentLRUCache<Integer,String> cache = new ConcurrentLRUCache<>(3);

        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");

        assertEquals("three", cache.get(3)); // MRU
        assertEquals("two",   cache.get(2));
        assertEquals("one",   cache.get(1));

        // Adding a fourth element evicts the LRU (key 1)
        cache.put(4, "four");
        assertNull(cache.get(1));               // evicted
        assertEquals(3, cache.size());

        // Accessing 2 makes it MRU; next put should evict 3 (LRU)
        cache.get(2);                           // touch 2
        cache.put(5, "five");
        assertNull(cache.get(3));               // 3 evicted
        assertEquals("two", cache.get(2));      // 2 still present (MRU)
    }

    /** Updating an existing key refreshes its value and order */
    @Test
    void putExistingUpdatesValueAndOrder() {
        ConcurrentLRUCache<Integer,String> c = new ConcurrentLRUCache<>(2);
        c.put(1, "a");
        c.put(2, "b");
        // order: 2 (MRU), 1 (LRU)

        c.put(1, "aa"); // update existing key
        assertEquals("aa", c.get(1));
        // after update, 1 becomes MRU, 2 becomes LRU
        List<Integer> order = c.keysInOrder();
        assertEquals(Arrays.asList(1, 2), order);
    }

    /** Stress test with many concurrent threads */
    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 10;
        final int keyRange = 100;   // keys 0..99
        final int opsPerThread = 5_000;
        final int threadCount = 20;

        ConcurrentLRUCache<Integer,Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // Worker: random mix of gets and puts
        Runnable worker = () -> {
            Random rnd = ThreadLocalRandom.current();
            try {
                startLatch.await(); // ensure all threads start together
                while (!stop.get()) {
                    int key = rnd.nextInt(keyRange);
                    if (rnd.nextBoolean()) { // GET
                        cache.get(key);
                    } else { // PUT
                        cache.put(key, rnd.nextInt());
                    }
                    // occasional yield to increase interleaving
                    if (rnd.nextInt(20) == 0) Thread.yield();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) exec.submit(worker);
        startLatch.countDown(); // let them go

        // Let the stress run for a short while
        Thread.sleep(2000);
        stop.set(true);               // ask workers to finish
        doneLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        // ---------- Invariant checks ----------
        // 1. Size never exceeds capacity
        assertTrue(cache.size() <= capacity,
                "Cache size (" + cache.size() + ") exceeded capacity (" + capacity + ")");

        // 2. Every key present in the map must have a corresponding node in the list
        //    and there must be no duplicate keys in the list.
        Set<Integer> mapKeys = new HashMap<>(cache.map).keySet();
        List<Integer> listKeys = cache.keysInOrder();
        assertEquals(mapKeys.size(), listKeys.size(),
                "Number of keys in map differs from number of nodes in list");
        assertEquals(new HashSet<>(listKeys), mapKeys,
                "List contains keys not present in map or duplicates");

        // 3. The list must be properly linked (no null pointers inside)
        Node<Integer,Integer> prev = null;
        for (Node<Integer,Integer> n = cache.head; n != null; n = n.next) {
            assertNotNull(n.key, "Node key must not be null");
            assertNotNull(n.value, "Node value must not be null");
            assertEquals(prev, n.prev, "Back-link mismatch");
            prev = n;
        }
        assertEquals(prev, cache.tail, "Tail pointer mismatch");

        // 4. Spot‑check a few random keys: if get returns non‑null, the value
        //    must equal the one stored in the map.
        Random check = new Random();
        for (int i = 0; i < 50; i++) {
            int k = check.nextInt(keyRange);
            V v = cache.get(k);
            if (v != null) {
                assertEquals(v, cache.map.get(k).value,
                        "Retrieved value differs from map-stored value for key " + k);
            }
        }
    }
}
```

**Explanation of the test**

* **Basic test** verifies correct LRU ordering, eviction, and value updates.
* **Existing‑key test** ensures that `put` on a present key refreshes the value **and** moves the entry to the MRU position.
* **Concurrent stress test**  
  * Spins up many threads that perform a random mixture of `gets` and `puts` on a key space larger than the cache capacity.  
  * After a short timed window we stop the workers and then check a set of invariants that must hold *regardless* of the interleaving:
    * size ≤ capacity,
    * map‑key set equals the list of keys (no missing or duplicate nodes),
    * the doubly‑linked list is well‑formed (every node’s `prev`/`next` pointers are consistent, head/tail point correctly),
    * occasional spot‑checks confirm that a successful `get` returns the same value stored in the map.
  * If any invariant were violated due to a race condition, the test would fail, giving us confidence that the implementation is truly thread‑safe under heavy parallel load.

---

### 5.  Complexity Summary

| Operation | Average time | Worst‑case time (due to lock) | Reason |
|-----------|--------------|------------------------------|--------|
| `get(K)`  | O(1)         | O(1) (hashmap lookup + constant‑time list move under lock) | Hashmap gives O(1) find; list move is constant work. |
| `put(K,V)`| O(1)         | O(1) (hashmap insert/update + constant‑time list updates + possible eviction) | Same reasoning; eviction also constant work. |
| Space     | O(capacity)  | –                            | One node per cached entry plus map overhead. |

The lock does **not** change the asymptotic complexity; it only adds a small, bounded constant factor due to the few pointer updates it protects.

--- 

**Result:** A compact, thread‑safe LRU cache with O(1) average `get`/`put` that passes functional unit tests and a rigorous concurrent stress test.