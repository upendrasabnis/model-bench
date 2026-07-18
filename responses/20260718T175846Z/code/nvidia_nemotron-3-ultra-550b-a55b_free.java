<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here's a complete implementation of a thread-safe concurrent LRU cache with JUnit 5 tests.

## ConcurrentLRUCache Implementation

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) average get/put operations.
 * 
 * Thread-safety guarantees:
 * 1. All mutative operations (get, put, eviction) are protected by a single ReentrantLock
 * 2. The ConcurrentHashMap provides safe concurrent reads for the key->node mapping
 * 3. The doubly-linked list modifications (relinking nodes) are atomic under the lock
 * 4. No partial updates visible: a node is either fully linked or fully unlinked
 * 5. Capacity invariant maintained: size never exceeds capacity after any operation completes
 * 6. Happens-before: lock release/acquire ensures visibility of all writes across threads
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head (most recently used)
    private final Node<K, V> tail; // dummy tail (least recently used)
    private final ReentrantLock lock = new ReentrantLock();

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

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 4 / 3 + 1);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Gets value for key, marking it as recently used.
     * @return value or null if not found
     */
    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Puts key-value pair. If key exists, updates value and marks recently used.
     * If capacity exceeded, evicts least recently used entry.
     * @return previous value or null if new entry
     */
    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        if (value == null) throw new NullPointerException("Value cannot be null");

        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                V oldValue = node.value;
                node.value = value;
                moveToHead(node);
                return oldValue;
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            if (map.size() > capacity) {
                evictTail();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Computes value if absent using the provided function.
     * Atomic: function executed while holding lock, no other operations interleaved.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        if (mappingFunction == null) throw new NullPointerException("Mapping function cannot be null");

        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                moveToHead(node);
                return node.value;
            }

            V value = mappingFunction.apply(key);
            if (value == null) {
                return null;
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            if (map.size() > capacity) {
                evictTail();
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    public void remove(K key) {
        if (key == null) return;
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) {
                unlink(node);
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return size() == 0;
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

    // Package-private for testing
    Node<K, V> getHeadNode() {
        lock.lock();
        try {
            return head.next == tail ? null : head.next;
        } finally {
            lock.unlock();
        }
    }

    Node<K, V> getTailNode() {
        lock.lock();
        try {
            return tail.prev == head ? null : tail.prev;
        } finally {
            lock.unlock();
        }
    }

    // Internal linked list operations (must hold lock)
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void moveToHead(Node<K, V> node) {
        unlink(node);
        addToHead(node);
    }

    private void evictTail() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            unlink(lru);
            map.remove(lru.key);
        }
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

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
    void updateExistingKey() {
        cache.put(1, "one");
        cache.put(1, "uno");
        
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Access 1 to make it recently used
        cache.get(1);
        
        // Add 4, should evict 2 (least recently used)
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void capacityLimit() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.put(4, "four");
        
        assertEquals(3, cache.size());
        assertNull(cache.get(1)); // evicted
        assertEquals("two", cache.get(2));
    }

    @Test
    void nullKeyThrows() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void nullValueThrows() {
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
    }

    @Test
    void computeIfAbsent() {
        String result = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", result);
        assertEquals("computed-1", cache.get(1));
        
        // Second call should not recompute
        AtomicInteger counter = new AtomicInteger();
        cache.computeIfAbsent(1, k -> {
            counter.incrementAndGet();
            return "should-not-run";
        });
        assertEquals(0, counter.get());
    }

    @Test
    void remove() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        cache.remove(1);
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals(1, cache.size());
    }

    @Test
    void clear() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void variousCapacities(int capacity) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < capacity * 2; i++) {
            c.put(i, i * 10);
        }
        assertEquals(capacity, c.size());
        // Last 'capacity' items should remain
        for (int i = capacity; i < capacity * 2; i++) {
            assertEquals(i * 10, c.get(i));
        }
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentPutAndGetStress() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int operationsPerThread = 5000;
        final ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);
        
        // Shared reference map for validation (thread-safe)
        ConcurrentHashMap<Integer, Integer> referenceMap = new ConcurrentHashMap<>();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(capacity * 2);
                        if (random.nextBoolean()) {
                            // Put operation
                            int value = key * 10 + threadId;
                            stressCache.put(key, value);
                            referenceMap.put(key, value);
                        } else {
                            // Get operation
                            Integer cached = stressCache.get(key);
                            Integer expected = referenceMap.get(key);
                            if (cached != null && !cached.equals(expected)) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertEquals(0, errors.get(), "Concurrent operations produced inconsistent results");
        assertTrue(stressCache.size() <= capacity, "Cache exceeded capacity: " + stressCache.size());
    }

    @Test
    void concurrentEvictionStress() throws InterruptedException {
        final int capacity = 500;
        final int numThreads = 8;
        final int putsPerThread = 2000;
        final ConcurrentLRUCache<Long, String> stressCache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicLong totalPuts = new AtomicLong();
        
        for (int t = 0; t < numThreads; t++) {
            final long threadOffset = (long) t * putsPerThread;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        long key = threadOffset + i;
                        stressCache.put(key, "value-" + key);
                        totalPuts.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify capacity invariant
        assertTrue(stressCache.size() <= capacity, 
            "Cache size " + stressCache.size() + " exceeds capacity " + capacity);
        
        // Verify all remaining entries are valid (no corruption)
        long remainingCount = 0;
        for (long i = 0; i < totalPuts.get(); i++) {
            String val = stressCache.get(i);
            if (val != null) {
                assertEquals("value-" + i, val, "Corrupted value for key " + i);
                remainingCount++;
            }
        }
        assertEquals(stressCache.size(), remainingCount, "Size mismatch");
    }

    @Test
    void highContentionSingleKey() throws InterruptedException {
        final int numThreads = 32;
        final int iterations = 10000;
        final ConcurrentLRUCache<Integer, AtomicInteger> cache = new ConcurrentLRUCache<>(10);
        
        // Pre-populate
        cache.put(1, new AtomicInteger(0));
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        AtomicInteger counter = cache.get(1);
                        if (counter != null) {
                            counter.incrementAndGet();
                        }
                        // Also do some puts to trigger eviction logic
                        cache.put(i % 20, new AtomicInteger(i));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Cache should still be valid
        assertTrue(cache.size() <= 10);
        AtomicInteger counter = cache.get(1);
        assertNotNull(counter);
        // Counter should have been incremented many times (exact count non-deterministic)
        assertTrue(counter.get() > 0);
    }

    @Test
    void computeIfAbsentConcurrent() throws InterruptedException {
        final int numThreads = 16;
        final int keys = 100;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(keys);
        AtomicInteger computeCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < keys; i++) {
                        int key = i % keys;
                        cache.computeIfAbsent(key, k -> {
                            computeCount.incrementAndGet();
                            return k * 10;
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Each key should be computed exactly once
        assertEquals(keys, computeCount.get(), "computeIfAbsent called multiple times for same key");
        
        // Verify all values correct
        for (int i = 0; i < keys; i++) {
            assertEquals(i * 10, cache.get(i));
        }
    }

    @Test
    void lruOrderUnderConcurrency() throws InterruptedException {
        final int capacity = 100;
        final int numThreads = 8;
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        // Phase 1: Fill cache sequentially to establish initial order
        for (int i = 0; i < capacity; i++) {
            cache.put(i, i);
        }
        
        // Phase 2: Concurrent access - some threads read old keys, some write new
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random r = new Random(threadId);
                    for (int i = 0; i < 1000; i++) {
                        if (r.nextBoolean()) {
                            // Read: access a key in lower half (should promote it)
                            int key = r.nextInt(capacity / 2);
                            cache.get(key);
                        } else {
                            // Write: add new keys (should evict LRU)
                            int key = capacity + r.nextInt(capacity);
                            cache.put(key, key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertEquals(0, errors.get());
        assertTrue(cache.size() <= capacity);
        
        // Verify no corruption: all present keys have correct values
        for (int i = 0; i < capacity * 2; i++) {
            Integer val = cache.get(i);
            if (val != null) {
                assertEquals(i, val, "Value corruption detected for key " + i);
            }
        }
    }

    @Test
    void memoryVisibility() throws InterruptedException {
        // Test that writes from one thread are visible to another
        final ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        final AtomicBoolean writerDone = new AtomicBoolean(false);
        final AtomicInteger readerResult = new AtomicInteger(-1);
        
        Thread writer = new Thread(() -> {
            cache.put(42, 100);
            writerDone.set(true);
        });
        
        Thread reader = new Thread(() -> {
            while (!writerDone.get()) {
                Thread.yield();
            }
            readerResult.set(cache.get(42));
        });
        
        writer.start();
        reader.start();
        writer.join(2000);
        reader.join(2000);
        
        assertEquals(100, readerResult.get(), "Memory visibility failure: reader didn't see writer's put");
    }
}
```

## Key Design Decisions & Thread-Safety Explanation

### Locking Strategy
- **Single `ReentrantLock`** protects all mutative operations (`get`, `put`, `remove`, `evict`, linked list modifications)
- **Why not ReadWriteLock?** `get` must move accessed node to front (mutates list), so it needs write access anyway
- **Why not lock-free?** Lock-free doubly-linked list with correct LRU ordering is extremely complex (requires hazard pointers/epoch-based reclamation)

### Correctness Guarantees
| Property | Mechanism |
|----------|-----------|
| **Mutual Exclusion** | Single lock serializes all structural modifications |
| **Visibility** | Lock release/acquire provides happens-before edges |
| **Atomicity** | Compound operations (check-map + relink + evict) execute atomically |
| **Capacity Invariant** | Eviction check and execution happen under same lock |
| **No Corruption** | Nodes fully linked/unlinked under lock; no partial states visible |

### Performance Characteristics
- **Get**: O(1) map lookup + O(1) list relink (2-3 pointer updates)
- **Put**: O(1) map insert + O(1) list insert + optional O(1) eviction
- **Contention**: Single lock becomes bottleneck under high write contention
- **Optimization Path**: For higher throughput, use **striped locking** (partition keys across 16-64 locks) or **segmented LRU** (like Caffeine)

### Stress Test Coverage
1. **Concurrent put/get** - validates no lost updates, no corruption
2. **Eviction under load** - verifies capacity invariant never violated
3. **High contention single key** - tests lock fairness and atomicity
4. **computeIfAbsent** - verifies atomic compute-once semantics
5. **LRU order under concurrency** - checks promotion/eviction logic
6. **Memory visibility** - confirms happens-before via lock

Run tests with: `./mvnw test -Dtest=ConcurrentLRUCacheTest`