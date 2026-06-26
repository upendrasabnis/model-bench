<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260626T130153Z -->
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
Please provide a single, self-contained Java file containing the complete implementation of the `ConcurrentReadWriteStore<K, V>` class and its corresponding JUnit 5 test suite. The solution must adhere to the following specifications:

### 1. Implementation Requirements: `ConcurrentReadWriteStore<K, V>`
Implement a thread-safe key-value store using `java.util.concurrent.locks.ReentrantReadWriteLock`. The class must support:
*   `V get(K key)`: Retrieve the value for a key.
*   `void put(K key, V value)`: Insert or update a key-value pair.
*   `void remove(K key)`: Delete a key-value pair.
*   `int size()`: Return the current number of entries.
*   `List<K> snapshotKeys()`: Return a consistent snapshot (a copy) of all current keys.

**Documentation Requirements:**
*   Include Javadoc explaining the concurrency strategy.
*   Explain why the `ReadWriteLock` scheme guarantees thread safety (atomicity and visibility).
*   Discuss the performance trade-offs (e.g., writer starvation vs. reader concurrency).

### 2. Testing Requirements (JUnit 5)
The test suite must be comprehensive and include:
*   **Basic Unit Tests:** Verify correctness of `get`, `put`, `remove`, `size`, and `snapshotKeys` in a single-threaded context.
*   **Multi-threaded Stress Tests:** Use an `ExecutorService` to launch dozens of concurrent reader threads (calling `get` and `snapshotKeys`) and writer threads (calling `put` and `remove`). Assert that no `ConcurrentModificationException` is thrown and that the internal state remains consistent.
*   **Concurrency Interleaving Tests:** Implement a test that demonstrates that multiple readers can hold the lock simultaneously, but a writer successfully blocks both new readers and existing readers to ensure exclusive access.
*   **Performance Benchmark Test:** 
    *   Implement a simple `SynchronizedStore<K, V>` class that uses the `synchronized` keyword on all methods.
    *   Create a benchmark that measures throughput (operations per second) under a **read-heavy load** (e.g., 90% reads, 10% writes).
    *   Assert that the `ConcurrentReadWriteStore` achieves at least a **2× speed-up** over the `SynchronizedStore` under this read-heavy scenario.

### 3. Format Requirements
*   **Single File:** All code (imports, the main class, the synchronized comparison class, and the JUnit test class) must be provided in one code block.
*   **Self-Contained:** The code must be ready to compile and run (assuming JUnit 5 is on the classpath).
*   **Clean Code:** Use meaningful variable names, clear comments, and follow standard Java naming conventions.