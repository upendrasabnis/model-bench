<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
Create a self‑contained Java program that implements a highly concurrent token‑bucket rate limiter with the following characteristics:

* **Variable refill rates per client** – each client (identified by a key) can have its own tokens‑per‑second refill rate.
* **Burst capacity** – each client can have a configurable maximum bucket size (burst allowance).
* **Thread‑safe `acquire(int permits)`** – blocks until the requested number of permits are available, then atomically deducts them.
* **Lock‑free implementation** – use only `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) for state updates; no `synchronized` blocks or explicit locks.
* **Comprehensive Javadoc** – explain the concurrency strategy, memory‑visibility guarantees (happens‑before relationships, volatile/atomic semantics), and how overflow/underflow of token counts is prevented.
* **Full JUnit 5 test suite** covering:
  1. Functional correctness (single‑threaded acquire/release, refill behavior, burst limits).
  2. Multi‑threaded fairness under contention (multiple threads acquiring from the same bucket, verifying no starvation).
  3. Stress test with at least 200 concurrent threads performing random acquire/release patterns for a minimum of 30 seconds, asserting that the observed throughput respects the configured refill rate.
  4. Verification that the limiter respects the configured refill rate under sustained load (e.g., measure tokens consumed vs. time elapsed).

The program must compile and run with a standard JDK 17+ (or later) without external dependencies beyond JUnit 5. Provide the complete source code for the limiter class, any supporting classes, and the test classes in a single response. Do not include any explanatory text outside the code.