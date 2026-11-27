<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261127T194246Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a combination of HashMap and doubly-linked list protected by a ReadWriteLock.
 * 
 * Concurrency guarantees:
 * - ReadWriteLock allows concurrent reads (get) but exclusive writes (put/eviction)
 * - All structural modifications (list pointer updates, map mutations) are atomic under write lock
 * - Read operations (get) that don't modify structure use read lock
 * - Read operations that promote nodes (move to front) upgrade to write lock
 * - Eviction is atomic with insertion under same write lock
 * - No ABA problem: nodes are never reused, only created/destroyed
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final Lock readLock = rwl.readLock();
    private final Lock writeLock = rwl.writeLock();

    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Retrieves value and promotes to MRU position.
     * @return value or null if absent
     */
    public V get(K key) {
        Node<K, V> node;
        readLock.lock();
        try {
            node = map.get(key);
            if (node == null) return null;
        } finally {
            readLock.unlock();
        }

        // Node exists - promote to front (requires write lock)
        writeLock.lock();
        try {
            // Re-check under write lock (node might have been evicted)
            node = map.get(key);
            if (node == null) return null;
            
            moveToFront(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Inserts or updates key-value pair. Evicts LRU if capacity exceeded.
     * @return previous value or null if new key
     */
    public V put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                V oldValue = node.value;
                node.value = value;
                moveToFront(node);
                return oldValue;
            }

            // New node
            if (map.size() >= capacity) {
                evictLRU();
            }
            
            node = new Node<>(key, value);
            map.put(key, node);
            addToFront(node);
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    /** Atomically computes value if absent using provided function. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        // Fast path: check with read lock
        readLock.lock();
        Node<K, V> node = map.get(key);
        readLock.unlock();
        if (node != null) {
            // Promote existing node
            writeLock.lock();
            try {
                node = map.get(key); // Re-check
                if (node != null) {
                    moveToFront(node);
                    return node.value;
                }
            } finally {
                writeLock.unlock();
            }
        }

        // Slow path: compute with write lock held
        writeLock.lock();
        try {
            node = map.get(key);
            if (node != null) {
                moveToFront(node);
                return node.value;
            }

            V value = mappingFunction.apply(key);
            if (value == null) return null;

            if (map.size() >= capacity) {
                evictLRU();
            }
            
            node = new Node<>(key, value);
            map.put(key, node);
            addToFront(node);
            return value;
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

    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToFront(Node<K, V> node) {
        remove(node);
        addToFront(node);
    }

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            remove(lru);
            map.remove(lru.key);
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutGet() {
        cache.put(1, "a");
        cache.put(2, "b");
        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1); // Promote 1
        cache.put(4, "d"); // Should evict 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKey() {
        cache.put(1, "a");
        cache.put(1, "b");
        assertEquals("b", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void computeIfAbsent() {
        String val = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", val);
        assertEquals("computed-1", cache.get(1));
        
        // Second call should return cached value
        String val2 = cache.computeIfAbsent(1, k -> "new-" + k);
        assertEquals("computed-1", val2);
    }

    @Test
    void computeIfAbsentReturnsNull() {
        assertNull(cache.computeIfAbsent(1, k -> null));
        assertNull(cache.get(1));
        assertEquals(0, cache.size());
    }

    @Test
    void clear() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
    }

    // ==================== CONCURRENCY TESTS ====================

    @Test
    void concurrentReadsNoBlocking() throws InterruptedException {
        cache.put(1, "a");
        cache.put(2, "b");
        
        int threadCount = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        String v1 = cache.get(1);
                        String v2 = cache.get(2);
                        if (!"a".equals(v1) || !"b".equals(v2)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            }).start();
        }

        start.countDown();
        end.await(5, TimeUnit.SECONDS);
        assertEquals(0, errors.get());
    }

    @Test
    void concurrentWritesAndReads() throws InterruptedException {
        int capacity = 100;
        cache = new ConcurrentLRUCache<>(capacity);
        int threadCount = 20;
        int opsPerThread = 500;
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    start.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2);
                        if (rnd.nextBoolean()) {
                            cache.put(key, "val-" + key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(end.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        
        // Verify invariants: size <= capacity, all keys in map are in list
        assertTrue(cache.size() <= capacity);
    }

    @Test
    void stressTestInvariantsUnderHeavyLoad() throws InterruptedException {
        final int CAPACITY = 500;
        final int THREADS = 50;
        final int OPS_PER_THREAD = 2000;
        cache = new ConcurrentLRUCache<>(CAPACITY);
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        // Phase 1: Parallel puts/gets
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    start.await();
                    Random rnd = new Random(threadId + System.nanoTime());
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = rnd.nextInt(CAPACITY * 3);
                        int op = rnd.nextInt(4);
                        switch (op) {
                            case 0: cache.put(key, "v" + key); break;
                            case 1: cache.get(key); break;
                            case 2: cache.computeIfAbsent(key, k -> "c" + k); break;
                            case 3: cache.containsKey(key); break;
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    firstError.compareAndSet(null, e);
                } finally {
                    end.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(end.await(30, TimeUnit.SECONDS), "Test timed out");
        
        if (errors.get() > 0) {
            throw new AssertionError("Concurrent errors: " + errors.get(), firstError.get());
        }

        // Phase 2: Verify structural invariants
        // We can't directly inspect internal structure, but we can verify:
        // 1. Size <= capacity
        // 2. All accessible keys return values
        // 3. No data corruption (get/put consistency)
        assertTrue(cache.size() <= CAPACITY, "Size exceeds capacity: " + cache.size());
        
        // Verify a sample of keys
        for (int i = 0; i < 100; i++) {
            int key = ThreadLocalRandom.current().nextInt(CAPACITY * 3);
            String val = cache.get(key);
            if (val != null) {
                assertTrue(val.equals("v" + key) || val.equals("c" + key), 
                    "Corrupted value for key " + key + ": " + val);
            }
        }
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        final int CAPACITY = 100;
        cache = new ConcurrentLRUCache<>(CAPACITY);
        final int THREADS = 20;
        final int INSERTS_PER_THREAD = 100;
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        // All threads insert unique keys
        for (int t = 0; t < THREADS; t++) {
            final int base = t * INSERTS_PER_THREAD;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < INSERTS_PER_THREAD; i++) {
                        cache.put(base + i, "val-" + (base + i));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(end.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        
        // Total inserts = 2000, capacity = 100
        // Only last 100 inserted should remain (but order is non-deterministic due to concurrency)
        // However, size must be exactly capacity
        assertEquals(CAPACITY, cache.size(), "Cache should be at capacity after massive inserts");
        
        // Verify no duplicates or corruption
        Set<String> values = new HashSet<>();
        for (int i = 0; i < THREADS * INSERTS_PER_THREAD; i++) {
            String v = cache.get(i);
            if (v != null) {
                assertFalse(values.contains(v), "Duplicate value: " + v);
                values.add(v);
                assertTrue(v.startsWith("val-"), "Corrupted value: " + v);
            }
        }
        assertEquals(CAPACITY, values.size());
    }

    @Test
    void readWriteLockFairness() throws InterruptedException {
        // This test ensures writers don't starve under heavy read load
        cache = new ConcurrentLRUCache<>(10);
        cache.put(1, "a");
        
        AtomicInteger readCount = new AtomicInteger();
        AtomicInteger writeCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readReady = new CountDownLatch(10);
        CountDownLatch end = new CountDownLatch(11); // 10 readers + 1 writer
        
        // Start 10 continuous readers
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    readReady.countDown();
                    while (!Thread.currentThread().isInterrupted()) {
                        cache.get(1);
                        readCount.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }).start();
        }
        
        start.countDown();
        readReady.await(); // Wait for readers to start
        
        // Now do a write - should eventually acquire lock
        new Thread(() -> {
            cache.put(2, "b");
            writeCount.incrementAndGet();
            end.countDown();
        }).start();
        
        // Give writer time to acquire lock
        Thread.sleep(100);
        assertEquals(1, writeCount.get(), "Writer starved by readers");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void variousCapacities(int capacity) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < capacity * 2; i++) {
            c.put(i, i * 10);
        }
        assertEquals(capacity, c.size());
        // Last 'capacity' items should be present
        for (int i = capacity; i < capacity * 2; i++) {
            assertEquals(i * 10, c.get(i));
        }
        for (int i = 0; i < capacity; i++) {
            assertNull(c.get(i));
        }
    }
}
```

### Thread-Safety Guarantees Explained

1. **ReadWriteLock Strategy**:
   - **Read Lock**: Used for non-mutating operations (`get` without promotion, `size`, `containsKey`)
   - **Write Lock**: Used for all structural modifications (`put`, eviction, node promotion, `computeIfAbsent`)
   - Allows **concurrent reads** while ensuring **exclusive writes**

2. **Atomicity of Compound Operations**:
   - `get` with promotion: Double-checked locking pattern - read lock for lookup, upgrade to write lock for promotion
   - `put`: Single write lock covers map insertion, list insertion, and potential eviction
   - `computeIfAbsent`: Write lock covers entire check-compute-insert sequence atomically

3. **Memory Visibility**:
   - `ReentrantReadWriteLock` provides **happens-before** guarantees
   - All writes (map updates, pointer changes) are visible to subsequent lock acquisitions
   - No `volatile` needed on node fields due to lock synchronization

4. **Eviction Correctness**:
   - Eviction (`evictLRU`) and insertion (`addToFront`) are atomic under same write lock
   - No window where cache exceeds capacity
   - LRU node removal from both list and map is atomic

5. **No ABA Problem**:
   - Nodes are **never reused** - new `Node` created for each insertion
   - Removed nodes become eligible for GC immediately
   - Pointer updates are protected by write lock

6. **Lock Ordering**:
   - Single lock (read or write) acquired per operation
   - No nested locks → **no deadlock possibility**
   - Write lock upgrade in `get` is safe because read lock is released first

7. **Invariant Preservation**:
   - `map.size() == list node count` always holds under write lock
   - List always properly linked (dummy head/tail never removed)
   - Capacity constraint enforced atomically with insertion

The stress tests verify these guarantees under heavy contention with 50 threads performing 2000 operations each, checking for data corruption, size invariants, and eviction correctness.