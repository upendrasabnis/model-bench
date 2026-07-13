<!-- model: poolside/laguna-m.1:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->

**Prompt:**  
Create a self-contained Java program that defines a public class named `CyclicBarrierPipeline` to implement a multi-stage data processing pipeline using `java.util.concurrent.CyclicBarrier`. The program must meet the following requirements:

---

### **Class Requirements**
1. **Multi-Stage Pipeline**:
   - The pipeline must support a configurable number of stages (minimum 3).
   - Each stage must have a configurable number of worker threads.
   - Workers in a stage process a chunk of data, wait at a `CyclicBarrier` until all peers in the same stage arrive, then pass their results to the next stage.

2. **Concurrency Strategy**:
   - Use thread-safe queues (e.g., `LinkedBlockingQueue`) to pass data between stages.
   - Each stage’s workers must synchronize using a `CyclicBarrier` to ensure all workers complete their current chunk before the next stage begins processing.
   - The barrier must be reused for multiple data batches (i.e., the pipeline can process successive batches without reinitializing barriers).

3. **Exception Handling**:
   - If any worker throws an exception, the pipeline must abort gracefully.
   - Exceptions must be propagated to a central coordinator (e.g., the main thread or a dedicated handler).
   - Ensure that all worker threads are terminated cleanly upon pipeline abortion.

4. **Shutdown Procedure**:
   - Provide a method to shut down the pipeline safely (e.g., `shutdown()`).
   - Workers must periodically check for shutdown signals and exit their processing loops cleanly.

5. **Javadoc**:
   - Document the concurrency strategy, including how barriers synchronize workers and ensure thread-safety.
   - Explain barrier reuse for processing multiple batches.
   - Describe the shutdown mechanism and how exceptions are handled and propagated.

---

### **Test Suite Requirements (JUnit 5)**
Write a full suite of tests to verify:
1. **Functional Correctness**:
   - Input known data through the pipeline and verify the output after all stages.
   - Example: Stage 1 adds 1, Stage 2 multiplies by 2, Stage 3 adds 3. Input `5` should produce `15`.

2. **Barrier Synchronization**:
   - Ensure no worker in a stage proceeds to the next stage before all peers have arrived at the barrier.
   - Example: Introduce artificial delays in one worker and verify others wait.

3. **Exception Handling**:
   - Force a worker to throw an exception and verify the pipeline aborts, reports the error, and no further processing occurs.

4. **Stress Testing**:
   - Test with high thread counts (e.g., 100 workers per stage) and large data volumes (e.g., 10,000 elements).
   - Run multiple iterations to detect race conditions or livelocks.

---

### **Constraints**
- Do not implement the solution. Output only the prompt text.
- Assume familiarity with Java concurrency utilities (`CyclicBarrier`, `BlockingQueue`, `ExecutorService`).
- Ensure the program is thread-safe, avoids deadlocks, and handles exceptions cleanly.
