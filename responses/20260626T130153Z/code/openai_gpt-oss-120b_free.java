<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260626T130153Z -->
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
Below is the prompt you can give to the model so that it generates **the full Java source code** (including all necessary imports, the `ConcurrentReadWriteStore<K,V>` class, any helper classes, and a naïve synchronized version for comparison) **and the complete JUnit 5 test class** in a single file. The request also asks for thorough documentation, a description of the concurrency strategy, and explanations of performance trade‑offs.

---

**Prompt to give the model**

```text
Please write a self‑contained Java program that implements a concurrent read‑write‑lock backed key‑value store.

**Requirements**

1. **Class name:** `ConcurrentReadWriteStore<K,V>`
2. **Public API**
   - `V get(K key)`: retrieve the value associated with the key.
   - `void put(K key, V value)`: insert or update the value for a key.
   - `void remove(K key)`: delete a key‑value pair.
   - `int size()`: return the number of entries.
   - `List<K> snapshotKeys()`: return a consistent snapshot of all keys.
3. **Concurrency**
   - Use `java.util.concurrent.locks.ReadWriteLock` (or `ReentrantReadWriteLock`).
   - Multiple threads must be able to read concurrently.
   - Writes must obtain exclusive access.
   - Document the concurrency strategy, explain why the chosen lock scheme guarantees thread‑safety, and discuss performance trade‑offs (e.g., read‑heavy vs. write‑heavy workloads, lock‑contention, snapshot cost).
4. **Additional helper class**
   - Provide a simple naïve implementation called `SynchronizedStore<K,V>` that uses `synchronized` methods for the same API. This will be used for performance comparison.
5. **JUnit 5 test suite** (all in one file, separate class named `ConcurrentReadWriteStoreTest`)
   - **Basic functionality tests** for `get`, `put`, `remove`, `size`, and `snapshotKeys`.
   - **Multi‑threaded stress test**:
     * Launch dozens of reader threads that repeatedly call `get` and `snapshotKeys`.
     * Launch a few writer threads that repeatedly perform `put` and `remove`.
     * Use `CountDownLatch`/`CyclicBarrier` to start them together, run for a fixed duration (e.g., 2 seconds), then verify that no `ConcurrentModificationException` or data races occurred and that the final store state is consistent (size matches expected number of keys, all keys are present with the correct values).
   - **Read‑blocking test**:
     * Interleave reads and a write while recording timestamps to prove that multiple reads proceed concurrently, but reads are blocked while a write holds the lock.
   - **Performance measurement test**:
     * Implement a benchmark that runs a mixed workload (e.g., 90 % reads, 10 % writes) for a fixed number of operations on both `ConcurrentReadWriteStore` and `SynchronizedStore`.
     * Measure throughput (operations per second) and assert that the read‑heavy scenario on `ConcurrentReadWriteStore` is at least **2× faster** than the synchronized version.
6. **Packaging**
   - Put everything in the default package (no `package` statement) so the file can be compiled as a single source file.
   - Include all necessary imports.
   - Add clear comments and Javadoc for the store class, the lock strategy, and each test method.
   - Ensure the code compiles with Java 17+ and JUnit 5.

Please output **one complete Java source file** containing both the implementation classes and the JUnit 5 test class, with no additional explanation or commentary outside the code.```