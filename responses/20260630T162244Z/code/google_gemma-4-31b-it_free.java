<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
Here is a precise, technical prompt you can use to generate this specific implementation.

***

**Prompt:**

"Write a self-contained Java program that implements a highly concurrent, lock-free, multi-producer multi-consumer counter named `LockFreeAtomicCounter`. 

**Technical Requirements:**
1. **Implementation Details:** 
   - Use `java.util.concurrent.atomic.VarHandle` (introduced in Java 9) to manage the underlying state. Do not use `synchronized` blocks, `ReentrantLock`, or higher-level `AtomicLong` wrappers.
   - Implement the following methods: `void increment()`, `void decrement()`, `void add(long delta)`, and `long get()`.
   - The core logic must utilize a non-blocking **Compare-And-Swap (CAS) loop** to ensure updates are atomic.

2. **Documentation & Theory:**
   - Provide detailed Javadoc explaining the lock-free algorithm used.
   - Explain why the implementation is **linearizable** (i.e., why every operation appears to take place instantaneously at some point between its invocation and its response).
   - Explain how the implementation adheres to the **Java Memory Model (JMM)**, specifically discussing the happens-before relationships established by `VarHandle`'s memory semantics (e.g., volatile access/atomic updates) to prevent stale reads and ensure visibility across threads.

3. **Testing Suite (JUnit 5):**
   - Create a comprehensive test class that includes:
     - **Functional Correctness:** Basic tests for single-threaded increments, decrements, and additions.
     - **High-Contention Stress Test:** Use an `ExecutorService` and a `CountDownLatch` to synchronize the simultaneous start of 100 threads. Each thread should perform 1,000,000 operations (a mix of increments and decrements). The final count must be verified to ensure zero lost updates.
     - **ABA Scenario Analysis:** Include a test case or a detailed commented explanation demonstrating why the ABA problem does not affect a simple numeric counter, or how the implementation handles it if applicable.
     - **Performance Measurement:** Log the total time taken for the stress test to demonstrate the efficiency of the lock-free approach.

4. **Constraints:**
   - The code must be self-contained in a single file or a small set of files.
   - Ensure the code is compatible with Java 11 or higher.
   - No third-party libraries other than JUnit 5 for testing."