<!-- model: poolside/laguna-m.1:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->

Write a self-contained Java program that implements a highly concurrent, lock-free, multi-producer multi-consumer counter using `java.util.concurrent.atomic.VarHandle` (or `sun.misc.Unsafe`) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide the following methods:

- `increment()`: Atomically increments the counter by 1.
- `decrement()`: Atomically decrements the counter by 1.
- `add(long delta)`: Atomically adds a specified delta to the counter.
- `long get()`: Returns the current value of the counter.

Document the lock-free algorithm used (e.g., compare-and-swap loop) and explain why it is linearizable and safe under the Java Memory Model (JMM). Include a comprehensive JUnit 5 test suite that verifies:

1. Functional correctness of the counter in a single-threaded context.
2. Thread-safety under heavy contention, using 100 threads each performing 1,000,000 operations (increments, decrements, or additions).
3. No lost updates occur during concurrent operations, validated by comparing final counter values against expected totals.
4. Stress tests using `ExecutorService` and `CountDownLatch` to coordinate thread start/stop for concurrent execution.
5. Intentional ABA scenario tests to demonstrate correctness in cases where intermediate values may repeat or wrap around (e.g., a thread reads value A, another thread modifies it to B and back to A before the first thread's CAS operation completes).

Ensure all tests are repeatable and deterministic, and that the implementation does not use explicit synchronization primitives like `synchronized` blocks or `ReentrantLock`.
