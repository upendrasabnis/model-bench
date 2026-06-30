<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->
**Prompt for Generating a Lock‑Free Multi‑Producer Multi‑Consumer Counter**

Write a **self‑contained Java program** that implements a **highly concurrent, lock‑free, multi‑producer multi‑consumer (MPMC) counter** using only the standard `java.util.concurrent.atomic` package (specifically `VarHandle` or, if you prefer, `sun.misc.Unsafe`). The implementation must **not contain any explicit `synchronized` blocks, `Lock`s, or other traditional blocking synchronization primitives**.

### Required Public API
Create a class named **`LockFreeAtomicCounter`** with the following methods:

| Method | Signature | Description |
|--------|-----------|-------------|
| `increment()` | `public void increment()` | Atomically adds 1 to the counter. |
| `decrement()` | `public void decrement()` | Atomically subtracts 1 from the counter. |
| `add(long delta)` | `public void add(long delta)` | Atomically adds an arbitrary `long` value (`delta`) to the counter. |
| `get()` | `public long get()` | Returns the current value of the counter without causing any memory‑visibility side effects. |

All methods must be **linearizable**: any interleaving of operations from multiple threads must appear as if they executed in some sequential order that respects the real‑time order of each operation.

### Implementation Constraints
1. **Lock‑free algorithm** – use a **compare‑and‑set (CAS) loop** on an atomic variable.  
2. **No explicit synchronization blocks** – you may only use `VarHandle` (preferred) or `Unsafe` for atomic operations.  
3. **Memory‑model guarantees** – explain in comments or accompanying documentation why the algorithm is safe under the Java Memory Model (e.g., `volatile` semantics of `VarHandle.get/set`, CAS guarantees linearizability, happens‑before relationships).  
4. **Self‑contained** – the entire program (class definition, any helper methods, and a `main` method that performs a quick sanity check) must be in a single source file.

### Testing Requirements
Provide a **comprehensive JUnit 5 test suite** (in a separate file or as part of the same source if you wish) that includes, at minimum:

1. **Functional correctness** – verify that after a series of sequential calls (`increment`, `decrement`, `add`) the final value matches the expected count.  
2. **Thread‑safety under heavy contention** – spawn **100 threads**, each performing **1 000 000** random operations (`increment`, `decrement`, or `add`) on a shared `LockFreeAtomicCounter` instance. After all threads finish, assert that the final value equals the sum of all performed deltas.  
3. **No lost updates** – ensure that the total number of performed increments equals the number of performed decrements plus the final counter value, proving that no updates were dropped.  
4. **Stress test with `ExecutorService` and `CountDownLatch`** – coordinate thread start/stop to avoid race conditions in the test harness itself. Use a single shared counter across all threads.  
5. **ABA scenario test** – deliberately construct a scenario where the counter’s value may wrap around or be overwritten in a way that could expose ABA problems. Verify that the counter still behaves correctly (e.g., by using `add(Long.MAX_VALUE)` and `add(-Long.MAX_VALUE)` sequences).  
6. **Performance hint (optional)** – measure throughput under contention and report that the lock‑free design scales better than a naïve `synchronized` version (this can be a simple log statement; no external libraries required).

### Documentation Requirements (within the source code)
- **Algorithm description** – Include a comment block that explains the CAS‑based loop, how ABA is handled (e.g., using a 128‑bit `VarHandle` with a version tag, or noting that the simple 64‑bit counter is safe because only the low‑order bits change and overflow is defined by Java’s unsigned arithmetic).  
- **Linearizability justification** – Explain why each operation is linearizable (e.g., CAS ensures that a successful compare‑and‑set appears to take effect at the instant it succeeds, and failed CAS retries preserve order).  
- **Memory‑model safety** – State why the use of `VarHandle` (which provides acquire/release semantics) guarantees visibility of updates to other threads without explicit `volatile` fields.

### Submission Format
- **One Java source file** containing:
  1. `LockFreeAtomicCounter` class with the required public methods.
  2. Inline documentation/comments covering the algorithm and memory‑model reasoning.
  3. A `main` method that runs a quick sanity check (e.g., creates a counter, performs a few operations, prints the result).  
- **Separate JUnit 5 test class** (or multiple test methods) that fulfills all testing requirements listed above.  
- No external dependencies beyond `junit-jupiter-api` and `junit-jupiter-engine`.  
- The entire code must compile and run on **Java 17** (or later) without any third‑party libraries.

---

**Your task:** Generate the full source code that satisfies every bullet point above **without providing the actual implementation** — i.e., only the **prompt** that would guide an AI (or a developer) to write the code. Do **not** output any Java code, comments, or test implementations. Provide only the detailed prompt as described.