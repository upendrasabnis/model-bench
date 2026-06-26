<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260626T130153Z -->
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
Please provide the full Java source code and a complete JUnit 5 test class in a single response.

The task is to implement a self-contained Java program featuring a **concurrent read-write-lock backed key-value store** called `ConcurrentReadWriteStore<K,V>`.

### Requirements for the Implementation:
1. **Core Functionality**: The class must implement the following methods:
   - `V get(K key)`: Retrieve the value associated with the key.
   - `void put(K key, V value)`: Insert or update the value for a key.
   - `void remove(K key)`: Delete a key-value pair.
   - `int size()`: Return the current number of entries.
   - `List<K> snapshotKeys()`: Return a consistent snapshot of all keys currently in the store.

2. **Concurrency Strategy**:
   - Use `java.util.concurrent.locks.ReadWriteLock` (specifically `ReentrantReadWriteLock`) to manage access.
   - Ensure that multiple reader threads can access the store concurrently, while writer threads obtain exclusive access.
   - Include detailed documentation explaining the concurrency strategy, how thread-safety is guaranteed, and the performance trade-offs of this approach.

### Requirements for the JUnit 5 Test Suite:
The test suite must be comprehensive and include the following:
1. **Basic Unit Tests**: Verify the correctness of `get`, `put`, `remove`, `size`, and `snapshotKeys`.
2. **Multi-threaded Stress Tests**: Use dozens of reader threads (calling `get` and `snapshotKeys`) and several writer threads (calling `put` and `remove`) simultaneously. Verify that no `ConcurrentModificationException` occurs and that the final state is consistent.
3. **Interleaving Tests**: Demonstrate that read operations do not block other reads, but are blocked when a write operation is in progress.
4. **Performance Benchmark**: 
   - Implement a simple, naïve `synchronized` version of the store for comparison.
   - Record the throughput under a mixed read/write load.
   - Assert that in a read-heavy scenario, the `ReadWriteLock` implementation achieves at least a 2× speed-up compared to the `synchronized` implementation.

**Please provide all imports, the implementation class, the comparison class, and the complete JUnit 5 test class in a single, runnable code block with clear comments.**