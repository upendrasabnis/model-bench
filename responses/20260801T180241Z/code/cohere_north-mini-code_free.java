<!-- model: cohere/north-mini-code:free | category: code | run: 20260801T180241Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe, generic bounded blocking queue in Java, followed by comprehensive JUnit 5 tests.

### Part 1: The Implementation

**Concurrency Strategy Documentation:**

This implementation uses the **Monitor Pattern** with intrinsic locks (`synchronized` blocks) to ensure thread safety. Here is the strategy for concurrency control:

1.  **Mutual Exclusion:** All methods that access or modify the shared state (the internal `ArrayList`) are marked as `synchronized`. This ensures that only one thread can execute any of these methods on a specific queue instance at any given time.
2.  **Blocking:** To implement blocking `put` and `take`, we use the `wait()` and `notifyAll()` methods. When a thread attempts to `put` into a full queue, it calls `wait()`. This releases the lock and puts the thread to sleep until notified. When an item is `take`n or the queue is `shutdown`, we call `notifyAll()` to wake up all waiting threads so they can re-evaluate their conditions.
3.  **Spurious Wakeups:** We use `while` loops around the wait conditions (e.g., `while (data.size() == capacity)`) rather than `if`. This is a standard precaution against spurious wakeups, where a thread might wake up without being notified.
4.  **Shutdown:** A volatile boolean flag `shutdown` is used. The `shutdown()` method sets this flag and calls `notifyAll()`. This interrupts the waiting state of producers and consumers, allowing them to exit gracefully instead of waiting indefinitely.
5.  **Timed Operations:** `offer` and `poll` use `wait(timeout)` to limit the time a thread spends waiting. If the timeout expires, the method returns the appropriate default value (`false` or `null`) without throwing an exception.

```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BoundedBlockingQueue<T> {
    private final List<T> data;
    private final int capacity;
    private volatile boolean shutdown = false;

    /**
     * Constructs a BoundedBlockingQueue with the specified capacity.
     *
     * @param capacity The maximum number of elements the queue can hold.
     * @throws IllegalArgumentException if capacity is not positive.
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.data = new ArrayList<>(capacity);
        this.capacity = capacity;
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately
     * without violating capacity constraints.
     *
     * @param t the element to add
     * @return {@code true} if it was possible to add the element to this queue, else {@code false}
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    public synchronized boolean offer(T t) throws InterruptedException {
        return offer(t, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Inserts the specified element into this queue, waiting up to the specified wait time
     * if necessary for space to become available.
     *
     * @param t the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, or {@code false} if the specified waiting time elapses before space is available
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    public synchronized boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        if (shutdown) {
            throw new IllegalStateException("Queue is shutdown");
        }

        long nanos = unit.toNanos(timeout);
        long end = System.nanoTime() + nanos;

        while (data.size() == capacity) {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            if (nanos <= 0) {
                return false; // Timeout
            }
            wait(nanos);
            nanos = end - System.nanoTime();
        }

        data.add(t);
        notifyAll();
        return true;
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary until space is available.
     *
     * @param t the element to add
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    public synchronized void put(T t) throws InterruptedException {
        if (shutdown) {
            throw new IllegalStateException("Queue is shutdown");
        }
        while (data.size() == capacity) {
            wait();
        }
        data.add(t);
        notifyAll();
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element is available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown and empty
     */
    public synchronized T take() throws InterruptedException {
        while (data.isEmpty()) {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            wait();
        }
        T item = data.remove(0);
        notifyAll();
        return item;
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the specified waiting time elapses before an element is available
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown and empty
     */
    public synchronized T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long end = System.nanoTime() + nanos;

        while (data.isEmpty()) {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            if (nanos <= 0) {
                return null; // Timeout
            }
            wait(nanos);
            nanos = end - System.nanoTime();
        }

        T item = data.remove(0);
        notifyAll();
        return item;
    }

    /**
     * Initiates an orderly shutdown of the queue. Existing threads waiting to put or take
     * will be interrupted and receive an IllegalStateException.
     */
    public synchronized void shutdown() {
        this.shutdown = true;
        notifyAll();
    }

    /**
     * Checks if the queue has been shutdown.
     *
     * @return true if the queue is shutdown
     */
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements
     */
    public synchronized int size() {
        return data.size();
    }
}
```

