<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260723T072749Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a **Monitor Pattern** approach with explicit `ReentrantLock` and two `Condition` variables. This is more efficient than using `synchronized` blocks because it allows producers and consumers to signal each other independently, reducing unnecessary thread wake-ups.

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
 * Concurrency Strategy:
 * - Uses a single ReentrantLock to protect the internal state (the queue and shutdown flag).
 * - Uses two Condition variables: 'notFull' (for producers) and 'notEmpty' (for consumers).
 * - This prevents "signal stealing" where a producer might wake up another producer 
 *   when it should have woken up a consumer.
 * - A 'hutdown' flag ensures that once the queue is closed, no new elements can be added
 *   and waiting threads are released.
 */
public class BoundedBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private boolean isShutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.queue = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * Inserts element, blocking if the queue is full.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shut down
     */
    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                notFull.await();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.add(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shut down and empty
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isShutdown) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to insert element within the given timeout.
     * @return true if successful, false if timeout reached or shutdown.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (isShutdown) return false;
            
            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to retrieve head within the given timeout.
     * @return element or null if timeout reached/shutdown.
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
     * Shuts down the queue, preventing new puts and waking up all waiting threads.
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

---

### 2. JUnit 5 Test Suite

To run this, you need `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    @DisplayName("Offer and Poll with Timeout")
    void testTimedOperations() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        assertTrue(queue.offer(10, 100, TimeUnit.MILLISECONDS));
        
        // Should fail because queue is full
        assertFalse(queue.offer(20, 50, TimeUnit.MILLISECONDS));
        
        assertEquals(10, queue.poll(50, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown behavior")
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new new BoundedBlockingQueue<>(5);
        
        Thread t = new Thread(() -> {
            try {
                queue.take();
            } catch (IllegalStateException | InterruptedException e) {
                // Expected behavior
            }
        });
        
        t.start();
        Thread.sleep(50); // Ensure thread is waiting
        queue.shutdown();
        t.join(1000);
        
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Track items received to verify no loss/duplicates
        ConcurrentHashMap<Integer, Integer> receivedCounts = new ConcurrentHashMap<>();
        AtomicInteger totalReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Create unique values: producerId * multiplier + j
                        queue.put(producerId * 100000 + j);
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
                        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (val!= null) {
                            receivedCounts.merge(val, 1, Integer::sum);
                            totalReceived.incrementAndGet();
                        } else {
                            // If poll returns null, check if producers are done
                            if (latch.getCount() <= numConsumers) {
                                // This is a heuristic for the test: if no producers left
                                // and queue is empty, we can exit.
                                if (queue.size() == 0) break;
                            }
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
        
        // Give consumers a moment to drain the remaining items
        int attempts = 0;
        while (queue.size() > 0 && attempts < 50) {
            Thread.sleep(50);
            attempts++;
        }

        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Assertions
        assertEquals(totalItems, totalReceived.get(), "Total items received must match total items produced");
        
        // Verify no duplicates and no missing items
        int uniqueItems = (int) receivedCounts.keySet().stream().count();
        assertEquals(totalItems, uniqueItems, "Number of unique items must match total items");
        
        for (Integer count : receivedCounts.values()) {
            assertEquals(1, count, "Each item should be processed exactly once");
        }
    }
}
```

### Key Features of the Solution:
1.  **Generics**: Uses `<T>` to allow any object type.
2.  **Boundedness**: The `capacity` limit is strictly enforced via the `notFull` condition.
3.  **Fairness/Efficiency**: By using two separate conditions (`notFull` and `notEmpty`), we avoid the "thundering herd" problem where all threads wake up even if only one can proceed.
4.  **Shutdown Safety**: The `shutdown()` method uses `signalAll()` to ensure that threads stuck in `await()` (either waiting to put or waiting to take) are woken up to receive an `IllegalStateException` or return `null`.
5.  **Stress Test Robustness**: The test uses a `ConcurrentHashMap` to verify that every single integer produced is accounted for exactly once, ensuring the queue logic doesn't suffer from race conditions that cause data loss or duplication.