<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260626T130153Z -->
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
**Prompt to the model:**

> Write a **self‑contained Java program** that implements a concurrent read‑write‑lock backed key‑value store.  
> 
> 1. Create a public generic class named `ConcurrentReadWriteStore<K,V>` with the following public methods:
>    - `V get(K key)`
>    - `void put(K key, V value)`
>    - `void remove(K key)`
>    - `int size()`
>    - `List<K> snapshotKeys()`
> 2. Use `java.util.concurrent.locks.ReadWriteLock` (or `ReentrantReadWriteLock`) so that multiple threads can read concurrently, while write operations obtain exclusive access.  
> 3. Include **JavaDoc** or inline comments that explain the concurrency strategy, why it guarantees thread safety, and discuss any performance trade‑offs.  
> 4. Provide a **comprehensive JUnit 5 test class** in the same file that covers:
>    - Basic functionality tests for all operations.
>    - Multi‑threaded stress tests: dozens of reader threads calling `get` and `snapshotKeys` continuously while writer threads perform `put` and `remove`. Verify no `ConcurrentModificationException` or data races occur and that the final state is consistent.
>    - Tests that interleave read and write operations to confirm that reads do not block each other but are blocked during a write.
>    - A performance test that measures throughput under a mixed read/write load. Compare against a naïve `synchronized` implementation (you may implement a simple synchronized version for comparison) and assert that the read‑heavy scenario achieves at least a 2× speed‑up.
> 5. All code should be in a **single file** and compile with Java 17 or later.  
> 6. Add necessary imports and any helper classes.  
> 7. Ensure the test class runs successfully with JUnit 5 and that it demonstrates the required concurrent behavior and performance characteristics.  
> 
> **Please output the complete Java source code (including imports, the `ConcurrentReadWriteStore` class, the synchronized fallback, and the JUnit 5 test class) in a single file, with clear comments and documentation.**