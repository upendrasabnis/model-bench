<!-- model: nousresearch/hermes-3-llama-3.1-405b:free | category: code | run: 20260625T020407Z -->
<!-- PROMPT -->
Write a **single, self‑contained Java program** that implements a **concurrent, thread‑safe Least‑Recently‑Used (LRU) cache** with a fixed maximum capacity and a full suite of **JUnit 5 tests**. Your solution must satisfy **all** of the following requirements:

---

### 1. Public API

Create a public class `ConcurrentLruCache<K, V>` with these members:

| Signature | Description |
|-----------|-------------|
| `public ConcurrentLruCache(int maxCapacity)` | Constructs a cache that can hold at most `maxCapacity` entries. `maxCapacity` must be > 0; throw `IllegalArgumentException` otherwise. |
| `public V get(K key)` | Returns the value associated with `key`, or `null` if the key is absent. This operation must update the LRU order (the accessed entry becomes most‑recently used). |
| `public void put(K key, V value)` | Inserts or updates the mapping `key → value`. If the cache is at capacity, evict the **least‑recently used** entry before inserting. This operation must update the LRU order (the inserted/updated entry becomes most‑recently used). |
| `public V remove(K key)` | Removes the entry for `key` if present and returns its value, or `null` if absent. |
| `public int size()` | Returns the current number of entries. |
| `public List<K> snapshotKeys()` | Returns a **thread‑safe snapshot** of the keys in LRU order, from most‑recently used to least‑recently used. The returned list must not be affected by subsequent cache modifications. |
| `public void clear()` | Removes all entries. |

All public methods must be safe to call concurrently from any number of threads.

---

### 2. Concurrency Strategy (Documentation Required)

In the source file, include a concise Javadoc comment (≤ 250 words) that explains **exactly** how thread‑safety is achieved, covering:

* The underlying data structures (e.g., `ConcurrentHashMap`, custom doubly‑linked list, `java.util.concurrent.locks.ReentrantLock`, etc.).
* How you avoid deadlocks, race conditions, and ensure **O(1)** expected time for `get` and `put`.
* How the LRU ordering is maintained under concurrent accesses.
* Any memory‑visibility guarantees you rely on (e.g., `volatile`, `AtomicReference`, lock ordering).

---

### 3. Implementation Constraints

* Do **not** use any third‑party libraries; only the Java Standard Library (Java 17 or later).
* The cache must achieve **amortized O(1)** for `get`, `put`, and `remove` under contention.
* You may use `java.util.concurrent.locks.ReentrantReadWriteLock`, `StampedLock`, `synchronized`, `java.util.concurrent.atomic` primitives, or a combination, but you must justify the choice in the Javadoc.
* The `snapshotKeys()` method must not block other operations longer than necessary and must return an immutable `List<K>` (e.g., `Collections.unmodifiableList`).

---

### 4. JUnit 5 Test Suite

Provide a **complete** JUnit 5 test class `ConcurrentLruCacheTest` in the same file (or a separate file if you prefer) that includes:

1. **Basic functional tests** for each public method (insertion, retrieval, eviction order, removal, clear, snapshot correctness).
2. **Concurrency stress tests**:
   * Spawn at least **8** threads that perform a random mix of `get`, `put`, and `remove` operations on a shared cache for **30 seconds**.
   * Use `java.util.concurrent.CyclicBarrier` (or similar) to start all threads simultaneously.
   * After the stress run, verify:
     * No data corruption (e.g., a key’s value is always the most recent `put` for that key).
     * The cache size never exceeds `maxCapacity`.
     * The LRU order reported by `snapshotKeys()` is consistent with the observed accesses (you may record a concurrent log of accesses to validate).
3. **Edge‑case tests**:
   * Construction with invalid capacity.
   * `null` keys or values (document whether they are allowed; if not, ensure the appropriate exception is thrown).
4. **Performance sanity check** (optional): assert that 1 million mixed operations complete within a reasonable time (e.g., < 2 seconds on a typical modern CPU).

All tests must be deterministic; avoid flaky failures caused by timing issues. Use `awaitility`‑style waiting or `Thread.join` with timeouts if needed, but rely only on JDK utilities.

---

### 5. Deliverable Format

Output a **single** Java source file (or two files if you separate the test class) that can be compiled and run with `javac` and `java` without any additional setup. The file(s) should contain:

* The fully documented `ConcurrentLruCache` class.
* The `ConcurrentLruCacheTest` class annotated with `@Test` methods.
* A `public static void main(String[] args)` entry point that runs the JUnit platform console launcher (optional but helpful).

