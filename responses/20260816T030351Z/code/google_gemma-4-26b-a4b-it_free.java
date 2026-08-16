<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260816T030351Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and `Condition` variables. This is more efficient than using `synchronized` blocks because it allows for multiple wait-sets (one for "not full" and one for "not empty"), reducing unnecessary thread wake-ups.

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
 * Uses a single ReentrantLock to protect the internal state (the queue and the shutdown flag).
 * Two Condition variables (notFull, notEmpty) are used to implement efficient signaling:
 * - Producers wait on 'notFull' when the queue is at capacity.
 * - Consumers wait on 'notEmpty' when the queue is empty.
 * - When an item is added, the 'notEmpty' condition is signaled.
 * - When an item is removed, the 'notFull' condition is signaled.
 * 
 * This approach avoids the "thundering herd" problem associated with a single wait-set
 * and ensures that producers only wake consumers and vice versa.
 */
public class CustomBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private boolean isShutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.queue = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * Inserts an element, blocking if the queue is full.
     * @throws InterruptedException if the thread is interrupted or queue is shut down.
     * @throws IllegalStateException if the queue is shut down.
     */
    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                notFull.await();
            }
            checkShutdown();
            queue.add(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty.
     * @throws InterruptedException if the thread is interrupted or queue is shut down.
     * @throws IllegalStateException if the queue is shut down and empty.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isShutdown) {
                throw new IllegalStateException("Queue shut down");
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
     * Shuts down the queue. Subsequent puts will fail.
     * Existing elements can still be consumed.
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

To run these, ensure you have `org.junit.jupiter:junit-jupiter-api` and `org.junit.jupiter:junit-jupiter-engine` in your classpath.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicOperations() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertTrue(queue.poll(10, TimeUnit.MILLISECONDS) == null);
    }

    @Test
    @DisplayName("Timed Offer and Poll")
    void testTimedOperations() throws InterruptedException {
        CustomBlockingQueue<String> queue = new CustomBlockingQueue<>(1);
        assertTrue(queue.offer("A", 100, TimeUnit.MILLISECONDS));
        assertFalse(queue.offer("B", 100, TimeUnit.MILLISECONDS)); // Full
        
        assertEquals("A", queue.poll(100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS)); // Empty
    }

    @Test
    @DisplayName("Shutdown behavior")
    void testShutdown() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(5);
        queue.put(1);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, queue::take);
        assertFalse(queue.offer(2, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10_000;
        int totalItems = numProducers * itemsPerProducer;
        
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        Set<Integer> consumedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger totalConsumedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Encode producerId and sequence to ensure unique values
                        queue.put(producerId * 1_000_000 + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
                    // Consumers keep polling until they've collected their share 
                    // or the queue is shut down and empty.
                    // In this test, we'll use a more robust way: loop until total items reached.
                    while (totalConsumedCount.get() < totalItems) {
                        Integer item = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (item!= null) {
                            if (!consumedItems.add(item)) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalConsumedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // GO!

        // Wait for producers to finish
        boolean finished = completionLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Test timed out - possible deadlock!");

        executor.shutdownNow();

        // Verification
        assertEquals(totalItems, consumedItems.size(), "Lost or duplicated elements detected!");
        assertEquals(totalItems, totalConsumedCount.get());
    }
}
```

### Key Features of the Test Suite:
1.  **Unique Data Encoding**: Instead of just putting `1`, I use `producerId * 1_000_000 + j`. This ensures that every single item produced is unique, allowing us to detect if the same item was delivered twice (a common concurrency bug).
2.  **Concurrency primitives**: Uses `CountDownLatch` to ensure all threads start at the exact same moment, maximizing contention.
3.  **Atomic Verification**: Uses `ConcurrentHashMap.newSetFromMap` to track consumed items across threads safely, allowing us to check for duplicates and total count without a global lock on the test logic itself.
4.  **Timeout Protection**: The `await` on the `completionLatch` ensures that if the code deadlocks, the test fails instead of hanging the build pipeline.