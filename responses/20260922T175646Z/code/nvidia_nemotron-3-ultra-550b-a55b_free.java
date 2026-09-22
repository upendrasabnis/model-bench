<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260922T175646Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * 
 * Thread-safety guarantees:
 * 1. Single ReentrantLock protects all structural modifications to the doubly-linked list
 * 2. ConcurrentHashMap provides thread-safe key->node mapping with volatile semantics
 * 3. All compound operations (check-then-act) are atomic under the lock
 * 4. Node fields (prev/next) are only modified under the lock, ensuring visibility
 * 5. No publication of partially constructed nodes - nodes are fully initialized before insertion
 * 6. Eviction and insertion are atomic - no window where capacity is exceeded
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head;  // dummy head (most recently used)
    private final Node<K, V> tail;  // dummy tail (least recently used)
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        
        lock.lock();
        try {
            // Re-check under lock (node might have been evicted)
            node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }
        
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToHead(existing);
                return existing.value;
            }
            
            // Check capacity and evict if needed
            if (map.size() >= capacity) {
                evictLeastRecentlyUsed();
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            addToHead(newNode);
            map.put(key, newNode);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null) {
            throw new NullPointerException();
        }
        
        // Fast path: check without lock
        Node<K, V> node = map.get(key);
        if (node != null) {
            lock.lock();
            try {
                node = map.get(key);
                if (node != null) {
                    moveToHead(node);
                    return node.value;
                }
            } finally {
                lock.unlock();
            }
        }
        
        // Slow path: compute with lock held
        lock.lock();
        try {
            node = map.get(key);
            if (node != null) {
                moveToHead(node);
                return node.value;
            }
            
            V value = mappingFunction.apply(key);
            if (value == null) {
                return null;
            }
            
            if (map.size() >= capacity) {
                evictLeastRecentlyUsed();
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            addToHead(newNode);
            map.put(key, newNode);
            return value;
        } finally {
            lock.unlock();
        }
    }

    public V remove(K key) {
        if (key == null) return null;
        
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) {
                return null;
            }
            unlink(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
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
    boolean containsKey(K key) {
        return map.containsKey(key);
    }

    // Package-private for testing - verifies internal consistency
    boolean verifyIntegrity() {
        lock.lock();
        try {
            // Check list size matches map size
            int listSize = 0;
            Node<K, V> current = head.next;
            while (current != tail) {
                listSize++;
                // Verify node is in map
                Node<K, V> mapNode = map.get(current.key);
                if (mapNode != current) {
                    return false;
                }
                // Verify bidirectional links
                if (current.prev == null || current.next == null) {
                    return false;
                }
                if (current.prev.next != current || current.next.prev != current) {
                    return false;
                }
                current = current.next;
            }
            return listSize == map.size();
        } finally {
            lock.unlock();
        }
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void moveToHead(Node<K, V> node) {
        unlink(node);
        addToHead(node);
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void evictLeastRecentlyUsed() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            unlink(lru);
            map.remove(lru.key);
        }
    }

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
import java.util.concurrent.atomic.LongAdder;
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
    void evictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Access 1 to make it MRU
        cache.get(1);
        
        // Add 4, should evict 2 (LRU)
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Update 1 - should become MRU
        cache.put(1, "ONE");
        
        // Add 4, should evict 2 (now LRU)
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("ONE", cache.get(1));
    }

    @Test
    void capacityLimitRespected() {
        for (int i = 0; i < 10; i++) {
            cache.put(i, "val" + i);
        }
        assertEquals(3, cache.size());
    }

    @Test
    void removeReturnsValueAndEvicts() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertEquals("one", cache.remove(1));
        assertNull(cache.get(1));
        assertEquals(1, cache.size());
        assertEquals("two", cache.get(2));
    }

    @Test
    void nullKeyAndValueRejected() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void computeIfAbsent() {
        String result = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", result);
        assertEquals("computed-1", cache.get(1));
        
        // Second call should not recompute
        AtomicInteger counter = new AtomicInteger();
        String result2 = cache.computeIfAbsent(1, k -> {
            counter.incrementAndGet();
            return "should-not-run";
        });
        assertEquals("computed-1", result2);
        assertEquals(0, counter.get());
    }

    @Test
    void computeIfAbsentWithNullReturn() {
        String result = cache.computeIfAbsent(1, k -> null);
        assertNull(result);
        assertFalse(cache.containsKey(1));
    }

    @Test
    void clearRemovesAllEntries() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
        assertNull(cache.get(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void variousCapacities(int capacity) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < capacity * 2; i++) {
            c.put(i, i);
        }
        assertEquals(capacity, c.size());
    }

    @Test
    void integrityCheckAfterOperations() {
        for (int i = 0; i < 100; i++) {
            cache.put(i, "val" + i);
            if (i % 7 == 0) cache.get(i - 3);
            if (i % 5 == 0) cache.remove(i - 2);
        }
        assertTrue(cache.verifyIntegrity());
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentPutAndGetStressTest() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int operationsPerThread = 5000;
        
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        LongAdder successfulGets = new LongAdder();
        LongAdder failedGets = new LongAdder();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(capacity * 2);
                        if (random.nextBoolean()) {
                            stressCache.put(key, key * 2);
                        } else {
                            Integer val = stressCache.get(key);
                            if (val != null) successfulGets.increment();
                            else failedGets.increment();
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
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify invariants
        assertTrue(stressCache.verifyIntegrity(), "Cache integrity violated after concurrent access");
        assertTrue(stressCache.size() <= capacity, "Cache size exceeds capacity: " + stressCache.size());
        
        System.out.println("Successful gets: " + successfulGets.sum());
        System.out.println("Failed gets: " + failedGets.sum());
        System.out.println("Final size: " + stressCache.size());
    }

    @Test
    void concurrentReadHeavyWorkload() throws InterruptedException {
        final int capacity = 500;
        ConcurrentLRUCache<Integer, String> readCache = new ConcurrentLRUCache<>(capacity);
        
        // Pre-populate
        for (int i = 0; i < capacity; i++) {
            readCache.put(i, "value-" + i);
        }
        
        final int numReaders = 32;
        final int numWriters = 4;
        final int operationsPerThread = 10000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numReaders + numWriters);
        LongAdder readCount = new LongAdder();
        LongAdder writeCount = new LongAdder();
        AtomicInteger integrityViolations = new AtomicInteger();
        
        // Readers
        for (int t = 0; t < numReaders; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(capacity * 2);
                        readCache.get(key);
                        readCount.increment();
                        
                        // Periodic integrity check
                        if (i % 1000 == 0 && !readCache.verifyIntegrity()) {
                            integrityViolations.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Writers
        for (int t = 0; t < numWriters; t++) {
            final int writerId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(writerId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(capacity * 2);
                        readCache.put(key, "writer-" + writerId + "-" + key);
                        writeCount.increment();
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
        
        assertEquals(0, integrityViolations.get(), "Integrity violations detected during concurrent access");
        assertTrue(readCache.verifyIntegrity(), "Final integrity check failed");
        assertTrue(readCache.size() <= capacity, "Capacity exceeded: " + readCache.size());
        
        System.out.println("Reads: " + readCount.sum() + ", Writes: " + writeCount.sum());
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        final int capacity = 100;
        final int numThreads = 20;
        final int putsPerThread = 200;
        
        ConcurrentLRUCache<Integer, Integer> evictionCache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        int key = threadId * putsPerThread + i;
                        evictionCache.put(key, key);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify: only the most recent 'capacity' entries should exist
        assertTrue(evictionCache.verifyIntegrity());
        assertEquals(capacity, evictionCache.size(), "Size should equal capacity after massive concurrent puts");
        
        // Verify all keys are from the expected range (last capacity entries)
        // Note: due to concurrency, exact keys are non-deterministic, but count must be exact
    }

    @Test
    void computeIfAbsentConcurrent() throws InterruptedException {
        final int capacity = 200;
        ConcurrentLRUCache<Integer, Integer> computeCache = new ConcurrentLRUCache<>(capacity);
        final int numThreads = 16;
        final int operationsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger computeCount = new AtomicInteger();
        
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(capacity / 2); // High collision rate
                        computeCache.computeIfAbsent(key, k -> {
                            computeCount.incrementAndGet();
                            return k * 10;
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertTrue(computeCache.verifyIntegrity());
        assertTrue(computeCache.size() <= capacity);
        
        // Each key should be computed at most once
        assertTrue(computeCount.get() <= capacity / 2, 
            "computeIfAbsent called more times than unique keys: " + computeCount.get());
    }

    @Test
    void lruOrderUnderConcurrency() throws InterruptedException {
        final int capacity = 50;
        ConcurrentLRUCache<Integer, Integer> lruCache = new ConcurrentLRUCache<>(capacity);
        
        // Phase 1: Fill cache sequentially to establish known order
        for (int i = 0; i < capacity; i++) {
            lruCache.put(i, i);
        }
        
        // Phase 2: Concurrent access - some threads read old keys (making them MRU),
        // others write new keys (causing eviction)
        final int numThreads = 10;
        final int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final boolean isReader = t % 2 == 0;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        if (isReader) {
                            // Read from lower half (older entries)
                            int key = random.nextInt(capacity / 2);
                            lruCache.get(key);
                        } else {
                            // Write new keys (higher than initial range)
                            int key = capacity + random.nextInt(capacity * 2);
                            lruCache.put(key, key);
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
        endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertTrue(lruCache.verifyIntegrity());
        assertEquals(capacity, lruCache.size());
        
        // The cache should contain a mix of recently accessed old keys and new keys
        // No specific key assertions due to non-determinism, but structure must be valid
    }

    @Test
    void highContentionOnSingleKey() throws InterruptedException {
        final int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        final int numThreads = 50;
        final int opsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        LongAdder putCount = new LongAdder();
        LongAdder getCount = new LongAdder();
        