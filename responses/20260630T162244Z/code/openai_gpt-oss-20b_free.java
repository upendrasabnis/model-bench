<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
**Prompt for a Self‑Contained, Highly Concurrent, Lock‑Free Counter**

> **Objective**  
> Implement a *lock‑free* counter in Java that supports multiple producers and multiple consumers without using any explicit `synchronized` blocks, `ReentrantLock`, or other traditional locking mechanisms.  
> The implementation must rely solely on the Java Memory Model primitives `java.util.concurrent.atomic.VarHandle` (or, if you prefer a lower‑level approach, `sun.misc.Unsafe`).  
> The counter should be thread‑safe, linearizable, and resistant to the ABA problem.

---

### 1. Class Specification

* **Class name**: `LockFreeAtomicCounter`  
* **Package**: `com.example.concurrent` (or any relevant package you choose)  
* **Public API**:
  ```java
  public class LockFreeAtomicCounter {
      // Atomically increments the counter by 1
      public void increment();

      // Atomically decrements the counter by 1
      public void decrement();

      // Atomically adds the given delta (positive or negative)
      public void add(long delta);

      // Returns the current value of the counter
      public long get();
  }
  ```
* **Internal representation**:  
  * A single `long` field (e.g., `private volatile long value;`).  
  * Obtain a `VarHandle` for this field using `MethodHandles.lookup().findVarHandle(...)`.  
  * All updates must use `compareAndSet` or equivalent CAS operations.

---

### 2. Algorithmic Requirements

* Implement each mutation method (`increment`, `decrement`, `add`) as a **compare‑and‑set loop** that:
  1. Reads the current value (`get` via the `VarHandle`).
  2. Computes the new value (`current + delta`).
  3. Attempts to write the new value using `compareAndSet`.
  4. Retries if the CAS fails (i.e., another thread modified the value in the meantime).
* No blocking or waiting; the loop must be *non‑blocking* and *lock‑free*.
* Provide **in‑line documentation** (comments) that explains:
  * Why the CAS loop guarantees *linearizability*.
  * How the Java Memory Model ensures visibility and atomicity.
  * How the algorithm avoids lost updates even under extreme contention.

---

### 3. Testing Requirements

Create a **JUnit 5** test suite (`LockFreeAtomicCounterTest`) that covers:

1. **Functional correctness**  
   * Single‑threaded tests for `increment`, `decrement`, `add`, and `get`.  
   * Edge cases such as adding a very large delta, negative deltas, and overflow scenarios.

2. **Thread‑safety under heavy contention**  
   * Spin up **100 concurrent threads** (or more) using an `ExecutorService`.  
   * Each thread should perform **1 000 000** operations (mixed `increment`, `decrement`, `add` with random deltas).  
   * Use a `CountDownLatch` to ensure all threads start simultaneously and another latch to wait for completion.  
   * After all operations, compute the expected final value and assert equality with `get()`.

3. **No lost updates**  
   * In the contention test, keep a **separate atomic counter** (e.g., `AtomicLong`) that records the *total delta* each thread intends to apply.  
   * After all threads finish, compare `lockFreeCounter.get()` with the sum of intended deltas.

4. **ABA scenario test**  
   * Demonstrate a classic ABA issue and show that the implementation is **ABA‑safe** (i.e., no lost updates).  
   * Example:  
     * Thread A reads value `5`.  
     * Thread B updates it to `6` then back to `5`.  
     * Thread A attempts `compareAndSet(5, 10)` – it should succeed because the value is still `5`.  
     * Verify that the final value is as expected, proving that the CAS loop is robust.

5. **Stress test**  
   * Combine the above tests: run a *stress test* that mixes a high number of threads (e.g., 200) performing random operations for a prolonged period (e.g., 30 seconds).  
   * Use `System.nanoTime()` to measure throughput (operations per second) and ensure no exceptions or deadlocks occur.

---

### 4. Deliverables

1. **Source code**  
   * `LockFreeAtomicCounter.java` – fully documented implementation.  
   * `LockFreeAtomicCounterTest.java` – comprehensive JUnit 5 test suite as described.

2. **Documentation**  
   * Inline comments explaining the lock‑free algorithm, CAS loop, and JMM guarantees.  
   * A brief README (or JavaDoc) summarizing the design choices and how to run the tests.

3. **Build configuration**  
   * A simple `build.gradle` or `pom.xml` that compiles the code and runs the tests with JUnit 5.

---

**Note**: *Do not provide the actual implementation code here.* The goal is to supply a specification that a developer (or an AI) can follow to create the required program and tests.