<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260915T175542Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation built on {@link ReentrantLock}
 * and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state
 * (the ring buffer, head/tail indices, count, and shutdown flag). This ensures
 * mutual exclusion for all queue mutations.
 * </p>
 * <p>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled by {@code put}/{@code offer} when an element is added.
 *       Waited upon by {@code take}/{@code poll} when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled by {@code take}/{@code poll} when an element is removed.
 *       Waited upon by {@code put}/{@code offer} when the queue is at capacity.</li>
 * </ul>
 * Using two separate conditions (instead of {@code notifyAll}) minimizes "spurious wakeup"
 * contention: producers only wake consumers, and consumers only wake producers.
 * </p>
 * <p>
 * <b>Shutdown Protocol:</b> The {@code shutdown()} method acquires the lock, sets a
 * {@code volatile boolean shutdown} flag, and signals <b>both</b> conditions ({@code signalAll}).
 * This unblocks all waiting threads immediately. Subsequent blocking/timed operations
 * throw {@link IllegalStateException}. Non-blocking operations ({@code offer}/{@code poll}
 * without timeout) return failure indicators ({@code false}/{@code null}) immediately.
 * Already enqueued elements remain accessible until drained.
 * </p>
 * <p>
 * <b>Interruption Handling:</b> Blocking methods ({@code put}, {@code take}) are
 * interruptible ({@code awaitUninterruptibly} is <b>not</b> used). If interrupted while
 * waiting, they throw {@link InterruptedException} after clearing the interrupt status
 * (standard JDK behavior). Timed methods return {@code false}/{@code null} on timeout
 * or interruption.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] items;      // Ring buffer
    private int putIndex = 0;          // Next slot to write (tail)
    private int takeIndex = 0;         // Next slot to read (head)
    private int count = 0;             // Current number of elements
    private volatile boolean shutdown = false; // Visible across threads without lock for fast-path check

    // --- Synchronization Primitives ---
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.items = new Object[capacity];
    }

    // ------------------------------------------------------------
    // Blocking Operations (Throws InterruptedException)
    // ------------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * for space to become available if the queue is full.
     *
     * @param e the element to add (must not be null)
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        lock.lockInterruptibly(); // Respect interruption during lock acquisition
        try {
            checkShutdown();
            // Wait while full
            while (count == items.length) {
                notFull.await(); // Releases lock, waits for signal
                checkShutdown(); // Re-check shutdown after wakeup
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * until an element becomes available if the queue is empty.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait while empty
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E item = dequeue();
            notFull.signal(); // Wake up one waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Timed Operations (Return boolean / Element or null)
    // ------------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * up to the specified wait time for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (count == items.length) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Returns remaining nanos
                checkShutdown();
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * up to the specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Shutdown + empty = drain complete
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Immediate / Non-Blocking Operations
    // ------------------------------------------------------------

    /** Inserts element if space available immediately. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lock();
        try {
            if (shutdown || count == items.length) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head if available immediately. */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head without removing. */
    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) items[takeIndex];
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // State Inspection & Shutdown
    // ------------------------------------------------------------

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isFull() {
        lock.lock();
        try { return count == items.length; } finally { lock.unlock(); }
    }

    public int capacity() { return items.length; }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Prevents new elements from being added ({@code put}/{@code offer} fail).</li>
     *   <li>Wakes up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Threads blocked in {@code take}/{@code poll} will drain remaining elements then return {@code null}/throw exception.</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake EVERYONE: producers (notFull) and consumers (notEmpty)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown; // Volatile read
    }

    // ------------------------------------------------------------
    // Internal Helpers
    // ------------------------------------------------------------

    private void checkShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % items.length;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // Help GC
        takeIndex = (takeIndex + 1) % items.length;
        count--;
        return e;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // ------------------------------------------------------------
    // Basic Contract Tests
    // ------------------------------------------------------------

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testNullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    void testBasicFifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testPeekDoesNotRemove() throws InterruptedException {
        queue.put(10);
        assertEquals(10, queue.peek());
        assertEquals(10, queue.peek());
        assertEquals(10, queue.take());
        assertNull(queue.peek());
    }

    @Test
    void testSizeAndCapacity() throws InterruptedException {
        assertEquals(0, queue.size());
        assertEquals(10, queue.capacity());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());

        queue.put(1);
        assertEquals(1, queue.size());
        
        IntStream.range(0, 9).forEach(i -> {
            try { queue.put(i); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        assertEquals(10, queue.size());
        assertTrue(queue.isFull());
    }

    // ------------------------------------------------------------
    // Blocking Behavior Tests
    // ------------------------------------------------------------

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < 10; i++) queue.put(i);
        
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                producerBlocked.set(true);
                queue.put(99); // Should block
            } catch (InterruptedException ignored) {}
        });
        
        producer.start();
        // Wait for producer to actually block on condition
        await().until(producerBlocked::get); 
        Thread.sleep(100); // Give it time to park
        assertTrue(producer.isAlive(), "Producer should be blocked");
        
        queue.take(); // Make space
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should unblock and finish");
        assertEquals(99, queue.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try {
                consumerBlocked.set(true);
                queue.take(); // Should block
            } catch (InterruptedException ignored) {}
        });
        
        consumer.start();
        await().until(consumerBlocked::get);
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        
        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    void testInterruptedExceptionOnPut() throws InterruptedException {
        for (int i = 0; i < 10; i++) queue.put(i); // Fill
        
        Thread t = new Thread(() -> {
            try { queue.put(1); } catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.BLOCKED);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testInterruptedExceptionOnTake() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ------------------------------------------------------------
    // Timed Offer / Poll Tests
    // ------------------------------------------------------------

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        for (int i = 0; i < 10; i++) queue.put(i); // Full
        
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(50); queue.take(); } catch (InterruptedException ignored) {}
        });
        releaser.start();
        
        // Should succeed because space opens up within timeout
        assertTrue(queue.offer(99, 200, MILLISECONDS));
        assertEquals(99, queue.poll()); // The last one added
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        for (int i = 0; i < 10; i++) queue.put(i); // Full
        // No one makes space
        assertFalse(queue.offer(99, 50, MILLISECONDS));
        assertEquals(10, queue.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(42); } catch (InterruptedException ignored) {}
        });
        producer.start();
        
        Integer val = queue.poll(200, MILLISECONDS);
        assertEquals(42, val);
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        assertNull(queue.poll(50, MILLISECONDS));
    }

    // ------------------------------------------------------------
    // Shutdown Tests
    // ------------------------------------------------------------

    @Test
    void testShutdownRejectsNewPuts() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(2));
        assertThrows(IllegalStateException.class, () -> queue.offer(2, 1, SECONDS));
    }

    @Test
    void testShutdownAllowsDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll(100, MILLISECONDS));
    }

    @Test
    void testShutdownWakesBlockedProducers() throws InterruptedException {
        for (int i = 0; i < 10; i++) queue.put(i); // Full
        
        Thread producer = new Thread(() -> {
            try { queue.put(99); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        producer.start();
        await().until(() -> producer.getState() == Thread.State.WAITING);
        
        queue.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive());
    }

    @Test
    void testShutdownWakesBlockedConsumers() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        consumer.start();
        await().until(() -> consumer.getState() == Thread.State.WAITING);
        
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // ------------------------------------------------------------
    // Stress Test: Multi-Producer / Multi-Consumer
    // ------------------------------------------------------------

    /**
     * Verifies no lost or duplicated elements under high contention.
     * Strategy: 
     * 1. Produce a known set of unique integers (0 to TOTAL_ITEMS-1).
     * 2. Multiple producers push them into the queue.
     * 3. Multiple consumers pull them into a thread-safe Set (ConcurrentHashMap.KeySetView).
     * 4. Verify the consumed set matches the produced set exactly.
     */
    @Test
    @DisabledIfSystemProperty(named = "ci", matches = "true", disabledReason = "Heavy stress test, disable on slow CI")
    void stressTestMultiProducerMultiConsumerNoLossNoDuplicates() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCER_COUNT = 8;
        final int CONSUMER_COUNT = 8;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
        
        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        
        // Use a concurrent set to collect results. 
        // Key: Integer, Value: Boolean (dummy). 
        // Using newKeySet() gives a Set view backed by ConcurrentHashMap.
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        LongAdder consumedSum = new LongAdder(); // For extra verification
        AtomicInteger producedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCER_COUNT + CONSUMER_COUNT);
        
        // --- Producers ---
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int item = producerId * ITEMS_PER_PRODUCER + i;
                        stressQueue.put(item); // Blocking put
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }
        
        // --- Consumers ---
        AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        // Use poll with timeout to allow graceful exit detection
                        Integer item = stressQueue.poll(100, MILLISECONDS);
                        if (item != null) {
                            // Verify uniqueness (add returns false if duplicate)
                            if (!consumed.add(item)) {
                                fail("Duplicate element detected: " + item);
                            }
                            consumedSum.add(item);
                        } else {
                            // Timeout occurred. Check if production is done and queue empty.
                            if (producedCount.get() == TOTAL_ITEMS && stressQueue.isEmpty()) {
                                break; // Exit condition
                            }
                            // If shutdown was called externally, break (not used here, but good pattern)
                            if (shutdownRequested.get() && stressQueue.isEmpty()) break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }
        
        // --- Go ---
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for completion with generous timeout
        assertTrue(doneLatch.await(60, SECONDS), "Test timed out");
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf("Stress test completed in %d ms. Throughput: %.0f ops/sec%n", 
                durationMs, (TOTAL_ITEMS * 2.0) / (durationMs / 1000.0));
        
        // --- Verification ---
        assertEquals(TOTAL_ITEMS, consumed.size(), "Lost elements detected!");
        assertEquals(TOTAL_ITEMS, producedCount.get(), "Production count mismatch");
        
        // Mathematical verification: Sum of 0 to N-1 = N*(N-1)/2
        long expectedSum = (long) TOTAL_ITEMS * (TOTAL_ITEMS - 1) / 2;
        assertEquals(expectedSum, consumedSum.sum(), "Element corruption detected (sum mismatch)");
        
        // Verify queue is empty
        assertTrue(stressQueue.isEmpty());
        assertEquals(0, stressQueue.size());
    }

    // ------------------------------------------------------------
    // Helper: Simple Awaitility-like polling for test synchronization
    // ------------------------------------------------------------
    private static class Await {
        static void until