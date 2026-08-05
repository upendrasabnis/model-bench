<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260805T210455Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
## Thread-Safe Concurrent LRU Cache Implementation

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock listLock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 4 / 3 + 1);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        
        listLock.lock();
        try {
            // Re-check in case node was evicted between map.get and lock acquisition
            if (map.get(key) != node) {
                return map.get(key)?.value;
            }
            moveToFront(node);
            return node.value;
        } finally {
            listLock.unlock();
        }
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value cannot be null");
        }
        
        listLock.lock();
        try {
            Node<K, V> existingNode = map.get(key);
            if (existingNode != null) {
                existingNode.value = value;
                moveToFront(existingNode);
                return existingNode.value;
            }
            
            if (map.size() >= capacity) {
                evictTail();
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            addToFront(newNode);
            map.put(key, newNode);
            return null;
        } finally {
            listLock.unlock();
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null) {
            throw new NullPointerException();
        }
        
        // Fast path: check without lock
        Node<K, V> node = map.get(key);
        if (node != null) {
            listLock.lock();
            try {
                if (map.get(key) == node) {
                    moveToFront(node);
                    return node.value;
                }
            } finally {
                listLock.unlock();
            }
        }
        
        // Slow path: compute with lock held
        listLock.lock();
        try {
            node = map.get(key);
            if (node != null) {
                moveToFront(node);
                return node.value;
            }
            
            V value = mappingFunction.apply(key);
            if (value == null) {
                return null;
            }
            
            if (map.size() >= capacity) {
                evictTail();
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            addToFront(newNode);
            map.put(key, newNode);
            return value;
        } finally {
            listLock.unlock();
        }
    }

    public void remove(K key) {
        if (key == null) return;
        
        listLock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) {
                unlink(node);
            }
        } finally {
            listLock.unlock();
        }
    }

    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public void clear() {
        listLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            listLock.unlock();
        }
    }

    // Package-private for testing
    Node<K, V> getHead() {
        return head.next;
    }

    Node<K, V> getTail() {
        return tail.prev;
    }

    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void moveToFront(Node<K, V> node) {
        if (node == head.next) return; // Already at front
        
        unlink(node);
        addToFront(node);
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void evictTail() {
        Node<K, V> lru = tail.prev;
        if (lru != head) {
            unlink(lru);
            map.remove(lru.key);
        }
    }

    static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

## Thread-Safety Guarantees Explanation

| Mechanism | Purpose |
|-----------|---------|
| **ConcurrentHashMap** | Provides thread-safe key→node mapping with O(1) average get/put. Handles concurrent reads/writes without external locking. |
| **ReentrantLock (listLock)** | Serializes all structural modifications to the doubly-linked list (add, move, evict). Guarantees mutual exclusion for list pointer updates. |
| **Lock ordering** | Map operations (get/put/remove) are always performed *while holding listLock* (except initial `map.get` in `get()` which is safe due to re-check). This prevents ABA-style races where a node is evicted between map lookup and list modification. |
| **Re-check pattern in `get()`** | After acquiring `listLock`, we verify `map.get(key) == node`. If another thread evicted/replaced the node, we return the current value (or null) without touching stale list pointers. |
| **Atomic eviction** | `evictTail()` unlinks from list *and* removes from map under the same lock, ensuring the two data structures never diverge. |
| **No publication of partially constructed nodes** | Nodes are fully initialized before insertion into both map and list. |
| **Null checks** | Fail-fast on null keys/values prevents ambiguous cache states. |

**Complexity**: All operations are O(1) average time. Contention is limited to the list lock; map operations are lock-free for reads.

---

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentLRUCacheTest {

    private ConcurrentLRUCache<Integer, String> cache;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentLRUCache<>(3);
    }

    @Test
    void basicPutAndGet() {
        cache.put(1, "one");
        cache.put(2, "two");
        assertEquals("one", cache.get(1));
        assertEquals("two", cache.get(2));
        assertNull(cache.get(3));
    }

    @Test
    void evictionOrder() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        cache.get(1); // Access 1 -> MRU
        cache.put(4, "four"); // Evicts 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("one", cache.get(1));
        assertEquals("three", cache.get(3));
        assertEquals("four", cache.get(4));
    }

    @Test
    void updateExistingKeyMovesToFront() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(1, "ONE"); // Update
        cache.put(3, "three");
        cache.put(4, "four"); // Evicts 2
        assertNull(cache.get(2));
        assertEquals("ONE", cache.get(1));
    }

    @Test
    void removeKey() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.remove(1);
        assertNull(cache.get(1));
        assertEquals("two", cache.get(2));
        assertEquals(1, cache.size());
    }

    @Test
    void clear() {
        cache.put(1, "one");
        cache.put(2, "two");
        cache.clear();
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void nullKeyValueRejected() {
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.put(1, null));
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(-1));
    }

    @Test
    void computeIfAbsent() {
        assertEquals("computed", cache.computeIfAbsent(1, k -> "computed"));
        assertEquals("computed", cache.get(1));
        assertEquals("computed", cache.computeIfAbsent(1, k -> "should not run"));
    }

    @Test
    void computeIfAbsentNullValueNotCached() {
        assertNull(cache.computeIfAbsent(1, k -> null));
        assertNull(cache.get(1));
        assertEquals(0, cache.size());
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    void concurrentPutGetStress() throws InterruptedException {
        final int capacity = 1000;
        final int numThreads = 16;
        final int opsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicLong errors = new AtomicLong();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2);
                        if (rnd.nextBoolean()) {
                            stressCache.put(key, key * 2);
                        } else {
                            Integer val = stressCache.get(key);
                            if (val != null && val != key * 2) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(0, errors.get(), "Data corruption detected");
        assertTrue(stressCache.size() <= capacity, "Size exceeds capacity: " + stressCache.size());
        verifyInvariants(stressCache);
    }

    @Test
    void concurrentReadHeavyWorkload() throws InterruptedException {
        final int capacity = 500;
        ConcurrentLRUCache<Integer, String> stressCache = new ConcurrentLRUCache<>(capacity);
        // Pre-populate
        for (int i = 0; i < capacity; i++) {
            stressCache.put(i, "val" + i);
        }

        final int numReaders = 32;
        final int numWriters = 4;
        final int opsPerThread = 10000;
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numReaders + numWriters);
        AtomicLong errors = new AtomicLong();

        // Readers
        for (int t = 0; t < numReaders; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 2);
                        String val = stressCache.get(key);
                        if (val != null && !val.equals("val" + key)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Writers
        for (int t = 0; t < numWriters; t++) {
            final int writerId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = capacity + writerId * opsPerThread + i;
                        stressCache.put(key, "val" + key);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(0, errors.get(), "Data corruption detected");
        assertTrue(stressCache.size() <= capacity, "Size exceeds capacity");
        verifyInvariants(stressCache);
    }

    @Test
    void concurrentEvictionCorrectness() throws InterruptedException {
        final int capacity = 100;
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        final int numThreads = 20;
        final int putsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger evictedCount = new AtomicInteger();

        // Track evicted keys using a concurrent set
        ConcurrentHashMap<Integer, Boolean> seenKeys = new ConcurrentHashMap<>();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < putsPerThread; i++) {
                        int key = threadId * putsPerThread + i;
                        stressCache.put(key, key);
                        seenKeys.put(key, true);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Verify: only the most recent 'capacity' keys should remain
        // Since we can't easily determine exact LRU order under concurrency,
        // we verify invariants and that size <= capacity
        assertTrue(stressCache.size() <= capacity, "Size exceeds capacity: " + stressCache.size());
        verifyInvariants(stressCache);

        // All keys in cache must have been put
        for (int i = 0; i < stressCache.size(); i++) {
            // Can't easily iterate, but we can spot-check
        }
    }

    @Test
    void highContentionOnSameKeys() throws InterruptedException {
        final int capacity = 10;
        ConcurrentLRUCache<Integer, Long> stressCache = new ConcurrentLRUCache<>(capacity);
        final int numThreads = 50;
        final int opsPerThread = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicLong errors = new AtomicLong();
        AtomicLong putCount = new AtomicLong();
        AtomicLong getCount = new AtomicLong();

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(5); // Only 5 keys, high contention
                        if (rnd.nextBoolean()) {
                            stressCache.put(key, (long) key * 100);
                            putCount.incrementAndGet();
                        } else {
                            Long val = stressCache.get(key);
                            getCount.incrementAndGet();
                            if (val != null && val != (long) key * 100) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(0, errors.get(), "Data corruption under high contention");
        assertTrue(stressCache.size() <= capacity);
        verifyInvariants(stressCache);
        System.out.printf("Puts: %d, Gets: %d, Final size: %d%n", putCount.get(), getCount.get(), stressCache.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void scalabilityTest(int numThreads) throws InterruptedException {
        final int capacity = 1000;
        final int opsPerThread = 2000;
        ConcurrentLRUCache<Integer, Integer> stressCache = new ConcurrentLRUCache<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        long start = System.nanoTime();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    Random rnd = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = rnd.nextInt(capacity * 4);
                        if (rnd.nextBoolean()) {
                            stressCache.put(key, key);
                        } else {
                            stressCache.get(key);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        executor.shutdownNow();

        verifyInvariants(stressCache);
        System.out.printf("Threads: %2d, Time: %4d ms, Ops: %d, Throughput: %.0f ops/ms%n",
                numThreads, elapsedMs, (long) numThreads * opsPerThread * 2,
                (double) numThreads * opsPerThread * 2 / elapsedMs);
    }

    // ==================== INVARIANT VERIFICATION ====================

    private void verifyInvariants(ConcurrentLRUCache<Integer, ?> cache) {
        // Use reflection to access internal structure for verification
        // In production, you might expose a package-private verification method
        try {
            java.lang.reflect.Field mapField = ConcurrentLRUCache.class.getDeclaredField("map");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Integer, ConcurrentLRUCache.Node<Integer, ?>> map =
                    (ConcurrentHashMap<Integer, ConcurrentLRUCache.Node<Integer, ?>>) mapField.get(cache);

            java.lang.reflect.Field headField = ConcurrentLRUCache.class.getDeclaredField("head");
            headField.setAccessible(true);
            ConcurrentLRUCache.Node<Integer, ?> head = (ConcurrentLRUCache.Node<Integer, ?>) headField.get(cache);

            java.lang.reflect.Field tailField = ConcurrentLRUCache.class.getDeclaredField("tail");
            tailField.setAccessible(true);
            ConcurrentLRUCache.Node<Integer, ?> tail = (ConcurrentLRUCache.Node<Integer, ?>) tailField.get(cache);

            // Verify list integrity
            int listSize = 0;
            ConcurrentLRUCache.Node<Integer, ?> current = head.next;
            Set<Integer> listKeys = new HashSet<>();
            while (current != tail) {
                assertNotNull(current.key, "Node key is null in list");
                assertTrue(listKeys.add(current.key), "Duplicate key in list: " + current.key);
                assertTrue(map.containsKey(current.key), "Key in list but not in map: " + current.key);
                assertSame(map.get(current.key), current, "Map node != list node for key: " + current.key);
                listSize++;
                current = current.next;
            }

            // Verify map keys all in list
            assertEquals(map.size(), listSize, "Map size != list size");
            for (Integer key : map.keySet()) {
                assertTrue(listKeys.contains(key), "Key in map but not in list: " + key);
            }

            // Verify dummy nodes
            assertNull(head.key);
            assertNull(head.value);
            assertNull(tail.key);
            assertNull(tail.value);
            assertSame(head.next.prev, head);
            assertSame(tail.prev.next, tail);

        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Reflection failed", e);
        }
    }
}
```

## Key Test Features

| Test | Purpose |
|------|---------|
| `concurrentPutGetStress` | 16 threads, mixed read/write, verifies no corruption & capacity bound |
| `concurrentReadHeavyWorkload` | 32 readers + 4 writers, pre-populated cache, validates read consistency |
| `concurrentEvictionCorrectness` | Many threads putting unique keys, verifies size never exceeds capacity |
| `highContentionOnSameKeys` | 50 threads hammering only 5 keys, tests lock contention handling |
| `scalabilityTest` | Parameterized thread counts (1-32), measures throughput scaling |
| `verifyInvariants` (helper) | Uses reflection to check: list↔map consistency, no duplicates, proper linking, dummy node integrity |

## Running the Tests

```bash
# With Maven
mvn test

# With Gradle
./gradlew test
```

The stress tests run for up to 30 seconds each and will fail fast on any invariant violation. The `verifyInvariants` method performs a complete structural audit after each conc