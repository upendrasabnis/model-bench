<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
```java
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A multi‑stage processing pipeline coordinated by a {@link Phaser}.
 *
 * <h2>Pipeline Stages</h2>
 * <ol>
 *   <li><b>Ingestion</b> – Tasks are submitted via {@link #submit(Object)} into a thread‑safe
 *       {@link BlockingQueue}.</li>
 *   <li><b>Transformation</b> – A configurable pool of worker threads repeatedly takes tasks
 *       from the queue, applies a user‑supplied {@link Function}, and places the result into a
 *       thread‑safe {@link ConcurrentLinkedQueue}.</li>
 *   <li><b>Aggregation</b> – Results are already stored in a thread‑safe collection; {@link #getResults()}
 *       returns a snapshot.</li>
 * </ol>
 *
 * <h2>Concurrency Strategy</h2>
 * <ul>
 *   <li>A single {@link Phaser} orchestrates the <em>graceful shutdown</em> sequence.
 *       The main thread (the one that calls {@link #shutdown()}) is registered initially registered.
 *       Each worker registers itself at start‑up ({@link Phaser#register()}) and deregisters
 *       when it finishes processing its poison‑pill ({@link Phaser#arriveAndDeregister()}).
 *   </li>
 *   <li>The {@link Phaser} guarantees that {@link #shutdown()} blocks until <b>all</b> workers
 *       have completed the transformation stage.  This is achieved by the main thread calling
 *       {@link Phaser#arriveAndAwaitAdvance()} after injecting poison pills.  Workers call
 *       {@link Phaser#arriveAndDeregister()} upon seeing the poison pill.  The phase advances
 *       only when every registered party (main + all workers) has arrived, ensuring no worker
 *       is left mid‑transformation.</li>
 *   <li>Dynamic registration/deregistration is demonstrated by workers joining the phaser at
 *       {@link #start()} and leaving at shutdown.  No other threads register with the phaser,
 *       keeping the coordination simple and deadlock‑free.</li>
 *   <li>Thread‑safety of data structures:
 *       <ul>
 *         <li>{@link LinkedBlockingQueue} for task ingestion – safe for concurrent producers
 *             and multiple consumers.</li>
 *         <li>{@link ConcurrentLinkedQueue} for results – lock‑free, safe for concurrent
 *             adds from workers and iteration for snapshots.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h2>Potential Pitfalls & Mitigations</h2>
 * <ul>
 *   <li><b>Poison‑pill count mismatch</b> – Exactly {@code workerCount} poison pills are
 *       enqueued; each worker consumes one and exits.  Extra pills would cause workers to
 *       terminate prematurely; fewer pills would leave workers blocked forever.</li>
 *   <li><b>Phaser party count changes</b> – Workers deregister <em>upon arrival</em> at the
 *       shutdown phase.  The phaser semantics guarantee that the phase advances when the
 *       last registered party arrives, even if some parties deregister during that arrival.
 *       The main thread never deregisters, so it always participates in the barrier.</li>
 *   <li><b>Submission after shutdown</b> – {@link #submit(Object)} throws
 *       {@link IllegalStateException} once {@link #shutdown()} has been invoked.</li>
 *   <li><b>Result snapshot consistency</b> – {@link #getResults()} copies the concurrent
 *       queue into a new {@link ArrayList}.  The copy reflects a consistent point‑in‑time
 *       view because {@link ConcurrentLinkedQueue#toArray()} (used internally by the
 *       collection constructor) is weakly consistent but sufficient for post‑shutdown
 *       inspection where no further mutations occur.</li>
 * </ul>
 *
 * @param <T> the type of input tasks
 * @param <R> the type of transformed results
 */
public class ConcurrentPhaserPipeline<T, R> {

    private final int workerCount;
    private final Function<T, R> transformer;
    private final BlockingQueue<T> taskQueue;
    private final ConcurrentLinkedQueue<R> resultQueue;
    private final Phaser phaser;
    private final List<Thread> workers;
    private volatile boolean acceptingTasks = true;
    private volatile boolean shutdown = false;

    /**
     * Creates a new pipeline.
     *
     * @param workerCount number of parallel transformation threads (must be &gt; 0)
     * @param transformer function applied to each task; must be thread‑safe / stateless
     * @throws IllegalArgumentException if {@code workerCount <= 0} or {@code transformer == null}
     */
    public ConcurrentPhaserPipeline(int workerCount, Function<T, R> transformer) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be > 0");
        }
        if (transformer == null) {
            throw new IllegalArgumentException("transformer must not be null");
        }
        this.workerCount = workerCount;
        this.transformer = transformer;
        this.taskQueue = new LinkedBlockingQueue<>();
        this.resultQueue = new ConcurrentLinkedQueue<>();
        this.phaser = new Phaser(1); // main thread registers as coordinator
        this.workers = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            workers.add(new Thread(new Worker(), "Pipeline-Worker-" + i));
        }
    }

    /**
     * Starts the worker threads.  Each worker registers with the internal {@link Phaser}
     * so that {@link #shutdown()} can wait for their completion.
     *
     * @throws IllegalStateException if called more than once
     */
    public synchronized void start() {
        if (!workers.isEmpty() && workers.get(0).isAlive()) {
            throw new IllegalStateException("Pipeline already started");
        }
        for (Thread w : workers) {
            phaser.register(); // each worker becomes a party for the shutdown barrier
            w.start();
        }
    }

    /**
     * Submits a task for processing.  The task is enqueued for the transformation stage.
     *
     * @param task the task to process; must not be {@code null}
     * @throws IllegalStateException if the pipeline has been shut down or is not accepting tasks
     * @throws NullPointerException  if {@code task == null}
     */
    public void submit(T task) {
        if (task == null) {
            throw new NullPointerException("task must not be null");
        }
        if (!acceptingTasks) {
            throw new IllegalStateException("Pipeline is not accepting tasks (shutdown initiated)");
        }
        // offer never blocks for LinkedBlockingQueue (unbounded), but we use it for clarity
        boolean offered = taskQueue.offer(task);
        if (!offered) {
            // Should never happen with unbounded queue, but guard anyway
            throw new IllegalStateException("Task queue unexpectedly full");
        }
    }

    /**
     * Initiates graceful shutdown:
     * <ol>
     *   <li>Stops accepting new tasks.</li>
     *   <li>Enqueues one poison pill per worker.</li>
     *   <li>Waits (via the {@link Phaser}) until all workers have finished their current
     *       transformation and deregistered.</li>
     * </ol>
     * After this method returns, the pipeline is fully stopped and {@link #getResults()}
     * can be safely called.
     *
     * @throws IllegalStateException if the pipeline was not started or already shut down
     */
    public void shutdown() {
        if (shutdown) {
            throw new IllegalStateException("Pipeline already shut down");
        }
        if (workers.isEmpty() || !workers.get(0).isAlive()) {
            throw new IllegalStateException("Pipeline not started");
        }

        acceptingTasks = false;

        // Inject poison pills – one per worker
        for (int i = 0; i < workerCount; i++) {
            taskQueue.offer(null);
        }

        // Main thread arrives at the current phase (0) and waits for all workers to arrive.
        // Workers call arriveAndDeregister() when they consume their poison pill.
        phaser.arriveAndAwaitAdvance(); // blocks until all parties have arrived

        shutdown = true;
    }

    /**
     * Returns an immutable snapshot of all results produced so far.
     * This method is thread‑safe and may be called at any time, but is most useful after
     * {@link #shutdown()} when no further results will be added.
     *
     * @return a list containing all results in the order they were added (not guaranteed
     *         to reflect submission order due to parallel transformation)
     */
    public List<R> getResults() {
        // Copy to isolate from concurrent modifications (though after shutdown there are none)
        return new ArrayList<>(resultQueue);
    }

    /**
     * Worker runnable: continuously takes tasks from the queue, transforms them,
     * and stores results.  Exits upon receiving a {@code null} poison pill,
     * then deregisters from the phaser.
     */
    private class Worker implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    T task = taskQueue.take(); // blocks until a task is available
                    if (task == null) { // poison pill
                        // Signal completion of transformation stage and leave the phaser
                        phaser.arriveAndDeregister();
                        break;
                    }
                    R result = transformer.apply(task