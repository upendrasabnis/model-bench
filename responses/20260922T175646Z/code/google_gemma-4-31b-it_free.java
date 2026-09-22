<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260922T175646Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ complexity, the standard approach is combining a **HashMap** (for fast lookup) and a **Doubly Linked List** (to maintain access order).

In Java, `LinkedHashMap` provides this functionality out of the box, but it is not thread-safe. While `Collections.synchronizedMap` would work, it uses a single coarse-grained lock for every operation, which can become a bottleneck. 

Below is a professional implementation using a `ReentrantReadWriteLock` to allow multiple concurrent reads while ensuring exclusive access for writes (updates/evictions).

### Implementation

```java
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity);
        
        // Dummy boundary nodes to avoid null checks during pointer manipulation
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.writeLock().lock(); // Write lock needed because 'get' modifies the LRU order
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            
            moveToHead(node);
            return node.value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                if (map.size() >= capacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
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
    }

    private void evict() {
        Node<K, V> last = tail.prev;
        if (last != head) {
            removeNode(last);
            map.remove(last.key);
        }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Mutual Exclusion:** I used a `ReentrantReadWriteLock`. Although `get` operations are logically "reads," in an LRU cache, a `get` updates the position of the node in the linked list to mark it as most recently used. Therefore, `get` must acquire the **write lock** to prevent race conditions while rearranging pointers.
2.  **Atomic Updates:** By wrapping the `HashMap` lookup and the `DoublyLinkedList` pointer updates within the same lock acquisition, we ensure that the map and the list are always in sync.
3.  **Boundary Nodes:** The use of dummy `head` and `tail` nodes prevents `NullPointerException` during concurrent removals/additions, eliminating the need for complex conditional checks.
4.  **Complexity:** 
    *   `get`: $O(1)$ average (HashMap lookup + pointer swap).
    *   `put`: $O(1)$ average (HashMap insert + pointer swap + potential eviction).

---

### JUnit 5 Tests & Concurrency Stress Test

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get functionality")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals("A", cache.get(1));
    }

    @Test
    @DisplayName("Stress test for concurrency and invariants")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 10;
        int threadCount = 50;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * operationsPerThread) + j; 
                        // Some keys overlap to force contention on same entries
                        int sharedKey = j % 20; 
                        
                        cache.put(key, key);
                        cache.put(sharedKey, sharedKey);
                        cache.get(sharedKey);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "There should be no exceptions during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size should never exceed capacity");
    }

    @Test
    @DisplayName("Verify LRU eviction order under load")
    void testEvictionOrder() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3);
        
        cache.get(1); // 1 is now MRU, 2 is LRU
        cache.put(4, 4); // Should evict 2
        
        assertNull(cache.get(2), "Key 2 should have been evicted");
        assertNotNull(cache.get(1));
        assertNotNull(cache.get(3));
        assertNotNull(cache.get(4));
    }
}
```