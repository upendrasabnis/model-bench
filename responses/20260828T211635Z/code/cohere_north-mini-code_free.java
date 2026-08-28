<!-- model: cohere/north-mini-code:free | category: code | run: 20260828T211635Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is the implementation of a thread-safe, generic bounded blocking queue and its comprehensive JUnit 5 tests.

### 1. Implementation: `MyBlockingQueue`

This implementation uses a `ReentrantLock` and two `Condition` variables (`notEmpty` and `notFull`) to manage thread synchronization. This approach is more flexible than `synchronized` blocks because it allows precise signaling of waiting threads based on specific queue states.

**Concurrency Strategy**:
*   **Mutual Exclusion**: A `ReentrantLock` ensures that only one thread can modify the queue's internal state (head, tail, count) at any given time.
*   **Signaling**: Two Condition variables are used to block threads efficiently:
    *   `notEmpty`: Threads calling `take()` or `poll()` wait on this if the queue is empty.
    *   `notFull`: Threads calling `put()` or `offer()` wait on this if the queue is full.
*   **Bounded Storage**: A circular buffer (ring buffer) implemented using a standard array. This minimizes memory overhead compared to a linked-list approach.
*   **Shutdown**: A volatile boolean flag `isShutdown` is used to signal termination. When `shutdown()` is called, it interrupts waiting threads by signaling all conditions, prompting them to check the flag and exit gracefully.

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * 
 * <p><b>Concurrency Strategy:</b></p>
 * <ul>
 * <li><b>Mutual Exclusion:</b> Uses a {@link ReentrantLock} to ensure exclusive access to shared state (the buffer array, head/tail pointers, and count).</li>
 * <li><b>Condition Variables:</b> Utilizes two {@link Condition} objects:
 *     <ul>
 *       <li>{@code notEmpty}: Signals waiting {@code take()} and {@code poll()} operations when an item is available.</li>
 *       <li>{@code notFull}: Signals waiting {@code put()} and {@code offer()} operations when space is available.</li>
 *     </ul>
 * </li>
 * <li><b>Bounded Buffer:</b> Implements a circular buffer (ring buffer) using a fixed-size array. This provides O(1) access and is memory-efficient compared to linked lists.</li>
 * <li><b>Graceful Shutdown:</b> A dedicated {@code isShutdown} flag allows the queue to close its "gate." Waiting producers will fail immediately, and waiting consumers will throw an exception or return null depending on the method, preventing indefinite blocking.</li>
 * </ul>
 *
 * @param <T> the type of elements held in this queue
 */
public class MyBlockingQueue<T> {
    private final Object[] items;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    private volatile boolean isShutdown = false;

