<!-- model: cohere/north-mini-code:free | category: code | run: 20260918T144055Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic bounded blocking queue implementation.
 *
 * Concurrency Strategy:
 * - Uses a ReentrantLock for exclusive access to the queue.
 * - Two Conditions: notEmpty (signaled when an item is added) and notFull (signaled when space is available).
 * - The queue is protected by the lock, ensuring mutual exclusion.
 * - All public methods that modify or access the queue acquire the lock.
 * - Shutdown is handled by a flag; put operations after shutdown throw an exception.
 * - Timed operations use Condition.await/unpark with timeout.
 * - The queue uses a LinkedList internally for FIFO ordering.
 *
 * @param <T> The type of elements held in this queue.
 */
public class BoundedBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a BoundedBlockingQueue with the specified capacity.
     *
     * @param capacity The maximum number of elements the queue can hold.
     * @throws IllegalArgumentException if capacity is non-positive.
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary until space is available.
     *
     * @param element The element to add.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public void put(T element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (queue.size() == capacity) {
                notFull.await();
            }
            queue.offer(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, blocking up to the specified wait time if necessary.
     *
     * @param element The element to add.
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return true if the element was added, false if the timeout expired.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (queue.size() == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            queue.offer(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, blocking until an element is available.
     *
     * @return The head of the queue.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            while (queue.isEmpty()) {
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
     * Retrieves and removes the head of this queue, blocking up to the specified wait time if necessary.
     *
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return The head of the queue, or null if the timeout expired.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
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
     * Returns the number of elements in the queue.
     *
     * @return The size of the queue.
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown of the queue. No further elements can be added via put/offer.
     * Existing elements can still be taken/poll.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll(); // Wake up any waiting consumers
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the queue is shutdown.
     *
     * @return true if the queue is shutdown.
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
```

Here's the JUnit 5 test suite:

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests concurrently (if supported)
class BoundedBlockingQueueTest {
    private static final int CAPACITY = 100;
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 5;
    private static final int NUM_ELEMENTS = 5000;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutTakeBasic() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferPollBasic() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(CAPACITY, queue.size());
        // Offer should timeout
        assertFalse(queue.offer(CAPACITY + 1, 100, TimeUnit.MILLISECONDS));
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // Queue is empty
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testInterruptedPut() throws InterruptedException {
        Thread t = new Thread(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        // Wait a bit for the put to start waiting
        Thread.sleep(100);
        t.interrupt();
        t.join();
        assertTrue(t.isInterrupted() || !t.isAlive());
        assertEquals(0, queue.size());
    }

    @Test
    void testInterruptedTake() throws InterruptedException {
        // Start a consumer that will block
        Thread t = new Thread(() -> {
            try {
                queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join();
        assertTrue(t.isInterrupted() || !t.isAlive());
        assertEquals(0, queue.size());
    }

    @Test
    void testShutdownPut() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownTake() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testMultipleProducersConsumers() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_PRODUCERS + NUM_CONSUMERS);
        List<Integer> produced = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < NUM_ELEMENTS / NUM_PRODUCERS; j++) {
                        int value = producerId * (NUM_ELEMENTS / NUM_PRODUCERS) + j;
                        queue.put(value);
                        produced.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (produced.size() > consumed.size()) {
                        Integer value = queue.take();
                        if (value != null) {
                            consumed.add(value);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        queue.shutdown();

        // Verify all produced elements were consumed exactly once
        assertEquals(produced.size(), consumed.size());
        assertTrue(produced.containsAll(consumed) && consumed.containsAll(produced));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int elementsPerProducer = 1000;
        int totalProduced = numProducers * elementsPerProducer;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);

        List<Integer> produced = Collections.synchronizedList(new ArrayList<>(totalProduced));
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>(totalProduced));

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < elementsPerProducer; j++) {
                        int value = producerId * elementsPerProducer + j;
                        queue.put(value);
                        produced.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (produced.size() > consumed.size()) {
                        Integer value = queue.take();
                        if (value != null) {
                            consumed.add(value);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "Not all producers/consumers finished within timeout");
        queue.shutdown();

        // Verify no lost or duplicated elements
        assertEquals(totalProduced, produced.size());
        assertEquals(totalProduced, consumed.size());
        assertTrue(produced.containsAll(consumed));
        assertTrue(consumed.containsAll(produced));
    }

    @Test
    void testQueueOrder() throws InterruptedException {
        int numElements = 100;
        for (int i = 0; i < numElements; i++) {
            queue.put(i);
        }
        for (int i = 0; i < numElements; i++) {
            assertEquals(i, queue.take());
        }
    }

    @Test
    void testSizeAccuracy() throws InterruptedException {
        assertEquals(0, queue.size());
        queue.put(1);
        assertEquals(1, queue.size());
        queue.put(2);
        assertEquals(2, queue.size());
        queue.take();
        assertEquals(1, queue.size());
        queue.take();
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferAfterShutdown() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testPollAfterShutdown() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }
}
```

This solution provides a thread-safe bounded blocking queue with blocking put/take, timed offer/poll, and a clean shutdown mechanism. The JUnit 5 tests cover basic functionality, edge cases, and a multi-producer/multi-consumer stress test to verify no lost or duplicated elements.The task is to write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.

Let's start by implementing the BoundedBlockingQueue class. We'll document the concurrency strategy as we go.

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic bounded blocking queue implementation.
 *
 * Concurrency Strategy:
 * - Uses a ReentrantLock for exclusive access to the queue, ensuring mutual exclusion.
 * - Two Conditions: notEmpty (signaled when an item is added) and notFull (signaled when space is available).
 * - The queue is protected by the lock, ensuring all operations on the queue are atomic.
 * - Shutdown is handled by a flag; put operations after shutdown throw an exception.
 * - Timed operations use Condition.await/unpark with timeout.
 * - The queue uses a LinkedList internally for FIFO ordering.
 *
 * @param <T> The type of elements held in this queue.
 */
public class BoundedBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a BoundedBlockingQueue with the specified capacity.
     *
     * @param capacity The maximum number of elements the queue can hold.
     * @throws IllegalArgumentException if capacity is non-positive.
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary until space is available.
     *
     * @param element The element to add.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public void put(T element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (queue.size() == capacity) {
                notFull.await();
            }
            queue.offer(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, blocking up to the specified wait time if necessary.
     *
     * @param element The element to add.
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return true if the element was added, false if the timeout expired.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("Element cannot be null");
        }
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (queue.size() == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            queue.offer(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, blocking until an element is available.
     *
     * @return The head of the queue.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            while (queue.isEmpty()) {
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
     * Retrieves and removes the head of this queue, blocking up to the specified wait time if necessary.
     *
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return The head of the queue, or null if the timeout expired.
     * @throws InterruptedException If the thread is interrupted while waiting.
     * @throws IllegalStateException If the queue is shutdown.
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
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
     * Returns the number of elements in the queue.
     *
     * @return The size of the queue.
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown of the queue. No further elements can be added via put/offer.
     * Existing elements can still be taken/poll.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll(); // Wake up any waiting consumers
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the queue is shutdown.
     *
     * @return true if the queue is shutdown.
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
```

Now, let's write the JUnit 5 tests. We'll create a test class that includes basic functionality tests and a multi-producer/multi-consumer stress test.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests concurrently (if supported)
class BoundedBlockingQueueTest {
    private static final int CAPACITY = 100;
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 5;
    private static final int NUM_ELEMENTS = 5000;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutTakeBasic() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferPollBasic() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(CAPACITY, queue.size());
        // Offer should timeout
        assertFalse(queue.offer(CAPACITY + 1, 100, TimeUnit.MILLISECONDS));
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // Queue is empty
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testInterruptedPut() throws InterruptedException {
        Thread t = new Thread(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        // Wait a bit for the put to start waiting
        Thread.sleep(100);
        t.interrupt();
        t.join();
        assertTrue(t.isInterrupted() || !t.isAlive());
        assertEquals(0, queue.size());
    }

    @Test
    void testInterruptedTake() throws InterruptedException {
        // Start a consumer that will block
        Thread t = new Thread(() -> {
            try {
                queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join();
        assertTrue(t.isInterrupted() || !t.isAlive());
        assertEquals(0, queue.size());
    }

    @Test
    void testShutdownPut() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownTake() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testMultipleProducersConsumers() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_PRODUCERS + NUM_CONSUMERS);
        List<Integer> produced = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < NUM_ELEMENTS / NUM_PRODUCERS; j++) {
                        int value = producerId * (NUM_ELEMENTS / NUM_PRODUCERS) + j;
                        queue.put(value);
                        produced.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (produced.size() > consumed.size()) {
                        Integer value = queue.take();
                        if (value != null) {
                            consumed.add(value);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        queue.shutdown();

        // Verify all produced elements were consumed exactly once
        assertEquals(produced.size(), consumed.size());
        assertTrue(produced.containsAll(consumed) && consumed.containsAll(produced));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int elementsPerProducer = 1000;
        int totalProduced = numProducers * elementsPerProducer;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);

        List<Integer> produced = Collections.synchronizedList(new ArrayList<>(totalProduced));
        List<Integer