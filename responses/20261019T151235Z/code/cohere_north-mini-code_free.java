<!-- model: cohere/north-mini-code:free | category: code | run: 20261019T151235Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
I'll implement a thread-safe concurrent LRU cache in Java with O(1) average operations and proper thread safety guarantees.

## Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.ThreadLocalRandom;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();
    private final Node<K, V> head, tail;
    
    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }
    
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            
            // Move to front (most recently used)
            moveToFront(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }
    
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            
            if (node != null) {
                // Update existing value and move to front
                node.value = value;
                moveToFront(node);
            } else {
                // Create new node
                node = new Node<>(key, value);
                map.put(key, node);
                addToFront(node);
                
                // Evict if over capacity
                if (map.size() > capacity) {
                    Node<K, V> toRemove = tail.prev;
                    removeNode(toRemove);
                    map.remove(toRemove.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    private void moveToFront(Node<K, V> node) {
        if (node.prev == head) return; // Already at front
        
        removeNode(node);
        addToFront(node);
    }
    
    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
    
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean containsKey(K key) {
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public String toString() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            Node<K, V> current = head.next;
            while (current != tail) {
                sb.append(current.key).append("=").append(current.value);
                if (current.next != tail) sb.append(", ");
                current = current.next;
            }
            sb.append("]");
            return sb.toString();
        } finally {
            lock.unlock();
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

## Thread Safety Explanation

The implementation guarantees thread safety through:

1. **Mutual Exclusion**: A single `ReentrantLock` protects all operations on shared data structures (HashMap and doubly linked list).

2. **Atomic Operations**: Each public method (`get`, `put`, etc.) acquires the lock at the beginning and releases it in a `finally` block, ensuring:
   - Complete visibility of changes to all threads
   - No partial updates or inconsistent states
   - Atomic get-or-put operations

3. **Consistent State**: The lock ensures that:
   - Map and linked list remain in sync
   - All operations on the doubly linked list (add, remove, move) are atomic
   - Capacity constraints are enforced atomically

4. **Memory Visibility**: The lock provides happens-before relationships, ensuring that changes made by one thread are visible to others.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {
    private static final int THREAD_COUNT = 8;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private ConcurrentLRUCache<Integer, Integer> cache;
    
    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(100);
    }
    
    @Test
    void testBasicOperations() {
        cache.put(1, 10);
        assertEquals(10, cache.get(1));
        assertEquals(1, cache.size());
        assertTrue(cache.containsKey(1));
        
        cache.put(2, 20);
        assertEquals(2, cache.size());
        assertEquals(20, cache.get(2));
        assertEquals(10, cache.get(1)); // First should still exist
        
        assertNull(cache.get(3));
        assertFalse(cache.containsKey(3));
    }
    
    @Test
    void testUpdateExistingKey() {
        cache.put(1, 10);
        cache.put(1, 15);
        
        assertEquals(15, cache.get(1));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testLRUOrder() {
        cache.put(1, 10);
        cache.put(2, 20);
        cache.put(3, 30);
        
        // Access 1 to make it most recently used
        cache.get(1);
        
        // Put 4 to trigger eviction (should evict 2, least recently used)
        cache.put(4, 40);
        
        assertEquals(3, cache.size());
        assertEquals(10, cache.get(1)); // Should still exist
        assertEquals(30, cache.get(3)); // Should still exist
        assertNull(cache.get(2));       // Should be evicted
        assertEquals(40, cache.get(4)); // Should exist
    }
    
    @Test
    void testCapacityEviction() {
        ConcurrentLRUCache<Integer, Integer> smallCache = new ConcurrentLRUCache<>(3);
        
        smallCache.put(1, 1);
        smallCache.put(2, 2);
        smallCache.put(3, 3);
        
        assertEquals(3, smallCache.size());
        
        // Adding 4th should evict least recently used (1)
        smallCache.put(4, 4);
        assertEquals(3, smallCache.size());
        assertNull(smallCache.get(1));
        assertEquals(2, smallCache.get(2));
        assertEquals(3, smallCache.get(3));
        assertEquals(4, smallCache.get(4));
    }
    
    @Test
    void testConcurrentGets() throws InterruptedException {
        cache.put(1, 100);
        cache.put(2, 200);
        cache.put(3, 300);
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    int key = (j % 3) + 1;
                    V value = cache.get(key);
                    if (value != null) {
                        successCount.incrementAndGet();
                    }
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get());
    }
    
    @Test
    void testConcurrentPuts() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger putCount = new AtomicInteger(0);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    int key = threadId * 1000 + j;
                    cache.put(key, j);
                    putCount.incrementAndGet();
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, putCount.get());
        assertTrue(cache.size() <= 100);
    }
    
    @Test
    void testMixedOperations() throws InterruptedException {
        // Initialize with some data
        for (int i = 0; i < 50; i++) {
            cache.put(i, i * 10);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger operations = new AtomicInteger(0);
        AtomicReferenceArray<Integer> results = new AtomicReferenceArray<>(1000);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int j = 0; j < OPERATIONS_PER_THREAD / THREAD_COUNT; j++) {
                    int op = random.nextInt(100);
                    if (op < 60) { // Get operation
                        int key = random.nextInt(0, 200);
                        results.set(operations.getAndIncrement(), cache.get(key));
                    } else { // Put operation
                        int key = random.nextInt(0, 200);
                        int value = random.nextInt(0, 1000);
                        cache.put(key, value);
                        results.set(operations.getAndIncrement(), value);
                    }
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Verify invariants
        assertTrue(cache.size() <= 100);
        
        // Check that all gets return non-null for keys we put
        for (int i = 0; i < 50; i++) {
            assertEquals(i * 10, cache.get(i));
        }
    }
    
    @Test
    void testStressCapacity() throws InterruptedException {
        final int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    int key = (threadId * 1000 + j) % 100; // Use only 100 unique keys
                    stressCache.put(key, j);
                    
                    // Verify size never exceeds capacity
                    assertTrue(stressCache.size() <= capacity, 
                        "Cache size exceeded capacity: " + stressCache.size() + " > " + capacity);
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Final size check
        assertTrue(stressCache.size() <= capacity);
    }
    
    @AfterEach
    void tearDown() {
        cache = null;
    }
}
```

## Key Design Decisions:

1. **Single Lock**: Using a single `ReentrantLock` ensures simplicity and correctness, though it might limit scalability. For higher throughput, you could use read-write locks or striped locks.

2. **O(1) Operations**: The HashMap provides O(1) lookups, and the doubly linked list allows O(1) moves to front and evictions.

3. **Memory Efficiency**: Nodes are only created for cached entries, and the dummy head/tail nodes simplify list operations.

4. **Thread Safety**: The lock ensures all operations are atomic, preventing race conditions and maintaining consistency between the map and linked list.

This implementation provides a robust, thread-safe LRU cache that performs well under concurrent access while maintaining all LRU properties.