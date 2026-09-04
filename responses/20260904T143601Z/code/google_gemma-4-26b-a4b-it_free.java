<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260904T143601Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, we combine a `HashMap` for fast lookups and a `DoublyLinkedList` to maintain access order.

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
        
        // Dummy head and tail to avoid null checks in DLL operations
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        lock.lock();
        try {
            Node node = map.get(key);
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
            Node node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node newNode = new Node(key, value);
                map.put(key, newNode);
                addToHead(newNode);

                if (map.size() > capacity) {
                    Node lru = tail.prev;
                    removeNode(lru);
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

    // --- Internal DLL Helpers (Must be called within lock) ---

    private void addToHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addToHead(node);
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Coarse-Grained Locking**: I used a `ReentrantLock` to wrap the entirety of the `get` and `put` operations. While fine-grained locking (locking individual nodes) is possible, it is extremely complex for LRU because every `get` operation performs a **write** to the structural integrity of the linked list (moving a node to the head).
2.  **Atomicity**: The `put` operation involves multiple steps: checking the map, updating the DLL, and potentially evicting an element. By holding the lock for the duration of the method, we ensure these steps are atomic. No thread can see the cache in an intermediate state (e.g., a node exists in the Map but is not yet in the DLL).
3.  **Visibility**: The `ReentrantLock` provides a *happens-before* relationship. Any write made by thread A before releasing the lock is guaranteed to be visible to thread B after it acquires the same lock.
4.  **Invariant Protection**: The dummy `head` and `tail` nodes prevent `NullPointerException` during concurrent structural changes, and the lock ensures the `map.size()` always stays in sync with the physical nodes in the DLL.

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    @Test
    @DisplayName("Basic Put and Get functionality")
    void testBasicFunctionality() {
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
    @DisplayName("Concurrency Stress Test: Heavy Parallel Load")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 50;
        int threadCount = 20;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        AtomicInteger failedAssertions = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = (threadId * operationsPerThread) + j;
                        // Mix of puts and gets
                        cache.put(key, key);
                        
                        // Occasional gets of existing or non-existing keys
                        cache.get(key % 10); 
                        
                        // Invariant Check: Size should never exceed capacity
                        if (cache.size() > capacity) {
                            failedAssertions.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    failedAssertions.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, failedAssertions.get(), "Invariants were violated during concurrent execution!");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity");
    }

    @Test
    @DisplayName("Ensure capacity is strictly enforced")
    void testCapacityEnforcement() {
        int capacity = 10;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        assertEquals(capacity, cache.size());
    }
}
```

### Complexity Analysis
*   **Time Complexity**: 
    *   `get(K key)`: $O(1)$ average for `HashMap` lookup + $O(1)$ for DLL pointer updates.
    *   `put(K key, V value)`: $O(1)$ average for `HashMap` insertion/lookup + $O(1)$ for DLL updates/eviction.
*   **Space Complexity**: $O(C)$ where $C$ is the capacity, to store the Map entries and the DLL nodes.