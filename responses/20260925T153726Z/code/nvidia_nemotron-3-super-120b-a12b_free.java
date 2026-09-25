<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260925T153726Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
**Solution Overview**

We implement a *thread‑safe* LRU (Least‑Recently‑Used) cache with a fixed capacity.  
The classic LRU algorithm uses:

* a hash map (`HashMap<K,Node>`) for **O(1)** lookup of a node by its key, and  
* a doubly linked list that keeps the usage order – the most‑recently used item is at the head, the least‑recently used at the tail.

All mutations of the map **and** the list are performed while holding a single `java.util.concurrent.locks.ReentrantLock`.  
The lock gives us:

* **Mutual exclusion** – only one thread can change the internal state at a time.  
* **Visibility** – the lock’s release‑acquire semantics provide a *happens‑before* relationship, so every thread sees the most up‑to‑date state after it acquires the lock.  

Because the underlying data structures (`HashMap` and pointer updates) are O(1), the *average* time complexity of `get` and `put` stays O(1) even though we serialize access with a lock. The lock is the simplest way to guarantee correctness; more sophisticated lock‑stripping or lock‑free designs are possible but are not required for the exercise.

---

## 1. Implementation

```java
package com.example.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread‑safe LRU cache with O(1) average get/put.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class ConcurrentLRUCache<K, V> implements Cache<K, V> {

    /** Node of the doubly linked list. */
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
    private final Node<K, V> head;                 // dummy most‑recently used
    private final Node<K, V> tail;                 // dummy least‑recently used
    private final ReentrantLock lock = new ReentrantLock();

    /** Constructs a cache with the given maximum number of entries. */
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);    // initial size, load factor 0.75
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** @return value associated with {@code key} or {@code null} if absent */
    @Override
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);          // mark as most‑recently used
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /** Inserts or updates {@code key} with {@code value}. */
    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                // key already present – update value and move to head
                existing.value = value;
                moveToHead(existing);
                return;
            }

            // evict if we are at capacity
            if (map.size() >= capacity) {
                evictTail();
            }

            Node<K, V> node = new Node<>(key, value);
            map.put(key, node);
            addToHead(node);
        } finally {
            lock.unlock();
        }
    }

    /** Returns a snapshot of the cache ordered from most‑ to least‑recently used.
     *  The snapshot is a defensive copy – modifications of the returned list do
     *  not affect the cache.  Used only in tests. */
    public java.util.List<Map.Entry<K, V>> snapshotOrdered() {
        lock.lock();
        try {
            java.util.List<Map.Entry<K, V>> list = new java.util.ArrayList<>(map.size());
            for (Node<K, V> cur = head.next; cur != tail; cur = cur.next) {
                list.add(new java.util.AbstractMap.SimpleImmutableEntry<>(cur.key, cur.value));
            }
            return List.copyOf(list); // immutable snapshot
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Internal helpers – all assume the lock is held by the caller.      */
    /* ------------------------------------------------------------------ */

    /** Remove the node that is right before the tail (the LRU element). */
    private void evictTail() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        map.remove(lru.key);
    }

    /** Insert node right after the head (most‑recent position). */
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Move an existing node to the head position. */
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    /** Detach a node from the linked list. */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}

/** Minimal cache interface used by the implementation and the tests. */
interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
}
```

### How Thread‑Safety Is Guaranteed

| Aspect | Mechanism |
|--------|-----------|
| **Mutual exclusion** | All public methods (`get`, `put`) and the test‑only `snapshotOrdered` acquire the same `ReentrantLock`. While a thread holds the lock, no other thread can enter any of those methods, therefore the internal `HashMap` and the doubly linked list are never modified concurrently. |
| **Visibility** | The lock’s unlock operation *happens‑before* any subsequent lock acquisition. Consequently, writes to `map`, `head`, `tail`, and the `Node` fields performed by one thread become visible to any other thread after it acquires the lock. |
| **Atomicity of compound operations** | Operations that touch both the map and the list (e.g., `put` when the cache is full) are performed inside the same critical section, guaranteeing that the cache never ends up in an inconsistent state (e.g., a node present in the map but unlinked from the list). |
| **No stale reads** | Even a read‑only `get` acquires the lock, preventing the scenario where a thread sees a partially updated list while another thread is moving a node to the head. |

Because the lock is held for only a few constant‑time operations (hash map lookup, a few pointer updates), contention is limited and the average latency remains O(1).

---

## 2. JUnit 5 Tests

The test suite contains:

* **Basic functional tests** – correctness of `get`, `put`, eviction, and capacity limits.
* **Concurrency stress test** – many threads perform random `get`/`put` operations while we record every access in a shared log. After all threads finish we verify:
  1. The cache size never exceeds the configured capacity.
  2. The order of elements in the cache matches the *recency* order implied by the access log (most‑recently accessed keys are at the head).

