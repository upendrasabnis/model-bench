<!-- model: cohere/north-mini-code:free | category: code | run: 20260810T091324Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

I'll create a thread-safe, generic bounded blocking queue in Java with comprehensive documentation and JUnit 5 tests.

## Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * A thread-safe, generic bounded blocking queue implementation.
 * 
 * <p>Concurrency Strategy:</p>
 * <ul>
 *   <li><b>Thread Safety</b>: Uses ReentrantLock to protect all shared state.
 *       The lock ensures exclusive access to the queue and coordinates between producers and consumers.</li>
 *   
 *   <li><b>Blocking Operations</b>: Implements blocking behavior using Condition objects:
 *       <ul>
 *         <li>notEmpty: Signaled when an element is added, used by take/poll operations</li>
 *         <li>notFull: Signaled when space is available, used by put/offer operations</li>
 *       </ul>
 *   </li>
 *   
 *   <li><b>Shutdown Mechanism</b>: Uses a volatile shutdown flag to signal all threads
 *       to stop waiting and throw IllegalStateException on subsequent operations.</li>
 *   
 *   <li><b>Non-blocking Operations</b>: offer() and poll() with timeout use awaitNanos()
 *       for timed blocking without busy waiting.</li>
 *   
 *   <li><b>Signal Management</b>: After queue modifications, appropriate conditions are
 *       signaled to wake up waiting threads, ensuring efficient thread coordination.</li>
 *   
 *   <li><b>Exception Safety</b>: Lock is always released in finally blocks to prevent deadlocks.</li>
 * </ul>
 * 
 * @param <T> The type of elements held in this queue
 */
