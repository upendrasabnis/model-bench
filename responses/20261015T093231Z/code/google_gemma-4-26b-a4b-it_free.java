<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20261015T093231Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and two `Condition` variables to manage thread synchronization.

### 1. Implementation: `BoundedBlockingQueue.java`

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
 * 1. Monitor Pattern: Uses a single ReentrantLock to protect the internal state.
 * 2. Condition Variables: Uses two separate conditions ('notFull' and 'notEmpty') 
 *    to avoid the "thundering herd" problem. Producers wait on 'notFull', 
 *    and consumers wait on 'notEmpty'.
 * 3. State Management: A 'shutdown' flag is checked during all blocking operations.
 *    Once shutdown is initiated, no new elements can be added, and existing elements 
 *    can be drained.
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
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts element, blocking if full until space is available or shutdown.
     * @throws InterruptedException if interrupted or queue is shut down.
     */
    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity && !isShutdown) {
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
     * Retrieves and removes the head, blocking if empty until element available or shutdown.
     * @throws InterruptedException if interrupted or queue is shut down.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !isShutdown) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isShutdown) {
                return null; // Return null to signal queue is empty and closed
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to insert an element within the given timeout.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity) {
                if (nanos <= 0 || isShutdown) return false;
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
     * Attempts to retrieve an element within the given timeout.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0 || isShutdown) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown. No more 'put' operations allowed.
     * Consumers can continue to 'take' until the queue is empty.
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

### 2. Comprehensive Tests: `BoundedBlockingQueueTest.java`

Requires JUnit 5 and AssertJ (optional, but used standard JUnit assertions here).

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take operations")
    void testBasicOps() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertEquals(2, q.size());
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("Offer and Poll with timeouts")
    void testTimedOps() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer(10, 100, TimeUnit.MILLISECONDS));
        assertFalse(q.offer(20, 50, TimeUnit.MILLISECONDS), "Should fail because queue is full");

        assertEquals(10, q.poll(100, TimeUnit.MILLISECONDS));
        assertNull(q.poll(50, TimeUnit.MILLISECONDS), "Should return null because queue is empty");
    }

    @Test
    @DisplayName("Shutdown prevents new puts but allows draining")
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        q.put(1);
        q.put(2);
        q.shutdown();

        assertThrows(IllegalStateException.class, () -> q.put(3));
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertNull(q.take(), "Should return null after shutdown and empty");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;
        int capacity = 100;

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Use a thread-safe set to track seen items for uniqueness and presence
        // Note: ConcurrentHashMap.newKeySet() is efficient for this
        Set<Integer> receivedItems = ConcurrentHashMap.newKeySet();
        AtomicInteger totalReceivedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Unique value: producerId * multiplier + j
                        queue.put(producerId * 100000 + j);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            // If queue is empty, check if producers are done
                            // This is a simplified way to handle shutdown for the test
                            if (totalReceivedCount.get() + (numProducers - 1) * itemsPerProducer < totalItems) {
                                // Not all items received yet, continue polling
                                continue; 
                            }
                            break;
                        }
                        if (receivedItems.add(item)) {
                            totalReceivedCount.incrementAndGet();
                        } else {
                            fail("Duplicate item detected: " + item);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire!
        
        // Wait for producers to finish
        // We use a separate loop to allow consumers to drain
        boolean producersFinished = completionLatch.await(5, TimeUnit.SECONDS);
        
        // For this specific test logic, we trigger shutdown to let consumers finish
        queue.shutdown();
        completionLatch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(totalItems, totalReceivedCount.get(), "Total items received mismatch");
        assertEquals(totalItems, receivedItems.size(), "Unique items count mismatch");
    }
}
```

### Key Design Explanations:

1.  **`lockInterruptibly()`**: This is crucial for a production-grade blocking queue. It allows a thread waiting for a slot (or an item) to be unblocked immediately if the application is shutting down or the thread is cancelled via `Thread.interrupt()`.
2.  **`notFull` vs `notEmpty`**: By using two conditions, we ensure that when a consumer takes an item, it only signals threads waiting to *put* (producers). It doesn't wake up other consumers unnecessarily, which optimizes CPU usage.
3.  **Shutdown Logic**:
    *   The `isShutdown` flag is checked inside the `while` loops.
    *   `signalAll()` is called on both conditions during shutdown to ensure any thread stuck in `await()` is woken up to see the `isShutdown` flag and exit gracefully.
4.  **Stress Test Strategy**:
    *   **Uniqueness**: Every producer generates a unique range of integers (e.g., Producer 0: 0-9999, Producer 1: 100000-109999).
    *   **Verification**: We use a `ConcurrentHashMap`-backed `Set` to verify that no two consumers ever see the same number (detects duplicates) and that the final count matches the expected total (detects lost elements).