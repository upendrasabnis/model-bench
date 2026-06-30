<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
**Prompt**

You are to generate a complete, self‑contained Java program that implements a highly concurrent, lock‑free multi‑producer/multi‑consumer counter using `java.util.concurrent.atomic.VarHandle` (or, alternatively, `sun.misc.Unsafe`). The implementation must **not** contain any explicit synchronization blocks (`synchronized`, `Lock`, etc.).  

Your output should consist of:

1. **Source code** for a public class named `LockFreeAtomicCounter` placed in a single file (`LockFreeAtomicCounter.java`).  
   - Fields: a `VarHandle` (or `Unsafe`) backed `volatile long` that holds the counter value.  
   - Public API:  
     ```java
     public void increment();
     public void decrement();
     public void add(long delta);
     public long get();
     ```
   - Each method must implement a classic compare‑and‑set (CAS) loop using the `VarHandle.compareAndSet` (or `Unsafe.compareAndSwapLong`) operation, retrying until the update succeeds.  
   - Include concise Javadoc comments that:
     - Describe the lock‑free algorithm (CAS loop).  
     - Explain why the algorithm is linearizable (each successful CAS appears to take effect instantaneously at the point of the successful compare‑and‑set).  
     - Argue safety under the Java Memory Model (the `volatile`/`VarHandle` provides the necessary happens‑before guarantees; the CAS operation is atomic and establishes a total order of successful updates).  

2. **JUnit 5 test suite** in a separate source file (`LockFreeAtomicCounterTest.java`) that thoroughly validates the implementation. The test suite must contain:
   - **Functional correctness tests** (single‑threaded scenarios for `increment`, `decrement`, `add`, and `get`).  
   - **Heavy‑contention test**:  
     - Create an `ExecutorService` with a fixed thread pool (e.g., 100 threads).  
     - Use a `CountDownLatch` to make all threads start simultaneously.  
     - Each thread performs 1 000 000 mixed operations (e.g., random choice of `increment`, `decrement`, `add` with a small delta).  
     - After all threads finish, assert that the final counter value equals the sum of all deltas applied (no lost updates).  
   - **ABA‑scenario test**:  
     - Construct a scenario where a thread reads a value, another thread changes it twice returning to the original value, and the first thread attempts a CAS based on the stale read.  
     - Verify that the CAS correctly fails and the algorithm retries until it succeeds, demonstrating that the implementation is not vulnerable to the ABA problem (explain any mitigation used, such as using a wide enough counter or a version stamp if needed).  
   - **Stress / longevity test**: run the heavy‑contention test for multiple iterations (e.g., 5 rounds) and record elapsed time to show performance characteristics (optional but encouraged).  
   - Use appropriate assertions (`assertEquals`, `assertTrue`, etc.) and, if desired, simple logging or `System.out.println` to report throughput.  

3. **Additional requirements**:
   - No use of `synchronized`, `java.util.concurrent.locks.Lock`, `java.util.concurrent.atomic.AtomicLong`, or any other explicit locking mechanism.  
   - All concurrent updates must rely solely on the CAS loop via `VarHandle` (or `Unsafe`).  
   - Ensure the code compiles with Java 17 or later.  
   - Provide clear, concise comments throughout the code to aid readability.  

Your final answer should consist only of the two source files (or a single multi‑file block) described above; **do not** include any explanatory text outside of the code comments. The generated code must be ready to compile and run with JUnit 5 on a standard JDK.