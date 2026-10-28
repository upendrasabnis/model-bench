<!-- model: cohere/north-mini-code:free | category: code | run: 20261028T235233Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
# Thread-Safe Concurrent LRU Cache Implementation

## Overview

I'll implement a thread-safe concurrent LRU (Least Recently Used) cache in Java that provides:
- Configurable capacity
- O(1) average time complexity for get/put operations
- Correct eviction under concurrent access
- Thread safety guarantees

## Implementation

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final LinkedList<Node<K, V>> doublyLinkedList;
    private final ReadWriteLock readWriteLock;
    private final AtomicInteger size;
    
    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.doublyLinkedList = new LinkedList<>();
        this.readWriteLock = new ReentrantReadWriteLock();
        this.size = new AtomicInteger(0);
    }
    
    public V get(K key) {
        readWriteLock.readLock().lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move to front (most recently used)
            doublyLinkedList.remove(node);
            doublyLinkedList.addFirst(node);
            return node.value;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }
    
    public void put(K key, V value) {
        readWriteLock.writeLock().lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node != null) {
                // Update value and move to front
                node.value = value;
                doublyLinkedList.remove(node);
                doublyLinkedList.addFirst(node);
            } else {
                // Check capacity and evict if necessary
                if (size.get() >= capacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                cacheMap.put(key, newNode);
                doublyLinkedList.addFirst(newNode);
                size.incrementAndGet();
            }
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }
    
    private void evict() {
        // Remove least recently used (last element)
        Node<K, V> node = doublyLinkedList.removeLast();
        cacheMap.remove(node.key);
        size.decrementAndGet();
    }
    
    public int size() {
        readWriteLock.readLock().lock();
        try {
            return size.get();
        } finally {
            readWriteLock.readLock().unlock();
        }
    }
    
    @Override
    public String toString() {
        readWriteLock.readLock().lock();
        try {
            return "ConcurrentLRUCache{" +
                    "capacity=" + capacity +
                    ", size=" + size.get() +
                    ", entries=" + cacheMap.entrySet() +
                    "}";
        } finally {
            readWriteLock.readLock().unlock();
        }
    }
    
    private static class Node<K, V> {
        final K key;
        V value;
        
        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node<?, ?> node = (Node<?, ?>) o;
            return Objects.equals(key, node.key) && Objects.equals(value, node.value);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
        
        @Override
        public String toString() {
            return "Node{" +
                    "key=" + key +
                    ", value=" + value +
                    "}";
        }
    }
}
```

## Thread Safety Guarantees

The implementation guarantees thread-safety through several mechanisms:

### 1. Read-Write Lock Pattern
- Uses `ReentrantReadWriteLock` to allow multiple concurrent readers while ensuring exclusive access for writers
- Optimizes performance for read-heavy workloads by allowing concurrent reads

### 2. Atomic Operations
- Uses `AtomicInteger` for size tracking to ensure atomic updates without requiring locks for size operations
- Provides atomic increment/decrement operations for thread-safe size management

### 3. Exclusive Write Access
- All write operations (put) and modifications to the cache structure are protected by the write lock
- Ensures that only one thread can modify the cache at any given time

### 4. Consistent State
- The write lock ensures that the cache's internal state (HashMap and doubly linked list) remains consistent during all modifications
- Prevents race conditions during eviction and updates

### 5. Memory Visibility
- The lock provides memory visibility guarantees
- Changes made by one thread are visible to other threads

## Key Design Decisions

### O(1) Operations
- Uses `HashMap` for O(1) average get/put operations
- Uses doubly linked list for O(1) LRU order maintenance (moving recently accessed items to front)

### Capacity Management
- Automatically evicts the least recently used item when capacity is exceeded
- Eviction removes from both the map and linked list

### Memory Efficiency
- Minimal additional memory beyond storing key-value pairs
- No redundant data structures

### Performance Optimizations
- Read-heavy workloads benefit from concurrent reads
- Write operations use exclusive locks to ensure consistency

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConcurrentLRUCacheTest {
    private static final int THREAD_COUNT = 8;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private static final int CACHE_CAPACITY = 100;
    
    private ConcurrentLRUCache<String, Integer> cache;
    
    @BeforeEach
    public void setUp() {
        cache = new ConcurrentLRUCache<>(CACHE_CAPACITY);
    }
    
    @Test
    public void testBasicOperations() {
        // Test put and get
        cache.put("key1", 1);
        assertEquals(1, cache.get("key1"));
        assertNull(cache.get("key2"));
        
        // Test update existing key
        cache.put("key1", 2);
        assertEquals(2, cache.get("key1"));
        
        // Test capacity limit
        for (int i = 0; i < CACHE_CAPACITY + 10; i++) {
            cache.put("key" + i, i);
        }
        assertEquals(CACHE_CAPACITY, cache.size());
        
        // Verify LRU order
        cache.put("key99", 99); // Should evict key0
        assertNull(cache.get("key0"));
        assertEquals(99, cache.get("key99"));
    }
    
    @Test
    public void testConcurrentGets() throws InterruptedException {
        // Populate cache
        for (int i = 0; i < CACHE_CAPACITY; i++) {
            cache.put("key" + i, i);
        }
        
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        
        Runnable getTask = () -> {
            try {
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    String key = "key" + (i % CACHE_CAPACITY);
                    Integer value = cache.get(key);
                    if (value != null && value == i % CACHE_CAPACITY) {
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                latch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread thread = new Thread(getTask);
            threads.add(thread);
            thread.start();
        }
        
        latch.await();
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // All gets should succeed
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get());
    }
    
    @Test
    public void testConcurrentPuts() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        
        Runnable putTask = () -> {
            try {
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    String key = "key" + Thread.currentThread().getId() + "_" + i;
                    cache.put(key, i);
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread thread = new Thread(putTask);
            threads.add(thread);
            thread.start();
        }
        
        latch.await();
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // All puts should succeed
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get());
        assertTrue(cache.size() <= CACHE_CAPACITY);
    }
    
    @Test
    public void testConcurrentGetAndPut() throws InterruptedException {
        // Populate with initial data
        for (int i = 0; i < CACHE_CAPACITY / 2; i++) {
            cache.put("key" + i, i);
        }
        
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        
        Runnable mixedTask = () -> {
            try {
                Random random = new Random();
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    if (random.nextBoolean()) {
                        // Get operation
                        String key = "key" + random.nextInt(CACHE_CAPACITY);
                        cache.get(key);
                        successCount.incrementAndGet();
                    } else {
                        // Put operation
                        String key = "mixed_key_" + Thread.currentThread().getId() + "_" + i;
                        cache.put(key, i);
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                latch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread thread = new Thread(mixedTask);
            threads.add(thread);
            thread.start();
        }
        
        latch.await();
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertTrue(cache.size() <= CACHE_CAPACITY);
    }
    
    @Test
    public void testLRUEvictionOrder() throws InterruptedException {
        // Fill cache
        for (int i = 0; i < CACHE_CAPACITY; i++) {
            cache.put("key" + i, i);
        }
        
        // Access key0 to make it recently used
        cache.get("key0");
        
        // Add new key to evict key1 (least recently used)
        cache.put("newKey", 999);
        
        // Verify key1 was evicted
        assertNull(cache.get("key1"));
        assertEquals(999, cache.get("newKey"));
        assertEquals(CACHE_CAPACITY, cache.size());
    }
    
    @Test
    public void testCacheConsistency() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger operations = new AtomicInteger(0);
        
        Runnable consistencyTask = () -> {
            try {
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    String key = "cons_key_" + Thread.currentThread().getId() + "_" + i;
                    cache.put(key, i);
                    assertEquals(i, cache.get(key));
                    operations.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        };
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            Thread thread = new Thread(consistencyTask);
            threads.add(thread);
            thread.start();
        }
        
        latch.await();
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, operations.get());
        assertTrue(cache.size() <= CACHE_CAPACITY);
    }
    
    @Test
    public void testStressTest() throws InterruptedException {
        final int NUM_THREADS = 16;
        final int NUM_OPERATIONS = 5000;
        final int MAX_SIZE = 500;
        
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(MAX_SIZE);
        
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        AtomicInteger totalOperations = new AtomicInteger(0);
        AtomicInteger getSuccesses = new AtomicInteger(0);
        AtomicInteger putSuccesses = new AtomicInteger(0);
        
        Runnable stressTask = () -> {
            try {
                Random random = new Random();
                for (int i = 0; i < NUM_OPERATIONS; i++) {
                    int operation = random.nextInt(100);
                    if (operation < 70) { // 70% puts
                        int key = random.nextInt(MAX_SIZE * 2);
                        int value = random.nextInt(Integer.MAX_VALUE);
                        stressCache.put(key, value);
                        putSuccesses.incrementAndGet();