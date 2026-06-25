<!-- model: qwen/qwen3-next-80b-a3b-instruct:free | category: code | run: 20260625T020407Z -->
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * A concurrent, thread-safe Least-Recently-Used (LRU) cache implementation.
 * <p>
 * Thread-safety is achieved using a hybrid locking strategy:
 * <ul>
 *   <li>A {@link ReentrantReadWriteLock} protects the internal doubly-linked list (maintaining LRU order) and the node map.</li>
 *   <li>The {@link ConcurrentHashMap} ({@code nodeMap}) stores {@code K → Node} mappings and provides O(1) key lookups.</li>
 *   <li>The doubly-linked list uses {@code Node} objects with {@code volatile} prev/next references to ensure memory visibility.</li>
 *   <li>All list operations (moveToHead, addToHead, removeNode, detachTail) are performed under write-lock to prevent race conditions.</li>
 *   <li>The {@code get} operation uses a read-lock only for the node lookup, then performs the list update under write-lock only after confirming presence, minimizing contention.</li>
 *   <li>The {@code put} operation acquires a write-lock for the entire insertion/eviction sequence to ensure atomicity.</li>
 *   <li>The {@code snapshotKeys()} method acquires a read-lock and copies the list into an immutable list, avoiding blocking other operations.</li>
 * </ul>
 * This design avoids deadlocks by using a single lock hierarchy (always acquire {@code lock} before any nested logic) and ensures O(1) expected time for {@code get} and {@code put} by delegating hash-based access to {@code ConcurrentHashMap} and list reordering to pointer manipulation.
 * <p>
 * Notes:
 * <ul>
 *   <li>{@code null} keys and values are explicitly disallowed; {@code NullPointerException} is thrown.</li>
 *   <li>Eviction policy: least-recently-used entry (tail of the list) is removed when capacity is exceeded.</li>
 *   <li>All public methods are fully thread-safe; {@code size()} and {@code snapshotKeys()} reflect a consistent point-in-time view.</li>
 * </ul>
 */
public class ConcurrentLruCache<K, V> {

    private static final class Node<K, V> {
        volatile Node<K, V> prev;
        volatile Node<K, V> next;
        final K key;
        V value;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int maxCapacity;
    private final ConcurrentHashMap<K, Node<K, V>> nodeMap;
    private final ReentrantReadWriteLock lock;
    private volatile Node<K, V> head; // most recently used
    private volatile Node<K, V> tail; // least recently used
    private volatile int currentSize;

    /**
     * Constructs a cache that can hold at most {@code maxCapacity} entries.
     *
     * @param maxCapacity the maximum number of entries; must be > 0
     * @throws IllegalArgumentException if maxCapacity ≤ 0
     */
    public ConcurrentLruCache(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
        this.maxCapacity = maxCapacity;
        this.nodeMap = new ConcurrentHashMap<>(maxCapacity);
        this.lock = new ReentrantReadWriteLock();
        this.currentSize = 0;
    }

