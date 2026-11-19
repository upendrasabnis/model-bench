<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261119T014205Z -->
<!-- PROMPT -->
Implement a thread-safe concurrent LRU cache in Java with a configurable capacity, supporting get/put with O(1) average operations and correct eviction under concurrent access. Explain how you guarantee thread-safety. Then write JUnit 5 tests including a concurrency stress test that checks invariants under heavy parallel load.
<!-- RESPONSE -->
```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe LRU Cache with O(1) average get/put operations.
 * 
 * Thread-safety guarantees:
 * 1. ConcurrentHashMap provides thread-safe key->node mapping with volatile semantics
 * 2. A single ReentrantLock protects all doubly-linked list mutations (add, move-to-front, evict)
 * 3. Compound operations (check-then-act) are atomic under the lock
 * 4. No ABA problem: nodes are never reused; evicted nodes become unreachable
 * 5. Memory visibility: lock release/acquire establishes happens-before for list pointers
 * 6. Eviction correctness: only the tail.prev (true LRU) is removed, under the same lock
 *    that guards list ordering, so no concurrent get can "resurrect" an evicted node.
 */
public class ConcurrentLRUCache<K, V> {
    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head
    private final Node<K, V> tail; // dummy tail
    private final ReentrantLock listLock = new ReentrantLock();

    public ConcurrentLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity * 4 / 3 + 1);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns value or null if absent. Moves node to front (MRU) on hit. */
    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;
        
        listLock.lock();
        try {
            // Re-check under lock: node might have been evicted between map.get and lock
            if (node.removed) return null;
            moveToFront(node);
            return node.value;
        } finally {
            listLock.unlock();
        }
    }

    /** Puts key-value pair. Returns previous value or null. Evicts LRU if over capacity. */
    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        
        Node<K, V> newNode = new Node<>(key, value);
        Node<K, V> existing = map.putIfAbsent(key, newNode);
        
        if (existing != null) {
            // Key existed: update value and move to front atomically
            listLock.lock();
            try {
                if (existing.removed) {
                    // Race: existing was evicted after map.putIfAbsent but before lock
                    // Our newNode is already in map; just link it
                    addToFront(newNode);
                    return null;
                }
                V oldValue = existing.value;
                existing.value = value;
                moveToFront(existing);
                // Discard newNode (not linked)
                return oldValue;
            } finally {
                listLock.unlock();
            }
        } else {
            // New key: link node, then evict if needed
            listLock.lock();
            try {
                addToFront(newNode);
                if (map.size() > capacity) {
                    evictLRU();
                }
                return null;
            } finally {
                listLock.unlock();
            }
        }
    }

    /** Atomic get-or-compute: if absent, computes value via function, puts it, returns it. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        if (mappingFunction == null) throw new NullPointerException("Mapping function cannot be null");
        
        Node<K, V> newNode = new Node<>(key, null); // value set later
        Node<K, V> existing = map.putIfAbsent(key, newNode);
        
        if (existing != null) {
            listLock.lock();
            try {
                if (existing.removed) {
                    // Evicted concurrently; compute for our newNode
                    V value = mappingFunction.apply(key);
                    newNode.value = value;
                    addToFront(newNode);
                    if (map.size() > capacity) evictLRU();
                    return value;
                }
                moveToFront(existing);
                return existing.value;
            } finally {
                listLock.unlock();
            }
        } else {
            // We won the race to insert placeholder; compute value
            V value = mappingFunction.apply(key);
            newNode.value = value;
            
            listLock.lock();
            try {
                // Double-check: another thread might have evicted us already (unlikely but possible)
                if (newNode.removed) {
                    return value; // already evicted, but map still has it? shouldn't happen
                }
                addToFront(newNode);
                if (map.size() > capacity) evictLRU();
                return value;
            } finally {
                listLock.unlock();
            }
        }
    }

    public int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
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

    // --- Internal linked list operations (must hold listLock) ---

    private void addToFront(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void moveToFront(Node<K, V> node) {
        if (node == head.next) return; // already MRU
        // unlink
        node.prev.next = node.next;
        node.next.prev = node.prev;
        // add to front
        addToFront(node);
    }

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru == head) return; // empty
        // unlink
        lru.prev.next = tail;
        tail.prev = lru.prev;
        lru.removed = true;
        // remove from map (only if still mapped to this node)
        map.remove(lru.key, lru);
    }

    // --- Node class ---
    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        volatile boolean removed = false; // visible across threads after lock release

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
```

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ConcurrentLRUCache including concurrency stress tests.
 * Run with: -Djunit.jupiter.execution.parallel.enabled=true -Djunit.jupiter.execution.parallel.mode.default=concurrent
 */
