<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->


**Prompt:**  Write a self-contained Java program named `LockFreeAtomicCounter` that implements a highly concurrent, lock-free, multi-producer multi-consumer counter using `java.util.concurrent.atomic.VarHandle` (or `sun.misc.Unsafe` if preferred, though `VarHandle` is encouraged for modern compatibility). The class must provide the following methods:  
- `void increment()`: Atomically increments the counter by 1.  
- `void decrement()`: Atomically decrements the counter by 1.  
- `void add(long delta)`: Atomically adds the specified `delta` to the counter.  
- `long get()`: Returns the current value of the counter.  

### Requirements:  
1. **Lock-Free Algorithm**:  
   - Implement the counter using a compare-and-set (CAS) loop to ensure thread safety without explicit synchronization blocks (e.g., `synchronized`, `ReentrantLock`).  
   - Use `VarHandle` (or `Unsafe`) to perform atomic operations directly on the counter’s memory location.  
   - Document the algorithm’s design, including how it avoids race conditions and ensures progress under contention.  

2. **Linearity and Safety Under the Java Memory Model (JMM)**:  
   - Explain how the algorithm ensures linearizability (i.e., operations appear to occur instantaneously) and memory visibility (i.e., all threads see the most recent value).  
   - Justify why the use of `VarHandle` or `Unsafe` with atomic operations complies with the JMM, including proper ordering of memory accesses.  

3. **Comprehensive JUnit 5 Test Suite**:  
   - **Functional Correctness**: Verify that all methods (`increment`, `decrement`, `add`, `get`) behave as expected under normal conditions.  
   - **Thread-Safety Under Heavy Contention**:  
     - Test with 100 threads, each performing 1,000,000 operations (mix of `increment`, `decrement`, and `add`).  
     - Ensure no lost updates occur (e.g., final count matches expected value after all operations).  
   - **Stress Tests**:  
     - Use `ExecutorService` and `CountDownLatch` to coordinate thread start/stop and measure performance.  
   - **ABA Scenario Tests**:  
     - Design tests that intentionally provoke ABA-like scenarios (e.g., rapid value changes or memory reallocation) to demonstrate correctness.  

4. **Documentation**:  
   - Include clear comments in the code explaining the lock-free mechanism and its adherence to the JMM.  
   - Provide a README or comments detailing how to run the tests and interpret results.  

### Constraints:  
- No external libraries beyond the Java Standard Library (e.g., no third-party concurrency utilities).  
- Avoid using `AtomicLong` or other built-in atomic classes; implement the counter from scratch using `VarHandle` or `Unsafe`.  
- Ensure the solution is self-contained and compilable as a single Java file.  

**Note**: The solution must not include any explicit synchronization blocks (e.g., `synchronized`, `Lock` instances). All synchronization must be achieved through atomic operations provided by `VarHandle` or `Unsafe`.
