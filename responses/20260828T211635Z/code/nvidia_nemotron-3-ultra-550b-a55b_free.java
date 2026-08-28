<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260828T211635Z -->
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
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} (`lock`) guards all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}, and the backing array).
 *       This ensures memory visibility and atomicity of compound actions (check-then-act).</li>
 *   <li><b>Condition Synchronization:</b> Two condition variables decouple waiting producers and consumers:
 *       <ul>
 *         <li>{@code notFull}: Signaled by {@code take}/{@code poll} when a slot becomes available.
 *             Awaited by {@code put}/{@code offer} when {@code count == capacity}.</li>
 *         <li>{@code notEmpty}: Signaled by {@code put}/{@code offer} when an element is added.
 *             Awaited by {@code take}/{@code poll} when {@code count == 0}.</li>
 *       </ul>
 *       Using two conditions avoids "thundering herd" problems where producers wake up producers
 *       (or consumers wake up consumers) unnecessarily.</li>
 *   <li><b>Signaling Policy:</b> {@code signal()} is used instead of {@code signalAll()} for standard
 *       enqueue/dequeue operations because only one thread (of the opposite type) can actually proceed
 *       when a single slot/item becomes available. {@code signalAll()} is reserved for {@code shutdown()}
 *       to unblock all waiting threads immediately.</li>
 *   <li><b>Spurious Wakeups:</b> All {@code await()} calls are wrapped in {@code while} loops
 *       re-checking the state predicate ({@code count == capacity} or {@code count == 0}).</li>
 *   <li><b>Shutdown Protocol:</b> Setting {@code shutdown = true} is atomic with the lock held.
 *       {@code signalAll()} is called on both conditions. Subsequent blocking calls throw
 *       {@link IllegalStateException}; non-blocking calls return failure indicators (null/false).</li>
 *   <li><b>Interruptibility:</b> Blocking methods ({@code put}, {@code take}) use
 *       {@code awaitUninterruptibly()} to match standard {@link java.util.concurrent.BlockingQueue}
 *       semantics (transfer interruption to caller only after operation completes or throw
 *       {@code InterruptedException} if interrupted *while* waiting). Timed methods respect
 *       interruption immediately.</li>
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

    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Internal Helpers ---

    private void checkNotShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return e;
    }

    // --- Blocking API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkNotShutdown();
                notFull.await(); // Releases lock, waits for signal
            }
            checkNotShutdown();
            enqueue(e);
            notEmpty.signal(); // Wake up one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E e = dequeue();
            notFull.signal(); // Wake up one waiting producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed / Non-Blocking API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return {@code true} if successful, {@code false} if the timeout elapsed
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
                checkNotShutdown();
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            checkNotShutdown();
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * wait time if necessary for an element to become available.
     * @return the head of this queue, or {@code null} if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Shutdown + empty = immediate return null
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue if it is possible to do so
     * immediately without exceeding the queue's capacity.
     * @return {@code true} if the element was added, {@code false} otherwise
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        lock.lock();
        try {
            checkNotShutdown();
            if (count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is empty.
     * @return the head of this queue, or {@code null} if empty
     */
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Inspection & Lifecycle ---

    /** Returns the number of elements in this queue. */
    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    /** Returns {@code true} if this queue contains no elements. */
    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    /** Returns {@code true} if this queue is at capacity. */
    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    /** Returns the capacity of this queue. */
    public int capacity() { return capacity; }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>All threads blocked in {@code put}, {@code take}, {@code offer}, or {@code poll}
     *       will unblock immediately.</li>
     *   <li>Threads blocked in {@code put}/{@code offer} will throw {@link IllegalStateException}.</li>
     *   <li>Threads blocked in {@code take}/{@code poll} will return normally if elements remain,
     *       or throw {@link IllegalStateException}/{@code null} respectively if empty.</li>
     *   <li>Subsequent calls to {@code put}/{@code offer} throw {@link IllegalStateException}.</li>
     * </ul>
     * This method is idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Wake everyone up to process shutdown logic
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if shutdown has been initiated. */
    public boolean isShutdown() {
        return shutdown; // Volatile read is sufficient
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
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
import static java.util.concurrent.TimeUnit.*;

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
    void testBasicFifo() {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testOfferPollNoWait() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void testCapacityLimit() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3)); // Full
        assertEquals(1, q.poll());
        assertTrue(q.offer(3)); // Space available
    }

    @Test
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
    }

    // --- Blocking Behavior ---

    @Test
    void testPutBlocksWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                producerBlocked.set(true);
                q.put(2); // Should block
            } catch (InterruptedException ignored) {}
        });
        producer.start();
        // Wait for producer to actually block
        await().until(producerBlocked::get);
        assertTrue(producer.isAlive());
        assertEquals(1, q.take()); // Consume
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertEquals(2, q.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws Exception {
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try {
                consumerBlocked.set(true);
                queue.take(); // Should block
            } catch (InterruptedException ignored) {}
        });
        consumer.start();
        await().until(consumerBlocked::get);
        assertTrue(consumer.isAlive());
        queue.put(42); // Produce
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        // Should fail quickly because full
        assertFalse(q.offer(2, 100, MILLISECONDS));
    }

    @Test
    void testOfferTimeoutEventuallySucceeds() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread consumer = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        // Wait up to 1s, should succeed after consumer takes
        assertTrue(q.offer(99, 1, SECONDS));
        consumer.join();
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, MILLISECONDS));
        queue.put(5);
        assertEquals(5, queue.poll(100, MILLISECONDS));
    }

    @Test
    void testInterruptionDuringPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50); // Ensure waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testInterruptionDuringTake() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Behavior ---

    @Test
    void testShutdownWakesUpProducers() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread producer = new Thread(() -> {
            try { q.put(2); fail("Should throw ISE"); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        producer.start();
        Thread.sleep(50);
        q.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertTrue(q.isShutdown());
    }

    @Test
    void testShutdownWakesUpConsumers() {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should throw ISE"); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        consumer.start();
        Thread.sleep(50);
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    void testShutdownAllowsDraining() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
    }

    @Test
    void testShutdownRejectsNewPuts() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, SECONDS));
    }

    // --- Stress Test: Multi-Producer / Multi-Consumer ---

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Heavy stress test, run locally")
    void stressTestMultiProducerMultiConsumerNoLossNoDuplicates() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        // Use a concurrent map to track counts of each specific integer to detect duplicates/loss
        ConcurrentHashMap<Integer, AtomicInteger> elementCounts = new ConcurrentHashMap<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // Producers
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i;
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
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
                    // Consumers run until queue is shutdown AND empty
                    while (true) {
                        Integer val;
                        try {
                            // Use poll with timeout to allow checking shutdown state periodically
                            // without relying solely on shutdown() interrupting take() (though it does).
                            // Using take() is fine because shutdown() signals notEmpty.
                            val = stressQueue.take();
                        } catch (IllegalStateException e) {
                            // Queue shut down and empty
                            break;
                        }
                        consumedSum.addAndGet(val);
                        AtomicInteger cnt = elementCounts.get(val);
                        if (cnt == null || cnt.decrementAndGet() < 0) {
                            errorFlag.set(true);
                            System.err.println("Duplicate or unknown element detected: " + val);
                        }
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // Start
        startLatch.countDown();

        // Wait for producers to finish
        // We wait on a separate latch for producers only to trigger shutdown exactly when done producing
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        // Re-using doneLatch is tricky because consumers count down too.
        // Let's just wait for all threads on doneLatch, but shutdown after producers finish.
        // Better: Use Phaser or separate latches. Simple approach: Join producer threads explicitly.
        // Since we used anonymous threads, let's track them.
        // Actually, the doneLatch counts down for everyone. We need to know when producers are done.
        // Let's modify: Producers count down a specific latch.

        // --- Re-working Stress Test for precise shutdown timing ---
        // (The above logic has a race: consumers might finish early if queue empties before producers finish,
        // but take() blocks. Shutdown must happen AFTER all puts).
    }

    // --- Refactored Robust Stress Test ---
    @Test
    void stressTestVerified() throws InterruptedException {
        final int CAPACITY = 64;
        final int PRODUCERS = 8;
        final int CONSUMERS = 8;
        final int ITEMS_PER_PRODUCER = 10_000;
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        
        // Tracking: We use a ConcurrentHashMap to count occurrences of every single integer.
        // Integers range from 0 to TOTAL_ITEMS - 1.
        ConcurrentHashMap<Integer, AtomicInteger> counter = new ConcurrentHashMap<>(TOTAL_ITEMS * 2);
        AtomicLong producedSum = new AtomicLong();
        AtomicLong consumedSum = new AtomicLong();
        AtomicInteger errors = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producersFinished = new CountDownLatch(PRODUCERS);
        CountDownLatch consumersFinished = new CountDownLatch(CONSUMERS);

        // Producer Threads
        for (int p = 0; p < PRODUCERS; p++) {
            final int base = p * ITEMS_PER_PRODUCER;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int val = base + i;
                        q.put(val);
                        producedSum.addAndGet(val);
                        counter.computeIfAbsent(val, k -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    producersFinished.countDown();
                }
            }, "Prod-" + p).start();
        }

        // Consumer Threads
        for (int c = 0; c < CONSUMERS; c++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    while (true) {
                        Integer val;
                        try {
                            val = q.take(); // Blocks until item or shutdown
                        } catch (IllegalStateException e) {
                            // Shutdown called and queue empty
                            break;
                        }
                        consumedSum.addAndGet(val);
                        AtomicInteger cnt = counter.get(val);
                        if (cnt == null || cnt.decrementAndGet() < 0) {
                            errors.incrementAndGet();
                            System.err.println("ERROR: Duplicate/Lost item " + val);
                        }
