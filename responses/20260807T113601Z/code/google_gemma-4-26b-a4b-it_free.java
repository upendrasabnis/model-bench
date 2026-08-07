<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260807T113601Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a **Monitor Pattern** approach. Instead of using high-level concurrency utilities, it relies on intrinsic locks (`synchronized`) and condition variables (`wait`/`notifyAll`) to manage thread coordination.

### 1. The Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * CONCURRENCY STRATEGY:
 * This implementation uses the Monitor Pattern. 
 * 1. Mutual Exclusion: All access to the underlying non-thread-safe LinkedList 
 *    is guarded by a single intrinsic lock (synchronized(this)).
 * 2. Coordination: We use the object's built-in wait/notifyAll mechanism.
 *    - 'put' operations wait if the queue is full.
 *    - 'take' operations wait if the queue is empty.
 *    - 'notifyAll()' is used instead of 'notify()' to prevent the "lost wakeup" 
 *      problem where a producer might wake another producer instead of a consumer.
 * 3. Shutdown: A volatile 'isShutdown' flag is checked at the start of every 
 *    blocking operation. Once shutdown is called, all waiting threads are 
 *    interrupted/notified to exit.
 *
 * @param <T> the type of elements held in this queue
 */
public class CustomBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private volatile boolean isShutdown = false;

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts element, waiting if necessary for space to become available.
     * @throws InterruptedException if interrupted or queue is shutdown
     * @throws IllegalStateException if queue is shut down
     */
    public synchronized void put(T element) throws InterruptedException {
        checkShutdown();
        while (queue.size() == capacity) {
            wait();
            checkShutdown(); // Re-check after waking up
        }
        queue.add(element);
        notifyAll(); // Notify consumers that data is available
    }

    /**
     * Retrieves and removes the head, waiting if necessary until an element is available.
     * @throws InterruptedException if interrupted or queue is shutdown
     * @throws IllegalStateException if queue is shut down
     */
    public synchronized T take() throws InterruptedException {
        checkShutdown();
        while (queue.isEmpty()) {
            wait();
            checkShutdown();
        }
        T element = queue.poll();
        notifyAll(); // Notify producers that space is available
        return element;
    }

    /**
     * Inserts element, waiting up to the specified timeout if space is unavailable.
     * @return true if successful, false if timeout reached
     */
    public synchronized boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        checkShutdown();
        while (queue.size() == capacity) {
            if (nanos <= 0) return false;
            nanos = waitNanos(nanos);
            checkShutdown();
        }
        queue.add(element);
        notifyAll();
        return true;
    }

    /**
     * Retrieves and removes head, waiting up to the specified timeout.
     * @return the element, or null if timeout reached
     */
    public synchronized T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        checkShutdown();
        while (queue.isEmpty()) {
            if (nanos <= 0) return null;
            nanos = waitNanos(nanos);
            checkShutdown();
        }
        T element = queue.poll();
        notifyAll();
        return element;
    }

    public synchronized void shutdown() {
        isShutdown = true;
        notifyAll(); // Wake up everyone to realize the queue is closed
    }

    public synchronized int size() {
        return queue.size();
    }

    private void checkShutdown() {
        if (isShutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    // Helper to handle timed waits manually with the monitor
    private long waitNanos(long nanos) throws InterruptedException {
        long start = System.nanoTime();
        wait(TimeUnit.NANOSECONDS.toMillis(nanos));
        long elapsed = System.nanoTime() - start;
        return nanos - elapsed;
    }
}
```

---

### 2. JUnit 5 Test Suite

This test suite includes basic functional tests, edge cases (timeouts), and a high-concurrency stress test.

```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take functionality")
    void testBasicPutTake() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("Timed offer should return false when full")
    void testTimedOffer() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        queue.put(1);
        
        boolean success = queue.offer(2, 100, TimeUnit.MILLISECONDS);
        assertFalse(success, "Offer should have failed due to timeout");
    }

    @Test
    @DisplayName("Timed poll should return null when empty")
    void testTimedPoll() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        
        Integer result = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(result, "Poll should have returned null due to timeout");
    }

    @Test
    @DisplayName("Shutdown should prevent further operations")
    void testShutdown() {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(5);
        queue.shutdown();

        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer (No loss/duplication)")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultiProducerMultiConsumer() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 1000;
        int totalExpectedItems = numProducers * itemsPerProducer;

        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(10);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Use ConcurrentHashMap to track occurrences (Value -> Count)
        // This allows us to detect both lost items and duplicates
        ConcurrentHashMap<Integer, Integer> counts = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicInteger totalConsumed = new AtomicInteger(0);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Sync start
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Unique value per producer-item pair
                        queue.put(producerId * itemsPerProducer + j);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Sync start
                    while (totalConsumed.get() < totalExpectedItems) {
                        Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (val != null) {
                            counts.merge(val, 1, Integer::sum);
                            totalConsumed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Expected during shutdown or end of test
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start the race
        
        // Wait for producers to finish
        // We wait for a reasonable time or until latch hits 0
        boolean finished = endLatch.await(5, TimeUnit.SECONDS);
        
        executor.shutdownNow();

        // Assertions
        assertEquals(totalExpectedItems, totalConsumed.get(), "Total items consumed mismatch");
        assertEquals(totalExpectedItems, counts.size(), "Unique items count mismatch (lost items)");
        
        counts.forEach((val, count) -> {
            assertEquals(1, count, "Duplicate item detected: " + val);
        });
    }
}
```

### Key Design Decisions

1.  **`notifyAll()` vs `notify()`**: In a bounded queue, both producers and consumers wait on the same monitor. If a consumer calls `notify()`, it might accidentally wake up another consumer while the queue is empty, leading to a deadlock where everyone is sleeping. `notifyAll()` ensures that if space becomes available, a producer *will* definitely be woken up.
2.  **`volatile boolean isShutdown`**: This ensures that when one thread calls `shutdown()`, the change is immediately visible to all other threads checking the flag in their `while` loops.
3.  **The "Check-Wait-Check" Loop**: In `put` and `take`, we use `while` loops rather than `if`. This handles **spurious wakeups** (a phenomenon where a thread wakes up without being notified) and ensures that if multiple threads are woken, they re-verify the condition (capacity/emptiness) before proceeding.
4.  **Stress Test Strategy**: The test uses a `ConcurrentHashMap` to count occurrences of every single integer produced. If an item is lost, the map size will be too small. If an item is duplicated, the count for that key will be $>1$. This is a mathematically rigorous way to verify integrity in a concurrent environment.