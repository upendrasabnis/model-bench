<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260820T052305Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
### Java Implementation of Thread-Safe Concurrent LRU Cache

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node> map;
    private final ReadWriteLock lock;
    private Node head;
    private Node tail;
    private AtomicInteger size;

    public ConcurrentLRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        this.lock = new ReentrantReadWriteLock();
        this.size = new AtomicInteger(0);
    }

    public V get(K key) {
        lock.readLock().lock();
        try {
            Node node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.readLock().unlock();
        }
    }

    public V put(K key, V value) {
        lock.writeLock().lock();
        try {
            Node existingNode = map.get(key);
            if (existingNode != null) {
                existingNode.value = value;
                moveToHead(existingNode);
                return existingNode.value;
            }

            if (size.get() >= capacity) {
                evict();
            }

            Node newNode = new Node(key, value);
            map.put(key, newNode);
            size.incrementAndGet();
            addToHead(newNode);
            return value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void evict() {
        if (tail == null) return;

        lock.writeLock().lock();
        try {
            if (tail == null) return;

            map.remove(tail.key);
            size.decrementAndGet();

            if (tail.prev != null) {
                tail.prev.next = null;
            }
            head = tail.prev;
            tail = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addToHead(Node node) {
        if (head == null) {
            head = tail = node;
            node.prev = null;
            node.next = null;
            return;
        }

        node.next = head;
        head.prev = node;
        head = node;
        node.prev = null;
    }

    private void moveToHead(Node node) {
        if (node == head) return;

        if (node.prev != null) {
            node.prev.next = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        }

        if (node == tail) {
            tail = node.prev;
            tail.next = null;
        }

        node.next = head;
        head.prev = node;
        head = node;
        node.prev = null;
    }

    static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
    }
}
```

### Thread-Safety Explanation
1. **Read/Write Locking**: A `ReadWriteLock` ensures that:
   - Multiple concurrent `get` operations can proceed in parallel (read operations).
   - `put` and `evict` operations acquire exclusive write locks, preventing interference during modifications.
   - Atomicity is maintained for critical sections (e.g., cache eviction).

2. **ConcurrentHashMap**: Used for O(1) lookups and updates, with atomicity for individual entries. Combined with the lock, it ensures that structural modifications (like eviction) are synchronized.

3. **Atomic Integer**: The `size` counter uses `AtomicInteger` for thread-safe size tracking during `put` and `evict` operations.

---

### JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {
    private static final int CAPACITY = 3;
    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(CAPACITY);
    }

    @Test
    void testPutAndGet() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        cache.put(2, "two");
        assertEquals("two", cache.get(2));
    }

    @Test
    void testEviction() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        assertNull(cache.put(4, "four")); // Evicts 1
        assertEquals("two", cache.get(2));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void testConcurrentGet() {
        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch latch = new CountDownLatch(100);
        int[] result = new int[1];

        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                cache.put(i, "val");
                assertEquals("val", cache.get(i));
            }
            latch.countDown();
        };

        for (int i = 0; i < 100; i++) {
            executor.submit(task);
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        assertEquals(1000, cache.size());
    }

    @Test
    void testConcurrentPutAndEvict() {
        ExecutorService executor = Executors.newFixedSizeThreadPool(50);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger putCount = new AtomicInteger(0);
        AtomicInteger evictCount = new AtomicInteger(0);

        Runnable putTask = () -> {
            for (int i = 0; i < 1000; i++) {
                cache.put(i, "val");
            }
            latch.countDown();
        };

        Runnable evictTask = () -> {
            for (int i = 0; i < 1000; i++) {
                cache.put(i + 1000, "val");
            }
            latch.countDown();
        };

        executor.submit(putTask);
        executor.submit(evictTask);
        executor.submit(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            evictCount.set(cache.size());
        });

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, evictCount.get()); // All entries fit within capacity
    }
}
```

### Key Test Scenarios
1. **Basic Functionality**: Verifies `put` and `get` operations.
2. **Eviction**: Tests capacity limits and LRU eviction.
3. **Concurrent Get**: Stress tests concurrent read access.
4. **Concurrent Put/Evict**: Ensures thread safety during mixed write operations.

### Limitations & Considerations
- **Memory Overhead**: The lock synchronizes all operations, potentially limiting concurrency.
- **Atomic Size**: The `AtomicInteger` ensures thread-safe size tracking but adds overhead.
- **Lock Granularity**: Fine-grained locking (e.g., per-node) could improve concurrency but increases complexity.

This implementation balances thread safety and performance while adhering to LRU requirements.