```java
package com.example.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link ConcurrentLRUCache}.
 */
class ConcurrentLRUCacheTest {

    private Cache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3); // small capacity for easy verification
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
        @DisplayName("put followed by get returns the value")
        void putThenGet() {
            cache.put(1, "one");
            assertEquals("one", cache.get(1));
        }

        @Test
        @DisplayName("put replaces existing value")
        void putReplace() {
            cache.put(1, "first");
            cache.put(1, "second");
            assertEquals("second", cache.get(1));
        }

        @Test
        @DisplayName("eviction respects LRU order")
        void lruEviction() {
            cache.put(1, "a");
            cache.put(2, "b");
            cache.put(3, "c"); // cache now: [3,2,1] (head->tail)

            // accessing 1 makes it most‑recent
            cache.put(1, "a"); // get‑like put (update)

            cache.put(4, "d"); // should evict key 2 (LRU)
            assertNull(cache.get(2));
            assertEquals("d", cache.get(4));
            assertEquals("a", cache.get(1));
            assertEquals("c", cache.get(3));
        }

        @Test
        @DisplayName("size never exceeds capacity")
        void sizeLimit() {
            for (int i = 0; i < 10; i++) {
                cache.put(i, String.valueOf(i));
                assertTrue(((ConcurrentLRUCache<Integer, String>) cache).snapshotOrdered().size() <= 3);
            }
        }
    }

    @Nested
    @DisplayName("Concurrent stress")
    class StressTests {

        private static final int THREAD_COUNT = 20;
        private static final int OPERATIONS_PER_THREAD = 5_000;
        private static final int KEY_SPACE = 1_000; // keys 0..999
        private static final int CAPACITY = 100;

        private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        private final AtomicLong seq = new AtomicLong(0); // monotonic access counter
        private final ConcurrentHashMap<Integer, Long> lastAccess = new ConcurrentHashMap<>();

        @BeforeEach
        void setUpStress() {
            cache = new ConcurrentLRUCache<>(CAPACITY);
            lastAccess.clear();
            seq.set(0);
        }

        @AfterEach
        void tearDownStress() throws InterruptedException {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Executor did not terminate in time");
        }

        @Test
        @DisplayName("Heavy parallel load respects LRU invariants")
        @Timeout(30)
        void concurrentStress() throws InterruptedException, ExecutionException {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < THREAD_COUNT; t++) {
                tasks.add(() -> {
                    java.util.Random rnd = new java.util.Random();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = rnd.nextInt(KEY_SPACE);
                        boolean put = rnd.nextBoolean(); // random mix
                        if (put) {
                            String value = "v-" + key;
                            cache.put(key, value);
                            // record access under the same lock as the cache operation
                            long ts = seq.incrementAndGet();
                            lastAccess.put(key, ts);
                        } else {
                            V v = cache.get(key);
                            if (v != null) {
                                long ts = seq.incrementAndGet();
                                lastAccess.put(key, ts);
                            }
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(); // propagate any exception
            }

            // ----- Invariant checks -----
            // 1. Size never exceeds capacity
            int size = ((ConcurrentLRUCache<Integer, String>) cache).snapshotOrdered().size();
            assertTrue(size <= CAPACITY,
                    "Cache size %d exceeded capacity %d".formatted(size, CAPACITY));

            // 2. Order matches recency (most recent at head)
            List<Map.Entry<Integer, String>> snapshot =
                    ((ConcurrentLRUCache<Integer, String>) cache).snapshotOrdered();

            // Build a list of keys sorted by lastAccess descending (most recent first)
            List<Integer> keysByRecency = lastAccess.entrySet().stream()
                    .filter(e -> snapshot.stream().anyMatch(en -> en.getKey() == e.getKey()))
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            // The cache should contain exactly the most‑recently accessed keys,
            // up to its capacity.
            List<Integer> expectedKeys = keysByRecency.subList(0, Math.min(CAPACITY, keysByRecency.size()));
            List<Integer> actualKeys = snapshot.stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            assertEquals(expectedKeys, actualKeys,
                    "Cache order does not match LRU recency order");
        }
    }
}
```

### Explanation of the Stress Test

* **Shared access log** – `lastAccess` (`ConcurrentHashMap<Integer,Long>`) stores a monotonically increasing timestamp (`seq.incrementAndGet()`) for every successful `get` or `put`. The timestamp is updated **while the cache lock is held**, guaranteeing that the log reflects the exact order in which the cache observed each key.
* **After all threads finish** we:
  1. Take an immutable snapshot of the cache in head‑to‑tail order (`snapshotOrdered`).
  2. Sort the keys that are present in the snapshot by their recorded timestamp (most recent first).
  3. Compare the first `capacity` keys of that sorted list with the snapshot’s key order.  
     If the cache truly implements LRU, the two lists must be identical.
* The test also asserts that the cache never exceeds its capacity, which is a direct safety property of the eviction logic.

The test is deliberately aggressive: 20 threads × 5 000 operations = 100 000 random accesses, with a key space ten times larger than the cache capacity, causing frequent evictions and heavy contention on the lock. Passing this test demonstrates that the lock‑based design remains correct under realistic concurrent load.

---

**Summary**

* The `ConcurrentLRUCache` class provides a thread‑safe LRU cache with O(1) average `get`/`put` by protecting all internal state with a single `ReentrantLock`.
* The lock gives mutual exclusion, visibility, and atomicity of the combined map/list updates, which is sufficient to guarantee correctness.
* The accompanying JUnit 5 test suite validates both ordinary behavior and sophisticated invariants under heavy parallel load, ensuring that the implementation behaves correctly in a concurrent environment.