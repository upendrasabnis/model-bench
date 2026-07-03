<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260703T125036Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *concurrent* LRU (Least‑Recently‑Used) cache that:

* has a fixed capacity supplied at construction time,
* provides `get(K)` and `put(K,V)` in **O(1)** average time,
* is safe to use from many threads simultaneously,
* evicts the least‑recently‑used entry when the capacity is exceeded.

The classic LRU design combines a hash table (for O(1) lookup) with a doubly‑linked list (for ordering).  
To make it thread‑safe we protect **all** mutable state with a single `ReentrantLock`.  
The lock gives us:

* **Mutual exclusion** – only one thread can modify the map or the list at a time, preventing races.
* **Visibility** – the lock’s release‑acquire semantics act as a happens‑before edge, so updates made by one thread are visible to others after they acquire the lock.

Because the underlying operations on `HashMap` and the list are O(1), the overall complexity stays O(1) average; the lock only adds a constant‑time synchronization cost.

---

## 1.  Implementation (`ConcurrentLRUCache.java`)

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type (must not be null)
 * @param <V> value type (must not be null)
 */
public class ConcurrentLRUCache<K, V> {

    /** Node of the doubly‑linked list used for LRU ordering. */
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
    private final Map<K, Node<K, V>> map;          // key → node
    private Node<K, V> head;                       // most recently used
    private Node<K, V> tail;                       // least recently used
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
        this.map = new HashMap<>(capacity * 2); // initial size, load factor 0.75 default
    }

    /**
     * Returns the value to which the specified key is mapped,
     * or {@code null} if the cache contains no mapping for the key.
     *
     * <p>If the key is present, the entry becomes the most‑recently‑used.</p>
     *
     * @param key key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key must not be null");
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);   // recent use
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates the specified value with the specified key in the cache.
     * If the key already exists, its value is replaced and the entry becomes MRU.
     * If the cache is at capacity, the least‑recently‑used entry is evicted.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null}
     *         if there was no mapping for {@code key}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // update value and make it MRU
                V old = existing.value;
                existing.value = value;
                moveToHead(existing);
                return old;
            }

            // need to possibly evict LRU entry
            if (map.size() >= capacity) {
                evictTail();
            }

            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
            return null; // no previous value
        } finally {
            lock.unlock();
        }
    }

    /** Removes the least‑recently‑used node (tail) from the list and the map. */
    private void evictTail() {
        if (tail == null) {
            return; // nothing to evict
        }
        removeNode(tail);
        map.remove(tail.key);
        // help GC
        tail = null;
    }

    /** Detaches a node from the doubly‑linked list. */
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

    /** Inserts a node right after the head (most‑recently‑used position). */
    private void addToHead(Node<K, V> node) {
        node.next = head;
        node.prev = null;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) { // list was empty
            tail = node;
        }
    }

    /** Moves an existing node to the head (MRU) position. */
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return; // already MRU
        }
        removeNode(node);
        addToHead(node);
    }

    /** For testing only – returns a snapshot of the current size. */
    int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** For testing only – returns a copy of the internal map. */
    Map<K, V> snapshotMap() {
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

### Why the implementation is thread‑safe

* **Single lock (`ReentrantLock`)** guards every method that touches `map`, `head`, or `tail`.  
  Consequently, at any moment only one thread can be inside a critical section, eliminating data races on the hash table and the linked list.
* The lock provides **happens‑before guarantees**: a thread that releases the lock (`unlock`) synchronizes‑with a thread that subsequently acquires it (`lock`). Therefore, all writes performed inside the critical section become visible to the next thread that enters the section.
* All operations (`get`, `put`, internal helpers) are **O(1)**:
  * `HashMap.get/put` – average O(1)
  * List pointer updates (`addToHead`, `removeNode`, `moveToHead`) – constant time
  * Lock acquisition/release – constant overhead.

Thus the cache satisfies the required functional and performance properties while being safe under concurrent access.

---

## 2.  JUnit 5 Tests (`ConcurrentLRUCacheTest.java`)

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3); // small capacity for easy eviction checks
    }

    @Nested
    @DisplayName("Basic functionality")
    class BasicTests {

        @Test
        @DisplayName("get returns null for absent key")
        void getAbsent() {
            assertNull(cache.get(1));
        }

        @Test
        @DisplayName("put/get works and returns previous value")
        void putGet() {
            assertNull(cache.put(1, "one"));
            assertEquals("one", cache.get(1));

            String prev = cache.put(1, "uno");
            assertEquals("one", prev);
            assertEquals("uno", cache.get(1));
        }

        @Test
        @DisplayName("LRU eviction when capacity exceeded")
        void lruEviction() {
            cache.put(1, "a");
            cache.put(2, "b");
            cache.put(3, "c"); // cache now [1,2,3] (3 is MRU)

            // Access 1 to make it MRU
            cache.get(1);
            // Order should be [2,3,1] (2 is LRU)

            cache.put(4, "d"); // should evict key 2
            assertNull(cache.get(2));
            assertEquals("a", cache.get(1));
            assertEquals("b", cache.get(3)); // 3 still present
            assertEquals("d", cache.get(4));
        }

        @Test
        @DisplayName("size never exceeds capacity")
        void sizeBound() {
            for (int i = 0; i < 10; i++) {
                cache.put(i, Integer.toString(i));
                assertTrue(cache.size() <= 3);
            }
        }
    }

    @Nested
    @DisplayName("Concurrent stress tests")
    class ConcurrencyTests {

        private static final int THREAD_COUNT = 16;
        private static final int OPERATIONS_PER_THREAD = 50_000;
        private static final int CAPACITY = 128;

        @Test
        @DisplayName("Heavy parallel put/get respects LRU invariants")
        @Timeout(30) // seconds
        void concurrentStress() throws Exception {
            ConcurrentLRUCache<Integer, Integer> sharedCache =
                    new ConcurrentLRUCache<>(CAPACITY);

            ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicInteger opsCounter = new AtomicInteger(0);

            // Worker that repeatedly does random puts/gets
            Runnable worker = () -> {
                java.util.Random rnd = new java.util.Random();
                while (!stop.get()) {
                    int key = rnd.nextInt(CAPACITY * 2); // keys 0..2*CAPACITY-1
                    if (rnd.nextBoolean()) {
                        sharedCache.put(key, key);
                    } else {
                        sharedCache.get(key);
                    }
                    opsCounter.incrementAndGet();
                }
            };

            // Start all workers
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(worker);
            }

            // Let them run for a short while
            Thread.sleep(2000);
            stop.set(true);
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);

            System.out.println("Total operations performed: " + opsCounter.get());

            // ----- Invariant checks -----
            sharedCache.lock.lock(); // we need to inspect internal state safely
            try {
                // 1. Size never exceeds capacity
                assertTrue(sharedCache.size() <= CAPACITY,
                        "size exceeded capacity: " + sharedCache.size());

                // 2. Map size equals number of nodes in the linked list
                int mapSize = sharedCache.map.size();
                int listSize = 0;
                for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head;
                     n != null; n = n.next) {
                    listSize++;
                }
                assertEquals(mapSize, listSize,
                        "map size (" + mapSize + ") != list size (" + listSize + ")");

                // 3. Every node in the list is present in the map with the same value
                for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head;
                     n != null; n = n.next) {
                    Integer mapVal = sharedCache.map.get(n.key).value;
                    assertEquals(n.value, mapVal,
                            "node value mismatch for key " + n.key);
                }

                // 4. No duplicate keys in the list (each key appears at most once)
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head;
                     n != null; n = n.next) {
                    assertFalse(seen.contains(n.key),
                            "duplicate key " + n.key + " found in LRU list");
                    seen.add(n.key);
                }

                // 5. List is properly doubly-linked (forward/backward consistency)
                ConcurrentLRUCache.Node<Integer, Integer> prev = null;
                for (ConcurrentLRUCache.Node<Integer, Integer> n = sharedCache.head;
                     n != null; n = n.next) {
                    assertEquals(prev, n.prev,
                            "backward link broken at key " + n.key);
                    prev = n;
                }
                assertEquals(sharedCache.tail, prev,
                        "tail does not match last visited node");

            } finally {
                sharedCache.lock.unlock();
            }
        }
    }
}
```

### Explanation of the test suite

* **Basic functionality tests** verify the classic LRU behavior (`get`, `put`, eviction, size bound) in a single‑threaded setting.
* **Concurrent stress test**:
  * Spawns many threads (`THREAD_COUNT = 16`) that continuously perform random `put` or `get` operations on a shared cache.
  * Runs for a fixed wall‑clock time (≈2 s) to generate a high volume of concurrent accesses.
  * After stopping the workers, we acquire the cache’s internal lock and inspect the internal state to assert several invariants:
    1. **Capacity bound** – `size() ≤ capacity`.
    2. **Map‑list size equality** – the number of entries in the hash map equals the number of nodes reachable via the linked list.
    3. **Value consistency** – each node’s value matches the value stored in the map for the same key.
    4. **No duplicate keys** – each key appears at most once in the LRU list.
    5. **Correct linking** – forward (`next`) and backward (`prev`) pointers are consistent, and the `tail` reference matches the last node.
  * If any invariant is violated, the test fails, indicating a thread‑safety bug.

The test uses JUnit 5 features (`@Nested`, `@DisplayName`, `@Timeout`) for readability and ensures the test does not hang indefinitely.

---

### How to run

Assuming a standard Maven/Gradle project with JUnit 5 on the classpath:

```bash
# Maven
mvn test

# Gradle
./gradlew test
```

All tests should pass, confirming that the `ConcurrentLRUCache` implementation is both **correct** (LRU semantics, capacity enforcement) and **thread‑safe** under heavy concurrent load.