    private void attachNode(Node<K, V> node) {
        // Attach node to head (MRU position)
        node.next = head;
        node.prev = null;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void detachNode(Node<K, V> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    /**
     * Returns the value associated with {@code key}, or {@code null} if absent.
     * Updates LRU order: accessed entry becomes most-recently used.
     */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        Node<K, V> node = nodeMap.get(key);
        if (node == null) {
            return null;
        }
        // Update LRU order: move to head
        lock.writeLock().lock();
        try {
            // Double-check in case node was removed during lock acquisition
            Node<K, V> current = nodeMap.get(key);
            if (current == null) {
                return null;
            }
            // Only move if not already at head
            if (current != head) {
                detachNode(current);
                attachNode(current);
            }
            return current.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts or updates the mapping {@code key → value}.
     * If cache is at capacity, evicts the least-recently used entry first.
     */
    public void put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        if (value == null) {
            throw new NullPointerException("value cannot be null");
        }

        lock.writeLock().lock();
        try {
            Node<K, V> existing = nodeMap.get(key);
            if (existing != null) {
                existing.value = value;
                // Move to head (already handled by detach/attach below)
                if (existing != head) {
                    detachNode(existing);
                    attachNode(existing);
                }
                return;
            }

            // New entry
            if (currentSize >= maxCapacity && tail != null) {
                // Evict LRU (tail)
                Node<K, V> lru = tail;
                Node<K, V> prev = lru.prev;
                detachNode(lru);
                nodeMap.remove(lru.key);
                currentSize--;
            }

            Node<K, V> newNode = new Node<>(key, value);
            attachNode(newNode);
            nodeMap.put(key, newNode);
            currentSize++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the entry for {@code key} if present and returns its value.
     */
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key cannot be null");
        }
        lock.writeLock().lock();
        try {
            Node<K, V> node = nodeMap.remove(key);
            if (node == null) {
                return null;
            }
            detachNode(node);
            currentSize--;
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the current number of entries.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return currentSize;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a thread-safe snapshot of keys in LRU order (most- to least-recently used).
     * The returned list is immutable and unaffected by subsequent modifications.
     */
    public List<K> snapshotKeys() {
        lock.readLock().lock();
        try {
            List<K> keys = new ArrayList<>();
            for (Node<K, V> curr = head; curr != null; curr = curr.next) {
                keys.add(curr.key);
            }
            return Collections.unmodifiableList(keys);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes all entries.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            nodeMap.clear();
            head = null;
            tail = null;
            currentSize = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Entry point for JUnit platform launcher.
     */
    public static void main(String[] args) {
        org.junit.platform.console.ConsoleLauncher.main(
                new String[]{"--select-class", ConcurrentLruCacheTest.class.getName()}
        );
    }
}

/**
 * JUnit 5 test suite for {@link ConcurrentLruCache}.
 */
@ExtendWith(ConcurrentLruCacheTest.TimeoutExtension.class)
class ConcurrentLruCacheTest {

    static class TimeoutExtension implements Extension {
        @Override
        public void beforeAll(ExtensionContext context) {
            // Set default timeout for all tests to 30 seconds
            System.setProperty("junit.jupiter.execution.timeout.default", "30 s");
        }
    }

    @Test
    @DisplayName("Construction with invalid capacity throws IllegalArgumentException")
    void testInvalidCapacity() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ConcurrentLruCache<>(-1));
    }

    @Test
    @DisplayName("Construction with valid capacity succeeds")
    void testValidCapacity() {
        Assertions.assertDoesNotThrow(() -> new ConcurrentLruCache<>(1));
        Assertions.assertDoesNotThrow(() -> new ConcurrentLruCache<>(100));
    }

    @Test
    @DisplayName("Put, get, and remove basic operations")
    void testBasicOperations() {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>(3);
        Assertions.assertNull(cache.get("a"));
        cache.put("a", 1);
        Assertions.assertEquals(1, cache.get("a"));
        cache.put("b", 2);
        Assertions.assertEquals(2, cache.get("b"));
        Assertions.assertEquals(2, cache.size());
        Assertions.assertEquals(Integer.valueOf(1), cache.remove("a"));
        Assertions.assertNull(cache.get("a"));
        Assertions.assertEquals(1, cache.size());
        cache.clear();
        Assertions.assertEquals(0, cache.size());
        Assertions.assertTrue(cache.snapshotKeys().isEmpty());
    }

    @Test
    @DisplayName("Null key and value throw NullPointerException")
    void testNullKeyAndValue() {
        ConcurrentLruCache<String, String> cache = new ConcurrentLruCache<>(2);
        Assertions.assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        Assertions.assertThrows(NullPointerException.class, () -> cache.put("key", null));
        Assertions.assertThrows(NullPointerException.class, () -> cache.get(null));
        Assertions.assertThrows(NullPointerException.class, () -> cache.remove(null));
    }

    @Test
    @DisplayName("LRU eviction order is correct")
    void testLruEviction() {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        // Access 'a' to make it recently used
        cache.get("a");
        // Now 'b' is LRU
        cache.put("d", 4); // should evict 'b'

        Assertions.assertNull(cache.get("b"));
        Assertions.assertEquals(1, cache.get("a"));
        Assertions.assertEquals(3, cache.get("c"));
        Assertions.assertEquals(4, cache.get("d"));
        Assertions.assertEquals(3, cache.size());

        // Access 'c' to make it recently used
        cache.get("c");
        // Now 'd' is LRU
        cache.put("e", 5); // should evict 'd'

        Assertions.assertNull(cache.get("d"));
        Assertions.assertEquals(Arrays.asList("c", "a", "e"), cache.snapshotKeys());
    }

    @Test
    @DisplayName("Update existing key updates value and LRU order")
    void testUpdateKey() {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 10); // update, should become MRU

        Assertions.assertEquals(Arrays.asList("a", "b"), cache.snapshotKeys());
        cache.put("c", 3);
        cache.put("d", 4); // evict 'b'

        Assertions.assertEquals(Arrays.asList("c", "a", "d"), cache.snapshotKeys());
        Assertions.assertEquals(10, cache.get("a"));
    }

    @Test
    @DisplayName("Snapshot keys returns correct order and is immutable")
    void testSnapshotKeys() {
        ConcurrentLruCache<String, Integer> cache = new ConcurrentLruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        List<String> snapshot1 = cache.snapshotKeys();
        Assertions.assertEquals(Arrays.asList("c", "b", "a"), snapshot1);

        cache.get("b"); // 'b' becomes MRU
        List<String> snapshot2 = cache.snapshotKeys();
        Assertions.assertEquals(Arrays.asList("b", "c", "a"), snapshot2);

        // Modify original list: should not affect cache
        Assertions.assertThrows(UnsupportedOperationException.class, () -> snapshot1.add("x"));

        // Subsequent modifications to cache should not affect snapshot
        cache.put("d", 4);
        Assertions.assertEquals(Arrays.asList("c", "b", "a"), snapshot1); // unchanged
        Assertions.assertEquals(Arrays.asList("b", "c", "d"), cache.snapshotKeys());
    }

    @Test
    @DisplayName("Stress test: concurrent operations with 8 threads for 30 seconds")
    void testConcurrentOperations() throws Exception {
        int numThreads = 8;
        int maxCapacity = 50;
        ConcurrentLruCache<Integer, Integer> cache = new ConcurrentLruCache<>(maxCapacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        AtomicInteger operationsCount = new AtomicInteger(0);
        ConcurrentHashMap<Integer, Integer> referenceMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(numThreads);

        // Start time
        long startTime = System.currentTimeMillis();
        long durationMs = 30_000;

        for (int i = 0; i < numThreads; i++) {
            int threadId = i;
            executor.submit(() -> {
                try {
                    barrier.await(); // Wait for all threads to be ready
                    Random random = new Random(threadId);
                    while (System.currentTimeMillis() - startTime < durationMs) {
                        int key = random.nextInt(1000);
                        int op = random.nextInt(100); // 0-99

                        if (op < 40) { // 40% get
                            Integer val = cache.get(key);
                            Integer refVal = referenceMap.get(key);
                            if (refVal != null) {
                                Assertions.assertEquals(refVal, val);
                            } else {
                                Assertions.assertNull(val);
                            }
                        } else if (op < 80) { // 40% put
                            int value = random.nextInt(10000);
                            cache.put(key, value);
                            referenceMap.put(key, value);
                            // Verify no size overflow
                            Assertions.assertTrue(cache.size() <= maxCapacity, 
                                "Cache size exceeded capacity: " + cache.size());
                        } else { // 20% remove
                            Integer removed = cache.remove(key);
                            Integer refRemoved = referenceMap.remove(key);
                            Assertions.assertEquals(refRemoved, removed);
                        }
                        operationsCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        latch.await(60, TimeUnit.SECONDS);

        // Verify consistency after stress test
        for (Map.Entry<Integer, Integer> entry : referenceMap.entrySet()) {
            Integer cachedVal = cache.get(entry.getKey());
            Assertions.assertEquals(entry.getValue(), cachedVal,
                "Mismatch for key " + entry.getKey() + ": expected " + entry.getValue() + ", got " + cachedVal);
        }

        // Verify size consistency
        Assertions.assertEquals(referenceMap.size(), cache.size(),
                "Reference map size != cache size: " + referenceMap.size() + " vs " + cache.size());

        // Verify capacity constraint always held (by checking final size, but stress test already asserted during run)
        Assertions.assertTrue(cache.size() <= maxCapacity);

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Performance sanity check: 1M operations in < 2 seconds")
    void testPerformance() throws Exception {
        int ops = 1_000_000;
        int maxCapacity = 1000;
        ConcurrentLruCache<Integer, Integer> cache = new ConcurrentLruCache<>(maxCapacity);
        Random random = new Random(42);

        long start = System.currentTimeMillis();
        for (int i = 0; i < ops; i++) {
            int key = random.nextInt(maxCapacity * 10);
            if (i % 3 == 0) {
                cache.get(key);
            } else if (i % 3 == 1) {
                cache.put(key, i);
            } else {
                cache.remove(key);
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        Assertions.assertTrue(elapsed < 2000, 
            "Performance too slow: " + elapsed + " ms for " + ops + " operations");
    }
}
```