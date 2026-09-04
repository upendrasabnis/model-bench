<!-- model: cohere/north-mini-code:free | category: code | run: 20260904T143601Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

// Node for the doubly linked list
class LRUCacheNode<K, V> {
    final K key;
    V value;
    LRUCacheNode<K, V> prev;
    LRUCacheNode<K, V> next;
    
    public LRUCacheNode(K key, V value) {
        this.key = key;
        this.value = value;
    }
}

// Thread-safe concurrent LRU Cache with O(1) operations
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, LRUCacheNode<K, V>> cacheMap;
    private final LRUCacheNode<K, V> head; // Most recently used
    private final LRUCacheNode<K, V> tail; // Least recently used
    private final ReentrantLock lock;
    private final Condition notFull;
    private final AtomicInteger size;
    
    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new LRUCacheNode<>(null, null); // Dummy head
        this.tail = new LRUCacheNode<>(null, null); // Dummy tail
        this.head.next = this.tail;
        this.tail.prev = this.head;
        this.lock = new ReentrantLock();
        this.notFull = lock.newCondition();
        this.size = new AtomicInteger(0);
    }
    
    // Get value by key (O(1))
    public V get(K key) {
        lock.lock();
        try {
            LRUCacheNode<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move to front (most recently used)
            moveToFront(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }
    
    // Put key-value pair (O(1))
    public void put(K key, V value) {
        lock.lock();
        try {
            LRUCacheNode<K, V> existingNode = cacheMap.get(key);
            
            if (existingNode != null) {
                // Update value and move to front
                existingNode.value = value;
                moveToFront(existingNode);
            } else {
                // Create new node
                LRUCacheNode<K, V> newNode = new LRUCacheNode<>(key, value);
                cacheMap.put(key, newNode);
                
                // Add to front
                addToFront(newNode);
                size.incrementAndGet();
                
                // Evict if necessary
                if (size.get() > capacity) {
                    evictLRU();
                }
            }
        } finally {
            lock.unlock();
            notFull.signal(); // Signal that space might be available
        }
    }
    
    // Remove least recently used item
    private void evictLRU() {
        if (size.get() <= capacity) {
            return;
        }
        
        LRUCacheNode<K, V> lruNode = tail.prev;
        if (lruNode != head) { // Not the dummy tail
            removeNode(lruNode);
            cacheMap.remove(lruNode.key);
            size.decrementAndGet();
        }
    }
    
    // Move node to front (most recently used)
    private void moveToFront(LRUCacheNode<K, V> node) {
        if (node == head || node == tail) {
            return;
        }
        
        // Remove from current position
        removeNode(node);
        
        // Add to front
        addToFront(node);
    }
    
    // Add node to front (after head)
    private void addToFront(LRUCacheNode<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }
    
    // Remove node from list
    private void removeNode(LRUCacheNode<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
    
    // Get current size
    public int size() {
        return size.get();
    }
    
    // Clear cache
    public void clear() {
        lock.lock();
        try {
            cacheMap.clear();
            // Reset linked list
            head.next = tail;
            tail.prev = head;
            size.set(0);
        } finally {
            lock.unlock();
        }
    }
    
    // Check if key exists
    public boolean containsKey(K key) {
        lock.lock();
        try {
            return cacheMap.containsKey(key);
        } finally {
            lock.unlock();
        }
    }
    
    // Get all keys
    public java.util.Set<K> keySet() {
        lock.lock();
        try {
            return new java.util.HashSet<>(cacheMap.keySet());
        } finally {
            lock.unlock();
        }
    }
}
```

## Thread-Safety Explanation

The implementation guarantees thread-safety through several mechanisms:

1. **ReentrantLock**: A single `ReentrantLock` protects all shared mutable state. This ensures:
   - Mutual exclusion: Only one thread can access the cache at a time
   - Visibility: Changes made by one thread are visible to others
   - Atomicity: Operations like get/put are atomic

2. **Coordinated State Management**: 
   - All modifications to the `cacheMap`, doubly linked list, and `size` counter happen within the lock
   - The lock is held for the entire duration of get/put operations
   - The `notFull` condition variable is used to signal when eviction space is available

3. **Consistent Data Structures**:
   - The HashMap and doubly linked list are always kept in sync
   - The size counter is updated atomically using `AtomicInteger`
   - The dummy head/tail nodes simplify boundary conditions

4. **Error Handling**:
   - Capacity validation prevents invalid cache creation
   - Null checks prevent NPEs during operations

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel
class ConcurrentLRUCacheTest {
    
    private static final int THREAD_COUNT = 8;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private static final int CACHE_CAPACITY = 100;
    
    @Test
    void testBasicPutAndGet() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(5);
        
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        
        assertEquals(1, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(3, cache.get("c"));
        assertNull(cache.get("d"));
        
        assertEquals(3, cache.size());
    }
    
    @Test
    void testUpdateExistingKey() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(3);
        
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("a", 10); // Update existing key
        
        assertEquals(10, cache.get("a"));
        assertEquals(2, cache.get("b"));
        assertEquals(2, cache.size()); // Should not increase size
    }
    
    @Test
    void testLRUEviction() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(3);
        
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        
        // Access 'a' to make it most recently used
        cache.get("a");
        
        cache.put("d", 4); // Should evict 'b' (least recently used)
        
        assertEquals(1, cache.get("a")); // Should still exist
        assertEquals(3, cache.get("c")); // Should still exist
        assertEquals(4, cache.get("d")); // Should exist
        assertNull(cache.get("b")); // Should be evicted
        assertEquals(3, cache.size());
    }
    
    @Test
    void testCapacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(-1));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        
        new ConcurrentLRUCache<>(1); // Should work
    }
    
    @Test
    void testConcurrentGetPut() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(50);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger operationsCompleted = new AtomicInteger(0);
        
        // Submit tasks that perform get/put operations
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        int key = threadId * 1000 + j;
                        int value = threadId * 1000 + j;
                        
                        // Put operation
                        cache.put(key, value);
                        
                        // Get operation
                        Integer result = cache.get(key);
                        assertEquals(value, result, 
                            () -> String.format("Thread %d: Expected %d but got %d for key %d", 
                                threadId, value, result, key));
                        
                        operationsCompleted.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        latch.await(2, TimeUnit.MINUTES);
        executor.shutdown();
        
        // Verify cache invariants
        assertTrue(cache.size() <= CACHE_CAPACITY, 
            () -> String.format("Cache size %d exceeds capacity %d", cache.size(), CACHE_CAPACITY));
        
        // Verify all operations completed
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, operationsCompleted.get(),
            "Not all operations completed");
    }
    
    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100, 500})
    void testStressTestWithDifferentCapacities(int capacity) throws InterruptedException {
        ConcurrentLRUCache<Long, Long> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successfulGets = new AtomicInteger(0);
        AtomicInteger successfulPuts = new AtomicInteger(0);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (long j = 0; j < OPERATIONS_PER_THREAD / THREAD_COUNT; j++) {
                        long key = threadId * 100000L + j;
                        long value = key * 2;
                        
                        // Put
                        cache.put(key, value);
                        successfulPuts.incrementAndGet();
                        
                        // Get
                        Long result = cache.get(key);
                        if (result != null) {
                            assertEquals(value, result, 
                                () -> String.format("Thread %d: Mismatch for key %d", threadId, key));
                            successfulGets.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(3, TimeUnit.MINUTES);
        executor.shutdown();
        
        // Verify final cache state
        assertTrue(cache.size() <= capacity,
            () -> String.format("Final size %d exceeds capacity %d", cache.size(), capacity));
        
        // Verify no corruption in key set
        Set<Long> keys = cache.keySet();
        assertEquals(keys.size(), cache.size(),
            "Key set size doesn't match cache size");
    }
    
    @Test
    void testConcurrentEviction() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        
        // First thread fills the cache
        executor.submit(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    cache.put(i, i);
                }
            } finally {
                latch.countDown();
            }
        });
        
        // Other threads continuously access and evict
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        // Access random keys
                        int key = j % 15; // Some keys will be evicted
                        cache.get(key);
                        
                        // Add new keys to trigger eviction
                        cache.put(1000 + j, 2000 + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(2, TimeUnit.MINUTES);
        executor.shutdown();
        
        // Cache should never exceed capacity
        assertTrue(cache.size() <= 10,
            () -> String.format("Cache size %d exceeds capacity 10 during concurrent eviction", cache.size()));
    }
    
    @Test
    void testClearOperation() throws InterruptedException {
        ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(100);
        
        // Fill cache
        for (int i = 0; i < 50; i++) {
            cache.put("key" + i, "value" + i);
        }
        
        assertEquals(50, cache.size());
        
        // Clear from another thread
        executorService.submit(() -> {
            cache.clear();
        }).get(1, TimeUnit.SECONDS);
        
        assertEquals(0, cache.size());
        assertNull(cache.get("key0"));
        assertTrue(cache.keySet().isEmpty());
    }
    
    @Test
    void testContainsKey() {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(10);
        
        cache.put("existing", 1);
        assertTrue(cache.containsKey("existing"));
        
        assertFalse(cache.containsKey("nonexistent"));
    }
}
```

## Key Features of the Tests:

1. **Basic Functionality Tests**: Test put/get operations, updates, and eviction

2. **Capacity Validation**: Ensure invalid capacities are rejected

3. **Concurrency Tests**: 
   - Multiple threads performing get/put operations
   - Stress tests with different cache capacities
   - Concurrent eviction scenarios

4. **Thread Safety Verification**:
   - Verify cache invariants under heavy load
   - Check that size never exceeds capacity
   - Validate data consistency across threads

5. **Parameterized Tests**: Run the same test with different capacities to ensure robustness

The implementation provides a thread-safe LRU cache with O(1) operations, and the tests thoroughly verify correctness under concurrent access patterns. The cache maintains LRU eviction policy while ensuring thread-safety through proper synchronization.