<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260705T060937Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that defines a public class named `ConcurrentPhaserPipeline` implementing a multi‑stage processing pipeline coordinated with a `java.util.concurrent.Phaser`. The pipeline should consist of three stages: (1) ingestion of tasks from a thread‑safe queue, (2) parallel transformation of each task using a configurable number of worker threads, and (3) aggregation of results into a thread‑safe collection. Use a `Phaser` to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish. The class must expose methods to submit tasks, start the pipeline, gracefully shut down, and retrieve aggregated results. Include comprehensive Javadoc that explains the concurrency strategy, potential pitfalls, and how thread‑safety is guaranteed. Additionally, provide a full suite of JUnit 5 tests that verify functional correctness, proper phase transitions, and include a stress test that launches 200 concurrent producer threads submitting 10 000 tasks while the pipeline processes them, asserting no lost or duplicated results and no deadlocks.
<!-- RESPONSE -->
We need to produce a single self-contained Java program that defines a public class ConcurrentPhaserPipeline implementing multi-stage processing pipeline with Phaser, three stages: ingestion of tasks from thread-safe queue, parallel transformation using configurable workers, aggregation into thread-safe collection. Expose methods: submit tasks, start pipeline, graceful shutdown, retrieve aggregated results. Include comprehensive Javadoc explaining concurrency strategy, pitfalls, thread-safety. Also provide a full suite of JUnit 5 tests verifying functional correctness, phase transitions, and stress test with 200 producer threads submitting 10,000 tasks while pipeline processes them, asserting no lost or duplicated results and no deadlocks.

We need to output a single Java file that includes both the class and the JUnit tests. However JUnit tests are separate classes; but the instruction says "single, self-contained Java program that defines a public class named ConcurrentPhaserPipeline implementing ...". It also asks "Additionally, provide a full suite of JUnit 5 tests". So perhaps we can include both the class and test classes within the same file? In Java, you can have multiple top-level classes in separate files, but you can also have multiple nested static classes in same file. However, you cannot have two public top-level classes in same file. So we need to put the main class and the test classes in separate files? But they ask "single, self-contained Java program". Could be a single .java file that includes the class and the test class as a non-public inner class? But JUnit tests usually are separate classes annotated with @Test. They could be placed as static nested classes inside the same file, but they would need to be non-public? Actually you can have multiple public top-level classes in separate files, but not in same file. However you can have a public class and a separate test class in the same file if the test class is package-private? But JUnit expects public test class. So perhaps they want a single Java file containing the class and the test class as a separate public class? That would violate Java file naming rule: only one public class per file, and file name must match class name. So we cannot have both public classes in same file. However we can provide a single Java source file that contains the ConcurrentPhaserPipeline class and then separate test classes in separate files. But they ask "single, self-contained Java program". Might be interpreted as a single source file that includes everything, maybe using the test class as a main method demonstration? But they specifically request JUnit 5 tests. Could embed them as inner classes but not public, and use a main method to run them? That would not be standard JUnit test runner.

Given constraints, maybe we can provide a single Java file that contains the ConcurrentPhaserPipeline class and then a separate test class in the same file but with package-private visibility, and then we can compile with JUnit? But JUnit requires public test class? Actually you can have a non-public test class; it can still be discovered by JUnit if you annotate with @Test? I think JUnit 5 requires the test class to be public? Not strictly; it can be non-public, but typical usage is public. However the rule about one public class per file would still be violated if we have two public classes. So we could make the test class package-private (default) and keep the main class public. That would satisfy one public class per file. The test class would be package-private, but still can be run via reflection? JUnit can discover non-public test classes? I think JUnit 5 can discover non-public test classes as long as they are annotated with @Test and have public static void? Actually JUnit 5's test discovery scans for classes with annotation @Test and methods annotated @Test, and they can be non-public. So we can have a package-private test class in same file.

Thus we can produce a single .java file with:

- public class ConcurrentPhaserPipeline { ... }

