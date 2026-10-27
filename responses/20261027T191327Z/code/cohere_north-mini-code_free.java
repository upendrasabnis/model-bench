<!-- model: cohere/north-mini-code:free | category: code | run: 20261027T191327Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
```java
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue implementation.
 * <p>
 * This queue uses an internal array to store elements and supports blocking put/take,
 * timed offer/poll, and a clean shutdown mechanism. The implementation uses
 * {@link ReentrantLock} and {@link Condition} variables for synchronization, providing
 * more flexibility than {@code synchronized} blocks and allowing for timed operations.
 * </p>
 *
 * <h3>Concurrency Strategy</h3>
 * <p>
 * The queue maintains an internal array of fixed capacity. The following invariants
 * are enforced:
 * <ul>
 *   <li>The queue is empty when {@code head == tail}.</li>
 *   <li>The queue is full when {@code (tail + 1) % capacity == head}.</li>
 * </ul>
 * </p>
 * <p>
 * The queue uses two condition variables:
 * <ul>
 *   <li>{@code notEmpty}: signaled when an element is added to the queue, allowing
 *       consumers to proceed.</li>
 *   <li>{@code notFull}: signaled when an element is removed from the queue, allowing
 *       producers to proceed.</li>
 * </ul>
 * </p>
 * <p>
 * The queue supports a clean shutdown via the {@code shutdown()} method. When shutdown
 * is invoked, all waiting producers and consumers are notified and the queue will
 * no longer accept new elements. The {@code take()} and {@code poll()} methods will
 * return {@code null} if the queue is empty after shutdown.
 * </p>
 *
 * @param <T> the type of elements held in this queue
 */
public class BoundedBlockingQueue<T> {
    private final T[] array;
    private int head = 0;
    private int tail = 0;
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a {@code BoundedBlockingQueue} with the specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if {@code capacity} is less than or equal to zero
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.array = (T[]) new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so
     * immediately without exceeding the queue's capacity, returning {@code true}
     * upon success and {@code false} if the queue is full.
     *
     * @param t the element to add
     * @return {@code true} if the element was added, {@code false} if the queue is full
     */
    public boolean offer(T t) {
        lock.lock();
        try {
            if (isFull()) {
                return false;
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary for space
     * to become available.
     *
     * @param t the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void put(T t) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (isFull() && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the specified
     * timeout if necessary for space to become available.
     *
     * @param t the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the queue is full
     *         after the timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (isFull() && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue. This method blocks until an
     * element is available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (isEmpty() && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && isEmpty()) {
                return null;
            }
            T t = array[head];
            array[head] = null; // help GC
            head = (head + 1) % array.length;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * timeout if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the queue is empty after the
     *         timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (isEmpty() && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && isEmpty()) {
                return null;
            }
            T t = array[head];
            array[head] = null; // help GC
            head = (head + 1) % array.length;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements
     */
    public int size() {
        lock.lock();
        try {
            if (tail >= head) {
                return tail - head;
            } else {
                return tail + array.length - head;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue is empty.
     *
     * @return {@code true} if this queue is empty
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return head == tail;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue is full.
     *
     * @return {@code true} if this queue is full
     */
    public boolean isFull() {
        lock.lock();
        try {
            return (tail + 1) % array.length == head;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown of the queue. After shutdown, no new elements can be added,
     * and any waiting producers and consumers are notified.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the capacity of this queue.
     *
     * @return the capacity
     */
    public int capacity() {
        return array.length;
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return Arrays.toString(Arrays.copyOfRange(array, head, tail));
        } finally {
            lock.unlock();
        }
    }
}
```

### Step 5: Write JUnit 5 Tests