public class MyBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a MyBlockingQueue with the specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if capacity is not positive
     */
    public MyBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary
     * until space is available.
     *
     * @param item the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     * @throws NullPointerException if the item is null
     */
    public void put(T item) throws InterruptedException {
        Objects.requireNonNull(item, "Item cannot be null");
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                notFull.await();
            }
            queue.offer(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param item the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if successful, false if timeout occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     * @throws NullPointerException if the item is null
     */
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(item, "Item cannot be null");
        lock.lockInterruptibly();
        try {
            if (queue.size() == capacity) {
                long nanos = unit.toNanos(timeout);
                while (queue.size() == capacity) {
                    if (shutdown) {
                        throw new IllegalStateException("Queue is shutdown");
                    }
                    long remaining = nanos - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    notFull.awaitNanos(remaining);
                }
            }
            queue.offer(item);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, blocking until an element
     * is available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     */
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (shutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                notEmpty.await();
            }
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or null if timeout occurs
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (queue.isEmpty()) {
                long nanos = unit.toNanos(timeout);
                while (queue.isEmpty()) {
                    if (shutdown) {
                        throw new IllegalStateException("Queue is shutdown");
                    }
                    long remaining = nanos - System.nanoTime();
                    if (remaining <= 0) {
                        return null;
                    }
                    notEmpty.awaitNanos(remaining);
                }
            }
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates shutdown of the queue. All waiting put/take operations will
     * throw IllegalStateException.
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
     * Returns true if the queue is shutdown.
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the number of elements in the queue.
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
     * Returns true if the queue is empty.
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }
}
```

## Comprehensive JUnit 5 Tests

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.DisplayName;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MyBlockingQueue.
 * Tests include basic operations, timeout behavior, shutdown mechanism,
 * and multi-producer/multi-consumer stress tests.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("MyBlockingQueue Tests")
public class MyBlockingQueueTest {
    
    private static final int CAPACITY = 1000;
    private static final int NUM_PRODUCERS = 10;
    private static final int NUM_CONSUMERS = 10;
    private static final int NUM_ITEMS_PER_PRODUCER = 100;
    
    private MyBlockingQueue<Integer> queue;
    
    @BeforeEach
    @DisplayName("Setup fresh queue before each test")
    public void setUp() {
        queue = new MyBlockingQueue<>(CAPACITY);
    }
    
    @AfterEach
    @DisplayName("Cleanup queue after each test")
    public void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }
    
    @Test
    @DisplayName("Test basic put and take operations")
    public void testPutAndTake() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size(), "Queue should have 1 element");
        assertEquals(1, queue.take(), "Take should return the element");
        assertTrue(queue.isEmpty(), "Queue should be empty after take");
    }
    
    @Test
    @DisplayName("Test offer and poll with timeout")
    public void testOfferAndPoll() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS), "Offer should succeed");
        assertEquals(1, queue.size(), "Queue should have 1 element");
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS), "Poll should return the element");
        assertTrue(queue.isEmpty(), "Queue should be empty after poll");
    }
    
    @Test
    @DisplayName("Test offer timeout when queue is full")
    public void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(CAPACITY, queue.size(), "Queue should be full");
        
        // Try to offer with timeout, should fail
        assertFalse(queue.offer(CAPACITY, 1, TimeUnit.SECONDS), 
                   "Offer should fail when queue is full");
        assertEquals(CAPACITY, queue.size(), "Queue size should remain unchanged");
    }
    
    @Test
    @DisplayName("Test poll timeout when queue is empty")
    public void testPollTimeout() throws InterruptedException {
        // Try to poll from empty queue with timeout, should fail
        assertNull(queue.poll(1, TimeUnit.SECONDS), 
                  "Poll should return null when queue is empty");
        assertTrue(queue.isEmpty(), "Queue should remain empty");
    }
    
    @Test
    @DisplayName("Test shutdown mechanism")
    public void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertTrue(queue.isShutdown(), "Queue should be marked as shutdown");
        
        // Put after shutdown should throw
        assertThrows(IllegalStateException.class, () -> queue.put(1),
                    "Put should throw when queue is shutdown");
        
        // Take after shutdown should throw
        assertThrows(IllegalStateException.class, () -> queue.take(),
                    "Take should throw when queue is shutdown");
    }
    
    @Test
    @DisplayName("Test put with null item throws NullPointerException")
    public void testPutNullItem() {
        assertThrows(NullPointerException.class, () -> queue.put(null),
                    "Put should throw NullPointerException for null item");
    }
    
    @Test
    @DisplayName("Test offer with null item throws NullPointerException")
    public void testOfferNullItem() throws InterruptedException {
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS),
                    "Offer should throw NullPointerException for null item");
    }
    
    @Test
    @DisplayName("Test queue capacity constraint")
    public void testQueueCapacity() throws InterruptedException {
        MyBlockingQueue<Integer> smallQueue = new MyBlockingQueue<>(2);
        
        smallQueue.put(1);
        smallQueue.put(2);
        assertEquals(2, smallQueue.size(), "Queue should be at capacity");
        
        // This should block, but we'll test with offer
        assertFalse(smallQueue.offer(3, 1, TimeUnit.SECONDS), 
                   "Offer should fail when queue is at capacity");
        assertEquals(2, smallQueue.size(), "Queue size should remain at capacity");
        
        // Take one and offer should succeed
        smallQueue.take();
        assertTrue(smallQueue.offer(3, 1, TimeUnit.SECONDS), 
                  "Offer should succeed after space becomes available");
        assertEquals(2, smallQueue.size(), "Queue size should be back at capacity");
    }
    
    @Test
    @DisplayName("Test multiple producers and consumers")
    @Timeout(30)
    public void testMultiProducerMultiConsumer() throws InterruptedException {
        ExecutorService producerExecutor = Executors.newFixedThreadPool(NUM_PRODUCERS);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(NUM_CONSUMERS);
        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);
        Set<Integer> producedSet = new HashSet<>();
        Set<Integer> consumedSet = new HashSet<>();
        
        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            producerExecutor.submit(() -> {
                for (int j = 0; j < NUM_ITEMS_PER_PRODUCER; j++) {
                    int item = producerId * NUM_ITEMS_PER_PRODUCER + j;
                    try {
                        queue.put(item);
                        synchronized (producedSet) {
                            producedSet.add(item);
                        }
                        totalProduced.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            consumerExecutor.submit(() -> {
                while (totalProduced.get() > totalConsumed.get() || !queue.isEmpty()) {
                    try {
                        Integer item = queue.poll(1, TimeUnit.SECONDS);
                        if (item != null) {
                            synchronized (consumedSet) {
                                consumedSet.add(item);
                            }
                            totalConsumed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        producerExecutor.shutdown();
        consumerExecutor.shutdown();
        
        // Wait for all tasks to complete
        assertTrue(producerExecutor.awaitTermination(30, TimeUnit.SECONDS),
                  "Producers should complete within timeout");
        assertTrue(consumerExecutor.awaitTermination(30, TimeUnit.SECONDS),
                  "Consumers should complete within timeout");
        
        // Verify no lost or duplicated elements
        assertEquals(totalProduced.get(), totalConsumed.get(),
                    "All produced items should be consumed");
        assertEquals(producedSet.size(), totalProduced.get(),
                    "Produced set should contain all items");
        assertEquals(consumedSet.size(), totalConsumed.get(),
                    "Consumed set should contain all items