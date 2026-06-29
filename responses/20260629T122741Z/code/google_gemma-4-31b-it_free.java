<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
Here is the detailed prompt you can use to generate this specific implementation.

***

**Prompt:**

Implement a high-performance, thread-safe `TokenBucketRateLimiter` in Java that manages rate limiting for multiple clients with variable refill rates and burst capacities. The implementation must strictly adhere to the following technical requirements:

### 1. Core Functional Requirements
*   **Variable Configuration:** Each client must be associated with a specific `refillRate` (tokens per second) and a `burstCapacity` (maximum tokens the bucket can hold).
*   **Blocking Acquire:** Implement a method `public void acquire(String clientId, int permits) throws InterruptedException` that blocks the calling thread until the requested number of permits are available.
*   **Dynamic Refill:** Tokens should be calculated lazily based on the time elapsed since the last acquisition (time-based refill) rather than using a background timer thread.

### 2. Concurrency & Memory Model Constraints
*   **Lock-Free Algorithm:** The core token calculation and consumption must be lock-free. You **must not** use `synchronized` blocks or `java.util.concurrent.locks.ReentrantLock` for the token accounting logic.
*   **Atomic Primitives:** Use `java.util.concurrent.atomic.AtomicLong` or `java.lang.invoke.VarHandle` to manage the state (current token count and last refill timestamp) to ensure atomicity and memory visibility across threads.
*   **Wait/Notify Mechanism:** Since `acquire` must block, use a `java.util.concurrent.Semaphore` or a `LockSupport.park/unpark` mechanism to handle the waiting state without busy-waiting (spinning), while keeping the token calculation itself lock-free.
*   **Overflow Prevention:** Implement safeguards to prevent `long` overflow when calculating accumulated tokens over long periods of inactivity.

### 3. Documentation Requirements
Include comprehensive Javadoc that explicitly explains:
*   **The Concurrency Strategy:** How the Compare-And-Swap (CAS) loop is used to update token counts.
*   **Memory Visibility:** How the implementation guarantees that updates made by one thread are visible to others.
*   **Complexity:** The time and space complexity of the `acquire` operation.

### 4. Testing Requirements (JUnit 5)
Provide a comprehensive test suite covering the following scenarios:
*   **Functional Correctness:** Verify that a client cannot exceed the burst capacity and that tokens refill at the exact configured rate.
*   **Multi-threaded Contention:** Test multiple threads competing for the same client's bucket to ensure no tokens are "lost" or "double-counted."
*   **Stress Test:** A high-load test using 200 concurrent threads performing random `acquire` patterns for at least 30 seconds. Use a `CountDownLatch` to start all threads simultaneously.
*   **Rate Verification:** A test that measures the actual throughput over a 5-second window to verify that the observed rate deviates from the configured `refillRate` by no more than a small margin (e.g., < 1%).

### 5. Code Quality
*   The code must be self-contained in a single file or a small set of classes.
*   Ensure the use of `System.nanoTime()` for high-precision timing.
*   Follow standard Java naming conventions and ensure the code is production-ready.