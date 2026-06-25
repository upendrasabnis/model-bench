<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260625T020407Z -->
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
**Prompt for the Model**

You are to generate a complete, self‑contained Java source file (or two files if you prefer) that implements a concurrent, thread‑safe LRU cache and a full JUnit 5 test suite for it. The generated code must satisfy every requirement listed below. Do **not** include any explanations, commentary, or partial snippets—only the compilable Java source.

---

### 1. Public API – `ConcurrentLruCache<K, V>`

Create a public generic class named `ConcurrentLruCache<K, V>` with the following members:

| Signature | Description |
|-----------|-------------|
| `public ConcurrentLruCache(int maxCapacity)` | Constructs a cache that can hold at most `maxCapacity` entries. `maxCapacity` must be > 0; otherwise throw `IllegalArgumentException`. |
| `public V get(K key)` | Returns the value associated with `key`, or `null` if absent. On a hit, the entry becomes the most‑recently used. |
| `public void put(K key, V value)` | Inserts or updates `key → value`. If the cache is at capacity, evict the least‑recently used entry before inserting. The inserted/updated entry becomes most‑recently used. |
| `public V remove(K key)` | Removes the entry for `key` if present and returns its value, or `null` if absent. |
| `public int size()` | Returns the current number of entries. |
| `public List<K> snapshotKeys()` | Returns a thread‑safe snapshot of the keys in LRU order (most‑recently used → least‑recently used). The returned list must be immutable and unaffected by later cache modifications. |
| `public void clear()` | Removes all entries. |

All public methods must be safe for concurrent invocation from any number of threads.

---

### 2. Concurrency Strategy Documentation

Inside the source file, place a concise Javadoc comment (≤ 250 words) on the class that explains **exactly** how thread‑safety is achieved. The comment must cover:

* The underlying data structures you use (e.g., `ConcurrentHashMap`, a custom doubly‑linked list, locks, atomics, etc.).
* How you avoid deadlocks and race conditions while guaranteeing **amortized O(1)** expected time for `get`, `put`, and `remove` under contention.
* How LRU ordering is maintained correctly when multiple threads access the cache concurrently.
* The memory‑visibility guarantees you rely on (e.g., `volatile`, `AtomicReference`, lock ordering, etc.).
* The rationale for any lock type you choose (`ReentrantReadWriteLock`, `StampedLock`, `synchronized`, etc.).

---

### 3. Implementation Constraints

* Use only the Java Standard Library (Java 17 or later). No third‑party dependencies.
* Ensure `get`, `put`, and `remove` run in amortized O(1) time even when many threads contend.
* You may employ `java.util.concurrent.locks.ReentrantReadWriteLock`, `StampedLock`, `synchronized`, `java.util.concurrent.atomic` classes, or a combination, but justify the choice in the Javadoc.
* `snapshotKeys()` must not block other operations longer than necessary and must return an immutable `List<K>` (e.g., `Collections.unmodifiableList`).

---

### 4. JUnit 5 Test Suite – `ConcurrentLruCacheTest`

Create a test class named `ConcurrentLruCacheTest` (in the same file or a separate file) that includes:

1. **Basic functional tests** for each public method:
   * Insertion, retrieval, update, eviction order, removal, `clear`, and snapshot correctness.
2. **Concurrency stress tests**:
   * Spawn **at least 8** threads that repeatedly perform a random mix of `get`, `put`, and `remove` on a shared cache for **30 seconds**.
   * Use a `java.util.concurrent.CyclicBarrier` (or equivalent) to start all threads simultaneously.
   * After the run, verify:
     * No data corruption – each key’s value equals the most recent `put` for that key.
     * Cache size never exceeds the configured `maxCapacity`.
     * The order returned by `snapshotKeys()` is consistent with a concurrently recorded access log (you may maintain a thread‑safe log of operations to validate LRU order).
3. **Edge‑case tests**:
   * Construction with invalid capacity (≤ 0) throws `IllegalArgumentException`.
   * Behavior with `null` keys or values – state clearly whether they are allowed; if not, ensure the appropriate exception (`NullPointerException` or `IllegalArgumentException`) is thrown.
4. **Optional performance sanity check**:
   * Assert that 1 million mixed operations complete within a reasonable time (e.g., < 2 seconds on a typical modern CPU).

All tests must be deterministic; avoid reliance on fragile timing. Use `awaitility`‑style waiting or `Thread.join` with timeouts only if needed, and rely solely on JDK utilities.

---

### 5. Deliverable Format

* Output a **single** Java source file (or two files if you separate the test class) that can be compiled and run with `javac` and `java` without any additional setup.
* The file(s) must contain:
  * The fully documented `ConcurrentLruCache<K, V>` class.
  * The `ConcurrentLruCacheTest` class annotated with JUnit 5 `@Test` methods.
  * Optionally, a `public static void main(String[] args)` method that launches the JUnit Platform Console Launcher (helpful but not required).

**Do not provide any solution, explanation, or partial code—only the final, compilable Java source that fulfills the specification.**