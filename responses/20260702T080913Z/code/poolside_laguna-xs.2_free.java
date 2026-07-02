<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260702T080913Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->

# Thread-Safe Bounded Blocking Queue Implementation

## Implementation

```java
import java.util.concurrent.*;
import java.util.*;

/**
 * A thread-safe, generic bounded blocking queue implementation.
 * 
 * <p>This implementation provides blocking put/take operations and timed offer/poll operations
 * without using java.util.concurrent.BlockingQueue. It uses ReentrantLock and Condition objects
 * for fine-grained concurrency control.
 * 
 * <p>The queue follows the same semantics as {@link BlockingQueue} but implements the blocking
 * behavior from scratch.
 * 
 * <p><b>Concurrency Strategy:</b></p>
 * <ul>
 *   <li>Uses a single ReentrantLock to protect all shared state</li>
 *   <li>Two Condition objects: notFull (for producers) and notEmpty (for consumers)</li>
 *   <li>Circular buffer implementation for efficient O(1) operations</li>
 *   <li>Volatile shutdown flag for immediate visibility across threads</li>
 *   <li>All state mutations occur within the lock; conditions are signaled on state changes</li>
 * </ul>
 * 
 * @param <T> the type of elements held in this queue
 */
public class BoundedBlockingQueue<T> {
    
    private final T[] buffer;
    private final ReentrantLock lock;
    private final Condition notFull;
    private final Condition notEmpty;
    
    private int putIndex;      // index for next put
    private int takeIndex;     // index for next take
    private int count;         // number of elements in queue
    private volatile boolean shutdown;
    
    /**
     * Creates a new bounded blocking queue with the specified capacity.
     *
     * @param capacity the maximum number of elements this queue can hold
     * @throws IllegalArgumentException if capacity is not greater than zero
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.buffer = (T[]) new Object[capacity];
        this.lock = new ReentrantLock();
        this.notFull = lock.newCondition();
        this.notEmpty = lock.newCondition();
    }
    
    /**
     * Inserts the specified element into this queue, blocking if the queue is full.
     *
     * @param item the element to insert
     * @throws InterruptedException if the current thread is interrupted while blocking
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (!shutdown && count == buffer.length) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            buffer[putIndex] = item;
            putIndex = (putIndex + 1) % buffer.length;
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Retrieves and removes the head of this queue, blocking if the queue is empty.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted while blocking
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (!shutdown && count == 0) {
                notEmpty.await();
            }
            if (shutdown && count == 0) {
                throw new IllegalStateException("Queue has been shut down and is empty");
            }
            T item = buffer[takeIndex];
            buffer[takeIndex] = null;  // help GC
            takeIndex = (takeIndex + 1) % buffer.length;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to insert the specified element into this queue, blocking until
     * the specified timeout elapses if the queue is full.
     *
     * @param item the element to insert
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@link TimeUnit} determining how to interpret the timeout argument
     * @return {@code true} if the element was successfully inserted, or {@code false}
     *         if the timeout elapsed before space was available or the queue was shut down
     * @throws InterruptedException if the current thread is interrupted while blocking
     */
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (!shutdown && count == buffer.length) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                return false;
            }
            buffer[putIndex] = item;
            putIndex = (putIndex + 1) % buffer.length;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Retrieves and removes the head of this queue, blocking until the specified
     * timeout elapses if the queue is empty.
     *
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit a {@link TimeUnit} determining how to interpret the timeout argument
     * @return the head of this queue, or {@code null} if the timeout elapsed
     *         before an element became available or the queue was shut down
     * @throws InterruptedException if the current thread is interrupted while blocking
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (!shutdown && count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && count == 0) {
                return null;
            }
            T item = buffer[takeIndex];
            buffer[takeIndex] = null;  // help GC
            takeIndex = (takeIndex + 1) % buffer.length;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Shuts down this queue, interrupting all waiting threads.
     * After shutdown, any remaining elements can still be taken,
     * but no new elements can be added.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notFull.signalAll();   // Wake up all waiting producers
            notEmpty.signalAll();  // Wake up all waiting consumers
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns whether this queue has been shut down.
     *
     * @return {@code true} if the queue has been shut down
     */
    public boolean isShutdown() {
        return shutdown;
    }
    
    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
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
     * Returns whether this queue is empty.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the capacity of this queue.
     *
     * @return the capacity of this queue
     */
    public int capacity() {
        return buffer.length;
    }
}
```

