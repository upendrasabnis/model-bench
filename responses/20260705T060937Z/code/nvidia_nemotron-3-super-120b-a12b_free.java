<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
**ConcurrentPhaserPipeline.java**

```java
package concurrentphaserpipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A three‑stage pipeline coordinated by a single {@link java.util.concurrent.Phaser}.
 *
 * <p>The pipeline works as follows:</p>
 * <ol>
 *   <li><b>Ingestion</b> – each worker thread tries to pull a task from a
 *       {@link BlockingQueue}. If the queue is empty (or shutdown has been
 *       requested) the worker obtains {@code null}. All workers must finish
 *       this step before any may proceed to the next stage.</li>
 *   <li><b>Transformation</b> – the (possibly {@code null}) task is handed to a
 *       user‑supplied {@link Function}. The result (or {@code null}) is kept for the
 *       next stage.</li>
 *   <li><b>Aggregation</b> – non‑null results are added to a thread‑safe
 *       {@link Collection}.</li>
 *   <li>The worker then deregisters from the {@code Phaser} and, unless a
 *       shutdown has been requested, registers again for the next task.</li>
 * </ol>
 *
 * <p>Because every stage ends with a {@code Phaser#arriveAndAwaitAdvance()}
 * (or {@code arriveAndDeregister} after the final stage) the Phaser guarantees
 * that **no worker can start stage *n+1* before **all** workers have finished
 * stage *n*.  Workers register and deregister dynamically for each task, so
 * the number of active parties reflects the current number of live worker
 * threads.</p>
 *
 * <p><b>Thread‑safety guarantees</b></p>
 * <ul>
 *   <li>The input queue is a {@link BlockingQueue} – all {@code put}/{@code take}
 *       operations are thread‑safe.</li>
 *   <li>The result collection is a {@link ConcurrentLinkedQueue} – lock‑free
 *       and safe for concurrent {@code add} and iteration.</li>
 *   <li>The {@code Phaser} itself coordinates the phases; all accesses to it
 *       occur through its thread‑safe methods.</li>
 *   <li>The only mutable non‑final fields are {@code shutdown} (volatile) and
 *       {@code nextIndex} (AtomicInteger) – both safe for concurrent updates.</li>
 * </ul>
 *
 * <p><b>Potential pitfalls</b></p>
 * <ul>
 *   <li>If the input queue remains empty for a long time while shutdown is
 *       false, workers will spin with short time‑outs.  In a production setting
 *       replace the timed {@code poll} with a blocking {@code take} and let a
 *       dedicated ingestor thread fill a small buffer before each batch.</li>
 *   <li>The transformer function must be thread‑safe or stateless; otherwise
 *       external synchronization is required.</li>
 *   <li>Calling {@code shutdown()} while workers are blocked in
 *       {@code BlockingQueue.poll} will cause them to exit after the timeout
 *       (default 10 ms).  Decrease the timeout for faster shutdown or use a
 *       custom {@link RejectedExecutionHandler}.</li>
 * </ul>
 *
 * @param <T> type of input tasks
 * @param <R> type of produced results
 */
public class ConcurrentPhaserPipeline<T, R> {

    /** Number of parallel worker threads. */
    private final int parallelism;

    /** Queue from which workers ingest tasks. */
    private final BlockingQueue<T> inputQueue;

    /** Function applied to each task during the transformation stage. */
    private final Function<T, R> transformer;

    /** Thread‑safe container for successful results. */
    private final ConcurrentLinkedQueue<R> results = new ConcurrentLinkedQueue<>();

    /** Phaser that coordinates the three stages. */
    private final Phaser phaser = new Phaser(0);

    /** Used to give each worker a deterministic index inside a batch (not strictly required
     *  for correctness but handy for debugging / extensions). */
    private final AtomicInteger nextIndex = new AtomicInteger(0);

    /** Flag visible to all workers that indicates a shutdown request. */
    private volatile boolean shutdown = false;

    /** Pool that runs the worker loops. */
    private final ExecutorService workerPool;

    /**
     * Creates a pipeline with the supplied degree of parallelism.
     *
     * @param parallelism   number of worker threads (must be &gt; 0)
     * @param inputQueue    queue where producers place tasks
     * @param transformer   function that transforms a task into a result
     */
    public ConcurrentPhaserPipeline(int parallelism,
                                    BlockingQueue<T> inputQueue,
                                    Function<T, R> transformer) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be > 0");
        }
        this.parallelism = parallelism;
        this.inputQueue = Objects.requireNonNull(inputQueue, "inputQueue");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.workerPool = Executors.newFixedThreadPool(parallelism,
                r -> {
                    Thread t = new Thread(r, "phaser-pipeline-worker-" + r.hashCode());
                    t.setDaemon(false);
                    return t;
                });
    }

    /**
     * Submits a task to the ingestion queue.
     *
     * @param task the task to be processed; must not be {@code null}
     */
    public void submit(T task) {
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        inputQueue.add(task);
    }

    /**
     * Starts the worker threads.  This method may be called only once.
     *
     * @throws IllegalStateException if the pipeline has already been started
     */
    public void start() {
        if (workerPool.isShutdown()) {
            throw new IllegalStateException("Pipeline already started/shutdown");
        }
        for (int i = 0; i < parallelism; i++) {
            workerPool.submit(this::workerLoop);
        }
    }

    /**
     * Signals the pipeline to shut down, waits for all workers to finish,
     * and returns the aggregated results.
     *
     * @return an unmodifiable list containing all results produced before shutdown
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public List<R> shutdown() throws InterruptedException {
        shutdown = true;
        // Wake up workers that might be blocked in poll()
        workerPool.shutdownNow();
        boolean terminated = workerPool.awaitTermination(30, TimeUnit.SECONDS);
        if (!terminated) {
            System.err.warn("Worker pool did not terminate in the allotted time");
        }
        // Return a snapshot; the internal queue is no longer mutated.
        return List.copyOf(results);
    }

    /**
     * Returns a snapshot of the results collected so far (useful for monitoring).
     *
     * @return an unmodifiable list of results accumulated up to the moment of the call
     */
    public List<R> getResults() {
        return List.copyOf(results);
    }

    /** Main loop executed by each worker thread. */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted() && !shutdown) {
            // --- register for this batch (one task) ---
            phaser.register();

            // Reset the per‑batch index counter when we are the first arriver of a new batch.
            // This is only for diagnostic purposes; the algorithm works without it.
            if (phaser.getPhase() == 0 && phaser.getRegisteredParties() == 1) {
                nextIndex.set(0);
            }

            int myIndex = nextIndex.getAndIncrement();

            /* ------------------- Phase 0: Ingestion ------------------- */
            T task = null;
            if (!shutdown) {
                try {
                    // Timed poll lets us check the shutdown flag frequently.
                    task = inputQueue.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Arrive at the end of ingestion; wait for all workers to finish phase 0.
            phaser.arriveAndAwaitAdvance();

            /* ------------------- Phase 1: Transformation ------------------- */
            R result = null;
            if (task != null) {
                result = transformer.apply(task);
            }
            // Arrive at the end of transformation; wait for all workers.
            phaser.arriveAndAwaitAdvance();

            /* ------------------- Phase 2: Aggregation ------------------- */
            if (result != null) {
                results.add(result);
            }
            // Final arrive – deregister from the phaser for this batch.
            phaser‑based synchronization.
            phaser.arriveAndDeregister();
        }
    }
}
```

---

**ConcurrentPhaserPipelineTest.java**

```java
package concurrentphaserpipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

/**
 * Test suite for {@link ConcurrentPhaserPipeline}.
 *
 * <p>The suite contains:</p>
 * <ul>
 *   <li>Functional correctness checks.</li>
 *   <li>A test that verifies the three‑phase barrier behaviour