<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->
**Prompt for a Self‑Contained Java Pipeline Using `CyclicBarrier`**

> Write a **self‑contained Java program** that defines a public class named `CyclicBarrierPipeline`.  
> The program should implement a **multi‑stage data processing pipeline** coordinated with a `java.util.concurrent.CyclicBarrier`.  
> The key requirements are:
> 
> 1. **Configurable Pipeline**  
>    * The number of stages must be configurable (minimum of three stages).  
>    * The number of worker threads per stage must also be configurable.  
>    * Each stage consists of a fixed set of worker threads that concurrently process a chunk of data.
> 
> 2. **Barrier Synchronization**  
>    * After a worker thread finishes processing its chunk, it must wait at a stage‑specific `CyclicBarrier` until all workers in that stage have arrived.  
>    * Only after all workers have reached the barrier may the next stage tratado begin processing the data produced by the previous stage.  
>    * The barrier should be **reused** for each round of data processing in the pipeline.
> 
> 3. **Data Flow**  
>    * Workers in stage *n* receive data from a thread‑safe queue or buffer supplied by stage *n‑1*.  
>    * Processed results are placed into the next stage’s input queue for consumption.  
>    * The final stage passes the fully processed data to a central coordinator or collects the results for verification.
> 
> 4. **Thread Safety & Deadlock Avoidance**  
>    * Use thread‑safe collections (e.g., `BlockingQueue`) for inter‑stage communication.  
>    * Ensure that the barrier is correctly reset after each cycle and that no thread remains blocked indefinitely.  
>    * Design the shutdown procedure so that all threads terminate gracefully even when the pipeline is aborted.
> 
> 5. **Exception Handling**  
>    * If any worker throws an exception during processing, that exception must be captured and propagated to a **central coordinator**.  
>    * Upon detecting an exception, the pipeline should abort **gracefully**, cancelling remaining work and releasing all resources.  
>    * The coordinator should report the error to the caller or log it appropriately.
> 
> 6. **Javadoc Requirements**  
>    * Provide comprehensive Javadoc for the `CyclicBarrierPipeline` class and all public methods.  
>    * Explain the concurrency strategy, how the barrier is reused across processing rounds, and the shutdown procedure.  
>    * Include examples of how to instantiate the pipeline with different configurations.
> 
> 7. **JUnit 5 Test Suite**  
>    * **Functional Test** – Verify that for a known input, the pipeline produces the correct output across all stages.  
>    * **Barrier Synchronization Test** – Confirm that no worker proceeds to the next stage before all peers in its current stage have reached the barrier (e.g., by using latches or monitoring timestamps).  
>    * **Exception Handling Test** – Inject a worker that throws an exception and assert that the pipeline aborts gracefully and the exception is reported to the coordinator.  
>    * **Stress Test** – Run the pipeline with a high number of threads (e.g., 50 workers per stage) and large data volumes (e.g., millions of items) to detect race conditions or livelocks.  
>    * Each test should be self‑contained, use temporary data structures, and clean up resources after execution.
> 
> 8. **Packaging**  
>    * The program must compile with Java 17 or later.  
>    * All necessary imports should be included.  
>    * The code should be ready to compile and run without external dependencies beyond the JDK and JUnit 5.
> 
> **Deliverables**  
> * A single Java file containing the `CyclicBarrierPipeline` class with all required functionality and Javadoc.  
> * A separate test class (or classes) using JUnit 5 that implements the tests described above.  
> * No actual implementation code should be provided in this prompt; only the specification text is required.