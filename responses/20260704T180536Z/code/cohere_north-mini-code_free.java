<!-- model: cohere/north-mini-code:free | category: code | run: 20260704T180536Z -->
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
**Prompt for Generating the Code**

Write a single, self‑contained Java program that implements a complex, non‑trivial multi‑threaded component using a `CompletableFuture` fan‑out/fan‑in pipeline. The program must define a public class named `AsyncProcessor` that provides:

1. **Core Processing Method**  
   ```java
   public CompletableFuture<List<Result>> processBatch(
       List<Input> inputs,
       Function<Input, CompletableFuture<Result>> taskFactory
   )
   ```  
   - For each `Input` in the supplied `inputs` list, invoke the `taskFactory` to obtain a `CompletableFuture<Result>`.  
   - Execute these futures concurrently using a configurable thread‑pool executor that limits the maximum parallelism.  
   - Merge the resulting `Result`s back into a `List<Result>` **preserving the original order** of the `inputs`.  
   - Return a `CompletableFuture<List<Result>>` that completes when **all** sub‑tasks finish, either successfully or exceptionally.

2. **Exception Handling**  
   - If any of the sub‑tasks fails (i.e., the `CompletableFuture` produced by `taskFactory` completes exceptionally), the entire batch future must complete **exceptionally**.  
   - The exception should contain a **clear aggregated error message** that identifies which input(s) caused the failure(s) and includes the underlying cause(s).  

3. **Configurable Thread‑Pool**  
   - The component must internally create a `ExecutorService` (e.g., a custom `ThreadPoolExecutor` or `ForkJoinPool`).  
   - It should expose a way to configure the pool’s parallelism (e.g., via a constructor parameter or a static builder).  
   - Provide a graceful shutdown mechanism (e.g., `shutdownNow` or `awaitTermination`) that can be invoked when the application no longer needs the processor.  

4. **Javadoc**  
   - Add comprehensive Javadoc to `AsyncProcessor`, its constructor(s), `processBatch`, and any supporting methods.  
   - Document the concurrency strategy, ordering guarantees, error‑propagation policy, and the lifecycle of the internal executor.  

**Additional Requirements – JUnit 5 Test Suite**

Create a separate JUnit 5 test class (or a set of test classes) that thoroughly validates the component:

- **Unit Tests for Successful Batch Processing**  
  - Test with small, medium, and large input lists (e.g., 1, 10, 1000 elements).  
  - Verify that the returned list contains the correct number of results and that each result matches the expected transformation defined by a mock `taskFactory`.  

- **Order Preservation Tests**  
  - Use inputs that have a deterministic transformation (e.g., `input -> input * 2`).  
  - Assert that the order of results in the output list matches the order of the input list.  

- **Stress Tests**  
  - Launch many concurrent `processBatch` calls (e.g., 50‑100 concurrent batches) with overlapping input sizes.  
  - Monitor thread‑pool usage and ensure no resource leaks (e.g., threads are not left daemon or stuck).  
  - Optionally, assert that the total processing time is bounded by the parallelism limit.  

- **Exception Propagation Tests**  
  - Inject a `taskFactory` that deliberately throws exceptions for specific inputs.  
  - Verify that the batch future completes exceptionally and that the aggregated error message clearly identifies the failing input(s) and includes the original cause.  

- **Executor Shutdown Tests**  
  - Instantiate an `AsyncProcessor` and explicitly invoke its shutdown method.  
  - Verify that after shutdown, no background threads are alive (e.g., by checking `Thread.activeCount()` or using a mock executor).  
  - Ensure that attempting to submit new tasks after shutdown fails gracefully (e.g., throws `RejectedExecutionException` or returns a failed future).  

**Project Structure & Dependencies**

- The entire code (including `AsyncProcessor`, any supporting classes such as `Input` and `Result`, and the JUnit 5 test class) should be self‑contained in a single Java source file (or a minimal Maven/Gradle project).  
- The project must be runnable without external dependencies beyond the JDK and JUnit 5 (use the JUnit Platform Runner or Maven/Gradle JUnit 5 support).  
- No external libraries (e.g., Guava, Apache Commons) should be introduced.  

**Output Requirement**

Do **not** provide any implementation code. Your response should be the **prompt text** described above—i.e., a clear, complete specification that can be used to generate the requested Java program and test suite.