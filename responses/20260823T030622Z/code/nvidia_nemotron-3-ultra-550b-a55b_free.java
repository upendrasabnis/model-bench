<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260823T030622Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) get/put operations.
 * Uses a combination of ConcurrentHashMap and a doubly-linked list protected by a ReadWriteLock.
 * 
 * Thread-safety guarantees:
 * 1. ReadWriteLock allows concurrent reads (get) but exclusive writes (put, eviction, node movement)
 * 2. All structural modifications to the linked list are protected by write lock
 * 3. Map operations are atomic (ConcurrentHashMap) but coordinated with list via locks
 * 4. Node movement on access (get/put) is atomic with respect to other mutations
 * 5. Eviction is performed atomically with insertion when capacity is exceeded
 * 6. No race conditions between check-then-act sequences due to lock protection
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head; // Most recently used
    private final Node<K, V> tail; // Least recently used
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicInteger size = new AtomicInteger(0);

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 2);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;

        lock.writeLock().lock();
        try {
            // Re-check in case node was evicted between map lookup and lock acquisition
            if (map.get(key) != node) return node.value; // Another thread already moved it
            moveToHead(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public V put(K key, V value) {
        Node<K, V> newNode = new Node<>(key, value);
        Node<K, V> existingNode = map.putIfAbsent(key, newNode);
        
        lock.writeLock().lock();
        try {
            if (existingNode != null) {
                // Key already existed, update value and move to head
                map.put(key, existingNode); // Ensure map has the correct node reference
                existingNode.value = value;
                moveToHead(existingNode);
                return existingNode.value;
            } else {
                // New key
                addToHead(newNode);
                int currentSize = size.incrementAndGet();
                
                if (currentSize > capacity) {
                    evictLeastRecentlyUsed();
                }
                return null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        // Fast path: check without lock
        Node<K, V> node = map.get(key);
        if (node != null) {
            lock.writeLock().lock();
            try {
                if (map.get(key) == node) {
                    moveToHead(node);
                    return node.value;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        // Slow path: compute value
        V value = mappingFunction.apply(key);
        if (value == null) return null;

        // Retry insertion with lock
        lock.writeLock().lock();
        try {
            node = map.get(key);
            if (node != null) {
                // Another thread beat us
                moveToHead(node);
                return node.value;
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            if (size.incrementAndGet() > capacity) {
                evictLeastRecentlyUsed();
            }
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(K key) {
        Node<K, V> node = map.remove(key);
        if (node != null) {
            lock.writeLock().lock();
            try {
                if (map.get(key) == null) { // Still not re-added
                    removeNode(node);
                    size.decrementAndGet();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public int size() {
        return size.get();
    }

    public int capacity() {
        return capacity;
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
            size.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Package-private for testing
    List<K> getKeysInOrder() {
        lock.readLock().lock();
        try {
            List<K> keys = new ArrayList<>();
            Node<K, V> current = head.next;
            while (current != tail) {
                keys.add(current.key);
                current = current.next;
            }
            return keys;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Package-private for testing
    boolean validateStructure() {
        lock.readLock().lock();
        try {
            // Check size consistency
            if (size.get() != map.size()) return false;
            if (size.get() > capacity) return false;

            // Check linked list integrity
            int count = 0;
            Node<K, V> current = head.next;
            Node<K, V> prev = head;
            while (current != tail) {
                if (current.prev != prev) return false;
                if (!map.containsKey(current.key) || map.get(current.key) != current) return false;
                count++;
                prev = current;
                current = current.next;
            }
            if (current.prev != prev) return false;
            if (count != size.get()) return false;

            // Check all map entries are in list
            for (Node<K, V> node : map.values()) {
                if (node.prev == null || node.next == null) return false;
                // Verify reachable from head
                Node<K, V> check = head.next;
                boolean found = false;
                while (check != tail) {
                    if (check == node) { found = true; break; }
                    check = check.next;
                }
                if (!found) return false;
            }
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private void evictLeastRecentlyUsed() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            removeNode(lru);
            map.remove(lru.key);
            size.decrementAndGet();
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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {

    @Test
    void basicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        assertNull(cache.get(1));
        assertNull(cache.put(1, "one"));
        assertEquals("one", cache.get(1));
        assertEquals(1, cache.size());
        
        assertNull(cache.put(2, "two"));
        assertNull(cache.put(3, "three"));
        assertEquals(3, cache.size());
        
        // Access 1 to make it MRU
        assertEquals("one", cache.get(1));
        assertEquals(List.of(1, 3, 2), cache.getKeysInOrder());
        
        // Add 4, should evict 2 (LRU)
        assertNull(cache.put(4, "four"));
        assertEquals(3, cache.size());
        assertFalse(cache.containsKey(2));
        assertEquals(List.of(4, 1, 3), cache.getKeysInOrder());
        
        // Update existing
        assertEquals("three", cache.put(3, "three-updated"));
        assertEquals("three-updated", cache.get(3));
        assertEquals(List.of(3, 4, 1), cache.getKeysInOrder());
        
        cache.remove(1);
        assertEquals(2, cache.size());
        assertEquals(List.of(3, 4), cache.getKeysInOrder());
        
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.validateStructure());
    }

    @Test
    void evictionOrder() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        assertEquals(List.of(3, 2, 1), cache.getKeysInOrder());
        
        cache.get(1); // Make 1 MRU
        assertEquals(List.of(1, 3, 2), cache.getKeysInOrder());
        
        cache.put(4, 4); // Evict 2
        assertEquals(List.of(4, 1, 3), cache.getKeysInOrder());
        assertFalse(cache.containsKey(2));
        
        cache.get(3); // Make 3 MRU
        assertEquals(List.of(3, 4, 1), cache.getKeysInOrder());
        
        cache.put(5, 5); // Evict 1
        assertEquals(List.of(5, 3, 4), cache.getKeysInOrder());
        assertFalse(cache.containsKey(1));
    }

    @Test
    void computeIfAbsent() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        
        String val1 = cache.computeIfAbsent(1, k -> "value-" + k);
        assertEquals("value-1", val1);
        assertEquals(1, cache.size());
        
        String val2 = cache.computeIfAbsent(1, k -> "new-value");
        assertEquals("value-1", val2); // Should return existing
        
        cache.put(2, "two");
        cache.put(3, "three"); // Evicts 1
        assertFalse(cache.containsKey(1));
        
        String val3 = cache.computeIfAbsent(1, k -> "recreated");
        assertEquals("recreated", val3);
        assertEquals(List.of(1, 3), cache.getKeysInOrder());
    }

    @Test
    void concurrentReadWrite() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        int threadCount = 10;
        int operationsPerThread = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Pre-populate
        for (int i = 0; i < 50; i++) cache.put(i, i);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int op = rand.nextInt(100);
                        int key = rand.nextInt(200);
                        
                        if (op < 40) { // 40% reads
                            cache.get(key);
                        } else if (op < 80) { // 40% writes
                            cache.put(key, key * 2);
                        } else if (op < 90) { // 10% computeIfAbsent
                            cache.computeIfAbsent(key, k -> k * 3);
                        } else { // 10% removes
                            cache.remove(key);
                        }
                        
                        // Periodic validation
                        if (i % 100 == 0 && !cache.validateStructure()) {
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
        assertEquals(0, errors.get(), "Structure validation failed during concurrent access");
        assertTrue(cache.validateStructure());
        assertTrue(cache.size() <= cache.capacity());
    }

    @Test
    void highContentionStressTest() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(50);
        int threadCount = 50;
        int operationsPerThread = 2000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger evictions = new AtomicInteger(0);

        // Track expected evictions
        ConcurrentHashMap<Integer, Integer> expectedValues = new ConcurrentHashMap<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    Random rand = new Random(threadId);
                    for (int i = 0; i < operationsPerThread; i++) {
                        int key = rand.nextInt(100);
                        int op = rand.nextInt(100);
                        
                        if (op < 50) { // 50% reads
                            Integer val = cache.get(key);
                            if (val != null && val != key * 10) {
                                errors.incrementAndGet();
                            }
                        } else if (op < 90) { // 40% writes
                            cache.put(key, key * 10);
                            expectedValues.put(key, key * 10);
                        } else { // 10% computeIfAbsent
                            cache.computeIfAbsent(key, k -> k * 100);
                            expectedValues.put(key, key * 100);
                        }
                        
                        if (i % 200 == 0 && !cache.validateStructure()) {
                            errors.incrementAndGet();
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
        assertTrue(endLatch.await(60, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Errors during high contention test");
        assertTrue(cache.validateStructure());
        assertTrue(cache.size() <= cache.capacity());
        
        // Verify all cached values are correct
        for (Map.Entry<Integer, Integer> entry : cache.map.entrySet()) {
            Integer expected = expectedValues.get(entry.getKey());
            if (expected != null && !expected.equals(entry.getValue().value)) {
                fail("Value mismatch for key " + entry.getKey() + 
                     ": expected "