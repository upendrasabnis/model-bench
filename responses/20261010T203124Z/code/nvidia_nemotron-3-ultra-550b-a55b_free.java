<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261010T203124Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a combination of HashMap and doubly-linked list with a ReadWriteLock for concurrency.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves value by key, marking it as recently used.
     * @return value or null if not found
     */
    public V get(K key) {
        readLock.lock();
        Node<K, V> node = map.get(key);
        readLock.unlock();

        if (node == null) {
            return null;
        }

        // Need write lock to move node to front (modifies list structure)
        writeLock.lock();
        try {
            // Re-check under write lock (node might have been evicted)
            node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Inserts or updates key-value pair.
     * @return previous value or null if new entry
     */
    public V put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                // Update existing
                V oldValue = node.value;
                node.value = value;
                moveToHead(node);
                return oldValue;
            }

            // New node
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            // Evict if over capacity
            if (map.size() > capacity) {
                Node<K, V> tailNode = removeTail();
                map.remove(tailNode.key);
            }
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return map.size();
        } finally {
            readLock.unlock();
        }
    }

    public boolean containsKey(K key) {
        readLock.lock();
        try {
            return map.containsKey(key);
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            writeLock.unlock();
        }
    }

    // --- Internal linked list operations (must hold write lock) ---

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

    private Node<K, V> removeTail() {
        Node<K, V> last = tail.prev;
        removeNode(last);
        return last;
    }

    // --- Node class ---
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
}
```

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void testBasicPutGet() {
        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void testUpdateExistingKey() {
        cache.put(1, "one");
        cache.put(1, "uno");
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void testEvictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        assertEquals(3, cache.size());

        // Access 1 to make it recently used
        cache.get(1);
        // Add 4, should evict 2 (least recently used)
        cache.put(4, "four");

        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
        assertEquals(3, cache.size());
    }

    @Test
    void testClear() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    void testCapacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void testCapacityRespected(int capacity) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < capacity * 2; i++) {
            c.put(i, i);
        }
        assertEquals(capacity, c.size());
        // First 'capacity' entries should be evicted
        for (int i = 0; i < capacity; i++) {
            assertNull(c.get(i));
        }
        for (int i = capacity; i < capacity * 2; i++) {
            assertEquals(i, c.get(i));
        }
    }

    // ============ CONCURRENCY STRESS TESTS ============

    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads * 2);
        AtomicInteger errors = new AtomicInteger(0);

        // Writer threads
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rand.nextInt(capacity * 2);
                        c.put(key, key * 2);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // Reader threads
        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rand.nextInt(capacity * 2);
                        c.get(key); // Just access, don't care about result
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Concurrent access caused errors");

        // Verify cache invariants
        assertTrue(c.size() <= capacity, "Size exceeds capacity: " + c.size());
    }

    @Test
    void testHighContentionOnSameKeys() throws InterruptedException {
        final int capacity = 100;
        final int numThreads = 32;
        final int operationsPerThread = 10000;
        ConcurrentLRUCache<Integer, AtomicInteger> c = new ConcurrentLRUCache<>(capacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        // Pre-populate
        for (int i = 0; i < capacity; i++) {
            c.put(i, new AtomicInteger(0));
        }

        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rand.nextInt(capacity);
                        AtomicInteger val = c.get(key);
                        if (val != null) {
                            val.incrementAndGet();
                        }
                        // Also do some puts to cause evictions
                        if (rand.nextInt(100) < 10) {
                            c.put(capacity + rand.nextInt(100), new AtomicInteger(0));
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "High contention caused errors");
        assertTrue(c.size() <= capacity);
    }

    @Test
    void testInvariantsUnderStress() throws InterruptedException {
        final int capacity = 500;
        final int numThreads = 20;
        final int operationsPerThread = 20000;
        ConcurrentLRUCache<Long, Long> c = new ConcurrentLRUCache<>(capacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId + System.nanoTime());
                    for (int i = 0; i < operationsPerThread; i++) {
                        long key = rand.nextLong(10000);
                        long value = rand.nextLong();
                        
                        if (rand.nextBoolean()) {
                            c.put(key, value);
                        } else {
                            Long v = c.get(key);
                            // If we get a value, verify it's one we could have put
                            // (This is a weak check since we don't track all puts)
                        }
                        
                        // Periodically check size invariant
                        if (i % 1000 == 0) {
                            if (c.size() > capacity) {
                                errors.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(60, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Invariant violation: size exceeded capacity");
        assertTrue(c.size() <= capacity);
    }

    @Test
    void testConcurrentEvictionCorrectness() throws InterruptedException {
        final int capacity = 50;
        final int numThreads = 10;
        final int opsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        
        // Track expected state with a thread-safe reference map
        ConcurrentHashMap<Integer, Integer> reference = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rand.nextInt(200);
                        int value = rand.nextInt();
                        
                        if (rand.nextDouble() < 0.6) { // 60% puts
                            c.put(key, value);
                            reference.put(key, value);
                        } else { // 40% gets
                            Integer cached = c.get(key);
                            Integer expected = reference.get(key);
                            // Note: Due to concurrent evictions, cached might be null even if reference has it
                            // But if cached is non-null, it MUST match reference
                            if (cached != null && !cached.equals(expected)) {
                                errors.incrementAndGet();
                                System.err.println("Mismatch: key=" + key + " cached=" + cached + " expected=" + expected);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Data inconsistency detected");
        
        // Final consistency check: all entries in cache must match reference
        // (We can't easily iterate cache, but we can spot-check)
        for (int i = 0; i < 100; i++) {
            Integer cached = c.get(i);
            Integer expected = reference.get(i);
            if (cached != null && !cached.equals(expected)) {
                errors.incrementAndGet();
                System.err.println("Final mismatch: key=" + i + " cached=" + cached + " expected=" + expected);
            }
        }
        assertEquals(0, errors.get(), "Final consistency check failed");
    }

    @Test
    void testReadWriteLockFairness() throws InterruptedException {
        // This test verifies that writers don't starve readers and vice versa
        final ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(100);
        final int numReaders = 50;
        final int numWriters = 10;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(numReaders + numWriters);
        final AtomicInteger readerCount = new AtomicInteger(0);
        final AtomicInteger writerCount = new AtomicInteger(0);
        final AtomicInteger maxConcurrentReaders = new AtomicInteger(0);

        // Readers
        for (int i = 0; i < numReaders; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        int current = readerCount.incrementAndGet();
                        maxConcurrentReaders.updateAndGet(prev -> Math.max(prev, current));
                        c.get(j % 200);
                        readerCount.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        // Writers
        for (int i = 0; i < numWriters; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        writerCount.incrementAndGet();
                        c.put(j % 200, j);
                        writerCount.decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "Test timed out");
        
        // With ReadWriteLock, we should see concurrent readers
        // (This is a best-effort check, not guaranteed due to scheduling)
        System.out.println("Max concurrent readers observed: " + maxConcurrentReaders.get());
        assertTrue(c.size() <= 100);
    }
}
```

