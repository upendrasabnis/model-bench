<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260718T175846Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
To implement a thread-safe LRU cache with $O(1)$ operations, the ideal data structure is a combination of a **ConcurrentHashMap** (for fast lookups) and a **Doubly Linked List** (to maintain access order).

While Java provides `LinkedHashMap` (which can implement LRU via `removeEldestEntry`), it is not thread-safe. Wrapping it in `Collections.synchronizedMap` creates a global lock, which bottlenecks performance. The implementation below uses a `ReentrantLock` to protect the linked list operations while leveraging a `ConcurrentHashMap` for the values.

### Implementation

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final ReentrantLock lock = new ReentrantLock();
    private final Node<K, V> head, tail;

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
        this.map = new ConcurrentHashMap<>(capacity);
        
        // Dummy head and tail to avoid null checks during pointer updates
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;

        // Move to front to mark as Most Recently Used
        lock.lock();
        try {
            moveToFront(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    public void put(K key, V value) {
        Node<K, V> node = map.get(key);

        lock.lock();
        try {
            if (node != null) {
                node.value = value;
                moveToFront(node);
            } else {
                if (map.size() >= capacity) {
                    evict();
                }
                Node<K, V> newNode = new Node<>(key, value);
                addToFront(newNode);
                map.put(key, newNode);
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    private void moveToFront(Node<K, V> node) {
        removeNode(node);
        addToFront(node);
    }

    private void addToFront(Node<K, V> node) {
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

1.  **ConcurrentHashMap**: Used for the key-to-node mapping. This allows multiple threads to check for the existence of a key simultaneously without blocking.
2.  **Fine-grained Locking**: I used a `ReentrantLock` specifically to protect the **Doubly Linked List**. Since pointers (`prev`, `next`) must be updated atomically to prevent corruption (e.g., losing a node or creating a cycle), the lock ensures that only one thread modifies the list structure at a time.
3.  **Dummy Nodes**: The `head` and `tail` sentinels eliminate edge cases (like empty lists), reducing the risk of `NullPointerException` during concurrent pointer swaps.
4.  **Atomic Put/Evict**: The `put` operation is wrapped in a lock to ensure that the "check capacity $\rightarrow$ evict $\rightarrow$ insert" sequence is atomic. This prevents the cache from exceeding its capacity when multiple threads insert simultaneously.

---

### JUnit 5 Tests

You will need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ConcurrentLRUCacheTest {

    @Test
    void testBasicLRUBehavior() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(2, "B");
        cache.get(1);       // 1 becomes MRU
        cache.put(3, "C");  // 2 should be evicted

        assertNull(cache.get(2));
        assertEquals("A", cache.get(1));
        assertEquals("C", cache.get(3));
    }

    @Test
    void testConcurrencyStress() throws InterruptedException {
        int capacity = 100;
        int threadCount = 50;
        int operationsPerThread = 1000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of puts and gets across a range larger than capacity
                        int key = (threadId * operationsPerThread + j) % (capacity * 2);
                        if (j % 2 == 0) {
                            cache.put(key, j);
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

        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "Concurrency errors detected");
        assertTrue(cache.size() <= capacity, "Cache exceeded capacity under load");
    }

    @Test
    void testUpdateExistingKey() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "A");
        cache.put(1, "B");
        assertEquals("B", cache.get(1));
        assertEquals(1, cache.size());
    }
}
```

### Complexity Analysis
- **Time Complexity**: `get` and `put` are $O(1)$ average. The `ConcurrentHashMap` lookup is $O(1)$, and the linked list pointer updates are $O(1)$.
- **Space Complexity**: $O(C)$ where $C$ is the capacity.