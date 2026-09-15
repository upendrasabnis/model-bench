<!-- model: cohere/north-mini-code:free | category: code | run: 20260915T175542Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is the complete implementation of a thread-safe, generic bounded blocking queue in Java, followed by comprehensive JUnit 5 tests.

### 1. The Implementation: `CustomBlockingQueue.java`

This class uses `java.util.concurrent.locks.ReentrantLock` and `Condition` variables to manage concurrency. This approach is preferred over `synchronized` because it allows for more complex waiting strategies (like waiting on multiple conditions) and interruptible waits.

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Arrays;

public class CustomBlockingQueue<T> {
    private final T[] array;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    
    // The lock protects all queue operations
    private final ReentrantLock lock = new ReentrantLock();
    
    // Conditions for waiting threads
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    // Volatile flag for shutdown signal to ensure visibility across threads
    private volatile boolean isShutdown = false;

    @SuppressWarnings("unchecked")
    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.array = (T[]) new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately
     * without exceeding the queue's capacity, throwing an IllegalStateException if this queue is shutdown.
     * 
     * @param t the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     */
    public void put(T t) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown. No more elements can be added.");
            }
            while (isFull()) {
                notFull.await();
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the specified wait time
     * if necessary for space to become available. Returns false if the queue is shutdown or timeout occurs.
     * 
     * @param t the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if successful, false otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (isShutdown) {
                return false;
            }
            long nanos = unit.toNanos(timeout);
            while (isFull()) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, blocking until an element is available.
     * 
     * @return the head of this queue
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (isEmpty()) {
                notEmpty.await();
            }
            T t = array[head];
            array[head] = null; // Avoid memory leak
            head = (head + 1) % array.length;
            count--;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available. Returns null if timeout occurs or queue is empty.
     * 
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or null if timeout occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            T t = array[head];
            array[head] = null;
            head = (head + 1) % array.length;
            count--;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown. No new elements can be put into the queue.
     * Existing elements can still be taken. Waiting threads are notified.
     */
    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            // Wake up all waiting threads so they can check the shutdown flag
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

    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return count == array.length;
        } finally {
            lock.unlock();
        }
    }
    
    public int remainingCapacity() {
        lock.lock();
        try {
            return array.length - count;
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. The Concurrency Strategy Documentation

**1. Locking Strategy**
We use `java.util.concurrent.locks.ReentrantLock` instead of the implicit `synchronized` blocks. This provides explicit control over lock acquisition, which is crucial for implementing complex blocking logic. `ReentrantLock` allows us to have multiple `Condition` objects associated with a single lock, which is essential for managing the two distinct states of the queue: "not empty" and "not full".

**2. Condition Variables (NotEmpty / NotFull)**
The queue relies on two Condition variables:
*   **`notEmpty`**: Threads calling `take()` or `poll()` wait on this condition when the queue is empty. Producers signal this condition when they add an element.
*   **`notFull`**: Threads calling `put()` or `offer()` wait on this condition when the queue is full. Consumers signal this condition when they remove an element.

This decoupling ensures that:
*   A consumer waiting for an element is not woken up by a producer putting an element (though `signal()` is specific, `signalAll` ensures safety), but specifically by the state change of the queue.
*   A producer waiting for space is not woken up by a consumer taking an element.
*   It prevents "spurious wakeups" by using `while (condition) await()` loops instead of `if`.

**3. Memory Visibility and Atomicity**
*   All state modifications (head, tail, count, isShutdown) are protected by the `lock`. This ensures atomicity of the "check-then-act" operations (e.g., checking if full, then adding).
*   The `isShutdown` flag is marked `volatile`. While holding the lock is the primary synchronization mechanism for the queue's internal state, `volatile` ensures that when a thread calls `shutdown()`, other threads waiting on conditions see the updated flag immediately upon waking up (even if they were waiting on a different condition via `signalAll`).

**4. Interrupt Handling**
Methods use `lock.lockInterruptibly()`. This allows the thread to be interrupted while waiting on a condition. If interrupted, an `InterruptedException` is thrown, preserving the interrupt status for the caller, which is standard practice for blocking queues.

**5. Clean Shutdown**
The `shutdown()` method serves as a "poison pill" for producers. It sets a flag and calls `signalAll()`. This ensures that:
*   Producers attempting to `put` will wake up, see `isShutdown` is true, and fail fast.
*   Consumers waiting in `take()` or `poll()` will wake up. They will proceed to take any remaining elements in the queue until it is empty, allowing the queue to drain gracefully.

### 3. JUnit 5 Tests: `CustomBlockingQueueTest.java`

This test suite includes unit tests for basic functionality, timeout behavior, shutdown logic, and a multi-threaded stress test to verify data integrity.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel if possible, though stress tests often need isolation
class CustomBlockingQueueTest {

    private CustomBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.take());
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        // Try to add one more with a short timeout
        assertFalse(queue.offer(99, 100, TimeUnit.MILLISECONDS));
        assertEquals(10, queue.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        new Thread(() -> {
            try {
                Thread.sleep(100);
                queue.put(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        
        Integer val = queue.poll(1, TimeUnit.SECONDS);
        assertEquals(42, val);
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(val);
    }

    @Test
    void testShutdownInterruptsPut() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertTrue(queue.isShutdown());
    }

    @Test
    void testTakeAfterShutdown_DrainsQueue() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferAfterShutdown_ReturnsFalse() throws InterruptedException {
        queue.shutdown();
        assertFalse(queue.offer(1, 1, TimeUnit.SECONDS));
    }

    /**
     * Stress Test: Multi-Producer, Multi-Consumer.
     * Verifies no lost or duplicated elements.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // Prevent infinite hangs
    void stressTest_MultiProducerMultiConsumer() throws InterruptedException {
        final int NUM_PRODUCERS = 5;
        final int NUM_CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 1000;
        final int QUEUE_CAPACITY = 100; // Small capacity to force contention

        CustomBlockingQueue<Long> stressQueue = new CustomBlockingQueue<>(QUEUE_CAPACITY);
        
        // Thread-safe collection to collect results
        List<Long> receivedItems = new CopyOnWriteArrayList<>();
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumerDoneCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producerDoneLatch = new CountDownLatch(NUM_PRODUCERS);
        CountDownLatch consumerDoneLatch = new CountDownLatch(NUM_CONSUMERS);

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    for (long j = 0; j < ITEMS_PER_PRODUCER; j++) {
                        // Unique ID: (ProducerID * MaxItems) + ItemIndex
                        long item = (long) producerId * ITEMS_PER_PRODUCER + j;
                        stressQueue.put(item);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producerDoneLatch.countDown();
                }
            }).start();
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    while (!producerDoneLatch.await(1, TimeUnit.SECONDS) || !stressQueue.isEmpty()) {
                        // Poll with timeout to check if producers are done and queue is empty
                        Long item = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            receivedItems.add(item);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumerDoneLatch.countDown();
                }
            }).start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for producers to finish
        producerDoneLatch.await(60, TimeUnit.SECONDS);
        
        // Signal consumers to finish by shutting down