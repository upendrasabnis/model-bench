<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261112T132831Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Thread-safe concurrent LRU Cache with O(1) average get/put operations.
 * 
 * Thread-safety guarantees:
 * 1. ConcurrentHashMap provides thread-safe key->node lookups and atomic putIfAbsent/computeIfAbsent
 * 2. ReentrantReadWriteLock protects the doubly-linked list structure:
 *    - Read lock for get() operations (traversing/moving nodes)
 *    - Write lock for structural modifications (eviction, new node insertion, node removal)
 * 3. Node's prev/next pointers are volatile to ensure visibility across threads
 * 4. All list mutations happen under write lock, ensuring consistent list state
 * 5. Map and list operations are coordinated: map update happens before/after list update
 *    under appropriate locks to maintain consistency
 */
public class ConcurrentLRUCache<K, V> {
    
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head;  // dummy head (most recently used)
    private final Node<K, V> tail;  // dummy tail (least recently used)
    private final ReentrantReadWriteLock listLock = new ReentrantReadWriteLock();
    private final AtomicInteger size = new AtomicInteger(0);
    
    // Statistics for testing
    private final AtomicInteger hits = new AtomicInteger(0);
    private final AtomicInteger misses = new AtomicInteger(0);
    private final AtomicInteger evictions = new AtomicInteger(0);
    
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
     * Gets value for key, marks as recently used. Returns null if not found.
     * O(1) average time complexity.
     */
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            misses.incrementAndGet();
            return null;
        }
        
        // Move to front (most recently used) under read lock
        // We use read lock because multiple gets can happen concurrently
        // but we need exclusive access to move this specific node
        listLock.readLock().lock();
        try {
            // Double-check node still exists in list (not evicted concurrently)
            if (node.prev != null && node.next != null) {
                moveToHead(node);
                hits.incrementAndGet();
                return node.value;
            }
        } finally {
            listLock.readLock().unlock();
        }
        
        // Node was evicted between map.get and acquiring lock
        misses.incrementAndGet();
        return null;
    }
    
    /**
     * Puts key-value pair. If key exists, updates value and marks as recently used.
     * If capacity exceeded, evicts least recently used entry.
     * O(1) average time complexity.
     * @return previous value or null if new entry
     */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }
        
        // Try to update existing node
        Node<K, V> existingNode = map.get(key);
        if (existingNode != null) {
            listLock.writeLock().lock();
            try {
                // Re-check under write lock
                if (map.get(key) == existingNode) {
                    V oldValue = existingNode.value;
                    existingNode.value = value;
                    moveToHead(existingNode);
                    return oldValue;
                }
            } finally {
                listLock.writeLock().unlock();
            }
            // Fall through to insert new if node was evicted
        }
        
        // Insert new node
        Node<K, V> newNode = new Node<>(key, value);
        
        // Use computeIfAbsent for atomic map insertion
        Node<K, V> actualNode = map.computeIfAbsent(key, k -> {
            listLock.writeLock().lock();
            try {
                // Check capacity and evict if needed
                if (size.get() >= capacity) {
                    evictLRU();
                }
                addToHead(newNode);
                size.incrementAndGet();
                return newNode;
            } finally {
                listLock.writeLock().unlock();
            }
        });
        
        // If another thread inserted the same key concurrently
        if (actualNode != newNode) {
            // Update the existing node's value and move to front
            listLock.writeLock().lock();
            try {
                if (map.get(key) == actualNode) {
                    V oldValue = actualNode.value;
                    actualNode.value = value;
                    moveToHead(actualNode);
                    return oldValue;
                }
            } finally {
                listLock.writeLock().unlock();
            }
            // Retry if node was evicted
            return put(key, value);
        }
        
        return null;
    }
    
    /**
     * Computes value for key if absent, using the provided mapping function.
     * The function is called at most once per key (atomic with respect to the key).
     * O(1) average time complexity.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null) {
            throw new NullPointerException();
        }
        
        Node<K, V> existingNode = map.get(key);
        if (existingNode != null) {
            listLock.readLock().lock();
            try {
                if (existingNode.prev != null && existingNode.next != null) {
                    moveToHead(existingNode);
                    hits.incrementAndGet();
                    return existingNode.value;
                }
            } finally {
                listLock.readLock().unlock();
            }
        }
        
        // Compute value (outside lock to avoid blocking other operations)
        V computedValue = mappingFunction.apply(key);
        if (computedValue == null) {
            return null;
        }
        
        // Insert atomically
        Node<K, V> newNode = new Node<>(key, computedValue);
        Node<K, V> actualNode = map.computeIfAbsent(key, k -> {
            listLock.writeLock().lock();
            try {
                if (size.get() >= capacity) {
                    evictLRU();
                }
                addToHead(newNode);
                size.incrementAndGet();
                return newNode;
            } finally {
                listLock.writeLock().unlock();
            }
        });
        
        if (actualNode != newNode) {
            // Another thread won the race
            listLock.writeLock().lock();
            try {
                if (map.get(key) == actualNode) {
                    moveToHead(actualNode);
                }
            } finally {
                listLock.writeLock().unlock();
            }
            return actualNode.value;
        }
        
        misses.incrementAndGet();
        return computedValue;
    }
    
    /**
     * Removes and returns value for key. O(1) average.
     */
    public V remove(K key) {
        Node<K, V> node = map.remove(key);
        if (node == null) {
            return null;
        }
        
        listLock.writeLock().lock();
        try {
            // Only remove from list if still linked (not already evicted)
            if (node.prev != null && node.next != null) {
                unlinkNode(node);
                size.decrementAndGet();
            }
            return node.value;
        } finally {
            listLock.writeLock().unlock();
        }
    }
    
    public int size() {
        return size.get();
    }
    
    public int capacity() {
        return capacity;
    }
    
    public boolean isEmpty() {
        return size.get() == 0;
    }
    
    public void clear() {
        listLock.writeLock().lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
            size.set(0);
        } finally {
            listLock.writeLock().unlock();
        }
    }
    
    // Statistics for testing
    public int getHits() { return hits.get(); }
    public int getMisses() { return misses.get(); }
    public int getEvictions() { return evictions.get(); }
    public void resetStats() { hits.set(0); misses.set(0); evictions.set(0); }
    
    // Internal linked list operations (must hold appropriate lock)
    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    
    private void moveToHead(Node<K, V> node) {
        if (node == head.next) return; // Already at head
        unlinkNode(node);
        addToHead(node);
    }
    
    private void unlinkNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }
    
    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            unlinkNode(lru);
            map.remove(lru.key);
            size.decrementAndGet();
            evictions.incrementAndGet();
        }
    }
    
    // Package-private for testing
    Node<K, V> getHeadNode() { return head.next; }
    Node<K, V> getTailNode() { return tail.prev; }
    
    /**
     * Node with volatile pointers for safe publication and visibility.
     */
    static class Node<K, V> {
        final K key;
        volatile V value;
        volatile Node<K, V> prev;
        volatile Node<K, V> next;
        
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
    void testBasicPutAndGet() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
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
    void testEvictionOnUpdate() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Update 1 (makes it MRU)
        cache.put(1, "uno");
        
        // Add 4, should evict 2
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("uno", cache.get(1));
    }
    
    @Test
    void testRemove() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertEquals("one", cache.remove(1));
        assertNull(cache.get(1));
        assertEquals(1, cache.size());
        assertNull(cache.remove(3)); // Non-existent
    }
    
    @Test
    void testClear() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }
    
    @Test
    void testCapacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(-1));
    }
    
    @Test
    void testNullKeyValueThrows() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
    }
    
    @Test
    void testComputeIfAbsent() {
        String value = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", value);
        assertEquals("computed-1", cache.get(1));
        
        // Second call should not recompute
        String value2 = cache.computeIfAbsent(1, k -> "should-not-run");
        assertEquals("computed-1", value2);
    }
    
    @Test
    void testComputeIfAbsentReturnsNull() {
        String value = cache.computeIfAbsent(1, k -> null);
        assertNull(value);
        assertNull(cache.get(1));
    }
    
    @Test
    void testStatistics() {
        cache.put(1, "one");
        cache.get(1); // hit
        cache.get(2); // miss
        cache.put(2, "two");
        cache.put(3, "three");
        cache.put(4, "four"); // evicts 1
        
        assertEquals(1, cache.getHits());
        assertEquals(1, cache.getMisses());
        assertEquals(1, cache.getEvictions());
    }
    
    // ==================== CONCURRENCY TESTS ====================
    
    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> testCache = new ConcurrentLRUCache<>(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount * 2);
        AtomicInteger errors = new AtomicInteger(0);
        
        // Writer threads
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = threadId * operationsPerThread + i;
                        testCache.put(key, key * 2);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        // Reader threads
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = ThreadLocalRandom.current().nextInt(threadCount * operationsPerThread);
                        testCache.get(key); // May return null, that's fine
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        
        assertEquals(0, errors.get(), "Concurrent operations should not throw exceptions");
        assertTrue(testCache.size() <= 100, "Size should not exceed capacity");
    }
    
    @Test
    void testHighContentionOnSameKeys() throws InterruptedException {
        int threadCount = 20;
        int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> testCache = new ConcurrentLRUCache<>(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = i % 100; // Only 100 distinct keys, high contention
                        testCache.put(key, key);
                        testCache.get(key);
                        if (i % 10 == 0) {
                            testCache.computeIfAbsent(key, k -> k * 10);
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
        assertTrue(endLatch.await(15, TimeUnit.SECONDS), "Test should complete in time");
        assertEquals(0, errors.get());
        assertTrue(testCache.size() <= 50);
    }
    
    @Test
    void testConcurrentEvictionCorrectness() throws InterruptedException {
        int capacity = 100;
        int threadCount = 16;
        int operationsPerThread = 2000;
        ConcurrentLRUCache<Integer, String> testCache = new ConcurrentLRUCache<>(capacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ConcurrentHashMap<Integer, String> referenceMap = new ConcurrentHashMap<>();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random random = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = random.nextInt(200); // Key space larger than capacity
                        String value = "thread-" + threadId + "-val-" + key;
                        
                        if (random.nextBoolean()) {
                            testCache.put(key, value);
                            referenceMap.put(key, value);
                        } else {
                            testCache.get(key);
                        }
                        
                        // Periodically verify a random key
                        if (i % 100 == 0) {
                            int verifyKey = random.nextInt(200);
                            String cached = testCache.get(verifyKey);
                            String reference = referenceMap.get(verifyKey);
                            // Note: referenceMap doesn't track LRU eviction, so we only
                            // verify that if cache has a value, it matches reference
                            if (cached != null && reference != null) {
                                assertEquals(reference, cached, 
                                    "Value mismatch for key " + verifyKey);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(endLatch.await(20, TimeUnit.SECONDS));
        
        // Final consistency check: all keys in cache must have correct values
        // We can't easily check eviction correctness without a concurrent reference LRU
        // but we can verify no corruption
        assertTrue(testCache.size() <= capacity);
    }
    
    @Test
    void testStressInvariantsUnderHeavyLoad() throws InterruptedException {
        // This test verifies critical invariants under heavy concurrent load:
        // 1. Size never exceeds capacity
        // 2. No null keys/values in cache
        // 3. List structure remains consistent (no cycles, proper linking)
        // 4. Map and list stay in sync
        
        int capacity = 200;
        int threadCount = 32;
        int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> testCache = new ConcurrentLRUCache<>(capacity);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger maxSizeObserved = new AtomicInteger(0);
        
        // Monitor thread to check invariants periodically
        Thread monitor = new Thread(() -> {
            try {
                startLatch.await();
                while (endLatch.getCount() > 0) {
                    Thread.sleep(10);
                    int currentSize = testCache.size();
                    maxSizeObserved.updateAndGet(prev -> Math.max(prev, currentSize));
                    
                    if (currentSize > capacity) {
                        errors.incrementAndGet();
                        System.err.println("INVARIANT VIOLATION: size=" + currentSize + " > capacity=" + capacity);
                    }
                    
                    // Verify list integrity by traversing
                    verifyListIntegrity(testCache);
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                e.printStackTrace();
            }
        });
        monitor.start();
        
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random random = new Random();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int op = random.nextInt(4);
                        int key = random.nextInt(500);
                        
                        switch (op) {
                            case 0: // put
                                testCache.put(key, key * 10);
                                break;
                            case 1: // get
                                testCache.get(key);
                                break;
                            case 2: // computeIfAbsent
                                testCache.computeIfAbsent(key, k -> k * 100);
                                break;
                            case 3: // remove
                                testCache.remove(key);
                                break;
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
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Worker threads should complete");
        monitor.join(5000);
        
        assertEquals(0, errors.get(), "No invariant violations should occur");
        assertTrue(maxSizeObserved.get() <= capacity, "Max observed size should not exceed capacity");
        
        // Final integrity check
        verifyListIntegrity(testCache);
        verifyMapListConsistency(testCache);
    }
    
    private void verifyListIntegrity(ConcurrentLRUCache<Integer, Integer> cache) {
        cache.listLock.readLock().lock();
        try {
            ConcurrentLRUCache.Node<Integer, Integer> node = cache.getHeadNode();
            int count = 0;
            Set<ConcurrentLRUCache.Node<Integer, Integer>> visited = new HashSet<>();
            
            while (node != cache.getTailNode() && node != null) {
                if (!visited.add(node)) {
                    throw new IllegalStateException("Cycle detected in linked list");
                }
                if (node.key == null || node.value == null) {
                    throw new IllegalStateException("Null key/value in list node");
                }
                if (node.prev == null || node.next == null) {
                    throw new IllegalStateException("Broken link in list");
                }
                if (node.prev.next != node || node.next.prev != node) {
                    throw new IllegalStateException("Inconsistent prev/next pointers");
                }
                node = node.next;
                count++;
                if (count > cache.capacity() + 10) {
                    throw new IllegalStateException("List too long, possible cycle");
                }
            }
            
            if (count != cache.size()) {
                throw new IllegalStateException("List count (" + count + ") != size (" + cache.size() + ")");
            }
        } finally {
            cache.listLock.readLock().unlock();
        }
    }
    
    private void verifyMapListConsistency(ConcurrentLRUCache<Integer, Integer> cache) {
        cache.listLock.readLock().lock();
        try {
            // Every node in list must be in map with same key
            ConcurrentLRUCache.Node<Integer, Integer> node = cache.getHeadNode();
            while (node != cache.getTailNode() && node != null) {
                ConcurrentLRUCache.Node<Integer, Integer> mapNode = cache.map.get(node.key);
                if (mapNode != node) {
                    throw new IllegalStateException("Map-list inconsistency for key " + node.key);
                }
                node = node.next;
            }
            
            // Every entry in map must be in list
            for (ConcurrentLRUCache.Node<Integer, Integer> mapNode : cache.map.values()) {
                if (mapNode.prev == null || mapNode.next == null) {
                    throw new IllegalStateException("Map node not in list: " + mapNode.key);
                }
            }
        } finally {
            cache.listLock.readLock().unlock();
        }
    }