- package-private class ConcurrentPhaserPipelineTest { ... } with @Test methods.

But the file name must match the public class name: ConcurrentPhaserPipeline.java. So we can have only one public class per file, which is ConcurrentPhaserPipeline. The test class must be package-private (default) and placed after the public class. That's okay.

Thus we can produce a single Java source file that contains both the pipeline class and the test class (non-public). That satisfies "single, self-contained Java program". Provide Javadoc, etc.

We need to implement the pipeline:

- Ingestion stage: tasks from a thread-safe queue (maybe BlockingQueue<Runnable> or BlockingQueue<Task>).

- Workers: configurable number of worker threads that pull tasks from queue, transform them, then go to next phase.

- Use Phaser to coordinate phases: all workers must finish stage 1 before entering stage 2, etc. But we have three stages: ingestion (tasks submission), transformation (worker processing), aggregation (collecting results). We can model each stage as a phase in Phaser. Workers register at startup, then proceed through phases.

Simplify: Use a Phaser with parties = number of worker threads + maybe one coordinator? Actually we need to ensure that all workers complete a stage before any may proceed to next. That's exactly what Phaser does: when a thread calls arrived(), it signals that it has reached this phase; when all parties have arrived, the phase advances and continues.

Implementation plan:

- Constructor: accept int numWorkers, maybe a Supplier<Result> for aggregation? Actually we need a thread-safe collection to aggregate results. Could be a ConcurrentLinkedQueue<T> or a List wrapped in Collections.synchronizedList.

- Provide method submitTask(Task<?> task) to enqueue tasks.

- start() method: creates worker threads, each worker loops: wait for phase 1 (ingestion) maybe not needed; Actually ingestion is just submitting tasks to queue; workers start processing tasks when they are available. But we need to coordinate phases: maybe we have three phases: PHASE_INGEST, PHASE_TRANSFORM, PHASE_AGGREGATE. The Phaser is used to ensure that all workers finish each phase before moving to next. For ingestion, maybe we just let producers submit tasks and then signal that ingestion phase is done? But we need to coordinate workers: they should not start transforming until all tasks have been enqueued? The requirement: "Use a Phaser to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish." So we can have workers register with Phaser at startup; they then go through phases: Stage 1: take a task from queue (maybe they wait until a task is available). Stage 2: transform the task (maybe compute result). Stage 3: aggregate result into thread-safe collection.

But we also need to handle dynamic registration/deregistration: workers can start and finish; we can allow workers to arrive at each phase and when all have arrived, the phase proceeds. When a worker finishes its work (maybe after aggregation), it can deregister (phaser.arrivedAndDeregister). This allows the number of parties to change.

Simplify: We'll have a fixed set of worker threads that are created at start() and never deregister; but the requirement says handling dynamic registration/deregistration of threads as they start and finish. So we can allow workers to register at start, and when they finish processing all tasks they can deregister. But we need to ensure that the Phaser works with a variable number of parties. We'll use a Phaser with an initial party count equal to number of workers, but we can also allow new threads to register later? Actually Phaser's getRegisteredParties() returns current parties; you can call arriveAndRegister to add parties dynamically. But we need to handle deregister as they finish. So we can have each worker thread loop until a shutdown flag is set; each iteration they go through phases: they take a task, transform, aggregate; after aggregation they may deregister if no more tasks remain.

But we need to ensure that all workers complete a stage before any may proceed to next. That can be done by using a barrier at the end of each stage: after finishing stage 1 (maybe after taking a task?), they call phaser.arriveAndAwaitAdvance(). This will block until all parties have arrived. Similarly for stage 2 and stage 3.

Implementation details:

- Use a BlockingQueue<Task<?>> workQueue = new LinkedBlockingQueue<>();

- SubmitTask(task) -> workQueue.offer(task).

