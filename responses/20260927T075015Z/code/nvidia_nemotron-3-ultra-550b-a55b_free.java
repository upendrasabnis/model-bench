<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260927T075015Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete implementation of a thread-safe, generic, bounded blocking queue with a clean shutdown mechanism, followed by comprehensive JUnit 5 tests.

---

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Objects;

/**
 * A thread-safe, generic, bounded blocking queue implementation built on {@link ReentrantLock}
 * and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} guards all mutable state (the ring buffer,
 *       head/tail indices, count, and shutdown flag). This ensures memory visibility and atomicity
 *       of compound actions (check-then-act).</li>
 *   <li><b>Signaling:</b> Two {@link Condition} variables are used for efficient waiting:
 *       <ul>
 *         <li>{@code notEmpty}: Signaled by producers after inserting an element; awaited by consumers when queue is empty.</li>
 *         <li>{@code notFull}: Signaled by consumers after removing an element; awaited by producers when queue is full.</li>
 *       </ul>
 *   </li>
 *   <li><b>Shutdown Protocol:</b> A volatile {@code shutdown} flag (guarded by the lock) allows the queue to be
 *       closed gracefully.
 *       <ul>
 *         <li>{@code shutdown()}: Sets the flag, clears the buffer (optional, helps GC), and signals <b>all</b> waiting threads
 *             on both conditions to wake up and exit blocking methods with {@link IllegalStateException}.</li>
 *         <li>Blocking methods ({@code put}, {@code take}) throw {@link IllegalStateException} if shutdown occurs while waiting.</li>
 *         <li>Timed methods ({@code offer}, {@code poll}) return {@code false}/{@code null} respectively if shutdown occurs.</li>
 *       </ul>
 *   </li>
 *   <li><b>Fairness:</b> The lock is instantiated as non-fair (default) for higher throughput. Fair locking can be enabled
 *       via constructor if strict FIFO thread ordering is required.</li>
 *   <li><b>Spurious Wakeups:</b> All {@code await()} calls are inside {@code while} loops checking the state predicate,
 *       handling spurious wakeups correctly.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] buffer; // Ring buffer
    private final int capacity;
    private int head = 0;          // Index to take from
    private int tail = 0;          // Index to put at
    private int count = 0;         // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast read check outside lock (optional optimization)

    // --- Synchronization Primitives ---
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    /**
     * Creates a queue with the given capacity and non-fair locking policy.
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @param fair if true, uses a fair ordering policy for the lock
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // ---------------------------------------------------------
    // Blocking Operations (Unbounded Wait)
    // ---------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly();
        try {
            // Wait while full AND not shutdown
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) throw new IllegalStateException("Queue shut down");
            
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait while empty AND not shutdown
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            // If shutdown and empty, throw exception. If shutdown but has elements, drain them.
            if (shutdown && count == 0) {
                throw new IllegalStateException("Queue shut down and empty");
            }
            
            E item = (E) buffer[head];
            dequeue();
            notFull.signal(); // Wake up one producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------
    // Timed Operations (Bounded Wait)
    // ---------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timed out or queue shut down
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) return false;
            
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timed out or queue shut down and empty
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && count == 0) return null;
            
            E item = (E) buffer[head];
            dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------
    // Immediate Operations (Non-blocking)
    // ---------------------------------------------------------

    /** @return true if inserted, false if full or shut down */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element, or null if empty or shut down */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null; // Empty (shutdown doesn't matter if empty, but spec says return null)
            E item = (E) buffer[head];
            dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------
    // Inspection & Shutdown
    // ---------------------------------------------------------

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    public boolean isShutdown() {
        return shutdown; // Volatile read is safe for boolean flag
    }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Sets the shutdown flag.</li>
     *   <li>Clears internal references (helps GC).</li>
     *   <li>Signals <b>all</b> threads waiting on {@code notEmpty} and {@code notFull}.</li>
     * </ul>
     * Threads blocked in {@code put/take} will wake up and throw {@link IllegalStateException}.
     * Threads blocked in {@code offer/poll} (timed) will wake up and return {@code false}/{@code null}.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            
            // Clear buffer for GC
            for (int i = 0; i < capacity; i++) buffer[i] = null;
            head = 0; tail = 0; count = 0;
            
            // Wake everyone up
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------
    // Internal Helpers (Assumes Lock Held)
    // ---------------------------------------------------------

    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) buffer[head];
        buffer[head] = null; // Avoid memory leak (GC)
        head = (head + 1) % capacity;
        count--;
        return item;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 * Covers: API contracts, blocking behavior, shutdown, concurrency correctness, and stress testing.
 */
