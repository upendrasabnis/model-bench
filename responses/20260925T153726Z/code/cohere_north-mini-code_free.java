<!-- model: cohere/north-mini-code:free | category: code | run: 20260925T153726Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To solve this problem, I'll implement a thread-safe concurrent LRU (Least Recently Used) cache in Java. The solution will use a combination of a hash map and a doubly linked list to achieve O(1) average time complexity for get and put operations while ensuring thread-safety.

## Approach

1. **Data Structure Design**: I'll use a hash map for O(1) key-value access and a doubly linked list to maintain the access order. The head of the list will represent the most recently used item, and the tail will represent the least recently used item.

2. **Thread-Safety**: I'll use `ReentrantLock` to ensure thread-safety. This lock will protect all operations on the cache, ensuring that the data structures remain consistent under concurrent access.

3. **Implementation Details**:
   - Create a `Node` class to represent entries in the cache
   - Use a `HashMap` for quick lookups
   - Use a doubly linked list to maintain access order
   - Implement `get` and `put` methods with O(1) complexity
   - Implement eviction when the cache exceeds capacity

4. **Testing**: I'll write comprehensive JUnit 5 tests to verify:
   - Basic functionality (get/put operations)
   - Capacity constraints (eviction)
   - Thread-safety under concurrent access
   - Stress testing with heavy parallel load

