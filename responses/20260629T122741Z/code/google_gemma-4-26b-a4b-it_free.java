<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
Here is a comprehensive prompt designed to elicit a high-quality, professional-grade implementation from an LLM.

***

**Prompt for LLM:**

"Act as a Senior Staff Engineer specializing in low-latency concurrency primitives. Your task is to implement a production-grade, high-performance Token Bucket rate limiter in Java.

### Core Requirements

1.  **Algorithm & API**:
    *   Implement a Token Bucket algorithm where each client can have a unique `refillRate` (tokens per second) and `burstCapacity` (maximum tokens).
    ing
    *   Implement a method: `public void acquire(int permits) throws InterruptedException`.
    *   The `acquire` method **must be blocking**. If insufficient tokens are available, the calling thread must wait until the required tokens are replenished.

2.  *Concurrency Strategy (Strict Constraints)*:
    *   **No `synchronized` blocks** and no explicit `java.util.concurrent.locks.ReentrantLock`.
    *   The implementation must be **lock-free** (or at least non-blocking in its state updates) using a Compare-And-Swap (CAS) loop.
    *   Use `java.util.concurrent.atomic.AtomicLong` to track the state.
    *   Use `java.lang.invoke.VarHandle` to manage the state transitions to ensure atomic updates of multiple related values (e.g., timestamp and current token count) if necessary, or to ensure memory visibility.
    *   The state must be packed into a single `long` (using bit-masking) if possible, or managed via a `VarHandle` on a volatile field to ensure that the 'last refill timestamp' and 'available tokens' are updated atomically to prevent race conditions where tokens are 'lost' or 'double-counted' during concurrent refills.

3._Documentation Requirements_:
    *   Provide comprehensive Javadoc.
    *   **Concurrency Strategy**: Explain how the CAS loop handles contention.
    *   **Memory Visibility**: Explicitly explain how `VarHandle` or `AtomicLong` provides the necessary happens-before relationships.
    *   **Overflow/Underflow**: Detail how you prevent arithmetic overflow when calculating elapsed time or token counts.

### Testing Requirements (JUnit 5)

Provide a complete, self-contained JUnit 5 test suite including:

1.  **Functional Correctness**: Verify that a single thread can acquire tokens and that the bucket correctly depletes and refills over time.
2.  *Multi-threaded Fairness/Contention**: A test where multiple threads compete for a limited pool of tokens to ensure no thread is permanently starved and the system remains stable under high contention.
3.  **Stress Test**: 
    *   Spawn **200 concurrent threads**.
    *   Each thread performs a mix of `acquire(n)` calls with random `n` and random sleep intervals.
    *   The test must run for a minimum of **30 seconds**.
    *   Use a `CountDownLatch` to ensure all threads start simultaneously.
4.  **Rate Verification**: A high-load test that measures the actual throughput (tokens per second) against the configured `refillRate`. The test should assert that the actual rate stays within a reasonable margin (e.g., ±5%) of the configured rate.

### Technical Constraints
*   The code must be a single, self-contained Java file (or a single block of code containing the class and the test class).
*   Use modern Java idioms (Java 11+).
*   Ensure the solution is 'Zero-Allocation' in the hot path (the `acquire` method should not create new objects to avoid GC pressure)."