<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260925T153726Z -->
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
 * Uses a combination of HashMap and doubly-linked list protected by a ReadWriteLock.
 * ReadWriteLock allows concurrent reads (get) but exclusive writes (put/eviction).
 * Since get() updates access order (move-to-front), it requires a write lock.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

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

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity);
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves value and marks as most recently used.
     * Requires write lock because it mutates the linked list order.
     */
    public V get(K key) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Inserts or updates key-value pair. Evicts LRU entry if capacity exceeded.
     */
    public void put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Key and value cannot be null");
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                if (map.size() >= capacity) {
                    evictLRU();
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
            }
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

    public int capacity() {
        return capacity;
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

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        map.remove(lru.key);
    }
}
```

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutAndGet() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(1); // access 1 -> MRU
        cache.put(4, "four"); // evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void updateExistingKey() {
        cache.put(1, "one");
        cache.put(1, "uno");
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    @Test
    void nullKeyOrValueThrows() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 50;
        final int opsPerThread = 2000;
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger putCount = new AtomicInteger();
        AtomicInteger getCount = new AtomicInteger();

        // Pre-populate with some data
        IntStream.range(0, capacity / 2).forEach(i -> stressCache.put(i, i));

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = random.nextInt(capacity * 2);
                        if (random.nextBoolean()) {
                            stressCache.put(key, key * 2);
                            putCount.incrementAndGet();
                        } else {
                            stressCache.get(key);
                            getCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        executor.shutdown();

        // Invariants
        assertTrue(stressCache.size() <= capacity, "Size exceeds capacity: " + stressCache.size());
        // Verify all entries are reachable and consistent
        // (We can't easily inspect internal structure, but we can verify no exceptions and size bound)
        System.out.printf("Stress test: puts=%d, gets=%d, final size=%d%n",
                putCount.get(), getCount.get(), stressCache.size());
    }

    @Test
    void concurrentReadWriteConsistency() throws InterruptedException {
        final int capacity = 100;
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity);
        int numThreads = 20;
        int iterations = 5000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        // Initialize
        for (int i = 0; i < capacity; i++) {
            cache.put(i, "val" + i);
        }

        for (int t = 0; t < numThreads; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                try {
                    Random r = new Random(threadNum);
                    for (int i = 0; i < iterations; i++) {
                        int key = r.nextInt(capacity * 2);
                        if (r.nextBoolean()) {
                            cache.put(key, "thread" + threadNum + "-" + key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(0, errors.get(), "Exceptions occurred during concurrent access");
        assertTrue(cache.size() <= capacity);
    }

    @Test
    void highContentionOnSameKeys() throws InterruptedException {
        final int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int numThreads = 30;
        int iterations = 10000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();

        // Only 5 distinct keys, high contention
        for (int i = 0; i < 5; i++) cache.put(i, i);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    Random r = new Random();
                    for (int i = 0; i < iterations; i++) {
                        int key = r.nextInt(5);
                        if (r.nextBoolean()) {
                            cache.put(key, key + 100);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(0, errors.get());
        assertTrue(cache.size() <= capacity);
    }
}
```

### Thread-Safety Guarantees

1. **Single Lock for Mutations**: All operations that mutate the linked list (`put`, `get` which moves nodes, eviction) are guarded by the **write lock** of a `ReentrantReadWriteLock`. This ensures:
   - Atomicity of composite operations (map update + list rearrangement)
   - No race conditions during node relocation or eviction
   - Visibility of changes across threads (lock provides happens-before)

2. **Read Lock for Size**: `size()` uses the read lock, allowing concurrent size checks while blocking only during writes.

3. **No Null Keys/Values**: Explicit checks prevent `NullPointerException` in internal logic.

4. **Consistent Eviction**: `evictLRU()` atomically removes from both list and map under the same write lock.

5. **Lock Ordering**: Only one lock type (write) is used for all mutations, eliminating deadlock risk.

### Concurrency Stress Test Design

The JUnit 5 tests verify:
- **Functional correctness**: Basic LRU behavior, eviction order, updates
- **Invariant preservation under load**:
  - `size() <= capacity` always holds
  - No exceptions during concurrent access
  - All operations complete without deadlock
- **Realistic contention scenarios**:
  - Mixed read/write workloads
  - High contention on small key sets
  - Pre-populated cache with concurrent modifications
- **Metrics collection**: Tracks operation counts for visibility

Run with: `./mvnw test` (requires JUnit 5, Maven Surefire 3.0+ for parallel execution)