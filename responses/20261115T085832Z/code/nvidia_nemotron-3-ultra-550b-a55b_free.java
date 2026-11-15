<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261115T085832Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## Thread-Safe Concurrent LRU Cache Implementation

### Design Overview
- **Data Structures**: Doubly-linked list (for access order) + `HashMap` (for O(1) lookups)
- **Concurrency Control**: Single `ReentrantLock` guarding all mutating operations
- **Complexity**: O(1) average for `get`/`put` (amortized)
- **Eviction**: Tail node (least recently used) removed when capacity exceeded

### Thread-Safety Guarantees
1. **Mutual Exclusion**: All public methods (`get`, `put`, `size`, `containsKey`, `clear`) acquire the same lock
2. **Atomic Compound Operations**: Check-then-act sequences (e.g., check capacity → evict → insert) execute atomically
3. **Memory Visibility**: Lock acquisition/release provides happens-before guarantees
4. **Consistency**: Map and linked list always synchronized – no partial updates visible
5. **No Lock-Free Complexity**: Avoids ABA problems and complex CAS loops while maintaining correctness

> **Trade-off**: Single lock limits read concurrency (since `get` moves nodes). For higher throughput, consider segmented locking or `ConcurrentLinkedHashMap` (external library).

---

### Production Code

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null);
    private final Node<K, V> tail = new Node<>(null, null);
    private final ReentrantLock lock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
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
            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                map.put(key, newNode);
                addToHead(newNode);
                if (map.size() > capacity) removeTail();
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return map.size(); }
        finally { lock.unlock(); }
    }

    public boolean containsKey(K key) {
        lock.lock();
        try { return map.containsKey(key); }
        finally { lock.unlock(); }
    }

    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally { lock.unlock(); }
    }

    // Package-private for test verification
    void checkInvariants() {
        lock.lock();
        try {
            int count = 0;
            Node<K, V> current = head.next;
            while (current != tail) {
                count++;
                if (current.next == null || current.prev == null)
                    throw new IllegalStateException("Broken link at " + current.key);
                if (current.next.prev != current)
                    throw new IllegalStateException("Next.prev mismatch at " + current.key);
                if (current.prev.next != current)
                    throw new IllegalStateException("Prev.next mismatch at " + current.key);
                if (map.get(current.key) != current)
                    throw new IllegalStateException("Map/list inconsistency for " + current.key);
                current = current.next;
            }
            if (count != map.size())
                throw new IllegalStateException("Size mismatch: map=" + map.size() + ", list=" + count);
            if (head.prev != null) throw new IllegalStateException("Head.prev not null");
            if (tail.next != null) throw new IllegalStateException("Tail.next not null");
        } finally { lock.unlock(); }
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

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private void removeTail() {
        Node<K, V> last = tail.prev;
        removeNode(last);
        map.remove(last.key);
    }

    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }
}
```

---

### JUnit 5 Tests (Including Concurrency Stress Test)

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() { cache = new ConcurrentLRUCache<>(3); }

    @Test
    void basicPutGet() {
        cache.put(1, "one");
        assertEquals("one", cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(1);                 // MRU: 1, 3, 2 (LRU)
        cache.put(4, "four");         // evicts 2
        assertNull(cache.get(2));
        assertEquals(3, cache.size());
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void updateExistingKey() {
        cache.put(1, "one");
        cache.put(1, "uno");
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void clearResetsState() {
        cache.put(1, "one");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int CAPACITY = 100;
        final int THREADS = 20;
        final int OPS_PER_THREAD = 5000;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = r.nextInt(CAPACITY * 2);
                        if (r.nextBoolean()) c.put(key, key * 10);
                        else c.get(key);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally { done.countDown(); }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "Concurrent errors detected");

        // Verify structural invariants
        c.checkInvariants();
        assertTrue(c.size() <= CAPACITY, "Capacity exceeded: " + c.size());
    }

    @Test
    void concurrentReadWriteCorrectness() throws InterruptedException {
        final int CAPACITY = 50;
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(CAPACITY);
        // Pre-populate
        for (int i = 0; i < CAPACITY; i++) c.put(i, i * 2);

        ExecutorService es = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger mismatches = new AtomicInteger();

        for (int t = 0; t < 10; t++) {
            es.submit(() -> {
                try {
                    Random r = new Random();
                    for (int i = 0; i < 1000; i++) {
                        int key = r.nextInt(CAPACITY);
                        Integer val = c.get(key);
                        if (val != null && val != key * 2)
                            mismatches.incrementAndGet();
                        if (r.nextBoolean()) c.put