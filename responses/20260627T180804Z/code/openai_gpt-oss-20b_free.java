<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260627T180804Z -->
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