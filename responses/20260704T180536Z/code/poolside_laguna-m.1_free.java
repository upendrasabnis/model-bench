<!-- model: poolside/laguna-m.1:free | category: code | run: 20260704T180536Z -->
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

Please write a single, self-contained Java program that implements a complex, non-trivial multi-threaded component using a CompletableFuture fan-out/fan-in pipeline. The program must define a public class named `AsyncProcessor` that provides:

1. A method `public CompletableFuture<List<Result>> processBatch(List<Input> inputs)` which concurrently processes each `Input` by invoking a supplied `Function<Input, CompletableFuture<Result>>` task, merges the results preserving order, and returns a `CompletableFuture` that completes when all sub-tasks finish.
2. Proper handling of exceptions: if any sub-task fails, the whole batch should complete exceptionally with a clear aggregated error message.
3. A configurable thread-pool executor (e.g., `ForkJoinPool` or custom `ExecutorService`) that limits the maximum parallelism and can be shut down gracefully.
4. Comprehensive Javadoc explaining the concurrency strategy, ordering guarantees, and error-propagation policy.

In addition, provide a full suite of JUnit 5 tests that includes:
- Unit tests for successful batch processing with varied input sizes.
- Tests verifying order preservation of results.
- Stress tests that launch many concurrent `processBatch` calls to expose race conditions or resource leaks.
- Tests that deliberately cause some sub-tasks to throw exceptions and assert that the returned `CompletableFuture` completes exceptionally with the expected aggregated message.
- Tests confirming proper shutdown of the internal executor without lingering threads.

The entire code (including `AsyncProcessor`, any supporting classes, and the JUnit 5 test class) must be runnable as a single file (or a minimal Maven/Gradle project) without external dependencies beyond the JDK and JUnit 5. Do NOT provide a solution; only supply the prompt text.
