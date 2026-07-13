<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->
Write a self-contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi-stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. 

The pipeline must feature:
1.  **Configurability**: A configurable number of stages (at least three) and a configurable number of worker threads per stage.
2.  **Synchronization Strategy**: Each worker thread processes a chunk of data, waits at a `CyclicBarrier` for its peers in the same stage to complete, and then passes its results to the next stage.
3.  **Thread-Safety & Robustness**: The implementation must ensure thread-safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator/manager).
4.  **Documentation**: Provide comprehensive Javadoc explaining the concurrency strategy, the mechanism for barrier reuse across stages, and the shutdown procedure.

Provide a full suite of JUnit 5 tests that verify:
*   **Functional Correctness**: Ensuring the final output is mathematically correct for a known input across all stages.
*   **Synchronization Integrity**: Verifying that no thread proceeds to the next stage before all peers in the current stage have arrived at the barrier.
*   **Error Propagation**: Validating that if a worker throws an exception, the pipeline aborts gracefully and the error is reported to the coordinator.
*   **Stress Testing**: Running the pipeline with high thread counts and large data volumes to detect potential race conditions or livelocks.