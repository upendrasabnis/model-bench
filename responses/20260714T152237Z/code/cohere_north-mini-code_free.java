<!-- model: cohere/north-mini-code:free | category: code | run: 20260714T152237Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Phase 1. READING: read the problem and reword it in clearer terms**

**1.1** The problem asks to write a thread-safe, generic bounded blocking queue in Java, **without using java.util.concurrent.BlockingQueue**. This means we must implement the interface ourselves from scratch, using synchronization primitives like `synchronized`, `wait`, `notify`, or `ReentrantLock` with condition variables. The queue must support:

* **Blocking put/take**: `put(E e)` and `take()` that block when the queue is full (for put) or empty (for take) until space/items are available.
* **Timed offer/poll**: `offer(E e, long timeout, TimeUnit unit)` and `poll(long timeout, TimeUnit unit)` that return `null` or `false` after the specified timeout if operation cannot be performed.
* **Clean shutdown**: The queue should be able to stop accepting new elements and wake up any waiting threads when a shutdown signal is given, perhaps throwing an exception or returning a special value.

Additionally, we need to **document the concurrency strategy** (how we ensure thread safety, avoid race conditions, etc.) and **write comprehensive JUnit 5 tests**, including a **multi-producer/multi-consumer stress test** that verifies no lost or duplicated elements.

**1.2** The key terms: thread-safe, generic, bounded blocking queue, blocking put/take, timed offer/poll, clean shutdown, concurrency strategy, JUnit 5, stress test, no lost or duplicated elements.

**1.3** In clear terms: Implement a thread-safe queue that can hold a fixed number of elements. Producers can add elements; if the queue is full, they block (or timeout) until space is available. Consumers can remove elements; if the queue is empty, they block (or timeout) until an element is available. The queue can be shut down, which should cause subsequent put operations to fail (or raise an exception) and wake up any waiting threads. The implementation must be generic (work for any type). Also provide thorough unit tests to verify correctness and stress the implementation with multiple threads.

**1.4** Steps to reproduce the problem:

1. Create a generic class `BoundedBlockingQueue<E>` with fields: a fixed-size array (or linked list) to hold elements, capacity, head/tail indices for circular buffer or linked list, and synchronization objects (lock, condition variables).
2. Implement `put(E e)` and `take()` using `synchronized` blocks and `wait`/`notifyAll` or `ReentrantLock` and `Condition`.
3. Implement `offer(E e, long timeout, TimeUnit unit)` and `poll(long timeout, TimeUnit unit)` using the same synchronization but with timeout logic.
4. Implement a `shutdown()` method that sets a flag and notifies all waiting threads.
5. Document the concurrency strategy: use a single lock to protect all shared state, use condition variables for blocking on full/empty, handle spurious wakeups, ensure memory visibility via `volatile` or synchronization.
6. Write JUnit 5 tests covering:
   * Basic put/take operations.
   * Blocking behavior (thread waiting).
   * Timed operations with timeout.
   * Shutdown behavior.
   * Multi-producer/multi-consumer stress test (producer threads continuously put, consumer threads continuously take, verify that every element produced is consumed exactly once).
   * Edge cases: capacity zero, capacity one, capacity large, null values (if allowed), etc.
7. Ensure tests are thread-safe and use appropriate synchronization (e.g., `CountDownLatch`, `Semaphore`, `AtomicInteger`).
8. Verify that the implementation does not use any classes from `java.util.concurrent.BlockingQueue` or its subinterfaces.

**1.5** Best practices to take into account:

* **Thread safety**: Use a single lock (or multiple condition variables) to protect all shared mutable state.
* **Avoid busy waiting**: Use `wait`/`notifyAll` or `Condition.await`/`signalAll`.
* **Spurious wakeups**: Handle them by looping on the condition.
* **Memory consistency**: Use `synchronized` or `volatile` where needed.
* **Generic typing**: Use `<E>` and ensure type safety.
* **Error handling**: Decide on behavior for `put` of null (allow or disallow) and how to propagate shutdown.
* **Timeout handling**: Convert timeouts to milliseconds and use `System.nanoTime()` for precise timing.
* **Clean shutdown**: Provide a way to stop the queue and wake up waiting threads (maybe via `shutdown()` and `isShutdown()`).
* **Performance**: Consider using a circular array for efficiency.
* **Testing**: Use JUnit 5's `@Test`, `@BeforeEach`, `@AfterEach`, `@ParameterizedTest`, `@ValueSource`. Use `@Timeout` for tests that may hang. Use `@DisplayName`. Use `@Disabled` if needed.
* **Stress test**: Use multiple threads, count produced/consumed elements with atomic counters, and verify that the counts match after all threads finish.

Now, let's move to Phase 2: RUNNING. Since there's no existing code, we'll skip installation and start implementing.

**Phase 2. RUNNING: install and run the tests on the repository**

We have an empty repository. We'll create the project structure and write the implementation and tests.

First, let's decide on the project layout. We'll create a Maven or Gradle project. Since the problem doesn't specify, I'll choose Maven for simplicity. We'll create a single module with source directories `src/main/java` and `src/test/java`.

Let's start by creating the necessary directories and files.