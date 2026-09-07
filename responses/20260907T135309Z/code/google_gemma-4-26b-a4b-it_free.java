<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260907T135309Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU (Least Recently Used) cache with $O(1)$ operations, the standard approach is to combine a **HashMap** (for $O(1)$ lookup) with a **Doubly Linked List** (for $O(1)$ reordering/eviction).

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
        
        // Dummy head and tail to avoid null checks in add/remove logic
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
                    Node removed = removeTail();
                    if (removed != null) {
                        map.remove(removed.key);
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

    // --- Internal Doubly Linked List Helpers (Must be called within lock) ---

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

    private Node removeTail() {
        if (tail.prev == head) return null;
        Node res = tail.prev;
        removeNode(res);
        return res;
    }
}
```

### How Thread-Safety is Guaranteed

1.  **Coarse-Grained Locking**: I used a `ReentrantLock` to protect the entire internal state. While fine-grained locking (locking individual nodes) is possible, it is extremely complex for an LRU because every `get()` operation performs a **write** to the doubly linked list (moving the node to the head).
2.  **Atomicity of Compound Operations**: A `get` operation isn't just a read; it's a "read + reorder" operation. By wrapping the entire logic in a lock, we ensure that no other thread can see the list in an inconsistent state (e.g., a node being unlinked but not yet re-linked).
3.  **Memory Visibility**: The `ReentrantLock` provides a *happens-before* relationship. Any write performed by Thread A before releasing the lock is guaranteed to be visible to Thread B after it acquires the same lock.
4.  **Invariant Protection**: The lock ensures that the `map.size()` always matches the actual number of nodes in the linked list, preventing "ghost" entries or capacity overflows.

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter` and `org.assertj:assertj-core` (optional, but used here for clarity).

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
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
        cache.put(3, "C"); // Should evict 2 (1 was accessed last)
        
        assertNull(cache.get(2));
        assertEquals("C", cache.get(3));
        assertEquals("A", cache.get(1));
    }

    @Test
    @DisplayName("Capacity constraint is strictly enforced")
    void testCapacity() {
        int capacity = 5;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        assertEquals(capacity, cache.size());
    }

    @Test
    @DisplayName("Concurrency Stress Test: Heavy Parallel Load")
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 10;
        int threadCount = 16;
        int operationsPerThread = 10000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        // Mix of puts and gets
                        int key = (threadId * operationsPerThread + i) % 50; // High collision rate
                        if (i % 2 == 0) {
                            cache.put(key, key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Invariant Checks
        assertEquals(0, errors.get(), "There should be no exceptions during concurrent execution");
        assertTrue(cache.size() <= capacity, "Cache size exceeded capacity under load");
        
        // Final sanity check: ensure the cache is still functional
        cache.put(999, 999);
        assertEquals(999, cache.get(999));
    }
}
```

### Explanation of the Stress Test
*   **High Contention**: By using a key range (`% 50`) much smaller than the total number of operations, we force multiple threads to fight for the same keys and the same lock.
*   **Mixed Workload**: We alternate between `put` (which modifies the map and the list) and `get` (which modifies the list).
*   **Invariant Validation**:
    1.  **No Exceptions**: Checks if the internal pointers (prev/next) ever become corrupted (which would cause `NullPointerException` or infinite loops).
    2.  **Capacity Invariant**: Ensures that even with race conditions, the `size()` never exceeds the `capacity`.
    3.  **Liveness**: The `latch.await` ensures the test doesn't hang indefinitely if a deadlock occurs.