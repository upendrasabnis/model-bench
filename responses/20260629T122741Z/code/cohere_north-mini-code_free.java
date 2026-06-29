<!-- model: cohere/north-mini-code:free | category: code | run: 20260629T122741Z -->
<!-- PROMPT -->
Write a self-contained Java program that implements a highly concurrent token‑bucket rate limiter supporting variable refill rates per client, burst capacity, and a thread‑safe `acquire(int permits)` method that blocks until permits are available. The implementation must use a lock‑free algorithm with `java.util.concurrent.atomic.AtomicLong` and `VarHandle` (or `sun.misc.Unsafe`) without explicit `synchronized` blocks. Include comprehensive Javadoc explaining the concurrency strategy, memory‑visibility guarantees, and how overflow is prevented. Provide a full JUnit 5 test suite that covers: functional correctness, multi‑threaded fairness under contention, stress testing with 200 concurrent threads performing random acquire/release patterns for at least 30 seconds, and verification that the limiter respects the configured refill rate under load. Do not write the solution; only produce the prompt text for the model to generate the code and tests.
<!-- RESPONSE -->
**Prompt for the Model**

You are to produce a complete, self‑contained Java artifact that satisfies the requirements below.  
Your response should contain **only** the source code for the implementation and the JUnit 5 test suite (no additional explanations, READMEs, or build scripts are needed). The code must be ready to be compiled and run as‑is.

---

### 1. Core Requirements – `TokenBucketRateLimiter`

* **Package & Class**
  * Place the implementation in a package `com.example.ratelimiter`.
  * The public class must be named `TokenBucketRateLimiter`.

* **Configuration**
  * `TokenBucketRateLimiter(long refillTokensPerSecond, long burstCapacity, String clientId)`
    * `refillTokensPerSecond` – the number of tokens that should be added to the bucket each second (may differ per client).
    * `burstCapacity` – the maximum number of tokens that can be stored in the bucket at any moment (burst allowance).
    * `clientId` – an identifier used to isolate per‑client state (you may store per‑client buckets in a concurrent map).

* **Public API**
  * `void acquire(int permits)` – blocks the calling thread until at least `permits` tokens are available, then removes them from the bucket.  
    * Must throw `IllegalArgumentException` if `permits <= 0`.
    * Must respect the per‑client refill rate and burst capacity.
  * `long availableTokens()` – returns the current number of tokens in the bucket (non‑negative, may be read‑only for concurrent readers).
  * `String getClientId()` – returns the client identifier.

* **Concurrency & Lock‑Free Constraints**
  * The implementation **must not** use any explicit `synchronized` blocks, `ReentrantLock`, `Semaphore`, or higher‑level primitives like `java.util.concurrent.Semaphore`.
  * Use **lock‑free** techniques:
    * `java.util.concurrent.atomic.AtomicLong` for mutable counters.
    * `java.lang.reflect.VarHandle` (or, if you prefer, `sun.misc.Unsafe`) for volatile reads/writes of long fields.
  * The `acquire` method must block **without** resorting to `synchronized` or `Lock` objects. You may use `java.util.concurrent.locks.AbstractQueuedSynchronizer` (AQS) or a `java.util.concurrent` condition variable, **provided** the underlying state updates are lock‑free (i.e., using `AtomicLong`/`VarHandle`).
  * The rate limiter must be **thread‑safe** and **highly concurrent** – many threads can call `acquire`/`availableTokens` simultaneously with minimal contention.

* **Javadoc**
  * Every public class, method, and field must have comprehensive Javadoc.
  * Explain the **concurrency strategy** (e.g., per‑client atomic counters, lock‑free updates, memory‑visibility guarantees via `volatile`/`VarHandle`).
  * Document **overflow handling** (how the token count is clamped to `[0, burstCapacity]` and how token addition avoids arithmetic overflow).

