<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.