<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260822T172454Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and two separate `Condition` variables. This approach is more efficient than a single `synchronized` block because it allows producers and consumers to wait on different signals, reducing unnecessary thread wake-ups (thundering herd problem).

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
 * 1. Monitor Pattern: Uses a single ReentrantLock to protect the internal state.
 * 2. Dual Conditions: Uses 'notFull' and 'notEmpty' Condition objects. 
 *    - Producers wait on 'notFull' when the queue is at capacity.
 *    - Consumers wait on 'notEmpty' when the queue is empty.
 *    - This prevents "signal stealing" where a producer accidentally wakes another producer.
 * 3. State Management: Uses a boolean 'isShutdown' flag to ensure graceful termination.
 *    - Once shutdown, no new elements can be put.
 *    - Consumers can continue draining existing elements until the queue is empty.
 */
public class BoundedBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private boolean isShutdown = false;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts an element, blocking if the queue is full.
     * @throws InterruptedException if thread is interrupted
     * @throws IllegalStateException if queue is shut down
     */
    public void put(T item) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity && !isShutdown) {
                notFull.await();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.add(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty.
     * @throws InterruptedException if thread is interrupted
     * @throws IllegalStateException if queue is shut down and empty
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !isShutdown) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isShutdown) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to insert an element within a timeout.
     */
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity) {
                if (nanos <= 0 || isShutdown) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (isShutdown) return false;
            queue.add(item);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to retrieve an element within a timeout.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0 || isShutdown) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown. Producers are blocked from adding new items.
     * Consumers can still take existing items.
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
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. Comprehensive JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(5);
    }

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicOperations() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("Timed Offer should return false when full")
    void testTimedOffer() throws InterruptedException {
        for (int i = 0; i < 5; i++) queue.put(i);
        
        boolean result = queue.offer(99, 100, TimeUnit.MILLISECONDS);
        assertFalse(result, "Offer should fail when queue is full");
    }

    @Test
    @DisplayName("Timed Poll should return null when empty")
    void testTimedPoll() throws InterruptedException {
        Integer result = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(result, "Poll should return null when queue is empty");
    }

    @Test
    @DisplayName("Shutdown prevents new puts but allows draining")
    void testShutdown() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 1000;
        int totalExpectedItems = numProducers * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        ConcurrentHashMap<Integer, AtomicInteger> receivedCounts = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        // Unique value: ProducerID * Multiplier + LoopIndex
                        queue.put(producerId * 10000 + i);
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        try {
                            Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                            if (val != null) {
                                receivedCounts.computeIfAbsent(val, k -> new AtomicInteger(0))
                                              .incrementAndGet();
                            } else if (doneLatch.getCount() <= numProducers) {
                                // If producers are done and poll returned null, we are finished
                                break;
                            }
                        } catch (IllegalStateException e) {
                            break; // Queue shutdown
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire!
        
        // Wait for producers to finish, then shutdown queue to trigger consumer exit
        Thread.sleep(1000); 
        queue.shutdown();
        
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Test timed out");
        executor.shutdownNow();

        // VERIFICATION
        int totalReceived = receivedCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        
        assertEquals(totalExpectedItems, totalReceived, 
            "Total items received must match total items produced");
        
        // Check for duplicates/loss
        for (Map.Entry<Integer, AtomicInteger> entry : receivedCounts.entrySet()) {
            assertEquals(1, entry.getValue().get(), 
                "Item " + entry.getKey() + " was duplicated or lost");
        }
    }
}
```

### Key Features of the Implementation:
1.  **Fairness (Optional):** While I used the default `ReentrantLock` (non-fair) for maximum throughput, you could pass `true` to the `ReentrantLock` constructor to prevent thread starvation at the cost of performance.
2.  **Interruptibility:** `lockInterruptibly()` and `await()` are used so that threads stuck in a blocking call can be cancelled via `Thread.interrupt()`.
3.  **Graceful Shutdown:** The `shutdown()` method signals all waiting threads. Producers receive an `IllegalStateException`, while consumers are allowed to finish processing whatever is left in the queue before receiving an `IllegalStateException`.
4.  **Stress Test Logic:** The test uses a `ConcurrentHashMap` of `AtomicInteger` to track every single item produced. By checking that every item appears exactly once, we prove there is no race condition causing data loss or duplication.