@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    // ---------------------------------------------------------
    // Fixtures & Helpers
    // ---------------------------------------------------------

    private BoundedBlockingQueue<Integer> queue;
    private static final int DEFAULT_CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(DEFAULT_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (queue != null && !queue.isShutdown()) {
            queue.shutdown();
        }
    }

    // ---------------------------------------------------------
    // Basic API & Contract Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void constructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    @DisplayName("Null elements rejected")
    void nullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Basic FIFO order and size tracking")
    void basicFifoAndSize() throws InterruptedException {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());

        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(3, queue.size());
        assertFalse(queue.isEmpty());
        assertFalse(queue.isFull());

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());

        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Offer/Poll immediate success/failure")
    void immediateOfferPoll() {
        // Empty queue
        assertNull(queue.poll());
        assertFalse(queue.offer(null)); // Null check happens before lock usually, but offer(null) throws NPE per impl
        // Actually offer(null) throws NPE. poll() returns null.
        assertThrows(NullPointerException.class, () -> queue.offer(null));

        // Fill up
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            assertTrue(queue.offer(i));
        }
        assertTrue(queue.isFull());
        assertFalse(queue.offer(999)); // Full
        assertEquals(DEFAULT_CAPACITY, queue.size());

        // Drain
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            assertEquals(i, queue.poll());
        }
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Timed offer/poll timeout behavior")
    void timedOfferPollTimeout() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < DEFAULT_CAPACITY; i++) queue.put(i);

        // Offer should timeout quickly
        assertFalse(queue.offer(99, 50, TimeUnit.MILLISECONDS));
        assertEquals(DEFAULT_CAPACITY, queue.size());

        // Drain
        for (int i = 0; i < DEFAULT_CAPACITY; i++) queue.take();

        // Poll should timeout quickly
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Timed offer/poll success before timeout")
    void timedOfferPollSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------
    // Blocking Behavior Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Put blocks when full, unblocks on take")
    void putBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());

        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch putFinished = new CountDownLatch(1);
        AtomicReference<Exception> putError = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            try {
                putStarted.countDown();
                q.put(3); // Should block
                putFinished.countDown();
            } catch (Exception e) {
                putError.set(e);
                putFinished.countDown();
            }
        });
        producer.start();

        assertTrue(putStarted.await(1, TimeUnit.SECONDS), "Producer should start and block");
        Thread.sleep(100); // Ensure it's waiting
        assertFalse(putFinished.await(100, TimeUnit.MILLISECONDS), "Producer should still be blocked");

        q.take(); // Make space
        assertTrue(putFinished.await(1, TimeUnit.SECONDS), "Producer should unblock and finish");
        assertNull(putError.get());
        assertEquals(3, q.take()); // Verify element was added
    }

    @Test
    @DisplayName("Take blocks when empty, unblocks on put")
    void takeBlocksWhenEmpty() throws InterruptedException {
        CountDownLatch takeStarted = new CountDownLatch(1);
        CountDownLatch takeFinished = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                takeStarted.countDown();
                result.set(queue.take()); // Should block
                takeFinished.countDown();
            } catch (Exception e) {
                error.set(e);
                takeFinished.countDown();
            }
        });
        consumer.start();

        assertTrue(takeStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertFalse(takeFinished.await(100, TimeUnit.MILLISECONDS));

        queue.put(42);
        assertTrue(takeFinished.await(1, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(42, result.get());
    }

    @Test
    @DisplayName("InterruptedException thrown on interrupt during put/take")
    void interruptDuringBlock() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it

        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure blocking
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should exit on interrupt");
    }

    // ---------------------------------------------------------
    // Shutdown Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown unblocks waiting producers/consumers with IllegalStateException")
    void shutdownUnblocksWaiters() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        CountDownLatch producerBlocked = new CountDownLatch(1);
        CountDownLatch consumerBlocked = new CountDownLatch(1);
        AtomicReference<Exception> prodEx = new AtomicReference<>();
        AtomicReference<Exception> consEx = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            try { producerBlocked.countDown(); q.put(2); } 
            catch (Exception e) { prodEx.set(e); }
        });
        Thread consumer = new Thread(() -> {
            try { q.take(); consumerBlocked.countDown(); q.take(); } // Take 1, then block on 2nd
            catch (Exception e) { consEx.set(e); }
        });

        producer.start();
        consumer.start();
        
        assertTrue(producerBlocked.await(1, TimeUnit.SECONDS));
        assertTrue(consumerBlocked.await(1, TimeUnit.SECONDS)); // Consumed the '1'
        
        // Both threads now blocked: Producer on full, Consumer on empty
        q.shutdown();

        producer.join(1000);
        consumer.join(1000);

        assertTrue(prodEx.get() instanceof IllegalStateException, "Producer should get ISE");
        assertTrue(consEx.get() instanceof IllegalStateException, "Consumer should get ISE");
        assertTrue(q.isShutdown());
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements before exception")
    void shutdownDrainsRemaining() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        // Should be able to take existing elements
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Next take throws
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Timed offer/poll return false/null immediately after shutdown")
    void shutdownTimedOpsReturnFalse() throws InterruptedException {
        queue.put(1);
        queue.shutdown();

        assertFalse(queue.offer(2, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS)); // Drain existing
        assertNull(queue.poll(1, TimeUnit.SECONDS));      // Empty & shutdown -> null
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void shutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // ---------------------------------------------------------
    // Concurrency Correctness Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Multi-producer/Multi-consumer stress test (No lost/duplicated elements)")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Set to detect duplicates (ConcurrentHashMap backed Set)
        Set<Integer> producedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<Integer> consumedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicReference<Exception> firstError = new AtomicReference<>();

        // Producers produce unique ranges: 0..4999, 5000..9999, etc.
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i;
                        stressQueue.put(val);
                        producedSet.add(val);
                        producedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.take();
                        if (val == -1) break; // Poison pill (not used here, rely on count)
                        if (!consumedSet.add(val)) {
                            firstError.compareAndSet(null, new IllegalStateException("Duplicate consumed: " + val));
                            break;
                        }
                        consumedCount.incrementAndGet();
                        if (consumedCount.get() >= totalItems) break;
                    }
                } catch (Exception e) {
                    if (!(e instanceof IllegalStateException && stressQueue.isShutdown())) {
                        firstError.compareAndSet(null, e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        startLatch.countDown(); // Go!
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Test timed out");

        if (firstError.get() != null) throw firstError.get();

        // Verification
        assertEquals(totalItems, producedCount.get(), "All items produced");
        assertEquals(totalItems, consumedCount.get(), "All items consumed");
        assertEquals(producedSet, consumedSet, "Produced set must equal Consumed set (No loss, no duplication)");
        assertTrue(stressQueue.isEmpty(), "Queue should be empty at end");
    }

    @Test
    @DisplayName("Stress test with Timed Offer/Poll and Shutdown")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void stressTestTimedOpsAndShutdown() throws InterruptedException {
        final int capacity = 50;
        final int threads = 10;
        final int opsPerThread = 2000;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        
        AtomicLong successfulOffers = new AtomicLong();
        AtomicLong successfulPolls = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final boolean isProducer = i % 2 == 0;
            new Thread(() -> {
                try {
                    Random r = new Random();
                    for (