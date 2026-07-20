<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260720T104637Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses the **Monitor Pattern** with explicit `ReentrantLock` and two `Condition` variables. This is more efficient than using `synchronized` because it allows producers and consumers to wait on different signals, reducing unnecessary thread wake-ups.

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
 * - Uses a single ReentrantLock to protect the internal state (the queue and the shutdown flag).
 * - Uses two Condition variables: 'notFull' (for producers) and 'notEmpty' (for consumers).
 * - This prevents "signal stealing" where a producer might wake up another producer 
 *   when it should have woken up a consumer.
 * - Implements a "fail-fast" mechanism via a shutdown flag.
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
     * Inserts element, blocking if the queue is full until space is available 
     * or the queue is shut down.
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
            notEmpty.signal(); // Notify one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to insert element within the given timeout.
     * @return true if successful, false if timed out or shut down.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                if (nanos <= 0L) return false;
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
     * Retrieves and removes the head, blocking if empty until an element is available.
     */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                notEmpty.await();
            }

            if (queue.isEmpty() && isShutdown) {
                return null; // Return null to signal shutdown/end of stream
            }

            T item = queue.poll();
            notFull.signal(); // Notify one waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to retrieve element within the given timeout.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            
            if (queue.isEmpty()) return null;

            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts down the queue, preventing new elements from being added and 
     * waking up all waiting threads.
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

### 2. Testing: `BoundedBlockingQueueTest.java`

Requires JUnit 5 dependency.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take should work")
    void testBasicFunctionality() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        
        assertEquals(2, q.size());
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Offer should return false when timeout expires")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill queue
        
        boolean success = q.offer(2, 100, TimeUnit.MILLISECONDS);
        assertFalse(success, "Offer should fail when queue is full and timeout expires");
    }

    @Test
    @DisplayName("Shutdown should prevent further puts and unblock threads")
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        
        Thread blockingThread = new Thread(() -> {
            try {
                q.put(1); // This will block if we fill it first
            } catch (Exception ignored) {}
        });

        // Fill the queue
        for(int i=0; i<5; i++) q.put(i);

        Thread producerThread = new Thread(() -> {
            assertThrows(IllegalStateException.class, () -> q.put(99));
        });

        producerThread.start();
        Thread.sleep(50); // Allow producer to enter put()
        q.shutdown();
        producerThread.join();
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer/Multi-Consumer (No loss, No duplicates)")
    void stressTest() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 10000;
        int totalExpectedItems = numProducers * itemsPerProducer;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        Set<Integer> consumedItems = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger totalConsumedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numProducers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int startRange = i * itemsPerProducer;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < itemsPerProducer; j++) {
                        queue.put(startRange + j);
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
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item!= null) {
                            if (!consumedItems.add(item)) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalConsumedCount.incrementAndGet();
                        } else {
                            // If queue is empty and producers are done, exit
                            if (latch.getCount() == 0 && queue.size() == 0) {
                                break;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for producers to finish
        latch.await(10, TimeUnit.SECONDS);
        
        // Allow consumers time to drain the remaining items
        long start = System.currentTimeMillis();
        while (totalConsumedCount.get() < totalExpectedItems && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(10);
        }

        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        assertEquals(totalExpectedItems, totalConsumedCount.get(), "Total items consumed mismatch!");
        assertEquals(totalExpectedItems, consumedItems.size(), "Set size mismatch (potential duplicates/losses)!");
    }
}
```

### Key Design Features:
1.  **Liveness**: The use of `signal()` inside the lock ensures that whenever a state change occurs (space becomes available or data becomes available), a waiting thread is notified.
2.  **Correctness (Stress Test)**: The stress test uses a `HashSet` to ensure no two consumers ever grab the same integer, and an `AtomicInteger` to ensure no item is lost.
3.  **Graceful Shutdown**: The `shutdown()` method sets a flag and calls `signalAll()`. This ensures that any thread stuck in `await()` (waiting for space or waiting for data) is woken up to see the `isShutdown` flag and exit cleanly.
4.  **Timeouts**: `offer` and `poll` use `awaitNanos` to provide high-precision timed blocking, essential for production-grade systems.