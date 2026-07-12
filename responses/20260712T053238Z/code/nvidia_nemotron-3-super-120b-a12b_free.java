<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260712T053238Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent **read‑write‑lock backed key‑value store** named `ConcurrentRwLockStore<K,V>`. The store must support `put(K key, V value)`, `V get(K key)`, `remove(K key)`, and `List<K> keys()` operations. Use a `java.util.concurrent.locks.ReadWriteLock` to allow multiple concurrent reads while writes obtain exclusive access. Document the concurrency strategy, including lock acquisition order, potential deadlock avoidance, and performance considerations. Provide a comprehensive JUnit 5 test suite that includes:

1. Functional correctness tests for all public methods.
2. Thread‑safety tests with multiple reader threads accessing the store simultaneously.
3. Stress tests where a mixture of reader and writer threads (e.g., 20 readers, 5 writers) perform random operations for a fixed duration, asserting that no data corruption occurs and that the final state is consistent with a sequential baseline.
4. Tests that verify that read operations do not block each other and that write operations block reads appropriately (use timing assertions).
5. Proper shutdown of any executor services used in the tests.

The prompt should ask the model to produce the full Java class (including necessary imports) and the complete JUnit 5 test class, both as a single compilable file. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt**

Write a single, self‑contained Java source file that contains both:

1. **`ConcurrentRwLockStore<K,V>`** – a generic, highly concurrent key‑value store that uses a `java.util.concurrent.locks.ReadWriteLock` to allow multiple concurrent readers while writers obtain exclusive access.  
   - Public API:  
     - `void put(K key, V value)`  
     - `V get(K key)`  
     - `void remove(K key)`  
     - `List<K> keys()` – returns a snapshot of all current keys (order does not matter).  
   - The implementation must clearly document the concurrency strategy, including:  
     - How the read‑lock and write‑lock are acquired and released for each operation.  
     - Lock acquisition order (if multiple locks are ever needed) and why deadlock cannot occur.  
     - Performance considerations (e.g., lock granularity, impact of long‑running reads/writes, snapshot safety for `keys()`).  
   - The class should be thread‑safe, avoid exposing internal mutable state, and handle null keys/values according to Java’s standard map conventions (you may decide to reject or allow nulls, but state the choice explicitly).  
   - No external libraries beyond the JDK may be used.

2. **A JUnit 5 test class** (in the same file, e.g., as a static nested top‑level class or a separate public class) that thoroughly validates the store, comprising:  
   - **Functional correctness tests** for `put`, `get`, `remove`, and `keys`.  
   - **Thread‑safety tests** where many reader threads invoke `get` concurrently on a pre‑populated store, asserting that all returned values are correct and no exceptions occur.  
   - **Stress / mixed workload test** that launches, for example, 20 reader threads and 5 writer threads. Each thread repeatedly performs random operations (`put`, `get`, `remove`, `keys`) for a fixed duration (e.g., 5 seconds). After the test ends, compute a sequential baseline by applying the same operations in a single thread to an identical initial store and assert that the final state of the concurrent store matches the baseline (same key‑value mappings). Additionally, assert that no data corruption (e.g., missing values, wrong values, duplicate keys) was observed during the run.  
   - **Blocking behavior tests** that verify:  
     - Multiple concurrent read operations do **not** block each other (measure elapsed time for a batch of reads with a write lock held vs. not held).  
     - A write operation blocks subsequent read attempts until the write completes (use timing assertions such as `assertTimeoutPreemptively` or manual `System.nanoTime()` checks).  
   - Proper shutdown of any `ExecutorService`, `ThreadPoolExecutor`, or similar resources used in the tests (e.g., `shutdownNow()` followed by `awaitTermination`).  

The entire solution must compile with JDK 17+ and JUnit 5 (Jupiter) without additional dependencies. Provide only the source code (imports, class definitions, method bodies, comments, and JUnit annotations) – **do not** include any explanation, commentary, or text outside the code block. The model’s response should be a single Java file that can be copied directly into a project and run with `mvn test` or an IDE’s JUnit runner.