<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260708T210536Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->

I'll create a thread-safe bounded blocking queue implementation from scratch, then write comprehensive tests.

## Implementation

```java
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, bounded blocking queue implementation.
 * 
 * <p>This implementation uses a ReentrantLock with two Condition variables
 * to manage producer-consumer coordination. It supports blocking operations
 * as well as timed operations and clean shutdown.
 * 
 * <p><b>Concurrency Strategy:</b>
 * <ul>
 *   <li>Uses a single ReentrantLock for mutual exclusion</li>
 *   <li>Two Condition variables: notFull and notEmpty</li>
 *   <li>notFull: signals when space is available (for producers)</li>
 *   <li>notEmpty: signals when elements exist (for consumers)</li>
 *   <li>Shutdown flag with proper signalAll() on shutdown</li>
 * </ul>
 * 
 * @param <T> the type of elements held in this queue
 */
public class BoundedBlockingQueue<T> {
    
    private final List<T> queue;
    private final int capacity;
    private volatile boolean shutdown = false;
    
    private final ReentrantLock lock;
    private final Condition notFull;
    private final Condition notEmpty;
    
    /**
     * Creates a new bounded blocking queue with the specified capacity.
     * 
     * @param capacity the maximum number of elements this queue can hold
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1");
        }
        this.capacity = capacity;
        this.queue = new LinkedList<>();
        this.lock = new ReentrantLock();
        this.notFull = lock.newCondition();
        this.notEmpty = lock.newCondition();
    }
    
    /**
     * Inserts the specified element at the tail of this queue, blocking
     * if the queue is full.
     * 
     * @param element the element to add
     * @throws InterruptedException if the current thread is interrupted
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(T element) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            queue.add(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Removes and returns the head of this queue, blocking until
     * an element is available.
     * 
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted
     * @throws IllegalStateException if the queue has been shut down
     */
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue has been shut down");
            }
            T element = queue.remove(0);
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to insert the specified element at the tail of this queue
     * without blocking, returning {@code true} if successful.
     * 
     * @param element the element to add
     * @return {@code true} if the element was added, {@code false} if the queue is full
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(T element) {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            if (queue.size() == capacity) {
                return false;
            }
            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Attempts to insert the specified element at the tail of this queue,
     * blocking until space is available or the timeout expires.
     * 
     * @param element the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if timeout elapsed
     * @throws InterruptedException if the current thread is interrupted
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            long deadline = System.nanoTime() + nanos;
            
            while (queue.size() == capacity && !shutdown) {
                if (nanos > 0) {
                    nanos = notFull.awaitNanos(nanos);
                } else {
                    return false;
                }
            }
            
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            
            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Removes and returns the head of this queue, or if the queue is empty,
     * returns {@code null}.
     * 
     * @return the head of this queue, or {@code null} if empty
     */
    public T poll() {
        lock.lock();
        try {
            if (queue.isEmpty()) {
                return null;
            }
            T element = queue.remove(0);
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Retrieves and removes the head of this queue, blocking up to the
     * specified timeout, or returns {@code null} if the timeout expires.
     * 
     * @param timeout how long to wait before returning
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if the current thread is interrupted
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            
            while (queue.isEmpty() && !shutdown) {
                if (nanos > 0) {
                    nanos = notEmpty.awaitNanos(nanos);
                } else {
                    return null;
                }
            }
            
            if (queue.isEmpty()) {
                return null;
            }
            
            T element = queue.remove(0);
            notFull.signal();
            return element;
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
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns {@code true} if the queue is empty.
     * 
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Shuts down the queue, interrupting all waiting threads.
     * After shutdown, put operations will throw IllegalStateException,
     * and take/poll operations may return null or throw IllegalStateException.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns {@code true} if the queue has been shut down.
     * 
     * @return {@code true} if shut down
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
```

## Concurrency Strategy Documentation

**Lock Management**: A single `ReentrantLock` protects all mutable state. This simplifies reasoning about correctness while still allowing high throughput since the critical sections are minimal.

**Condition Variables**: Two `Condition` objects coordinate producers and consumers:
- `notFull`: Producers wait here when the queue is full
- `notEmpty`: Consumers wait here when the queue is empty

**Interrupt Handling**: All blocking methods properly respond to thread interruption by throwing `InterruptedException` and preserving the interrupt status.

**Shutdown Mechanism**: A volatile `shutdown` flag ensures visibility across threads. On shutdown, `signalAll()` wakes all waiting threads so they can exit gracefully.

**Memory Consistency**: The lock provides the necessary memory barriers to ensure all threads see consistent state.

