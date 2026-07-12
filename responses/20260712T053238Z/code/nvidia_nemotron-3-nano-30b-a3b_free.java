<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260712T053238Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent **read‑write‑lock backed key‑value store** named `ConcurrentRwLockStore<K,V>`. The store must support `put(K key, V value)`, `V get(K key)`, `remove(K key)`, and `List<K> keys()` operations. Use a `java.util.concurrent.locks.ReadWriteLock` to allow multiple concurrent reads while writes obtain exclusive access. Document the concurrency strategy, including lock acquisition order, potential deadlock avoidance, and performance considerations. Provide a comprehensive JUnit 5 test suite that includes:

1. Functional correctness tests for all public methods.
2. Thread‑safety tests with multiple reader threads accessing the store simultaneously.
3. Stress tests where a mixture of reader and writer threads (e.g., 20 readers, 5 writers) perform random operations for a fixed duration, asserting that no data corruption occurs and that the final state is consistent with a sequential baseline.
4. Tests that verify that read operations do not block each other and that write operations block reads appropriately (use timing assertions).
5. Proper shutdown of any executor services used in the tests.

The prompt should ask the model to produce the full Java class (including necessary imports) and the complete JUnit 5 test class, both as a single compilable file. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt for the model**

> Write a **single, self‑contained Java source file** that implements a generic, highly‑concurrent read‑write‑lock backed key‑value store named `ConcurrentRwLockStore<K,V>` and also includes a complete JUnit 5 test suite for that store.  
> The file must compile and run with Java 17 (or later) and use only the standard library (`java.util.*`, `java.util.concurrent.*`, `java.util.concurrent.locks.*`) and JUnit 5 (`org.junit.jupiter.api.*`).  
> Do **not** provide any additional files or external resources – everything must be inside this one source file.  
>   
> **Implementation requirements for `ConcurrentRwLockStore`**  
> 1. **Public generic class** `ConcurrentRwLockStore<K,V>` with the following public methods:  
>    - `void put(K key, V value)` – inserts or replaces the value for the given key.  
>    - `V get(K key)` – returns the current value for the key, or `null` if the key is absent.  
>    - `boolean remove(K key)` – removes the mapping for the key and returns `true` if such a mapping existed.  
>    - `List<K> keys()` – returns an immutable `List` containing all keys currently stored.  
> 2. Internally use a `java.util.concurrent.locks.ReadWriteLock` to protect shared state.  
> 3. **Concurrency strategy documentation** (as comments or Javadoc) must explain:  
>    - How many reader threads may access the store simultaneously.  
>    - How a writer thread obtains exclusive access and how it interacts with ongoing reads/writes.  
>    - Why the chosen lock acquisition order prevents deadlocks.  
>    - Performance considerations (e.g., read‑heavy workload, lock granularity, potential contention).  
> 4. The implementation must be **thread‑safe** and must not expose any internal mutable state outside the class.  
>   
> **Test suite requirements (JUnit 5)** – the test class must be **non‑public** and placed in the same file (it may be package‑private). It must contain:  
> 1. **Functional correctness tests** that verify the behavior of `put`, `get`, `remove`, and `keys` on a single thread.  
> 2. **Thread‑safety tests** that spawn multiple reader threads (e.g., 10) that concurrently call `get` on a populated store and assert that no `NullPointerException` or other runtime errors occur.  
> 3. **Stress test** that runs for a fixed duration (e.g., 2 seconds) with a mixed pool of 20 reader threads and 5 writer threads. Each thread performs random operations (`put`, `get`, `remove`, `keys`) chosen uniformly at random. After the duration, the test must:  
>    - Assert that no data corruption (e.g., duplicate keys with different values) is observed.  
>    - Re‑construct the store’s state from the observed keys and compare it against a **sequential baseline** built by a single‑threaded reference implementation (you may implement this baseline inside the test).  
> 4. **Lock‑behavior timing assertions**:  
>    - Measure the time taken for a single `put` operation while a large number of readers are active (e.g., 50 concurrent readers).  
>    - Assert that the write operation’s duration is **significantly longer** than a single reader’s duration, confirming that writers block readers.  
>    - Conversely, run multiple readers concurrently and assert that their combined throughput is higher than a single‑threaded read, demonstrating that reads do not block each other.  
> 5. **Proper shutdown** of any `ExecutorService` or `ScheduledExecutorService` used in the tests (call `shutdown()` and await termination).  
> 6. Use `@Test` (and any necessary `@RepeatedTest`, `@ParameterizedTest`, etc.) and appropriate assertions from `org.junit.jupiter.api.Assertions`.  
>   
> **Formatting & compilation constraints**  
> - The file must contain **exactly one public top‑level class** named `ConcurrentRwLockStore`.  
> - The test class must be **non‑public** (package‑private) and can be named anything (e.g., `ConcurrentRwLockStoreTest`).  
> - All imports required for the store implementation and the tests must be included at the top of the file.  
> - No external libraries beyond `junit-jupiter-api` and `junit-jupiter-engine` may be referenced.  
> - The entire source code must fit within a single file; you may not reference any other files.  
>   
> **Deliverable**  
> Provide **only the source code** that satisfies the above specifications. Do **not** include any explanatory text outside the Java source file. The code must be syntactically correct, compile with `javac`, and the test class must be runnable with a standard JUnit 5 runner (e.g., `mvn test` or `java -jar junit-platform-console-standalone.jar`).  

---  

*When you receive this prompt, generate the complete Java source file that meets every bullet point above.*