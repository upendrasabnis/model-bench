<!-- model: cohere/north-mini-code:free | category: code | run: 20260820T052305Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
# Thread-Safe Concurrent LRU Cache Implementation

Here's a complete implementation of a thread-safe concurrent LRU cache in Java with O(1) operations and comprehensive JUnit 5 tests:

```java
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.*;

class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> cache;
    private final ReentrantLock lock;
    private final Condition notFull;
    private Node<K, V> head;
    private Node<K, V> tail;
    
    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new HashMap<>();
        this.lock = new ReentrantLock();
        this.notFull = lock.newCondition();
    }
    
    public V get(K key) {
        lock.lock();
        try {
            Node<K, V> node = cache.get(key);
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
            Node<K, V> node = cache.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                cache.put(key, newNode);
                addToHead(newNode);
                
                if (cache.size() > capacity) {
                    Node<K, V> evicted = removeTail();
                    cache.remove(evicted.key);
                }
            }
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }
    
    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        
        if (node.prev != null) {
            node.prev.next = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        }
        if (node == tail) {
            tail = node.prev;
        }
        
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
    }
    
    private void addToHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }
    
    private Node<K, V> removeTail() {
        if (tail == null) {
            return null;
        }
        
        Node<K, V> node = tail;
        if (tail.prev != null) {
            tail = tail.prev;
            tail.next = null;
        } else {
            head = tail = null;
        }
        return node;
    }
    
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
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
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentLRUCacheTest {
    private ConcurrentLRUCache<Integer, String> cache;
    
    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }
    
    @Test
    void testBasicPutAndGet() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
    }
    
    @Test
    void testUpdateExistingKey() {
        cache.put(1, "one");
        cache.put(1, "updated");
        assertEquals("updated", cache.get(1));
    }
    
    @Test
    void testEviction() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        assertEquals(3, cache.size());
        
        cache.put(4, "four");
        
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testLRUOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        
        cache.get(1);
        
        cache.put(4, "four");
        
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }
    
    @Test
    void testConcurrentPutGet() throws InterruptedException {
        int numThreads = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = j % 5;
                        String value = "thread-" + threadId + "-op-" + j;
                        
                        cache.put(key, value);
                        
                        String retrieved = cache.get(key);
                        if (retrieved != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(1, TimeUnit.MINUTES);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        assertEquals(5, cache.size());
        
        for (int i = 0; i < 5; i++) {
            assertNotNull(cache.get(i));
        }
        
        System.out.println("Concurrent operations completed successfully");
        System.out.println("Total successful gets: " + successCount.get());
    }
    
    @Test
    void testStressConcurrency() throws InterruptedException {
        int numThreads = 50;
        int operationsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger putCount = new AtomicInteger(0);
        AtomicInteger getCount = new AtomicInteger(0);
        AtomicInteger missCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    Random random = new Random();
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (random.nextDouble() < 0.7) {
                            int key = random.nextInt(20);
                            String value = "value-" + key + "-t" + threadId + "-" + j;
                            cache.put(key, value);
                            putCount.incrementAndGet();
                        } else {
                            int key = random.nextInt(20);
                            String result = cache.get(key);
                            getCount.incrementAndGet();
                            if (result == null) {
                                missCount.incrementAndGet();
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(2, TimeUnit.MINUTES);
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.MINUTES);
        
        assertTrue(cache.size() <= 3);
        
        assertEquals(numThreads * operationsPerThread, putCount.get() + getCount.get());
        
        for (int i = 0; i < 20; i++) {
            assertNotNull(cache.get(i));
        }
        
        System.out.println("Stress test completed");
        System.out.println("Puts: " + putCount.get() + ", Gets: " + getCount.get() + ", Misses: " + missCount.get());
    }
    
    @Test
    void testCapacityOne() {
        ConcurrentLRUCache<Integer, String> singleCache = new ConcurrentLRUCache<>(1);
        singleCache.put(1, "one");
        assertEquals("one", singleCache.get(1));
        
        singleCache.put(2, "two");
        assertNull(singleCache.get(1));
        assertEquals("two", singleCache.get(2));
    }
    
    @Test
    void testEmptyCache() {
        ConcurrentLRUCache<Integer, String> emptyCache = new ConcurrentLRUCache<>(5);
        assertNull(emptyCache.get(1));
        assertEquals(0, emptyCache.size());
    }
}

public class Main {
    public static void main(String[] args) {
        org.junit.platform.console.ConsoleLauncher.main(args);
    }
}
```

## Thread-Safety Guarantees

The implementation ensures thread-safety through several mechanisms:

### 1. **Exclusive Locking**
- Uses `ReentrantLock` to protect all shared state
- All operations (`get`, `put`, `size`) acquire the lock before accessing shared data
- Lock is held for the entire duration of each operation to ensure atomicity

### 2. **Protected Shared State**
- `cache` HashMap: Only accessible within lock scope
- Doubly linked list (`head`, `tail`, node pointers): Only modified within lock
- All node operations (moveToHead, addToHead, removeTail) are synchronized

### 3. **Atomic Operations**
- Complete `get` and `put` operations are atomic
- LRU ordering updates happen atomically with value updates
- Eviction happens atomically with insertion

### 4. **Memory Visibility**
- Lock ensures proper memory visibility
- Changes made by one thread are visible to others after lock release
- No stale reads or writes occur

## Key Design Features

### O(1) Operations
- **Hash Map**: Provides O(1) average lookup for `get` operations
- **Doubly Linked List**: Allows O(1) insertion/removal at both ends
- **LRU Updates**: Moving nodes to head is O(1) with direct pointer manipulation

### LRU Implementation
- **Head**: Most recently used items
- **Tail**: Least recently used items
- **Eviction**: When capacity exceeded, tail node is removed

### Capacity Handling
- Configurable capacity at construction
- Automatic eviction when exceeding capacity
- Maintains exact capacity at all times

## JUnit 5 Test Coverage

### 1. **Basic Functionality Tests**
- `testBasicPutAndGet`: Verifies fundamental put/get operations
- `testUpdateExistingKey`: Tests updating existing keys
- `testEmptyCache`: Tests behavior on empty cache

### 2. **LRU Ordering Tests**
- `testEviction`: Verifies correct eviction when full
- `testLRUOrder`: Confirms LRU ordering works correctly
- `testCapacityOne`: Edge case with single capacity

### 3. **Concurrency Tests**
- `testConcurrentPutGet`: Multiple threads performing puts and gets
- `testStressConcurrency`: Heavy parallel load test with 50 threads

### 4. **Stress Test Features**
- 70% puts, 30% gets mixed workload
- 20 unique keys with 3-slot capacity
- Validates invariants under extreme concurrency
- Checks cache size never exceeds capacity
- Verifies all operations complete without corruption

## Running the Tests

To run the tests:

```bash
# Using Maven
mvn test

# Using Gradle
./gradlew test

# Using JUnit Console
javac ConcurrentLRUCacheTest.java Main.java
java -cp . org.junit.platform.console.ConsoleLauncher --select-class ConcurrentLRUCacheTest
```

The implementation provides a robust, thread-safe LRU cache that maintains correctness under concurrent access while delivering O(1) performance characteristics. The comprehensive test suite validates both functional correctness and thread-safety under various concurrency scenarios.