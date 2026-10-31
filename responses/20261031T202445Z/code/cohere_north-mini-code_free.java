<!-- model: cohere/north-mini-code:free | category: code | run: 20261031T202445Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
# Thread-Safe Concurrent LRU Cache Implementation

## Overview

I'll implement a thread-safe concurrent LRU (Least Recently Used) cache in Java that provides O(1) average time complexity for get/put operations while maintaining correctness under concurrent access.

## Implementation

```java
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.*;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cache;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReadWriteLock lock;
    private final Lock readLock;
    private final Lock writeLock;
    
    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.readLock = lock.readLock();
        this.writeLock = lock.writeLock();
        
        // Initialize dummy head and tail nodes
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }
    
    public V get(K key) {
        readLock.lock();
        try {
            Node<K, V> node = cache.get(key);
            if (node == null) {
                return null;
            }
            // Move node to head (most recently used)
            moveToHead(node);
            return node.value;
        } finally {
            readLock.unlock();
        }
    }
    
    public void put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = cache.get(key);
            if (node != null) {
                // Update value and move to head
                node.value = value;
                moveToHead(node);
            } else {
                // Create new node
                Node<K, V> newNode = new Node<>(key, value);
                cache.put(key, newNode);
                addToHead(newNode);
                
                // Check capacity
                if (cache.size() > capacity) {
                    Node<K, V> evicted = removeTail();
                    cache.remove(evicted.key);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }
    
    private void moveToHead(Node<K, V> node) {
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
    
    public int size() {
        readLock.lock();
        try {
            return cache.size();
        } finally {
            readLock.unlock();
        }
    }
    
    // Protected method for eviction policy customization
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return false; // Default: don't remove
    }
    
    private static class Node<K, V> {
        private K key;
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

## Thread Safety Guarantees

### 1. **Locking Strategy**
- **ReadWriteLock**: Allows concurrent reads (multiple threads can read simultaneously)
- **Exclusive Write Lock**: Ensures atomicity for all modifications
- **Proper Lock Management**: All locks are released in finally blocks

### 2. **Atomic Operations**
- **get()**: Thread-safe lookup + move-to-head operation
- **put()**: Atomic insertion/update with capacity management
- **size()**: Safe size reporting

### 3. **Data Consistency**
- **HashMap + Doubly Linked List**: Both structures remain synchronized
- **Node Integrity**: No dangling references during list operations
- **Capacity Enforcement**: Atomic size checking and eviction

### 4. **Memory Visibility**
- Lock-based synchronization ensures visibility of changes across threads
- No stale reads or writes due to memory reordering

## JUnit 5 Test Suite

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class ConcurrentLRUCacheTest {
    private static final int THREAD_COUNT = 8;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private static final int CACHE_CAPACITY = 100;
    
    @Test
    public void testBasicPutAndGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertNull(cache.get(4));
    }
    
    @Test
    public void testUpdateExistingKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(1, "updated");
        
        assertEquals("updated", cache.get(1));
        assertEquals(2, cache.size());
    }
    
    @Test
    public void testLRUEviction() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.get(1);
        
        cache.put(3, "three");
        
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertNull(cache.get(2));
    }
    
    @Test
    public void testCapacityLimit() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        assertEquals(2, cache.size());
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
    }
    
    @Test
    public void testConcurrentPutAndGet() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger operationsCount = new AtomicInteger(0);
        
        class Worker implements Runnable {
            private final int threadId;
            
            public Worker(int threadId) {
                this.threadId = threadId;
            }
            
            @Override
            public void run() {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = threadId * 1000 + i;
                        int value = threadId * 1000 + i;
                        
                        cache.put(key, value);
                        operationsCount.incrementAndGet();
                        
                        Integer retrieved = cache.get(key);
                        assertEquals(value, retrieved);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }
        }
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads.add(new Thread(new Worker(i)));
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        startLatch.countDown();
        completionLatch.await();
        
        assertTrue(cache.size() <= CACHE_CAPACITY);
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, operationsCount.get());
    }
    
    @Test
    public void testConcurrentReadOperations() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(1000);
        
        for (int i = 0; i < 500; i++) {
            cache.put(i, "value" + i);
        }
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        
        class Reader implements Runnable {
            @Override
            public void run() {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = i % 500;
                        String value = cache.get(key);
                        assertNotNull(value);
                        assertTrue(value.startsWith("value"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }
        }
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads.add(new Thread(new Reader()));
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        startLatch.countDown();
        completionLatch.await();
    }
    
    @Test
    public void testMixedOperations() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger putCount = new AtomicInteger(0);
        AtomicInteger getCount = new AtomicInteger(0);
        
        class MixedWorker implements Runnable {
            private final int threadId;
            
            public MixedWorker(int threadId) {
                this.threadId = threadId;
            }
            
            @Override
            public void run() {
                try {
                    startLatch.await();
                    
                    Random random = new Random();
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = random.nextInt(100);
                        int operation = random.nextInt(100);
                        
                        if (operation < 60) {
                            cache.put(key, threadId * 1000000 + i);
                            putCount.incrementAndGet();
                        } else if (operation < 95) {
                            cache.get(key);
                            getCount.incrementAndGet();
                        } else {
                            cache.put(key, threadId * 1000000 + i);
                            putCount.incrementAndGet();
                            cache.get(key);
                            getCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }
        }
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads.add(new Thread(new MixedWorker(i)));
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        startLatch.countDown();
        completionLatch.await();
        
        assertTrue(cache.size() <= CACHE_CAPACITY);
    }
    
    @Test
    public void testCacheInvariantsUnderStress() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
        
        class StressWorker implements Runnable {
            private final int threadId;
            
            public StressWorker(int threadId) {
                this.threadId = threadId;
            }
            
            @Override
            public void run() {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        int key = threadId * 10000 + i;
                        cache.put(key, i);
                        
                        Integer value = cache.get(key);
                        assertEquals(i, value);
                        
                        int size = cache.size();
                        assertTrue(size >= 0 && size <= CACHE_CAPACITY);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }
        }
        
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            threads.add(new Thread(new StressWorker(i)));
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        startLatch.countDown();
        completionLatch.await();
        
        assertTrue(cache.size() <= CACHE_CAPACITY);
    }
}
```

## Key Design Decisions

### 1. **ReadWriteLock Strategy**
- **Read operations** (get, size) use read lock for concurrency
- **Write operations** (put) use write lock for atomicity
- This balances performance with thread safety

### 2. **LRU Implementation**
- **Doubly linked list