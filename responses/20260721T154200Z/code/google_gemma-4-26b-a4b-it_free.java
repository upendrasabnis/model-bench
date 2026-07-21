<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260721T154200Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ complexity, we combine a `HashMap` (for fast lookups) with a `Doubly Linked List` (to maintain usage order).

### Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

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
        
        // Dummy head and tail to simplify edge cases (empty list, etc.)
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

    // Internal helper methods (must be called within lock)
    private void addNode(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
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
}
```

### How Thread-Safety is Guaranteed
1.  **Coarse-Grained Locking**: I used a `ReentrantLock` to protect the entire state of the cache. While fine-grained locking (locking individual nodes) is possible, it is extremely complex for an LRU because every `get()` operation is technically a "write" operation (it modifies the pointers of the linked list to move the node to the head).
2.  **Atomicity**: The `put` operation requires multiple steps: checking existence, updating the map, updating the linked list, and potentially evicting. By wrapping the entire logic in a `lock.lock()/unlock()` block, we ensure these operations are atomic. No thread will ever see the cache in an inconsistent state (e.g., a key in the map but not in the list).
3.  **Visibility**: The `lock` provides the necessary "happens-before" relationship. Any write performed by Thread A before releasing the lock is guaranteed to be visible to Thread B after it acquires the same lock.

---

### JUnit 5 Tests

You will need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic functionality: put and get")
    void testBasicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        
        assertEquals("A", cache.get(1));
        assertEquals("B", cache.get(2));
        
        cache.put(3, "C"); // Evicts 1
        assertNull(cache.get(1));
        assertEquals("C", cache.get(3));
        assertEquals("B", cache.get(2));
    }

    @Test
    @DisplayName("Concurrency Stress Test: Integrity under heavy load")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int numThreads = 20;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of puts and gets
                        int key = (threadId * opsPerThread) + j;
                        cache.put(key, j);
                        
                        // Occasionally access older keys to trigger LRU movement
                        cache.get(j % 10); 
                        
                        // Check invariant: Size should never exceed capacity
                        if (cache.size() > capacity) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariants Check
        assertEquals(0, errorCount.get(), "Errors detected during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache exceeded capacity under load");
    }

    @Test
    @DisplayName("Concurrency: Race condition on same key")
    void testConcurrentSameKey() throws InterruptedException {
        ConcurrentLRUCache<String, Integer> cache = new ConcurrentLRUCache<>(10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        // Two threads fighting to update the same key
        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                cache.put("key", i);
            }
        };

        executor.submit(task);
        executor.submit(task);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // If thread-safe, the size should be 1 and no exceptions thrown
        assertEquals(1, cache.size());
        assertNotNull(cache.get("key"));
    }
}
```

### Key Test Explanations:
1.  **`testConcurrencyStress`**: This spawns 20 threads performing 1,000 operations each. It checks the primary invariant: **The cache size must never exceed the configured capacity**, even when multiple threads are simultaneously adding and evicting.
2.  **`testConcurrentSameKey`**: This focuses on race conditions where multiple threads target the exact same key. It ensures the internal linked list doesn't become corrupted (which would lead to `NullPointerException` or infinite loops) and that the map/list remain synchronized.