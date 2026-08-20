<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260820T052305Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, we combine a `HashMap` (for $O(1)$ lookups) with a `Doubly Linked List` (for $O(1)$ updates to the access order).

### Implementation

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {

    private class Node {
        K key;
        V value;
        Node prev;
        Node next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node> map;
    private final Node head;
    private final Node tail;
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity);
        
        // Dummy head and tail to avoid null checks in list operations
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node node = map.get(key);
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
            Node node = map.get(key);
            if (node!= null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node newNode = new Node(key, value);
                map.put(key, newNode);
                addNode(newNode);
                if (map.size() > capacity) {
                    Node lru = popTail();
                    map.remove(lru.key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void addNode(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addNode(node);
    }

    private Node popTail() {
        Node res = tail.prev;
        removeNode(res);
        return res;
    }

    // For testing purposes
    protected int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Coarse-Grained Locking**: I used a `ReentrantLock` to protect the entire state of the cache (the `HashMap` and the `Doubly Linked List`). While fine-grained locking (locking individual nodes) is possible, it is extremely complex for an LRU because every `get` operation is actually a "write" operation to the linked list (moving a node to the head).
2.  **Atomicity**: By wrapping the logic of `get` and `put` within `lock.lock()` and `lock.unlock()`, we ensure that the sequence of "Check map $\rightarrow$ Update list $\rightarrow$ Evict if necessary" is atomic. No other thread can see the cache in an inconsistent state (e.g., a node present in the map but disconnected from the list).
3.  **Memory Visibility**: The `ReentrantLock` provides a *happens-before* relationship. Changes made by one thread before releasing the lock are guaranteed to be visible to another thread after it acquires the lock.

---

### JUnit 5 Tests

To run these, ensure you have `org.junit.jupiter:junit-jupiter` in your dependencies.

```java
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    void testBasicFunctionality() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        assertEquals("A", cache.get(1));
        cache.put(3, "C"); // Evicts 2
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int opsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Stress test: Many threads performing random puts and gets
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        // Mix of operations
                        cache.put(j % (capacity * 2), j);
                        cache.get(j % (capacity * 2));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant Check: Size must never exceed capacity
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load!");
    }

    @Test
    void testHighContentionUpdate() throws InterruptedException {
        // Test that many threads updating the SAME key doesn't corrupt the list
        int threadCount = 50;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(10);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 1000; j++) {
                    cache.put(1, j); // Constant contention on key 1
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // If thread-safety failed, the internal list would likely be corrupted
        // causing an infinite loop or NullPointerException during get/put.
        assertNotNull(cache.get(1));
    }
}
```

### Explanation of Tests
1.  **`testBasicFunctionality`**: Validates the core LRU logic (eviction of the least recently used item).
2.  **`testConcurrencyStress`**: Uses a `CountDownLatch` and multiple threads to bombard the cache with `put` and `get` operations. It checks the fundamental invariant: **The cache size must never exceed its capacity**, regardless of how many threads are competing for memory.
3.  **`testHighContentionUpdate`**: Focuses on "hot keys." When many threads update the same key, they are constantly triggering the `moveToHead` logic. This tests if the linked list pointers (`prev`/`next`) get corrupted, which would typically cause a `NullPointerException` or an infinite loop in a non-thread-safe implementation.