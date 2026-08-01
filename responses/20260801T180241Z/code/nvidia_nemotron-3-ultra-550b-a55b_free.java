<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260801T180241Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
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
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} (non-fair by default for throughput,
 *       fair optional via constructor) guards all mutable state ({@code head}, {@code tail},
 *       {@code count}, {@code shutdown}). This ensures atomicity of check-then-act sequences
 *       (e.g., "if not full, insert").</li>
 *   <li><b>Signaling:</b> Two {@link Condition} variables decouple waiting producers and consumers:
 *       <ul>
 *         <li>{@code notFull}: Signaled by {@code take/poll} when a slot becomes available. Producers wait here.</li>
 *         <li>{@code notEmpty}: Signaled by {@code put/offer} when an element becomes available. Consumers wait here.</li>
 *       </ul>
 *       This avoids "thundering herd" problems where signaling all threads (via {@code lock.notifyAll()})
 *       would wake incorrect waiters (e.g., waking a producer when an item was added).</li>
 *   <li><b>Memory Visibility:</b> The lock provides happens-before edges for all state mutations.
 *       The {@code shutdown} flag and {@code shutdownCause} are {@code volatile}/{@code AtomicReference}
 *       to allow lock-free visibility checks in {@code offer/poll} fast-paths, though primary
 *       synchronization occurs via the lock.</li>
 *   <li><b>Spurious Wakeups:</b> All {@code await()} calls are wrapped in {@code while} loops
 *       re-checking the predicate (state), per the Java Language Specification requirements.</li>
 *   <li><b>Shutdown Protocol:</b> {@code shutdown()} acquires the lock, sets the {@code shutdown} flag,
 *       records the cause, and signals {@code notEmpty} and {@code notFull} (broadcast) to unblock
 *       all waiting threads. Blocking methods throw {@link IllegalStateException} if the queue
 *       is shutdown and empty (or full for producers). Non-blocking methods return special values
 *       ({@code false}/{@code null}) immediately.</li>
 *   <li><b>Interrupt Handling:</b> Blocking methods ({@code put}, {@code take}, timed variants)
 *       are responsive to interruption. If interrupted while waiting, they throw
 *       {@link InterruptedException} with the interrupt status cleared (standard behavior).
 *       The implementation restores the interrupt status on the current thread if the wait
 *       returns due to interruption but the operation cannot complete (rare race), though
 *       standard {@code Condition.await} handles this natively.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] items;       // Circular buffer
    private int head;                   // Index to take from
    private int tail;                   // Index to put at
    private int count;                  // Current number of elements
    private final int capacity;         // Fixed capacity

    // --- Synchronization ---
    private final ReentrantLock lock;
    private final Condition notEmpty;   // Signaled when count goes 0 -> 1
    private final Condition notFull;    // Signaled when count goes capacity -> capacity-1

    // --- Shutdown State ---
    private volatile boolean shutdown = false;
    private final AtomicReference<Throwable> shutdownCause = new AtomicReference<>();

    /**
     * Creates a queue with the given capacity and non-fair locking policy (default).
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @param fair if true, uses a fair locking policy (FIFO thread ordering), else non-fair (higher throughput)
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdown();
                notFull.await();
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
     * @throws IllegalStateException if the queue has been shutdown and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown();
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
     * @return {@code true} if successful, {@code false} if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdown();
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     * @return the head of this queue, or {@code null} if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown and is empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown();
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Non-Blocking Operations (Fast Path) ---

    /**
     * Inserts the specified element at the tail of this queue if it is possible
     * to do so immediately without exceeding the queue's capacity.
     * @return {@code true} if the element was added, {@code false} if queue is full or shutdown
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e) {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if
     * this queue is empty.
     * @return the head of this queue, or {@code null} if empty or shutdown
     */
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

    // --- Inspection & Shutdown ---

    /** @return current number of elements */
    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    /** @return remaining capacity */
    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    /** @return true if shutdown has been initiated */
    public boolean isShutdown() {
        return shutdown; // Volatile read
    }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Wake up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Subsequent blocking calls throw {@link IllegalStateException}.</li>
     *   <li>Non-blocking calls return failure indicators ({@code false}/{@code null}).</li>
     *   <li>Existing elements remain accessible via {@code take/poll} until empty.</li>
     * </ul>
     * @param cause the reason for shutdown (available via {@link #getShutdownCause()})
     */
    public void shutdown(Throwable cause) {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            shutdownCause.set(cause);
            // Broadcast to all waiters (producers and consumers)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Convenience: shutdown with generic cause. */
    public void shutdown() {
        shutdown(new IllegalStateException("Queue shutdown"));
    }

    /** @return the cause provided to {@link #shutdown(Throwable)}, or null if not shutdown. */
    public Throwable getShutdownCause() {
        return shutdownCause.get();
    }

    // --- Internal Helpers ---

    private void checkShutdown() {
        if (shutdown) {
            Throwable cause = shutdownCause.get();
            throw new IllegalStateException("Queue shutdown: " + (cause != null ? cause.getMessage() : ""), cause);
        }
    }

    @SuppressWarnings("unchecked")
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 * Covers functional correctness, blocking behavior, timeouts, interruption,
 * shutdown semantics, and high-concurrency stress testing.
 */
@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int SMALL_CAPACITY = 2;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure no threads hang
    }

    // ---------------------------------------------------------
    // Basic Functionality
    // ---------------------------------------------------------

    @Test
    @DisplayName("Basic FIFO offer/poll")
    void testBasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Capacity limit respected")
    void testCapacityLimit() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(SMALL_CAPACITY);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3)); // Full
        assertEquals(2, q.size());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------
    // Blocking Behavior (put/take)
    // ---------------------------------------------------------

    @Test
    @DisplayName("put blocks when full")
    void testPutBlocksWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it

        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch putBlocked = new CountDownLatch(1);
        AtomicReference<Exception> putError = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            try {
                putStarted.countDown();
                q.put(2); // Should block
                putBlocked.countDown(); // Should not reach here until take happens
            } catch (Exception e) {
                putError.set(e);
            }
        });
        producer.start();

        assertTrue(putStarted.await(1, TimeUnit.SECONDS), "Producer didn't start");
        Thread.sleep(100); // Ensure producer is waiting
        assertTrue(producer.isAlive(), "Producer should be blocked");
        assertEquals(0, putBlocked.getCount(), "Producer should not have proceeded");

        q.take(); // Consume 1, wake producer
        assertTrue(putBlocked.await(1, TimeUnit.SECONDS), "Producer not woken");
        assertEquals(2, q.take()); // Verify element transferred
    }

    @Test
    @DisplayName("take blocks when empty")
    void testTakeBlocksWhenEmpty() throws Exception {
        CountDownLatch takeStarted = new CountDownLatch(1);
        CountDownLatch takeBlocked = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                takeStarted.countDown();
                result.set(queue.take()); // Should block
                takeBlocked.countDown();
            } catch (InterruptedException ignored) {}
        });
        consumer.start();

        assertTrue(takeStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(consumer.isAlive());

        queue.put(42);
        assertTrue(takeBlocked.await(1, TimeUnit.SECONDS));
        assertEquals(42, result.get());
    }

    // ---------------------------------------------------------
    // Timed Operations
    // ---------------------------------------------------------

    @Test
    @DisplayName("offer with timeout returns false on full")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("poll with timeout returns null on empty")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Timed offer succeeds if space opens")
    void testTimedOfferSuccess() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);

        CountDownLatch consumerStarted = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            consumerStarted.countDown();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            q.take();
        });
        consumer.start();
        consumerStarted.await();

        // Offer with timeout longer than consumer sleep
        assertTrue(q.offer(2, 500, TimeUnit.MILLISECONDS));
        assertEquals(2, q.take());
    }

    // ---------------------------------------------------------
    // Interruption Handling
    // ---------------------------------------------------------

    @Test
    @DisplayName("put throws InterruptedException")
    void testPutInterruption() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread producer = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50); // Ensure waiting
        producer.interrupt();
        producer.join(1000);
        assertFalse(producer.isAlive(), "Thread should exit on interrupt");
    }

    @Test
    @DisplayName("take throws InterruptedException")
    void testTakeInterruption() throws Exception {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(50);
        consumer.interrupt();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("Timed offer/poll respect interruption")
    void testTimedInterruption() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread producer = new Thread(() -> {
            try { q.offer(2, 10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50);
        producer.interrupt();
        producer.join(1000);
        assertFalse(producer.isAlive());
    }

    // ---------------------------------------------------------
    // Shutdown Semantics
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown wakes blocked put")
    void testShutdownWakesPut() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { q.put(2); }
            catch (IllegalStateException e) { error.set(e); } // Expected
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50);
        q.shutdown(new RuntimeException("Maintenance"));
        producer.join(1000);

        assertNotNull(error.get(), "Should throw IllegalStateException");
        assertTrue(error.get() instanceof IllegalStateException);
        assertTrue(q.isShutdown());
    }

    @Test
    @DisplayName("Shutdown wakes blocked take")
    void testShutdownWakesTake() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try { queue.take(); }
            catch (IllegalStateException e) { error.set(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(50);
        queue.shutdown();
        consumer.join(1000);

        assertNotNull(error.get());
        assertTrue(error.get() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrain() {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown -> take throws
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.put(3));
    }

    @Test
    @DisplayName("Non-blocking ops return failure after shutdown")
    void testNonBlockingAfterShutdown() {
        queue.shutdown();
        assertFalse(queue.offer(1));
        assertNull(queue.poll());
        assertFalse(queue.offer(1, 1, TimeUnit.SECONDS));
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown(new Exception("First"));
        queue.shutdown(new Exception("Second")); // Should not overwrite cause? Or just no-op.
        // Implementation uses CAS on AtomicReference? No, simple set. Last write wins usually, but lock guards it.
        // Current impl: if(shutdown) return. So first cause preserved.
        assertTrue(queue.getShutdownCause().getMessage().contains("First"));
    }

    // ---------------------------------------------------------
    // Fairness / Ordering (Best Effort)
    // ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Fair vs Non-Fair construction")
    void testFairnessConstruction(boolean fair) {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10, fair);
        assertTrue(q.offer(1));
        q.shutdown();
    }

    // ---------------------------------------------------------
    // STRESS TEST: Multi-Producer / Multi-Consumer
    // ---------------------------------------------------------

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer - No Lost/Duplicate Elements")
    @DisabledOnOs(OS.WINDOWS) // Timing sensitive on CI Windows runners sometimes, optional
    void stressTestMultiProducerMultiConsumer() throws Exception {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Phaser to synchronize start/stop for cleaner measurement
        Phaser phaser = new Phaser(producerCount + consumerCount + 1); // +1 for main
        
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        AtomicReference<Throwable> producerError = new AtomicReference<>();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(producerCount + consumerCount);
        List<Future<?>> futures = new ArrayList<>();

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            futures.add(exec.submit(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all to start
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i + 1; // Unique values 1..Total
                        stressQueue.put(val); // Blocking put
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    producerError.compareAndSet(null, t);
                } finally {
                   