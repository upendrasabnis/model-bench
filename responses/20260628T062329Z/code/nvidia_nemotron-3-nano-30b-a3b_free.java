<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260628T062329Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent, lock‑free atomic counter using Java's VarHandle and sun.misc.Unsafe (or java.util.concurrent.atomic) primitives. The class should be named `LockFreeCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, `get()` and `compareAndSet(long expected, long update)`. The implementation must avoid any synchronized blocks or explicit Locks, guaranteeing thread‑safety through atomic compare‑and‑set loops and proper memory ordering. Include thorough Javadoc comments explaining the lock‑free algorithm, its ABA‑problem mitigation strategy, and any performance considerations. Additionally, supply a complete JUnit 5 test suite that covers:
1. Functional correctness of all public methods under single‑threaded execution.
2. Multi‑threaded stress tests where dozens of threads concurrently perform random increments, decrements, and adds for at least 10 seconds, asserting that the final value matches the expected total.
3. A test that deliberately creates an ABA scenario and demonstrates that the implementation correctly handles it (e.g., using versioned stamps or a wide CAS).
4. Performance benchmark tests comparing `LockFreeCounter` against `AtomicLong` under high contention.
The prompt must require the model to output the entire Java source file (including the `LockFreeCounter` class and any helper classes) and the separate JUnit test class, both ready to compile and run. Do not provide a solution yourself.
<!-- RESPONSE -->
**Task Prompt**

> **Write the complete source code for a lock‑free atomic counter named `LockFreeCounter` and a full JUnit 5 test suite that validates its behavior.**  
> Do **not** provide any implementation, explanations, or sample output yourself. Instead, output **only** the two Java source files that satisfy every requirement listed below.  

---

### 1. `LockFreeCounter.java`

Create a **single** public class named `LockFreeCounter` that implements a highly concurrent, lock‑free atomic counter.

| Method | Signature | Requirement |
|--------|-----------|-------------|
| `increment()` | `public void increment()` | Atomically adds 1 to the counter. |
| `decrement()` | `public void decrement()` | Atomically subtracts 1 from the counter. |
| `add(long delta)` | `public void add(long delta)` | Atomically adds the supplied `delta` (which may be negative) to the counter. |
| `get()` | `public long get()` | Returns the current value of the counter. |
| `compareAndSet(long expected, long update)` | `public boolean compareAndSet(long expected, long update)` | Performs a compare‑and‑set on the internal state; returns `true` iff the current value equals `expected` and the CAS succeeds. |

**Implementation constraints**

1. **No synchronized blocks and no explicit `java.util.concurrent.locks.Lock` usage.**  
2. **Must use only lock‑free primitives** – you may employ either  
   * `java.util.concurrent.atomic.AtomicLong` **or**  
   * `java.lang.invoke.VarHandle` **or** `sun.misc.Unsafe` (the latter only if you explicitly restrict yourself to those APIs).  
3. **ABA‑problem mitigation** – the counter must be robust against ABA scenarios. Acceptable approaches include:  
   * Using a **versioned stamp** (e.g., a 64‑bit value where the high 32 bits represent a monotonically increasing sequence number).  
   * Performing a **wide CAS** on a composite structure (e.g., an `AtomicReference` that holds both the value and the stamp).  
4. **Memory ordering** – ensure that reads and writes are ordered appropriately (e.g., `VarHandle` provides acquire/release semantics; if you use `Unsafe`, apply the correct `volatile` semantics).  
5. **Public API only** – the class may contain private helper methods or fields, but **no public mutable state** other than the counter itself.  
6. **Javadoc** – each public method must contain a thorough Javadoc comment that explains:  
   * What the method does,  
   * How the lock‑free algorithm works,  
   * The ABA‑mitigation strategy employed, and  
   * Any performance or ordering considerations relevant to that method.  

**File layout**

```java
/**
 * Lock‑free atomic counter implementation.
 *
 * <p>This class provides a highly concurrent counter that avoids any
 * synchronized blocks or explicit locks.  The implementation relies on
 * a compare‑and‑set loop that operates on a composite {@code AtomicLong}
 * holding both the numeric value and a version stamp to mitigate the ABA
 * problem.  All operations are performed with the appropriate memory
 * ordering guarantees to ensure visibility across threads.</p>
 */
public final class LockFreeCounter {
    // ---- fields -----------------------------------------------------------

    // ---- constructors -----------------------------------------------------

    // ---- public API -------------------------------------------------------
    /** Increment the counter by one. */
    public void increment();

    /** Decrement the counter by one. */
    public void decrement();

    /** Atomically add the supplied delta. */
    public void add(long delta);

    /** Return the current value. */
    public long get();

    /**
     * Atomically set the value to {@code update} only if the current value
     * equals {@code expected}.
     *
     * @param expected the value that must be observed for the CAS to succeed
     * @param update   the new value to store
     * @return {@code true} if the CAS succeeded, {@code false} otherwise
     */
    public boolean compareAndSet(long expected, long update);
}
```

