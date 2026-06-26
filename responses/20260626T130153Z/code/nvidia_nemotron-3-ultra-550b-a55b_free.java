<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260626T130153Z -->
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

> Write a **single, self‑contained Java source file** that contains:
> 
> 1. A class `ConcurrentReadWriteStore<K,V>` implementing a thread‑safe key‑value store backed by a `java.util.concurrent.locks.ReadWriteLock` (or `ReentrantReadWriteLock`). The class must provide the following public methods:
>    - `V get(K key)`
>    - `void put(K key, V value)`
>    - `void remove(K key)`
>    - `int size()`
>    - `List<K> snapshotKeys()` – returns a consistent snapshot of all keys.
>    
>    Include thorough **JavaDoc comments** that explain the concurrency strategy, why the chosen lock scheme guarantees thread‑safety, and any performance trade‑offs (e.g., read‑heavy vs. write‑heavy workloads, lock contention, memory visibility).
> 
> 2. A **JUnit 5 test class** (in the same file, e.g. as a `static` inner class or separate top‑level class) that covers:
>    - **Basic functional tests** for `get`, `put`, `remove`, `size`, `snapshotKeys`.
>    - **Multi‑threaded stress tests**: launch dozens of reader threads that continuously call `get` and `snapshotKeys` while writer threads perform `put` and `remove`. Verify that no `ConcurrentModificationException` or data races occur and that the final map state is consistent.
>    - **Interleaving tests** that deliberately mix reads and writes to demonstrate that concurrent reads do not block each other but are blocked during a write (e.g., using `CountDownLatch`/`CyclicBarrier` to control timing).
>    - **Performance measurement test**: run a mixed read/write workload (e.g., 80 % reads, 20 % writes) and record throughput. Also implement a naïve `synchronized` version of the same API (e.g., `SynchronizedStore<K,V>`). Assert that the read‑heavy scenario achieves **at least a 2× speed‑up** over the synchronized version.
> 
> 3. All necessary **imports**, helper classes, and a `main` method (optional) that can be used to run the performance test manually.
> 
> The file must compile and run with **Java 17+** and **JUnit 5** (no external dependencies beyond the JDK and JUnit). Provide clear, well‑structured code with comments explaining each part. Do **not** include any extraneous explanation—only the complete source code.