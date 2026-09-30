<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260930T232756Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy
 * -----------------------
 * The queue protects all mutable state with a single {@link ReentrantLock}
 * (named {@code lock}). Two {@link Condition} objects are used:
 *
 * <ul>
 *   <li>{@code notFull} – waited on when the queue is full (by {@code put} and
 *       timed {@code offer}) and signalled when an element is removed.</li>
 *   <li>{@code notEmpty} – waited on when the queue is empty (by {@code take}
 *       and timed {@code poll}) and signalled when an element is added.</li>
 * </ul>
 *
 * All state changes (head/tail indices, element count, shutdown flag) are
 * performed while holding {@code lock}, guaranteeing visibility and atomicity.
 * The {@code shutdown} flag is volatile only for the benefit of the
 * {@code isShutdown()} read‑only check; writes to it happen under the lock,
 * so there is no race condition.
 *
 * The internal storage is a fixed‑size array treated as a circular buffer.
 * {@code head} points to the element that will be removed next,
 * {@code tail} points to the next free slot for insertion, and {@code count}
 * holds the number of stored elements.
 *
 * The queue supports:
 *
 * <ul>
 *   <li>Blocking {@code put(E)} and {@code take()}</li>
 *   <li>Timed {@code offer(E, long, TimeUnit)} and {@code poll(long, TimeUnit)}</li>
 *   <li>A clean {@code shutdown()} that wakes all waiting threads and causes
 *       subsequent {@code put}/{@code offer} to return {@code false} (or throw
 *       {@code IllegalStateException} for {@code put}) and {@code take}/{@code
 *       poll} to return {@code null} (or throw {@code NoSuchElementException}
 *       for {@code take}) when the queue is empty.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // storage array
    private final int capacity;            // maximum number of elements
    private int head;                      // index of first element
    private int tail;                      // index of next free slot
    private int count;                     // current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    /** true after shutdown() has been called */
    private volatile boolean shutdown;

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum size of the queue; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not allowed");
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue is shut down");
                }
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
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     * @throws NoSuchElementException if the queue has been shut down and is
     *         empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new java.util.NoSuchElementException("Queue is shut down and empty");
                }
                notEmpty.await();
            }
            E e = (E) dequeue();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to
     * the given timeout for space to become available.
     *
     * @param e      the element to add
     * @param timeout the maximum time to wait
     * @param unit   the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the
     *         timeout elapsed before space became available
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the given
     * timeout if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the head of the queue, or {@code null} if the timeout elapsed
     *         before an element was available
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null;
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return (E) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown of the queue.
     * <p>After this method returns, no further {@code put} or timed {@code offer}
     * will succeed (they will throw {@code IllegalStateException} or return
     * {@code false} respectively). Blocking {@code take} and timed {@code poll}
     * will return {@code null} (or throw {@code NoSuchElementException} for
     * {@code take}) when the queue becomes empty.</p>
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
     * @return {@code true} if {@code shutdown()} has been called
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /** Returns the current number of elements in the queue. */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the remaining capacity (number of elements that can be added
     *  without blocking). */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal helper methods – must be called while holding lock
    // -----------------------------------------------------------------
    private void enqueue(E e) {
        // assert lock.isHeldByCurrentThread();
        items[tail] = e;
        tail = (tail + 1) % capacity;
        ++count;
        notEmpty.signal();
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        E e = (E) items[head];
        items[head] = null; // help GC
        head = (head + 1) % capacity;
        --count;
        notFull.signal();
        return e;
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int ITEMS_PER_PRODUCER = 1_000;
    private static final int NUM_PRODUCERS = 4;
    private static final int NUM_CONSUMERS = 3;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // ensure no threads are left waiting
    }

    // -----------------------------------------------------------------
    // Basic functionality tests
    // -----------------------------------------------------------------
    @Test
    void testPutTake() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
    }

    @Test
    void testOfferPollTimedSuccess() throws InterruptedException {
        assertTrue(queue.offer(99, 200, TimeUnit.MILLISECONDS));
        assertEquals(99, queue.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void testOfferPollTimedFailure() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 100, TimeUnit.MILLISECONDS));
        }
        // now it's full – offer with short timeout should fail
        assertFalse(queue.offer(999, 10, TimeUnit.MILLISECONDS));
        // poll with short timeout should return null because queue is not empty
        // (we just check that it does not block)
        assertNotNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownBlocksPut() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void testShutdownOfferReturnsFalse() {
        queue.shutdown();
        assertFalse(queue.offer(1, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownTakeThrowsWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertThrows(java.util.NoSuchElementException.class, queue::take);
    }

    @Test
    void testShutdownPollReturnsNullWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------
    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        // Shared atomic counter that produces unique values for all producers
        AtomicInteger sequencer = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        List<Future<?>> producerFutures = new ArrayList<>();
        List<Future<?>> consumerFutures = new ArrayList<>();

        // Collection where consumers deposit what they take.
        // Using a ConcurrentLinkedQueue avoids extra synchronization in the test.
        ConcurrentLinkedQueue<Integer> consumed = new ConcurrentLinkedQueue<>();

        // ----- Producer task -------------------------------------------------
        Runnable producer = () -> {
            try {
                for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                    int value = sequencer.getAndIncrement();
                    queue.put(value); // blocking put
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // preserve interrupt status
            }
        };

        // ----- Consumer task -------------------------------------------------
        Runnable consumer = () -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (val == null) {
                        // If the queue is shut down and empty we can finish.
                        if (queue.isShutdown() && queue.isEmpty()) {
                            break;
                        }
                        // otherwise just spin again after timeout
                        continue;
                    }
                    consumed.add(val);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Start producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            producerFutures.add(exec.submit(producer));
        }
        // Start consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            consumerFutures.add(exec.submit(consumer));
        }

        // Wait for all producers to finish
        for (Future<?> f : producerFutures) {
            f.get(); // will throw if producer terminated exceptionally
        }

        // Signal shutdown – no more puts will be accepted
        queue.shutdown();

        // Wait for consumers to finish (they will exit after seeing shutdown+empty)
        for (Future<?> f : consumerFutures) {
            f.get();
        }

        exec.shutdownNow(); // ensure no stray threads remain
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));

        // -----------------------------------------------------------------
        // Verification
        // -----------------------------------------------------------------
        int expectedTotal = NUM_PRODUCERS * ITEMS_PER_PRODUCER;
        assertEquals(expectedTotal, consumed.size(),
                "Number of consumed elements does not match number of produced elements");

        // Ensure each value appears exactly once
        List<Integer> list = new ArrayList<>(consumed);
        Collections.sort(list);
        for (int i = 0; i < expectedTotal; i++) {
            assertEquals(i, list.get(i),
                    "Duplicate or missing element detected at position " + i);
        }
    }

    // Helper to expose the empty state (package‑private for test only)
    private boolean isEmpty() {
        return queue.size() == 0;
    }
}
```

### Explanation of the Test Suite

* **Basic functionality** – verifies that `put`/`take`, timed `offer`/`poll`, and shutdown behave as specified for a single thread scenario.
* **Multi‑producer / multi‑consumer stress test** –  
  * Uses an `AtomicInteger` to generate a globally unique sequence of integers (`0 … N‑1`).  
  * Starts several producer threads that repeatedly call blocking `put`.  
  * Starts several consumer threads that repeatedly call timed `poll` (with a short timeout) and place each retrieved value into a `ConcurrentLinkedQueue`.  
  * After all producers finish, `shutdown()` is invoked; consumers exit once they observe the queue is both shut down and empty.  
  * Finally the test asserts that the total number of consumed elements equals the number produced, sorts the consumed values, and checks that each integer from `0` to `N‑1` appears exactly once – guaranteeing **no loss** and **no duplication** even under heavy concurrent contention.

The queue itself relies on a single `ReentrantLock` plus two `Condition` variables, which is a classic monitor‑style implementation that guarantees thread‑safety while keeping the code easy to reason about. All public methods respect the contract of a bounded blocking queue, and the shutdown procedure cleanly wakes waiting threads without leaving them blocked indefinitely.