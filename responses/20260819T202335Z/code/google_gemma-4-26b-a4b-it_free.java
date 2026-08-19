<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260819T202335Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and `Condition` variables. This is the standard low-level approach for building custom blocking structures in Java.

### 1. The Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * CONCURRENCY STRATEGY:
 * This implementation uses a single ReentrantLock to protect the internal state (the queue and shutdown flag).
 * It utilizes two Condition variables (notFull and notEmpty) to implement efficient signaling:
 * - Threads calling 'put' wait on 'notFull' when the queue is at capacity.
 * - Threads calling 'take' wait on 'notEmpty' when the queue is empty.
 * - A 'hutdown' flag is checked before any blocking operation to ensure graceful termination.
 * 
 * Complexity:
 * - put/take/offer/poll: O(1)
 * - Space: O(N) where N is the capacity.
 */
public class CustomBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    
    // Condition variables for signaling
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    
    private boolean isShutdown = false;

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.queue = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * Inserts element, blocking if full. Returns false if queue is shut down.
     */
    public boolean put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                notFull.await();
            }
            
            if (isShutdown) return false;

            queue.add(element);
            notEmpty.signal(); // Wake up a consumer
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty. Returns null if shut down.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                notEmpty.await();
            }

            if (queue.isEmpty() && isShutdown) return null;

            T element = queue.poll();
            notFull.signal(); // Wake up a producer
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to insert after waiting for given time.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            
            if (isShutdown || queue.size() == capacity) return false;

            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to retrieve after waiting for given time.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }

            if (queue.isEmpty()) return null;

            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts down the queue, waking up all waiting threads.
     */
    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return queue.size(); } finally { lock.unlock(); }
    }
}
```

---

### 2. JUnit 5 Test Suite

To run this, ensure you have `junit-jupiter-api` and `junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class CustomBlockingQueueTest {

    private CustomBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(5);
    }

    @Test
    @DisplayName("Basic Put and Take functionality")
    void testBasicPutTake() throws InterruptedException {
        assertTrue(queue.put(1));
        assertTrue(queue.put(2));
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("Timed offer and poll")
    void testTimedOperations() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 5; i++) queue.put(i);

        // Test offer timeout
        long start = System.currentTimeMillis();
        boolean offered = queue.offer(99, 100, TimeUnit.MILLISECONDS);
        long duration = System.currentTimeMillis() - start;
        
        assertFalse(offered, "Should not be able to offer to full queue");
        assertTrue(duration >= 100, "Should have waited for timeout");

        // Test poll timeout
        queue = new CustomBlockingQueue<>(5); // reset
        start = System.currentTimeMillis();
        Integer polled = queue.poll(100, TimeUnit.MILLISECONDS);
        duration = System.currentTimeMillis() - start;
        
        assertNull(polled, "Should return null on timeout");
        assertTrue(duration >= 100);
    }

    @Test
    @DisplayName("Shutdown should release blocked threads")
    void testShutdown() throws InterruptedException {
        Thread blocker = new Thread(() -> {
            try {
                queue.take();
            } catch (InterruptedException ignored) {}
        });
        blocker.start();

        Thread.sleep(50); // Ensure thread is actually waiting
        queue.shutdown();
        
        blocker.join(1000);
        assertFalse(blocker.isAlive(), "Thread should have been released by shutdown");
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer (No loss/duplication)")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10000;
        int totalExpectedItems = numProducers * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        ConcurrentHashMap<Integer, Integer> receivedItems = new ConcurrentHashMap<>();
        AtomicInteger totalConsumedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Create unique items: Producer 0: 0-9999, Producer 1: 10000-19999...
                        queue.put(producerId * itemsPerProducer + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        Integer item = queue.poll(10, TimeUnit.MILLISECONDS);
                        if (item!= null) {
                            // Check for duplicates using computeIfAbsent
                            // If value was already present, we found a duplicate
                            if (receivedItems.putIfAbsent(item, 1)!= null) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalConsumedCount.incrementAndGet();
                        } else if (latch.getCount() <= numConsumers) {
                            // If producers are done and queue is empty
                            // We check if queue is empty to exit gracefully
                            if (queue.size() == 0) break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for producers to finish
        latch.await(10, TimeUnit.SECONDS);
        queue.shutdown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Final drain of the queue to ensure all items are processed
        Integer remaining;
        while ((remaining = queue.poll(10, TimeUnit.MILLISECONDS))!= null) {
            if (receivedItems.putIfAbsent(remaining, 1)!= null) {
                fail("Duplicate item detected during drain: " + remaining);
            }
            totalConsumedCount.incrementAndGet();
        }

        assertEquals(totalExpectedItems, totalConsumedCount.get(), "Total items consumed must match total items produced");
        assertEquals(totalExpectedItems, receivedItems.size(), "Unique items count must match total items");
    }
}
```

### Key Features of the Test Suite:
1.  **Edge Case Testing**: Checks timeouts and empty/full states.
2.  **Shutdown Integrity**: Ensures that `shutdown()` breaks threads out of `await()` loops.
3.  **Stress Test (The "Big One")**:
    *   **Multi-threading**: Uses `ExecutorService` with multiple producers and consumers.
    *   **Uniqueness**: Uses a `ConcurrentHashMap` to detect if any integer is produced/consumed twice.
    *   **Integrity**: Uses an `AtomicInteger` to ensure the total count of items processed matches the total count of items sent.
    *   **Deterministic Data**: Each producer generates a unique range of integers so that every item is identifiable.