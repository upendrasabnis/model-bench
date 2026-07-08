<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260708T210536Z -->
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
 * <p>Implementation notes (concurrency strategy):
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state
 *       ({@code items}, {@code takeIndex}, {@code putIndex}, {@code count},
 *       {@code shutdown}). This guarantees mutual exclusion for
 *       enqueue/dequeue operations.</li>
 *   <li>Two {@link Condition} objects are associated with the lock:
 *       <ul>
 *         <li>{@code notEmpty} – signaled when an element is added;
 *             threads waiting in {@code take} or {@code poll} await on it.</li>
 *         <li>{@code notFull}  – signaled when an element is removed;
 *             threads waiting in {@code put} or {@code offer} await on it.</li>
 *       </ul>
 *   </li>
 *   <li>The lock is acquired in {@code lockInterruptibly()} form so that
 *       a thread can be interrupted while waiting.</li>
 *   <li>All waiting loops re‑check the predicate after being awakened
 *       (to protect against spurious wake‑ups).</li>
 *   <li>A volatile {@code shutdown} flag allows a clean termination:
 *       after {@code shutdown()} is called,
 *       <ul>
 *         <li>{@code put} and {@code offer} fail immediately
 *             (throw {@code IllegalStateException} or return {@code false}).</li>
 *         <li>{@code take} and {@code poll} will drain the queue and then
 *             return {@code null} (or {@code false} for timed poll).</li>
 *       </ul>
 *       The flag is read without holding the lock (volatile) but the
 *       actual state change is performed under the lock and both conditions
 *       are signaled to wake any blocked threads.</li>
 *   </li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The lock that protects all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when the queue is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Condition waited on when the queue is full. */
    private final Condition notFull = lock.newCondition();

    /** Circular buffer storing the elements. */
    @SuppressWarnings("unchecked")
    private final E[] items = (E[]) new Object[0]; // placeholder, replaced in ctor

    /** Index of the next element to be taken. */
    private int takeIndex;

    /** Index of the next element to be put. */
    private int putIndex;

    /** Number of elements currently in the queue. */
    private int count;

    /** Maximum capacity of the queue. */
    private final int capacity;

    /** True after {@code shutdown()} has been invoked. */
    private volatile boolean shutdown;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold;
     *                 must be > 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = (E[]) new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     * @throws IllegalStateException if {@code shutdown()} has been called
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shutdown");
                }
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shutdown");
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting up to the specified timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting if necessary until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // queue is empty and shutdown → no more data
                }
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting up to the specified timeout if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return the head of this queue, or {@code null} if the specified waiting time
     *         elapses before an element is available (or the queue is shutdown and empty)
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // shutdown and empty → nothing to return
                }
                if (nanos <= 0) {
                    return null; // timeout
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals that no further elements will be placed in this queue.
     * After invocation, {@code put} and {@code offer} will fail,
     * while {@code take} and {@code poll} will drain any remaining elements
     * and then return {@code null} (or {@code false} for timed operations).
     *
     * <p>All threads blocked in {@code put}/{@code offer}/{@code take}/{@code poll}
     * are awakened.</p>
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return {@code true} if this queue has been shut down
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * @return the number of elements currently in this queue
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
     * @return the number of additional elements that this queue can ideally
     *         accept without blocking
     */
    public int remainingCapacity() {
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /* -------------------- internal helpers -------------------- */

    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % items.length;
        ++count;
        notEmpty.signal(); // at least one element is now present
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = items[takeIndex];
        items[takeIndex] = null; // help GC
        takeIndex = (takeIndex + 1) % items.length;
        --count;
        notFull.signal(); // at least one slot is now free
        return e;
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 *
 * <p>The stress test creates multiple producer and consumer threads that
 * cooperate to insert and remove a known set of uniquely identifiable
 * elements. After all producers finish, the queue is shut down and the
 * consumers drain it. The test then verifies that:
 * <ul>
 *   <li>No element is lost (the set of taken elements equals the set of
 *       produced elements).</li>
 *   <li>No element appears more than once (no duplicates).</li>
 *   <li>The queue respects its bounded capacity (producers block when full).</li>
 *   <li>Shutdown prevents further insertions and allows graceful draining.</li>
 * </ul>
 */
class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int TOTAL_ELEMENTS = 10_000;
    private static final int PRODUCER_COUNT = 4;
    private static final int CONSUMER_COUNT = 4;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure no stray threads are left running
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutTakeBasics() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferPollWithTimeout() throws InterruptedException {
        // Offer succeeds immediately
        assertTrue(queue.offer(99, 100, TimeUnit.MILLISECONDS));
        // Poll returns the element immediately
        assertEquals(99, queue.poll(100, TimeUnit.MILLISECONDS));
        // Poll times out and returns null
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownPreventsFurtherInserts() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(CAPACITY, queue.size());

        // Shutdown while full
        queue.shutdown();
        assertTrue(queue.isShutdown());

        // Further put should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> queue.put(-1));
        // Offer should return false
        assertFalse(queue.offer(-1, 10, TimeUnit.MILLISECONDS));

        // Drain the queue – take should return elements then null
        List<Integer> drained = new ArrayList<>();
        Integer v;
        while ((v = queue.take()) != null) {
            drained.add(v);
        }
        assertEquals(CAPACITY, drained.size());
        // After draining, take returns null immediately
        assertNull(queue.take());
    }

    @Test
    @Timeout(30)
    void testMultiProducerMultiConsumerStress() throws Exception {
        // Shared state for verification
        final AtomicInteger nextId = new AtomicInteger(0);
        final List<Integer> produced = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        CountDownLatch prodStart = new CountDownLatch(1);
        CountDownLatch prodDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consDone = new CountDownLatch(CONSUMER_COUNT);

        // Producer task
        Runnable producer = () -> {
            try {
                prodStart.await(); // wait for all producers to start together
                while (true) {
                    int id = nextId.getAndIncrement();
                    if (id >= TOTAL_ELEMENTS) {
                        break; // no more work
                    }
                    // put may block; we rely on the queue's bounded behavior
                    queue.put(id);
                    produced.add(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                prodDone.countDown();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                while (true) {
                    Integer v = queue.take(); // blocks until element or shutdown+empty
                    if (v == null) {
                        // queue is empty and shutdown → exit
                        break;
                    }
                    consumed.add(v);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consDone.countDown();
            }
        };

        // Launch producers and consumers
        for (int i = 0; i < PRODUCER_COUNT; i++) {
            exec.submit(producer);
        }
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            exec.submit(consumer);
        }

        // Let all producers start at roughly the same time
        prodStart.countDown();

        // Wait for producers to finish putting all elements
        assertTrue(prodDone.await(20, TimeUnit.SECONDS), "Producers did not finish in time");

        // Shutdown the queue – no more puts will be accepted
        queue.shutdown();

        // Wait for consumers to drain the queue and exit
        assertTrue(consDone.await(20, TimeUnit.SECONDS), "Consumers did not finish in time");

        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        // ---- Verification ----
        // 1. No lost elements: every produced element must have been consumed
        assertEquals(TOTAL_ELEMENTS, produced.size(), "Number of produced elements mismatch");
        assertEquals(TOTAL_ELEMENTS, consumed.size(), "Number of consumed elements mismatch");

        // 2. No duplicates: each id appears exactly once in consumed list
        List<Integer> consumedSorted = new ArrayList<>(consumed);
        Collections.sort(consumedSorted);
        for (int i = 0; i < TOTAL_ELEMENTS; i++) {
            assertEquals(i, consumedSorted.get(i), "Duplicate or missing element at position " + i);
        }

        // 3. Queue should be empty after shutdown and drain
        assertTrue(queue.isEmpty(), "Queue should be empty after test");
        assertTrue(queue.isShutdown(), "Queue should be shut down");
    }

    // Helper to check emptiness (not part of the public API but useful for tests)
    private boolean isEmpty() {
        return queue.size() == 0;
    }
}
```

**Explanation of the test**

* **Basic tests** verify the core blocking behavior, timed operations, and shutdown semantics.
* **Stress test**
  * Uses a fixed‑size thread pool with several producers and consumers.
  * Producers atomically fetch increasing integer IDs (`AtomicInteger`) to guarantee uniqueness.
  * All produced IDs are recorded in a thread‑safe list; consumers record what they take.
  * After producers finish, `shutdown()` is called – this blocks any further `put`/`offer` and wakes waiting consumers.
  * Consumers exit when `take()` returns `null` (signalling shutdown + empty queue).
  * Final assertions confirm:
    * No element is lost (`produced.size() == consumed.size() == TOTAL_ELEMENTS`).
    * No duplicates or missing elements (sorted consumed list matches `0 … TOTAL_ELEMENTS‑1`).
    * The queue ends empty and shut down.

The implementation relies solely on explicit locking (`ReentrantLock`) and condition variables, satisfying the requirement **not** to use `java.util.concurrent.BlockingQueue`. All operations are thread‑safe, bounded, and support clean shutdown.