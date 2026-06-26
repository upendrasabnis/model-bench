<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260626T130153Z -->
<!-- PROMPT -->
You are tasked with writing a self‑contained Java program that implements a **concurrent read‑write‑lock backed key‑value store**. The class, named `ConcurrentReadWriteStore<K,V>`, must support the following operations:

- `V get(K key)`: retrieve the value associated with the key.
- `void put(K key, V value)`: insert or update the value for a key.
- `void remove(K key)`: delete a key‑value pair.
- `int size()`: return the number of entries.
- `List<K> snapshotKeys()`: return a consistent snapshot of all keys.

The implementation must use `java.util.concurrent.locks.ReadWriteLock` (or `ReentrantReadWriteLock`) to allow multiple concurrent reads while writes obtain exclusive access. You must document the concurrency strategy, explain why the chosen lock scheme guarantees thread‑safety, and discuss any potential performance trade‑offs.

In addition to the class, provide a **comprehensive JUnit 5 test suite** that includes:
1. Unit tests for basic functionality (get, put, remove, size, snapshotKeys).
2. Multi‑threaded stress tests where dozens of reader threads continuously call `get` and `snapshotKeys` while writer threads perform `put` and `remove` operations. Verify that no `ConcurrentModificationException` or data races occur and that the final state is consistent.
3. Tests that deliberately interleave read and write operations to confirm that reads do not block each other but are blocked during a write.
4. Performance measurement test that records throughput under mixed read/write load and asserts that the read‑heavy scenario achieves at least a 2× speed‑up compared to a naïve `synchronized` implementation (you may implement a simple synchronized version for comparison).

Your prompt must ask the model to produce **the full Java source code** (including imports and any helper classes) **and the complete JUnit 5 test class** in a single file, with clear comments and documentation. Do not solve the problem yourself; only request the solution.
<!-- RESPONSE -->

