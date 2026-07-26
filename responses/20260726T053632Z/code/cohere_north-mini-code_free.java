<!-- model: cohere/north-mini-code:free | category: code | run: 20260726T053632Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is the implementation of a thread-safe, generic, bounded blocking queue in Java, followed by the JUnit 5 test suite.

### Part 1: The Implementation

This class implements a circular buffer using a `ReentrantLock` and `Condition` variables to manage concurrency. This approach is chosen over `synchronized` blocks because it provides better flexibility for interruptible waits and timed operations, which are required for the `offer` and `poll` methods with timeouts.

**Concurrency Strategy Documentation:**

1.  **Mutual Exclusion**: A `ReentrantLock` (`lock`) is used to ensure that only one thread can modify the queue state (`head`, `tail`, `count`, `buffer`) at any given time.
2.  **Condition Variables**:
    *   `notEmpty`: Threads calling `take()` or `poll()` wait on this condition if the queue is empty.
    *   `notFull`: Threads calling `put()` or `offer()` wait on this condition if the queue is full.
3.  **Spurious Wakeups**: We use `while` loops (not `if`) when waiting on conditions to guard against spurious wakeups.
4.  **Shutdown Mechanism**: A `volatile` boolean flag (`isShutdown`) signals the end of operations. The `shutdown()` method sets this flag and calls `signalAll()` on both conditions to wake up any waiting threads so they can check the flag and exit.
5.  **Interrupt Handling**: The `put`, `take`, `offer`, and `poll` methods use `lock.lockInterruptibly()` and `await`/`awaitNanos`. This allows the JVM or external code to interrupt a waiting thread, causing an `InterruptedException` to be thrown.
6.  **Time Units**: `TimeUnit` is used to standardize timeout inputs for timed methods.

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<T> {
    private final T[] buffer;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private final int capacity;

    // The lock provides exclusive access to the queue
    private final ReentrantLock lock = new ReentrantLock();
    
    // Conditions for waiting on empty/full states
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Volatile flag for shutdown signaling
    private volatile boolean isShutdown = false;

    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
    }

    /**
     * Inserts the specified element into the queue, waiting if necessary up to
     * the specified wait time if the queue is full.
     */
    public void put(T t) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (isShutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                notFull.await(); // Wait until space is available
            }
            buffer[tail] = t;
            tail = (tail + 1) % capacity;
            count++;
            notEmpty.signal(); // Notify a waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary up to
     * the specified wait time if the queue is empty.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (isShutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                notEmpty.await(); // Wait until an element is available
            }
            T item = buffer[head];
            buffer[head] = null; // Avoid memory leak
            head = (head + 1) % capacity;
            count--;
            notFull.signal(); // Notify a waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into the queue, waiting if necessary up to
     * the specified wait time if the queue is full.
     * Returns true if successful, false if the timeout is reached.
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (count == capacity) {
                if (isShutdown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false; // Timeout reached
                }
                nanos = notFull.awaitNanos(nanos);
            }
            buffer[tail] = t;
            tail = (tail + 1) % capacity;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary up to
     * the specified wait time if the queue is empty.
     * Returns null if the timeout is reached.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (count == 0) {
                if (isShutdown) {
                    return null;
                }
                if (nanos <= 0) {
                    return null; // Timeout reached
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            T item = buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a clean shutdown. Wakes up all threads waiting on the queue
     * so they can check the shutdown flag and exit with an exception.
     */
    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}
```

---

### Part 2: JUnit 5 Tests

This test suite includes unit tests for basic functionality, bounded behavior, and a stress test verifying data integrity (no lost or duplicated elements) using multiple producers and consumers.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(42);
        int result = queue.take();
        assertEquals(42, result);
        assertTrue(queue.isShutdown()); // Should be shutdown by tearDown
    }

    @Test
    void testOfferSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.take());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }

        // Try to offer, should timeout
        boolean offered = queue.offer(99, 100, TimeUnit.MILLISECONDS);
        assertFalse(offered);
        
        // Ensure original item is still there
        assertEquals(9, queue.take());
        queue.shutdown();
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // Queue is empty
        Integer result = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(result);
        
        queue.put(5);
        queue.shutdown();
    }

    @Test
    void testShutdownInterruptsWaiters() throws InterruptedException {
        // Start a thread that will wait to put
        Thread producer = new Thread(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException e) {
                // Expected on shutdown
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        // Let it start waiting
        Thread.sleep(100);

        // Fill the queue to ensure it is waiting
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }

        // Shutdown should interrupt the put
        queue.shutdown();
        producer.join();

        // Verify queue is empty except for items we put in loop
        assertEquals(10, queue.size());
    }

    /**
     * Multi-producer/multi-consumer stress test.
     * Verifies that no elements are lost or duplicated.
     */
    @Test
    void stressTestNoLostOrDuplicatedElements() throws InterruptedException {
        final int NUM_PRODUCERS = 10;
        final int NUM_CONSUMERS = 10;
        final int ITEMS_PER_PRODUCER = 100;
        final int TOTAL_ITEMS = NUM_PRODUCERS * ITEMS_PER_PRODUCERS;

        // Shared set to track what has been taken (to check for duplicates)
        Set<Integer> takenItems = new CopyOnWriteArraySet<>();
        
        // Latch to ensure all producers have started putting before we start timing/stop
        CountDownLatch startLatch = new CountDownLatch(1);
        // Latch to ensure all items have been put before shutdown
        CountDownLatch putLatch = new CountDownLatch(NUM_PRODUCERS * ITEMS_PER_PRODUCERS);

        List<Thread> producers = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();

        // Create Producers
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            final int producerId = p;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int value = (producerId * ITEMS_PER_PRODUCER) + i;
                        queue.put(value);
                        putLatch.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            producers.add(t);
        }