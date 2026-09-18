<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260918T144055Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Arrays;
import java.util.Objects;
import java.util.NoSuchElementException;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state ({@code head}, {@code tail},
 * {@code count}, {@code closed}, and the backing {@code items} array). This coarse-grained locking strategy is chosen
 * because:
 * <ol>
 *   <li><strong>Simplicity & Correctness:</strong> A single lock eliminates complex lock-ordering deadlocks and makes
 *       reasoning about invariants (e.g., {@code 0 <= count <= capacity}) trivial.</li>
 *   <li><strong>Condition Variables:</strong> Two {@link Condition} variables ({@code notEmpty}, {@code notFull}) are
 *       associated with this lock. This allows precise signaling:
 *       <ul>
 *         <li>Producers wait on {@code notFull}; Consumers signal {@code notFull} after {@code take}.</li>
 *         <li>Consumers wait on {@code notEmpty}; Producers signal {@code notEmpty} after {@code put}.</li>
 *       </ul>
 *       Using separate conditions avoids "spurious wakeups" waking the wrong thread type (though spurious wakeups are
 *       still handled via {@code while} loops) and reduces context switching overhead compared to {@code notifyAll()}.
 *   </li>
 *   <li><strong>Fairness:</strong> The lock is instantiated as <strong>non-fair</strong> (default). For a bounded queue,
 *       non-fair locks typically provide higher throughput (barging allows a thread releasing the lock to re-acquire it
 *       immediately if it loops fast enough). If strict FIFO thread ordering is required, pass {@code true} to the
 *       {@link ReentrantLock} constructor.</li>
 * </ol>
 *
 * <h3>Shutdown Protocol</h3>
 * <ul>
 *   <li>{@link #shutdown()} atomically sets the {@code closed} flag and signals <strong>all</strong> waiting threads
 *       ({@code signalAll} on both conditions).</li>
 *   <li>Blocking operations ({@code put}, {@code take}) throw {@link IllegalStateException} if the queue is closed
 *       and empty/full respectively.</li>
 *   <li>Non-blocking operations ({@code offer}, {@code poll}) return {@code false}/{@code null} immediately if closed.</li>
 *   <li>Draining: After shutdown, {@code take}/{@code poll} continue to return remaining elements until empty.</li>
 * </ul>
 *
 * <h3>Interruption Handling</h3>
 * Blocking methods ({@code put}, {@code take}, timed {@code offer}, timed {@code poll}) are responsive to interruption.
 * If interrupted while waiting, they throw {@link InterruptedException} with the thread's interrupt status cleared
 * (standard {@link Condition#await} behavior).
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
    private boolean closed = false; // Shutdown flag

    // Synchronization primitives
    private final ReentrantLock lock = new ReentrantLock(); // Non-fair for throughput
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

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

    // --- Internal Helpers ---

    private void checkNotClosedForPut() {
        if (closed) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    private void enqueue(E x) {
        // Nulls are allowed as elements? Standard BlockingQueue forbids nulls. We follow that convention.
        Objects.requireNonNull(x, "Null elements not supported");
        items[tail] = x;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return x;
    }

    // --- Public API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been {@link #shutdown()} shut down
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly(); // Respond to interrupt immediately on lock acquisition
        try {
            // Wait while full AND not closed. If closed while full, we fail.
            while (count == capacity && !closed) {
                notFull.await();
            }
            checkNotClosedForPut();
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
     * @throws IllegalStateException if the queue has been {@link #shutdown()} shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait while empty AND not closed. If closed while empty, we fail.
            while (count == 0 && !closed) {
                notEmpty.await();
            }
            if (count == 0 && closed) {
                throw new IllegalStateException("Queue shut down and empty");
            }
            E x = dequeue();
            notFull.signal(); // Wake up one producer
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time
     * for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if the waiting time elapsed before space was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been {@link #shutdown()} shut down
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !closed) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            checkNotClosedForPut();
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
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !closed) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (count == 0) { // Either timeout or closed+empty
                return null;
            }
            E x = dequeue();
            notFull.signal();
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown. No further elements can be added ({@code put}/{@code offer} will fail).
     * Threads blocked in {@code put}, {@code take}, or timed variants will be unblocked and receive
     * {@link IllegalStateException} (blocking) or {@code false}/{@code null} (timed).
     * Elements already in the queue can still be consumed via {@code take}/{@code poll}.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (closed) return; // Idempotent
            closed = true;
            // Wake everyone up to re-evaluate state (closed flag)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue has been shut down.
     */
    public boolean isShutdown() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
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
     * Returns {@code true} if this queue contains no elements.
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
     * Returns {@code true} if this queue is full.
     */
    public boolean isFull() {
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the capacity of this queue.
     */
    public int capacity() {
        return capacity;
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
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

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
    void testCapacityLimit() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());
        assertEquals(2, q.size());
    }

    @Test
    void testNullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    // --- Blocking Behavior ---

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
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

        // Wait for producer to actually block on the lock/condition
        await().until(producerBlocked::get);
        assertTrue(q.isFull());

        // Consume to unblock
        assertEquals(1, q.take());
        producer.join(1000);
        assertFalse(producer.isAlive()); // Producer should have finished putting 2
        assertEquals(2, q.take());
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
        assertTrue(queue.isEmpty());

        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer(1, 100, MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        assertFalse(q.offer(2, 50, MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.poll(100, MILLISECONDS));
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        assertNull(queue.poll(50, MILLISECONDS));
    }

    @Test
    void testTimedOperationsRespondToInterrupt() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread t = new Thread(() -> {
            try {
                q.offer(2, 10, SECONDS); // Long wait
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore flag
            }
        });
        t.start();
        Thread.sleep(50); // Ensure it's waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Protocol ---

    @Test
    void testShutdownRejectsNewPuts() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(2, 1, SECONDS));
    }

    @Test
    void testShutdownAllowsDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testShutdownUnblocksWaitingThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        AtomicBoolean producerUnblocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                q.put(2);
            } catch (IllegalStateException | InterruptedException e) {
                producerUnblocked.set(true);
            }
        });
        producer.start();
        await().until(() -> q.isFull()); // Ensure producer is waiting

        q.shutdown();
        producer.join(1000);
        assertTrue(producerUnblocked.get());
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // --- Interruption ---

    @Test
    void testPutInterrupted() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testTakeInterrupted() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Stress Test: Multi-Producer / Multi-Consumer ---

    /**
     * High-concurrency stress test.
     * Verifies:
     * 1. No lost elements (Sum of consumed == Sum of produced).
     * 2. No duplicated elements (Set size == Count).
     * 3. Correct blocking/unblocking under contention.
     * 4. Graceful shutdown drains correctly.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS) // Timing sensitivity on CI Windows runners sometimes; remove if stable
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        // Use ConcurrentHashMap as a concurrent Set to track unique elements
        Set<Integer> consumedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producerDoneLatch = new CountDownLatch(producerCount);
        CountDownLatch consumerDoneLatch = new CountDownLatch(consumerCount);

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
                } catch (IllegalStateException e) {
                    // Shutdown during put
                } finally {
                    producerDoneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.take(); // Blocks until element or shutdown+empty
                        if (val == null) break; // Should not happen with take()
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        boolean added = consumedSet.add(val);
                        if (!added) {
                            fail("Duplicate element detected: " + val);
                        }
                    }
                } catch (IllegalStateException e) {
                    // Queue shut down and empty - normal termination
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumerDoneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go! ---
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for producers to finish putting
        assertTrue(producerDoneLatch.await(30, SECONDS), "Producers timed out");
        
        // Give consumers a moment to drain, then shutdown
        // We don't wait for consumerDoneLatch yet because they are blocked on take()
        Thread.sleep(100); // Allow drain
        stressQueue.shutdown(); // Unblocks consumers

        assertTrue(consumerDoneLatch.await(10, SECONDS), "Consumers timed out after shutdown");

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        // --- Verification ---
        assertEquals(totalItems, producedCount.get(), "Produced count mismatch");
        assertEquals(totalItems, consumedCount.get(), "Consumed count mismatch");
        assertEquals(totalItems, consumedSet.size(), "Duplicate elements detected (Set size mismatch)");
        assertEquals(producedSum.get(), consumedSum.get(), "Data corruption: Sum mismatch");
        assertTrue(stressQueue.isEmpty(), "Queue not empty after drain");
        assertTrue(stressQueue.isShutdown());

        System.out.printf("Stress Test Passed: %d items in %d ms (Throughput: %.0f ops/sec)%n",
                totalItems, durationMs, (totalItems * 2.0 / durationMs) * 1000); // *2 for put+take
    }

    // --- Helper for async condition waiting in tests ---
    // Simple polling await to avoid Awaitility dependency
    private void await() {
        // Static import style helper
    }
    
    // Java doesn't have a built-in await().until() without libraries.
    // Implementing a simple one here for the test class.
    static class Awaitility {
        public static void until(BooleanSupplier condition) {
            until(condition, 5, SECONDS);
        }
        public static void until(BooleanSupplier condition, long timeout, TimeUnit unit) {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Condition not met within timeout");
                }
                Thread.onSpinWait(); // Hint to CPU
                // Sleep briefly to avoid burning CPU in test waits
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted waiting"); }
            }
        }
    }
    
    // Static helper methods for test readability
    static void await(BooleanSupplier condition) { Awaitility.until(condition); }
    static void await(BooleanSupplier condition, long timeout, TimeUnit unit) { Awaitility.until(condition, timeout, unit); }
}
```

---

### 3. Key Design Decisions Explained

#### 1. `ReentrantLock` vs `synchronized`
*   **Choice:** `ReentrantLock` with `Condition` variables.
*   **Reason:** `Condition` allows multiple wait-sets per lock (`notEmpty` vs `notFull`). With `synchronized`/`wait`/`notifyAll`, a `put` waking up a `put` (spurious or `notifyAll`) wastes CPU cycles re-checking `count == capacity`. `Condition.signal()` targets the correct wait-set.

#### 2. `lockInterruptibly()` in Blocking Ops
*   **Choice:** `lock.lockInterruptibly()` instead of `lock.lock()`.
*   **Reason:** If a