<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260704T180536Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a complex, non‑trivial multi‑threaded component using a CompletableFuture fan‑out/fan‑in pipeline. The program must define a public class named `AsyncProcessor` that provides:

1. A method `public CompletableFuture<List<Result>> processBatch(List<Input> inputs)` which concurrently processes each `Input` by invoking a supplied `Function<Input, CompletableFuture<Result>>` task, merges the results preserving order, and returns a `CompletableFuture` that completes when all sub‑tasks finish.
2. Proper handling of exceptions: if any sub‑task fails, the whole batch should complete exceptionally with a clear aggregated error message.
3. A configurable thread‑pool executor (e.g., `ForkJoinPool` or custom `ExecutorService`) that limits the maximum parallelism and can be shut down gracefully.
4. Comprehensive Javadoc explaining the concurrency strategy, ordering guarantees, and error‑propagation policy.

In addition, provide a full suite of JUnit 5 tests that includes:
- Unit tests for successful batch processing with varied input sizes.
- Tests verifying order preservation of results.
- Stress tests that launch many concurrent `processBatch` calls to expose race conditions or resource leaks.
- Tests that deliberately cause some sub‑tasks to throw exceptions and assert that the returned `CompletableFuture` completes exceptionally with the expected aggregated message.
- Tests confirming proper shutdown of the internal executor without lingering threads.

The entire code (including `AsyncProcessor`, any supporting classes, and the JUnit 5 test class) must be runnable as a single file (or a minimal Maven/Gradle project) without external dependencies beyond the JDK and JUnit 5. Do NOT provide a solution; only supply the prompt text.
<!-- RESPONSE -->
**Prompt for Generating a Self‑Contained Java Program**

Write a single Java source file (or a minimal Maven/Gradle project) that contains **only**:

1. **`AsyncProcessor` class**  
   - A public class named `AsyncProcessor`.  
   - A method with the signature:  
     ```java
     public CompletableFuture<List<Result>> processBatch(
         List<Input> inputs,
         Function<Input, CompletableFuture<Result>> taskFunction)
     ```  
   - The method must:
     * Execute each `Input` concurrently using a configurable thread‑pool executor (e.g., a `ForkJoinPool` or custom `ExecutorService`).  
     * Preserve the original order of `Result`s in the returned `List`.  
     * Return a `CompletableFuture` that completes when **all** sub‑tasks finish.  
     * Propagate any exception from a sub‑task so that the returned `CompletableFuture` completes exceptionally with an aggregated error message that clearly indicates which input(s) failed.  
     * Allow graceful shutdown of the internal executor.  
   - Include comprehensive Javadoc for the class and the `processBatch` method that explains:
     * The concurrency strategy (fan‑out/fan‑in).  
     * Ordering guarantees.  
     * Error‑propagation policy.  

2. **Supporting domain classes** (defined within the same source file)  
   - `Input` – a simple immutable data holder (e.g., `String id`, `int value`).  
   - `Result` – a simple immutable data holder (e.g., `String id`, `String payload`).  

3. **JUnit 5 test class** (also defined in the same source file) that contains:  
   - Unit tests for successful batch processing with various input sizes (including empty and single‑element lists).  
   - Tests that verify order preservation of results.  
   - Stress tests that invoke `processBatch` many times concurrently to expose race conditions or resource leaks.  
   - Tests that deliberately cause some sub‑tasks to throw exceptions and assert that the returned `CompletableFuture` completes exceptionally with the expected aggregated message.  
   - Tests confirming that the internal executor shuts down cleanly without lingering threads.  

**Constraints**  
- The entire code must be **self‑contained**; no external libraries other than the JDK and JUnit 5 may be referenced.  
- The source file (or minimal project) must compile and run with a standard JDK (e.g., JDK 17+) and JUnit 5 on the classpath.  
- Do **not** provide any implementation code — only the prompt description above.  

Your task is to generate the above prompt exactly as described, ensuring it includes all required elements and constraints.