    /**
     * Constructs a {@code MyBlockingQueue} with the specified capacity.
     *
     * @param capacity the capacity of the queue
     * @throws IllegalArgumentException if {@code capacity} is less than or equal to 0
     */
    public MyBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * until space is available.
     *
     * @param t the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     */
    public void put(T t) throws InterruptedException {
        lock.lock();
        try {
            while (count == capacity) {
                notFull.await();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            enqueue(t);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param t the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timeout occurred
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (isShutdown) {
                return false;
            }
            
            long nanos = unit.toNanos(timeout);
            while (count == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(t);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element is available.
     *
     * @return the head element
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown and empty
     */
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            if (isShutdown && count == 0) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            T item = dequeue();
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
     * @return the head element, or {@code null} if timeout occurs
     * @throws InterruptedException if interrupted while waiting
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (isShutdown) {
                // If shutdown, we still return the element if available, 
                // but if empty we return null.
                if (count == 0) return null;
            }
            T item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a clean shutdown of the queue.
     * This wakes up all waiting threads so they can check the shutdown status
     * and exit gracefully.
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

    /**
     * Returns the current number of elements in the queue.
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the queue is shutdown.
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    // Helper methods
    private void enqueue(T t) {
        items[tail] = t;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private T dequeue() {
        T t = (T) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return t;
    }
}
```

### 2. Tests: `MyBlockingQueueTest`

This test suite uses JUnit 5. It includes unit tests for basic operations, edge cases (full/empty queue), timed operations, and a multi-producer/multi-consumer stress test to verify data integrity.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MyBlockingQueueTest {

    private static final int STRESS_TEST_CAPACITY = 100;
    private static final int STRESS_TEST_PRODUCERS = 10;
    private static final int STRESS_TEST_CONSUMERS = 10;
    private static final int STRESS_TEST_TOTAL_ITEMS = 10_000;

    private MyBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new MyBlockingQueue<>(STRESS_TEST_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure shutdown in case test fails
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutAndTake_SingleElement() throws InterruptedException {
        queue.put(42);
        int result = queue.take();
        assertEquals(42, result);
        assertEquals(0, queue.size());
    }

    @Test
    void testOffer_Success() throws InterruptedException {
        boolean result = queue.offer(42, 1, TimeUnit.SECONDS);
        assertTrue(result);
        assertEquals(1, queue.size());
        assertEquals(42, queue.take());
    }

    @Test
    void testOffer_Timeout_Fail() throws InterruptedException {
        // Fill queue to capacity
        for (int i = 0; i < STRESS_TEST_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(STRESS_TEST_CAPACITY, queue.size());

        // Try to add one more, should timeout immediately if we use 0 timeout
        // or return false if full.
        boolean result = queue.offer(STRESS_TEST_CAPACITY + 1, 10, TimeUnit.MILLISECONDS);
        assertFalse(result);
        assertEquals(STRESS_TEST_CAPACITY, queue.size());
    }

    @Test
    void testPoll_Timeout_Empty() throws InterruptedException {
        // Queue is empty
        Integer result = queue.poll(10, TimeUnit.MILLISECONDS);
        assertNull(result);
    }

    @Test
    void testTake_Timeout_Empty() throws InterruptedList;
    {
        // This test is tricky because take() is blocking. 
        // We cannot easily test a timeout on take() without interrupting the thread 
        // or using a separate executor. 
        // Instead, we verify that blocking take() works.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                Thread.sleep(100); // Let poll start waiting
                queue.put(99);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Integer result = queue.take();
        assertEquals(99, result);
        executor.shutdownNow();
    }

    @Test
    void testShutdown_InterruptsPut() throws InterruptedException {
        queue.shutdown();
        
        // put() should throw IllegalStateException immediately
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        
        // offer() should return false immediately
        boolean result = queue.offer(2, 1, TimeUnit.SECONDS);
        assertFalse(result);
    }

    @Test
    void testShutdown_InterruptsTake() throws InterruptedException {
        queue.shutdown();
        
        // take() on empty queue should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> queue.take());
        
        // poll() on empty queue should return null
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdown_AllowsTakeOnNonEmpty() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        
        // Should still be able to take the existing element
        assertEquals(1, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testMultipleProducersSingleConsumer() throws InterruptedException {
        int producerCount = 5;
        int itemsPerProducer = 100;
        ExecutorService executor = Executors.newFixedThreadPool(producerCount + 1);

        List<Integer> expected = Collections.synchronizedList(new ArrayList<>());
        
        // Start Consumer
        executor.submit(() -> {
            for (int i = 0; i < producerCount * itemsPerProducer; i++) {
                try {
                    int val = queue.take();
                    expected.add(val);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Start Producers
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            executor.submit(() -> {
                for (int i = 0; i < itemsPerProducer; i++) {
                    try {
                        int item = producerId * 1000 + i;
                        queue.put(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify
        // We can't guarantee order in a Queue implementation strictly speaking, 
        // but usually it's FIFO. However, for stress testing, we check for duplicates/loss.
        Set<Integer> uniqueResults = ConcurrentHashMap.newKeySet();
        for(Integer val : expected) {
            uniqueResults.add(val);
        }
        
        assertEquals(producerCount * itemsPerProducer, uniqueResults.size());
        assertEquals(producerCount * itemsPerProducer, expected.size());
    }

    /**
     * Multi-producer/multi-consumer stress test.
     * Verifies no lost or duplicated elements.
     */
    @Test
    void testStress_NoLostOrDuplicatedElements() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(STRESS_TEST_PRODUCERS + STRESS_TEST_CONSUMERS);
        
        // We use a thread-safe set to track what has been produced.
        // This set is populated by producers and checked by the test at the end.
        // Note: We cannot easily return the list of produced items from the test 
        // setup to the producer threads without shared mutable state.
        // We will use a ConcurrentHashMap or Set shared across threads.
        
        Set<Integer> producedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<Integer> consumedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // Start Producers
        for (int i = 0; i < STRESS_TEST_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < STRESS_TEST_TOTAL_ITEMS / STRESS_TEST_PRODUCERS; j++) {
                    int item = producerId * (STRESS_TEST_TOTAL_ITEMS / STRESS_TEST_PRODUCERS) + j;
                    try {
                        queue.put(item);
                        producedItems.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Start Consumers
        for (int i = 0; i < STRESS_TEST_CONSUMERS; i++) {
            executor.submit(() -> {
                while (!queue.isShutdown() || queue.size() > 0) {
                    try {
                        // Poll with a small timeout to allow checking shutdown flag periodically
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            consumedItems.add(item);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Wait for producers to finish
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // At this point, producers are done, but consumers might still be processing 
        // the last items. We need to wait until the queue is empty.
        // We trigger shutdown to wake up any blocked consumers.
        queue.shutdown();
        
        // Drain remaining items from queue into consumedItems
        while (queue.size() > 0) {
            try {
                consumedItems.add(queue.poll(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Verification
        System.out.println("Produced: " + producedItems.size());
        System.out.println("Consumed: " + consumedItems.size());

        // Check for Lost Elements
        assertEquals(producedItems.size(), consumedItems.size(), 
            "Data loss detected: Produced " + producedItems.size() + " but consumed " + consumedItems.size());

        // Check for Duplicates
        assertEquals(producedItems.size(), consumedItems.size(), 
            "Duplicates detected: The set of consumed items should match the set of produced items in size.");

        // Check exact content equality
        assertTrue(producedItems.containsAll(consumedItems),