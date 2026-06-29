<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for the model**

---

You are to write a **self‑contained Java program** that implements a **highly concurrent token‑bucket rate limiter** with the following requirements:

### Core functionality
1. **Variable refill rates per client** – each client (identified by a `String clientId`) can have its own refill rate (tokens per second) and burst capacity (maximum bucket size).
2. **Thread‑safe `acquire(int permits)` method** – callers request a number of permits; the call **blocks** until the requested permits are available in the client’s bucket.
3. **Lock‑free implementation** – the limiter must **not** use `synchronized`, `ReentrantLock`, or any other explicit blocking lock. All synchronization must be achieved with **`java.util.concurrent.atomic.AtomicLong`** and/or **`VarHandle`** (or `sun.misc.Unsafe` if you prefer). Use CAS loops, `compareAndSet`, `getAndAdd`, etc.
4. **Overflow protection** – the bucket token count must never overflow a `long`. The implementation must cap the token count at the configured burst capacity and handle arithmetic safely.
5. **Memory‑visibility guarantees** – clearly document how the chosen atomic primitives provide the necessary happens‑before relationships for correct visibility between threads.

### API design
Provide a public class `TokenBucketRateLimiter` with at least the following members:

```java
public final class TokenBucketRateLimiter {
    /** Constructs a limiter with no clients; clients are added via {@link #addClient(String, double, long)}. */
    public TokenBucketRateLimiter();

    /** Adds or updates a client configuration.
     *  @param clientId unique identifier of the client
     *  @param refillRate tokens added per second (may be fractional)
     *  @param burstCapacity maximum number of tokens the bucket can hold
     */
    public void addClient(String clientId, double refillRate, long burstCapacity);

    /** Removes a client; subsequent calls to {@code acquire} for this client will throw {@code IllegalArgumentException}. */
    public void removeClient(String clientId);

    /** Acquires the requested number of permits for the given client, blocking until they become available.
     *  @throws InterruptedException if the thread is interrupted while waiting
     *  @throws IllegalArgumentException if {@code permits} ≤ 0 or the client does not exist
     */
    public void acquire(String clientId, int permits) throws InterruptedException;
}
```

You may add any private helper classes, methods, or data structures needed (e.g., a per‑client bucket state object). The implementation must be **fully lock‑free**.

### Documentation
- Write **comprehensive Javadoc** for the public class and all public methods.
- Include a detailed **“Concurrency Strategy”** section explaining:
  - How the token count is stored and updated atomically.
  - How the refill logic works without a dedicated scheduler (e.g., lazy refill on each acquire based on elapsed time).
  - How CAS loops provide linearizability and prevent lost updates.
  - How memory‑visibility is guaranteed (volatile/VarHandle semantics).
  - How overflow is avoided (capping at burst capacity, using `Math.min`, careful arithmetic).

### Test suite
Provide a **JUnit 5** test class `TokenBucketRateLimiterTest` that covers:

1. **Functional correctness**
   - Single‑threaded acquisition respecting burst capacity.
   - Correct blocking behaviour when permits are not immediately available.
   - Proper handling of fractional refill rates (e.g., 0.5 tokens/sec).

2. **Multi‑threaded fairness under contention**
   - Spawn several threads competing for the same client’s permits.
   - Verify that no thread starves and that permits are granted roughly in order of request arrival.

3. **Stress test**
   - Launch **200 concurrent threads**.
   - Each thread repeatedly performs random `acquire` calls (1‑5 permits) on a set of clients with varying refill rates and burst capacities.
   - Run the test for at least **30 seconds**.
   - Assert that no thread hangs indefinitely and that total permits granted do not exceed what the refill rates allow (allow a small tolerance for timing variance).

4. **Rate‑limit verification**
   - For a given client with a known refill rate and burst capacity, measure the number of permits granted over a fixed interval (e.g., 5 seconds) under heavy load.
   - Assert that the observed average rate is within **±5 %** of the configured rate.

5. **Edge cases**
   - Acquire with `permits` larger than burst capacity (should block until enough tokens have accumulated).
   - Removing a client while other threads are blocked on it (should cause those threads to receive an exception or be unblocked appropriately).
   - Adding a client with zero or negative refill rate or burst capacity (should throw `IllegalArgumentException`).

All tests must be **self‑contained**, use only standard JDK classes and JUnit 5, and should not rely on external libraries.

### Deliverables
- A single Java source file (or a small set of files) containing the **implementation** and **Javadoc**.
- A separate Java source file containing the **JUnit 5 test class**.
- The code must compile and run on **Java 17** (or newer) without additional dependencies.

**Do not provide the implementation or tests themselves; only generate the prompt text above for a model to produce the required code and tests.**