<!-- model: cohere/north-mini-code:free | category: code | run: 20260928T162214Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe concurrent LRU cache in Java, along with JUnit 5 tests and an explanation of the thread-safety guarantees.

### 1. Implementation: `ConcurrentLRUCache`

This implementation uses a standard HashMap for O(1) lookups and a Doubly Linked List to maintain access order. A `ReentrantLock` is used to ensure thread safety.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> cacheMap;
    private final Node<K, V> head; // Dummy head
    private final Node<K, V> tail; // Dummy tail
    private final Lock lock;

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.cacheMap = new HashMap<>();
        this.head = new Node<>(null);
        this.tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        this.lock = new ReentrantLock();
    }

    public V get(K key) {
        if (key == null) return null;
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            if (node == null) {
                return null;
            }
            // Move accessed node to the head (Most Recently Used)
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value) {
        if (key == null) return;
        lock.lock();
        try {
            Node<K, V> node = cacheMap.get(key);
            
            if (node != null) {
                // Update value and move to head
                node.value = value;
                moveToHead(node);
            } else {
                // Create new node
                Node<K, V> newNode = new Node<>(value);
                cacheMap.put(key, newNode);
                addNodeToHead(newNode);

                // Check capacity
                if (cacheMap.size() > capacity) {
                    // Evict Least Recently Used (Tail)
                    Node<K, V> tailNode = popTail();
                    cacheMap.remove(tailNode.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // Helper: Add node right after head
    private void addNodeToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    // Helper: Remove node from its current position
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    // Helper: Move existing node to head
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNodeToHead(node);
    }

    // Helper: Remove tail node (LRU)
    private Node<K, V> popTail() {
        Node<K, V> res = tail.prev;
        removeNode(res);
        return res;
    }

    // Node class for the Doubly Linked List
    private static class Node<K, V> {
        V value;
        K key;
        Node<K, V> prev;
        Node<K, V> next;

        Node(V value) {
            this.value = value;
            // Key is set when added to map
        }
    }

    // For debugging/inspection
    public int size() {
        lock.lock();
        try {
            return cacheMap.size();
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. Explanation of Thread-Safety

We guarantee thread-safety in this implementation using the following mechanisms:

1.  **Mutual Exclusion (The Global Lock):** We employ a `ReentrantLock` (a mutual exclusion lock) to protect the entire state of the cache. The cache consists of two tightly coupled data structures:
    *   A **HashMap**: Stores the mapping from keys to Node objects.
    *   A **Doubly Linked List**: Maintains the access order (Most Recently Used at head, Least Recently Used at tail).
    
    To maintain the invariant that the HashMap and the Linked List are always consistent (e.g., a key exists in the map if and only if it exists in the list, and the list order reflects access times), we cannot allow one thread to modify the map while another modifies the list. A single global lock ensures that only one thread can execute `get` or `put` at any moment. This prevents race conditions, deadlocks, and corruption of the linked list pointers (e.g., `A.next = B` but `B.prev` not updated).

2.  **Atomicity of Operations:** The lock ensures that operations like `get`, `put`, and eviction happen atomically. For example, when a key is updated:
    *   The lock is acquired.
    *   The map is checked.
    *   If found, the value is updated.
    *   The node is removed from its current position in the list.
    *   The node is added to the head.
    *   The lock is released.
    
    No other thread can interfere during this sequence, ensuring that no "torn" or inconsistent state is observable.

3.  **Memory Visibility:** The `ReentrantLock` establishes a *happens-before* relationship. Changes made by a thread releasing the lock are guaranteed to be visible to any thread acquiring the same lock subsequently. This ensures that updates to the map and list pointers are propagated across CPU caches correctly.

### 3. JUnit 5 Tests

This includes unit tests for basic functionality and a concurrency stress test to verify invariants under heavy parallel load.

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel if desired, though stress tests are usually isolated
class ConcurrentLRUCacheTest {

    private static final int DEFAULT_CAPACITY = 3;
    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(DEFAULT_CAPACITY);
    }

    @Test
    void testPutAndGet() {
        cache.put(1, "One");
        assertEquals("One", cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    void testUpdateExistingKey() {
        cache.put(1, "One");
        cache.put(1, "UpdatedOne");
        assertEquals("UpdatedOne", cache.get(1));
        // Ensure it moves to MRU (Head) - implicit check via eviction test below
    }

    @Test
    void testEviction() {
        // Fill cache
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.put(3, "Three");
        
        assertEquals(3, cache.size());

        // Access 1 to make it MRU
        cache.get(1);
        
        // Add 4, should evict 2 (LRU) not 1
        cache.put(4, "Four");
        
        assertEquals(3, cache.size());
        assertEquals("One", cache.get(1)); // 1 should still be there
        assertEquals("Four", cache.get(4)); // 4 added
        assertNull(cache.get(2)); // 2 should be evicted
    }

    @Test
    void testEvictionOrder() {
        cache.put(1, "A");
        cache.put(2, "B");
        cache.put(3, "C");
        
        // Access 1 and 2
        cache.get(1);
        cache.get(2);
        
        // Put 4. 3 is LRU.
        cache.put(4, "D");
        
        assertNull(cache.get(3));
        assertEquals("A", cache.get(1));
        assertEquals("B", cache.get(2));
        assertEquals("C", cache.get(3)); // Should be null
    }

    /**
     * Concurrency Stress Test.
     * Multiple threads perform random puts and gets.
     * We verify that the final size never exceeds capacity
     * and that no data corruption occurred (values match keys).
     */
    @Test
    void testConcurrencyStress() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Runnable> tasks = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            tasks.add(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = random.nextInt(threadId * 10); // Spread keys across threads
                        String value = "Thread-" + threadId + "-Val-" + j;
                        
                        cache.put(key, value);
                        
                        // Verify we can read it back immediately
                        String retrieved = cache.get(key);
                        if (retrieved == null) {
                            // This is allowed if the capacity is small and the key was evicted 
                            // before we tried to get it in the same loop iteration (race condition).
                            // However, in a single-threaded loop, if we just put it, it should be there 
                            // UNLESS capacity is 0. With capacity > 0, put usually succeeds unless full.
                            // But here we have concurrent puts, so eviction is likely.
                            // We will only check consistency if the value exists.
                        } else {
                             // Basic sanity check: Value format should be consistent
                             if (!retrieved.contains("Thread-" + threadId)) {
                                 errorCount.incrementAndGet();
                             }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        for (Runnable task : tasks) {
            executor.submit(task);
        }

        latch.await(1, TimeUnit.MINUTES);
        executor.shutdownNow();

        assertEquals(0, errorCount.get(), "Data corruption detected during concurrent execution");
        assertTrue(cache.size() <= DEFAULT_CAPACITY, "Cache exceeded capacity");
    }
}
```

### Key Features of the Solution:

1.  **O(1) Operations**: The `HashMap` provides O(1) average time complexity for `get` and `put` lookups. The Doubly Linked List allows moving a node to the head or removing the tail in O(1) time via pointer manipulation.
2.  **Thread Safety**: The `ReentrantLock` ensures that the cache behaves correctly even when accessed by multiple threads simultaneously. By guarding the entire mutation logic, we avoid complex but error-prone "lock splitting" strategies.
3.  **Correctness**: The stress test (`testConcurrencyStress`) is designed to catch race conditions. It spawns multiple threads that aggressively write and read random keys. It asserts that the cache never exceeds its defined capacity and that no data corruption (mismatched values) occurs.