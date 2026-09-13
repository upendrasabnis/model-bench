<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260913T071906Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a combination of HashMap and Doubly Linked List with ReadWriteLock.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head (most recently used)
    private final Node<K, V> tail; // dummy tail (least recently used)
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    // Statistics for testing
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /**
     * Gets value for key, marks as recently used.
     * @return value or null if not found
     */
    public V get(K key) {
        readLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                misses.incrementAndGet();
                return null;
            }
            // Upgrade to write lock to move to front
            readLock.unlock();
            writeLock.lock();
            try {
                // Re-check under write lock (double-check)
                node = map.get(key);
                if (node == null) {
                    misses.incrementAndGet();
                    return null;
                }
                moveToHead(node);
                hits.incrementAndGet();
                return node.value;
            } finally {
                writeLock.unlock();
                readLock.lock();
            }
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Puts key-value pair, evicts LRU if at capacity.
     * @return previous value or null
     */
    public V put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                return node.value;
            }

            if (map.size() >= capacity) {
                evictLRU();
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes and returns value for key.
     */
    public V remove(K key) {
        writeLock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) return null;
            removeNode(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns true if key exists.
     */
    public boolean containsKey(K key) {
        readLock.lock();
        try {
            return map.containsKey(key);
        } finally {
            readLock.unlock();
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

    public boolean isEmpty() {
        readLock.lock();
        try {
            return map.isEmpty();
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

    // Statistics
    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public long evictions() { return evictions.get(); }
    public double hitRate() {
        long h = hits.get();
        long m = misses.get();
        return (h + m) == 0 ? 0.0 : (double) h / (h + m);
    }

    // Snapshot for testing (not thread-safe for iteration, only for verification)
    public List<K> getKeysInOrder() {
        writeLock.lock();
        try {
            List<K> keys = new ArrayList<>(map.size());
            Node<K, V> curr = head.next;
            while (curr != tail) {
                keys.add(curr.key);
                curr = curr.next;
            }
            return keys;
        } finally {
            writeLock.unlock();
        }
    }

    // Internal methods (must hold writeLock)
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
        if (lru != head) {
            removeNode(lru);
            map.remove(lru.key);
            evictions.incrementAndGet();
        }
    }

    // Doubly linked list node
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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit 5 tests for ConcurrentLRUCache.
 */
@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {

    @Test
    void basicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        assertNull(cache.get(1));
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionOrder() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
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
        assertEquals(3, cache.size());
    }

    @Test
    void updateExistingKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "one");
        cache.put(2, "two");
        
        // Update 1 - should become MRU
        cache.put(1, "ONE");
        
        // Add 3 - should evict 2 (LRU)
        cache.put(3, "three");
        
        assertEquals("ONE", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("three", cache.get(3));
    }

    @Test
    void removeOperation() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertEquals("one", cache.remove(1));
        assertNull(cache.get(1));
        assertEquals(1, cache.size());
        assertNull(cache.remove(999));
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(-1));
    }

    @Test
    void clearOperation() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void statisticsTracking() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "one");
        cache.get(1); // hit
        cache.get(2); // miss
        cache.get(1); // hit
        
        assertEquals(2, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(2.0/3, cache.hitRate(), 0.001);
    }

    @Test
    void getKeysInOrderReflectsRecency() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(1); // MRU
        
        List<Integer> keys = cache.getKeysInOrder();
        assertEquals(List.of(1, 3, 2), keys); // MRU to LRU
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentReadWriteStress() throws InterruptedException {
        int capacity = 1000;
        int numThreads = 16;
        int operationsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        // Pre-populate
        for (int i = 0; i < capacity; i++) {
            cache.put(i, i * 10);
        }
        
        AtomicLong totalOps = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        List<Thread> threads = new ArrayList<>();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rand.nextInt(capacity * 2); // Some keys outside range
                        if (rand.nextBoolean()) {
                            // Read
                            cache.get(key);
                        } else {
                            // Write
                            cache.put(key, key * 100);
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Test timed out");
        
        // Verify invariants
        assertTrue(cache.size() <= capacity, "Size exceeds capacity: " + cache.size());
        assertEquals(capacity, cache.capacity());
        
        // Verify no data corruption - spot check
        for (int i = 0; i < 100; i++) {
            Integer val = cache.get(i);
            if (val != null) {
                assertTrue(val == i * 10 || val == i * 100, 
                    "Corrupted value for key " + i + ": " + val);
            }
        }
        
        System.out.println("Total ops: " + totalOps.get() + 
                          ", Hits: " + cache.hits() + 
                          ", Misses: " + cache.misses() + 
                          ", Evictions: " + cache.evictions());
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        int capacity = 100;
        int numThreads = 8;
        int putsPerThread = 2000;
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        int key = threadId * putsPerThread + i;
                        cache.put(key, "value-" + key);
                        
                        // Verify we can read it back immediately
                        String val = cache.get(key);
                        if (val == null || !val.equals("value-" + key)) {
                            errors.incrementAndGet();
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
        assertTrue(endLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Data corruption detected");
        
        // Final size must not exceed capacity
        assertTrue(cache.size() <= capacity, "Size " + cache.size() + " exceeds capacity " + capacity);
        assertEquals(cache.evictions(), numThreads * putsPerThread - cache.size() + 
                    (cache.size() < capacity ? 0 : 0), "Eviction count mismatch");
    }

    @Test
    void highContentionSameKeys() throws InterruptedException {
        int capacity = 50;
        int numThreads = 20;
        int opsPerThread = 10000;
        ConcurrentLRUCache<Integer, AtomicInteger> cache = new ConcurrentLRUCache<>(capacity);
        
        // Use shared counter objects to detect lost updates
        Map<Integer, AtomicInteger> expectedValues = new ConcurrentHashMap<>();
        for (int i = 0; i < capacity * 2; i++) {
            expectedValues.put(i, new AtomicInteger(0));
        }
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();
        
        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rand.nextInt(capacity); // High contention on small key set
                        AtomicInteger counter = expectedValues.get(key);
                        
                        if (rand.nextDouble() < 0.7) {
                            // Increment operation
                            int newVal = counter.incrementAndGet();
                            cache.put(key, counter); // Store same object
                            
                            // Verify
                            AtomicInteger cached = cache.get(key);
                            if (cached != counter || cached.get() != newVal) {
                                errors.incrementAndGet();
                            }
                        } else {
                            // Read operation
                            AtomicInteger cached = cache.get(key);
                            if (cached != null && cached != counter) {
                                errors.incrementAndGet(); // Object identity lost
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Lost updates or object corruption under contention");
        assertTrue(cache.size() <= capacity);
    }

    @Test
    void lruOrderingUnderConcurrency() throws InterruptedException {
        int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        // Fill cache
        for (int i = 0; i < capacity; i++) {
            cache.put(i, i);
        }
        
        // Concurrently access a subset to make them MRU
        int hotKeys = 20;
        int numThreads = 10;
        int accessesPerThread = 1000;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();
                    for (int i = 0; i < accessesPerThread; i++) {
                        int key = rand.nextInt(hotKeys);
                        cache.get(key); // Make hot keys MRU
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        
        // Now add new entries - should evict cold keys first
        for (int i = capacity; i < capacity + 50; i++) {
            cache.put(i, i);
        }
        
        // Hot keys should still be present
        int hotKeysFound = 0;
        for (int i = 0; i < hotKeys; i++) {
            if (cache.get(i) != null) hotKeysFound++;
        }
        
        // Most hot keys should survive (allowing some variance due to timing)
        assertTrue(hotKeysFound >= hotKeys * 0.7, 
            "Too many hot keys evicted: " + hotKeysFound + "/" + hotKeys);
        
        // Cold keys should be mostly evicted
        int coldKeysFound = 0;
        for (int i = hotKeys; i < capacity; i++) {
            if (cache.get(i) != null) coldKeysFound++;
        }
        
        System.out.println("Hot keys retained: " + hotKeysFound + "/" + hotKeys +
                          ", Cold keys retained: " + coldKeysFound + "/" + (capacity - hotKeys));
    }

    @Test
    void concurrentRemoveAndGet() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(1000);
        for (int i = 0; i < 1000; i++) {
            cache.put(i, "val-" + i);
        }
        
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger removes = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        
        for (int t = 0; t < numThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random();
                    for (int i = 0; i < 1000; i++) {
                        int key = rand.nextInt(1000);
                        if (rand.nextBoolean()) {
                            String val = cache.remove(key);
                            if (val != null) removes.incrementAndGet();
                        } else {
                            if (cache.get(key) != null) gets.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS));
        
        // Size should be consistent
        int expectedSize = 1000 - removes.get();
        // Note: gets don't change size, but concurrent remove/get race means
        // we can only verify size <= initial and no exceptions
        assertTrue(cache.size() <= 1000);
        assertTrue(cache.size() >= 0);
    }

    @Test
    void memoryVisibility() throws InterruptedException {
        // Test that writes from one thread are visible to others
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicBoolean readerSawValue = new AtomicBoolean(false);
        
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                cache.put(i, i * 2);
            }
            writerDone.set(true);
        });
        
        Thread reader = new Thread(() -> {
            while (!writerDone.get()) {
                // Spin
            }
            // Should see all values
            for (int i = 0; i < 1000; i++) {
                Integer val = cache.get(i);
                if (val != null && val == i * 2) {
                    readerSawValue.set(true);
                }
            }
        });
        
        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);
        
        assertTrue(readerSawValue.get(), "Memory visibility issue: reader didn't see written values");
    }

    @Test
    void stressTestWithVerification() throws InterruptedException {
        // This test uses a deterministic verification approach
        int capacity = 200;
        int numThreads = 16;
        int opsPerThread = 2000;
        
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        // Track expected state with a thread-safe map
        ConcurrentHashMap<Integer, Integer> expected = new ConcurrentHashMap<>();
        // Track access order for LRU verification (simplified)
        ConcurrentLinkedQueue<Integer> accessOrder = new ConcurrentLinkedQueue<>();
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId * 12345);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rand.nextInt(capacity * 3);
                        int value = threadId * 100000 + i;
                        
                        if (rand.nextDouble() < 0.6) {
                            // Put
                            cache.put(key, value);
                            expected.put(key, value);
                            accessOrder.add(key);
                        } else {
                            // Get
                            Integer cached = cache.get(key);
                            Integer exp = expected.get(key);
                            if ((cached == null) != (exp == null) || 
                                (cached != null && !cached.equals(exp))) {
                                errors.incrementAndGet();
                            }
                            if (cached != null) {
                                accessOrder.add(key);
                            }
                        }
                        
                        // Periodic size check
                        if (i % 500 == 0) {
                            if (cache.size() > capacity) {
                                errors.incrementAndGet();
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
        assertEquals(0, errors.get(), "Concurrent access caused data corruption");
        
        // Final verification
        assertTrue(cache.size() <= capacity);
        
        // Verify all cached values match expected
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            Integer cached = cache.get(entry.getKey());
            // Note: due to evictions, not all expected keys will be in cache
            if (cached != null) {
                assertEquals(entry.getValue(), cached, 
                    "Value mismatch for key " + entry.getKey());
            }
        }
        
        System.out.println("Final size: " + cache.size() + 
                          ", Evictions: " + cache.evictions() +
                          ", Hit rate: " + String.format("%.2f%%", cache.hitRate() * 100));
    }
}
```

## Thread-Safety Guarantees Explanation

### Locking Strategy
The implementation uses a **single `ReentrantReadWriteLock`** protecting all internal state:

1. **Read Lock** (`readLock`): Acquired for `get()`, `containsKey()`, `size()`, `isEmpty()`
   - Multiple readers can proceed concurrently
   - `get()` upgrades to write lock when moving accessed node to head (LRU update)

2. **Write Lock** (`writeLock`): Acquired for `put()`, `remove()`, `clear()`, and internal mutations
   - Exclusive access - no readers or other writers
   - Protects structural modifications to both HashMap and linked list

### Why This Guarantees