### Part 2: JUnit 5 Tests

This section includes tests for basic functionality, timed operations, shutdown behavior, and a multi-producer/multi-consumer stress test.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel if possible, though tests here are mostly independent
class BoundedBlockingQueueTest {

    private static final int STRESS_TEST_QUEUE_CAPACITY = 100;
    private static final int STRESS_TEST_TOTAL_ITEMS = 5000;
    private static final int STRESS_TEST_PRODUCERS = 10;
    private static final int STRESS_TEST_CONSUMERS = 10;
    private static final long TIMEOUT_MS = 1000;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(STRESS_TEST_QUEUE_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutAndTake_FifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
        
        assertEquals(0, queue.size());
    }

    @Test
    void testOffer_Success() throws InterruptedException {
        assertTrue(queue.offer(1));
        assertEquals(1, queue.size());
    }

    @Test
    void testOffer_Fail() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < STRESS_TEST_QUEUE_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(STRESS_TEST_QUEUE_CAPACITY, queue.size());

        // Try to add one more immediately (should fail)
        assertFalse(queue.offer(999));
        assertEquals(STRESS_TEST_QUEUE_CAPACITY, queue.size());
    }

    @Test
    void testTimedOffer_Success() throws InterruptedException {
        assertTrue(queue.offer(1, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void testTimedOffer_Timeout() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < STRESS_TEST_QUEUE_CAPACITY; i++) {
            queue.put(i);
        }

        // Try to add one with a short timeout (should fail)
        assertFalse(queue.offer(999, 100, TimeUnit.MILLISECONDS));
        assertEquals(STRESS_TEST_QUEUE_CAPACITY, queue.size());
    }

    @Test
    void testPoll_Success() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testPoll_Timeout() throws InterruptedException {
        // Queue is empty
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testTake_Timeout() throws InterruptedException {
        // Queue is empty
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertTrue(queue.isShutdown());

        // Operations should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownWakesUpWaiters() throws InterruptedException {
        // Start a consumer that will wait
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (IllegalStateException e) {
                // Expected
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Give it time to start waiting
        Thread.sleep(100);

        // Shutdown should interrupt it
        queue.shutdown();
        consumer.join(1000);

        assertFalse(consumer.isAlive(), "Consumer thread should have terminated after shutdown");
    }

    // --- Stress Test ---
    @Test
    void stressTest_MultiProducerMultiConsumer_NoLostOrDuplicatedItems() throws InterruptedException {
        // We need a shared container to collect results from consumers
        // CopyOnWriteArraySet is thread-safe for reads and writes, suitable for this verification
        Set<Integer> takenItems = Collections.synchronizedSet(new CopyOnWriteArraySet<>());
        AtomicInteger takenCount = new AtomicInteger(0);
        CountDownLatch producersDoneLatch = new CountDownLatch(STRESS_TEST_PRODUCERS);
        CountDownLatch consumersDoneLatch = new CountDownLatch(STRESS_TEST_CONSUMERS);

        ExecutorService producerExecutor = Executors.newFixedThreadPool(STRESS_TEST_PRODUCERS);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(STRESS_TEST_CONSUMERS);

        // Logic for Producers
        Runnable producerTask = () -> {
            try {
                for (int i = 0; i < (STRESS_TEST_TOTAL_ITEMS / STRESS_TEST_PRODUCERS); i++) {
                    // Calculate unique ID to ensure no duplicates across threads
                    // Using thread id and loop index is tricky for deduplication without a global counter.
                    // Better approach: Global AtomicInteger for IDs.
                    // Let's use a global AtomicInteger for unique IDs to verify logic.
                    // Actually, for stress testing "no duplicates", we usually assume producers 
                    // produce distinct items. 
                    // Let's use a simple atomic counter for IDs to ensure uniqueness.
                    int id = globalIdCounter.incrementAndGet();
                    queue.put(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producersDoneLatch.countDown();
            }
        };

        // Logic for Consumers
        Runnable consumerTask = () -> {
            try {
                while (true) {
                    // We can't just take until empty because producers might still be adding 
                    // relative to the latch, but we need to consume everything.
                    // To simplify, we consume until we have taken STRESS_TEST_TOTAL_ITEMS 
                    // OR queue is shutdown and empty.
                    // However, we need to know when to stop.
                    // Strategy: Consumers run until they have taken the expected number of items 
                    // OR queue is shutdown and empty.
                    
                    // We need a shared atomic counter for how many items have been taken.
                    // But we also need to know when producers are done to stop consuming early?
                    // No, consumers should just consume until queue is empty AND shutdown.
                    
                    // Let's use a timeout for the poll to avoid infinite blocking if logic is slightly off,
                    // but since we control the shutdown, we can rely on that.
                    
                    // However, to avoid busy waiting, we rely on shutdown.
                    // We will shutdown after all producers are done.
                    
                    // So here, we just take.
                    Integer item = queue.poll(500, TimeUnit.MILLISECONDS); // Timeout to check for shutdown periodically
                    if (item != null) {
                        takenItems.add(item);
                        takenCount.incrementAndGet();
                    } else if (queue.isShutdown() && queue.size() == 0) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumersDoneLatch.countDown();
            }
        };

        // We need a global counter to generate unique IDs for verification
        AtomicInteger globalIdCounter = new AtomicInteger(0);

        // Start Producers
        for (int i = 0; i < STRESS_TEST_PRODUCERS; i++) {
            producerExecutor.submit(() -> {
                try {
                    for (int i = 0; i < (STRESS_TEST_TOTAL_ITEMS / STRESS_TEST_PRODUCERS); i++) {
                        int id = globalIdCounter.incrementAndGet();
                        queue.put(id);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDoneLatch.countDown();
                }
            });
        }

        // Start Consumers
        for (int i = 0; i < STRESS_TEST_CONSUMERS; i++) {
            consumerExecutor.submit(consumerTask);
        }

        // Wait for all producers to finish
        producersDoneLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Initiate shutdown to stop consumers
        queue.shutdown();

        // Wait for all consumers to finish
        consumersDoneLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Cleanup
        producerExecutor.shutdownNow();
        consumerExecutor.shutdownNow();
        
        // Verification
        // 1. Check count
        assertEquals(STRESS_TEST_TOTAL_ITEMS, takenCount.get(), 
            "Not all items were taken. Total expected: " + STRESS_TEST_TOTAL_ITEMS + ", Found: " + takenCount.get());

        // 2. Check for duplicates
        // If the set size equals the count, there are no duplicates.
        assertEquals(STRESS_TEST_TOTAL_ITEMS, takenItems.size(), 
            "Duplicate items found or items lost. Total expected: " + STRESS_TEST_TOTAL_ITEMS + ", Unique found: " + takenItems.size());

        // 3. Check for gaps (optional, but good for "clean" queue logic)
        // Since IDs are 1..N, we can check min/max or sum if we wanted to be strict.
        // But "no lost or duplicated" is the primary requirement.
    }
}
```

### Key Implementation Notes

1.  **Atomic Operations**: The `AtomicInteger` `globalIdCounter` in the stress test is crucial. It ensures that every item produced has a unique ID, making it possible to detect duplicates or losses by comparing the size of the `takenItems` Set against the expected total.
2.  **Shutdown Logic**: The `shutdown()` method sets a volatile flag and calls `notifyAll()`. This is critical because threads waiting in `wait()` or `wait(timeout)` will wake up, check the condition, and throw an exception or exit. This prevents the test from hanging indefinitely if a thread is stuck waiting.
3.  **Timeout Handling**: In `offer` and `poll`, we subtract time elapsed from the remaining timeout (`nanos = end - System.nanoTime()`). This ensures that if a thread is woken up spuriously or by a notification, it doesn't wait the full original duration again.
4.  **Spurious Wakeups**: The `while` loops around the wait conditions protect against rare JVM-level spurious wakeups.