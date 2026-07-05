<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
**ConcurrentPhaserPipeline.java**

```java
/*
 * ConcurrentPhaserPipeline.java
 *
 * A self‑contained Java program that implements a three‑stage processing pipeline
 * coordinated with a java.util.concurrent.Phaser.  The pipeline ingests tasks
 * from a thread‑safe queue, transforms each task in parallel using a configurable
 * number of worker threads, and aggregates the results into a thread‑safe
 * collection.  All stages are synchronised by a Phaser so that every worker
 * finishes a stage before any worker proceeds to the next stage.
 *
 * The class exposes the following API:
 *
 *   - submitTask(T task)   –   add a task to the pipeline
 *   - start(int workerCount) – start the pipeline with the specified number of workers
 *   - shutdown()           – request graceful shutdown of the pipeline
 *   - getResults()         – retrieve an unmodifiable view of all aggregated results
 *
 * The implementation deliberately keeps the design simple yet fully
 * thread‑safe.  It uses a BlockingQueue for the ingestion stage, a Phaser for
 * stage coordination, and a ConcurrentLinkedQueue for the aggregation stage.
 *
 * The accompanying JUnit‑5 test suite (defined in the same file) verifies
 * functional correctness, correct phase progression, and stresses the pipeline
 * with 200 concurrent producers submitting 10 000 tasks.
 *
 * Author:   OpenAI ChatGPT
 * Date:     2026‑07‑05
 */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * A three‑stage concurrent pipeline that uses a {@link Phaser} to coordinate
 * the stages.  The pipeline is generic over the task type {@code T} and the
 * result type {@code R}.
 *
 * <p>Concurrency strategy</p>
 * <ul>
 *   <li>Stage 1 – Ingestion: a {@link BlockingQueue} holds tasks submitted
 *   by external producers.  Each worker thread pulls a task from the queue
 *   using {@code take()}, thereby blocking until a task is available.</li>
 *
 *   <li>Stage 2 – Transformation: a user supplied {@link Function} is applied
 *   to the task.  The transformation is performed by the worker thread that
 *   ingested the task.</li>
 *
 *   <li>Stage 3 – Aggregation: the transformed result is added to a
 *   {@link ConcurrentLinkedQueue}.  The queue is thread‑safe, so no
 *   additional synchronisation is required.</li>
 *
 *   <li>Stage coordination: a {@link Phaser} is used to make sure that
 *   all workers finish a stage before any worker proceeds to the next
 *   stage.  Each worker calls {@code phaser.arriveAndAwaitAdvance()}
 *   after completing a stage.  The Phaser is dynamically registered with
 *   each worker thread as it starts and deregistered when the worker
 *   terminates.</li>
 *
 *   <li>Shutdown: a graceful shutdown is performed by submitting a
 *   special sentinel object (the "poison pill") once per worker, after
 *   which the {@link ExecutorService} is shut down.  The sentinel is a
 *   unique object that will never be supplied by a user task.</li>
 * </ul>
 *
 * <p>Thread safety guarantees</p>
 * <ul>
 *   <li>All shared data structures ({@code BlockingQueue} and
 *   {@code ConcurrentLinkedQueue}) are thread‑safe.</li>
 *   <li>All mutable state that can be accessed concurrently is
 *   {@link Atomic} or {@link volatile}.</li>
 *   <li>The Phaser ensures that workers do not interleave stages,
 *   eliminating race conditions between ingestion, transformation,
 *   and aggregation.</li>
 * </ul>
 *
 * @param <T> the type of tasks accepted by the pipeline
 * @param <R> the type of results produced by the pipeline
 */
public class ConcurrentPhaserPipeline<T, R> {

    /** Function that transforms a task into a result. */
    private final Function<T, R> transformer;

    /** Queue that holds tasks submitted to the pipeline. */
    private final BlockingQueue<Object> taskQueue = new LinkedBlockingQueue<>();

    /** Thread‑safe collection of results. */
    private final ConcurrentLinkedQueue<R> results = new ConcurrentLinkedQueue<>();

    /** Phaser that synchronises stages across workers.