*All code, including field declarations, helper classes, and imports, must appear in this single file.*  
*The class must be **final** (or otherwise not extendable) to preserve the lock‑free guarantees.*

---

### 2. `LockFreeCounterTest.java`

Create a **separate** public test class named `LockFreeCounterTest` that uses **JUnit 5** (JUnit Jupiter). The test class must contain **four** distinct test methods or test groups that collectively satisfy the following requirements:

1. **Single‑threaded functional correctness**  
   * Verify that `increment()`, `decrement()`, `add(long)`, and `get()` behave correctly when invoked sequentially from a single thread.  
   * Include assertions that check intermediate values as well as the final state.

2. **Multi‑threaded stress test**  
   * Spawn **multiple threads** (e.g., 8 – 16) that each perform a random mixture of `increment()`, `decrement()`, and `add(long)` operations for **at least 10 seconds** (wall‑clock time).  
   * Use `Thread.sleep` or a scheduled executor to stop the threads after the required duration.  
   * After all threads finish, assert that the final counter value equals the **expected total** (i.e., sum of all issued increments minus sum of all decrements plus all adds).  
   * The test should be nondeterministic; it must pass under high contention.

3. **ABA scenario test**  
   * Design a test that deliberately creates an ABA situation using the versioned stamp or wide CAS mechanism.  
   * After performing a sequence of operations that cause the internal stamp to wrap around, verify that the counter still yields the correct final value and that `compareAndSet` does not incorrectly succeed when it should not.  
   * Document (in comments) how the test demonstrates ABA safety.

4. **Performance benchmark against `java.util.concurrent.atomic.AtomicLong`**  
   * Run a high‑contention benchmark where, for a configurable number of threads (e.g., 1, 4, 8, 16), each thread repeatedly calls `increment()` on either `LockFreeCounter` or an `AtomicLong` for a fixed number of operations (e.g., 10 million increments).  
   * Measure elapsed time for each implementation and store the results in a simple data structure.  
   * After the benchmark, assert that the `LockFreeCounter` does **not** exceed a reasonable slowdown threshold (e.g., it should not be more than 2× slower than `AtomicLong` under the tested contention).  
   * The benchmark must be deterministic enough to compile and run, but you may use `System.nanoTime()` to capture timings.

**Additional test requirements**

* All test methods must be annotated with `@Test`.  
* Use `org.junit.jupiter.api.Assertions.*` for assertions.  
* If any test may fail due to timing, include a generous timeout (`@Timeout(30)` or similar) to avoid flaky failures.  
* The test class must be **self‑contained** – it may import any JUnit 5 classes, `java.util.concurrent.*`, `java.util.Random`, etc., but it must **not** depend on any external resources or files.  

**File layout**

```java
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.*;

public final class LockFreeCounterTest {

    // ---- single‑threaded functional tests ------------------------------

    @Test
    void testSingleThreadedOperations() { … }

    // ---- multi‑threaded stress test ------------------------------------

    @Test
    void testConcurrentStress() throws InterruptedException { … }

    // ---- ABA scenario test -------------------------------------------

    @Test
    void testAbaScenario() { … }

    // ---- performance benchmark ---------------------------------------

    @RepeatedTest(5)
    void testPerformanceVsAtomicLong() throws Exception { … }
}
```

*Each `…` placeholder represents the body you must fill in, respecting the specifications above.*

---

### 3. General Instructions for the Model

* **Do not output any explanatory text, markdown fences, or commentary.**  
* **Emit exactly two Java source files**, named `LockFreeCounter.java` and `LockFreeCounterTest.java`, each containing **complete, compilable code** that satisfies every bullet point above.  
* The code must **compile** with a standard JDK (e.g., JDK 21) and **run** with JUnit 5 on the class‑path.  
* Ensure that the lock‑free implementation truly avoids `synchronized`, `Lock`, or any blocking constructs.  
* Include all necessary imports and package statements (or leave the default package if you prefer).  
* The Javadoc comments must be present and must contain the required explanations.  
* The test class must contain the four test groups described, each with appropriate assertions and comments.  
* Do **not** provide any sample runs, benchmark numbers, or external scripts—only the source code.  

---

**When you are ready, output only the two Java source files as described.**