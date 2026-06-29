<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for the Language Model**

Write a complete, self‑contained Java source file that implements a highly concurrent token‑bucket rate limiter with the following features:

1. **Variable refill rates per client** – each client (identified by a key, e.g., `String` or any object) can have its own refill rate (tokens per second) and burst capacity.
2. **Thread‑safe `acquire(int permits)`** – blocks the calling thread until the requested number of permits is available, then atomically deducts them.
3. **Lock‑free implementation** – use only `java.util.concurrent.atomic.AtomicLong` and `java.lang.invoke.VarHandle` (or, if you prefer, `sun.misc.Unsafe`) to achieve lock‑free updates. **Do not use** `synchronized`, `java.util.concurrent.locks.Lock`, or any built‑in blocking queues for the core token‑bucket logic.
4. **Comprehensive Javadoc** – explain:
   * The overall concurrency strategy (how you avoid races, how you guarantee progress, etc.).
   * Memory‑visibility guarantees provided by the chosen atomic/VarHandle operations (e.g., volatile reads/writes, full fences, acquire/release semantics).
   * How you prevent integer overflow when updating token counts and timestamps.
   * Any assumptions about the underlying JVM or hardware (e.g., that `long` updates are atomic).
5. **Utility methods** (as needed) for:
   * Adding/updating a client’s refill rate and burst capacity.
   * Removing a client.
   * Optionally, a non‑blocking `tryAcquire(int permits)` that returns `true` if permits were granted immediately.
6. **No external dependencies** – the code should compile and run with only the JDK (version 17 or newer is acceptable).

After the implementation, provide a **full JUnit 5 test suite** that validates the limiter under the following scenarios:

* **Functional correctness** – single‑threaded tests that verify:
  * Tokens are refilled at the configured rate.
  * Burst capacity is respected.
  * `acquire` blocks until sufficient tokens are available and then proceeds.
  * `tryAcquire` behaves correctly.
* **Multi‑threaded fairness under contention** – launch multiple threads that repeatedly call `acquire(1)` on the same client key and assert that:
  * No thread starves indefinitely.
  * The observed throughput approximates the configured refill rate (within a reasonable tolerance).
* **Stress test with 200 concurrent threads** – run a test for **at least 30 seconds** where:
  * Each thread repeatedly chooses a random client key (from a small pool, e.g., 10 keys) and a random permit count (1‑5).
  * Calls `acquire(permits)` (blocking) and, after a short random think‑time, optionally calls a `release`‑like operation if you expose one (or simply lets the limiter refill naturally).
  * The test records the total number of successful acquisitions per client and verifies that the long‑term average rate does not exceed the configured refill rate plus burst tolerance.
* **Verification of refill rate under load** – under the stress test, compute the inter‑arrival time of acquisitions for each client and assert that the empirical rate stays within, say, ±10 % of the configured rate when the system is saturated.

The test suite should:

* Use JUnit 5 annotations (`@Test`, `@BeforeEach`, `@AfterEach`, `@Timeout`, etc.).
* Employ appropriate synchronization aids (e.g., `CountDownLatch`, `CyclicBarrier`, `Phaser`) only for test coordination — **not** inside the limiter implementation.
* Make use of `java.time` for measuring elapsed time where needed.
* Include assertions that clearly state the expected behavior and any tolerances.
* Be deterministic enough to pass reliably on a typical CI runner, while still exercising the concurrent behavior intensely.

**Deliverables**

* One Java file containing the token‑bucket limiter class (with all necessary imports, Javadoc, and lock‑free logic).
* A separate Java file (or multiple files) containing the JUnit 5 test suite.

Do **not** include any explanation or commentary beyond the requested source code and tests. The model should output only the code.