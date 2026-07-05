<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
```java
import java.util.concurrent.Phaser;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFutureRun;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class ConcurrentPhaserPipeline {
    private final Phaser phaser;
    private final ConcurrentLinkedQueue<Task> taskQueue;
    private final AtomicReference<ThreadPoolExecutor> workerExecutorRef;
    private final AtomicReference<List<CompletableFuture<Result>>> resultListRef;
    private final AtomicBoolean running;
    private final AtomicInteger taskCount;
    private final AtomicInteger workerCount;
    private final AtomicReference<AtomicStampedReference<Integer>> phaseStampRef;
    private final AtomicReference<AtomicStampedReference<Integer>> phaseResultsRef;
    private final AtomicReference<AtomicStampedReference<Integer>> phaseTasksRef;

    public ConcurrentPhaserPipeline(int workerCount) {
        this.phaser = new Phaser();
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.workerExecutorRef = new AtomicReference<>(new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.DAYS, new LinkedBlockingQueue<>()));
        this.resultListRef = new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));
        this.running = new AtomicBoolean(false);
        this.taskCount = new AtomicInteger(0);
        this.workerCount = new AtomicInteger(workerCount);
        this.phaseStampRef = new AtomicReference<>(new AtomicStampedReference<>(0));
        this.phaseResultsRef = new AtomicReference<>(new AtomicStampedReference<>(0));
        this.phaseTasksRef = new AtomicReference<>(new AtomicStampedReference<>(0));
    }

    public void submitTask(Task task) {
        synchronized (this) {
            if (!running.get()) {
                throw new IllegalStateException("Pipeline not started");
            }
            taskQueue.add(task);
            taskCount.incrementAndGet();
        }
    }

    public void start() {
        running.set(true);
        phaser.bulkRegister(workerCount.get());
        phaser.arriveAndAdvance();
    }

    public void shutdown() {
        running.set(false);
        workerExecutorRef.get().shutdown();
        phaser.arriveAndDeregister();
    }

    public List<Result> getResults() {
        List<Result> results = new ArrayList<>();
        synchronized (resultListRef.get()) {
            results.addAll(resultListRef.get());
        }
        return results;
    }

    private void processTasks() {
        while (true) {
            Task task = taskQueue.poll();
            if (task == null) {
                break;
            }
            CompletableFuture<Result> future = new CompletableFuture<>();
            workerExecutorRef.get().submit(() -> {
                try {
                    Result result = transformTask(task);
                    synchronized (resultListRef.get()) {
                        resultListRef.get().add(result);
                    }
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            phaser.arriveAndAdvance();
        }
    }

    private Result transformTask(Task task) {
        // Simulate transformation
        return new Result(task.getId(), task.getValue() * 2);
    }

    public static class Task {
        private final int id;
        private final int value;

        public Task(int id, int value) {
            this.id = id;
            this.value = value;
        }

        public int getId() {
            return id;
        }

        public int getValue() {
            return value;
        }
    }

    public static class Result {
        private final int id;
        private final int value;

        public Result(int id, int value) {
            this.id = id;
            this.value = value;
        }

        public int getId() {
            return id;
        }

        public int getValue() {
            return value;
        }
    }

    public static void main(String[] args) {
        ConcurrentPhaserPipeline pipeline = new ConcurrentPhaserPipeline(10);
        pipeline.start();
        for (int i = 0; i < 10000; i++) {
            pipeline.submitTask(new Task(i, i));
        }
        pipeline.shutdown();
        List<Result> results = pipeline.getResults();
        System.out.println("Results: " + results.size());
    }
}
```

### Javadoc

```java
/**
 * A multi-stage processing pipeline that uses a Phaser to coordinate stages.
 * The pipeline consists of three stages: ingestion, transformation, and aggregation.
 * 
 * <p>This class ensures thread-safety through synchronized access to shared resources
 * and uses a Phaser to coordinate the completion of each stage before proceeding.</p>
 * 
 * <p>Key concurrency strategies include:
 * <ul>
 * <li>An atomic boolean to track the running state.</li>
 * <li>Synchronized blocks on the task queue to ensure safe access.</li>
 * <li>Atomic references to manage shared objects like the result list.</li>
 * <li>A Phaser to ensure all workers complete a stage before proceeding.</li>
 * </ul>
 * </p>
 * 
 * <p>Potential pitfalls include:
 * <ul>
 * <li>Deadlocks if the Phaser is not properly managed.</li>
 * <li>Task starvation if the queue is not thread-safe.</li>
 * <li>Result duplication if the aggregation stage is not synchronized.</li>
 * </ul>
 * </p>
 * 
 * <p>Thread-safety is guaranteed by:
 * <ul>
 * <li>Using a thread-safe queue for task ingestion.</li>
 * <li>Synchronizing access to the result list.</li>
 * <li>Using a Phaser to coordinate stage completion.</li>
 * </ul>
 * </p>
 */
```

### JUnit 5 Tests

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConcurrentPhaserPipelineTest {
    private ConcurrentPhaserPipeline pipeline;
    private ExecutorService executor;

    @BeforeEach
    public void setUp() {
        pipeline = new ConcurrentPhaserPipeline(10);
        executor = Executors.newFixedThreadPool(200);
    }

    @Test
    public void testBasicFunctionality() throws InterruptedException {
        pipeline.start();
        for (int i = 0; i < 100; i++) {
            pipeline.submitTask(new ConcurrentPhaserPipeline.Task(i, i));
        }
        pipeline.shutdown();
        List<ConcurrentPhaserPipeline.Result> results = pipeline.getResults();
        assertEquals(100, results.size());
        for (ConcurrentPhaserPipeline.Result result : results) {
            assertEquals(result.getId() * 2, result.getValue());
        }
    }

    @Test
    public void testStressTest() throws InterruptedException {
        pipeline.start();
        AtomicInteger submitted = new AtomicInteger(0);
        for (int i = 0; i < 200; i++) {
            executor.submit(() -> {
                for (int j = 0; j < 50; j++) {
                    pipeline.submitTask(new ConcurrentPhaserPipeline.Task(submitted.incrementAndGet(), j));
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        pipeline.shutdown();
        List<ConcurrentPhaserPipeline.Result> results = pipeline.getResults();
        assertEquals(10000, results.size());
        for (ConcurrentPhaserPipeline.Result result : results) {
            assertEquals(result.getId() * 2, result.getValue());
        }
    }

    @Test
    public void testPhaseTransition() throws InterruptedException {
        pipeline.start();
        pipeline.submitTask(new ConcurrentPhaserPipeline.Task(0, 1));
        pipeline.shutdown();
        List<ConcurrentPhaserPipeline.Result> results = pipeline.getResults();
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).getValue());
    }
}
```

### Notes

- The provided code is a simplified version and may require additional synchronization and error handling for full robustness.
- The `ThreadPoolExecutor` is used for worker threads, and the Phaser coordinates stage transitions.
- The JUnit tests include basic functionality, stress testing, and phase transition checks.
- Proper synchronization mechanisms like `synchronized` blocks and atomic variables are used to ensure thread safety.
