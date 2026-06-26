<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260626T130153Z -->
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
**Prompt for a language model**

> Write a **single Java source file** that contains:
> 
> 1. **The implementation** of a concurrent read‑write‑lock backed key‑value store named `ConcurrentReadWriteStore<K,V>` with the following public API:
>    - `V get(K key)` – retrieve the value associated with the key.  
>    - `void put(K key, V value)` – insert or update the value for a key.  
>    - `void remove(K key)` – delete a key‑value pair.  
>    - `int size()` – return the current number of stored entries.  
>    - `List<K> snapshotKeys()` – return a **consistent snapshot** of all keys currently present in the store.  
>    
>    The class must:
>    - Use `java.util.concurrent.locks.ReadWriteLock` (e.g., `ReentrantReadWriteLock`) to allow **multiple concurrent reads** while **writes obtain exclusive access**.  
>    - Include **comprehensive inline documentation** (javadoc‑style comments) that explains the concurrency strategy, why the chosen lock scheme guarantees thread‑safety, and discusses any performance trade‑offs (e.g., read‑write contention, lock‑acquisition overhead).  
>    - Be **self‑contained** (no external dependencies beyond the JDK).  
> 
> 2. **A JUnit 5 test suite** in the same file (or a separate top‑level class) named `ConcurrentReadWriteStoreTest` that contains:
>    - **Unit tests** covering basic functionality (`get`, `put`, `remove`, `size`, `snapshotKeys`).  
>    - **Multi‑threaded stress tests** where dozens of reader threads repeatedly invoke `get` and `snapshotKeys` while writer threads perform `put` and `remove`. The test must verify that no `ConcurrentModificationException` or data races occur and that the final store state is consistent.  
>    - **Interleaved‑operation tests** that deliberately mix reads and writes to confirm that reads do not block each other but are blocked during a write.  
>    - **Performance measurement test** that records throughput under a mixed read/write load and asserts that the read‑heavy scenario achieves **at least a 2× speed‑up** compared to a naïve `synchronized` implementation.  
>      - Implement a simple `SynchronizedStore<K,V>` class (using `synchronized` methods) within the same file for the baseline comparison.  
>    - All tests must be **self‑contained** and compile/run with JUnit 5 (no external test framework configuration required).  
> 
> 3. The entire file should be **clearly commented** and include a brief **README‑style header** describing the purpose of the code, how to compile/run the tests, and any assumptions made.  
> 
> **Deliverable:** One Java source file that can be copied into a project and compiled/run directly (e.g., `javac MyStore.java && java org.junit.runner.JUnitCore ConcurrentReadWriteStoreTest`). Do **not** provide any explanation or additional text outside the requested source code; just output the full source.  

> **Constraints for the model:**  
> - Do **not** solve the problem yourself; only generate the requested Java source code.  
> - Include all necessary imports.  
> - Ensure the code is syntactically correct and ready to compile.  
> - Keep the implementation concise but complete, respecting the concurrency requirements and the test specifications above.  

> **End of prompt.**