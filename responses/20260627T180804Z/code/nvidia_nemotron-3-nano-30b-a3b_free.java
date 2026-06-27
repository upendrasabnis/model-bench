<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260627T180804Z -->
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
**Prompt**

Create a self‑contained Java program (single `.java` file) that implements a `PhasedDataProcessor` class using `java.util.concurrent.Phaser` for multi‑threaded coordination. The program must:

1. Accept a list of `DataChunk` objects and process each chunk through three deterministic phases: loading, transformation, and aggregation.  
2. Use a `Phaser` to synchronize all worker threads so that every thread finishes phase (a) before any thread begins phase (b), and similarly for phase (c).  
3. Allow the caller to specify the number of worker threads (minimum 2) at construction time.  
4. Provide the following thread‑safe API:  
   - `void submit(DataChunk chunk)` – enqueues a chunk for processing.  
   - `Map<String, Result> getResults()` – returns the aggregated results; this method must block until all phases are complete for all submitted chunks.  
5. Support dynamic arrival of new chunks while earlier phases are still running; the `Phaser` must be correctly registered/unregistered for each phase transition.  
6. Include comprehensive Javadoc for `PhasedDataProcessor`, `DataChunk`, and `Result` that explains the concurrency strategy, potential pitfalls (e.g., deadlock, livelock), and how back‑pressure is handled.  
7. Compile and run under Java 17, and be executable as a single source file (no external dependencies besides the JDK).  

In addition, write JUnit 5 test cases (also within the same file, using only the JDK’s built‑in testing utilities or `org.junit.jupiter:junit-jupiter-api` if desired) that verify:

- Correct phase ordering under normal load.  
- A stress test with > 1000 chunks and 8 threads that never deadlocks or misses a phase.  
- Proper handling of late‑arrival chunks that arrive after phase (a) has started (they should be processed in the next cycle).  
- `getResults()` blocks until all phases finish and returns accurate aggregation.  
- Concurrency correctness using `Awaitility` (or similar) to detect race conditions.  

The final output should be **only the prompt** (i.e., the description above) and **must not contain any implementation code**. Ensure the prompt is clear enough for a developer to produce the required program.