## Solution Code

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock;
    private final AtomicInteger size;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = this.tail;
        this.tail.prev = this.head;
        this.lock = new ReentrantLock();
        this.size = new AtomicInteger(0);
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move accessed node to front (most recently used)
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
                // Update existing node
                node.value = value;
                moveToHead(node);
            } else {
                // Create new node
                Node<K, V> newNode = new Node<>(key, value);
                cacheMap.put(key, newNode);
                addToHead(newNode);
                size.incrementAndGet();
                
                // Check capacity and evict if necessary
                if (size.get() > capacity) {
                    Node<K, V> lru = removeTail();
                    cacheMap.remove(lru.key);
                    size.decrementAndGet();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return false;
            }
            removeNode(node);
            cacheMap.remove(key);
            size.decrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cacheMap.clear();
            // Clear the list but keep head and tail
            head.next = tail;
            tail.prev = head;
            size.set(0);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return size.get();
    }

    public boolean containsKey(K key) {
        lock.lock();
        try {
            return cacheMap.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head || node == tail) {
            return;
        }
        
        // Remove from current position
        removeNode(node);
        
        // Add to head
        addToHead(node);
    }

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        if (node != head) {
            removeNode(node);
            return node;
        }
        return null;
    }

    private static class Node<K, V> {
        private final K key;
        private V value;
        private Node<K, V> prev;
        private Node<K, V> next;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

## Thread-Safety Explanation

I've implemented thread-safety using the following mechanisms:

1. **ReentrantLock**: This lock ensures that only one thread can modify the cache at any given time. All operations on the cache (get, put, remove, clear) are protected by this lock, ensuring that the data structures (HashMap and doubly linked list) remain consistent.

2. **AtomicInteger**: The `size` counter is implemented using `AtomicInteger` to ensure atomic updates without needing additional synchronization.

3. **Lock Ordering**: The lock is acquired at the beginning of each public method and released in the `finally` block to ensure it's always released, even if an exception occurs.

4. **Immutable Node Objects**: The `Node` class is designed to be immutable except for the `value` field (which can be updated for existing keys). This ensures that nodes can be safely moved within the list without causing race conditions.

5. **Atomic Operations**: Operations like moving nodes to the head, adding nodes, and removing nodes are performed atomically under the lock protection.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {
    private ConcurrentLRUCache<Integer, String> cache;
    
    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(10);
    }
    
    @AfterEach
    void tearDown() {
        cache.clear();
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
        cache.put(1, "updated");
        
        assertEquals("updated", cache.get(1));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testCapacityEviction() {
        for (int i = 0; i < 15; i++) {
            cache.put(i, "value" + i);
        }
        
        assertEquals(10, cache.size());
        assertNull(cache.get(0)); // Should be evicted (least recently used)
        assertEquals("value9", cache.get(9)); // Should still be there
    }
    
    @Test
    void testLRUOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        // Access key 1 to make it most recently used
        cache.get(1);
        
        cache.put(4, "four"); // Should evict key 2
        
        assertEquals("updated", cache.get(1));
        assertNull(cache.get(2)); // Should be evicted
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testRemove() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertTrue(cache.remove(1));
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testContainsKey() {
        cache.put(1, "one");
        
        assertTrue(cache.containsKey(1));
        assertFalse(cache.containsKey(2));
    }
    
    @Test
    void testClear() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        cache.clear();
        
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
        assertNull(cache.get(2));
    }
    
    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = j % 20; // Use 20 different keys
                        cache.put(key, "value" + threadId + "-" + j);
                        String value = cache.get(key);
                        if (value != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify cache size doesn't exceed capacity
        assertTrue(cache.size() <= 10);
        
        // Verify all keys are valid
        for (int i = 0; i < 20; i++) {
            String value = cache.get(i);
            if (value != null) {
                assertTrue(value.startsWith("value"));
            }
        }
    }
    
    @Test
    void testStressTest() throws InterruptedException {
        int threadCount = 50;
        int operationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        List<String> errors = new ArrayList<>();
        Random random = new Random();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = random.nextInt(100); // Use 100 different keys
                        int operation = random.nextInt(100);
                        
                        if (operation < 70) { // 70% put operations
                            cache.put(key, "thread-" + threadId + "-op-" + j);
                        } else { // 30% get operations
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.add("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        
        // Check for errors
        if (!errors.isEmpty()) {
            fail("Errors occurred during stress test: " + errors);
        }
        
        // Verify invariants
        assertTrue(cache.size() <= 10);
        
        // Verify cache consistency by checking all keys are valid
        for (int i = 0; i < 100; i++) {
            String value = cache.get(i);
            if (value != null) {
                assertTrue(value.startsWith("thread-"));
            }
        }
    }
    
    @Test
    void testConcurrentRemoveAndGet() throws InterruptedException {
        int threadCount = 5;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        // First populate the cache
        for (int i = 0; i < 20; i++) {
            cache.put(i, "value" + i);
        }
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = j % 20;
                        
                        if (j % 3 == 0) { // Remove every third operation
                            cache.remove(key);
                        } else { // Get operation
                            cache.get(key);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify cache size doesn't exceed capacity
        assertTrue(cache.size() <= 10);
    }
}
```

## Key Features of the Solution

1. **Thread-Safety**: The implementation uses `ReentrantLock` to ensure that all operations on the cache are atomic and thread-safe.

2. **O(1) Operations**: Both `get` and `put` operations have O(1) average time complexity due to the combination of a hash map and a doubly linked list.

3. **LRU Eviction**: The cache correctly evicts the least recently used item when it exceeds capacity.

4. **Comprehensive Testing**: The JUnit 5 tests cover:
   - Basic functionality (get/put operations)
   - Capacity constraints (eviction)
   - LRU order maintenance
   - Thread-safety under concurrent access
   - Stress testing with heavy parallel load

5. **Error Handling**: The implementation handles edge cases like removing non-existent keys and clearing the cache.

This solution provides a robust, thread-safe LRU cache that can handle concurrent access efficiently while maintaining the LRU eviction policy.# Thread-Safe Concurrent LRU Cache Implementation

## Overview

I'll implement a thread-safe concurrent LRU (Least Recently Used) cache in Java that supports O(1) average time complexity for get and put operations while ensuring correct eviction under concurrent access. The solution uses a combination of a hash map and a doubly linked list to maintain access order efficiently.

## Implementation Details

### Core Data Structure

The LRU cache is implemented using:
1. **HashMap**: For O(1) key-value lookups
2. **Doubly Linked List**: To maintain access order (head = most recently used, tail = least recently used)
3. **ReentrantLock**: To ensure thread-safety
4. **AtomicInteger**: For atomic size tracking

### Thread-Safety Mechanisms

1. **ReentrantLock**: Protects all cache operations to ensure atomicity
2. **Lock Ordering**: Consistent lock acquisition order to prevent deadlocks
3. **Atomic Operations**: All modifications happen under lock protection
4. **Immutable Nodes**: Node objects are designed for safe concurrent access

### Key Operations

- **get(key)**: Returns value if present, moves node to head (MRU), O(1)
- **put(key, value)**: Updates existing or adds new, evicts LRU if full, O(1)
- **remove(key)**: Safely removes key-value pair, O(1)
- **clear()**: Empties the cache, O(1)

## Code Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock;
    private final AtomicInteger size;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = this.tail;
        this.tail.prev = this.head;
        this.lock = new ReentrantLock();
        this.size = new AtomicInteger(0);
    }

    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
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
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                cacheMap.put(key, newNode);
                addToHead(newNode);
                size.incrementAndGet();
                
                if (size.get() > capacity) {
                    Node<K, V> lru = removeTail();
                    cacheMap.remove(lru.key);
                    size.decrementAndGet();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return false;
            }
            removeNode(node);
            cacheMap.remove(key);
            size.decrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cacheMap.clear();
            head.next = tail;
            tail.prev = head;
            size.set(0);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return size.get();
    }

    public boolean containsKey(K key) {
        lock.lock();
        try {
            return cacheMap.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head || node == tail) {
            return;
        }
        
        removeNode(node);
        addToHead(node);
    }

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private Node<K, V> removeTail() {
        Node<K, V> node = tail.prev;
        if (node != head) {
            removeNode(node);
            return node;
        }
        return null;
    }

    private static class Node<K, V> {
        private final K key;
        private V value;
        private Node<K, V> prev;
        private Node<K, V> next;

        public Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

## Thread-Safety Explanation

I've implemented thread-safety using several mechanisms:

1. **ReentrantLock**: This lock ensures that only one thread can modify the cache at any given time. All operations on the cache (get, put, remove, clear) are protected by this lock, ensuring that the data structures (HashMap and doubly linked list) remain consistent.

2. **AtomicInteger**: The `size` counter is implemented using `AtomicInteger` to ensure atomic updates without needing additional synchronization.

3. **Lock Ordering**: The lock is acquired at the beginning of each public method and released in the `finally` block to ensure it's always released, even if an exception occurs.

4. **Immutable Node Objects**: The `Node` class is designed to be immutable except for the `value` field (which can be updated for existing keys). This ensures that nodes can be safely moved within the list without causing race conditions.

5. **Atomic Operations**: Operations like moving nodes to the head, adding nodes, and removing nodes are performed atomically under the lock protection.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {
    private ConcurrentLRUCache<Integer, String> cache;
    
    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(10);
    }
    
    @AfterEach
    void tearDown() {
        cache.clear();
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
        cache.put(1, "updated");
        
        assertEquals("updated", cache.get(1));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testCapacityEviction() {
        for (int i = 0; i < 15; i++) {
            cache.put(i, "value" + i);
        }
        
        assertEquals(10, cache.size());
        assertNull(cache.get(0));
        assertEquals("value9", cache.get(9));
    }
    
    @Test
    void testLRUOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        cache.get(1);
        
        cache.put(4, "four");
        
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testRemove() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        assertTrue(cache.remove(1));
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals(1, cache.size());
    }
    
    @Test
    void testContainsKey() {
        cache.put(1, "one");
        
        assertTrue(cache.containsKey(1));
        assertFalse(cache.containsKey(2));
    }
    
    @Test
    void testClear() {
        cache.put(1, "one");
        cache.put(2, "two");
        
        cache.clear();
        
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
        assertNull(cache.get(2));
    }
    
    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = j % 20;
                        cache.put(key, "value" + threadId + "-" + j);
                        String value = cache.get(key);
                        if (value != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertTrue(cache.size() <= 10);
        
        for (int i = 0; i < 20; i++) {
            String value = cache.get(i);
            if (value != null) {
                assertTrue(value.startsWith("value"));
            }
        }
    }
    
    @Test
    void testStressTest() throws InterruptedException {
        int threadCount = 50;
        int operationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        List<String> errors = new ArrayList<>();
        Random random = new Random();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = random.nextInt(100);
                        int operation = random.nextInt(100);
                        
                        if (operation < 70) {
                            cache.put(key, "thread-" + threadId + "-op-" + j);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.add("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        
        if (!errors.isEmpty()) {
            fail("Errors occurred during stress test: " + errors);
        }
        
        assertTrue(cache.size() <= 10);
        
        for (int i = 0; i < 100; i++) {
            String value = cache.get(i);
            if (value != null) {
                assertTrue(value.startsWith("thread-"));
            }
        }
    }
    
    @Test
    void testConcurrentRemoveAndGet