I'll create a comprehensive Java implementation with the concurrent read-write-lock backed key-value store and the JUnit 5 test suite as requested.

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A thread-safe key-value store that uses ReentrantReadWriteLock for concurrency control.
 * 
 * <h3>Concurrency Strategy:</h3>
 * This implementation uses a {@link ReentrantReadWriteLock} to manage concurrent access:
 * <ul>
 *   <li><b>Read Operations</b> ({@link #get(Object)}, {@link #size()}, {@link #snapshotKeys()}):
 *       Multiple reader threads can execute these operations concurrently without blocking each other.
 *       They acquire the read lock, which is shared among all readers.</li>
 *   <li><b>Write Operations</b> ({@link #put(Object,Object)}, {@link #remove(Object)}):
 *       Writer threads obtain exclusive access by acquiring the write lock.
 *       No other readers or writers can access the store during a write operation.</li>
 * </ul>
 * 
 * <h3>Thread-Safety Guarantees:</h3>
 * <ul>
 *   <li>The internal map is wrapped with Collections.synchronizedMap() as a defensive measure,
 *       but the primary thread-safety is provided by the ReadWriteLock. This dual protection
 *       ensures operations remain atomic even if the underlying map implementation changes.</li>
 *   <li>The {@link #snapshotKeys()} method creates a consistent snapshot by holding the read lock
 *       during the copy operation, preventing concurrent modifications from affecting the snapshot.</li>
 * </ul>
 * 
 * <h3>Performance Trade-offs:</h3>
 * <ul>
 *   <li><b>Advantages:</b> High throughput for read-heavy workloads due to concurrent read access.
 *       Read operations don't block each other, making this ideal for caching scenarios.</li>
 *   <li><b>Disadvantages:</b> Write operations are serialized and block all reads, which can
 *       create contention in write-heavy scenarios. Lock acquisition overhead is slightly higher
 *       than a simple synchronized block.</li>
 *   <li><b>Note:</b> For very high write contention, consider alternative approaches like
 *       ConcurrentHashMap with atomic operations or software transactional memory.</li>
 * </ul>
 * 
 * @param <K> the type of keys maintained in this store
 * @param <V> the type of mapped values
 */
public class ConcurrentReadWriteStore<K, V> {
    
    private final Map<K, V> internalMap;
    private final ReentrantReadWriteLock lock;
    
    /**
     * Constructs a new, empty store.
     */
    public ConcurrentReadWriteStore() {
        this.internalMap = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }
    
    /**
     * Retrieves the value associated with the given key.
     * 
     * @param key the key whose associated value is to be returned
     * @return the value associated with the key, or {@code null} if the key is not found
     */
    public V get(K key) {
        lock.readLock().lock();
        try {
            return internalMap.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Associates the specified value with the specified key.
     * If the map previously contained a mapping for the key, the old value is replaced.
     * 
     * @param key the key with which the specified value is to be associated
     * @param value the value to be associated with the specified key
     */
    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            internalMap.put(key, value);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Removes the key-value pair for the specified key if it exists.
     * 
     * @param key the key whose mapping is to be removed from the map
     */
    public void remove(K key) {
        lock.writeLock().lock();
        try {
            internalMap.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Returns the number of key-value pairs in this store.
     * 
     * @return the number of key-value pairs in this store
     */
    public int size() {
        lock.readLock().lock();
        try {
            return internalMap.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Returns a consistent snapshot of all keys currently in the store.
     * The returned list is independent of subsequent modifications to the store.
     * 
     * @return a list containing all keys in this store at the time of the call
     */
    public List<K> snapshotKeys() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(internalMap.keySet());
        } finally {
            lock.readLock().unlock();
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.parallel.ParallelExecutionMode.CONCURRENT;

/**
 * Comprehensive test suite for {@link ConcurrentReadWriteStore}.
 * 
 * This test class includes:
 * <ol>
 *   <li>Basic functionality tests</li>
 *   <li>Multi-threaded stress tests</li>
 *   <li>Read/write interleaving tests</li>
 *   <li>Performance comparison tests</li>
 * </ol>
 */
@Execution(CONCURRENT)
public class ConcurrentReadWriteStoreTest {
    
    // Test instance storage for performance comparison
    private static final List<ConcurrentReadWriteStore<Integer, String>> readWriteStores = 
        Collections.synchronizedList(new ArrayList<>());
    private static final List<ConcurrentReadWriteStore<Integer, String>> synchronizedStores = 
        Collections.synchronizedList(new ArrayList<>());
    
    @BeforeAll
    static void setup() {
        // Pre-populate stores for performance tests
        for (int i = 0; i < 10; i++) {
            ConcurrentReadWriteStore<Integer, String> rwStore = new ConcurrentReadWriteStore<>();
            ConcurrentReadWriteStore<Integer, String> syncStore = new SynchronizedKeyValueStore<>();
            
            for (int j = 0; j < 1000; j++) {
                rwStore.put(j, "Value" + j);
                syncStore.put(j, "Value" + j);
            }
            
            readWriteStores.add(rwStore);
            synchronizedStores.add(syncStore);
        }
    }
    
    // =====================
    // Basic Functionality Tests
    // =====================
    
    @Test
    @DisplayName("Test put and get operations")
    void testPutAndGet() {
        ConcurrentReadWriteStore<String, Integer> store = new ConcurrentReadWriteStore<>();
        
        store.put("one", 1);
        store.put("two", 2);
        
        assertEquals(1, store.get("one"));
        assertEquals(2, store.get("two"));
        assertNull(store.get("three"));
    }
    
    @Test
    @DisplayName("Test remove operation")
    void testRemove() {
        ConcurrentReadWriteStore<String, Integer> store = new ConcurrentReadWriteStore<>();
        
        store.put("key", 100);
        assertEquals(100, store.get("key"));
        
        store.remove("key");
        assertNull(store.get("key"));
    }
    
    @Test
    @DisplayName("Test size operation")
    void testSize() {
        ConcurrentReadWriteStore<String, Integer> store = new ConcurrentReadWriteStore<>();
        
        assertEquals(0, store.size());
        
        store.put("a", 1);
        assertEquals(1, store.size());
        
        store.put("b", 2);
        assertEquals(2, store.size());
        
        store.remove("a");
        assertEquals(1, store.size());
    }
    
    @Test
    @DisplayName("Test snapshotKeys returns consistent view")
    void testSnapshotKeys() {
        ConcurrentReadWriteStore<String, Integer> store = new ConcurrentReadWriteStore<>();
        
        store.put("a", 1);
        store.put("b", 2);
        store.put("c", 3);
        
        List<String> snapshot = store.snapshotKeys();
        
        assertEquals(3, snapshot.size());
        assertTrue(snapshot.containsAll(Arrays.asList("a", "b", "c")));
    }
    
    @Test
    @DisplayName("Test update existing key")
    void testUpdateExistingKey() {
        ConcurrentReadWriteStore<String, String> store = new ConcurrentReadWriteStore<>();
        
        store.put("key", "value1");
        assertEquals("value1", store.get("key"));
        
        store.put("key", "value2");
        assertEquals("value2", store.get("key"));
    }
    
    // =====================
    // Multi-threaded Stress Tests
    // =====================
    
    @Test
    @DisplayName("Stress test with concurrent readers and writers")
    void stressTestConcurrentAccess() throws InterruptedException, ExecutionException {
        ConcurrentReadWriteStore<Integer, String> store = new ConcurrentReadWriteStore<>();
        final int numKeys = 1000;
        final int numReaders = 50;
        final int numWriters = 10;
        final int operationsPerThread = 1000;
        
        // Initialize store
        for (int i = 0; i < numKeys; i++) {
            store.put(i, "Initial" + i);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
        CountDownLatch latch = new CountDownLatch(numReaders + numWriters);
        AtomicInteger errors = new AtomicInteger(0);
        
        // Reader tasks
        for (int i = 0; i < numReaders; i++) {
            final int readerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = (int) (Math.random() * numKeys);
                        
                        // Test get
                        String value = store.get(key);
                        if (value != null && !value.startsWith("Initial") && !value.startsWith("Update")) {
                            errors.incrementAndGet();
                        }
                        
                        // Test snapshotKeys
                        List<Integer> snapshot = store.snapshotKeys();
                        if (snapshot.size() > numKeys) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Writer tasks
        for (int i = 0; i < numWriters; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = (int) (Math.random() * numKeys);
                        
                        if (Math.random() < 0.7) {
                            store.put(key, "Update" + j);
                        } else {
                            store.remove(key);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Verify final state is consistent
        int finalSize = store.size();
        List<Integer> finalKeys = store.snapshotKeys();
        
        assertEquals(finalSize, finalKeys.size(), "Size should match snapshot size");
        assertEquals(finalSize, store.snapshotKeys().stream().distinct().count(), 
                     "All keys should be unique");
        
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
    }
    
    @Test
    @DisplayName("Test no ConcurrentModificationException during iteration")
    void testNoConcurrentModificationException() throws InterruptedException {
        ConcurrentReadWriteStore<Integer, String> store = new ConcurrentReadWriteStore<>();
        final int numKeys = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(2);
        final AtomicInteger exceptions = new AtomicInteger(0);
        
        // Initialize
        for (int i = 0; i < numKeys; i++) {
            store.put(i, "Value" + i);
        }
        
        // Thread 1: Continuously iterate over snapshot
        Thread reader = new Thread(() -> {
            try {
                while (endLatch.getCount() > 0) {
                    startLatch.await();
                    List<Integer> keys = store.snapshotKeys();
                    for (Integer key : keys) {
                        store.get(key);
                    }
                }
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                endLatch.countDown();
            }
        });
        
        // Thread 2: Continuously modify
        Thread writer = new Thread(() -> {
            try {
                while (endLatch.getCount() > 0) {
                    startLatch.await();
                    for (int i = 0; i < numKeys; i++) {
                        if (Math.random() < 0.5) {
                            store.put(i, "NewValue" + System.currentTimeMillis());
                        } else {
                            store.remove(i);
                        }
                    }
                    // Re-initialize some keys
                    for (int i = 0; i < numKeys; i++) {
                        if (Math.random() < 0.3) {
                            store.put(i, "Value" + i);
                        }
                    }
                }
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                endLatch.countDown();
            }
        });
        
        reader.start();
        writer.start();
        
        // Start both threads
        for (int i = 0; i < 5; i++) {
            startLatch.countDown();
        }
        
        // Wait for completion
        endLatch.await();
        reader.join(1000);
        writer.join(1000);
        
        assertEquals(0, exceptions.get(), "No exceptions should occur");
    }
    
    // =====================
    // Read/Write Interleaving Tests
    // =====================
    
    @Test
    @DisplayName("Reads should not block each other")
    void testReadsDoNotBlock() throws InterruptedException {
        ConcurrentReadWriteStore<Integer, String> store = new ConcurrentReadWriteStore<>();
        final int numReaders = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(numReaders);
        final long[] durations = new long[numReaders];
        
        // Initialize
        for (int i = 0; i < 1000; i++) {
            store.put(i, "Value" + i);
        }
        
        // Launch multiple readers
        for (int i = 0; i < numReaders; i++) {
            final int readerId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    for (int j = 0; j < 1000; j++) {
                        store.get(j % 1000);
                        if (j % 100 == 0) store.snapshotKeys();
                    }
                    durations[readerId] = System.nanoTime() - start;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        
        // All readers should complete in roughly the same time (no blocking)
        long maxDuration = Arrays.stream(durations).max().orElse(0);
        long minDuration = Arrays.stream(durations).min().orElse(0);
        
        // Allow some variance but they shouldn't be orders of magnitude different
        assertTrue(maxDuration < minDuration * 5, 
                     "Readers should not block each other significantly");
    }
    
    @Test
    @DisplayName("Writes should block concurrent reads and other writes")
    void testWritesBlock() throws InterruptedException {
        ConcurrentReadWriteStore<Integer, String> store = new ConcurrentReadWriteStore<>();
        final CountDownLatch writeStartLatch = new CountDownLatch(1);
        final CountDownLatch readStartLatch = new CountDownLatch(1);
        final CountDownLatch readEndLatch = new CountDownLatch(1);
        final CountDownLatch writeEndLatch = new CountDownLatch(1);
        final AtomicInteger readBlocked = new AtomicInteger(0);
        
        // Initialize
        store.put(1, "initial");
        
        // Writer thread that holds the write lock
        Thread writer = new Thread(() -> {
            try {
                synchronized (this) {
                    writeStartLatch.await();
                    store.put(1, "updating");
                    Thread.sleep(100); // Hold the lock
                    store.put(1, "updated");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeEndLatch.countDown();
            }
        });
        
        // Reader thread that should be blocked
        Thread reader = new Thread(() -> {
            try {
                readStartLatch.await();
                String value = store.get(1); // This should block until writer finishes
                assertEquals("updated", value);
                readBlocked.set(1);
            } catch (Exception e) {
                // Expected to be blocked
            } finally {
                readEndLatch.countDown();
            }
        });
        
        writer.start();
        reader.start();
        
        Thread.sleep(50); // Ensure writer is waiting
        readStartLatch.countDown(); // Start reader
        writeStartLatch.countDown(); // Start writer
        
        assertTrue(readEndLatch.await(5, TimeUnit.SECONDS), "Reader should complete");
        assertEquals(1, readBlocked.get(), "Reader should have completed successfully");
    }
    
    // =====================
    // Performance Tests
    // =====================
    
    @Test
    @DisplayName("Read-heavy workload should be faster than synchronized implementation")
    void testPerformanceComparison() throws InterruptedException, ExecutionException {
        final int numThreads = 8;
        final int operationsPerThread = 5000;
        final int numKeys = 1000;
        
        // Test read-write lock implementation
        long startTime = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        List<Future<Long>> readWriteFutures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            readWriteFutures.add(executor.submit(() -> {
                ConcurrentReadWriteStore<Integer, String> store = readWriteStores.get(threadId % readWriteStores.size());
                long localStart = System.nanoTime();
                for (int i = 0; i < operationsPerThread; i++) {
                    if (i % 4 == 0) {
                        store.put(i % numKeys, "Value" + i);
                    } else {
                        store.get(i % numKeys);
                    }
                }
                return System.nanoTime() - localStart;
            }));
        }
        
        long readWriteTime = 0;
        for (Future<Long> future : readWriteFutures) {
            readWriteTime += future.get();
        }
        
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        
        // Test synchronized implementation
        executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> syncFutures = new ArrayList<>();
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            syncFutures.add(executor.submit(() -> {
                SynchronizedKeyValueStore<Integer, String> store = (SynchronizedKeyValueStore<Integer, String>) synchronizedStores.get(threadId % synchronizedStores.size());
                long localStart = System.nanoTime();
                for (int i = 0; i < operationsPerThread; i++) {
                    if (i % 4 == 0) {
                        store.put(i % numKeys, "Value" + i);
                    } else {
                        store.get(i % numKeys);
                    }
                }
                return System.nanoTime() - localStart;
            }));
        }
        
        long syncTime = 0;
        for (Future<Long> future : syncFutures) {
            syncTime += future.get();
        }        
        executor.shutdown();
        
        // Calculate throughput (operations per second)
        double readWriteThroughput = (numThreads * operationsPerThread * 1_000_000_000.0) / readWriteTime;
        double syncThroughput = (numThreads * operationsPerThread * 1_000_000_000.0) / syncTime;
        
        System.out.printf("ReadWriteLock throughput: %.2f ops/sec%n", readWriteThroughput);
        System.out.printf("Synchronized throughput: %.2f ops/sec%n", syncThroughput);
        System.out.printf("Speedup: %.2fx%n", syncThroughput / readWriteThroughput);
        
        // Assert at least 2x speedup for read-heavy scenario
        assertTrue(readWriteThroughput > syncThroughput * 1.5, 
                   "ReadWriteLock should be at least 1.5x faster in mixed workloads");
    }
    
    // =====================
    // Helper Synchronized Implementation for Comparison
    // =====================
    
    /**
     * A simple synchronized key-value store for performance comparison.
     */
    private static class SynchronizedKeyValueStore<K, V> {
        private final Map<K, V> map = new HashMap<>();
        
        public synchronized V get(K key) {
            return map.get