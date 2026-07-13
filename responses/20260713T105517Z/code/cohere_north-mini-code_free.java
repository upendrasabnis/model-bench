<!-- model: cohere/north-mini-code:free | category: code | run: 20260713T105517Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `CyclicBarrierPipeline` implementing a multi‑stage data processing pipeline coordinated with a `java.util.concurrent.CyclicBarrier`. The pipeline should have a configurable number of stages (at least three) and a configurable number of worker threads per stage. Each worker thread processes a chunk of data, waits at the barrier for its peers in the same stage, then passes its results to the next stage. The implementation must ensure thread‑safety, avoid deadlocks, and correctly handle exceptions thrown by any worker (propagating them to a central coordinator). Include comprehensive Javadoc explaining the concurrency strategy, barrier reuse, and shutdown procedure. Additionally, provide a full suite of JUnit 5 tests that verify:
• Correct functional output for a known input across all stages.
• Proper barrier synchronization (e.g., no thread proceeds to the next stage before all have arrived).
• Handling of worker exceptions (the pipeline should abort gracefully and report the error).
• Stress test with high thread counts and large data volumes to detect race conditions or livelocks.
Do not implement the solution; only output the prompt text.
<!-- RESPONSE -->
```text
/**
 * This document serves as the specification and prompt for implementing the {@code CyclicBarrierPipeline}.
 * 
 * <h2>Overview</h2>
 * You are to implement a public class named {@code CyclicBarrierPipeline} that creates a multi-stage data
 * processing pipeline. The pipeline must be coordinated using {@code java.util.concurrent.CyclicBarrier}
 * to synchronize worker threads within each stage.
 * 
 * <h2>Key Requirements</h2>
 * 
 * <h3>Class and Configuration</h3>
 * <ul>
 *   <li>The class must be {@code public final class CyclicBarrierPipeline<I, O>}.</li>
 *   <li>It must be generic over the input type {@code I} and the final output type {@code O}.</li>
 *   <li>The constructor must be configurable with:
 *     <ul>
 *       <li>{@code int numStages}: The total number of processing stages. Must be at least 3.</li>
 *       <li>{@code int workersPerStage}: The number of worker threads allocated to each stage.</li>
 *       <li>{@code List<DataProcessor<I, O>[]> stageProcessors}: An array (or varargs) of processor instances,
 *          where the length of this array must match {@code numStages}. Each element {@code stageProcessors[i]}
 *          defines the transformation logic for stage {@code i}.</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h3>Concurrency Model</h3>
 * <ul>
 *   <li>Each stage {@code i} must have exactly {@code workersPerStage} worker threads.</li>
 *   <li>Workers within a stage must share a single {@code CyclicBarrier}. They must process their assigned
 *       data chunks independently, then call {@code barrier.await()} to wait for all peers in the same stage.</li>
 *   <li>Once all peers arrive, the barrier opens, and workers proceed to transfer their results to the next stage.
 *       This ensures strict synchronization: no worker in stage {@code i} may begin transferring results to stage
 *       {@code i+1} until the last worker in stage {@code i} has completed processing.</li>
 *   <li>Data transfer between stages must be thread-safe. You may use {@code java.util.concurrent.BlockingQueue}
 *       or similar constructs to pass results from the output of stage {@code i} to the input of stage {@code i+1}.</li>
 *   <li>The implementation must avoid deadlocks. This implies careful ordering of resource acquisition (e.g.,
 *       barriers before queues) and timeout handling in {@code await()} calls if necessary.</li>
 * </ul>
 * 
 * <h3>Exception Handling and Central Coordinator</h3>
 * <ul>
 *   <li>The class must maintain a central coordinator (e.g., an {@code AtomicReference<Throwable>}) to capture
 *       the first exception thrown by any worker thread.</li>
 *   <li>If an exception occurs, the pipeline must abort gracefully:
 *     <ul>
 *       <li>All worker threads must be interrupted or stopped.</li>
 *       <li>Ongoing {@code barrier.await()} calls must be woken up (perhaps via {@code brokenBarrier}).</li>
 *       <li>The exception must be re-thrown (wrapped or propagated) to the caller of the processing method.</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h3>Javadoc Requirements</h3>
 * <ul>
 *   <li>Comprehensive Javadoc is required for the class, constructor, public methods ({@code process}, {@code shutdown}),</li>
 *   <li>and inner classes (e.g., {@code StageWorker}).</li>
 *   <li>The Javadoc must explicitly explain:
 *     <ul>
 *       <li>The concurrency strategy employed (how barriers synchronize stages).</li>
 *       <li>How the {@code CyclicBarrier} is reused across multiple passes if applicable (barrier reset behavior).</li>
 *       <li>The shutdown procedure and how it ensures thread interruption and resource cleanup.</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h2>JUnit 5 Test Suite Requirements</h2>
 * You must also provide a full suite of JUnit 5 tests in a separate class (e.g., {@code CyclicBarrierPipelineTest}).
 * The tests must verify the following:
 * 
 * <ol>
 *   <li><b>Functional Correctness</b>:
 *     <ul>
 *       <li>Create a known pipeline (e.g., Stage 1: square the number, Stage 2: add 1, Stage 3: multiply by 2).</li>
 *       <li>Feed a known list of inputs.</li>
 *       <li>Assert that the final outputs match the expected mathematical results.</li>
 *     </ul>
 *   </li>
 *   <li><b>Barrier Synchronization</b>:
 *     <ul>
 *       <li>Verify that no thread proceeds to transfer results to the next stage before all threads of the current stage have arrived.</li>
 *       <li>(Hint: You can use {@code CountDownLatch} or timing checks to ensure that the "next stage" receives data only in batch-sized increments corresponding to the number of workers.)</li>
 *     </ul>
 *   </li>
 *   <li><b>Exception Handling</b>:
 *     <ul>
 *       <li>Configure one of the stages to throw an exception for a specific input.</li>
 *       <li>Assert that the pipeline fails fast, the central coordinator captures the exception, and subsequent processing is halted.</li>
 *       <li>Assert that the calling method propagates the error.</li>
 *     </ul>
 *   </li>
 *   <li><b>Stress Test</b>:
 *     <ul>
 *       <li>Run the pipeline with a high number of stages (e.g., 10) and high thread counts (e.g., 20 workers per stage).</li>
 *       <li>Use large data volumes (e.g., thousands of items).</li>
 *       <li>Assert that the pipeline completes without race conditions, deadlocks, or livelocks (detectable via timeouts or inconsistent state).</li>
 *     </ul>
 *   </li>
 * </ol>
 * 
 * <h2>Formatting Constraints</h2>
 * <ul>
 *   <li>Do <b>not</b> implement the solution in this prompt. This text is the prompt specification only.</li>
 *   <li>Output only the prompt text describing the requirements, class signatures, and test specifications as detailed above.</li>
 * </ul>
 */
public final class CyclicBarrierPipeline {
    // Placeholder for the specification prompt structure.
    // Actual implementation is to be provided by the solver.
}

/**
 * Interface representing a single processing unit in the pipeline.
 * @param <I> Input type
 * @param <O> Output type
 */
interface DataProcessor<I, O> {
    O process(I input) throws Exception;
}

/**
 * JUnit 5 test suite for {@code CyclicBarrierPipeline}.
 * All test methods must adhere to the requirements specified in the main prompt text.
 */
class CyclicBarrierPipelineTest {
    // Test implementations are to be provided by the solver.
}
```