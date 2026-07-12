<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260712T053238Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent **read‑write‑lock backed key‑value store** named `ConcurrentRwLockStore<K,V>`. The store must support `put(K key, V value)`, `V get(K key)`, `remove(K key)`, and `List<K> keys()` operations. Use a `java.util.concurrent.locks.ReadWriteLock` to allow multiple concurrent reads while writes obtain exclusive access. Document the concurrency strategy, including lock acquisition order, potential deadlock avoidance, and performance considerations. Provide a comprehensive JUnit 5 test suite that includes:

1. Functional correctness tests for all public methods.
2. Thread‑safety tests with multiple reader threads accessing the store simultaneously.
3. Stress tests where a mixture of reader and writer threads (e.g., 20 readers, 5 writers) perform random operations for a fixed duration, asserting that no data corruption occurs and that the final state is consistent with a sequential baseline.
4. Tests that verify that read operations do not block each other and that write operations block reads appropriately (use timing assertions).
5. Proper shutdown of any executor services used in the tests.

The prompt should ask the model to produce the full Java class (including necessary imports) and the complete JUnit 5 test class, both as a single compilable file. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt for the model:**

> Write a single, self‑contained Java source file (`.java`) that contains **both** the implementation of a highly concurrent read‑write‑lock backed key‑value store named `ConcurrentRwLockStore<K,V>` **and** a comprehensive JUnit 5 test suite for it. The file must be compilable and runnable with a standard Java 17+ toolchain (e.g., `javac` + `java -jar junit-platform-console-standalone.jar` or via Maven/Gradle).  
>   
> **Requirements for `ConcurrentRwLockStore<K,V>`**  
> 1. **Public API**  
>    - `void put(K key, V value)` – inserts or updates the mapping.  
>    - `V get(K key)` – returns the value associated with `key` or `null` if absent.  
>    - `V remove(K key)` – removes the mapping for `key` and returns the previous value, or `null` if absent.  
>    - `List<K> keys()` – returns a snapshot list of all current keys (order not important).  
> 2. **Concurrency control**  
>    - Use a single `java.util.concurrent.locks.ReadWriteLock` (e.g., `ReentrantReadWriteLock`).  
>    - All read‑only operations (`get`, `keys`) must acquire the **read lock** (`lock.readLock().lock()` / `unlock()`).  
>    - All mutating operations (`put`, `remove`) must acquire the **write lock** (`lock.writeLock().lock()` / `unlock()`).  
>    - Document the lock acquisition order, why a single lock is sufficient, and how deadlock is avoided (no nested lock acquisition, no lock upgrade).  
>    - Discuss performance considerations: read‑read concurrency, write exclusivity, potential contention, and when a `StampedLock` or `ConcurrentHashMap` might be preferable.  
> 3. **Implementation details**  
>    - Back the store with a `java.util.HashMap<K,V>` (or `LinkedHashMap` if you want deterministic iteration order).  
>    - `keys()` must return a **defensive copy** (e.g., `new ArrayList<>(map.keySet())`) while still holding the read lock to guarantee a consistent snapshot.  
>    - All public methods must be `null`‑safe for keys and values (allow `null` values, but `null` keys are allowed only if `HashMap` permits).  
>    - The class must be `public` and non‑final (to allow subclassing in tests if needed).  
>   
> **Requirements for the JUnit 5 test suite (in the same file, e.g., a `static` nested test class or a top‑level class in the same compilation unit)**  
> 1. **Functional correctness tests** – verify each public method works as specified in a single‑threaded scenario (including edge cases: empty store, duplicate puts, remove non‑existent key, `keys()` order independence).  
> 2. **Thread‑safety / concurrent read tests** – launch multiple reader threads (e.g., 20) that repeatedly call `get` and `keys` on a pre‑populated store; assert no exceptions and that all reads return values consistent with the initial data.  
> 3. **Stress test with mixed readers/writers** –  
>    - Use an `ExecutorService` (e.g., `Executors.newFixedThreadPool(25)`).  
>    - Submit 20 reader tasks and 5 writer tasks that run for a fixed duration (e.g., 2 seconds).  
>    - Readers perform random `get`/`keys`; writers perform random `put`/`remove` on a shared key space (e.g., keys `"k0"` … `"k99"`).  
>    - Maintain a **sequential baseline** (a `Collections.synchronizedMap` or a single‑threaded reference implementation) that records the same operations in the same order (use a thread‑safe log or apply operations to both stores atomically).  
>    - After shutdown, compare the final state of `ConcurrentRwLockStore` with the baseline; they must be identical (same keys, same values).  
>    - Assert no data corruption (e.g., no `ConcurrentModificationException`, no lost updates).  
> 4. **Lock‑behavior timing tests** –  
>    - Verify that concurrent reads do **not** block each other: start two reader threads that each hold the read lock for a measurable period (e.g., `Thread.sleep(100)` inside a `get`‑like operation) and assert total elapsed time ≈ 100 ms (not 200 ms).  
>    - Verify that a write blocks reads: start a writer that holds the write lock for 100 ms, then start a reader; assert the reader’s start‑to‑finish time ≥ 100 ms.  
>    - Use `System.nanoTime()` or `Instant` for measurements; allow a small tolerance (e.g., ±20 ms).  
> 5. **Resource cleanup** – All `ExecutorService` instances must be shut down in `@AfterAll` / `@AfterEach` (call `shutdown()` + `awaitTermination`). No daemon threads left running.  
> 6. **Test annotations** – Use `@Test`, `@RepeatedTest`, `@Timeout`, `@BeforeAll`, `@AfterAll`, `@BeforeEach`, `@AfterEach` as appropriate.  
>   
> **Deliverable**  
> - A **single** `.java` file containing:  
>   1. All necessary `import` statements.  
>   2. The `ConcurrentRwLockStore<K,V>` class with JavaDoc explaining the concurrency strategy.  
>   3. The JUnit 5 test class (can be a top‑level class named `ConcurrentRwLockStoreTest` in the same file).  
> - The file must compile with `javac --release 17 *.java` and run with the JUnit Platform console launcher (no external build files required).  
>   
> **Do not provide any additional explanation, only the complete Java source code.**