We'll write comprehensive tests for the `BoundedBlockingQueue` class. We'll create a test class `BoundedBlockingQueueTest` with various test methods.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30) // Timeout for all tests
class BoundedBlockingQueueTest {
    private static final int CAPACITY = 10;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testOfferAndPoll() {
        assertTrue(queue.offer(1));
        assertEquals(1, queue.size());
        assertEquals(Integer.valueOf(1), queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i));
        }
        // Queue is full, offer should return false
        assertFalse(queue.offer(CAPACITY));
        // Take one element
        queue.take();
        // Now offer should succeed
        assertTrue(queue.offer(CAPACITY));
        assertEquals(CAPACITY, queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testPutTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        // Queue is full, offer with timeout should return false
        assertFalse(queue.offer(CAPACITY, 1, TimeUnit.SECONDS));
        // Take one element
        queue.take();
        // Now offer with timeout should succeed
        assertTrue(queue.offer(CAPACITY, 1, TimeUnit.SECONDS));
        assertEquals(CAPACITY, queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testBlockingPutWhenFull() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        // Start a producer thread that will block until space is available
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                queue.put(CAPACITY); // Should block until space is available
                producerFinished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        producerStarted.await(); // Ensure producer has started and is waiting
        // Take one element to make space
        queue.take();
        producerFinished.await(5, TimeUnit.SECONDS); // Wait for producer to finish
        assertEquals(CAPACITY, queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testBlockingTakeWhenEmpty() throws InterruptedException {
        // Start a consumer thread that will block until an element is available
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch consumerFinished = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                assertEquals(0, queue.take()); // Should block until element is available
                consumerFinished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        consumerStarted.await(); // Ensure consumer has started and is waiting
        // Put an element to make it available
        queue.put(0);
        consumerFinished.await(5, TimeUnit.SECONDS); // Wait for consumer to finish
        assertTrue(queue.isEmpty());
    }

    @Test
    void testShutdown() throws InterruptedException {
        // Start a producer thread that will block
        CountDownLatch producerStarted = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                queue.put(1); // Should block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        producerStarted.await();
        // Shutdown the queue
        queue.shutdown();
        producer.join(5, TimeUnit.SECONDS); // Wait for producer to finish
        assertTrue(producer.isInterrupted() || !producer.isAlive());
        // Take should return null after shutdown and empty queue
        assertNull(queue.take());
    }

    @Test
    void testPollAfterShutdown() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertNull(queue.poll(1, TimeUnit.SECONDS)); // Should return null after shutdown
        queue.put(2); // Should throw IllegalStateException
        fail("Expected IllegalStateException");
    }

    @Test
    void testMultipleProducersConsumers() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int elementsPerProducer = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        CountDownLatch producerLatch = new CountDownLatch(numProducers);
        CountDownLatch consumerLatch = new CountDownLatch(numConsumers);
        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);
        List<Integer> producedList = new ArrayList<>();
        List<Integer> consumedList = new ArrayList<>();

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < elementsPerProducer; j++) {
                        int value = producerId * elementsPerProducer + j;
                        queue.put(value);
                        totalProduced.incrementAndGet();
                        producedList.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producerLatch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        Integer value = queue.poll(1, TimeUnit.SECONDS);
                        if (value == null) {
                            break; // Shutdown
                        }
                        totalConsumed.incrementAndGet();
                        consumedList.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumerLatch.countDown();
                }
            });
        }

        // Wait for all producers to finish
        producerLatch.await();
        // Shutdown the queue
        queue.shutdown();
        // Wait for all consumers to finish
        consumerLatch.await();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify all elements were produced and consumed
        assertEquals(numProducers * elementsPerProducer, totalProduced.get());
        assertEquals(numProducers * elementsPerProducer, totalConsumed.get());
        // Verify no lost or duplicated elements
        assertEquals(producedList.size(), consumedList.size());
        producedList.sort(Integer::compareTo);
        consumedList.sort(Integer::compareTo);
        assertEquals(producedList, consumedList);
    }

    @Test
    void testQueueCapacity() {
        assertEquals(CAPACITY, queue.capacity());
    }

    @Test
    void testIsEmptyAndIsFull() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertFalse(queue.isEmpty());
        assertTrue(queue.isFull());
    }

    @Test
    void testToString() {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals("[1, 2, 3]", queue.toString());
    }
}
```

### Explanation

- **BoundedBlockingQueue**: A generic bounded blocking queue implementation using `ReentrantLock` and `Condition` for synchronization. It supports blocking `put`/`take`, timed `offer`/`poll`, and a clean shutdown mechanism.
- **Concurrency Strategy**: The queue uses two condition variables (`notEmpty` and `notFull`) to signal threads waiting on queue operations. The queue maintains invariants for empty and full states, ensuring thread-safe operations.
- **JUnit 5 Tests**: The tests cover basic operations, blocking behavior, timed operations, shutdown, and a multi-producer/multi-consumer stress test to verify no lost or duplicated elements. The stress test uses `CountDownLatch` and `ExecutorService` to coordinate threads and verify correctness.

This implementation provides a thread-safe, generic bounded blocking queue with all required features and comprehensive test coverage.To implement a thread-safe, generic bounded blocking queue in Java (without using `java.util.concurrent.BlockingQueue`), we can use an array-based approach with `ReentrantLock` and `Condition` variables for synchronization. This approach provides more flexibility than `synchronized` blocks and allows for timed operations. The queue will support blocking `put`/`take`, timed `offer`/`poll`, and a clean shutdown mechanism.

### Step 1: Implement the Thread-Safe Bounded Blocking Queue

We'll create a class `BoundedBlockingQueue<T>` with the following key features:
- **Generic type**: The queue can hold any type of elements.
- **Fixed capacity**: The queue size is bounded by a specified capacity.
- **Blocking operations**: `put` and `take` methods that block when the queue is full or empty.
- **Non-blocking operations**: `offer` and `poll` methods with optional timeout.
- **Clean shutdown**: A `shutdown` method to stop accepting new elements and notify waiting threads.

Here's the implementation:```java
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue implementation.
 * <p>
 * This queue uses an internal array to store elements and supports blocking put/take,
 * timed offer/poll, and a clean shutdown mechanism. The implementation uses
 * {@link ReentrantLock} and {@link Condition} variables for synchronization, providing
 * more flexibility than {@code synchronized} blocks and allowing for timed operations.
 * </p>
 *
 * <h3>Concurrency Strategy</h3>
 * <p>
 * The queue maintains an internal array of fixed capacity. The following invariants
 * are enforced:
 * <ul>
 *   <li>The queue is empty when {@code head == tail}.</li>
 *   <li>The queue is full when {@code (tail + 1) % capacity == head}.</li>
 * </ul>
 * </p>
 * <p>
 * The queue uses two condition variables:
 * <ul>
 *   <li>{@code notEmpty}: signaled when an element is added to the queue, allowing
 *       consumers to proceed.</li>
 *   <li>{@code notFull}: signaled when an element is removed from the queue, allowing
 *       producers to proceed.</li>
 * </ul>
 * </p>
 * <p>
 * The queue supports a clean shutdown via the {@code shutdown()} method. When shutdown
 * is invoked, all waiting producers and consumers are notified and the queue will
 * no longer accept new elements. The {@code take()} and {@code poll()} methods will
 * return {@code null} if the queue is empty after shutdown.
 * </p>
 *
 * @param <T> the type of elements held in this queue
 */
public class BoundedBlockingQueue<T> {
    private final T[] array;
    private int head = 0;
    private int tail = 0;
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a {@code BoundedBlockingQueue} with the specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if {@code capacity} is less than or equal to zero
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.array = (T[]) new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so
     * immediately without exceeding the queue's capacity, returning {@code true}
     * upon success and {@code false} if the queue is full.
     *
     * @param t the element to add
     * @return {@code true} if the element was added, {@code false} if the queue is full
     */
    public boolean offer(T t) {
        lock.lock();
        try {
            if (isFull()) {
                return false;
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary for space
     * to become available.
     *
     * @param t the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void put(T t) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (isFull() && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the specified
     * timeout if necessary for space to become available.
     *
     * @param t the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the queue is full
     *         after the timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (isFull() && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = t;
            tail = (tail + 1) % array.length;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue. This method blocks until an
     * element is available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (isEmpty() && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && isEmpty()) {
                return null;
            }
            T t = array[head];
            array[head] = null; // help GC
            head = (head + 1) % array.length;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * timeout if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the queue is empty after the
     *         timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (isEmpty() && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && isEmpty()) {
                return null;
            }
            T t = array[head];
            array[head] = null; // help GC
            head = (head + 1) % array.length;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number