<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260712T053238Z -->
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

> Write a single, self‑contained Java program that implements a highly concurrent **read‑write‑lock backed key‑value store** named `ConcurrentRwLockStore<K,V>`.  
> 
> The store must expose the following public methods:  
> 
> • `void put(K key, V value)` – insert or replace a value.  
> • `V get(K key)` – retrieve the value for a key (or `null` if absent).  
> • `void remove(K key)` – delete a key/value pair.  
> • `List<K> keys()` – return a snapshot list of all keys.  
> 
> Use a `java.util.concurrent.locks.ReadWriteLock` so that:
> * Multiple reads can proceed concurrently.  
> * A write obtains exclusive access, blocking other reads and writes.  
> 
> **Concurrency strategy**:  
> * Document how the lock is acquired and released in each method.  
> * Explain how deadlocks are avoided (e.g., by acquiring only one lock per operation).  
> * Discuss performance considerations such as lock granularity and contention.  
> 
> Provide a comprehensive JUnit 5 test suite that includes:  
> 1. Functional correctness tests for all public methods.  
> 2. Thread‑safety tests with multiple reader threads accessing the store simultaneously.  
> 3. Stress tests where a mixture of reader and writer threads (e.g., 20 readers, 5 writers) perform random operations for a fixed duration, asserting that no data corruption occurs and that the final state is consistent with a sequential baseline.  
> 4. Tests that verify that read operations do not block each other and that write operations block reads appropriately (use timing assertions).  
> 5. Proper shutdown of any executor services used in the tests.  
> 
> The entire solution (the `ConcurrentRwLockStore` class and the JUnit 5 test class) should be placed in one compilable file, including all necessary imports, and should compile and run with JDK 17 (or later).  
> 
> Do **not** provide the solution yourself.  Instead, produce a prompt that can be fed to a language model to generate the described Java program and sẽ test suite.