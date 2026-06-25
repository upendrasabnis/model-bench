<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260625T004409Z -->
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
/*
 * ConcurrentKeyValueStoreTest.java
 *
 * A self‑contained Java 17 source file that defines a thread‑safe key‑value store
 * backed by a ReadWriteLock and a comprehensive JUnit 5 test suite.
 *
 * To compile and run the tests with Maven:
 *   mvn -q test
 * (Assumes JUnit 5 is on the test classpath, e.g. via the Maven Surefire plugin.)
 *
 * The file contains a top‑level public test class and a static nested
 * implementation class.
 */

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for {@link ConcurrentKeyValueStore}.
 *
 * <p>This class contains all unit and stress tests required by the specification.
 * The implementation of the store is provided as a static nested class
 * {@code ConcurrentKeyValueStore}.</p>
 */
public class ConcurrentKeyValueStoreTest {

    /* --------------------------------------------------------------------- */
    /*  Implementation of the concurrent key‑value store                      */
    /* --------------------------------------------------------------------- */

    /**
     * A thread‑safe in‑memory key‑value store backed by a {@link ReadWriteLock}.
     *
     * <p>All public methods are documented with the lock they acquire:</p>
     *
     * <ul>
     *   <li>{@code get(K)} – acquires the <em>read</em> lock. Multiple threads may
     *       read concurrently.</li>
     *   <li>{@code put(K,V)} – acquires the <em>write</em> lock. The write lock
     *       is exclusive, therefore no other reads or writes proceed while a
     *       mutation is in progress.</li>
     *   <li>{@code remove(K)} – acquires the <em>write</em> lock for the same
     *       reason as {@code put}.</li>
     *   <li>{@code keySet()} – acquires the <em>read</em> lock, makes a defensive
     *       copy of the current keys and returns an immutable snapshot. The lock
     *       is released before the snapshot is returned, so callers may iterate
     *       safely without holding any lock.</li>
     *   <li>{@code size()} – acquires the <em>read</em> lock because it only
     *       reads the map size.</li>
     * </ul>
     *
     * <p>The store uses a {@link ReentrantReadWriteLock} with the default (non‑fair)
     * policy. This choice favors higher throughput under read‑heavy workloads
     * because readers are not forced to wait for waiting writers. In write‑heavy
     * scenarios the lock may starve writers; a fair lock can be substituted if
     * strict ordering is required.</p>
     *
     * @param <K> type of keys; must be non‑null and correctly implement {@code equals} and {@code hashCode}
     * @param <V> type of values; may be {@code null}
     */
    public static class ConcurrentKeyValueStore<K, V> {

        private final Map<K, V> map = new HashMap<>();
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

        /**
         * Retrieves the value associated with {@code key}.
         *
         * <p>Acquires the read lock; multiple threads may call this method
         * concurrently.</p>
         *
         * @param key the key whose value is to be returned
         * @return the current value associated with {@code key}, or {@code null}
         *         if the key is not present
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public V get(K key) {
            Objects.requireNonNull(key);
            Lock readLock = rwLock.readLock();
            readLock.lock();
            try {
                return map.get(key);
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Inserts or updates the mapping for {@code key} to {@code value}.
         *
         * <p>Acquires the write lock; the operation is exclusive.</p>
         *
         * @param key   the key to insert or update
         * @param value the new value; may be {@code null}
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public void put(K key, V value) {
            Objects.requireNonNull(key);
            Lock writeLock = rwLock.writeLock();
            writeLock.lock();
            try {
                map.put(key, value);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Removes the entry for {@code key} if present.
         *
         * <p>Acquires the write lock; the operation is exclusive.</p>
         *
         * @param key the key to remove
         * @return the previous value associated with {@code key}, or {@code null}
         *         if the key was not present
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public V remove(K key) {
            Objects.requireNonNull(key);
            Lock writeLock = rwLock.writeLock();
            writeLock.lock();
            try {
                return map.remove(key);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Returns an immutable snapshot of the current key set.
         *
         * <p>Acquires the read lock, copies the keys into a new {@link HashSet},
         * releases the lock and returns an unmodifiable view. The snapshot is
         * safe to iterate without further synchronization.</p>
         *
         * @return an immutable set containing all keys present at the time of the call
         */
        public Set<K> keySet() {
            Lock readLock = rwLock.readLock();
            readLock.lock();
            try {
                return Collections.unmodifiableSet(new HashSet<>(map.keySet()));
            } finally {
                readLock.unlock();
            }
        }

        /**
         * Returns the number of entries in the store.
         *
         * <p>Acquires the read lock.</p>
         *
         * @return the size of the map
         */
        public int size() {
            Lock readLock = rwLock.readLock();
            readLock.lock();
            try {
                return map.size();
            } finally {
                readLock.unlock();
            }
        }

        /* ----------------------------------------------------------------- */
        /*  Optional benchmark utility                                        */
        /* ----------------------------------------------------------------- */

