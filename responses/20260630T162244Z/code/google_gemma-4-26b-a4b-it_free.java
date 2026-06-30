<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
Since you requested the **prompt** rather than the solution, here is the refined, highly technical prompt you can use to challenge an AI or a developer to produce this specific implementation.

***

# Technical Specification: Lock-Free Multi-Producer Multi-Consumer Counter

### Objective
Implement a high-performance,-lock-free, multi-producer multi-consumer (MPMC) counter in Java. The implementation must bypass traditional `synchronized` blocks and `ReentrantLock` instances, relying instead on low-level atomic primitives provided by `java.util.concurrent.atomic.VarHandle` (or `sun.misc.Unsafe` if preferred).

### Class Requirements
**Class Name:** `LockFreeAtomicCounter`

**Core API:**
*   `void increment()`: Atomically increments the counter by 1.
*   `void decrement()`: Atomically decrements the counter by 1.
*   `void add(long delta)`: Atomically adds the specified `long` value.
*   `long get()`: Returns the current value of the counter.

**Implementation Constraints:**
1.  **Lock-Free Algorithm:** You must implement the logic using a **Compare-And-Swap (CAS) loop** (optimistic concurrency control).
2.  **Memory Ordering:** Use `VarHandle` to ensure proper memory visibility. You must explicitly decide between `getVolatile`, `setVolatile`, `getAcquire`, `setRelease`, or `compareAndSet` to ensure the implementation adheres to the Java Memory Model (JMM).
3.er **No Explicit Locks:** The use of `synchronized`, `Lock`, or any blocking primitive is strictly prohibited.
4.  **Documentation:** Include a Javadoc block explaining:
    *   The specific CAS loop mechanism used.
    g*   **Linearizability:** Explain why each operation appears to take effect instantaneously at a single point in time between its invocation and response.
    *   **JMM Safety:** Explain how your choice of `VarHandle` access modes (e even if using `compareAndSet`) prevents instruction reordering and ensures visibility across threads.

### Testing Requirements (JUnit 5)
Provide a comprehensive test suite using JUnit 5 that includes:

1.  **Functional Correctness:** Simple single-threaded tests to verify basic arithmetic.
2.  *High Contention Stress Test:** 
    *   Use an `ExecutorService` to spawn **100 concurrent threads**.
    *   Each thread must perform **1,000,000 operations** (a mix of increments and decrements).
    *   Use a `CountDownLatch` to ensure all threads start simultaneously to maximize contention.
    *   **Verification:** The final value must exactly match the expected mathematical sum. Any discrepancy indicates a "lost update" (a failure of atomicity).
3.  **ABA Scenario Simulation:** While `long` counters are less prone to the classic ABA problem than pointer-based stacks, implement a test case that simulates rapid-fire updates to ensure the CAS loop correctly handles state changes between the `get` and the `compareAndSet` calls.
4.  **Throughput/Latency Observation:** A test that prints the time taken to complete the 100-million-operation workload.

### Deliverable Format
The response must be a single, self-contained Java file containing both the `LockFreeAtomicCounter` class and the JUnit 5 test class (or a standard Maven/Gradle structure if preferred, but self-contained is prioritized).