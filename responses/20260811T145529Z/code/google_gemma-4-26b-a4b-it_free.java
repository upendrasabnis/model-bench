<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260811T145529Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ average complexity, we combine a `HashMap` (for fast lookups) with a `Doubly Linked List` (to track usage order).

### Implementation

We use `ReentrantReadWriteLock` to allow multiple concurrent readers while ensuring exclusive access for writers (updates/evictions).

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

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

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        // Dummy head and tail to avoid null checks during node removal/addition
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        // We must use writeLock even for 'get' because 'get' modifies 
        // the linked list order (moving the node to head).
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    public void put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node!= null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addNode(newNode);
                if (map.size() > capacity) {
                    Node<K, V> lru = popTail();
                    map.remove(lru.key);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void addNode(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        prev.next = next;
        next.prev = prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addNode(node);
    }

    private Node<K, V> popTail() {
        Node<K, V> res = tail.prev;
        removeNode(res);
        return res;
    }

    // Package-private for testing
    int size() {
        writeLock.lock();
        try { return map.size(); } finally { writeLock.unlock(); }
    }
}
```

### How Thread-Safety is Guaranteed
1.  **Coarse-Grained Locking:** I used a `ReentrantReadWriteLock`. While `get` operations logically "read" data, in an LRU cache, a `get` also "writes" to the metadata (the linked list order). Therefore, I use the `writeLock` for both `get` and `put` to ensure the integrity of the doubly linked list pointers.
2.  **Atomic Compound Operations:** Operations like "check if exists $\rightarrow$ move to head $\rightarrow$ check capacity $\rightarrow$ evict" are wrapped in a single lock acquisition. This prevents a race condition where two threads might both see the cache is at capacity and both attempt to evict different nodes, potentially corrupting the list.
3.  **Memory Visibility:** The `Lock` interface provides a *happens-before* relationship. Any write performed by Thread A before releasing the lock is visible to Thread B after it acquires the lock.

---

### JUnit 5 Test Suite

Requires JUnit 5 dependency.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic functionality: Put and Get")
    void testBasicOperations() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        assertEquals("B", cache.get(2));
        
        cache.put(3, "C"); // Evicts 1
        assertNull(cache.get(1));
        assertEquals("C", cache.get(3));
    }

    @Test
    @DisplayName("Stress Test: High Concurrency Invariants")
    void stressTest() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * operationsPerThread) + j;
                        cache.put(key % 100, j); // High collision on keys
                        cache.get(key % 100);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant 1: No exceptions were thrown during execution
        assertEquals(0, errorCount.get(), "Exceptions occurred during concurrent execution");
        
        // Invariant 2: Size never exceeds capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity");
    }

    @Test
    @DisplayName("LRU Order Invariant: Most recent is kept")
    void testLRUOrder() {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, 1);
        cache.put(2, 2);
        cache.get(1);    // 1 is now most recent
        cache.put(3, 3); // Should evict 2
        
        assertNull(cache.get(2));
        assertEquals(1, cache.get(1));
        assertEquals(3, cache.get(3));
    }
}
```

### Complexity Analysis
*   **Time Complexity:** 
    *   `get(K)`: $O(1)$ average (HashMap lookup + $O(1)$ pointer updates).
    *   `put(K, V)`: $O(1)$ average (HashMap insertion + $O(1)$ pointer updates/eviction).
*   **Space Complexity:** $O(C)$ where $C$ is the capacity, to store the Map and the Doubly Linked List nodes.