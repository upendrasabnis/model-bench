<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260701T212623Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent **work‑stealing thread pool** named `StealingExecutor`. The pool should dynamically balance tasks across worker threads using a deque per worker and allow task submission via a `submit(Callable<T>)` method returning a `Future<T>`. Include proper shutdown handling, task cancellation, and thread‑safety guarantees documented in Javadoc. Additionally, provide a comprehensive JUnit 5 test suite that covers:

1. Correct execution order and result retrieval for submitted callables.
2. Proper work‑stealing behavior under heavy load (e.g., many more tasks than workers).
3. Concurrency stress tests with multiple producer threads submitting tasks simultaneously.
4. Shutdown semantics, including awaiting termination and handling of rejected tasks after shutdown.
5. Detection of potential deadlocks or race conditions using `ExecutorService` utilities.

The prompt must require the model to write **the full Java class(es)** and **the full JUnit 5 test class**, all in a single file, with clear documentation of the concurrency strategy. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt for the Language Model**

Write a single Java source file that contains **both** of the following:

1. **`StealingExecutor`** – a highly concurrent work‑stealing thread pool that:
   - Implements `java.util.concurrent.ExecutorService` (you may extend `AbstractExecutorService` for convenience).
   - Provides a per‑worker `ArrayDeque` (or similar lock‑free deque) for storing tasks.
   - Implements the classic work‑stealing algorithm: each worker primarily pops tasks from the **head** of its own deque; when its deque is empty, it attempts to steal a task from the **tail** of a randomly chosen victim’s deque.
   - Exposes a `submit(Callable<T>)` method that returns a `java.util.concurrent.Future<T>`.
   - Supports graceful shutdown (`shutdown()`, `shutdownNow()`), awaits termination, and rejects new tasks appropriately after shutdown has begun.
   - Handles task cancellation via the returned `Future` (i.e., interrupting the worker thread if the task is still running).
   - Is fully thread‑safe: all shared state is accessed with appropriate synchronization or lock‑free primitives, and the Javadoc must explicitly state the thread‑safety guarantees.
   - Includes comprehensive Javadoc comments for the class, each public method, and any important internal fields, describing the concurrency strategy, invariants, and any guarantees (e.g., lock‑freedom of the deque, progress guarantees, memory‑visibility effects).

2. **`StealingExecutorTest`** – a JUnit 5 test class that thoroughly validates the implementation, covering at least the following scenarios:
   - **Correct execution order & result retrieval** – submit a small set of `Callable`s with known return values, verify that each `Future.get()` yields the expected result and that tasks complete.
   - **Work‑stealing behavior under load** – submit a large number (e.g., 10× the number of workers) of short‑lived tasks that increment a shared atomic counter; assert that the final count matches the number of submitted tasks and that no worker remains idle while work exists in another worker’s deque (you may expose internal statistics for testing or infer from timing).
   - **Multiple producer stress test** – launch several producer threads that concurrently submit tasks to the same `StealingExecutor`; ensure no tasks are lost, no exceptions are thrown, and all futures complete successfully.
   - **Shutdown semantics** – test:
     - `shutdown()` followed by `awaitTermination` returns true after all previously submitted tasks finish.
     - Submitting new tasks after `shutdown()` (or `shutdownNow()`) results in rejection (either via `RejectedExecutionException` or via the executor’s rejected‑execution handler).
     - `shutdownNow()` attempts to interrupt running tasks and returns a list of tasks that were awaiting execution.
   - **ExecutorService utilities & deadlock/race detection** – use utilities such as `Executors.newFixedThreadPool` as a baseline for comparison, or employ `CountDownLatch`/`Phaser` to detect potential deadlocks; also verify that invoking `Future.cancel(true)` on a running task results in interruption and that the future reports cancellation correctly.

**Additional Requirements**

- The entire solution (both `StealingExecutor` and `StealingExecutorTest`) must reside in **one** Java file (e.g., `StealingExecutor.java`).  
- Use only the Java SE API (no external libraries). JUnit 5 annotations (`@Test`, `@BeforeEach`, `@AfterEach`, `@Timeout`, etc.) must be used for the test class.  
- Ensure the code compiles with Java 17 or later.  
- Provide clear, concise Javadoc that explains:
  - The overall work‑stealing algorithm employed.
  - How task submission, stealing, and shutdown interact.
  - Which operations are wait‑free, lock‑free, or blocking.
  - Any assumptions about the underlying hardware or JVM (e.g., visibility guarantees of `volatile` or `java.util.concurrent.atomic`).  
- The test class should be deterministic (no reliance on arbitrary timing) where possible, and any use of `Thread.sleep` should be justified and bounded.  
- Do **not** include a `main` method or any extraneous code unrelated to the requested components.

Your output should be **only** the prompt (as above); do **not** include the actual implementation or test code.