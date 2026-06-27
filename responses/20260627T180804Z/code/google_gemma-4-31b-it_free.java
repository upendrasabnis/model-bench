<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260627T180804Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a sophisticated multi‑threaded component using a Phaser‑based coordination mechanism. Specifically, create a class named `PhasedDataProcessor` that:

1. Accepts a list of `DataChunk` objects to be processed in three distinct phases: (a) loading, (b) transformation, and (c) aggregation.
2. Uses a `java.util.concurrent.Phaser` to ensure that all worker threads complete phase a before any begin phase b, and similarly for phase c.
3. Allows the user to specify the number of worker threads (must be >= 2) at construction time.
4. Provides thread‑safe methods:
   - `void submit(DataChunk chunk)` – adds a chunk to the processing queue.
   - `Map<String, Result> getResults()` – returns the aggregated results after all phases are finished.
5. Handles dynamic arrival of new chunks while previous phases are still running, correctly registering/unregistering parties with the Phaser.
6. Includes comprehensive Javadoc explaining the concurrency strategy, potential pitfalls, and how back‑pressure is managed.
7. Implements thorough JUnit 5 tests that cover:
   - Correct phase ordering under normal load.
   - Stress test with 1000+ chunks and 8 threads, asserting no deadlocks or missed phases.
   - Tests for late‑arrival chunks after phase a has begun, ensuring they are processed in the next cycle.
   - Verification that `getResults` blocks until all phases complete and returns accurate aggregation.
   - Concurrency correctness using `awaitility` or similar utilities to detect race conditions.

The program must be executable as a single file (including any auxiliary classes like `DataChunk` and `Result`) and must compile with Java 17. Do not provide the solution; only the prompt.
<!-- RESPONSE -->
Since you requested that I **do not provide the solution**, but rather the **prompt** (which you have already provided in your request), I have refined your requirements into a formal, technical specification. 

If you are intending to hand this to an LLM or a developer to implement, here is the polished, professional version of that prompt to ensure the highest quality output:

***

### Technical Specification: Implementation of `PhasedDataProcessor`

**Objective:** 
Implement a high-performance, thread-safe Java 17 component that processes data in a strictly sequenced multi-phase pipeline using a `java.util.concurrent.Phaser`.

**Core Requirements:**

1.  **Architecture:**
    *   **Class Name:** `PhasedDataProcessor`.
    *   **Data Models:** Define `DataChunk` (input) and `Result` (output) as immutable POJOs/Records.
    *   **Phases:** The processor must execute three distinct phases: **Loading $\rightarrow$ Transformation $\rightarrow$ Aggregation**.
    *   **Coordination:** Use a `Phaser` to act as a reusable synchronization barrier. No thread may enter Phase $N+1$ until all registered parties have completed Phase $N$.

2.  **Functional Requirements:**
    *   **Constructor:** Must accept an `int numWorkers` (must be $\ge 2$).
    *   **`void submit(DataChunk chunk)`:** Thread-safe submission of data. The system must handle dynamic arrival of chunks.
    *   **`Map<String, Result> getResults()`:** A blocking call that returns the final aggregated results only after all submitted chunks have traversed all three phases.
    *   **Dynamic Registration:** The implementation must correctly use `phaser.register()` and `phaser.arriveAndDeregister()` to handle the lifecycle of worker threads and the arrival of new work without causing deadlocks.

3.  **Concurrency & Safety:**
    *   **Back-pressure:** Implement a mechanism (e.g., `BlockingQueue` with a capacity limit) to prevent `OutOfMemoryError` during high-volume submission.
    *   **Thread Safety:** Ensure all shared state (intermediate buffers, result maps) is handled via `ConcurrentHashMap` or other `java.util.concurrent` primitives.
    *   **Documentation:** Provide comprehensive Javadoc explaining the synchronization strategy, the specific use of `Phaser` over `CyclicBarrier`, and how the program avoids common pitfalls like "stuck" phasers.

4.  **Testing Suite (JUnit 5):**
    *   **Phase Ordering Test:** Verify that no "Transformation" logic executes before all "Loading" logic for the current batch is complete.
    *   **Stress Test:** Process $1,000+$ chunks with 8+ threads to ensure stability and absence of deadlocks.
    *   **Late-Arrival Test:** Submit chunks while Phase A is already in progress; verify they are correctly picked up in the subsequent processing cycle.
    *   **Blocking Verification:** Ensure `getResults()` blocks correctly and does not return partial results.
    *   **Race Condition Detection:** Use `Awaitility` or a similar synchronization utility to assert that the final state is consistent across multiple concurrent producers.

**Constraints:**
*   **Language:** Java 17.
*   **Format:** A single, self-contained `.java` file containing all necessary auxiliary classes and tests.
*   **Dependencies:** JUnit 5 and Awaitility.

***