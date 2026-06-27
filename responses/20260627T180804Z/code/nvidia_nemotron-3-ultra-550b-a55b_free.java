<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260627T180804Z -->
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

Write a single, self‑contained Java 17 program that implements a sophisticated multi‑threaded component using a `java.util.concurrent.Phaser`‑based coordination mechanism. The program must contain the following elements (all in one source file so it can be compiled and run directly):

1. **Domain classes**  
   - `DataChunk` – a simple immutable holder for a payload (e.g., `String id`, `byte[] data`).  
   - `Result` – an immutable holder for the aggregated outcome of a chunk (e.g., `String id`, `long checksum`, `int transformedCount`).  

2. **`PhasedDataProcessor` class**  
   - **Constructor** `PhasedDataProcessor(int workerCount)` – `workerCount` must be ≥ 2; otherwise throw `IllegalArgumentException`.  
   - **Concurrency strategy** – use a single `Phaser` to coordinate three distinct phases for each processing *cycle*:  
     1. **Loading** – workers read/validate chunks.  
     2. **Transformation** – workers mutate/transform the data.  
     3. **Aggregation** – workers combine partial results into a thread‑safe map `Map<String, Result>`.  
   - **Dynamic registration** – the processor must accept new chunks at any time via `submit(DataChunk chunk)`. When a new chunk arrives while a cycle is in progress, it must be queued for the *next* cycle. The Phaser’s party count must be adjusted (register/unregister) safely to reflect the number of chunks participating in the current cycle.  
   - **Thread‑safe API**  
     - `void submit(DataChunk chunk)` – enqueues a chunk; non‑blocking.  
     - `Map<String, Result> getResults()` – blocks until *all* currently submitted chunks have passed through all three phases of the *current* cycle and returns an unmodifiable snapshot of the aggregated results.  
   - **Worker pool** – a fixed `ExecutorService` (e.g., `Executors.newFixedThreadPool(workerCount)`) that runs a reusable `Runnable` task per worker. Workers repeatedly:  
     1. Wait for a chunk to become available for the current phase.  
     2. Perform the phase’s work.  
     3. `phaser.arriveAndAwaitAdvance()` to synchronize with peers.  
   - **Back‑pressure** – if the internal queue grows beyond a configurable high‑water mark (e.g., 10 000 chunks), `submit` should block until space becomes available (use a `Semaphore` or `ArrayBlockingQueue`). Document the chosen mechanism.  

3. **Comprehensive Javadoc** on `PhasedDataProcessor` (class‑level and each public method) that explains:  
   - The three‑phase lifecycle and how the `Phaser` guarantees phase ordering.  
   - Dynamic party registration/unregistration and why it is safe.  
   - Back‑pressure strategy and its impact on latency.  
   - Potential pitfalls (e.g., lost wake‑ups, phase‑skip if a worker crashes, phaser termination).  

4. **JUnit 5 test suite** (in the same file, using `@Test` methods) covering:  
   - **Phase ordering** – submit a handful of chunks, start processing, and assert that no worker begins phase b before all have finished phase a, etc.  
   - **Stress test** – 1 000+ chunks, 8 worker threads; verify no deadlocks, all chunks processed exactly once, and `getResults` returns a map of the expected size.  
   - **Late‑arrival chunks** – submit chunks, let phase a start, then submit additional chunks; ensure the late chunks are processed in the *next* cycle and not the current one.  
   - **`getResults` blocking semantics** – call `getResults` before any work is submitted (should return empty map), after submitting but before completion (should block), and after completion (should return immediately with correct aggregation).  
   - **Concurrency correctness** – use Awaitility (or `CountDownLatch`/`CyclicBarrier` helpers) to detect race conditions such as missing phase advances or duplicate processing.  

5. **Main method** – a minimal demo that creates a `PhasedDataProcessor` with 4 workers, submits 20 random `DataChunk`s, prints the aggregated `Result`s, and shuts down cleanly.  

6. **Compilation & execution constraints**  
   - Single `.java` file (e.g., `PhasedDataProcessor.java`).  
   - No external dependencies beyond the JDK and JUnit 5 / Awaitility (declare them as `//`‑commented `//` `module-info.java`‑style `requires` or as Maven/Gradle coordinates in a comment block at the top).  
   - Must compile with `javac --release 17 PhasedDataProcessor.java` and run with `java PhasedDataProcessor`.  

**Deliverable**: Provide *only* the prompt (as above). Do **not** supply the implementation.