## Comprehensive JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {
    
    // Basic functionality tests
    
    @Test
    void testBasicPutTake() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        
        queue.put("first");
        queue.put("second");
        assertEquals(2, queue.size());
        
        assertEquals("first", queue.take());
        assertEquals("second", queue.take());
        assertEquals(0, queue.size());
    }
    
    @Test
    void testOfferPoll() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        
        assertTrue(queue.offer("first", 1, TimeUnit.SECONDS));
        assertTrue(queue.offer("second", 1, TimeUnit.SECONDS));
        assertFalse(queue.offer("third", 1, TimeUnit.SECONDS)); // Queue is full
        
        assertEquals("first", queue.poll(1, TimeUnit.SECONDS));
        assertEquals("second", queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll(1, TimeUnit.SECONDS)); // Queue is empty
    }
    
    @Test
    void testCapacity() {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(10);
        assertEquals(10, queue.capacity());
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }
    
    @Test
    void testSizeAndIsEmpty() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(3);
        
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        
        queue.put(1);
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());
        
        queue.put(2);
        queue.put(3);
        assertEquals(3, queue.size());
        
        queue.take();
        assertEquals(2, queue.size());
    }
    
    @Test
    void testInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }
    
    // Blocking behavior tests
    
    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch consumerReady = new CountDownLatch(1);
        AtomicBoolean putCompleted = new AtomicBoolean(false);
        
        queue.put("initial");
        
        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                queue.put("second", consumerReady);
                putCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        
        // Wait for producer to start and block
        producerStarted.await();
        Thread.sleep(100);
        assertFalse(putCompleted.get(), "Put should block");
        
        // Consume one item to make room
        assertEquals("initial", queue.take());
        consumerReady.countDown();
        
        // Wait for producer to complete
        producer.join(1000);
        assertTrue(putCompleted.get(), "Put should complete after space available");
    }
    
    private void queue(BoundedBlockingQueue<String> queue, CountDownLatch consumerReady) throws InterruptedException {
        queue.take();
        consumerReady.countDown();
    }
    
    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch producerReady = new CountDownLatch(1);
        AtomicBoolean takeCompleted = new AtomicBoolean(false);
        
        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                T take result = queue.take();
                takeCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        
        consumerStarted.await();
        Thread.sleep(100);
        assertFalse(takeCompleted.get(), "Take should block");
        
        // Add an item to make the consumer proceed
        queue.put("item");
        producerReady.countDown();
        
        consumer.join(1000);
        assertTrue(takeCompleted.get(), "Take should complete after item available");
    }
    
    // Timed operation tests
    
    @Test
    void testOfferTimesOut() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.put("full");
        
        assertTimeoutPreemptively(Duration.ofMillis(100), () -> {
            assertFalse(queue.offer("overflow", 100, TimeUnit.MILLISECONDS));
        });
    }
    
    @Test
    void testPollTimesOut() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        
        assertTimeoutPreemptively(Duration.ofMillis(100), () -> {
            assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        });
    }
    
    @Test
    void testOfferSucceedsBeforeTimeout() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        queue.put("one");
        
        assertTrue(queue.offer("two", 1, TimeUnit.SECONDS));
        assertEquals(2, queue.size());
    }
    
    // Shutdown tests
    
    @Test
    void testShutdownInterruptsWaitingProducers() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.put("full");
        
        Thread producer = new Thread(() -> {
            try {
                queue.put("overflow");
                fail("Should not reach here");
            } catch (IllegalStateException e) {
                // Expected
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        producer.start();
        Thread.sleep(100);
        queue.shutdown();
        producer.join(1000);
        
        assertTrue(producer.isAlive() == false);
    }
    
    @Test
    void testShutdownInterruptsWaitingConsumers() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
                fail("Should not reach here");
            } catch (IllegalStateException e) {
                // Expected
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        consumer.start();
        Thread.sleep(100);
        queue.shutdown();
        consumer.join(1000);
        
        assertTrue(consumer.isAlive() == false);
    }
    
    @Test
    void testShutdownPreventsNewOperations() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        queue.put("item");
        queue.shutdown();
        
        // Should still be able to take existing elements
        assertEquals("item", queue.take());
        
        // But cannot add more
        assertThrows(IllegalStateException.class, () -> queue.put("new"));
        assertFalse(queue.offer("new", 1, TimeUnit.SECONDS));
        
        assertTrue(queue.isShutdown());
    }
    
    @Test
    void testOfferReturnsFalseOnShutdown() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.put("full");
        queue.shutdown();
        
        assertFalse(queue.offer("overflow", 1, TimeUnit.SECONDS));
    }
    
    @Test
    void testPollReturnsNullOnShutdown() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.shutdown();
        
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }
    
    // Multi-producer/multi-consumer stress test
    
    @Test
    void testMultiProducerMultiConsumerStressTest() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 1000;
        int capacity = 10;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        CountDownLatch consumersDone = new CountDownLatch(numConsumers);
        
        // Track all produced and consumed elements
        Set<Integer> produced = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> consumed = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);
        
        // Create producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            Thread producer = new Thread(() -> {
                try {
                    for (int j = 0; j < itemsPerProducer; j++) {
                        int item = producerId * 10000 + j;
                        produced.add(item);
                        queue.put(item);
                        totalProduced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
            producer.start();
        }
        
        // Create consumers
        for (int i = 0; i < numConsumers; i++) {
            Thread consumer = new Thread(() -> {
                try {
                    while (totalProduced.get() > totalConsumed.get() || 
                           (totalProduced.get() == numProducers * itemsPerProducer && queue.size() > 0)) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            consumed.add(item);
                            totalConsumed.incrementAndGet();
                        }
                        if (producersDone.getCount() == 0 && queue.isEmpty()) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            });
            consumer.start();
        }
        
        // Wait for completion
        assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Producers should complete");
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Consumers should complete");
        
        // Drain remaining items
        Integer item;
        while ((item = queue.poll()) != null) {
            consumed.add(item);
            totalConsumed.incrementAndGet();
        }
        
        // Verify no lost or duplicated elements
        assertEquals(numProducers * itemsPerProducer, totalProduced.get(), 
                     "All items should be produced");
        assertEquals(numProducers * itemsPerProducer, totalConsumed.get(), 
                     "All items should be consumed");
        
        // Verify no duplicates
        assertEquals(produced.size(), numProducers * itemsPerProducer, 
                     "No duplicate productions");
        assertEquals(consumed.size(), numProducers * itemsPerProducer, 
                     "No duplicate consumptions");
        
        // Verify all produced items were consumed
        assertTrue(consumed.containsAll(produced),