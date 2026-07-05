<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
```java
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>ConcurrentPhaserPipeline</h1>
 * <p>
 * This class implements a three-stage processing pipeline coordinated by a {@link java.util.concurrent.Phaser}.
 * The pipeline architecture is as follows:
 * <ol>
 *     <li><b>Ingestion:</b> Tasks are pulled from a {@link LinkedBlockingQueue}.</li>
 *     <li><b>Transformation:</b> Tasks are processed in parallel by a pool of worker threads.</li>
 *     <li><b>Aggregation:</b> Results are collected into a thread-safe {@link ConcurrentLinkedQueue}.</li>
 * </ol>
 *
 * <h3>Concurrency Strategy</h3>
 * <p>
 * The {@code Phaser} acts as a reusable synchronization barrier. Unlike a {@code CyclicBarrier}, 
 * the Phaser allows for a dynamic number of parties. Each worker thread registers itself with 
 * the phaser upon startup. The pipeline advances through phases (0: Ingestion, 1: Transformation, 
 * 2: Aggregation) only when all registered parties have arrived at the barrier.
 * </p>
 *
 * <h3>Thread-Safety Guarantees</h3>
 * <ul>
 *     <li><b>Task Queue:</b> A {@link LinkedBlockingQueue} ensures thread-safe handoff from producers to the pipeline.</li>
 *     <li><b>Result Collection:</b> A {@link ConcurrentLinkedQueue} ensures lock-free, thread-safe aggregation.</li>
 *     <li><b>State Management:</b> An {@link AtomicBoolean} manages the lifecycle (running/stopped) to prevent 
 *     submissions after shutdown.</li>
 *     <li><b>Phase Coordination:</b> The {@code Phaser} prevents "race-to-the-next-stage" bugs by ensuring 
 *     all workers complete the current stage's logic before any worker starts the next.</li>
 * </ul>
 *
 * <h3>Potential Pitfalls</h3>
 * <ul>
 *     <li><b>Deadlocks:</b> If a worker thread crashes without calling {@code arriveAndDeregister()}, 
 *     the phaser will hang. This is mitigated by wrapping worker logic in try-finally blocks.</li>
 *     <li><b>Memory Visibility:</b> The Phaser's arrival/advance mechanism creates a happens-before 
 *     relationship, ensuring that modifications made in one phase are visible to all threads in the next.</li>
 * </ul>
 */
public class ConcurrentPhaserPipeline<T, R> {

    private final BlockingQueue<T> inputQueue = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<R> results = new ConcurrentLinkedQueue<>();
    private final Phaser phaser = new Phaser(1); // Register the main controller thread
    private final int workerCount;
    private final java.util.function.Function<T, R> transformer;
    private final ExecutorService executor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * @param workerCount Number of worker threads to use for processing.
     * @param transformer The transformation function applied to each task.
     */
    public ConcurrentPhaserPipeline(int workerCount, java.util.function.Function<T, R> transformer) {
        this.workerCount = workerCount;
        this.transformer = transformer;
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    /**
     * Submits a task to the pipeline.
     * @param task The item to process.
     * @throws IllegalStateException if the pipeline has been shut down.
     */
    public void submit(T task) {
        if (!isRunning.get() && phaser.getPhase() != 0) {
            throw new IllegalStateException("Pipeline is not running or has been shut down");
        }
        inputQueue.offer(task);
    }

    /**
     * Starts the pipeline. This method blocks until all submitted tasks are processed 
     * through all three stages.
     */
    public void start() {
        isRunning.set(true);
        
        // Create a snapshot of current tasks to process in this batch
        List<T> batch = new ArrayList<>();
        inputQueue.drainTo(batch);

        if (batch.isEmpty()) return;

        // Partition the batch among workers
        int tasksPerWorker = (int) Math.ceil((double) batch.size() / workerCount);
        
        for (int i = 0; i < workerCount; i++) {
            int startIdx = i * tasksPerWorker;
            int endIdx = Math.min(startIdx + tasksPerWorker, batch.size());
            List<T> workerBatch = batch.subList(startIdx, endIdx);

            executor.submit(new PipelineWorker(workerBatch));
        }

        // The controller thread waits for all phases to complete
        // Phase 0 -> 1 (Ingestion/Setup), 1 -> 2 (Transformation), 2 -> 3 (Aggregation)
        while (!phaser.isTerminated()) {
            phaser.arriveAndAwaitAdvance();
        }
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executor.shutdownNow();
        phaser.forceTermination();
        isRunning.set(false);
    }

    /**
     * Returns the aggregated results.
     * @return A list of processed results.
     */
    public List<R> getResults() {
        return new ArrayList<>(results);
    }

    private class PipelineWorker implements Runnable {
        private final List<T> myTasks;
        private final List<R> myResults = new ArrayList<>();

        PipelineWorker(List<T> tasks) {
            this.myTasks = tasks;
        }

        @Override
        public void run() {
            phaser.register();
            try {
                // STAGE 1: Ingestion/Preparation
                // (In this implementation, tasks are passed via constructor, 
                // but the phaser ensures all workers are ready).
                phaser.arriveAndAwaitAdvance();

                // STAGE 2: Parallel Transformation
                for (T task : myTasks) {
                    myResults.add(transformer.apply(task));
                }
                phaser.arriveAndAwaitAdvance();

                // STAGE 3: Aggregation
                results.addAll(myResults);
                phaser.arriveAndAwaitAdvance();

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                phaser.arriveAndDeregister();
            }
        }
    }

    // ============================================================================================
    // JUNIT 5 TESTS
    // ============================================================================================

    public static class PipelineTests {

        @Test
        void testFunctionalCorrectness() {
            ConcurrentPhaserPipeline<Integer, String> pipeline = 
                new ConcurrentPhaserPipeline<>(4, i -> "Val: " + i);
            
            pipeline.submit(1);
            pipeline.submit(2);
            pipeline.submit(3);
            
            pipeline.start();
            List<String> results = pipeline.getResults();
            
            Assertions.assertEquals(3, results.size());
            Assertions.assertTrue(results.contains("Val: 1"));
            Assertions.assertTrue(results.contains("Val: 2"));
            Assertions.assertTrue(results.contains("Val: 3"));
            pipeline.shutdown();
        }

        @Test
        @Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
        void stressTestConcurrentProducers() throws InterruptedException {
            int producerCount = 200;
            int tasksPerProducer = 50;
            int totalTasks = producerCount * tasksPerProducer;
            
            ConcurrentPhaserPipeline<Integer, Integer> pipeline = 
                new ConcurrentPhaserPipeline<>(16, i -> i * 2);
            
            ExecutorService producerService = Executors.newFixedThreadPool(producerCount);
            CountDownLatch latch = new CountDownLatch(producerCount);
            AtomicInteger submittedCount = new AtomicInteger(0);

            // Launch 200 concurrent producers
            for (int i = 0; i < producerCount; i++) {
                producerService.submit(() -> {
                    try {
                        for (int j = 0; j < tasksPerProducer; j++) {
                            pipeline.submit(submittedCount.getAndIncrement());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            pipeline.start();
            
            List<Integer> results = pipeline.getResults();
            
            Assertions.assertEquals(totalTasks, results.size(), "Should not lose or duplicate tasks");
            
            // Verify transformation correctness (sum of (0..N-1)*2)
            long expectedSum = 0;
            for (int i = 0; i < totalTasks; i++) expectedSum += (i * 2);
            long actualSum = results.stream().mapToLong(Integer::longValue).sum();
            
            Assertions.assertEquals(expectedSum, actualSum, "Transformation logic failed");
            
            producerService.shutdown();
            pipeline.shutdown();
        }

        @Test
        void testEmptyPipeline() {
            ConcurrentPhaserPipeline<Integer, Integer> pipeline = 
                new ConcurrentPhaserPipeline<>(2, i -> i);
            pipeline.start();
            Assertions.assertTrue(pipeline.getResults().isEmpty());
            pipeline.shutdown();
        }
    }

    /**
     * Main method to run tests if not using a JUnit runner.
     */
    public static void main(String[] args) {
        System.out.println("Running Pipeline Tests...");
        PipelineTests tests = new PipelineTests();
        try {
            tests.testFunctionalCorrectness();
            tests.stressTestConcurrentProducers();
            tests.testEmptyPipeline();
            System.out.println("All tests passed successfully!");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
```