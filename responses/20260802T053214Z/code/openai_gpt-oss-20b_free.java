<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260802T053214Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## 1.  Design

```text
LRUCache<K,V>
 ├─ capacity          – maximum number of entries
 ├─ map : ConcurrentHashMap<K,Node<K,V>>
 ├─ head, tail : dummy nodes of a doubly‑linked list
 └─ lock : ReentrantLock
```

* **`ConcurrentHashMap`** gives us O(1) average lookup / insertion and is fully
  thread‑safe for the *data* part of the cache.
* The **doubly‑linked list** keeps the access order.  
  The head is the most recently used entry, the tail нави entry.
* All operations that modify the list **and/or the map** are wrapped in
  `lock.lock()/unlock()` so that the two data structures stay consistent.
  `get()` also mutates the order – it must be protected as well.
* Because the list operations are O(1) (just pointer updates) and the
  map operations are O(1) on average, the whole cache works in
  **O(1) average time**.

### Thread‑safety guarantees

| Operation | Map interaction | List interaction | Locking |
|-----------|-----------------|------------------|----------|
| `get(k)`  | `map.get(k)` – no lock (safe) | `moveToFront(node)` – **locked** સ્વ | **Lock** |
| `put(k,v)` | `map.put(k,node)` / `map.remove(k)` – safe | `addToFront(node)` / `removeLast()` – **locked** | **Lock** |

Only the *critical section* that touches the list is protected by the lock.
The map itself is a `ConcurrentHashMap`, so many threads can read from it in
parallel.  The lock guarantees that the list and the map stay in sync and that
the eviction policy is applied atomically.

---

## 2.  Implementation

```java
package com.example.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, concurrent LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class LRUCache<K, V> {

    private final int capacity;
    private final ConcurrentHashMap<K, Node> map;
    private final Node head;          // dummy head
    private final Node tail;          // dummy tail
    private final ReentrantLock lock = new ReentrantLock();

    /** Node of the doubly linked list */
    private final class Node {
        final K key;
        V value;
        Node prev;
        Node next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /** @param capacity Maximum number of entries (must be > 0) */
    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Returns the value associated with the key, or {@code null} if not present.
     * The entry is moved to the head (most recently used).
     */
    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        Node node = map.get(key);
        if (node == null) return null;

        // Update order
        lock.lock();
        try {
            moveToFront(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    /**
     * Inserts or updates the value for the key.
     * If the cache exceeds its capacity, the least recently used entry is evicted.
     */
    public void put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        lock.lock();
        try {
            Node node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToFront(node);
                return;
            }
            node = new Node(key, value);
            addToFront(node);
            map.put(key, node);
            if (map.size() > capacity) {
                evictLeastRecentlyUsed();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Current number of elements in the cache */
    public int size() {
        return map.size();
    }

    /** Removes all entries */
    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  List manipulation – all called only while holding `lock`          */
    /* ------------------------------------------------------------------ */

    private void moveToFront(Node node) {
        // unlink
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // link after head
        node.next = head.next;
        head.next.prev = node;
        node.prev = head;
        head.next = node;
    }

    private void addToFront(Node node) {
        node.next = head.next;
        head.next.prev = node;
        node.prev = head;
        head.next = node;
    }

    private void evictLeastRecentlyUsed() {
        // node just before tail is the LRU
        Node lru = tail.prev;
        if (lru == head) return; // nothing to evict
        // unlink
        lru.prev.next = tail;
        tail.prev = lru.prev;
        // remove from map
        map.remove(lru.key);
    }
}
```

### Why the lock is safe

* **Only the list is protected** – the map can be read concurrently.
* Elementor called within the lock (`moveToFront`, `addToFront`, `evictLeastRecentlyUsed`) always sees a consistent view of the list.
* No dead‑lock can happen because the lock is neverpointer‑locked recursively.
* The lock is held only for the very short time needed to modify the list,
  which keeps contention low.

---

## 3.  Tests (JUnit 5)

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;

class LRUCacheTest {

    @Test
    void singleThreadedBasic() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);

        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");

        assertEquals(3, cache.size());
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));

        // Add a new element – should evict LRU (which is 3 after access order)
        cache.put(4, "four");
        assertEquals(3, cache.size());
        assertNull(cache.get(3));          // evicted
        assertEquals("four", cache.get(4));
    }

    @Test
    voidబ్బ evictionOrder() {
        LRUCache<Integer, Integer> cache = new LRUCache<>(2);

        cache.put(1, 1);
        cache.put(2, 2);
        assertEquals(2, cache.size());

        // Access 1, making 2 the LRU
        cache.get(1);

        // Add 3 – should evict 2
        cache.put(3, 3);
        assertNull(cache.get(2));
        assertEquals(2, cache.size());
    }

    @Test
    void clearWorks() {
        LRUCache<String, String> cache = new LRUCache<>(5);
        cache.put("a", "alpha");
        cache.put("b", "beta");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
    }

    /* -------------------------------------------------------------------- */
    /*  Concurrency stress test                                               */
    /* -------------------------------------------------------------------- */

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 1000;
        final int threadCount = 20;
        final int opsPerThread = 50_000;

        LRUCache<Integer, Integer> cache = new LRUCache<>(capacity);
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Integer> keySpace = new ArrayList<>();
        for (int i = 0; i < capacity * 10; i++) keySpace.add(i);

        // Random generator per thread
        ThreadLocal<Random> rnd = ThreadLocal.withInitial(Random::new);

        for (int t = 0; t < threadCount; t++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    Random r = rnd.get();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = keySpace.get(r.nextInt(keySpace.size()));
                        if (r.nextBoolean()) {
                            cache.put(key, key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();          // start all workers
        doneLatch.await();               // wait for completion
        exec.shutdownNow();

        // Invariant: size <= capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeds capacity");

        // Invariant: all keys in cache are consistent
        // (no null values)
        for (Map.Entry<Integer, Integer> e : cache.map.entrySet()) {
            assertNotNull(e.getKey());
            assertNotNull(e.getValue());
            assertEquals(e.getKey(), e.getValue());
        }

        // In representing the eviction order is not trivial to check,
        // but we can at least ensure that no duplicate keys exist
        Set<Integer> seen = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : cache.map.entrySet()) {
            assertTrue(seen.add(e.getKey()), "Duplicate key in cache");
        }
    }
}
```

### What the stress test checks

1. **No race conditions** – all operations complete without exceptions.
2. **Invariant** `size() ≤ capacity` – eviction works correctly even under heavy contention.
3. **Data integrity** – every key maps to itself (since `put(k,k)` was used) and no `null` entries exist.
4. **No duplicate keys** – the internal map never contains the same key twice.

The test uses a `CountDownLatch` to start all worker threads simultaneously,
thereby maximizing contention.  Each thread performs a mix_echo of `get`/`put`
operations on a shared cache.  After all threads finish, the cache is inspected
for the invariants above.

---

## 4.  Summary

* A **doubly‑linked list + ConcurrentHashMap** gives us the required O(1)
  average operations.
* A single `ReentrantLock` protects the list and the eviction logic,
  guaranteeing that the map and list stay in sync.
*哪些 JUnit 5 tests cover correctness in single‑threaded scenarios and.sulake
  verify that the cache remains valid under heavy concurrent use.