- startPipeline(): create worker threads (numWorkers). Each worker thread:

   - Register with phaser (phaser.register()).

   - Loop while not shutdown:

        // Stage 1: Ingestion? Actually ingestion is done by producers; workers just take tasks.

        // But we can treat stage 1 as "acquire task". They will take a task from queue, maybe blocking.

        // They then call phaser.arriveAndAwaitAdvance() to signal they have a task ready? But we need to ensure all workers have taken tasks before moving to transformation.

        // Simpler: We can have a two-phase approach: Stage 1: Workers wait until a flag indicates that ingestion is complete? Hmm.

        // Perhaps we can use Phaser to coordinate each stage of processing a task: Stage 1: Workers take a task (they may block until one is available). Stage 2: Transform (do some work). Stage 3: Aggregate result.

        // At the end of each stage, they call phaser.arriveAndAwaitAdvance().

        // However, we need to ensure that all workers finish Stage 1 before any start Stage 2. That's exactly what arriveAndAwaitAdvance does: they all wait at the barrier until all have arrived.

        // So we can structure each iteration as:

            // Stage 1: take a task (may block)
            Task<?> task = workQueue.take(); // blocking

            // Stage 2: transform
            Object result = transform(task);

            // Stage 3: aggregate
            resultsCollection.add(result);

            // Then they call phaser.arriveAndAwaitAdvance() to indicate they have completed the iteration for this task? But we want to coordinate across all workers for each stage globally, not per task. Actually we need to ensure that all workers complete stage 1 (i.e., have taken a task) before any move to stage 2. That could be done by having a global barrier per stage across all workers, not per iteration. So we could have a loop that processes tasks until a sentinel indicates shutdown. But the barrier would be per iteration, which would cause all workers to wait after each task, which is not desired.

        // Better: Use Phaser to coordinate phases of the pipeline overall: Stage 1: All workers start and register; then they go to phase 1 (maybe just waiting for tasks). But we need to ensure that all workers have entered stage 1 before any proceed to stage 2. That can be done by having a "start signal" that all workers wait on, then release them. But Phaser can be used as a barrier that all workers must arrive before proceeding. So we can have each worker call phaser.arrive() at the beginning of stage 1, then do some work (like take a task), then call phaser.arriveAndAwaitAdvance() to move to stage 2. However, we need to ensure that all workers have called arrive() before any can call arriveAndAwaitAdvance(). That's guaranteed because arrive() just increments count; arriveAndAwaitAdvance() will wait until all have called arrive() (i.e., all parties have arrived). But if some workers call arriveAndAwaitAdvance() before others have called arrive(), they will wait. So we can design:

            // Stage 1: Each worker calls phaser.arrive() to indicate they are ready for stage 1.
            // Then they perform ingestion (maybe just wait for tasks) but we can treat ingestion as done before moving to stage 2.

            // Actually we can have a "phase" concept: Stage 1: All workers are ready (they have arrived). Then they proceed to process tasks (which includes transformation and aggregation). But we need to coordinate that all workers finish stage 1 before any start stage 2. That could be done by having a barrier at the start of stage 2: after all have arrived, the barrier is released, and then they can start processing tasks. However, we also need to coordinate that all workers finish stage 2 before moving to stage 3. That could be done by having them call arriveAndAwaitAdvance() at the end of stage 2, which will block until all have arrived, then release to stage 3.

        // So we can structure each worker's run as:

            // Register with phaser at startup (phaser.register())
            // Stage 1: arrive() (maybe just to synchronize start)
            // Then loop:
                // Take task (if any)
                // Transform
                // Aggregate
                // Then call phaser.arriveAndAwaitAdvance() to indicate completion of this iteration's stage 2? Actually we need to coordinate per stage globally across all workers, not per iteration.

        // Perhaps we can separate the pipeline into three distinct phases that happen once: ingestion phase (tasks submitted), transformation phase (workers process tasks), aggregation phase (results collected). The Phaser can be used to ensure that all workers finish ingestion (i.e., have taken all tasks?) before moving to transformation, and then all finish transformation before moving to aggregation. But tasks may be ongoing; we might need to use a more dynamic approach.

