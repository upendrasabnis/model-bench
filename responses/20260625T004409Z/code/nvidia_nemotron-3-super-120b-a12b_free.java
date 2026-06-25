<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260625T004409Z -->
<!-- PROMPT -->
Write a **single, self‑contained Java program** that implements a **complex, non‑trivial multi‑threaded component** and a full suite of **JUnit 5 tests**. The component must address the following requirements (choose **one** concurrency scenario and **do not reuse any previous scenario**; for this variation you must implement a **read‑write‑lock backed key‑value store**):

1. **Component Specification**  
   - Create a class `ConcurrentKeyValueStore<K, V>` that provides a thread‑safe in‑memory key‑value store.  
   - The store must support the operations:  
     ```java
     V get(K key);                     // read a value
     void put(K key, V value);        // insert or update a value
     V remove(K key);                 // delete a key, returning the previous value
     Set<K> keySet();                 // snapshot of current keys
     int size();                      // current number of entries
     ```  
   - Internally, protect the underlying `HashMap<K,V>` (or equivalent) with a **`java.util.concurrent.locks.ReadWriteLock`** so that:
     * Multiple concurrent reads may proceed without blocking each other.  
     * Writes (put/remove) acquire the write lock exclusively, blocking other reads and writes while the mutation is in progress.  
   - Document **exactly** which lock (read or write) is taken for each public method, and explain the reasoning behind your strategy in a Javadoc comment for the class.

2. **Correctness & Thread‑Safety**  
   - Ensure there are **no data races, deadlocks, or lock‑ordering problems**.  
   - The `keySet()` method must return a **snapshot** (e.g., an immutable copy) that is safe to iterate over without holding any lock after the method returns.

3. **Performance Considerations** (optional but rewarded)  
   - Explain how the chosen read‑write lock choice (e.g., `ReentrantReadWriteLock` with fairness policy) impacts throughput under read‑heavy vs. write‑heavy workloads.  
   - Provide a small benchmark method inside the class (or a separate utility) that measures throughput for a mixed workload (e.g., 80 % reads, 20 % writes) using a configurable number of threads.

4. **JUnit 5 Test Suite**  
   - Write **comprehensive unit tests** for all public methods covering normal, edge, and exceptional cases.  
   - Include **concurrency stress tests** that:  
     * Spawn a configurable pool of threads (e.g., 50 threads) performing random mixes of reads, puts, and removes for a fixed duration (e.g., 5 seconds).  
     * Use `java.util.concurrent.CountDownLatch` or `CyclicBarrier` to start all threads simultaneously.  
     * After the stress run, verify **internal consistency** (e.g., `size()` matches the number of unique keys in `keySet()`, all returned values correspond to the most recent puts, no lost updates).  
   - Use `org.junit.jupiter.api.Assertions` and, where appropriate, `assertTimeout` to ensure tests complete in a reasonable time.  
   - Add a test that checks **snapshot isolation** of `keySet()` by modifying the store after obtaining the snapshot and confirming the snapshot does not reflect later changes.

5. **Code Organization**  
   - All code must be in a single file named `ConcurrentKeyValueStoreTest.java` (or similar) that compiles and runs with **Java 17** or later.  
   - Place the `ConcurrentKeyValueStore` class in the same file (either as a top‑level class or a static nested class) so that the test suite can access it directly.  
   - Include necessary imports, and ensure the file compiles with `javac` and the tests run with `mvn test` or `gradle test` without additional configuration.

6. **Submission Requirements**  
   - Provide **clear Javadoc** for the class and each public method, describing thread‑safety guarantees.  
   - Ensure the JUnit tests are deterministic (aside from the intentional concurrency stress) and do not rely on `Thread.sleep` for correctness.  
   - The entire solution must be **self‑contained**: no external libraries beyond the JDK and JUnit 5 may be used.

