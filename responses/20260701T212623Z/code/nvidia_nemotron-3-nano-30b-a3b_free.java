<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260701T212623Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent **work‑stealing thread pool** named `StealingExecutor`. The pool should dynamically balance tasks across worker threads using a deque per worker and allow task submission via a `submit(Callable<T>)` method returning a `Future<T>`. Include proper shutdown handling, task cancellation, and thread‑safety guarantees documented in Javadoc. Additionally, provide a comprehensive JUnit 5 test suite that covers:

1. Correct execution order and result retrieval for submitted callables.
2. Proper work‑stealing behavior under heavy load (e.g., many more tasks than workers).
3. Concurrency stress tests with multiple producer threads submitting tasks simultaneously.
4. Shutdown semantics, including awaiting termination and handling of rejected tasks after shutdown.
5. Detection of potential deadlocks or race conditions using `ExecutorService` utilities.

The prompt must require the model to write **the full Java class(es)** and **the full JUnit 5 test class**, all in a single file, with clear documentation of the concurrency strategy. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt for the model**

> Write **one complete Java source file** that contains both of the following parts:
> 
> 1. **Implementation** – a highly concurrent **work‑stealing thread pool** named `StealingExecutor`.  
>    - The class must implement a work‑stealing algorithm using a **deque per worker thread** (e.g., `java.util.Deque`).  
>    - Provide a public method `public <T> Future<T> submit(Callable<T> task)` that returns a `java.util.concurrent.Future<T>` and submits the task to the pool.  
>    - Include a method `public void shutdown()` that initiates graceful shutdown and a method `public boolean awaitTermination(long timeout, TimeUnit unit)` that waits for all submitted tasks to finish.  
>    - Ensure that after shutdown, any further `submit` calls raise an appropriate `RejectedExecutionException` (or a custom unchecked exception of your design).  
>    - Document **all thread‑safety guarantees** in Javadoc comments (e.g., “`submit` may be called concurrently from multiple threads”, “the returned `Future` is safe for concurrent retrieval”, etc.).  
>    - Use only standard Java concurrency utilities (`java.util.concurrent.*`, `java.util.*`). No external libraries are allowed.  
>    - The implementation must be **self‑contained** – no separate source files, no Maven/Gradle project structure, no external resources.  
> 
> 2. **JUnit 5 test suite** – placed **inside the same source file** (i.e., a single `.java` file).  
>    - The test class must be named `StealingExecutorTest`.  
>    - It must contain at least the following test methods (each annotated with `@Test`):  
>      1. **Correctness & result retrieval** – submit several `Callable<Integer>` tasks that return distinct values, await termination, and verify that each future yields the expected result.  
>      2. **Work‑stealing under heavy load** – create many more tasks than the pool’s core pool size (e.g., 10 × workers) and submit them concurrently; verify that all tasks complete and that results are correct.  
>      3. **Concurrency stress** – launch multiple producer threads (e.g., 8) each submitting a large batch of tasks; after shutdown, ensure no tasks are lost and that results are still correct.  
>      4. **Shutdown semantics** – submit tasks, call `shutdown()`, then submit another task and assert that it is rejected; also verify that `awaitTermination` returns `true` only after all previously submitted tasks finish.  
>      5. **Deadlock / race detection** – use `ExecutorService` utilities (e.g., `ForkJoinPool.commonPool().addTask`, `Thread.sleep`, or `java.util.concurrent.atomic.AtomicBoolean`) to create a scenario that would deadlock if the stealing logic were flawed; the test should complete without hanging and should assert that no deadlock occurs.  
>    - All tests must be **self‑contained** and not rely on external test resources.  
>    - Use JUnit 5’s `org.junit.jupiter.api.*` static imports for assertions (`assertEquals`, `assertTrue`, etc.).  
>    - Document the **concurrency strategy** at the top of the file with a multi‑line comment block that explains:  
>      * How work stealing is performed (producer‑consumer semantics, deque ordering, stealing order).  
>      * How tasks are distributed and stolen.  
>      * How shutdown and cancellation are handled.  
>      * Any assumptions made about tasks (e.g., they must be non‑blocking, short‑lived, etc.).  
> 
> **Formatting & Submission Requirements**  
> - The entire solution must be in a **single Java file** named `StealingExecutor.java`.  
> - The file must compile and run with a JDK 17+ environment (no preview features).  
> - Include a `package` statement if you wish, but keep everything in that same file.  
> - Do **not** use any third‑party libraries beyond the JDK and JUnit 5 (which will be provided at test time).  
> - Ensure the code is **thread‑safe** and that all mutable shared state is properly protected (e.g., with `AtomicReference`, `ConcurrentLinkedDeque`, `ReentrantLock`, etc.).  
> - Provide Javadoc for the public API (`StealingExecutor`, `submit`, `shutdown`, `awaitTermination`) that clearly describes the concurrency guarantees.  
> 
> **Deliverable**  
> Output only the full source code of `StealingExecutor.java` that meets **all** of the above specifications. Do **not** include any additional explanation, build scripts, or separate test files. The code you produce should be ready to be compiled and executed in a standard Java environment with JUnit 5 on the classpath.  

--- 

*The model should output exactly one Java source file containing the `StealingExecutor` implementation and the `StealingExecutorTest` JUnit 5 test class, satisfying every bullet point listed above.*