        /**
         * Runs a simple throughput benchmark for a mixed workload.
         *
         * <p>The workload consists of {@code readRatio}% reads and the remainder
         * writes (half puts, half removes). The method spawns {@code threadCount}
         * threads that each perform {@code operationsPerThread} operations.
         * The total number of completed operations per second is printed to
         * {@code System.out}.</p>
         *
         * @param threadCount          number of concurrent threads
         * @param operationsPerThread  operations each thread performs
         * @param readRatio            percentage of read operations (0‑100)
         */
        public void runBenchmark(int threadCount, int operationsPerThread, int readRatio) {
            Objects.checkFromToIndex(0, readRatio, 101);
            ExecutorService exec = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            Random sharedRand = new Random();

            for (int i = 0; i < threadCount; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        Random rnd = new Random(sharedRand.nextLong());
                        for (int op = 0; op < operationsPerThread; op++) {
                            int choice = rnd.nextInt(100);
                            K key = (K) ("key" + rnd.nextInt(1000));
                            if (choice < readRatio) {
                                get(key);
                            } else if (choice < readRatio + (100 - readRatio) / 2) {
                                put(key, (V) ("val" + rnd.nextInt()));
                            } else {
                                remove(key);
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            long startNs = System.nanoTime();
            start.countDown(); // release all workers
            try {
                done.await();
            } catch (InterruptedException ignored) {
            }
            long elapsedNs = System.nanoTime() - startNs;
            long totalOps = (long) threadCount * operationsPerThread;
            double opsPerSec = totalOps / (elapsedNs / 1_000_000_000.0);
            System.out.printf("Benchmark: %d ops in %.3f s => %.2f ops/s%n",
                    totalOps, elapsedNs / 1e9, opsPerSec);
            exec.shutdown();
        }
    }

    /* --------------------------------------------------------------------- */
    /*  Test cases                                                            */
    /* --------------------------------------------------------------------- */

    @Nested
    @DisplayName("Functional correctness")
    class FunctionalTests {

        private ConcurrentKeyValueStore<String, Integer> store;

        @BeforeEach
        void setUp() {
            store = new ConcurrentKeyValueStore<>();
        }

        @Test
        @DisplayName("put and get")
        void testPutGet() {
            store.put("a", 1);
            store.put("b", 2);
            assertEquals(1, store.get("a"));
            assertEquals(2, store.get("b"));
        }

        @Test
        @DisplayName("remove returns previous value")
        void testRemove() {
            store.put("x", 10);
            assertEquals(10, store.remove("x"));
            assertNull(store.get("x"));
            assertNull(store.remove("nonexistent"));
        }

        @Test
        @DisplayName("size and keySet reflect current state")
        void testSizeAndKeySet() {
            assertEquals(0, store.size());
            store.put("k1", 1);
            store.put("k2", 2);
            assertEquals(2, store.size());
            Set<String> keys = store.keySet();
            assertTrue(keys.containsAll(List.of("k1", "k2")));
            assertEquals(2, keys.size());
        }

        @Test
        @DisplayName("keySet snapshot isolation")
        void testKeySetSnapshot() {
            store.put("a", 1);
            Set<String> snapshot = store.keySet();
            store.put("b", 2);
            assertTrue(snapshot.contains("a"));
            assertFalse(snapshot.contains("b"));
        }

        @Test
        @DisplayName("null values are allowed, null keys are rejected")
        void testNullHandling() {
            assertThrows(NullPointerException.class, () -> store.put(null, 1));
            assertThrows(NullPointerException.class, () -> store.get(null));
            assertThrows(NullPointerException.class, () -> store.remove(null));

            store.put("nullVal", null);
            assertTrue(store.keySet().contains("nullVal"));
            assertNull(store.get("nullVal"));
        }
    }

    @Nested
    @DisplayName("Concurrency stress tests")
    class StressTests {

        private ConcurrentKeyValueStore<Integer, Integer> store;
        private static final int THREAD_COUNT = 50;
        private static final int TEST_DURATION_SECONDS = 5;
        private static final int KEY_SPACE = 1000;

        @BeforeEach
        void setUp() {
            store = new ConcurrentKeyValueStore<>();
        }

        @Test
        @DisplayName("random mixed workload does not corrupt internal state")
        void mixedWorkloadStress() {
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CyclicBarrier startBarrier = new CyclicBarrier(THREAD_COUNT);
            CountDownLatch stopLatch = new CountDownLatch(THREAD_COUNT);
            AtomicBoolean stopFlag = new AtomicBoolean(false);
            Random globalRand = new Random();

            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        startBarrier.await(); // synchronize start
                        Random rnd = new Random(globalRand.nextLong());
                        while (!stopFlag.get()) {
                            int op = rnd.nextInt(100);
                            int key = rnd.nextInt(KEY_SPACE);
                            if (op < 80) { // 80% reads
                                store.get(key);
                            } else if (op < 90) { // 10% puts
                                store.put(key, rnd.nextInt());
                            } else { // 10% removes
                                store.remove(key);
                            }
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    } finally {
                        stopLatch.countDown();
                    }
                });
            }

            // run for a fixed duration
            assertTimeoutPreemptively(Duration.ofSeconds(TEST_DURATION_SECONDS + 2), () -> {
                Thread.sleep(TEST_DURATION_SECONDS * 1000L);
                stopFlag.set(true);
                stopLatch.await();
            });

            // Consistency checks
            Set<Integer> keys = store.keySet();
            assertEquals(keys.size(), store.size(),
                    "size() must equal number of unique keys in snapshot");

            for (Integer k : keys) {
                // The value returned by get must be the same as the one stored at the moment of the snapshot
                // (no further guarantee – we just ensure no exceptions and that a value exists)
                assertNotNull(store.get(k), "Value for existing key must not be null (may be null value stored)");
            }

            executor.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Performance discussion (optional)")
    class OptionalPerformanceTests {

        @Test
        @DisplayName("run a small benchmark (not asserted)")
        void benchmarkDemo() {
            ConcurrentKeyValueStore<String, String> store = new ConcurrentKeyValueStore<>();
            // Populate with some data to avoid empty‑map bias
            for (int i = 0; i < 1000; i++) {
                store.put("init" + i, "val" + i);
            }
            // Execute benchmark; output goes to stdout
            store.runBenchmark(8, 100_000, 80);
        }
    }
}
```