<!-- model: nvidia/nemotron-nano-9b-v2:free | category: code | run: 20260723T072749Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->


### Thread-Safe Bounded Blocking Queue in Java

#### **Concurrency Strategy**
The queue uses a `ReentrantLock` to ensure mutual exclusion when modifying the internal state (head, tail, count). Two `Condition` variables (`notFull` and `notEmpty`) are used to allow threads to wait efficiently when the queue is full or empty. The `shutdown` flag is a volatile variable to signal termination. 

- **Put/Take Operations**: Blocking via `Condition.await()` when the queue is full/empty.
- **Offer/Poll Operations**: Non-blocking with immediate return on failure.
- **Shutdown**: Sets a flag to prevent further `put` operations and signals waiting threads to terminate gracefully.

---

###