Given complexity, perhaps we can simplify: Use a Phaser with parties = number of worker threads. The pipeline will have three phases that are executed once: Phase 0: All workers register and wait at barrier (arrive()). Then we signal that ingestion is done (maybe by submitting a special "end-of-ingestion" marker). Then all workers proceed to transformation (they process tasks until a shutdown flag). Then they call arriveAndAwaitAdvance() to move to aggregation phase. Then they aggregate results. Then they call arriveAndAwaitAdvance() again to exit.

But the requirement: "Use a Phaser to ensure that all workers complete a stage before any may proceed to the next, handling dynamic registration/deregistration of threads as they start and finish." So we need to allow threads to register/deregister dynamically. We can implement a Worker class that registers with phaser at start, and when it finishes its work (maybe after processing all tasks), it calls phaser.deregister() (or arriveAndDeregister). This will reduce the number of parties, and the barrier will adjust accordingly.

Thus we can have a method shutdown() that sets a flag and then each worker, upon noticing the flag, will deregister and exit.

Simplify further: We'll implement a basic version that meets the functional requirements, not necessarily super optimal.

Implementation outline:

- Class ConcurrentPhaserPipeline<T, R> where T is task type, R is result type.

- Fields:
   - private final BlockingQueue<T> workQueue = new LinkedBlockingQueue<>();
   - private final ConcurrentLinkedQueue<R> results = new ConcurrentLinkedQueue<>();
   - private final Phaser phaser;
   - private final int numWorkers;
   - private volatile boolean running = false;
   - private final ExecutorService executor = Executors.newFixedThreadPool(numWorkers); maybe we manage threads manually.

- Constructor: ConcurrentPhaserPipeline(int numWorkers) { this.numWorkers = numWorkers; this.phaser = new Phaser(numWorkers); }

- submitTask(T task) { workQueue.offer(task); }

- start() {
     running = true;
     for (int i=0; i<numWorkers; i++) {
         phaser.register(); // register each worker
         Thread worker = new Thread(() -> workerLoop(), "Worker-" + i);
         worker.setDaemon(true);
         worker.start();
     }
 }