**Do not provide any solution or partial implementation here**—only the prompt describing what the model must produce.
<!-- RESPONSE -->
Here is the prompt for the model to generate the Java source files for the ConcurrentLruCache and ConcurrentLruCacheTest classes:

Write a single, self-contained Java program that implements a concurrent, thread-safe Least-Recently-Used (LRU) cache with a fixed maximum capacity and a full suite of JUnit 5 tests. The solution must satisfy all of the following requirements:

1. Public API:
   - Create a public class `ConcurrentLruCache<K, V>` with these members:
     - `public ConcurrentLruCache(int maxCapacity)`: Constructs a cache that can hold at most `maxCapacity` entries. `maxCapacity` must be > 0; throw `IllegalArgumentException` otherwise.
     - `public V get(K key)`: Returns the value associated with `key`, or `null` if the key is absent. This operation must update the LRU order (the accessed entry becomes most-recently used).
     - `public void put(K key, V value)`: Inserts or updates the mapping `key → value`. If the cache is at capacity, evict the least-recently used entry before inserting. This operation must update the LRU order (the inserted/updated entry becomes most-recently used).
     - `public V remove(K key)`: Removes the entry for `key` if present and returns its value, or `null` if absent.
     - `public int size()`: Returns the current number of entries.
     - `public List<K> snapshotKeys()`: Returns a thread-safe snapshot of the keys in LRU order, from most-recently used to least-recently used. The returned list must not be affected by subsequent cache modifications.
     - `public void clear()`: Removes all entries.
   - All public methods must be safe to call concurrently from any number of threads.

2. Concurrency Strategy (Documentation Required):
   - In the source file, include a concise Javadoc comment (≤ 250 words) that explains exactly how thread-safety is achieved, covering:
     - The underlying data structures (e.g., `ConcurrentHashMap`, custom doubly-linked list, `java.util.concurrent.locks.ReentrantLock`, etc.).
     - How you avoid deadlocks, race conditions, and ensure O(1) expected time for `get` and `put`.
     - How the LRU ordering is maintained under concurrent accesses.
     - Any memory-visibility guarantees you rely on (e.g., `volatile`, `AtomicReference`, lock ordering).

3. Implementation Constraints:
   - Do not use any third-party libraries; only the Java Standard Library (Java 17 or later).
   - The cache must achieve amortized O(1) for `get`, `put`, and `remove` under contention.
   - You may use `java.util.concurrent.locks.ReentrantReadWriteLock`, `StampedLock`, `synchronized`, `java.util.concurrent.atomic` primitives, or a combination, but you must justify the choice in the Javadoc.
   - The `snapshotKeys()` method must not block other operations longer than necessary and must return an immutable `List<K>` (e.g., `Collections.unmodifiableList`).

4. JUnit 5 Test Suite:
   - Provide a complete JUnit 5 test class `ConcurrentLruCacheTest` in the same file (or a separate file if you prefer) that includes:
     - Basic functional tests for each public method (insertion, retrieval, eviction order, removal, clear, snapshot correctness).
     - Concurrency stress tests:
       - Spawn at least 8 threads that perform a random mix of `get`, `put`, and `remove` operations on a shared cache for 30 seconds.
       - Use `java.util.concurrent.CyclicBarrier` (or similar) to start all threads simultaneously.
       - After the stress run, verify:
         - No data corruption (e.g., a key's value is always the most recent `put` for that key).
         - The cache size never exceeds `maxCapacity`.
         - The LRU order reported by `snapshotKeys()` is consistent with the observed accesses (you may record a concurrent log of accesses to validate).
     - Edge-case tests:
       - Construction with invalid capacity.
       - `null` keys or values (document whether they are allowed; if not, ensure the appropriate exception is thrown).
     - Performance sanity check (optional): assert that 1 million mixed operations complete within a reasonable time (e.g., < 2 seconds on a typical modern CPU).
   - All tests must be deterministic; avoid flaky failures caused by timing issues. Use `awaitility`-style waiting or `Thread.join` with timeouts if needed, but rely only on JDK utilities.

5. Deliverable Format:
   - Output a single Java source file (or two files if you separate the test class) that can be compiled and run with `javac` and `java` without any additional setup. The file(s) should contain:
     - The fully documented `ConcurrentLruCache` class.
     - The `ConcurrentLruCacheTest` class annotated with `@Test` methods.
     - A `public static void main(String[] args)` entry point that runs the JUnit platform console launcher (optional but helpful).

Do not provide any solution or partial implementation here—only the prompt describing what the model must produce.