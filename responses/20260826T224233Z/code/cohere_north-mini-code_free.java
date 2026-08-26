<!-- model: cohere/north-mini-code:free | category: code | run: 20260826T224233Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
# Thread-Safe Concurrent LRU Cache Implementation

Here's a complete implementation of a thread-safe concurrent LRU cache in Java with O(1) average operations:

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final Lock lock;
    
    public LRUCache(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative");
        }
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
        this.lock = new ReentrantLock(true); // Fair lock for FIFO ordering
    }
    
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move to front (most recently used)
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }
    
    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node != null) {
                // Update value and move to head
                node.value = value;
                moveToHead(node);
            } else {
                // Create new node
                Node<K, V> newNode = new Node<>(key, value);
                cacheMap.put(key, newNode);
                addToHead(newNode);
                
                // Check capacity
                if (cacheMap.size() > capacity) {
                    Node<K, V> tailNode = removeTail();
                    cacheMap.remove(tailNode.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    private void moveToHead(Node<K, V> node) {
        if (node == head.next) {
            return; // Already at head
        }
        
        // Remove from current position
        node.prev.next = node.next;
        node.next.prev = node.prev;
        
        // Add to head
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    
    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    
    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        if (node != head) {
            node.prev.next = tail;
            tail.prev = node.prev;
        }
        return node;
    }
    
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
    
    public int size() {
        lock.lock();
        try {
            return cacheMap.size();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean isEmpty() {
        lock.lock();
        try {
            return cacheMap.isEmpty();
        } finally {
            lock.unlock();
        }
    }
}
```

## Thread-Safety Guarantees

The implementation ensures thread-safety through several key mechanisms:

### 1. **Mutual Exclusion with ReentrantLock**
- Uses a single `ReentrantLock` to synchronize all cache operations
- Ensures that only one thread can modify the cache state at any time
- Prevents race conditions and data corruption

### 2. **Fair Lock Policy**
```java
this.lock = new ReentrantLock(true); // Fair lock for FIFO ordering
```
- The `true` parameter enables fairness, preventing thread starvation
- Threads acquire locks in the order they requested them
- Ensures predictable behavior under concurrent load

### 3. **Atomic Operations**
- All cache modifications (get, put, eviction) are performed atomically
- Linked list operations (moveToHead, addToHead, removeTail) are protected by the same lock
- Map operations (put, get, remove) are also synchronized

### 4. **Proper Resource Management**
- Locks are always released in `finally` blocks
- Prevents deadlocks even if exceptions occur
- Ensures thread pool safety

## JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class LRUCacheTest {
    
    @Test
    void testBasicOperations() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertNull(cache.get(4));
        
        cache.put(4, "four"); // Should evict 1
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
        
        // Access 2 to make it most recently used
        cache.get(2);
        cache.put(5, "five"); // Should evict 3
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
        assertEquals("four", cache.get(4));
        assertEquals("five", cache.get(5));
    }
    
    @Test
    void testUpdateExistingKey() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(1, "newOne"); // Update existing key
        
        assertEquals("newOne", cache.get(1));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testEvictionOrder() {
        LRUCache<Integer, String> cache = new LRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Access 1 to make it recently used
        cache.get(1);
        
        cache.put(4, "four"); // Should evict 2 (least recently used)
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testThreadSafety() throws InterruptedException {
        LRUCache<Integer, Integer> cache = new LRUCache<>(100);
        int numThreads = 10;
        int operationsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = threadId * 100 + j % 50; // Some overlap
                        int value = threadId * 1000 + j;
                        
                        cache.put(key, value);
                        
                        // Verify we can read back what we put
                        Integer retrieved = cache.get(key);
                        if (retrieved != null && retrieved == value) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        // At least some operations should have succeeded
        assertTrue(successCount.get() > 0);
        // Cache should not exceed capacity
        assertTrue(cache.size() <= 100);
    }
    
    @Test
    @Execution(ExecutionMode.CONCURRENT)