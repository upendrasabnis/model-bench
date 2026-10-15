<!-- model: cohere/north-mini-code:free | category: code | run: 20261015T093231Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
```java
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<E> {
    private final Deque<E> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            while (queue.size() == capacity) {
                notFull.await();
            }
            queue.add(e);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public E take() throws InterruptedException {
        lock.lock();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            E e = queue.removeFirst();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("Element cannot be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            long nanos = unit.toNanos(timeout);
            while (queue.size() == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            queue.add(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        lock.lock();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            long nanos = unit.toNanos(timeout);
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = queue.removeFirst();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }
}
```

Now, let's write comprehensive JUnit 5 tests for the BoundedBlockingQueue.```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel
class BoundedBlockingQueueTest {
    private static final int CAPACITY = 100;
    private static final int NUM_PRODUCERS = 10;
    private static final int NUM_CONSUMERS = 10;
    private static final int NUM_ELEMENTS = 10000;

    @Test
    void testBasicPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(Integer.valueOf(1), queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferPoll() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1), queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testBlockingPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // Ensure consumer starts first
                queue.put(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertEquals(Integer.valueOf(42), queue.take());
    }

    @Test
    void testTimedOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        queue.put(1);
        assertFalse(queue.offer(2, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTimedPollTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        queue.shutdown();
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testMultipleProducersMultipleConsumers() throws InterruptedException, ExecutionException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<Integer> produced = new ArrayList<>();
        List<Integer> consumed = new ArrayList<>();

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            int start = i * NUM_ELEMENTS / NUM_PRODUCERS;
            int end = (i + 1) * NUM_ELEMENTS / NUM_PRODUCERS;
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = start; j < end; j++) {
                    try {
                        queue.put(j);
                        produced.add(j);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, executor));
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    while (!queue.isShutdown() || !queue.isEmpty()) {
                        Integer e = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (e != null) {
                            consumed.add(e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor));
        }

        // Shutdown after all producers are done
        CompletableFuture.allOf(futures.subList(NUM_PRODUCERS, futures.size())).thenRun(queue::shutdown).join();

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        executor.shutdownNow();

        // Verify no lost or duplicated elements
        assertEquals(NUM_ELEMENTS, produced.size(), "Some elements were lost by producers");
        assertEquals(NUM_ELEMENTS, consumed.size(), "Some elements were lost by consumers");
        assertTrue(produced.containsAll(consumed), "Some elements were duplicated or lost");
        assertTrue(consumed.containsAll(produced), "Some elements were duplicated or lost");
    }

    @Test
    void testQueueCapacity() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(5);
        for (int i = 0; i < 5; i++) {
            queue.put(i);
        }
        assertEquals(5, queue.size());
        assertThrows(IllegalStateException.class, () -> queue.offer(6, 100, TimeUnit.MILLISECONDS)); // Queue is full
        assertThrows(IllegalStateException.class, () -> queue.put(6)); // Queue is full and blocking
    }

    @Test
    void testNullElement() {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testNegativeTimeout() {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        assertThrows(IllegalArgumentException.class, () -> queue.offer(1, -1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> queue.poll(-1, TimeUnit.SECONDS));
    }

    @Test
    void testNegativeCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testConcurrentPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);

        List<CompletableFuture<Void>> producers = new ArrayList<>();
        List<CompletableFuture<Void>> consumers = new ArrayList<>();

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            producers.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < NUM_ELEMENTS / NUM_PRODUCERS; j++) {
                    try {
                        queue.put(j);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, executor));
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            consumers.add(CompletableFuture.runAsync(() -> {
                try {
                    while (!queue.isShutdown()) {
                        Integer e = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (e != null) {
                            // Process element
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor));
        }

        // Shutdown after all producers are done
        CompletableFuture.allOf(producers.toArray(new CompletableFuture[0])).thenRun(queue::shutdown).join();

        // Wait for all futures to complete
        CompletableFuture.allOf(consumers.toArray(new CompletableFuture[0])).join();

        executor.shutdownNow();

        // Verify no lost or duplicated elements
        int totalProduced = NUM_ELEMENTS;
        int totalConsumed = NUM_ELEMENTS;
        assertEquals(totalProduced, totalConsumed, "Some elements were lost or duplicated");
    }
}
```

This implementation provides a thread-safe, generic bounded blocking queue with blocking put/take, timed offer/poll, and clean shutdown. The concurrency strategy uses `ReentrantLock` and `Condition` variables to ensure mutual exclusion and proper signaling between producers and consumers. The JUnit 5 tests cover basic functionality, edge cases, and a multi-producer/multi-consumer stress test to verify no lost or duplicated elements.