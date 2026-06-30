<!-- model: cohere/north-mini-code:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
**Prompt for an AI Assistant**

---

**Task**  
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using `java.util.concurrent.atomic.VarHandle` (or `sun.misc.Unsafe`) **without any explicit synchronization blocks**.  

The class must be named `LockFreeAtomicCounter` and expose the following public API:

```java
public class LockFreeAtomicCounter {
    // No‑arg constructor
    public LockFreeAtomicCounter();

    // Atomically increment the counter by one
    public void increment();

    // Atomically decrement the counter by one
    public void decrement();

    // Atomically add the given delta (may be positive, negative, or zero)
    public void add(long delta);

    // Return the current value of the counter
    public long get();
}
```

---

### Requirements

1. **Lock‑free algorithm**  
   * Use a *compare‑and‑set* (CAS) loop to update the counter atomically.  
   * Document the algorithm in clear comments or a separate Javadoc block, explaining how the CAS loop works, why it is lock‑free, and how it avoids lost updates.

2. **Java Memory Model (JMM) safety**  
   * Explain why the implementation is linearizable and safe under the JMM.  
   * Reference the volatile semantics of `VarHandle` (or `Unsafe`) and the happens‑before relationships guaranteed by CAS.

3. **Implementation constraints**  
   * Do **not** use any `synchronized`, `ReentrantLock`, `Atomic*` classes (except for the low‑level `VarHandle`/`Unsafe` primitives).  
   * The solution must be self‑contained (single source file or a small set of related files) and compile with a standard JDK.

4. **JUnit 5 test suite**  
   * Provide a complete JUnit 5 test class (`LockFreeAtomicCounterTest`) that validates:
     * **Functional correctness** – basic operations (`increment`, `decrement`, `add`) behave as expected on a single thread.  
     * **Thread‑safety under heavy contention** – spawn **100 threads**, each performing **1,000,000** operations (a mix of increments, decrements, and `add` with random deltas). Verify that the final value matches the expected mathematical result.  
     * **No lost updates** – after the stress test, assert that the observed total change equals the sum of all individual deltas applied.  
   * Use `ExecutorService` (e.g., `ForkJoinPool` or `Executors.newFixedThreadPool`) and `CountDownLatch` to coordinate start/stop of the threads.  
   * Measure and report the elapsed time (optional but recommended for performance comparison).

5. **ABA‑provoking tests**  
   * Design additional test scenarios that deliberately trigger ABA‑like conditions (e.g., by interleaving reads and writes with intermediate values that return to the original state). The test should verify that the counter still reflects the net effect of all operations despite the ABA potential.

6. **Code style & documentation**  
   * Follow clean code conventions: meaningful variable names, appropriate encapsulation, and consistent formatting.  
   * Include Javadoc for the public API and for any internal helper methods.  
   * Add comments that explain the CAS loop, linearization points, and any memory‑visibility guarantees relied upon.

7. **No solution code**  
   * **Do not** output the actual implementation or test code.  
   * Your response should be **only the prompt** (i.e., the detailed specification above) that you would give to an AI to generate the requested program.

---

**Output requirement**  
Provide **only** the prompt described above. No code, no explanations, no solutions—only the request for the AI to generate the program.