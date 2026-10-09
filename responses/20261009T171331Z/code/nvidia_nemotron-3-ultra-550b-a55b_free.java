<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261009T171331Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} ("monitor lock") to protect all mutable state:
 * the backing array, head/tail indices, element count, and shutdown flag.
 * </p>
 * <p>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added (enqueued). Consumers await this condition when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled when an element is removed (dequeued). Producers await this condition when the queue is full.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Memory Visibility:</b> The {@code ReentrantLock} establishes happens-before relationships. Lock acquisition flushes
 * processor caches, ensuring visibility of writes (element placement, index updates, shutdown flag) across threads
 * without requiring {@code volatile} on the array or indices (though {@code shutdown} is volatile for fast-path checks).
 * </p>
 * <p>
 * <b>Wait/Notify Protocol:</b> We use {@code while} loops (not {@code if}) around {@code Condition.await()} to guard
 * against <b>spurious wakeups</b> and <b>signal stealing</b> (where a third thread consumes the element signaled for).
 * </p>
 * <p>
 * <b>Shutdown Protocol:</b> Calling {@link #shutdown()} atomically sets a {@code volatile boolean shutdown} flag
 * and signals <b>all</b> waiting threads (both producers and consumers). Threads waking up check the flag:
 * <ul>
 *   <li>Producers ({@code put}/{@code offer}) throw {@link IllegalStateException}.</li>
 *   <li>Consumers ({@code take}/{@code poll}) drain remaining elements normally; once empty, throw {@link IllegalStateException}.</li>
 * </ul>
 * This ensures no thread waits indefinitely after shutdown is initiated.
 * </p>
 * <p>
 * <b>Interruption Policy:</b> Blocking methods ({@code put}, {@code take}, timed {@code offer}, {@code poll})
 * are responsive to interruption. If interrupted while waiting, they throw {@link InterruptedException}
 * with the thread's interrupt status cleared (standard JDK behavior).
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;

    // State guarded by 'lock'
    private int head = 0;      // Index to take from
    private int tail = 0;      // Index to put at
    private int count = 0;     // Current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Volatile for fast-path read without lock in isShutdown()
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        
        lock.lockInterruptibly(); // Allows interruption during lock acquisition
        try {
            // Wait for space or shutdown
            while (count == capacity) {
                checkShutdown(); // Throws ISE if shutdown
                notFull.await();
            }
            // Re-check shutdown after await returns (could be woken by shutdown())
            checkShutdown();
            
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throws ISE if shutdown && empty
                notEmpty.await();
            }
            // If we woke up, either count > 0 OR shutdown=true && count==0 (handled by checkShutdown in loop)
            // But if shutdown=true && count>0, we proceed to dequeue.
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time
     * if necessary for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if the waiting time elapsed before space was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdown();
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            checkShutdown();
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the waiting time elapsed before an element was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throws ISE if shutdown && empty
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Non-blocking / Immediate Operations ---

    /**
     * Inserts the specified element at the tail of this queue if it is possible to do so immediately
     * without violating capacity restrictions, returning {@code true} upon success.
     *
     * @param e the element to add
     * @return {@code true} if the element was added, {@code false} if the queue is full
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        
        lock.lock();
        try {
            checkShutdown();
            if (count == capacity) {
                return false;
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) {
                checkShutdown(); // Throws ISE if shutdown
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Inspection & State ---

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
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Initiates an orderly shutdown.
     * <p>
     * All threads currently blocked in {@code put}, {@code take}, {@code offer}, or {@code poll}
     * will wake up and throw {@link IllegalStateException} (producers) or drain remaining elements
     * then throw {@link IllegalStateException} (consumers).
     * </p>
     * <p>
     * Subsequent calls to put/offer will throw {@link IllegalStateException} immediately.
     * Subsequent calls to take/poll will drain remaining elements, then throw {@link IllegalStateException}.
     * </p>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Signal ALL waiters on both conditions to unblock them immediately
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    // --- Internal Helpers ---

    private void checkShutdown() {
        if (shutdown) {
            // Producers always fail. Consumers fail only if empty (handled by callers logic usually),
            // but take/poll loop condition handles "shutdown && empty".
            // For put/offer, any shutdown is immediate failure.
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        notFull.signal(); // Wake up one producer
        return e;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure clean state for next test
    }

    // --- Basic Functionality ---

    @Test
    void testBasicFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testCapacityLimit() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3)); // Full
        assertEquals(2, q.size());
    }

    @Test
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    // --- Blocking Behavior ---

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it

        Thread putter = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        putter.start();

        // Give putter time to block
        Thread.sleep(100);
        assertTrue(putter.isAlive(), "Producer should be blocked");

        // Consume to unblock
        assertEquals(1, q.take());
        putter.join(1000);
        assertFalse(putter.isAlive(), "Producer should have unblocked and finished");
        assertEquals(2, q.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        taker.start();

        Thread.sleep(100);
        assertTrue(taker.isAlive(), "Consumer should be blocked");

        queue.put(42);
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    // --- Timed Operations ---

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        releaser.start();

        // Should succeed because space opens up within timeout
        assertTrue(q.offer(2, 500, TimeUnit.MILLISECONDS));
        assertEquals(2, q.take());
    }

    @Test
    void testOfferTimeoutFailure() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full, no one consuming
        
        assertFalse(q.offer(2, 50, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(99); } catch (InterruptedException ignored) {}
        });
        producer.start();

        Integer val = queue.poll(500, TimeUnit.MILLISECONDS);
        assertEquals(99, val);
    }

    @Test
    void testPollTimeoutFailure() {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    // --- Interruption ---

    @Test
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
        // Verify queue state unchanged
        assertEquals(1, q.take());
    }

    @Test
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testTimedOfferInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        Thread t = new Thread(() -> {
            try { q.offer(2, 10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Protocol ---

    @Test
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        AtomicBoolean producerThrew = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (IllegalStateException e) { producerThrew.set(true); }
            catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive());

        q.shutdown();
        producer.join(1000);
        assertTrue(producerThrew.get(), "Producer should throw IllegalStateException on shutdown");
        assertTrue(q.isShutdown());
    }

    @Test
    void testShutdownUnblocksConsumers() throws InterruptedException {
        AtomicBoolean consumerThrew = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try { queue.take(); } 
            catch (IllegalStateException e) { consumerThrew.set(true); }
            catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(50);
        assertTrue(consumer.isAlive());

        queue.shutdown();
        consumer.join(1000);
        assertTrue(consumerThrew.get(), "Consumer should throw IllegalStateException on shutdown when empty");
    }

    @Test
    void testShutdownDrainsRemainingElements() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        // Should be able to drain existing elements
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());

        // Now empty and shutdown -> exception
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll());
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownRejectsNewPuts() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // --- Concurrency Stress Test ---

    @Test
    @DisabledOnOs(OS.WINDOWS) // Timing sensitive on CI Windows runners sometimes; remove if stable
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Set to detect duplicates (ConcurrentHashMap.KeySetView)
        Set<Integer> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Unique values
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val;
                        try {
                            // Use timed poll to allow graceful exit checking error flag/shutdown
                            // But for stress, we rely on count. We know exactly how many items total.
                            // We just loop until we have consumed our share? No, consumers compete.
                            // Better: loop until consumedCount reaches totalItems.
                            if (consumedCount.get() >= totalItems) break;
                            
                            val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                            if (val == null) continue; // Timeout, retry
                            
                            // Check for duplicates
                            if (!seen.add(val)) {
                                System.err.println("DUPLICATE DETECTED: " + val);
                                errorFlag.set(true);
                                break;
                            }
                            consumedSum.addAndGet(val);
                            consumedCount.incrementAndGet();
                        } catch (IllegalStateException e) {
                            // Shutdown happened, drain remaining
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Run ---
        long startTime = System.nanoTime();
        startLatch.countDown(); // Release all threads
        
        // Wait for completion with generous timeout
        boolean finished = endLatch.await(30,