<!-- model: cohere/north-mini-code:free | category: code | run: 20261025T072557Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe concurrent LRU cache in Java, along with JUnit 5 tests and an explanation of the thread-safety guarantees.

### 1. The Implementation

The solution uses a **Striped Locking** strategy. Instead of a single global lock (which serializes all operations), the cache is divided into segments. Each segment has its own lock and manages its own internal LRU structure. This allows multiple threads to access different segments concurrently, significantly improving throughput under heavy load.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A thread-safe Concurrent LRU Cache with configurable capacity.
 * Uses a striped locking strategy to allow concurrent access to different segments.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
public class ConcurrentLRUCache<K, V> {

    private final Segment<K, V>[] segments;
    private final int capacity;
    private static final int DEFAULT_SEGMENTS = 16;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        // Number of segments is typically a power of two
        int numSegments = DEFAULT_SEGMENTS;
        while (numSegments < 1) numSegments <<= 1;
        
        this.segments = new Segment[numSegments];
        for (int i = 0; i < numSegments; i++) {
            segments[i] = new Segment<>(capacity);
        }
    }

    private Segment<K, V> getSegment(Object key) {
        // Simple hash-based routing to segments
        int hash = (key == null) ? 0 : key.hashCode();
        // Spread bits to avoid collisions (simple approach: use low bits)
        int index = (hash & 0x7FFFFFFF) % segments.length;
        return segments[index];
    }

    public V get(K key) {
        if (key == null) return null;
        return getSegment(key).get(key);
    }

    public void put(K key, V value) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        getSegment(key).put(key, value);
    }

    public int size() {
        int size = 0;
        for (Segment<?, ?> seg : segments) {
            size += seg.size();
        }
        return size;
    }

    /**
     * Internal Segment class. Contains a Map and a Doubly Linked List
     * to maintain LRU order. Protected by a single ReentrantLock.
     */
    private static class Segment<K, V> {
        private final Map<K, Node<K, V>> map;
        private final Node<K, V> head, tail; // Dummy head and tail for easier logic
        private final Lock lock;
        private final int capacity;

        Segment(int capacity) {
            this.capacity = capacity;
            this.map = new HashMap<>();
            this.lock = new ReentrantLock();
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
                // Move to front (Most Recently Used)
                moveToHead(node);
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
                    // Update value and move to front
                    node.value = value;
                    moveToHead(node);
                } else {
                    // Create new node
                    node = new Node<>(key, value);
                    map.put(key, node);
                    addToHead(node);
                    
                    // Evict if necessary
                    if (map.size() > capacity) {
                        Node<K, V> evicted = removeTail();
                        if (evicted != null) {
                            map.remove(evicted.key);
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            lock.lock();
            try {
                return map.size();
            } finally {
                lock.unlock();
            }
        }

        // Helper: Remove node from list
        private void removeNode(Node<K, V> node) {
            if (node.prev != null) node.prev.next = node.next;
            if (node.next != null) node.next.prev = node.prev;
        }

        // Helper: Add node right after head
        private void addToHead(Node<K, V> node) {
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
        }

        // Helper: Move existing node to head
        private void moveToHead(Node<K, V> node) {
            removeNode(node);
            addToHead(node);
        }

        // Helper: Remove tail node (before dummy tail)
        private Node<K, V> removeTail() {
            if (tail.prev == head) return null; // Empty list
            Node<K, V> node = tail.prev;
            removeNode(node);
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
    }
}
```

### 2. Explanation of Thread-Safety and O(1) Operations

#### Data Structures
1.  **HashMap (`map`)**: Provides O(1) average time complexity for `get` and `put` operations based on key hashing.
2.  **Doubly Linked List**: Maintains the order of elements. The "Head" is the Most Recently Used (MRU), and the "Tail" is the Least Recently Used (LRU).
3.  **Node**: Links key, value, and pointers to previous/next nodes.

#### O(1) Operations
*   **Get**: The HashMap retrieves the node in O(1). Moving the node to the head involves updating 4 pointers (`prev` and `next` of neighbors, and node's own pointers), which is also O(1).
*   **Put**:
    *   *Update*: Similar to Get (O(1)).
    *   *Insert*: Adding to head is O(1). If capacity is exceeded, removing the tail is O(1).
*   **Eviction**: Removing the tail node is O(1) because we maintain dummy head/tail sentinels to avoid null checks.

#### Thread-Safety Guarantees
We guarantee thread-safety using **Striped Locking**:
1.  **Partitioning**: The cache is split into `N` independent `Segment` instances. Each segment handles a subset of keys based on their hash code.
2.  **Fine-Grained Locking**: Each segment has its own `ReentrantLock`. This allows multiple threads to perform operations on *different* segments simultaneously without blocking each other. Contention is only reduced to the scope of a single segment.
3.  **Atomicity**: Within a segment, we hold the lock for the entire duration of `get` or `put`. This ensures that race conditions (like two threads trying to evict the tail simultaneously, or updating the same key) are prevented. The `finally` block ensures the lock is always released.
4.  **Memory Visibility**: The `Lock` interface in Java provides happens-before guarantees, ensuring that changes made by one thread to a segment's state are visible to other threads acquiring that segment's lock.

### 3. JUnit 5 Tests

This includes unit tests for basic functionality and a concurrency stress test to verify invariants under parallel load.

```java
@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel if possible, though stress test is the main concern
class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3); // Small capacity for easy eviction testing
    }

    @AfterEach
    void tearDown() {
        cache = null;
    }

    @Test
    void testBasicPutAndGet() {
        cache.put(1, "One");
        assertEquals("One", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void testUpdateExistingKey() {
        cache.put(1, "A");
        cache.put(1, "B"); // Update
        assertEquals("B", cache.get(1));
        assertEquals(1, cache.size()); // Size should not change
    }

    @Test
    void testEvictionLRU() {
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.put(3, "Three");
        
        // Cache is now [1, 2, 3] (3 is MRU, 1 is LRU)
        
        // Access 1 to make it MRU
        cache.get(1); 
        
        // Add 4. 2 should be evicted (it became LRU after 1 was accessed)
        cache.put(4, "Four");
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertEquals("One", cache.get(1)); // Still there
        assertEquals("Three", cache.get(3));
        assertEquals("Four", cache.get(4));
        assertEquals(3, cache.size());
    }

    @Test
    void testGetNonExistentKey() {
        assertNull(cache.get(99));
        assertEquals(0, cache.size());
    }

    @Test
    void testPutNullKeyThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> cache.put(null, "value"));
    }

    /**
     * Concurrency Stress Test.
     * Spawns multiple threads that randomly put and get values.
     * Verifies that the cache never exceeds capacity and remains in a consistent state.
     */
    @Test
    void testConcurrentStress() throws InterruptedException {
        int threadCount = 20;
        int operationsPerThread = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        Random random = new Random();
        
        // Shared state to verify final consistency (optional, but good for debugging)
        // For this test, we rely on the cache's internal invariants.
        
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = random.nextInt(100); // Keys 0-99
                        if (random.nextBoolean()) {
                            cache.put(key, "Value-" + threadId + "-" + j);
                        } else {
                            // Just read, might be null
                            cache.get(key);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads[i].start();
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        endLatch.await();
        
        // Verification Phase
        // 1. Check Capacity Invariant
        assertTrue(cache.size() <= 3, "Cache size " + cache.size() + " exceeded capacity 3");
        
        // 2. Check Consistency
        // Iterate over internal segments to verify map integrity (tricky without reflection, 
        // but we can check size and try to retrieve values).
        // Since we cannot easily access private segments, we rely on public API.
        // However, we can check that all keys in [0, 99] that are present are valid strings.
        for (int i = 0; i < 100; i++) {
            Object val = cache.get(i);
            if (val != null) {
                assertTrue(val.toString().startsWith("Value-"), "Invalid value format for key " + i);
            }
        }
    }
}
```

### Key Design Decisions in the Code

1.  **Striped Segment Count**: I chose 16 segments as a default. This is a power of two, which makes the modulo operation cheap (`% segments.length`). It balances the overhead of having many locks versus the reduction in contention.
2.  **Dummy Head/Tail**: The `head` and `tail` nodes in the `Segment` class are dummy nodes. This eliminates the need for special null checks when the list is empty or has one element, making the pointer manipulation logic cleaner and less error-prone.
3.  **Stress Test Strategy**: Testing strict LRU order under heavy concurrency is notoriously flaky because race conditions can alter the order. Instead, the stress test focuses on **safety invariants**:
    *   **Capacity Constraint**: The size must never exceed the limit.
    *   **Data Integrity**: If a key exists, the value must be a valid string (indicating it wasn't corrupted by a race condition).
    *   **Concurrency**: The test spawns 20 threads performing 1000 operations each, ensuring the locking mechanism doesn't deadlock or cause `OutOfMemory` due to internal corruption.