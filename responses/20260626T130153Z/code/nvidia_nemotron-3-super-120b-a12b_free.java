<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260626T130153Z -->
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
**Prompt for the model:**

Please generate a single Java source file that contains:

1. **Implementation**  
   - A public generic class `ConcurrentReadWriteStore<K,V>` that provides the methods  
     `V get(K key)`, `void put(K key, V value)`, `void remove(K key)`, `int size()`, and `List<K> snapshotKeys()`.  
   - The class must use `java.util.concurrent.locks.ReadWriteLock` (or `ReentrantReadWriteLock`) to allow multiple concurrent readers while writers obtain exclusive access.  
   - Include all necessary imports and any helper classes (e.g., a simple synchronized version for performance comparison).  
   - Provide thorough Javadoc comments for the class and each method, and add inline comments that explain the concurrency strategy, why the chosen lock scheme guarantees thread‑safety, and discuss any performance trade‑offs.

2. **JUnit 5 Test Suite**  
   - A public test class (e.g., `ConcurrentReadWriteStoreTest`) in the same file, annotated with `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` or similar as needed.  
   - The test suite must include:  
     a. **Unit tests** for basic functionality (`get`, `put`, `remove`, `size`, `snapshotKeys`).  
     b. **Multi‑threaded stress tests** where dozens of reader threads repeatedly invoke `get` and `snapshotKeys` while writer threads perform `put` and `remove`. Verify that no `ConcurrentModificationException` or data races occur and that the final state is consistent.  
     c. **Interleaving tests** that deliberately mix reads and writes to confirm that reads do not block each other but are blocked during a write (e.g., using `CountDownLatch` or `Phaser` to control timing).  
     d. **Performance measurement test** that records throughput under a mixed read/write load for both the lock‑based implementation and a naïve `synchronized` version. Assert that the read‑heavy scenario achieves at least a 2× speed‑up compared to the synchronized baseline.  

   - Use appropriate JUnit 5 annotations (`@Test`, `@BeforeEach`, `@AfterEach`, `@RepeatedTest`, etc.) and utilities such as `ExecutorService`, `CountDownLatch`, `CyclicBarrier`, or `Phaser` to coordinate threads.  
   - Ensure the test class is self‑contained (no external dependencies beyond JUnit 5 and the JDK).  
   - Include comments explaining the purpose of each test and any important timing or synchronization details.

**Formatting requirements:**  
- The entire source (implementation + test class) must be placed in a single `.java` file.  
- Use clear, consistent formatting and meaningful variable/method names.  
- Do not include a `package` statement unless you wish to place the code in the default package.  
- The file should compile with JDK 17+ and run with JUnit 5 (Jupiter).  

Please output only the requested Java source code (implementation and test class) in a single code block, with no additional explanation.