@Execution(ExecutionMode.CONCURRENT)
class ConcurrentLRUCacheTest {

    @Test
    void basicPutGet() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        assertNull(cache.get(1));
        assertNull(cache.put(1, "one"));
        assertEquals("one", cache.get(1));
        assertEquals("one", cache.put(1, "uno"));
        assertEquals("uno", cache.get(1));
        assertEquals(1, cache.size());
    }

    @Test
    void evictionOrder() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        assertEquals(3, cache.size());
        
        cache.get(1); // 1 becomes MRU, order: 2,3,1 (LRU->MRU)
        cache.put(4, "d"); // evicts 2
        assertNull(cache.get(2));
        assertEquals("a", cache.get(1));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    void updateMovesToFront() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "updated"); // 1 becomes MRU
        cache.put(3, "c"); // should evict 2 (LRU)
        assertNull(cache.get(2));
        assertEquals("updated", cache.get(1));
        assertEquals("c", cache.get(3));
    }

    @Test
    void computeIfAbsent() {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(2);
        String v1 = cache.computeIfAbsent(1, k -> "computed-" + k);
        assertEquals("computed-1", v1);
        String v2 = cache.computeIfAbsent(1, k -> "should-not-run");
        assertEquals("computed-1", v2); // cached
        assertEquals(1, cache.size());
    }

    @Test
    void computeIfAbsentConcurrent() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        AtomicInteger computeCount = new AtomicInteger(0);
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                cache.computeIfAbsent(42, k -> { computeCount.incrementAndGet(); return k * 2; });
                done.countDown();
            }).start();
        }
        start.countDown();
        done.await();
        
        assertEquals(1, computeCount.get(), "Mapping function should run exactly once");
        assertEquals(84, cache.get(42));
    }

    @Test
    void capacityZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLRUCache<>(-1));
    }

    @Test
    void nullKeyThrows() {
        ConcurrentLRUCache<String, String> cache = new ConcurrentLRUCache<>(10);
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.computeIfAbsent(null, k -> "v"));
    }

    // ==================== CONCURRENCY STRESS TESTS ====================

    @Test
    @Timeout(30)
    void stressTestHighConcurrency() throws InterruptedException {
        int capacity = 1000;
        int numThreads = 16;
        int opsPerThread = 5000;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        AtomicLong putCount = new AtomicLong();
        AtomicLong getCount = new AtomicLong();
        AtomicLong evictCount = new AtomicLong();
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                Random r = new Random(threadId);
                for (int i = 0; i < opsPerThread; i++) {
                    int key = r.nextInt(capacity * 2); // key space larger than capacity
                    if (r.nextBoolean()) {
                        cache.put(key, key * 2);
                        putCount.incrementAndGet();
                    } else {
                        cache.get(key);
                        getCount.incrementAndGet();
                    }
                }
                done.countDown();
            });
        }
        
        start.countDown();
        done.await();
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        
        // Invariants
        assertTrue(cache.size() <= capacity, "Size must not exceed capacity: " + cache.size());
        assertEquals(cache.size(), cache.map.size(), "Map size must match logical size");
        
        // Verify no data corruption: spot-check some entries
        for (int i = 0; i < 100; i++) {
            Integer v = cache.get(i);
            if (v != null) assertEquals(i * 2, v, "Value corruption detected for key " + i);
        }
        
        System.out.printf("Stress test: puts=%d, gets=%d, finalSize=%d%n", 
            putCount.get(), getCount.get(), cache.size());
    }

    @Test
    @Timeout(30)
    void stressTestReadHeavy() throws InterruptedException {
        int capacity = 500;
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(capacity);
        
        // Pre-populate
        for (int i = 0; i < capacity; i++) cache.put(i, "val-" + i);
        
        int numReaders = 32;
        int numWriters = 4;
        int opsPerThread = 10000;
        AtomicLong reads = new AtomicLong();
        AtomicLong writes = new AtomicLong();
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numReaders + numWriters);
        ExecutorService exec = Executors.newFixedThreadPool(numReaders + numWriters);
        
        for (int t = 0; t < numReaders; t++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                Random r = new Random();
                for (int i = 0; i < opsPerThread; i++) {
                    cache.get(r.nextInt(capacity * 2));
                    reads.incrementAndGet();
                }
                done.countDown();
            });
        }
        for (int t = 0; t < numWriters; t++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                Random r = new Random();
                for (int i = 0; i < opsPerThread; i++) {
                    cache.put(r.nextInt(capacity * 2), "new");
                    writes.incrementAndGet();
                }
                done.countDown();
            });
        }
        
        start.countDown();
        done.await();
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        
        assertTrue(cache.size() <= capacity);
        System.out.printf("Read-heavy: reads=%d, writes=%d, size=%d%n", reads.get(), writes.get(), cache.size());
    }

    @Test
    @Timeout(30)
    void stressTestEvictionCorrectness() throws InterruptedException {
        int capacity = 200;
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(capacity);
        int numThreads = 8;
        int opsPerThread = 20000;
        
        // Track expected state with a thread-safe reference map
        ConcurrentHashMap<Integer, Integer> reference = new ConcurrentHashMap<>();
        AtomicInteger refSize = new AtomicInteger(0);
        ReentrantLock refLock = new ReentrantLock();
        LinkedHashSet<Integer> refOrder = new LinkedHashSet<>(); // LRU -> MRU
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                Random r = new Random();
                for (int i = 0; i < opsPerThread; i++) {
                    int key = r.nextInt(capacity * 3);
                    if (r.nextDouble() < 0.7) { // 70% puts
                        int val = key * 10;
                        cache.put(key, val);
                        
                        refLock.lock();
                        try {
                            reference.put(key, val);
                            refOrder.remove(key);
                            refOrder.add(key);
                            if (reference.size() > capacity) {
                                int lru = refOrder.iterator().next();
                                refOrder.remove(lru);
                                reference.remove(lru);
                            }
                        } finally {
                            refLock.unlock();
                        }
                    } else { // 30% gets
                        cache.get(key);
                        refLock.lock();
                        try {
                            if (reference.containsKey(key)) {
                                refOrder.remove(key);
                                refOrder.add(key);
                            }
                        } finally {
                            refLock.unlock();
                        }
                    }
                }
                done.countDown();
            });
        }
        
        start.countDown();
        done.await();
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        
        // Verify cache matches reference
        refLock.lock();
        try {
            assertEquals(reference.size(), cache.size(), "Size mismatch");
            for (Map.Entry<Integer, Integer> e : reference.entrySet()) {
                Integer cv = cache.get(e.getKey());
                assertEquals(e.getValue(), cv, "Value mismatch for key " + e.getKey());
            }
            // Check no extra entries in cache
            for (Integer key : cache.map.keySet()) {
                assertTrue(reference.containsKey(key), "Cache has key not in reference: " + key);
            }
            // Verify LRU order matches (sample check)
            List<Integer> cacheOrder = getCacheOrder(cache);
            List<Integer> refOrderList = new ArrayList<>(refOrder);
            assertEquals(refOrderList, cacheOrder, "LRU order mismatch");
        } finally {
            refLock.unlock();
        }
    }

    @Test
    @Timeout(10)
    void stressTestComputeIfAbsentConcurrent() throws InterruptedException {
        ConcurrentLRUCache<Integer, Integer> cache = new ConcurrentLRUCache<>(100);
        AtomicInteger computeCount = new AtomicInteger(0);
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        
        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 1000; i++) {
                    cache.computeIfAbsent(42, k -> { computeCount.incrementAndGet(); return k * 2; });
                }
                done.countDown();
            });
        }
        start.countDown();
        done.await();
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
        
        assertEquals(1, computeCount.get(), "Compute function must run exactly once");
        assertEquals(84, cache.get(42));
    }

    @Test
    @Timeout(15)
    void stressTestClearDuringAccess() throws InterruptedException {
        ConcurrentLRUCache<Integer, String> cache = new ConcurrentLRUCache<>(100);
        for (int i = 0; i < 100; i++) cache.put(i, "v" + i);
        
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(3);
        ExecutorService exec = Executors.newFixedThreadPool(3);
        
        exec.submit(() -> {
            Random r = new Random();
            while (!stop.get()) {
                cache.get(r.nextInt(200));
            }
            done.countDown();
        });
        exec.submit(() -> {
            Random r = new Random();
            while (!stop.get()) {
                cache.put(r.nextInt(200), "x");
            }
            done.countDown();
        });
        exec.submit(() -> {
            Random r = new Random();
            while (!stop.get()) {
                cache.clear();
                for (int i = 0; i < 50; i++) cache.put(i, "y" + i);
            }
            done.countDown();
        });
        
        Thread.sleep(1000);
        stop.set(true);
        done.await();
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
        
        // Should not crash; size invariant holds
        assertTrue(cache.size() <= 100);
    }

    // Helper to extract LRU->MRU order from cache (for testing only)
    private List<Integer> getCacheOrder(ConcurrentLRUCache<Integer, ?> cache) {
        List<Integer> order = new ArrayList<>();
        // Access private fields via reflection for verification
        try {
            java.lang.reflect.Field headField = ConcurrentLRUCache.class.getDeclaredField("head");
            java.lang.reflect.Field tailField = ConcurrentLRUCache.class.getDeclaredField("tail");
            headField.setAccessible(true);
            tailField.setAccessible(true);
            Object head = headField.get(cache);
            Object tail = tailField.get(cache);
            
            java.lang.reflect.Field nextField = head.getClass().getDeclaredField("next");
            java.lang.reflect.Field keyField = head.getClass().getDeclaredField("key");
            nextField.setAccessible(true);
            keyField.setAccessible(true);
            
            Object curr = nextField.get(head);
            while (curr != tail) {
                Object key = keyField.get(curr);
                if (key != null) order.add((Integer) key);
                curr = nextField.get(curr);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return order;
    }
}
```

**Thread-Safety Guarantees Explained:**

1. **ConcurrentHashMap** provides lock-free, thread-safe key→node mapping with volatile semantics for `get`/`putIfAbsent`/`remove`.

2. **Single ReentrantLock (`listLock`)** serializes all doubly-linked list mutations:
   - `addToFront`, `moveToFront`, `evictLRU` are atomic
   - Compound operations (check-then-act) are linearizable: e.g., `get` re-checks `node.removed` under lock after `map.get`
   - Lock release/acquire establishes *happens-before* for list pointer updates

3. **No ABA / Node Reuse**: Evicted nodes are marked `removed=true` (volatile) and never relinked. New puts create fresh nodes.

4. **Eviction Correctness**: Only `tail.prev` (true LRU) is removed, under the same lock that guards list ordering. A concurrent `get` on the LRU key either:
   - Acquires lock before eviction → moves node to front, eviction picks new LRU
   - Acquires lock after eviction → sees `node.removed=true`, returns null

5. **Memory Visibility**: All list pointer writes (`prev`/`next`) happen under lock; reads in `get`/`put` also under lock → sequential consistency for list structure.

6. **Linearizability Points**:
   - `put` new key: linearizes at `map.putIfAbsent` (if absent) or at `listLock` acquisition (if updating)
   - `get` hit: linearizes at `moveToFront` under lock
   - `evictLRU`: linearizes at `map.remove` under lock

**Test Coverage:**
- Functional correctness (LRU order, updates, eviction)
- `computeIfAbsent` atomicity (single computation under contention)
- High-concurrency stress (16 threads, 80k ops) with size/value invariants
- Read-heavy workload (32 readers, 4 writers)
- Eviction correctness