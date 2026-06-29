<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->

Here is the prompt text for the model to generate the complete solution:

---

**Prompt: Implement a Highly Concurrent Token-Bucket Rate Limiter in Java**

Your task is to write a complete, self-contained Java program that implements a **highly concurrent token-bucket rate limiter** with the following specifications:

### Core Requirements

1. **Class Structure**:
   - Create a public class named `TokenBucketRateLimiter` in the package `ratelimiter`.
   - The class must be fully encapsulated; no public fields except for a builder or factory method if needed.

2. **Configuration**:
   - Support variable **refill rate** (tokens per second, as a `double`).
   - Support configurable **burst capacity** (maximum number of tokens, as an `int`).
   - Support identification of clients (via a `String` client ID) to allow per-client rate limiting.
   - Provide a fluent API or builder pattern for easy configuration.

3. **Core Method**:
   - Implement a thread-safe method:
     ```java
     public boolean acquire(int permits) throws InterruptedException
     ```
   - This method must:
     - Block until the requested number of permits is available.
     - Return `true` if permits were successfully acquired, `false` if interrupted.
     - Deduct the permits from the bucket upon successful acquisition.
     - Refill tokens based on the elapsed time since the last refilling operation, according to the configured rate.

4. **Concurrency Strategy**:
   - Use a **lock-free algorithm** leveraging `java.util.concurrent.atomic.AtomicLong` and `java.lang.invoke.VarHandle`.
   - **Do NOT use explicit `synchronized` blocks or `ReentrantLock`.**
   - Employ compare-and-swap (CAS) operations to ensure thread safety.
   - Handle the "wait/notify" mechanism safely without traditional intrinsic locks (hint: consider `LockSupport.park()` and atomic state tracking).

5. **Memory Visibility & Correctness**:
   - Ensure all shared state is properly published and visible across threads.
   - Prevent integer/long overflow when calculating token refills.
   - Document all assumptions and design choices in Javadoc.

6. **Documentation**:
   - Provide comprehensive Javadoc for the class and all public methods.
   - Clearly explain the concurrency strategy, memory model guarantees, and how overflow is handled.

### Test Suite Requirements (JUnit 5)

Write a comprehensive JUnit 5 test class named `TokenBucketRateLimiterTest` in the package `ratelimiter` that includes:

1. **Functional Tests**:
   - Test basic acquire functionality with sufficient tokens.
   - Test blocking behavior when tokens are exhausted.
   - Test refill timing accuracy over short intervals.
   - Test burst capacity limits.

2. **Multi-threaded Fairness Test**:
   - Create a scenario with multiple threads competing for tokens.
   - Verify that threads are not indefinitely starved and that the distribution of acquired permits is reasonably fair.

3. **Stress Test**:
   - Launch **200 concurrent threads**.
   - Each thread should perform **random acquire/release patterns** (e.g., acquire 1–5 permits, then release them back after a short delay).
   - Run this test for **at least 30 seconds**.
   - Use a shared `CountDownLatch` or similar mechanism to coordinate thread start and measure total throughput.
   - Assert that no thread is blocked indefinitely and that the system remains stable.

4. **Refill Rate Verification Under Load**:
   - Configure a known refill rate (e.g., 100 tokens/sec).
   - Start a large number of threads trying to acquire tokens continuously.
   - Measure the actual rate at which tokens are consumed/refilled over a period (e.g., 10 seconds).
   - Assert that the measured rate does not exceed the configured rate by more than a small tolerance (e.g., 5%).

### Submission Format

Provide the complete source code as two files:
1. `src/main/java/ratelimiter/TokenBucketRateLimiter.java`
2. `src/test/java/ratelimiter/TokenBucketRateLimiterTest.java`

Ensure the code compiles and passes all tests. You may assume Java 17+ for `VarHandle` usage.

---
