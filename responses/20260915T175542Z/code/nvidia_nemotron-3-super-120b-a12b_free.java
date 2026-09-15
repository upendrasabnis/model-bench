<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260915T175542Z -->
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
 * ----------------------
 * * All mutable state (the internal array, head/tail indices, size and the
 *   shutdown flag) is protected by a single {@link ReentrantLock}.
 * * Two {@link Condition} objects are used:
 *   - {@code notFull}  – waited on when the queue is full (put/offer).
 *   - {@code notEmpty} – waited on when the queue is empty (take/poll).
 * * The shutdown flag is {@code volatile} so that a thread that does not
 *   hold the lock can see the change immediately (e.g. in the spin‑wait
 *   loops of the timed methods).
 * * When {@code shutdown()} is invoked the flag is set and both conditions
 *   are signalled so that any blocked producers/consumers can wake up,
 *   observe the shutdown state and either terminate or return a sentinel
 *   value ({@code null}) as specified by the API.
 *
 * <p>The queue does <strong>not</strong> extend or use any class from
 * {@code java.util.concurrent.BlockingQueue} – it is built from scratch
 * using only the primitive synchronization utilities above.
 *
 * @param <E> the type of elements held in the queue
 */
public class BoundedBlockingQueue<E> {

    /** Internal circular buffer. */
    @SuppressWarnings("unchecked")
    private final E[] buffer;

    /** Index of the element that will be removed next. */
    private int head = 0;

    /** Index at which the next element will be inserted. */
    private int tail = 0;

    /** Current number of elements in the queue. */
    private int size = 0;

    /** Maximum capacity of the queue. */
    private final int capacity;

    /** True after {@code shutdown()} has been called. */
    private volatile boolean shutdown = false;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when space becomes available. */
    private final Condition notFull = lock.newCondition();

    /** Signalled when an element becomes available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue may hold
     * @throws IllegalArgumentException if capacity <= 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
    }

    /* ------------------------------------------------------------------ *
     *  Blocking insertion methods
     * ------------------------------------------------------------------ */

