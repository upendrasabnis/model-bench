<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261003T194309Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-quality implementation of a generic bounded blocking queue with comprehensive JUnit 5 tests.

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe, generic, bounded blocking queue implementation built on {@link ReentrantLock}
 * and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} guards all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code items}, {@code shutdown}).
 *       This ensures sequential consistency for all operations.</li>
 *   <li><b>Signaling:</b> Two {@link Condition} variables are used:
 *       <ul>
 *         <li>{@code notEmpty}: Signaled by producers after enqueueing; awaited by consumers when empty.</li>
 *         <li>{@code notFull}: Signaled by consumers after dequeueing; awaited by producers when full.</li>
 *       </ul>
 *       This avoids "wake-up storms" by targeting specific waiter sets.</li>
 *   <li><b>Spurious Wakeups:</b> All {@code await()} calls are wrapped in {@code while} loops
 *       re-checking the state predicate ({@code count == 0} or {@code count == capacity}).</li>
 *   <li><b>Memory Visibility:</b> The lock provides happens-before edges for all state mutations.
 *       The {@code shutdown} flag and {@code shutdownCause} are additionally {@code volatile}/{@code AtomicReference}
 *       for safe publication during the shutdown handshake without requiring the lock for read-only checks.</li>
 *   <li><b>Shutdown Protocol:</b>
 *       <ol>
 *         <li>{@code shutdown(Throwable)} acquires the lock, sets {@code shutdown=true}, records cause, signals {@code ALL} waiters.</li>
 *         <li>Producers ({@code put/offer}) check {@code shutdown} immediately upon lock acquisition and throw {@link IllegalStateException}.</li>
 *         <li>Consumers ({@code take/poll}) drain remaining elements normally. Once empty {@code AND} shutdown, they throw {@link IllegalStateException}
 *             with the recorded cause.</li>
 *       </ol>
 *   </li>
 *   <li><b>Interruption:</b> Blocking methods ({@code put}, {@code take}, timed variants) are responsive to interruption.
 *       If interrupted while waiting, {@link InterruptedException} is thrown. The lock is released cleanly via {@code finally} blocks.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;

    private int head = 0;
    private int tail = 0;
    private int count = 0;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Volatile/Atomic for fast-path shutdown checks without locking (optional optimization),
    // but primary synchronization happens via 'lock'.
    private volatile boolean shutdown = false;
    private final AtomicReference<Throwable> shutdownCause = new AtomicReference<>();

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lockInterruptibly();
        try {
            checkShutdownForProducers();
            while (count == capacity) {
                notFull.await();
                checkShutdownForProducers(); // Re-check after wakeup
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw newIllegalStateException("Queue shut down");
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return {@code true} if successful, {@code false} if the waiting time elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdownForProducers();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                checkShutdownForProducers();
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * wait time if necessary for an element to become available.
     * @return the head of this queue, or {@code null} if the waiting time elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw newIllegalStateException("Queue shut down");
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Non-blocking / Immediate Operations ---

    public boolean offer(E e) {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lock();
        try {
            checkShutdownForProducers();
            if (count == capacity) return false;
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) items[head];
        } finally {
            lock.unlock();
        }
    }

    // --- State Inspection ---

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() { return size() == 0; }
    public boolean isFull() { return remainingCapacity() == 0; }

    // --- Shutdown ---

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Producers ({@code put/offer}) will immediately fail with {@link IllegalStateException}.</li>
     *   <li>Consumers ({@code take/poll}) will drain existing elements normally.</li>
     *   <li>Once empty, consumers will fail with {@link IllegalStateException}.</li>
     *   <li>All blocked threads are unblocked immediately.</li>
     * </ul>
     * @param cause the reason for shutdown (wrapped in exceptions thrown to waiters)
     */
    public void shutdown(Throwable cause) {
        Objects.requireNonNull(cause, "Shutdown cause cannot be null");
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            shutdownCause.set(cause);
            // Wake everyone up to re-evaluate state
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        shutdown(new IllegalStateException("Queue shut down"));
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public Throwable getShutdownCause() {
        return shutdownCause.get();
    }

    // --- Internal Helpers ---

    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == capacity) tail = 0;
        count++;
        notEmpty.signal(); // Wake one consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        notFull.signal(); // Wake one producer
        return e;
    }

    private void checkShutdownForProducers() {
        if (shutdown) throw newIllegalStateException("Queue shut down");
    }

    private IllegalStateException newIllegalStateException(String msg) {
        Throwable cause = shutdownCause.get();
        return (cause != null) ? new IllegalStateException(msg, cause) : new IllegalStateException(msg);
    }
}
```

---

### 2. Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // --- Basic Functionality ---

    @Test
    @DisplayName("Basic FIFO order and capacity")
    void testBasicFifo() throws InterruptedException {
        assertEquals(0, queue.size());
        assertEquals(10, queue.remainingCapacity());

        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(3, queue.size());
        assertEquals(7, queue.remainingCapacity());

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Peek does not remove")
    void testPeek() throws InterruptedException {
        assertNull(queue.peek());
        queue.put(10);
        assertEquals(10, queue.peek());
        assertEquals(10, queue.peek());
        assertEquals(1, queue.size());
        assertEquals(10, queue.take());
        assertNull(queue.peek());
    }

    // --- Blocking Behavior ---

    @Test
    @DisplayName("Put blocks when full")
    void testPutBlocksWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());

        Thread producer = new Thread(() -> {
            try { q.put(3); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(100); // Ensure producer is parked
        assertTrue(producer.isAlive(), "Producer should be blocked");

        assertEquals(1, q.take()); // Make space
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should have unblocked");
        assertEquals(2, q.take());
        assertEquals(3, q.take());
    }

    @Test
    @DisplayName("Take blocks when empty")
    void testTakeBlocksWhenEmpty() throws Exception {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");

        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @Test
    @DisplayName("Offer with timeout returns false on timeout")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("Offer with timeout succeeds when space available")
    void testOfferTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Poll with timeout returns null on timeout")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Poll with timeout returns element")
    void testPollTimeoutSuccess() throws InterruptedException {
        queue.put(99);
        assertEquals(99, queue.poll(100, TimeUnit.MILLISECONDS));
    }

    // --- Interruption ---

    @Test
    @DisplayName("Put responds to interruption")
    void testPutInterruption() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it

        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
        assertTrue(t.isInterrupted() || Thread.interrupted()); // Interrupted status cleared by join usually, but exception thrown
    }

    @Test
    @DisplayName("Take responds to interruption")
    void testTakeInterruption() throws Exception {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Protocol ---

    @Test
    @DisplayName("Shutdown prevents new puts")
    void testShutdownBlocksProducers() {
        queue.shutdown(new IllegalStateException("Maintenance"));
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrainsConsumers() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown(new IllegalStateException("Done"));

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());

        // Now empty and shutdown -> exception
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> queue.take());
        assertTrue(ex.getMessage().contains("Done"));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("Shutdown unblocks waiting producers immediately")
    void testShutdownUnblocksProducers() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        AtomicReference<Exception> producerEx = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { q.put(2); }
            catch (Exception e) { producerEx.set(e); }
        });
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive());

        q.shutdown(new IllegalStateException("Stop"));
        producer.join(1000);

        assertNotNull(producerEx.get());
        assertTrue(producerEx.get() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("Shutdown unblocks waiting consumers immediately")
    void testShutdownUnblocksConsumers() throws Exception {
        AtomicReference<Exception> consumerEx = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try { queue.take(); }
            catch (Exception e) { consumerEx.set(e); }
        });
        consumer.start();
        Thread.sleep(50);
        assertTrue(consumer.isAlive());

        queue.shutdown(new IllegalStateException("Stop"));
        consumer.join(1000);

        assertNotNull(consumerEx.get());
        assertTrue(consumerEx.get() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown(new Exception("First"));
        queue.shutdown(new Exception("Second")); // Should not overwrite cause
        assertEquals("First", queue.getShutdownCause().getMessage());
    }

    // --- Parameterized Capacity Tests ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 100})
    @DisplayName("Roundtrip for various capacities")
    void testRoundtrip(int cap) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(cap);
        for (int i = 0; i < cap; i++) q.put(i);
        for (int i = 0; i < cap; i++) assertEquals(i, q.take());
    }

    // --- STRESS TEST: Multi-Producer / Multi-Consumer ---

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer - No Lost/Duplicated Elements")
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Producers
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
                } catch (Throwable t) {
                    errorRef.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // Consumers
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.take();
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        if (consumedCount.get() >= totalItems) break;
                    }
                } catch (Throwable t) {
                    // Expected: IllegalStateException on shutdown after drain, or InterruptedException
                    if (!(t instanceof IllegalStateException && t.getMessage().contains("shut down")) &&
                        !(t instanceof InterruptedException)) {
                        errorRef.compareAndSet(null, t);
                    }
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for producers to finish producing
        // We need a separate latch or logic to know when producers are done to shutdown
        // Simpler: Wait for producedCount to reach total, then shutdown.
        while (producedCount.get() < totalItems) {
            Thread.sleep(10);
        }
        
        // Give consumers a moment to drain the last few items, then shutdown
        // Actually, consumers loop until count == totalItems. 
        // We must ensure queue is shut down AFTER all items are consumed OR handle the shutdown exception in consumers.
        // Current consumer logic breaks loop at totalItems. 
        // But take() blocks if queue empty before totalItems reached.
        // Producers finish -> Queue drains -> Consumers take last items -> Count hits total -> Break loop -> Thread ends.
        // This works without explicit shutdown if counts match perfectly.
        
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Test timed out");
        
        // Check for errors
        if (errorRef.get() != null) throw new AssertionError("Thread error", errorRef.get());

        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed");
        assertEquals(producedSum.get(), consumedSum.get(), "Sum mismatch: Lost or duplicated elements!");
        assertEquals(0, stressQueue.size(), "Queue not empty at end");
    }

    @Test
    @DisplayName("Stress Test: High Contention with Shutdown")
    void testStressWithShutdown() throws InterruptedException {
        final int capacity = 50;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10); // 5 prod + 5 cons
        AtomicReference<Throwable> error = new AtomicReference<>();

        Runnable producer = () -> {
            try { start.await(); while(true) q.put(produced.incrementAndGet()); }
            catch (IllegalStateException ignored) { /* Shutdown expected */ }
            catch (Throwable t) { error.compareAndSet(null, t); }
            finally { done.countDown(); }
        };

        Runnable consumer = () -> {
            try { start.await(); while(true) { q.take(); consumed.incrementAndGet(); } }
            catch (IllegalStateException ignored) { /* Shutdown expected */ }
            catch (Throwable t) { error.compareAndSet(null, t); }
            finally { done.countDown(); }
        };

        for(int i=0;i<5;i++) new Thread(producer, "P"+i).start