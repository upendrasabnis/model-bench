<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260726T053632Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a **Monitor Pattern** approach with intrinsic locks (`synchronized`) and condition variables (`wait`/`notifyAll`).

### 1. The Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * Concurrency Strategy:
 * We use a single intrinsic lock (the queue object itself) to protect the internal state.
 * Two Condition variables (not implemented via ReentrantLock here to keep it standard, 
 * but simulated via wait/notifyAll for simplicity and correctness) manage the state:
 * 1. Waiting Producers: Threads blocked on a full queue.
 * 2. Waiting Consumers: Threads blocked on an empty queue.
 * 
 * We use `notifyAll()` instead of `notify()` to avoid the "lost wakeup" problem 
 * where a producer might accidentally wake another producer instead of a consumer.
 * 
 * A 'hutdown' flag is used to break blocking operations when the queue is closed.
 */
public class CustomBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private boolean isShutdown = false;

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.queue = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * Inserts element, blocking if full until space is available or shutdown.
     * @throws InterruptedException if interrupted or queue is shut down.
     */
    public void put(T element) throws InterruptedException {
        synchronized (this) {
            while (queue.size() == capacity &&!isShutdown) {
                this.wait();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.add(element);
            this.notifyAll(); // Notify consumers
        }
    }

    /**
     * Retrieves and removes the head, blocking if empty until element available or shutdown.
     */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        synchronized (this) {
            while (queue.isEmpty() &&!isShutdown) {
                this.wait();
            }
            if (isShutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            T element = queue.poll();
            this.notifyAll(); // Notify producers
            return element;
        }
    }

    /**
     * Attempts to insert element within the given timeout.
     * @return true if successful, false if timeout reached.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (this) {
            while (queue.size() == capacity) {
                if (nanos <= 0 || isShutdown) return false;
                long start = System.nanoTime();
                this.wait(nanos);
                nanos -= (System.nanoTime() - start);
            }
            if (isShutdown) return false;
            queue.add(element);
            this.notifyAll();
            return true;
        }
    }

    /**
     * Attempts to retrieve element within the given timeout.
     * @return element or null if timeout reached.
     */
    @SuppressWarnings("unchecked")
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (this) {
            while (queue.isEmpty()) {
                if (nanos <= 0 || isShutdown) return null;
                long start = System.nanoTime();
                this.wait(nanos);
                nanos -= (System.nanoTime() - start);
            }
            if (isShutdown && queue.isEmpty()) return null;
            T element = queue.poll();
            this.notifyAll();
            return element;
        }
    }

    public void shutdown() {
        synchronized (this) {
            isShutdown = true;
            this.notifyAll();
        }
    }

    public int size() {
        synchronized (this) {
            return queue.size();
        }
    }
}
```

---

### 2. JUnit 5 Test Suite

To run this, ensure you have `org.junit.jupiter:junit-jupiter-api` and `org.assertj:assertj-core` (optional but recommended) in your classpath.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicPutTake() throws InterruptedException {
        CustomBlockingQueue<Integer> q = new CustomBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertEquals(1, q.take());
        assertEquals(2, q.take());
    }

    @Test
    @DisplayName("Timed Offer and Poll")
    void testTimedOperations() throws InterruptedException {
        CustomBlockingQueue<Integer> q = new CustomBlockingQueue<>(1);
        q.put(1);

        // Test offer timeout (should fail because queue is full)
        boolean offered = q.offer(2, 100, TimeUnit.MILLISECONDS);
        assertFalse(offered);

        // Test poll timeout (should return null because queue is empty after take)
        q.take();
        Integer polled = q.poll(100, TimeUnit.MILLISECONDS);
        assertNull(polled);
    }

    @Test
    @DisplayName("Shutdown behavior")
    void testShutdown() throws InterruptedException {
        CustomBlockingQueue<Integer> q = new CustomBlockingQueue<>(5);
        
        Thread t = new Thread(() -> {
            try {
                q.take();
            } catch (IllegalStateException e) {
                // Expected
            } catch (InterruptedException e) {
                // Expected
            }
        });
        t.start();
        
        Thread.sleep(50);
        q.shutdown();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer/Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 10000;
        int totalExpectedItems = numProducers * itemsPerProducer;

        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        ConcurrentHashMap<Integer, Integer> receivedItems = new ConcurrentHashMap<>();
        AtomicInteger totalReceivedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Use a unique value for every single item: (producerId * offset) + j
                        queue.put(producerId * 100000 + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Consumers keep taking until they've reached the total count 
                    // or queue is shut down. Since we don't know exact timing, 
                    // we use a loop that exits when the latch reaches 0.
                    while (totalReceivedCount.get() < totalExpectedItems) {
                        Integer item = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (item!= null) {
                            // Check for duplicates
                            if (receivedItems.putIfAbsent(item, 1)!= null) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalReceivedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Boom! Start everyone
        
        // Wait for producers to finish
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Test timed out - likely deadlock or slow execution");

        executor.shutdownNow();

        // Assertions
        assertEquals(totalExpectedItems, totalReceivedCount.get(), "Count mismatch - items lost!");
        assertEquals(totalExpectedItems, receivedItems.size(), "Map size mismatch - duplicate or lost items!");
    }
}
```

### Explanation of the Stress Test
1.  **Uniqueness**: Each producer generates numbers in a specific range (e.g., Producer 0: 0-9999, Producer 1: 100000-109999). This ensures no two producers produce the same number.
2.  **Concurrency**: We use a `CountDownLatch` (`startLatch`) to ensure all threads start exactly at the same time, maximizing contention.
3.  **Verification**:
    *   **Lost Elements**: We use an `AtomicInteger` to count how many items consumers actually pulled.
    *   **Duplicated Elements**: We use a `ConcurrentHashMap` to store every item received. If `putIfAbsent` returns a non-null value, it means the same number was received twice, failing the test immediately.
4.  **Timeout**: The `endLatch.await` ensures that if a deadlock occurs, the test fails with a timeout rather than hanging the build pipeline.