* **Design Notes**
  * You may store per‑client buckets in a `ConcurrentHashMap<String, TokenBucketRateLimiter>` if you wish to keep the per‑client state encapsulated, but the final object returned to the caller must be the actual limiter instance.
  * The refill mechanism should be **time‑based** (e.g., using `System.nanoTime()` or `System.currentTimeMillis()`) and run **independently** of `acquire` calls (e.g., via a background thread or lazy refill on each `acquire`).
  * Ensure that the limiter works correctly under **high contention** (hundreds of threads acquiring/release tokens simultaneously).

---

### 2. JUnit 5 Test Suite

Provide a **single** test class (or a small group of related classes) that fully exercises the implementation.

* **Package** – Place tests under `com.example.ratelimiter`.

* **Test Class Name** – `TokenBucketRateLimiterTest`.

* **Test Scenarios** (all must be present and annotated with `@Test`):
  1. **Functional Correctness**
     * Verify that a newly created limiter has `availableTokens() == burstCapacity`.
     * Verify that `acquire(permits)` reduces `availableTokens()` by exactly `permits`.
     * Verify that `acquire` throws `IllegalArgumentException` for non‑positive permits.
     * Verify that tokens are never negative and never exceed `burstCapacity`.

  2. **Refill Rate Accuracy**
     * Configure a limiter with a known refill rate (e.g., 10 tokens/second) and burst capacity.
     * After waiting precise intervals, assert that `availableTokens()` has increased by the expected amount (within a small tolerance).
     * Verify that the refill respects the per‑client rate when multiple limiters exist for different clients.

  3. **Burst Capacity**
     * Show that the limiter can instantly consume up to `burstCapacity` tokens even if the refill rate would normally supply fewer tokens.
     * Demonstrate that any tokens beyond the burst capacity are not stored (i.e., they are discarded or cause the acquire to block).

  4. **Multi‑threaded Fairness under Contention**
     * Spawn a fixed number of threads (e.g., 10) that repeatedly call `acquire(1)` on the **same** limiter.
     * Collect the order in which threads acquire tokens and assert that no thread starves indefinitely (e.g., using a timeout or a fairness check).
     * Optionally, measure that each thread gets roughly an equal share over a long run.

  5. **Stress Test – 200 Threads, Random Pattern, 30 seconds**
     * Create a pool of **200** threads.
     * Each thread randomly picks an `acquire` amount (e.g., 1‑5 tokens) or a “sleep” action, and performs these actions for at least **30 seconds**.
     * Ensure the test does **not** deadlock or crash.
     * After the stress period, verify that the token count is consistent with the number of tokens added by refills minus tokens acquired (taking into account burst capacity).
     * Record and optionally log the total number of successful acquires vs. the expected number based on the refill rate to confirm that the limiter respects its configured rate under heavy load.

* **General Test Requirements**
  * Use JUnit 5 assertions (`assertEquals`, `assertTrue`, `assertThrows`, etc.).
  * Use `@Timeout` where appropriate to avoid hanging tests.
  * Use `@Tag` or `@DisplayName` to organize tests logically.
  * Ensure tests are **deterministic** where possible, but the stress test may be non‑deterministic; use appropriate tolerances.
  * All tests must be **self‑contained** (no external resources, no manual seed).

---

### 3. Build & Execution

* The code must compile with **Java 21** (or the latest LTS) using the standard `javac`/`maven`/`gradle` setup – you do not need to provide the build configuration, only the source files.
* The test suite must be runnable with JUnit 5 (e.g., using Maven Surefire or Gradle Test).

---

**Deliverable**

Provide **only** the Java source files:

* `src/main/java/com/example/ratelimiter/TokenBucketRateLimiter.java`
* `src/test/java/com/example/ratelimiter/TokenBucketRateLimiterTest.java`

(Or a single file if you prefer to keep everything together.)

The model should generate the complete, compilable, and runnable implementation and test suite as described above, adhering strictly to the lock‑free, `AtomicLong`/`VarHandle` constraints and the Javadoc requirements.