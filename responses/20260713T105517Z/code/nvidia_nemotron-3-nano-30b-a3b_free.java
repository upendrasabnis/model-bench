<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->
**Prompt (do not include any implementation code, only this description):**

---

Write a **self‑contained Java program** that defines a public class named `CyclicBarrierPipeline`. The class must implement a **multi‑stage data processing pipeline** coordinated with a `java.util.concurrent.CyclicBarrier`.  

### Core Requirements  

1. **Configurable pipeline**  
   - The number of processing stages must be configurable (minimum **three** stages).  
   - Each stage must be capable of running with a configurable number of **worker threads** (e.g., via constructor arguments or builder pattern).  

2. **Worker behavior**  
   - Each worker thread processes a chunk of input data.  
   - After completing its work, the thread must **wait at the barrier** for all other workers in the same stage before proceeding to the next stage.  
   - The results of a stage must be passed safely to the next stage (e.g., via a thread‑safe queue, shared buffer, or direct hand‑off).  

3. **Thread‑safety & deadlock avoidance**  
   - The design must guarantee that no thread can deadlock while waiting at a barrier.  
   - All shared state must be protected appropriately (e.g., using concurrent collections or proper synchronization).  

4. **Exception handling**  
   - If any worker thread throws an exception, the exception must be **captured and propagated to a central coordinator** (e.g., the main thread or a dedicated monitor).  
   - Upon detection of an error, the pipeline should **shut down gracefully**, aborting further processing and reporting the failure.  

5. **Barrier reuse**  
   - The same `CyclicBarrier` instance must be reused for each stage transition (i.e., the barrier is reset or configured to allow reuse).  

6. **Shutdown procedure**  
   - Provide a method (e.g., `shutdown()`) that cleanly terminates all worker threads, releases barrier resources, and ensures no lingering tasks.  

### Documentation  
- Include **comprehensive Javadoc** for the class, its constructors, public methods, and any inner classes.  
- Explain the concurrency strategy, how the barrier is reused, and the shutdown semantics.  

---

### Required JUnit 5 Test Suite  

Create a **complete JUnit 5 test class** (e.g., `CyclicBarrierPipelineTest`) that validates the implementation against the following criteria:

1. **Functional correctness**  
   - Run the pipeline with a known input dataset and verify that the final output matches the expected result across all configured stages.  

2. **Barrier synchronization**  
   - Ensure that no worker thread proceeds to the next stage until **all** workers in the current stage have arrived at the barrier.  
   - Use timing assertions or concurrency‑aware checks to confirm proper ordering.  

3. **Exception propagation**  
   - Simulate a worker throwing an exception and verify that the exception is correctly captured and reported by the central coordinator.  
   - Confirm that the pipeline shuts down gracefully after the error is detected.  

4. **Stress testing**  
   - Execute the pipeline with a **high thread count** (e.g., dozens or hundreds of workers per stage) and a **large data volume**.  
   - Verify that the tests complete without race conditions, livelocks, or deadlocks.  
   - Optionally, run multiple iterations to increase confidence that the system remains stable under load.  

---

**Submission**  
- Provide only the **source code** for `CyclicBarrierPipeline` and `CyclicBarrierPipelineTest`.  
- Do **not** include any build scripts, external library dependencies beyond JUnit 5 and the JDK, or unrelated files.  
- The entire solution must be **self‑contained** and compile/run with a standard JDK (e.g., JDK 17+).  

--- 

*End of prompt.*