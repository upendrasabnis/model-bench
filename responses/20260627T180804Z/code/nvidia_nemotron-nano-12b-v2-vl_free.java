<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260627T180804Z -->
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
```java
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.anyOf;

public final class PhasedDataProcessor {
    private final Phaser phaser;
    private final DataProcessor[] workers;
    private final Map<String, Result> resultsMap = new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> allPhasesCompleted = new AtomicReference<>(false);
    private final AtomicBoolean awaitTermination = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public PhasedDataProcessor(int numThreads) {
        if (numThreads < 2) throw new IllegalArgumentException("Number of threads must be >= 2");
        phaser = new Phaser(numThreads);
        workers = new DataProcessor[numThreads];
        for (int i = 0; i < numThreads; i++) {
            workers[i] = new DataProcessor(this, i);
            Thread thread = new Thread(workers[i]);
            thread.start();
        }
    }

    public synchronized void submit(DataChunk chunk) {
        if (allPhasesCompleted.get()) {
            throw new IllegalStateException("All phases have completed. No more submissions allowed.");
        }
        workers[chunk.hashCode() % workers.length].processChunk(chunk);
    }

    public Map<String, Result> getResults() throws InterruptedException {
        while (!allPhasesCompleted.get()) {
            synchronized (this) {
                if (awaitTermination.get()) {
                    wait();
                }
            }
        }
        synchronized (this) {
            awaitTermination.set(false);
            notifyAll();
        }
        return new Map<String, Result>(resultsMap);
    }

    private synchronized void phaseComplete(int phase) {
        if (phase == 2) {
            allPhasesCompleted.set(true);
            awaitTermination.set(true);
            notifyAll();
        }
        phaser.phase();
    }

    private void registerWorker() {
        phaser.register();
    }

    private void unregisterWorker() {
        phaser.tryPhase();
        phaser.bulkReap(1);
    }

    private void processChunk(DataChunk chunk) {
        try {
            if (phaser.getPhase() == 0) {
                String result = transformData(chunk);
                resultsMap.put(chunk.getId(), new Result(result));
            } else if (phaser.getPhase() == 1) {
                String result = aggregateData(chunk);
                resultsMap.merge(chunk.getId(), new Result(result), (r1, r2) -> new Result(r1 + ", " + r2));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (phaser.getPhase() == 2) {
                phaseComplete(2);
            }
        }
    }

    private String transformData(DataChunk chunk) {
        // Transformation logic
        return "transformed-" + chunk.getId();
    }

    private String aggregateData(DataChunk chunk) {
        // Aggregation logic
        return "aggregated-" + chunk.getId();
    }

    private static class DataProcessor implements Runnable {
        private final PhasedDataProcessor parent;
        private final int workerId;

        DataProcessor(PhasedDataProcessor parent, int workerId) {
            this.parent = parent;
            this.workerId = workerId;
        }

        @Override
        public void run() {
            parent.registerWorker();
            while (!parent.shutdownRequested.get() || !parent.phaser.isTerminated()) {
                try {
                    DataChunk chunk = getNextChunk();
                    parent.processChunk(chunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            parent.unregisterWorker();
        }

        private DataChunk getNextChunk() throws InterruptedException {
            // Simulate dynamic chunk submission
            synchronized (this) {
                while (parent.phaser.getPhase() < 1) {
                    wait();
                }
                // Logic to fetch a chunk from a queue
                return new DataChunk("chunk-" + System.currentTimeMillis());
            }
        }
    }

    private static class DataChunk {
        private final String id;

        DataChunk(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    private static class Result {
        private final String value;

        Result(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }

    @Test
    public void testPhaseOrdering() {
        PhasedDataProcessor processor = new PhasedDataProcessor(2);
        DataChunk chunk1 = new DataChunk("1");
        DataChunk chunk2 = new DataChunk("2");
        processor.submit(chunk1);
        processor.submit(chunk2);
        processor.phaseComplete(0);
        Map<String, Result> results = processor.getResults();
        assertFalse(results.isEmpty());
        assertTrue(results.containsKey("1"));
        assertTrue(results.containsKey("2"));
    }

    @Test
    public void testStressTest() {
        PhasedDataProcessor processor = new PhasedDataProcessor(8);
        for (int i = 0; i < 1000; i++) {
            processor.submit(new DataChunk("chunk-" + i));
        }
        processor.phaseComplete(0);
        processor.phaseComplete(1);
        Map<String, Result> results = processor.getResults();
        assertEquals(1000, results.size());
    }

    @Test
    public void testLateArrivalChunks() {
        PhasedDataProcessor processor = new PhasedDataProcessor(2);
        processor.submit(new DataChunk("1"));
        processor.phaseComplete(0);
        processor.submit(new DataChunk("2"));
        Map<String, Result> results = processor.getResults();
        assertEquals(2, results.size());
    }

    @Test
    public void testGetResultsBlocks() {
        PhasedDataProcessor processor = new PhasedDataProcessor(2);
        DataChunk chunk = new DataChunk("1");
        processor.submit(chunk);
        CompletableFuture<Void> future = runAsync(() -> {
            try {
                processor.getResults();
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
            return null;
        });
        processor.phaseComplete(0);
        processor.phaseComplete(1);
        future.join();
        assertTrue(processor.getResults().containsKey("1"));
    }

    @Test
    public void testConcurrencyCorrectness() {
        PhasedDataProcessor processor = new PhasedDataProcessor(2);
        AtomicBoolean phase0 = new AtomicBoolean(true);
        AtomicBoolean phase1 = new AtomicBoolean(false);
        CompletableFuture<Void> phase0Future = CompletableFuture.runAsync(() -> {
            processor.submit(new DataChunk("1"));
            phase0.set(false);
            processor.phaseComplete(0);
            phase1.set(true);
        });
        CompletableFuture<Void> phase1Future = CompletableFuture.runAsync(() -> {
            while (!phase1.get()) {
                // Wait for phase 1 to start
            }
            processor.submit(new DataChunk("2"));
            processor.phaseComplete(1);
        });
        allOf(phase0Future, phase1Future).join();
        assertEquals(2, processor.getResults().size());
    }
}
```
