<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260918T144055Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe, generic bounded blocking queue, followed by the concurrency documentation and a comprehensive JUnit 5 test suite.

### 1. Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * @param <T> the type of elements held in this queue
 */
public class CustomBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private volatile boolean isShutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts element, waiting if necessary for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     */
    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            ensureNotShutdown();
            while (queue.size() == capacity) {
                notFull.await();
                ensureNotShutdown(); // Re-check after waking up
            }
            queue.add(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary until an element is available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (isShutdown) throw new IllegalStateException("Queue is shut down");
                notEmpty.await();
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts element, waiting up to the specified timeout if space is not available.
     * @return true if successful, false if timeout reached
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            ensureNotShutdown();
            while (queue.size() == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                ensureNotShutdown();
            }
            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting up to the specified timeout.
     * @return the element, or null if timeout reached
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (isShutdown) return null;
                if (nanos <= 0) return null;
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
     * Gracefully shuts down the queue. 
     * Unblocks all waiting threads.
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

    private void ensureNotShutdown() {
        if (isShutdown) {
            throw new IllegalStateException("Queue is shut down");
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

### 2. Concurrency Strategy Documentation

**Locking Mechanism:**
I used a single `ReentrantLock` to protect the state of the internal `LinkedList`. While two-lock queues (one for head, one for tail) can offer higher throughput, a single lock is more robust for implementing a "shutdown" signal that affects both producers and consumers simultaneously.

**Condition Variables:**
To avoid the "busy-waiting" problem and the "thundering herd" inefficiency of `notifyAll()`, I implemented two separate `Condition` objects:
1.  `notFull`: Producers wait on this when the queue is at capacity.
2.  `notEmpty`: Consumers wait on this when the queue is empty.

**Wait/Signal Protocol:**
*   **Producers** use `while` loops (not `if`) to check the capacity condition. This protects against "spurious wakeups." When a producer adds an item, it signals `notEmpty`.
*   **Consumers** use `while` loops to check if the queue is empty. When a consumer removes an item, it signals `notFull`.

**Shutdown Strategy:**
The `isShutdown` flag is marked `volatile` to ensure visibility. When `shutdown()` is called, the lock is acquired, the flag is set, and `signalAll()` is called on both conditions. This ensures that any thread currently blocked in `await()` is woken up immediately to receive an `IllegalStateException` or a `null` return, preventing deadlocks during application exit.

---

### 3. JUnit 5 Test Suite

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    private CustomBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(5);
    }

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicPutTake() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("Offer with timeout should return false when full")
    void testOfferTimeout() throws InterruptedException {
        for (int i = 0; i < 5; i++) queue.put(i);
        
        boolean success = queue.offer(99, 100, TimeUnit.MILLISECONDS);
        assertFalse(success, "Offer should have failed due to timeout");
    }

    @Test
    @DisplayName("Poll with timeout should return null when empty")
    void testPollTimeout() throws InterruptedException {
        Integer result = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(result, "Poll should have returned null due to timeout");
    }

    @Test
    @DisplayName("Shutdown should unblock waiting threads")
    void testShutdown() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (IllegalStateException e) {
                // Expected
            } catch (InterruptedException ignored) {}
        });

        consumer.start();
        Thread.sleep(100); // Ensure consumer is waiting
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive(), "Consumer thread should have terminated after shutdown");
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 1000;
        int totalItems = numProducers * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numProducers + numConsumers);
        
        // Use a thread-safe set to track seen elements
        // Using a ConcurrentHashMap-based Set to prevent duplicates/losses
        Set<Integer> receivedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger totalReceivedCount = new AtomicInteger(0);

        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        // Unique item: producerId * offset + i
                        queue.put(producerId * 10000 + i);
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (totalReceivedCount.get() < totalItems) {
                        Integer item = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            if (!receivedItems.add(item)) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalReceivedCount.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // Fire!
        
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(finished, "Stress test timed out");
        assertEquals(totalItems, totalReceivedCount.get(), "Lost elements detected");
        assertEquals(totalItems, receivedItems.size(), "Set size mismatch - possible duplicates");
    }
}
```