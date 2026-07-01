<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260701T212623Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent **work‑stealing thread pool** named `StealingExecutor`. The pool should dynamically balance tasks across worker threads using a deque per worker and allow task submission via a `submit(Callable<T>)` method returning a `Future<T>`. Include proper shutdown handling, task cancellation, and thread‑safety guarantees documented in Javadoc. Additionally, provide a comprehensive JUnit 5 test suite that covers:

1. Correct execution order and result retrieval for submitted callables.
2. Proper work‑stealing behavior under heavy load (e.g., many more tasks than workers).
3. Concurrency stress tests with multiple producer threads submitting tasks simultaneously.
4. Shutdown semantics, including awaiting termination and handling of rejected tasks after shutdown.
5. Detection of potential deadlocks or race conditions using `ExecutorService` utilities.

The prompt must require the model to write **the full Java class(es)** and **the full JUnit 5 test class**, all in a single file, with clear documentation of the concurrency strategy. Do not provide the solution yourself.
<!-- RESPONSE -->
**Prompt for a Code‑Generating Model**

> Write a **self‑contained Java program** that implements a highly concurrent **work‑stealing thread pool** named `StealingExecutor`.  
> 
> The pool should:
> 
> * Use a deque per worker thread to dynamically balance tasks via work‑stealing.  
> * Provide a method `public <T> Future<T> submit(Callable<T> task)` that returns a `Future` representing the task’s result.  
> * Handle proper shutdown, including `shutdown()`, `shutdownNow()`, and `awaitTermination()`.  
> * Support task cancellation and ensure that cancelled tasks do not execute.  
> * Offer strong thread‑safety guarantees, documented in Javadoc comments.  
> * Include comprehensive Javadoc for the public API and for key internal classes.  
> * Use only standard Java SE APIs (no third‑party libraries).  
> * Be written in a single `.java` file containing all necessary classes (the executor and any helper classes).  
> 
> Additionally, provide a **comprehensive JUnit 5 test class** that covers the following scenarios:
> 
> 1. **Correct execution order and result retrieval** for a set of submitted callables.  
> 2. **Work‑stealing behavior** under heavy load (e.g., submit many more tasks than there are worker threads and verify that tasks are distributed fairly).  
> 3. **Concurrency stress tests** with multiple producer threads submitting tasks simultaneously, ensuring no lost or duplicated executions.  
> 4. **Shutdown semantics**: test that after `shutdown()` no new tasks are accepted, that `awaitTermination()` works, and that tasks submitted after shutdown are rejected appropriately.  
> 5. **Detection of potential deadlocks or race conditions** by using `ExecutorService` utilities such as `invokeAll`, `invokeAny`, and by asserting that the pool remains responsive under contention.  
> 
> The test class should be in the same file, following the main class, and should use JUnit 5 annotations (`@Test`, `@BeforeEach`, etc.).  
> 
> Ensure that the implementation and tests are fully self‑contained and can be compiled and run with a standard JDK and JUnit 5 on the classpath.  
> 
> **Do not provide the solution yourself.** The goal is to generate the complete Java source file (executor + test class) from this prompt.