---

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongAdder;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {
    
    @Test
    void testBasicOfferTake() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(2);
        
        assertTrue(queue.offer("first"));
        assertTrue(queue.offer("second"));
        assertFalse(queue.offer("third")); // Queue full
        assertEquals(2, queue.size());
        
        assertEquals("first", queue.poll());
        assertEquals("second", queue.poll());
        assertNull(queue.poll());
    }
    
    @Test
    void testPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(2);
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        
        CountDownLatch produced = new CountDownLatch(2);
        CountDownLatch consumed = new CountDownLatch(2);
        
        executor.submit(() -> {
            try {
                queue.put(1);
                produced.countDown();
                queue.put(2);
                produced.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        executor.submit(() -> {
            try {
                Integer a = queue.take();
                Integer b = queue.take();
                consumed.countDown();
                consumed.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        assertTrue(produced.await(2, TimeUnit.SECONDS));
        assertTrue(consumed.await(2, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
        
        executor.shutdown();
    }
    
    @Test
    void testTimedOffer() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        assertTrue(queue.offer("first"));
        assertFalse(queue.offer("second", 100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    void testTimedPoll() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        
        queue.offer("value");
        assertEquals("value", queue.poll(100, TimeUnit.MILLISECONDS));
    }
    
    @Test
    void testShutdownDuringPut() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.offer("initial");
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put("last"));
    }
    
    @Test
    void testShutdownDuringTake() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, queue::take);
    }
    
    @Test
    void testShutdownWakesWaitingThreads() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.offer("item");
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch consumerStarted = new CountDownLatch(1);
        AtomicInteger exceptions = new AtomicInteger(0);
        
        Future<?> producer = executor.submit(() -> {
            try {
                producerStarted.countDown();
                queue.put("new item");
            } catch (IllegalStateException e) {
                exceptions.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        Future<String> consumer = executor.submit(() -> {
            try {
                consumerStarted.countDown();
                return queue.take();
            } catch (IllegalStateException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        
        // Fill queue to make producer wait
        queue.offer("filler");
        
        producerStarted.await();
        consumerStarted.await();
        
        queue.shutdown();
        
        // Give threads time to react to shutdown
        Thread.sleep(100);
        
        assertEquals(1, exceptions.get(), "Producer should have gotten IllegalStateException");
        
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }
    
    @Test
    void testSizeAndIsEmpty() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(3);
        
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        
        queue.offer("a");
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());
        
        queue.offer("b");
        queue.offer("c");
        assertEquals(3, queue.size());
    }
    
    @Test
    void testIllegalCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }
    
    @Test
    void testInterruptedPut() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        queue.offer("filler");
        
        Thread t = new Thread(() -> {
            try {
                queue.put("attempted");
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // Expected
            }
        });
        
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(1000);
        
        assertFalse(t.isAlive());
    }
    
    @Test
    void testInterruptedTake() throws InterruptedException {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(1);
        
        Thread t = new Thread(() -> {
            try {
                queue.take();
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // Expected
            }
        });
        
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(1000);
        
        assertFalse(t.isAlive());
    }
    
    @Test
    void testMultiProducerMultiConsumerStressTest() throws ExecutionException, InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 1000;
        int capacity = 10;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Track all produced values
        ConcurrentHashMap<Integer, Integer> valueCounts = new ConcurrentHashMap<>();
        AtomicLongAdder producedCount = new AtomicLongAdder();
        AtomicLongAdder consumedCount = new AtomicLongAdder();
        
        // Start consumers first
        List<Future<Integer>> consumerFutures = new ArrayList<>();
        for (int i = 0; i < numConsumers; i++) {
            Future<Integer> future = executor.submit(() -> {
                int localCount = 0;
                try {
                    while (localCount < itemsPerProducer) {
                        Integer value = queue.take();
                        valueCounts.merge(value, 1, Integer::sum);
                        consumedCount.increment();
                        localCount++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return localCount;
            });
            consumerFutures.add(future);
        }
        
        // Start producers
        List<Future<Integer>> producerFutures = new ArrayList<>();
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            Future<Integer> future = executor.submit(() -> {
                int localCount = 0;
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = producerId * itemsPerProducer + i;
                        queue.put(value);
                        producedCount.increment();
                        localCount++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return localCount;
            });
            producerFutures.add(future);
        }
        
        // Wait for completion
        for (Future<Integer> future : producerFutures) {
            Integer produced = future.get(10, TimeUnit.SECONDS);
            assertEquals(itemsPerProducer, produced.intValue());
        }
        
        for (Future<Integer> future : consumerFutures) {
            Integer consumed = future.get(10, TimeUnit.SECONDS);
            assertEquals(itemsPerProducer, consumed.intValue());
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Verify counts
        assertEquals(numProducers * itemsPerProducer, producedCount.sum());
        assertEquals(numProducers * itemsPerProducer, consumedCount.sum());
        
        // Verify no duplicates
        int totalElements = valueCounts.size();
        assertEquals(numProducers * itemsPerProducer, totalElements, 
            "Should have exactly one entry per unique value");
        
        // Verify each value appears exactly once
        for (int count : valueCounts.values()) {
            assertEquals(1, count, "Each value should appear exactly once");
        }
        
        // Verify no lost elements
        assertEquals(0, queue.size(), "Queue should be empty after all consumption");
    }
    
    @Test
    void testEmptyQueueShutdown() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(10);
        queue.shutdown();
        
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, queue::take);
        assertNull(queue.poll()); // poll should return null, not throw
    }
    
    @Test
    void testOfferAfterShutdown() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(10);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer("test"));
    }
    
    @Test
    void testTimedOfferAfterShutdown() {
        BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(10);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> 
            queue.offer("test", 1, TimeUnit.SECONDS));
    }
}
```

---

## Key Features

1. **Thread-safe**: Uses `ReentrantLock` with two `Condition` variables for efficient producer-consumer coordination
2. **Bounded**: Enforces a maximum capacity
3. **Blocking operations**: `put()` and `take()` block when necessary
4. **Timed operations**: `offer()` and `poll()` support timeouts
5. **Clean shutdown**: `shutdown()` method wakes all waiting threads and prevents new operations
6. **Interruptible**: All blocking methods respond to thread interruption

The stress test verifies:
- No lost elements (all produced values are consumed)
- No duplicates (each value appears exactly once)
- Correct coordination between multiple producers and consumers
- Proper handling of bounded capacity
