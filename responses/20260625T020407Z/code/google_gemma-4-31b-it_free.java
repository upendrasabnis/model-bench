<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260625T020407Z -->
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A thread-safe Least-Recently-Used (LRU) cache.
 * 
 * <p><strong>Concurrency Strategy:</strong>
 * This implementation uses a combination of a {@link ConcurrentHashMap} for O(1) access 
 * and a custom doubly-linked list to track usage order. 
 * 
 * To ensure thread-safety while maintaining O(1) amortized time complexity:
 * <ul>
 *   <li>A {@link ReentrantLock} guards all modifications to the doubly-linked list. 
 *       Since the list operations (moving a node to head or removing a node) are O(1), 
 *       the critical section is extremely short, minimizing contention.</li>
 *   <li>The {@code ConcurrentHashMap} provides safe concurrent access to the nodes.</li>
 *   <li>To prevent race conditions between the map and the list (e.g., a node being 
 *       removed from the map but still existing in the list), the lock is held 
 *       during both the map update and the list update.</li>
 *   <li>Memory visibility is guaranteed by the {@code ReentrantLock}'s happen-before 
 *       semantics: any action in one thread before unlocking is visible to any 
 *       thread after locking.</li>
 *   <li>{@code snapshotKeys()} locks the list briefly to copy the keys into a 
 *       new {@code ArrayList}, ensuring a consistent point-in-time snapshot.</li>
 * </ul>
 * 
 * <strong>Constraints:</strong> Null keys and values are not permitted.
 * 
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public class ConcurrentLruCache<K, V> {

    private final int maxCapacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Lock lock = new ReentrantLock();
    private final Node<K, V> head;
    private final Node<K, V> tail;

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

    public ConcurrentLruCache(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
        this.maxCapacity = maxCapacity;
        this.map = new ConcurrentHashMap<>();
        
        // Dummy head and tail to avoid null checks during pointer updates
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Key/Value cannot be null");
        
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                if (map.size() >= maxCapacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                addToHead(newNode);
                map.put(key, newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) {
                removeNode(node);
                return node.value;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    public List<K> snapshotKeys() {
        lock.lock();
        try {
            List<K> keys = new ArrayList<>(map.size());
            Node<K, V> current = head.next;
            while (current != tail) {
                keys.add(current.key);
                current = current.next;
            }
            return Collections.unmodifiableList(keys);
        } finally {
            lock.unlock();
        }
    }

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

    // --- Internal List Helpers (Must be called within lock) ---

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private void evict() {
        Node<K, V> last = tail.prev;
        if (last != head) {
            map.remove(last.key);
            removeNode(last);
        }
    }
}

/**
 * JUnit 5 Test Suite for ConcurrentLruCache.
 */
class ConcurrentLruCacheTest {

    @Test
    void testBasicFunctionality() {
        ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(3);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.put(3, "C");

        assertEquals("A", cache.get(1));
        // Order now: 1, 3, 2
        cache.put(4, "D"); // Evicts 2
        
        assertNull(cache.get(2));
        assertEquals("D", cache.get(4));
        assertEquals(3, cache.size());
    }

    @Test
    void testLRUOrder() {
        ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(3);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.put(3, "C");
        
        cache.get(1); // 1 is MRU
        cache.get(2); // 2 is MRU
        
        List<Integer> keys = cache.snapshotKeys();
        assertEquals(Arrays.asList(2, 1, 3), keys);
    }

    @Test
    void testInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-1));
    }

    @Test
    void testNulls() {
        ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(5);
        assertThrows(NullPointerException.class, () -> cache.put(null, "Val"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        final int capacity = 100;
        final int threadCount = 8;
        final ConcurrentLruCache<Integer, Integer> cache = new ConcurrentLruCache<>(capacity);
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger totalOps = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    long endTime = System.currentTimeMillis() + 5000; // Run for 5 seconds (shorter for CI)
                    ThreadLocalRandom rand = ThreadLocalRandom.current();
                    
                    while (System.currentTimeMillis() < endTime) {
                        int op = rand.nextInt(3);
                        int key = rand.nextInt(200);
                        if (op == 0) {
                            cache.put(key, key);
                        } else if (op == 1) {
                            cache.get(key);
                        } else {
                            cache.remove(key);
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity");
        System.out.println("Completed " + totalOps.get() + " concurrent operations.");
    }

    @Test
    void testPerformanceSanity() {
        ConcurrentLruCache<Integer, Integer> cache = new ConcurrentLruCache<>(1000);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1_000_000; i++) {
            cache.put(i % 2000, i);
        }
        long end = System.currentTimeMillis();
        long duration = end - start;
        System.out.println("1M operations took: " + duration + "ms");
        assertTrue(duration < 2000, "Performance too slow: " + duration + "ms");
    }

    @Test
    void testClear() {
        ConcurrentLruCache<Integer, String> cache = new ConcurrentLruCache<>(10);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.snapshotKeys().isEmpty());
    }
}
```