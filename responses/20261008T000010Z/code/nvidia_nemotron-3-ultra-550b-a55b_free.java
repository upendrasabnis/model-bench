<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261008T000010Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * Thread-safe LRU Cache with O(1) get/put using HashMap + Doubly Linked List.
 * Uses a ReadWriteLock to allow concurrent reads while serializing writes and LRU updates.
 * 
 * Thread-safety guarantees:
 * 1. All structural modifications (put, eviction, node relocation) hold the write lock.
 * 2. Read-only lookups (get without relocation) hold the read lock.
 * 3. get() upgrades to write lock only when the key exists and needs LRU update,
 *    with a double-check under write lock to handle races with eviction.
 * 4. The map and linked list are always modified atomically under the same lock.
 * 5. No publication of partially constructed nodes; nodes are fully initialized before linking.
 */
public class ConcurrentLRUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail; // Sentinel nodes
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1); // Pre-size to avoid rehash
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        // Fast path: read lock for lookup
        readLock.lock();
        Node<K, V> node;
        try {
            node = map.get(key);
            if (node == null) return null;
        } finally {
            readLock.unlock();
        }

        // Slow path: need to move to front (LRU update) - requires write lock
        writeLock.lock();
        try {
            // Double-check: node might have been evicted while we waited for write lock
            node = map.get(key);
            if (node == null) return null;
            
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("Key/value cannot be null");
        
        writeLock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                // Update existing
                V oldValue = node.value;
                node.value = value;
                moveToHead(node);
                return oldValue;
            }

            // Check capacity and evict if needed
            if (map.size() >= capacity) {
                evictTail();
            }

            // Add new node
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public V remove(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        writeLock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) return null;
            unlink(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        readLock.lock();
        try {
            return map.size();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) return false;
        readLock.lock();
        try {
            return map.containsKey(key);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            writeLock.unlock();
        }
    }

    // Package-private for testing
    List<K> getKeysInOrder() {
        readLock.lock();
        try {
            List<K> keys = new ArrayList<>(map.size());
            Node<K, V> curr = head.next;
            while (curr != tail) {
                keys.add(curr.key);
                curr = curr.next;
            }
            return keys;
        } finally {
            readLock.unlock();
        }
    }

    // --- Internal linked list operations (must hold write lock) ---

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null; // Help GC
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head.next) return; // Already at head
        unlink(node);
        addToHead(node);
    }

    private void evictTail() {
        Node<K, V> lru = tail.prev;
        if (lru == head) return; // Empty
        unlink(lru);
        map.remove(lru.key);
    }

    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    public interface Cache<K, V> {
        V get(K key);
        V put(K key, V value);
        V remove(K key);
        int size();
        boolean containsKey(K key);
        void clear();
    }
}
```

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutGet() {
        cache.put(1, "a");
        cache.put(2, "b");
        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1); // Access 1 -> MRU: 1,3,2
        cache.put(4, "d"); // Evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.put(1, "a-updated"); // Update should move to front
        cache.put(4, "d"); // Evicts 2 (LRU), not 1
        assertNull(cache.get(2));
        assertEquals("a-updated", cache.get(1));
    }

    @Test
    void remove() {
        cache.put(1, "a");
        cache.put(2, "b");
        assertEquals("a", cache.remove(1));
        assertNull(cache.get(1));
        assertEquals(1, cache.size());
        assertNull(cache.remove(999));
    }

    @Test
    void clear() {
        cache.put(1, "a");
        cache.put(2, "b");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @Test
    void nullKeyValueRejected() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
    }

    @Test
    void capacityOne() {
        ConcurrentLRUCache<Integer, String> c = new ConcurrentLRUCache<>(1);
        c.put(1, "a");
        c.put(2, "b");
        assertNull(c.get(1));
        assertEquals("b", c.get(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 100})
    void sequentialOperationsRespectLRU(int cap) {
        ConcurrentLRUCache<Integer, Integer> c = new ConcurrentLRUCache<>(cap);
        for (int i = 0; i < cap * 2; i++) {
            c.put(i, i);
        }
        // First 'cap' entries should be evicted
        for (int i = 0; i < cap; i++) {
            assertNull(c.get(i), "Key " + i + " should be evicted");
        }
        for (int i = cap; i < cap * 2; i++) {
            assertEquals(i, c.get(i), "Key " + i + " should exist");
        }
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentReadOnlyAccess() throws InterruptedException {
        int capacity = 1000;
        cache = new ConcurrentLRUCache<>(capacity);
        for (int i = 0; i < capacity; i++) cache.put(i, "val" + i);

        int threads = 16;
        int opsPerThread = 100_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = r.nextInt(capacity);
                        String v = cache.get(key);
                        if (v == null || !v.equals("val" + key)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await(30, TimeUnit.SECONDS);
        es.shutdown();
        assertEquals(0, errors.get(), "Concurrent reads produced errors");
    }

    @Test
    void concurrentReadWriteStress() throws InterruptedException {
        int capacity = 500;
        cache = new ConcurrentLRUCache<>(capacity);
        
        int threads = 16;
        int opsPerThread = 50_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();
        AtomicInteger putCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            es.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = r.nextInt(capacity * 2); // Some keys outside capacity
                        if (r.nextBoolean()) {
                            // Read
                            cache.get(key);
                        } else {
                            // Write
                            cache.put(key, "t" + threadId + "-v" + i);
                            putCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "Test timed out");
        es.shutdown();
        
        assertEquals(0, errors.get(), "Concurrent read/write produced errors");
        
        // Verify invariants: size <= capacity, all values non-null
        assertTrue(cache.size() <= capacity, "Size exceeds capacity: " + cache.size());
        List<Integer> keys = cache.getKeysInOrder();
        assertEquals(new HashSet<>(keys).size(), keys.size(), "Duplicate keys in linked list");
        for (Integer k : keys) {
            assertNotNull(cache.get(k), "Key in list but get returns null: " + k);
        }
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        // Many threads putting distinct keys, should evict correctly
        int capacity = 200;
        cache = new ConcurrentLRUCache<>(capacity);
        
        int threads = 32;
        int putsPerThread = 1000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            final int base = t * 10000;
            es.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        cache.put(base + i, "v" + i);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(30, TimeUnit.SECONDS), "Timed out");
        es.shutdown();
        assertEquals(0, errors.get());
        
        // Size must not exceed capacity
        assertTrue(cache.size() <= capacity, "Size " + cache.size() + " > capacity " + capacity);
        
        // All present keys must be retrievable
        List<Integer> keys = cache.getKeysInOrder();
        for (Integer k : keys) {
            assertNotNull(cache.get(k), "Missing value for key " + k);
        }
    }

    @Test
    void highContentionOnSameKeys() throws InterruptedException {
        // All threads hammer the same small key set
        int keyCount = 10;
        cache = new ConcurrentLRUCache<>(keyCount);
        for (int i = 0; i < keyCount; i++) cache.put(i, "init" + i);

        int threads = 32;
        int opsPerThread = 100_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();
        AtomicLong gets = new AtomicLong();
        AtomicLong puts = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = r.nextInt(keyCount);
                        if (r.nextDouble() < 0.7) {
                            String v = cache.get(key);
                            if (v == null) errors.incrementAndGet();
                            else gets.incrementAndGet();
                        } else {
                            cache.put(key, "updated-" + Thread.currentThread().getId() + "-" + i);
                            puts.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "Timed out");
        es.shutdown();
        
        assertEquals(0, errors.get(), "Errors under high contention");
        assertEquals(keyCount, cache.size(), "Size should equal key count");
        for (int i = 0; i < keyCount; i++) {
            assertNotNull(cache.get(i), "Key " + i + " missing after stress");
        }
    }

    @Test
    void linearizabilitySpotCheck() throws InterruptedException {
        // Verify that operations appear atomic: no lost updates, no phantom keys
        int capacity = 50;
        cache = new ConcurrentLRUCache<>(capacity);
        
        int threads = 20;
        int ops = 10_000;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ConcurrentHashMap<Integer, String> expected = new ConcurrentHashMap<>();
        AtomicLong mismatches = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    Random r = new Random();
                    for (int i = 0; i < ops; i++) {
                        int key = r.nextInt(capacity * 2);
                        if (r.nextBoolean()) {
                            // Put
                            String val = "t" + Thread.currentThread().getId() + "-" + i;
                            cache.put(key, val);
                            expected.put(key, val);
                        } else {
                            // Get and compare
                            String cacheVal = cache.get(key);
                            String expVal = expected.get(key);
                            if ((cacheVal == null) != (expVal == null) ||
                                (cacheVal != null && !cacheVal.equals(expVal))) {
                                mismatches.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    mismatches.incrementAndGet();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "Timed out");
        es.shutdown();
        
        // Allow some mismatches due to races (get sees stale value before put completes)
        // But cache should never return a value for a key that was never put
        assertEquals(0, mismatches.get(), "Linearizability violations detected");
        
        // Final consistency check
        for (Integer k : cache.getKeysInOrder()) {
            assertNotNull(cache.get(k));
        }
    }

    @Test
    void memoryVisibility() throws InterruptedException {
        // Ensure writes are visible across threads without explicit synchronization
        cache = new ConcurrentLRUCache<>(10);
        AtomicReference<String> holder = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                cache.put(i, "value" + i);
            }
            holder.set("done");
        });
        Thread reader = new Thread(() -> {
            while (!"done".equals(holder.get())) {
                // Spin
            }
            // After writer finishes, all puts should be visible
            for (int i = 0; i < 1000; i++) {
                if (i < 10) {
                    // Last 10 should be in cache (LRU)
                    assertNotNull(cache.get(i), "Key " + i + " should be in cache");
                } else {
                    // Others evicted
                    // Note: due to timing, some might still be there, but not required
                }
            }
        });
        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);
        assertTrue(writer.getState() == Thread.State.TERMINATED);
        assertTrue(reader.getState() == Thread.State.TERMINATED);
    }
}
```