- workerLoop():
    while (running || !phaser.isTerminated()) {
        // Stage 1: take a task (if any)
        T task = workQueue.poll();
        if (task == null) {
            // no tasks, maybe wait?
            // We can block until a task is available or shutdown.
            try {
                task = workQueue.take();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        // Stage 2: transform
        R result = transform(task);
        // Stage 3: aggregate
        results.add(result);
        // After completing a unit of work, we need to coordinate phase transition.
        // We'll use phaser.arriveAndAwaitAdvance() to signal completion of stage 2? Actually we need to coordinate that all workers finish stage 2 before moving to next stage.
        // Let's define three phases: PHASE_TRANSFORM, PHASE_AGGREGATE, PHASE_DONE.
        // But we need to ensure all workers complete stage 1 before moving to stage 2. However stage 1 is just taking a task; we can treat that as part of stage 1 and then call arriveAndAwaitAdvance() before transformation.
        // Actually we can structure:
            // Stage 1: take task (done)
            // Then call phaser.arriveAndAwaitAdvance() to indicate we have taken a task and are ready for stage 2.
            // But we need all workers to call arrive() before any can call arriveAndAwaitAdvance().
            // So we can have each worker call phaser.arrive() before taking a task? That would be weird.
        // Perhaps we can use the Phaser to coordinate each iteration: At the start of each iteration, all workers call arrive() to indicate they are entering the iteration (i.e., they will take a task). Then they take a task (maybe blocking). Then they transform and aggregate, then they call arriveAndAwaitAdvance() to signal they have completed the iteration and are ready for next iteration. This ensures that all workers finish the iteration (i.e., have taken a task) before any move to next iteration? Actually they call arrive() at start of iteration, then they take a task (which may block). But they might not have taken a task yet when they call arrive(); they could call arrive() before taking a task, then take a task, then do work, then call arriveAndAwaitAdvance(). However, other workers might also call arrive() after they have taken a task; but the barrier will only release when all have called arrive(). So if one worker calls arrive() and then blocks waiting for a task, others may also call arrive() and block; but they need to eventually call arriveAndAwaitAdvance() after they finish processing to move to next iteration. However, the barrier will be at arriveAndAwaitAdvance(), not at arrive(). So they could all call arrive() quickly, then each proceeds to take a task, then do work, then call arriveAndAwaitAdvance() which will wait until all have called arriveAndAwaitAdvance()? Actually arriveAndAwaitAdvance() will wait until all parties have called arriveAndAwaitAdvance()? No, it waits until all parties have called arrive() and then proceeds; but if some parties call arriveAndAwaitAdvance() before others have called arrive(), they will wait at the barrier until those others call arrive(). But arriveAndAwaitAdvance() also increments the generation counter; it will return the new generation number. So we can use it as a barrier that ensures all have reached that point.

        // Simpler: Use a two-phase barrier: At the start of each stage, all workers call phaser.arrive(); then they do the stage work; then they call phaser.arriveAndAwaitAdvance() to move to next stage. But we need to ensure that all workers have called arrive() before any can call arriveAndAwaitAdvance(). That's guaranteed because arriveAndAwaitAdvance() will wait until all have called arrive() (i.e., all parties have arrived). However, if a worker calls arriveAndAwaitAdvance() before others have called arrive(), it will block until they do. So we can structure:

            // Stage 1: all workers call phaser.arrive() to indicate they are entering stage 1.
            // Then they take a task (maybe block).
            // Then they transform and aggregate.
            // Then they call phaser.arriveAndAwaitAdvance() to indicate they have completed stage 1 and are ready for stage 2.
            // But the barrier is at arriveAndAwaitAdvance(), not at arrive(). Actually arriveAndAwaitAdvance() will wait until all parties have called arriveAndAwaitAdvance()? Let's recall: arriveAndAwaitAdvance() arrives at the phaser and then waits for all parties to reach the same generation. It returns the new generation number. So if some workers call arriveAndAwaitAdvance() while others haven't yet called arrive(), they will wait. But they also need to have called arrive() earlier? Actually the phaser maintains a count of parties that have arrived at the current generation. When a party calls arrive(), it increments the count. When a party calls arriveAndAwaitAdvance(), it first increments the count (like arrive()), then it checks if the count equals parties; if not, it waits. So it will wait until all parties have called arrive() (or arriveAndAwaitAdvance()) at the current generation. So if some call arriveAndAwaitAdvance() before others have called arrive(), they will wait. So we can use arriveAndAwaitAdvance() as the point where they wait for all to reach that stage.

        // So we can design:

            // Stage 1: all workers call phaser.arrive() to register readiness.
            // Then they take a task (maybe block).
            // Then they transform and aggregate.
            // Then they call phaser.arriveAndAwaitAdvance() to indicate they have completed stage 1 and are ready for stage 2.
            // But we need to ensure that all workers complete stage 1 before any may proceed to stage 2. That is exactly what arriveAndAwaitAdvance() does: it blocks until all parties have called it (i.e., all have reached that point). However, if a worker calls arriveAndAwaitAdvance() before others have called arrive(), it will wait. But we want all to finish stage 1 (i.e., have taken a task and transformed and aggregated) before any can proceed to stage 2. So we can have them call arriveAndAwaitAdvance() after they finish stage 1. But then the barrier will be at that point, and all must reach it before proceeding. That ensures that all finish stage 1 before any can start stage 2? Actually they all call arriveAndAwaitAdvance() after finishing stage 1; they will block until all have called it, which means they all must finish stage 1 before any can proceed beyond that point (i.e., after the barrier). So that satisfies the requirement.

        // However, we also need to coordinate stage transitions: after all have completed stage 1, they can proceed to stage 2 (maybe transformation). But we only have one barrier; we need multiple barriers for each stage. We can use the same phaser with multiple generations: each call to arriveAndAwaitAdvance() moves to the next generation, which corresponds to moving to the next stage. So we can have three stages: Stage 1: ingestion (taking tasks) - we can treat that as part of stage 1; Stage 2: transformation; Stage 3: aggregation. But we need to ensure that all workers finish stage 1 before any start stage 2. So we can have them call arriveAndAwaitAdvance() after stage 1, which moves to generation 1 (i.e., stage 2). Then they do stage 2 work (transform). Then they call arriveAndAwaitAdvance() again to move to generation 2 (stage 3). Then they aggregate. Then they call arriveAndAwaitAdvance() again to finish.

        // But we also need to handle dynamic registration/deregistration. When a worker finishes its work (maybe after processing all tasks), it can deregister using arriveAndDeregister(). This will reduce the number of parties for subsequent generations.

        // Implementation: We'll have a loop that continues while running. Each iteration:

            // Stage 1: take a task (if any). If no task and running is false, break.

            // Stage 2: transform (maybe simple identity).

            // Stage 3: aggregate result.

            // Then call phaser.arriveAndAwaitAdvance() to move to next generation (stage transition). But we need three transitions: after stage 1, after stage 2, after stage 3? Actually we can treat each stage transition as a barrier. So we can have:

                // After taking a task (stage 1 done), call phaser.arriveAndAwaitAdvance() -> moves to generation 1 (stage 2)
                // After transformation (stage 2 done), call phaser.arriveAndAwaitAdvance() -> moves to generation 2 (stage 3)
                // After aggregation (stage 3 done), call phaser.arriveAndAwaitAdvance() -> moves to generation 3 (maybe done)

            // But we also need to ensure that all workers finish stage 1 before any may proceed to stage 2. That means they all must call arriveAndAwaitAdvance() after stage 1, but they may call it at different times; the barrier will wait until all have called it. So that's fine.

        // However, we need to ensure that the Phaser's party count is adjusted as workers deregister. When a worker finishes its last iteration (maybe after processing a sentinel task), it can call phaser.arriveAndDeregister() to indicate it is leaving. This will reduce the number of parties for subsequent generations.

        // To simplify, we can have each worker process tasks until a special "poison pill" task is taken, then they deregister and exit.

        // For the stress test, we will not use poison pills; we will have a fixed number of tasks (10,000) and then after all tasks are processed, we will shut down the pipeline gracefully.

        // So we can implement a method shutdown() that sets a flag and then each worker, after processing all tasks (i.e., when workQueue.isEmpty() and no more tasks will be submitted), will call phaser.arriveAndDeregister() and exit.

        // But we also need to coordinate that all workers finish the current stage before moving to next stage. That can be done by using the barrier at each stage transition.

        // Let's design the worker thread code:

            // Register with phaser at startup: phaser.register()
            // Loop:
                // Take a task from workQueue (blocking)
                // If task is special PoisonTask, break loop after deregister.
                // Transform task -> result
                // Add result to results collection
                // // Stage transition 1: after taking task (stage 1 done)
                // int gen1 = phaser.arriveAndAwaitAdvance(); // wait for all to reach this point
                // // Stage 2: transformation already done; now we can proceed to next stage? Actually we need to separate transformation and aggregation into separate stages.
                // // Let's separate: Stage 1: take task (done)
                // // Stage 2: transform
                // // Stage 3: aggregate
                // // We'll use three arriveAndAwaitAdvance() calls: after taking task (stage 1 done), after transformation (stage 2 done), after aggregation (stage 3 done).
                // // But we need to ensure that all workers finish stage 1 before any start stage 2. That is achieved by the barrier after stage 1 (i.e., after all have taken a task,