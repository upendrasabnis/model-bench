<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260627T180804Z -->
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
**Task:**  
Write a single, self‑contained Java source file (compatible with Java 17) that implements a sophisticated multi‑threaded component using a `java.util.concurrent.Phaser` for coordination. The file must contain all necessary auxiliary classes (`DataChunk`, `Result`, etc.) and a full JUnit 5 test suite. **Do not include the solution in your response—only the prompt.**

---

### Requirements

1. **Main Class – `PhasedDataProcessor`**
   - **Constructor:** `PhasedDataProcessor(int workerCount)`  
     - `workerCount` must be ≥ 2; throw `IllegalArgumentException` otherwise.
   - **Phases:**  
     - Phase 0 – **Loading**  
       - Each worker reads the raw data from a `DataChunk` (e.g., simulates I/O).  
     - Phase 1 – **Transformation**  
       - Each worker transforms the loaded data (e.g., applies a function).  
     - Phase 2 – **Aggregation**  
       - Each worker contributes its transformed result to a shared aggregation structure.  
   - **Phaser Usage:**  
     - All workers must **arrive** at the end of a phase before any worker may **advance** to the next phase.  
     - Dynamically register new parties when a chunk is submitted via `submit(DataChunk)` and deregister when the chunk has completed all three phases.  
   - **Thread‑safe Public API:**  
     - `void submit(DataChunk chunk)` – enqueues a chunk for processing.  
     - `Map<String, Result> getResults()` – blocks until **all** submitted chunks have finished all three phases, then returns a read‑only map of aggregation results (keyed by a chunk identifier).  
   - **Internal Queuing:**  
     - Use appropriate concurrent collections (e.g., `LinkedBlockingQueue`, `ConcurrentLinkedQueue`) to hold chunks awaiting each phase.  
   - **Back‑pressure / Dynamic Arrival:**  
     - If new chunks arrive while some workers are still in phase 0 or 1, they must be registered with the Phaser and processed in the **next** cycle of that phase (i.e., they must not jump ahead of already‑started phases).  
   - **Shutdown:**  
     - After `getResults()` returns, the processor should be in a terminal state; further calls to `submit` may either throw `IllegalStateException` or be ignored—document the chosen behavior.  

2. **Auxiliary Classes**
   - `DataChunk` – immutable holder for the data to be processed (e.g., `String id; byte[] payload;`).  
   - `Result` – immutable holder for the outcome of a chunk after all three phases (e.g., `String id; String aggregatedValue;`).  
   - Any additional helper classes or enums you deem necessary.

3. **Documentation**
   - Provide **comprehensive Javadoc** for `PhasedDataProcessor` (class, constructor, and each public method) that explains:
     - The overall concurrency strategy (how the Phaser enforces phase ordering).  
     - How dynamic registration/deregistration works.  
     - Potential pitfalls (e.g., forgetting to deregister, Phaser “arrival” vs. “awaitAdvance” mismatches, thread‑starvation).  
     - The back‑pressure mechanism and how the implementation avoids unbounded queue growth.  
   - Javadoc for auxiliary classes and any non‑trivial private methods.

4. **JUnit 5 Test Suite** (in the same source file, under a static nested test class or a separate top‑level class if you prefer)
   - **Test 1 – Phase Ordering (Normal Load):**  
     - Submit a small, known set of chunks (e.g., 5) with 3 worker threads.  
     - Verify that no worker begins transformation before all loading is complete, and no worker begins aggregation before all transformation is complete.  
   - **Test 2 – Stress Test:**  
     - Submit 1000+ chunks with 8 worker threads.  
     - Assert that the program terminates without deadlock, that `getResults()` returns a result for every chunk, and that no phase is skipped.  
   - **Test 3 – Late‑Arrival Chunks:**  
     - Begin processing a batch of chunks, then, after phase 0 has started for the initial batch, submit additional chunks.  
     - Confirm that the late chunks are processed only after the current phase 0 cycle finishes (i.e., they appear in the results of a subsequent cycle).  
   - **Test 4 – `getResults` Blocking & Correctness:**  
     - Call `getResults()` before any chunks are submitted; it should block until processing finishes and then return an empty map (or throw a documented exception).  
     - After submitting chunks, ensure `getResults()` returns the exact expected aggregation (you can define a deterministic transformation, e.g., converting payload to hexadecimal length).  
   - **Test 5 – Concurrency Correctness (awaitility):**  
     - Use **Awaitility** (or a similar polling library) to repeatedly check internal state (e.g., number of arrived parties per phase) while processing runs, asserting that no illegal state (such as a worker advancing a phase early) is ever observed.  

5. **Compilation & Execution**
   - The entire program must be contained in **one** `.java` file.  
   - It should compile with `javac --release 17 FileName.java`.  
   - Running the JUnit tests (e.g., via `mvn test` or `gradle test` or directly with `java -jar junit-platform-console-standalone-1.10.0.jar`) must succeed.  

---

### Deliverable (to be produced by the user)

- **One Java source file** that includes:
  - Package declaration (optional) and imports.  
  - `PhasedDataProcessor` class with all required constructors, methods, and Javadoc.  
  - `DataChunk` and `Result` classes (or any other supporting types).  
  - The JUnit 5 test class(es) with the five test scenarios described above.  
- No additional external libraries beyond the JDK, JUnit 5, and Awaitility (if you choose to use it). If you use Awaitility, you may assume it is available on the test classpath; you do **not** need to provide its source.  

---

**Note:** Your answer should consist **only** of the prompt above—do **not** include any implementation code, test code, or explanatory text beyond what is required to convey the task.