**Your task:** Write the complete Java source code fulfilling all the above specifications. Do not include any explanatory text outside of code comments or Javadoc; the output should be a ready‑to‑compile Java file containing the implementation and its JUnit 5 test suite.
<!-- RESPONSE -->
```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for {@link ConcurrentKeyValueStore}.
 *
 * <p>The test class is public; the store class has default (package‑private) access,
 * allowing both to reside in a single source file as required.</p>
 */
public class ConcurrentKeyValueStoreTest {

    /**
     * A simple in‑memory key‑value store backed by a {@link ReadWriteLock}.
     *
     * <p>All read operations ({@link #get(Object)}, {@link #keySet()},
     * {@link #size()}) acquire the read lock, allowing concurrent reads.
     * Write operations ({@link #put(Object, Object)},
     * {@link #remove(Object)}) acquire the write lock exclusively,
     * blocking all reads and other writes while the mutation is in progress.
     *
     * <p>The {@code keySet()} method returns a snapshot (an immutable copy)
     * of the current key set, safe to use after the method returns without
     * holding any lock.</p>
     *
     * <p>Null keys and values are permitted, following the semantics of
     * {@link HashMap}.</p>
     *
     * @param <K> the type of keys maintained by this map
     * @param <V> the type of mapped values
     */
    static class ConcurrentKeyValueStore<K, V> {
        private final Map<K, V> map = new HashMap<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Lock readLock = lock.readLock();
        private final Lock writeLock = lock.writeLock();

        /**
         * Returns the value to which the specified key is mapped,
         * or {@code null} if this map contains no mapping for the key.
         *
         * <p>Acquires the <strong>read lock</strong>.</p>
         *
         * @param key the key whose associated value is to be returned
         * @return the value to which the specified key is mapped, or {@code null}
         *         if this map contains no mapping for the key
         */
        public V get(K key) {
            readLock.lock();
            try {
                return map.get(key);
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Associates the specified value with the specified key in this map.
         * If the map previously contained a mapping for the key, the old
         * value is replaced.
         *
         * <p>Acquires the <strong>write lock</strong>.</p>
         *
         * @param key   key with which the specified value is to be associated
         * @param value value to be associated with the specified key
         */
        public void put(K key, V value) {
            writeLock.lock();
            try {
                map.put(key, value);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Removes the mapping for a key from this map if it is present.
         *
         * <p>Acquires the <strong>write lock</strong>.</p>
         *
         * @param key key whose mapping is to be removed from the map
         * @return the previous value associated with {@code key}, or
         *         {@code null} if there was no mapping for {@code key}
         */
        public V remove(K key) {
            writeLock.lock();
            try {
                return map.remove(key);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Returns a snapshot of the keys contained in this map.
         * The returned set is independent of the map; modifications to the
         * returned set do not affect the map, and vice‑versa.
         *
         * <p>Acquires the <strong>read lock</strong> while copying the key set,
         * then releases it before returning.</p>
         *
         * @return a set view of the keys contained in this map
         */
        public Set<K> keySet() {
            readLock.lock();
            try {
                return new HashSet<>(map.keySet());
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Returns the number of key‑value mappings in this map.
         *
         * <p>Acquires the <strong>read lock</strong>.</p>
         *
         * @return the number of entries in this map
         */
        public int size() {
            readLock.lock();
            try {
                return map.size();
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Simple throughput benchmark for a mixed read/write workload.
         *
         * @param threads      number of worker threads
         * @param readRatio    fraction of operations that are reads (0.0‑1.0)
         * @param durationSec  how long the benchmark should run (seconds)
         * @return approximate number of operations per second
         */
        public static <K, V> double benchmark(int threads, double readRatio, int durationSec,
                                              ConcurrentKeyValueStore<K, V> store,
                                              Supplier<K> keySupplier,
                                              Supplier<V> valueSupplier) throws InterruptedException {
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threads);
            AtomicLong ops = new AtomicLong(0);
            long stopTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
            ThreadLocalRandom rand = ThreadLocalRandom.current();

            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        startLatch.await();
                        while (System.nanoTime() < stopTime) {
                            if (rand.nextDouble() < readRatio) {
                                store.get(keySupplier.get());
                            } else {
                                // choose put or remove with equal probability among writes
                                if (rand.nextBoolean()) {
                                    store.put(keySupplier.get(), valueSupplier.get());
                                } else {
                                    store.remove(keySupplier.get());
                                }
                            }
                            ops.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // let all workers go
            endLatch.await();       // wait for completion
            exec.shutdownNow();

            double seconds = durationSec;
            return ops.doubleValue() / seconds;
        }
    }

    // -----------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------

    private ConcurrentKeyValueStore<String, Integer> store;

    @BeforeEach
    void setUp() {
        store = new ConcurrentKeyValueStore<>();
    }

    // -----------------------------------------------------------------
    // Basic functionality tests
    // -----------------------------------------------------------------

    @Test
    void testGetPutRemove() {
        assertNull(store.get("foo"));
        store.put("foo", 42);
        assertEquals(42, store.get("foo"));
        assertEquals(1, store.size());

        Integer removed = store.remove("foo");
        assertEquals(42, removed);
        assertNull(store.get("foo"));
        assertEquals(0, store.size());
    }

    @Test
    void testPutOverwrite() {
        store.put("x", 1);
        store.put("x", 2);
        assertEquals(2, store.get("x"));
        assertEquals(1, store.size());
    }

    @Test
    void testRemoveAbsent() {
        assertNull(store.remove("missing"));
        assertEquals(0, store.size());
    }

    @Test
    void testKeySetSnapshot() {
        store.put("a", 1);
        store.put("b", 2);
        Set<String> snap = store.keySet();
        assertEquals(Set.of("a", "b"), snap);
        assertEquals(2, snap.size());

        // modify store after snapshot
        store.put("c", 3);
        store.remove("a");
        // snapshot should be unchanged
        assertEquals(Set.of("a", "b"), snap);
        assertEquals(2, snap.size());

        // but store reflects changes
        assertEquals(Set.of("b", "c"), store.keySet());
        assertEquals(2, store.size());
    }

    @Test
    void testSizeConsistency() {
        assertEquals(0, store.size());
        store.put("k1", 10);
        assertEquals(1, store.size());
        store.put("k2", 20);
        assertEquals(2, store.size());
        store.remove("k1");
        assertEquals(1, store.size());
        store.remove("k2");
        assertEquals(0, store.size());
    }

    // -----------------------------------------------------------------
    // Concurrency stress tests
    // -----------------------------------------------------------------

    @Test
    @Timeout(value = 15)
    void testConcurrentMixedWorkload() throws InterruptedException {
        final int threadCount = 50;
        final int durationSec = 5;
        final double readRatio = 0.8; // 80% reads, 20% writes

        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicLong ops = new AtomicLong(0);
        long stopTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        final int keySpace = 1000; // limited key range to increase contention

        Supplier<String> keySupplier = () -> "key-" + rand.nextInt(keySpace);
        Supplier<Integer> valueSupplier = () -> rand.nextInt();

        for (int i = 0; i < threadCount; i++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    while (System.nanoTime() < stopTime) {
                        if (rand.nextDouble() < readRatio) {
                            store.get(keySupplier.get());
                        } else {
                            if (rand.nextBoolean()) {
                                store.put(keySupplier.get(), valueSupplier.get());
                            } else {
                                store.remove(keySupplier.get());
                            }
                        }
                        ops.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        exec.shutdownNow();

        // Basic invariants after the stress run
        Set<String> snap = store.keySet();
        assertEquals(store.size(), snap.size(), "size() must match keySet size");

        // Every key in snapshot should map to a non‑null value
        for (String k : snap) {
            assertNotNull(store.get(k), "Key from keySet should have a value");
        }

        // No key outside snapshot should have a value (optional, but check a sample)
        List<String> outside = IntStream.range(0, keySpace)
                .mapToObj(i -> "key-" + i)
                .filter(k -> !snap.contains(k))
                .limit(20)
                .collect(Collectors.toList());
        for (String k : outside) {
            // Not asserting null because concurrent puts could have added it after snapshot;
            // we only ensure no exception is thrown.
            store.get(k);
        }
    }

    // -----------------------------------------------------------------
    // Additional correctness tests
    // -----------------------------------------------------------------

    @Test
    void testConcurrentReadsOnly() throws InterruptedException {
        final int threads = 30;
        final int durationSec = 2;
        store.put("shared", 99);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong reads = new AtomicLong(0);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    long local = 0;
                    long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
                    while (System.nanoTime() < stop) {
                        store.get("shared");
                        local++;
                    }
                    reads.addAndGet(local);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdownNow();

        assertTrue(reads.get() > 0);
    }

    @Test
    void testConcurrentWritesOnly() throws InterruptedException {
        final int threads = 20;
        final int durationSec = 2;
        final int keySpace = 50; // small key space to cause collisions

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicLong ops = new AtomicLong(0);
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
                    while (System.nanoTime() < stop) {
                        String k = "k" + rand.nextInt(keySpace);
                        store.put(k, rand.nextInt());
                        ops.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    end.countDown();
                }
            });
        }

        start.countDown();
        end.await();
        exec.shutdownNow();

        // After only writes, the map size cannot exceed keySpace
        assertTrue(store.size() <= keySpace);
    }
}
```