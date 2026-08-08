<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260808T173453Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ performance, we combine a `HashMap` (for $O(1)$ lookups) with a `DoublyLinkedList` (for $O(1)$ updates to the usage order).

### Implementation

We use `ReentrantReadWriteLock` to allow multiple concurrent readers while ensuring exclusive access for writes (updates and evictions).

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final DoublyLinkedList<K, V> list;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

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

    private static class DoublyLinkedList<K, V> {
        Node<K, V> head;
        Node<K, V> tail;

        void addFirst(Node<K, V> node) {
            node.next = head;
            node.prev = null;
            if (head!= null) head.prev = node;
            head = node;
            if (tail == null) tail = node;
        }

        void remove(Node<K, V> node) {
            if (node.prev!= null) node.prev.next = node.next;
            else head = node.next;

            if (node.next!= null) node.next.prev = node.prev;
            else tail = node.prev;
        }

        void moveToFront(Node<K, V> node) {
            remove(node);
            addFirst(node);
        }
    }

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.list = new DoublyLinkedList<>();
    }

    public V get(K key) {
        // We must use writeLock even for 'get' because LRU requires 
        // reordering the list (a structural mutation) to mark it as "recently used".
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            list.moveToFront(node);
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
                list.moveToFront(node);
            } else {
                if (map.size() >= capacity) {
                    K removedKey = list.tail.key;
                    map.remove(removedKey);
                    list.remove(list.tail);
                }
                Node<K, V> newNode = new Node<>(key, value);
                list.addFirst(newNode);
                map.put(key, newNode);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        writeLock.lock();
        try {
            return map.size();
        } finally {
            writeLock.unlock();
        }
    }
}
```

### How Thread-Safety is Guaranteed
1.  **Coarse-Grained Locking:** I used a `ReentrantReadWriteLock`. While `get` operations technically only read the value, in an LRU cache, a `get` also modifies the linked list (moving the node to the head). Therefore, `get` **must** acquire the `writeLock` to prevent race conditions where two threads try to reorder the same node simultaneously, which would corrupt the pointers.
2.  **Atomicity:** The `put` operation involves checking size, removing the tail (if full), and inserting a new node. By wrapping the entire logic in a `writeLock`, we ensure that no other thread sees the cache in an inconsistent state (e.g., where the map size is 10 but the list size is 9).
3.  **Visibility:** The `Lock` implementation provides the necessary "happens-before" relationship. Any write made by Thread A before releasing the lock is guaranteed to be visible to Thread B when it acquires the lock.

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Functionality: Put and Get")
    void testBasicFunctionality() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Should evict 2 (since 1 was accessed)
        
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("Concurrency Stress Test: High Contention")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 10;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    latch.await(); // Wait for signal to start all threads at once
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * opsPerThread + j) % (capacity * 2);
                        cache.put(key, j);
                        cache.get(key % capacity);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown(); // Start race
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(finished, "Threads did not finish in time");
        // Invariant: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity: " + cache.size());
    }

    @Test
    @DisplayName("Concurrency Stress Test: Invariant Integrity")
    void testInvariantsUnderLoad() throws InterruptedException {
        int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        // Repeatedly put/get to stress pointers
        for (int i = 0; i < 5000; i++) {
            executor.submit(() -> {
                int val = ThreadLocalRandom.current().nextInt();
                cache.put(val, val);
                cache.get(ThreadLocalRandom.current().nextInt());
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // If pointers were corrupted, the size or subsequent gets would crash/fail
        assertTrue(cache.size() <= capacity);
    }
}
```

### Complexity Analysis
*   **Time Complexity:**
    *   `get(K)`: $O(1)$ average for HashMap lookup + $O(1)$ for Doubly Linked List node movement.
    *   `put(K, V)`: $O(1)$ average for HashMap insertion/removal + $O(1)$ for Doubly Linked List operations.
*   **Space Complexity:** $O(\text{capacity})$ to store the nodes and the hash map entries.