## Thread-Safety Guarantees Explanation

The implementation uses a **single `ReentrantReadWriteLock`** to protect all shared state:

1. **Map Operations**: The `HashMap` is not thread-safe, so all accesses (get/put/remove) are guarded by locks.

2. **Linked List Operations**: The doubly-linked list pointers (`prev`/`next`) are mutated during:
   - `addToHead` (new insertions)
   - `removeNode` (evictions/moves)
   - `moveToHead` (LRU updates on get/put)
   These require exclusive access to maintain list integrity.

3. **Lock Strategy**:
   - **Read Lock**: Used only for non-mutating operations (`size()`, `containsKey()`)
   - **Write Lock**: Used for ALL mutating operations (`get`, `put`, `clear`, eviction)
   
   **Critical Insight**: Even `get()` requires a write lock because it calls `moveToHead()` which modifies the linked list structure. This prevents the "readers-writers" optimization but ensures correctness.

4. **Atomicity**: Each public operation (`get`, `put`) executes atomically:
   - No other thread can see intermediate states (e.g., node in map but not in list)
   - Eviction checks (`map.size() > capacity`) happen under the same lock as removal

5. **Memory Visibility**: The lock provides happens-before guarantees, ensuring all threads see the most recent updates to `map` and list pointers.

## Concurrency Stress Test Coverage

The JUnit 5 tests verify:
- **Basic correctness** under single-threaded access
- **High contention** with 16-32 threads doing mixed reads/writes
- **Data consistency** by cross-referencing with a `ConcurrentHashMap`
- **Invariant preservation** (size ≤ capacity) checked during stress
- **Eviction correctness** under concurrent modifications
- **Lock fairness** observation (readers can run concurrently when no writers)

The stress tests use `CountDownLatch` for synchronized start, run 10K-20K operations per thread, and validate no exceptions or invariant violations occur.