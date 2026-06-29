<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for Java Program and Test Suite:**

---

**Task:**  
Implement a highly concurrent token-bucket rate limiter in Java using a lock-free algorithm with `AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`). The implementation must include:

1. **Core Components:**
   - A `ClientLimiter` class with configurable:
     - **Variable refill rate** (tokens/second, per-client).
     - **Burst capacity** (max tokens in reserve).
     - **Token bucket capacity** (max total tokens).
   - A thread-safe `acquire(int permits)` method that blocks until permits are available, using atomic operations and `VarHandle` for low-level memory access.
   - **Overflow prevention**: Tokens exceeding `burstCapacity` are discarded during refills.

2. **Concurrency Strategy:**
   - **Lock-free design**: Use `AtomicLong` for token counters and `VarHandle` to manage atomic increments/decrements with memory visibility guarantees (e.g., `volatile` semantics via `UNSAFE.compareAndSwapLong`).
   - **Refill Mechanism**: Compute refill intervals using `System.nanoTime()` and adjust token counters atomically. Handle drift by dynamically recalculating refill rates.
   - **Block on acquire**: Use `Condition` variables or atomic checks with busy-waiting (optimized for contention) to block threads until permits are available.

3. **Javadoc Requirements:**
   - Detail the **lock-free algorithm**, including how `VarHandle` ensures atomicity and visibility.
   - Explain **memory visibility guarantees** (e.g., `volatile` semantics for token counters).
   - Describe **overflow handling** (e.g., clamping tokens to `burstCapacity` during refills).

4. **JUnit 5 Test Suite:**
   - **Functional Tests**:
     - Verify correct token refill rates under varying loads.
     - Test burst capacity limits and overflow behavior.
   - **Multi-threaded Tests**:
     - Simulate 200 concurrent threads performing random `acquire`/`release` patterns for 30 seconds.
     - Validate fairness (no starvation) and thread safety under high contention.
   - **Stress Tests**:
     - Confirm the limiter adheres to configured refill rates even under extreme concurrency.
     - Test edge cases (e.g., burst capacity overflow, rapid token depletion).

5. **Constraints:**
   - No `synchronized` blocks; use only `AtomicLong`, `VarHandle`, and `UNSAFE`.
   - Ensure thread safety without traditional locks (e.g., `ReentrantLock`).

---

**Example Javadoc Snippet (for `ClientLimiter`):**

```java
/**
 * A highly concurrent token-bucket rate limiter supporting per-client variable refill rates.
 * Uses lock-free atomic operations with VarHandle for low-level memory access.
 * <p>
 * The limiter maintains a token bucket with a configurable burst capacity (max reserve) and
 * total capacity. Tokens are refilled at a variable rate per client, adjusted dynamically
 * based on elapsed time since the last refill. Refills are performed atomically using
 * VarHandle.compareAndSwapLong to ensure thread safety without locks.
 * </p>
 * <p>
 * <b>Concurrency Strategy</b>: The implementation uses atomic counters and VarHandle for
 * lock-free token management. Refill operations compute expected token increments based on
 * elapsed time and apply them atomically. Acquire operations use a combination of atomic
 * checks and Condition variables to block threads until permits are available, avoiding
 * busy-waiting in low-contention scenarios.
 * </p>
 * <p>
 * <b>Memory Visibility</b>: All shared variables (e.g., token counters) are declared
 * {@code volatile} to ensure visibility across threads. VarHandle operations enforce
 * {@code volatile} read/write semantics, preventing reordering and ensuring up-to-date
 * values are observed.
 * </p>
 * <p>
 * <b>Overflow Handling</b>: During refills, tokens exceeding the burst capacity are clamped
 * to the maximum reserve. This ensures the limiter never exceeds its configured burst limit,
 * even under high load or concurrent refills.
 * </p>
 */
public class ClientLimiter {
    // Implementation details...
}
```

---

**Example Test Case (JUnit 5):**

```java
@Test
@Timeout(30)
void testStressUnderLoad() {
    ClientLimiter limiter = new ClientLimiter(100, 50, 1000); // 100 tokens/sec, burst 50, capacity 1000
    ExecutorService executor = Executors.newFixedThreadPool(200);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger totalAcquired = new AtomicInteger(0);

    executor.invokeAll(
        Collections.nTransform(
            IntStream.range(0, 200).boxed().collect(Collectors.toList()),
            i -> CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    Random rand = new Random();
                    while (true) {
                        int permits = rand.nextInt(5) + 1;
                        if (limiter.acquire(permits)) {
                            totalAcquired.incrementAndGet(permits);
                        }
                        Thread.sleep(rand.nextInt(10));
                    }
                } catch (Exception e) {
                    // Handle exceptions
                }
            })
        )
    );

    start.countDown();
    Thread.sleep(30_000); // Run for 30 seconds
    executor.shutdownNow();

    // Verify total acquired tokens align with expected refill rate
    assertEquals(100 * 30, totalAcquired.get(), 1000); // Allow ±1s drift
}
```

---

**Key Requirements Recap:**
- Use `VarHandle` or `Unsafe` for atomic operations.
- Ensure no `synchronized` blocks; rely on lock-free patterns.
- Tests must validate correctness, fairness, and stress scenarios.
- Include detailed Javadoc explaining concurrency mechanics and guarantees.
