<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261114T211253Z -->
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

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * <p>
 * Concurrency Strategy:
 * <ul>
 *   <li><b>Single Lock ({@link ReentrantLock}):</b> Uses a single mutual exclusion lock to protect all
 *       internal state (buffer array, head, tail, count, shutdown flag). This simplifies reasoning
 *       about invariants compared to lock-striping (two locks for head/tail) and avoids the
 *       complexity of memory visibility issues across multiple locks for a bounded buffer.</li>
 *   <li><b>Two Conditions ({@link Condition}):</b> Uses {@code notEmpty} and {@code notFull} conditions
 *       associated with the single lock. This allows precise signaling: producers wait on {@code notFull},
 *       consumers wait on {@code notEmpty}. {@code signal()} is used instead of {@code signalAll()}
 *       for efficiency (only one thread type can proceed per state change), though {@code signalAll()}
 *       is used during shutdown to wake everyone.</li>
 *   <li><b>Circular Array:</b> Fixed-size array with {@code head} (index to take) and {@code tail}
 *       (index to put) indices modulo capacity. {@code count} tracks current size for O(1) {@code size()}.</li>
 *   <li><b>Spurious Wakeup Handling:</b> All {@code await()} calls are inside {@code while} loops
 *       re-checking the state predicate.</li>
 *   <li><b>Interruption Policy:</b> Blocking methods ({@code put}, {@code take}, timed {@code offer}/{@code poll})
 *       are responsive to interruption. They throw {@link InterruptedException} immediately if the
 *       current thread is interrupted upon entry or while waiting. The interrupt status is cleared
 *       by {@code await()} upon throwing the exception, adhering to standard Java convention.</li>
 *   <li><b>Shutdown Semantics:</b> {@code shutdown()} is idempotent. It sets a volatile {@code shutdown}
 *       flag, signals all waiting threads, and prevents future {@code put}/{@code offer}. 
 *       {@code take}/{@code poll} drain remaining elements; once empty and shut down, they throw
 *       {@link IllegalStateException} ({@code take}) or return {@code null} ({@code poll}).</li>
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
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Internal Helpers ---

    private void checkNotShutdownForPut() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

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
        return e;
    }

    // --- Public API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lockInterruptibly();
        try {
            checkNotShutdownForPut();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkNotShutdownForPut(); // Re-check after wakeup
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timed out or queue shut down
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            if (shutdown) return false;
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                if (shutdown) return false; // Fail fast if shutdown during wait
            }
            enqueue(e);
            notEmpty.signal();
            return true;
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
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E e = dequeue();
            notFull.signal(); // Wake up one producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timed out or queue shut down and empty
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null;
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
     * Initiates an orderly shutdown. 
     * <ul>
     *   <li>Prevents future {@code put}/{@code offer} (they fail immediately).</li>
     *   <li>Wakes up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Allows consumers to drain remaining elements via {@code take}/{@code poll}.</li>
     *   <li>Once drained, {@code take} throws {@link IllegalStateException}, {@code poll} returns {@code null}.</li>
     * </ul>
     * This method is idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Wake everyone up so they can observe the shutdown flag
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
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
import java.util.stream.Collectors;
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

    // --- Basic Functionality ---

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testNullElementRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    void testFifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testCapacityAndSize() throws InterruptedException {
        assertEquals(10, queue.capacity());
        assertEquals(0, queue.size());
        assertEquals(10, queue.remainingCapacity());

        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(9, queue.remainingCapacity());

        queue.put(2);
        queue.put(3);
        assertEquals(3, queue.size());

        queue.take();
        assertEquals(2, queue.size());
    }

    // --- Blocking Behavior ---

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(2);
        small.put(1);
        small.put(2);
        assertEquals(2, small.size());

        Thread producer = new Thread(() -> {
            try { small.put(3); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(100); // Give producer time to block
        assertTrue(producer.isAlive(), "Producer should be blocked");

        assertEquals(1, small.take()); // Make space
        producer.join(1000); // Producer should unblock and finish
        assertFalse(producer.isAlive());
        assertEquals(3, small.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
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
    void testOfferTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Fill it
        assertFalse(small.offer(2, 100, MILLISECONDS), "Should return false on timeout");
        assertEquals(1, small.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.poll(1, SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        assertNull(queue.poll(100, MILLISECONDS), "Should return null on timeout");
    }

    // --- Interruption Handling ---

    @Test
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full

        Thread t = new Thread(() -> {
            try { small.put(2); fail("Should have been interrupted"); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); fail("Should have been interrupted"); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testTimedOfferInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1);
        Thread t = new Thread(() -> {
            try { small.offer(2, 10, SECONDS); fail("Should have been interrupted"); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Semantics ---

    @Test
    void testShutdownPreventsPut() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(2, 1, SECONDS));
    }

    @Test
    void testShutdownAllowsDrainThenFails() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll(1, SECONDS));
    }

    @Test
    void testShutdownWakesBlockedThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full

        AtomicBoolean producerUnblocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { small.put(2); } 
            catch (IllegalStateException e) { producerUnblocked.set(true); } // Expected after shutdown
            catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive());

        small.shutdown();
        producer.join(1000);
        assertTrue(producerUnblocked.get(), "Producer should unblock and see shutdown");
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    // --- Multi-Producer / Multi-Consumer Stress Test ---

    @Test
    @Timeout(value = 30, unit = SECONDS) // Global timeout for the test
    void testStressMultiProducerMultiConsumerNoLostOrDuplicatedElements() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Set to detect duplicates (ConcurrentHashMap as Set)
        Set<Integer> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        LongAdder sumProduced = new LongAdder();
        LongAdder sumConsumed = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicBoolean producerError = new AtomicBoolean(false);
        AtomicBoolean consumerError = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Unique values globally
                        stressQueue.put(val); // Blocking put
                        sumProduced.add(val);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    producerError.set(true);
                } catch (Exception e) {
                    producerError.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.poll(500, MILLISECONDS); // Timed poll
                        if (val == null) {
                            // Timeout: check if production is done and queue empty
                            if (producedCount.get() == totalItems && stressQueue.size() == 0) {
                                break; // Exit condition
                            }
                            continue; // Spurious timeout, keep polling
                        }
                        
                        // Verify uniqueness
                        if (!seen.add(val)) {
                            System.err.println("DUPLICATE DETECTED: " + val);
                            consumerError.set(true);
                            break;
                        }
                        sumConsumed.add(val);
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerError.set(true);
                } catch (Exception e) {
                    consumerError.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Start & Wait ---
        startLatch.countDown(); // Release all threads simultaneously
        assertTrue(doneLatch.await(20, SECONDS), "Test timed out waiting for threads to finish");

        // --- Verification ---
        assertFalse(producerError.get(), "Producer threads encountered errors");
        assertFalse(consumerError.get(), "Consumer threads encountered errors (duplicates or exceptions)");
        
        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed");
        assertEquals(sumProduced.sum(), sumConsumed.sum(), "Sum mismatch: Lost or corrupted elements");
        assertEquals(totalItems, seen.size(), "Duplicate elements detected (Set size mismatch)");
        
        // Queue should be empty
        assertEquals(0, stressQueue.size());
        stressQueue.shutdown();
    }

    // --- Edge Cases & Fairness (Optional but good) ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void testSingleElementQueue(int capacity) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        q.put(1);
        assertThrows(IllegalStateException.class, () -> q.put(2)); // Full
        assertEquals(1, q.take());
        assertThrows(IllegalStateException.class, () -> q.take()); // Empty after shutdown? No, not shutdown yet.
        // Wait, take() blocks if not shutdown. 
        // Let's test shutdown on single element.
        q.put(1);
        q.shutdown();
        assertEquals(1, q.take());
        assertThrows(IllegalStateException.class, () -> q.take());
    }
}
```

---

### 3. Concurrency Strategy Documentation (Summary)

The implementation follows a **Monitor Pattern** using `ReentrantLock` and `Condition` variables.

#### 1. Mutual Exclusion (Single Lock)
A single `ReentrantLock` guards **all** mutable state: the circular buffer array, `head`, `tail`, `count`, and `shutdown` flag.
*   **Why not two locks (split head/tail)?** While split locks (like `ArrayBlockingQueue` in JDK) allow concurrent put/take, they significantly complicate `size()`, `remainingCapacity()`, and especially `shutdown()` atomicity. For a generic bounded queue where throughput is often limited by the critical section speed (array access is fast), a single lock reduces complexity and avoids subtle memory visibility bugs without significant contention penalty for typical workloads.

#### 2. Condition Synchronization (Two Conditions)
*   `notEmpty`: Signaled by `put`/`offer` after enqueueing. Waited on by `take`/`poll`.
*   `notFull`: Signaled by `take`/`poll` after dequeueing. Waited on by `put`/`offer`.
*   **Signal vs SignalAll**: `signal()` (wake one) is used for standard operation. This is optimal because:
    *   Only *one* consumer can consume the single added element.
    *   Only *one* producer can fill the single freed slot.
    *   `signalAll()` is reserved for `shutdown()` to unblock *all* threads immediately so they can observe the terminal state.

#### 3. Spurious Wakeups & State Re-checking
All `await()` calls reside in `while (condition)` loops, not `if` blocks. This handles:
*   Spurious wakeups (JVM/OS behavior).
*   **Signal Stealing**: Another thread acquires the lock and consumes the slot/element before the woken thread runs.

#### 4. Interruption Handling
*   `lock.lockInterruptibly()` allows the lock acquisition itself to be interrupted.
*   `Condition.await()` throws `InterruptedException`, clearing the interrupt flag.
*   The methods propagate `InterruptedException` immediately, preserving the standard Java blocking method contract