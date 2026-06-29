<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for Generating the Solution**

```
Write a self‑contained Java program that implements a highly concurrent, lock‑free token‑bucket rate limiter meeting the specifications below. Do **not** include any solution code — only produce a detailed prompt that a model can use to generate the required Java source file and its accompanying JUnit 5 test suite.

---

### 1. Core Implementation (TokenBucketRateLimiter.java)

Create a single public class named `TokenBucketRateLimiter` that provides the following:

1. **Constructor**
   - `TokenBucketRateLimiter(long refillTokensPerSecond, int burstCapacity, int initialTokens)`
   - `refillTokensPerSecond` – the average rate at which tokens are added per client (may differ per client).
   - `burstCapacity` – the maximum number of tokens that can be accumulated for burst handling.
   - `initialTokens` – the number of tokens present at creation (must not exceed `burstCapacity`).

2. **Per‑Client Configuration**
   - Provide a method `void configureClient(String clientId, long refillRate, int maxTokens)` that allows a client to change its refill rate and burst capacity at runtime.
   - Each client must be associated with its own token bucket state.

3. **Thread‑Safe Acquire API**
   - `int acquire(int permits)`  
     - Blocks the calling thread until at least `permits` tokens are available.  
     - Returns the number of tokens actually consumed (may be less than `permits` only if the bucket is closed, which never happens).  
     - Must be **lock‑free**: use only `java.util.concurrent.atomic.AtomicLong` (or `VarHandle`) and **no** `synchronized` blocks.  
     - Use `VarHandle` (or `sun.misc.Unsafe` if you prefer) to read/write the token count atomically and to establish the required memory‑visibility guarantees.  
     - The algorithm must prevent overflow: if adding tokens would exceed `burstCapacity`, the surplus is discarded.

4. **Release API (optional but required for the tests)**
   - `void release(int permits)` – returns `permits` tokens to the bucket, never exceeding `burstCapacity`.

5. **Concurrency Strategy (Javadoc)**
   - Write a comprehensive Javadoc block for the class and its public methods that explains:
     * How the lock‑free algorithm works (e.g., CAS loops on the token count, use of `VarHandle` for atomic updates).
     * Memory‑visibility guarantees (e.g., volatile semantics of `AtomicLong`/`VarHandle`, happens‑before relationships).
     * How overflow is detected and prevented.
     * Why the algorithm is fair under contention (e.g., FIFO ordering of threads via the CAS loop).

6. **Internal State**
   - Store the token count for each client in an `AtomicLong` (or `VarHandle`‑backed array) indexed by a client identifier.
   - Maintain a global refill clock or use `System.nanoTime()` to compute token additions on each `acquire` call based on elapsed time and the configured refill rate.
   - Ensure that token addition calculations are performed atomically and that the result never exceeds `burstCapacity`.

7. **Public API Summary**
   - `configureClient(String clientId, long refillRate, int maxTokens)`
   - `int acquire(int permits)`
   - `void release(int permits)`
   - (Optional) `int getAvailableTokens()` for debugging only; not required for the public contract.

---

### 2. JUnit 5 Test Suite (TokenBucketRateLimiterTest.java)

Create a test class named `TokenBucketRateLimiterTest` that contains **all** of the following test methods and behaviors:

1. **Functional Correctness**
   - Test that `acquire` blocks until the requested permits are available.
   - Verify that `release` returns tokens to the bucket without exceeding `burstCapacity`.
   - Confirm that tokens are correctly refilled according to the configured rate after waiting a known duration.

2. **Multi‑Threaded Fairness Under Contention**
   - Spawn **N** threads (e.g., 10) that repeatedly call `acquire(1)` and `release(1)` in a tight loop.
   - Ensure that after a sufficiently long run, each thread obtains permits in a roughly FIFO order (no starvation).
   - Use `CountDownLatch` to start all threads simultaneously and `Thread.join()` to wait for completion.

3. **Stress Test (30‑Second Random Pattern)**
   - Create **200** concurrent threads.
   - Each thread performs a random sequence of `acquire` and `release` calls for **at least 30 seconds**.
   - The random pattern must include bursts of many permits followed by releases, simulating realistic load.
   - Collect statistics:
     * Total permits acquired.
     * Total permits released.
     * Final token count (must be ≤ `burstCapacity`).
   - Assert that no thread observes a negative token count or exceeds burst capacity.

4. **Refill‑Rate Verification Under Load**
   - Configure a client with a known refill rate (e.g., 100 tokens/second) and a burst capacity (e.g., 1000).
   - Run the stress test for a fixed interval (e.g., 10 seconds).
   - Compute the **average** tokens added per second observed by the limiter.
   - Assert that the observed average is within a small tolerance (e.g., ±5 %) of the configured refill rate, even under the concurrent load.

5. **Overflow Prevention**
   - Initialize a bucket with `burstCapacity = 5` and `initialTokens = 5`.
   - Attempt to add tokens far beyond the capacity (e.g., via a rapid series of `acquire` calls that would trigger refill logic).
   - Verify that the token count never exceeds `burstCapacity` after each operation.

6. **Edge Cases**
   - Acquire with `permits = 0` should return immediately without modifying state.
   - Release with `permits = 0` should be a no‑op.
   - configureClient should update the refill rate and burst capacity atomically and be visible to all threads.

**General Requirements for the Test Suite**
- Use JUnit 5 (`org.junit.jupiter.api.*`).
- Annotate test methods with appropriate JUnit annotations (`@Test`, `@RepeatedTest`, `@Timeout` where relevant).
- Use `java.util.concurrent.*` utilities (e.g., `ExecutorService`, `CountDownLatch`, `AtomicInteger`) for orchestrating concurrency.
- Include assertions from `org.junit.jupiter.api.Assertions`.
- Ensure that the test suite completes within a reasonable time (e.g., overall timeout of 2 minutes) but still exercises the 30‑second stress scenario.

---

### 3. General Formatting & Documentation

- The source file must compile with Java 17 (or later) without any external dependencies beyond the JDK and JUnit 5.
- All public classes and methods must be documented with Javadoc that includes `@param`, `@return`, and `@throws` tags where applicable.
- The lock‑free algorithm must **not** use any `synchronized` keyword or explicit locks (`ReentrantLock`, etc.).
- Use only `java.util.concurrent.atomic.AtomicLong`, `VarHandle`, or `sun.misc.Unsafe` for atomic state updates.
- Ensure that the code is **self‑contained** (i.e., no reliance on external configuration files or Spring/etc.).

---

### 4. Deliverables (What the Model Should Produce)

1. **`TokenBucketRateLimiter.java`** – a single Java source file implementing the lock‑free token‑bucket rate limiter as described above, complete with Javadoc.
2. **`TokenBucketRateLimiterTest.java`** – a JUnit 5 test class containing all the test methods and scenarios listed in section 2, with proper imports and assertions.

Do **not** output any actual implementation code in this prompt; only provide the detailed instructions that a model can follow to generate the required Java files.
```