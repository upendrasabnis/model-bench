<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
**Prompt**

Create a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using only `java.util.concurrent.atomic.VarHandle` (or `sun.misc.Unsafe`) and **no explicit synchronization blocks**.  

**Requirements**

1. **Class name**: `LockFreeAtomicCounter`.
2. **Public API**  
   - `void increment()` – atomically adds 1.  
   - `void decrement()` – atomically subtracts 1.  
   - `void add(long delta)` – atomically adds the given `delta`.  
   - `long get()` – returns the current value (volatile read).  
3. **Algorithm** – Use a compare‑and‑set (CAS) loop (e.g., `VarHandle.compareAndSet` or `Unsafe.compareAndSwapLong`). Document the algorithm in‑line, explaining why the CAS loop is lock‑free, linearizable, and safe under the Java Memory Model (happens‑before guarantees of volatile/VarHandle accesses).  
4. **No `synchronized`, `ReentrantLock`, `StampedLock`, or any other blocking primitives**.  
5. **Comprehensive JUnit 5 test suite** (separate test class) that verifies:  
   - **Functional correctness** – single‑threaded sequence of operations matches a plain `long`.  
   - **Thread‑safety under heavy contention** – e.g., 100 threads each performing 1 000 000 random `increment`, `decrement`, and `add` operations.  
   - **No lost updates** – final counter value equals the sum of all deltas applied by all threads.  
   - **Stress test harness** – use `ExecutorService` + `CountDownLatch` (or `Phaser`) to start all workers simultaneously and wait for completion.  
   - **ABA‑scenario provocation** – design a test that forces the CAS to see the same value twice (e.g., by rapidly incrementing and decrementing the same amount) and assert the counter remains correct.  
6. **Build‑ready** – single Maven/Gradle compatible source layout (or a single file with `public static void main` that runs the tests programmatically).  
7. **Documentation** – Javadoc on the class and each method, plus a short design rationale section (can be a comment block) describing lock‑freedom, linearizability, and JMM safety.  

**Deliverable**: Provide only the **prompt** (this text) – do **not** write the solution code.