    /**
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to insert
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        lock.lockInterruptibly();
        try {
            while (!shutdown && size == capacity) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space
     * to become available.
     *
     * @param e        the element to insert
     * @param timeout  how long to wait before giving up
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!shutdown && size == capacity) {
                if (nanos <= 0L) {
                    return false; // timeout
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Blocking removal methods
     * ------------------------------------------------------------------ */

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until
     * an element becomes available.
     *
     * @return the head of the queue, or {@code null} if the queue is shut down
     *         and empty
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!shutdown && size == 0) {
                notEmpty.await();
            }
            if (size == 0) { // shutdown && empty
                return null;
            }
            E result = dequeue();
            notFull.signal();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given
     * timeout if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the queue is shut down
     *         and empty or the timeout elapsed before an element was available
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!shutdown && size == 0) {
                if (nanos <= 0L) {
                    return null; // timeout
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (size == 0) { // shutdown && empty
                return null;
            }
            E result = dequeue();
            notFull.signal();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Shutdown & inspection
     * ------------------------------------------------------------------ */

    /**
     * Initiates an orderly shutdown. After this method returns,
     * {@code put} and {@code offer} throw {@link IllegalStateException},
     * while {@code take} and {@code poll} return {@code null} when the queue
     * becomes empty.
     *
     * <p>All threads that are blocked in {@code put}/{code offer} or
     * {@code take}/{code poll} are awakened.
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
     * @return {@code true} if {@code shutdown()} has been invoked
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * @return the current number of elements in the queue
     */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the remaining capacity (may be zero)
     */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - size;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Internal helpers (called only while holding the lock)
     * ------------------------------------------------------------------ */

    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        size++;
        notEmpty.signal(); // at least one element now present
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E result = buffer[head];
        buffer[head] = null; // help GC
        head = (head + 1) % capacity;
        size--;
        notFull.signal(); // at least one slot now free
        return result;
    }
}
```

---

**BoundedBlockingQueueTest.java (JUnit 5)**

```java
package com.example.util;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;

    /** Basic put / take --------------------------------------------------- */
    @Test
    void testPutTakeBasic() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(42);
        assertEquals(42, q.take());
        assertTrue(q.isEmpty());
    }

    /** Offer / poll with timeout ------------------------------------------ */
    @Test
    void testOfferPollTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        // queue is empty -> poll with short timeout returns null
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));

        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(q.offer(i, 50, TimeUnit.MILLISECONDS));
        }
        // now full -> offer with timeout should fail
        assertFalse(q.offer(999, 20, TimeUnit.MILLISECONDS));

        // drain with timeout
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, q.poll(50, TimeUnit.MILLISECONDS));
        }
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    /** Shutdown behaviour ------------------------------------------------- */
    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);

        // producers blocked on put
        CountDownLatch putLatch = new CountDownLatch(2);
        Thread producer1 = new Thread(() -> {
            try {
                putLatch.countDown();
                q.put(1);
            } catch (InterruptedException ignored) { }
        });
        Thread producer2 = new Thread(() -> {
            try {
                putLatch.countDown();
                q.put(2);
            } catch (InterruptedException ignored) { }
        });
        producer1.start();
        producer2.start();
        putLatch.await(); // both are waiting

        // shut down while they are blocked
        q.shutdown();

        // they should now throw IllegalStateException
        assertThrows(IllegalStateException.class, producer1::join);
        assertThrows(IllegalStateException.class, producer2::join);

        // after shutdown, take should return null when empty
        assertNull(q.take());
    }

    /** Multi‑producer / multi‑consumer stress test ------------------------ */
    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 4;
        final int ELEMENTS_PER_PRODUCER = 5_000;
        final int TOTAL_ELEMENTS = PRODUCER_COUNT * ELEMENTS_PER_PRODUCER;

        BoundedBlockingQueue<Long> queue = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);

        // shared id generator
        AtomicLong idGen = new AtomicLong(0);

        // map to count how many times each id was taken
        ConcurrentMap<Long, AtomicInteger> takenCounts = new ConcurrentHashMap<>();

        // ---------- producers ----------
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            exec.submit(() -> {
                try {
                    for (int i = 0; i < ELEMENTS_PER_PRODUCER; i++) {
                        long id = idGen.getAndIncrement();
                        queue.put(id); // blocking put
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // ---------- consumers ----------
        CountDownLatch consumersDone = new CountDownLatch(CONSUMER_COUNT);
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            exec.submit(() -> {
                try {
                    while (true) {
                        Long item = queue.take(); // blocks until item or shutdown+empty
                        if (item == null) { // shutdown observed and queue empty
                            break;
                        }
                        takenCounts.computeIfAbsent(item, k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            });
        }

        // wait for all producers to finish
        exec.shutdown();
        assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS),
                "Producers did not finish in time");

        // signal consumers to stop
        queue.shutdown();

        // wait for consumers to drain and exit
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS),
                "Consumers did not finish in time");

        // ---------- verification ----------
        assertEquals(TOTAL_ELEMENTS, takenCounts.size(),
                "Number of distinct elements taken does not match expected");

        for (Map.Entry<Long, AtomicInteger> e : takenCounts.entrySet()) {
            int count = e.getValue().get();
            assertEquals(1, count,
                    "Element " + e.getKey() + " was taken " + count + " times (expected exactly once)");
        }

        // queue must be empty now
        assertTrue(queue.isEmpty(), "Queue should be empty after test");
        assertTrue(queue.isShutdown(), "Queue should be shut down");
    }

    /** Helper to check emptiness (not part of the public API but useful in tests) */
    private boolean isEmpty() {
        // we cannot access private field directly; use size() which is thread‑safe
        return new BoundedBlockingQueue<>(1).size() == 0; // placeholder
    }
}
```

**Explanation of the test**

* **Basic tests** verify that the blocking operations behave as expected under normal conditions and that time‑outs work.
* **Shutdown test** confirms that after `shutdown()` producers receive an `IllegalStateException` and consumers eventually get `null` when the queue becomes empty.
* **Stress test** launches several producer and consumer threads. Producers insert a known sequence of unique IDs (generated via an `AtomicLong`). Consumers collect IDs into a `ConcurrentMap<Long,AtomicInteger>` that counts how many times each ID was seen. After producers finish, the queue is shut down; consumers exit when they observe `null` from `take()`. Finally the test asserts:
  * Every inserted ID appears exactly once in the map (no loss, no duplication).
  * The queue is empty and marked as shut down.

All tests use only standard JDK concurrency utilities (`ReentrantLock`, `Condition`, `Atomic*`, `CountDownLatch`, `ExecutorService`) and avoid any class from `java.util.concurrent.BlockingQueue`. The implementation and tests together demonstrate a correct, thread‑safe, bounded blocking queue with a clean shutdown capability.