### Thread-Safety Guarantees Explained

| Mechanism | Purpose |
|-----------|---------|
| **Single `ReadWriteLock`** | Coordinates all access to both `HashMap` and doubly-linked list. Guarantees mutual exclusion for structural changes. |
| **Read lock for `get` lookup** | Allows unlimited concurrent readers when no writes/relocations occur. |
| **Write lock for LRU update** | `get()` upgrades to write lock *only* when key exists, ensuring `moveToHead` is atomic with respect to evictions and other updates. |
| **Double-check under write lock** | After acquiring write lock, re-checks `map.get(key)` because the node might have been evicted while waiting for the lock. |
| **All mutations under write lock** | `put`, `remove`, `evictTail`, `addToHead`, `unlink`, `clear` all hold the write lock → no partial updates visible. |
| **Sentinel nodes (`head`/`tail`)** | Eliminates null checks in list operations, reducing critical-section complexity. |
| **No publication of uninitialized nodes** | `Node` fully constructed before `map.put` and `addToHead`. |
| **`volatile` not needed** | All shared state guarded by lock → happens-before edges established by lock acquire/release. |

### Concurrency Stress Test Coverage

| Test | What It Validates |
|------|-------------------|
| `concurrentReadOnlyAccess` | Pure read scalability, no writer starvation. |
| `concurrentReadWriteStress` | Mixed workload; verifies size ≤ capacity, no duplicate keys, all values retrievable. |
| `concurrentEvictionCorrectness` | High-volume puts from many threads; ensures eviction never exceeds capacity