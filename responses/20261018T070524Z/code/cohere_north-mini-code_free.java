<!-- model: cohere/north-mini-code:free | category: code | run: 20261018T070524Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

Here's a complete implementation of a thread-safe, generic bounded blocking queue in Java along with comprehensive JUnit 5 tests:

## BoundedBlockingQueue Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Objects;

public class BoundedBlockingQueue<T> {
    private final Object[] array;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;
    
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        array = new Object[capacity];
    }
    
    public void put(T item) throws InterruptedException {
        Objects.requireNonNull(item, "Item cannot be null");
        lock.lockInterruptibly();
        try {
            while (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            while (count == array.length) {
                notFull.await();
            }
            array[tail] = item;
            tail = (tail + 1) % array.length;
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(item, "Item cannot be null");
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            while (count == array.length) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            array[tail] = item;
            tail = (tail + 1) % array.length;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (shutdown && count == 0) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            while (count == 0) {
                notEmpty.await();
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % array.length;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (shutdown && count == 0) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            while (count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % array.length;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
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
    
    public boolean isShutdown() {
        lock.lock();
        try {
            return shutdown;
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

## Concurrency Strategy

The implementation uses the **Monitor Pattern** with explicit synchronization:

1. **Mutual Exclusion**: A `ReentrantLock` protects all shared state (the array buffer, head/tail pointers, and count).

2. **Condition Variables**: Two condition variables coordinate producers and consumers:
   - `notEmpty`: Signaled when items are added, waited on by consumers
   - `notFull`: Signaled when space is available, waited on by producers

3. **Shutdown Protocol**: A volatile `shutdown` flag signals all waiting threads to wake up and throw exceptions.

4. **Thread Safety**: All operations are atomic under the lock, preventing race conditions.

5. **Interrupted Handling**: Threads are properly interrupted when waiting, maintaining the interrupted status.

6. **Memory Management**: Nulling out array elements helps garbage collection.

## JUnit 5 Test Suite

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class BoundedBlockingQueueTest {
    
    private static final int DEFAULT_CAPACITY = 10;
    private static final int STRESS_TEST_SIZE = 10000;
    private static final int NUM_PRODUCERS = 10;
    private static final int NUM_CONSUMERS = 10;
    private static final long TIMEOUT = 10; // seconds
    
    private BoundedBlockingQueue<Integer> queue;
    
    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(DEFAULT_CAPACITY);
    }
    
    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }
    
    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertEquals(0, queue.size());
    }
    
    @Test
    void testOfferAndPoll() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }
    
    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(DEFAULT_CAPACITY, queue.size());
        
        // Try to offer with timeout - should fail
        assertFalse(queue.offer(DEFAULT_CAPACITY + 1, 1, TimeUnit.SECONDS));
        assertEquals(DEFAULT_CAPACITY, queue.size());
    }
    
    @Test
    void testPollTimeout() throws InterruptedException {
        // Queue is empty
        assertNull(queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }
    
    @Test
    void testBlockingPut() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try {
                queue.put(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        producer.start();
        producer.join();
        
        assertEquals(42, queue.take());
    }
    
    @Test
    void testBlockingTake() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try {
                Integer item = queue.take();
                assertEquals(42, item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        queue.put(42);
        consumer.start();
        consumer.join();
    }
    
    @Test
    void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertTrue(queue.isShutdown());
        
        // Put should throw exception
        try {
            queue.put(1);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // Expected
        }
        
        // Take from empty queue should throw exception
        try {
            queue.take();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // Expected
        }
        
        // Take from non-empty queue should work
        queue.put(1);
        assertEquals(1, queue.take());
    }
    
    @Test
    void testConcurrentPutTake() throws InterruptedException {
        int numItems = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        for (int i = 0; i < numItems; i++) {
            executor.submit(() -> {
                try {
                    queue.put(i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < numItems; i++) {
            results.add(queue.take());
        }
        
        executor.shutdown();
        executor.awaitTermination(TIMEOUT, TimeUnit.SECONDS);
        
        assertEquals(numItems, results.size());
        assertTrue(results.containsAll(IntStream.range(0, numItems).boxed().toList()));
    }
    
    @Test
    @Timeout(30) // Test should complete within 30 seconds
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        List<Integer> producedItems = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumedItems = Collections.synchronizedList(new ArrayList<>());
        
        ExecutorService producerExecutor = Executors.newFixedThreadPool(NUM_PRODUCERS);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(NUM_CONSUMERS);
        
        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            producerExecutor.submit(() -> {
                for (int j = 0; j < STRESS_TEST_SIZE / NUM_PRODUCERS; j++) {
                    try {
                        int item = produced.incrementAndGet();
                        queue.put(item);
                        producedItems.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            consumerExecutor.submit(() -> {
                for (int j = 0; j < STRESS_TEST_SIZE / NUM_CONSUMERS; j++) {
                    try {
                        Integer item = queue.take();
                        if (item != null) {
                            consumed.incrementAndGet();
                            consumedItems.add(item);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        // Wait for all producers to finish
        producerExecutor.shutdown();
        producerExecutor.awaitTermination(TIMEOUT, TimeUnit.SECONDS);
        
        // Shutdown the queue to signal consumers to finish
        queue.shutdown();
        
        // Wait for all consumers to finish
        consumerExecutor.shutdown();
        consumerExecutor.awaitTermination(TIMEOUT, TimeUnit.SECONDS);
        
        // Verify no lost or duplicated elements
        assertEquals(STRESS_TEST_SIZE, producedItems.size(), "Some items were lost during production");
        assertEquals(STRESS_TEST_SIZE, consumedItems.size(), "Some items were lost during consumption");
        
        // Verify all consumed items