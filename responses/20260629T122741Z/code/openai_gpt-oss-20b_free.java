<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for the model to generate the code and tests**

> Write a **self‑contained** Java program that implements a highly concurrent token‑bucket rate limiter. The implementation must meet the following requirements:
> 
> 1. **Variable refill rates per client** – each client can be configured with its own refill rate (tokens per second) and burst capacity (maximum token bucket size).
> 2. **Burst capacity** – the bucket must never exceed the configured burst capacity.
> 3. **Thread‑safe `acquire(int permits)`** – this method blocks until the requested number of permits is available. It must be safe to call concurrently from many threads.
> 4. **Lock‑free algorithm** – use `java.util.concurrent.atomic.AtomicLong` and `java.lang.invoke.VarHandle` (or `sun.misc.Unsafe` if you prefer) to manage the token count. Do **not** use any `synchronized` blocks, `ReentrantLock`, or other blocking primitives for the core token accounting.
> 5. **Memory‑visibility guarantees** – the implementation must correctly use volatile semantics (via `AtomicLong` or `VarHandle`) so that updates to the token count are immediately visible to all threads. Include a discussion in the Javadoc about how this guarantees correctness.
> 6. **Overflow prevention** – ensure that token counts never overflow or become negative. The implementation should guard against integer overflow and properly clamp the count to the burst capacity.
> 7. **Comprehensive Javadoc** – all classes, methods, and critical sections must be documented. Explain the concurrency strategy, how the lock‑free algorithm works, the memory‑visibility guarantees, and how overflow is prevented.
> 8. **JUnit 5 test suite** – provide a full test suite that covers:
>    * Functional correctness: basic acquire/release behavior under single‑threaded use.
>    * Multi‑threaded fairness: under contention, threads acquire permits in roughly the order they requested them.
>    * Stress test: launch 200 concurrent threads that perform random `acquire()` and `release()` patterns for at least 30 seconds. Verify that the limiter does not deadlock, does not lose permits, and respects the configured refill rate.
>    * Refill‑rate verification: under a sustained load, measure the average number of permits granted per second and assert it matches the configured refill rate within a reasonable tolerance.
> 
> The program should be fully self‑contained: all necessary imports, classes, and the `main` method (if desired) should be included. The code must compile with Java 17 or later. Do **not** provide the implementation; only produce the prompt text that instructs the model to generate the code and tests.