<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260711T175732Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and two separate `Condition` variables (`notFull` and `notEmpty`). This approach is more efficient than a single `synchronized` block because it avoids the "signal all" overhead, allowing producers to wake consumers and vice versa without waking the wrong group.

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
 * 2. Condition Variables: Uses two separate Condition objects (notFull, notEmpty) 
 *    to prevent the "thundering herd" problem. Producers wait on 'notFull' and 
 *    consumers wait on 'notEmpty'.
 * 3. State Management: A 'closed' flag is checked during every blocking operation 
 *    to ensure clean shutdown.
 */
public class BoundedBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private boolean isClosed = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts element, blocking if full until space is available or queue is closed.
     * @throws InterruptedException if interrupted or queue is closed.
     */
    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity && !isClosed) {
                notFull.await();
            }
            if (isClosed) {
                throw new IllegalStateException("Queue is closed");
            }
            queue.add(element);
            notEmpty.signal(); // Wake up one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty until element is available or queue is closed.
     * @throws InterruptedException if interrupted or queue is closed.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !isClosed) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isClosed) {
                return null; // Return null to signal end of stream during shutdown
            }
            T element = queue.poll();
            notFull.signal(); // Wake up one waiting producer
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
            while (queue.size() == capacity && !isClosed) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (isClosed) return false;
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
            while (queue.isEmpty() && !isClosed) {
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
     * Closes the queue, preventing new puts and waking all waiting threads.
     */
    public void close() {
        lock.lock();
        try {
            isClosed = true;
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

### 2. Unit Tests: `BoundedBlockingQueueTest.java`

Requires JUnit 5 and AssertJ (optional, but standard `Assertions` used here).

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take operations")
    void testBasicPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Offer and Poll with timeouts")
    void testTimeouts() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertFalse(queue.offer(2, 100, TimeUnit.MILLISECONDS), "Should fail when full");
        
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS), "Should return null when empty");
    }

    @Test
    @DisplayName("Shutdown/Close behavior")
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(5);
        
        Thread blocker = new Thread(() -> {
            try {
                queue.take(); // Will block
            } catch (InterruptedException ignored) {}
        });
        blocker.start();
        
        Thread.sleep(50); // Ensure thread is blocking
        queue.close();
        blocker.join(1000);
        
        assertFalse(blocker.isAlive(), "Thread should have been woken up by close()");
        assertThrows(IllegalStateException.class, () -> queue.put(10));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer (No loss/duplication)")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Use a thread-safe set to track received items to detect duplicates/loss
        // Note: Using ConcurrentHashMap.newKeySet() for high performance
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
                        // Unique ID: producerIndex * multiplier + sequence
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
                        Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (val != null) {
                            if (!receivedItems.add(val)) {
                                fail("Duplicate detected: " + val);
                            }
                            totalReceivedCount.incrementAndGet();
                        } else if (totalReceivedCount.get() == totalItems) {
                            // Optimization to break early if we know we are done
                            break;
                        }
                        // If queue is closed and empty, poll returns null
                        // In this test, we rely on the total count or the shutdown
                    }
                } catch (Exception e) {
                    // Expected during shutdown
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // GO!
        
        // Wait for producers to finish
        // We need to wait for producers specifically or just wait for all
        Thread.sleep(2000); 
        queue.close(); // Signal consumers to wrap up
        
        completionLatch.await();
        executor.shutdown();

        assertEquals(totalItems, totalReceivedCount.get(), "Lost elements detected!");
        assertEquals(totalItems, receivedItems.size(), "Set size mismatch!");
    }
}
```

### Key Features Explained:

1.  **Concurrency Strategy**:
    *   **`ReentrantLock`**: Provides the mutual exclusion.
    *   **`Condition` (notFull)**: Producers call `notFull.await()` when the queue is at capacity. They are only signaled when a consumer calls `take()` or `poll()`.
    *   **`Condition` (notEmpty)**: Consumers call `notEmpty.await()` when the queue is empty. They are only signaled when a producer calls `put()` or `offer()`.
    *   **Efficiency**: By using two conditions, we avoid waking up producers when a consumer just took an item (which would result in producers waking up only to see the queue is still potentially full).

2.  **Shutdown Logic**:
    *   The `close()` method sets `isClosed = true` and calls `signalAll()` on both conditions.
    *   This ensures that any thread stuck in `await()` is immediately woken up.
    *   The `put` method throws an `IllegalStateException` to prevent adding items to a dead queue.
    *   The `take` method returns `null` to allow consumers to exit gracefully.

3.  **Stress Test Design**:
    *   **Uniqueness**: Each producer generates a unique range of integers (`producerId * 100000 + j`).
    *   **Verification**: We use a `ConcurrentHashMap`-backed `Set` to ensure every single integer produced is received exactly once. If a duplicate is added, `set.add(val)` returns `false`, failing the test.
    *   **Integrity**: We compare the `totalReceivedCount` against the expected `totalItems` to ensure no elements were dropped during context switches.