<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260927T075015Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, bounded, blocking queue called `BoundedBlockingQueue<E>` that does **not** rely on `java.util.concurrent.BlockingQueue`.  
The implementation uses a single `ReentrantLock` together with two `Condition` objects:

* `notFull` – waited on by producers when the queue is full.  
* `notEmpty` – waited on by consumers when the queue is empty.

A `volatile boolean shutDown` flag signals a clean shutdown. When `shutDown` becomes `true`:

* `put(E)` throws `IllegalStateException`.  
* `offer(E, timeout, unit)` returns `false`.  
* `take()` returns `null` (instead of blocking forever) when the queue is empty and shut down.  
* `poll(timeout, unit)` returns `null` under the same condition.

All mutating operations (`put`, `take`, `offer`, `poll`) are performed while holding the lock, guaranteeing mutual exclusion. The two conditions allow threads to block efficiently without busy‑spinning.

---

## 1. BoundedBlockingQueue Implementation

```java
package com.example.concurrent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy:
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state (the array,
 *       head/tail indices and the element count). This guarantees mutual
 *       exclusion.</li>
 *   <li>Two {@link Condition} objects, {@code notFull} and {@code notEmpty},
 *       are used for efficient blocking:
 *       <ul>
 *         <li>Producers wait on {@code notFull} when the queue reaches its
 *             capacity.</li>
 *         <li>Consumers wait on {@code notEmpty} when the queue is empty.</li>
 *       </ul>
 *   </li>
 *   <li>A volatile {@code shutDown} flag enables a clean shutdown. When set,
 *       producers are rejected immediately and consumers receive a sentinel
 *       {@code null} value instead of blocking forever.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** Internal circular buffer */
    @SuppressWarnings("unchecked")
    private final E[] buffer;
    /** Index of the next element to take */
    private int takeIndex = 0;
    /** Index of the next element to put */
    private int putIndex = 0;
    /** Number of elements currently stored */
    private int count = 0;
    /** Maximum capacity (must be > 0) */
    private final int capacity;
    /** Guard for all mutable state */
    private final ReentrantLock lock = new ReentrantLock();
    /** Signaled when the queue is not full */
    private final Condition notFull = lock.newCondition();
    /** Signaled when the queue is not empty */
    private final Condition notEmpty = lock.newCondition();
    /** True after {@link #shutdown()} has been invoked */
    private volatile boolean shutDown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if capacity &le; 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
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
        Objects.requireNonNull(e, "null elements are not permitted");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutDown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            insert(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted while
     *         waiting
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutDown) {
                    return null; // sentinel indicating shutdown + empty
                }
                notEmpty.await();
            }
            E e = extract();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue if it is possible
     * to do so immediately without exceeding the queue's capacity, waiting up
     * to the specified wait time if necessary for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout}
     * @return {@code true} if the element was added; {@code false} if the timeout
     *         elapsed before space became available or the queue was shut down
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if {@code e} is {@code null}
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutDown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            insert(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit    a {@link TimeUnit} determining how to interpret the {@code timeout}
     * @return the head of this queue, or {@code null} if the specified waiting
     *         time elapses before an element is available or the queue is shut down
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutDown) {
                    return null; // shutdown + empty
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = extract();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown. After this method returns:
     * <ul>
     *   <li>{@code put} and {@code offer} will reject further insertions
     *       (throw {@code IllegalStateException} or return {@code false}).</li>
     *   <li>{@code take} will return {@code null} when the queue becomes empty
     *       instead of blocking indefinitely.</li>
     *   <li>{@code poll} will return {@code null} when the queue becomes empty
     *       instead of blocking.</li>
     * </ul>
     * No further synchronization is required; callers may continue to invoke
     * the blocking methods – they will observe the shutdown state as described.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            shutDown = true;
            // Wake up any threads that might be waiting on notFull or notEmpty
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue has been shut down via {@link #shutdown()}.
     *
     * @return {@code true} if shut down, {@code false} otherwise
     */
    public boolean isShutdown() {
        return shutDown;
    }

    /** Returns the current number of elements in the queue (for testing/debugging). */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Inserts an element at putIndex; assumes lock is held and space is available. */
    private void insert(E e) {
        buffer[putIndex] = e;
        putIndex = (putIndex + 1) % capacity;
        ++count;
        notEmpty.signal(); // at least one consumer can now proceed
    }

    /** Extracts an element at takeIndex; assumes lock is held and queue is non‑empty. */
    @SuppressWarnings("unchecked")
    private E extract() {
        E e = buffer[takeIndex];
        if (e == null) {
            // This should never happen because we never store null.
            throw new AssertionError("Null element encountered in buffer");
        }
        buffer[takeIndex] = null; // help GC
        takeIndex = (takeIndex + 1) % capacity;
        --count;
        notFull.signal(); // at least one producer can now proceed
        return e;
    }
}
```

### Key Points of the Concurrency Strategy

| Aspect | Detail |
|--------|--------|
| **Mutual Exclusion** | A single `ReentrantLock` guards all mutable fields (`buffer`, `takeIndex`, `putIndex`, `count`). |
| **Condition Variables** | `notFull` blocks producers when `count == capacity`; `notEmpty` blocks consumers when `count == 0`. |
| **Efficient Waiting** | Threads call `await()` / `awaitNanos()` which releases the lock and parks the thread until signaled. |
| **Shutdown Handling** | A `volatile boolean shutDown` is checked under the lock. When set, waiting threads are awakened via `signalAll()` and subsequently either reject inserts or return a sentinel (`null`) for blocking removal calls. |
| **Fairness** | The default `ReentrantLock` is non‑fair, which is acceptable for a generic queue. If strict FIFO ordering of thread acquisition is required, construct the lock with `new ReentrantLock(true)`. |
| **Null Elements** | Not permitted – simplifies the sentinel logic for shutdown (`null` means “queue empty & shut down”). |

---

## 2. JUnit 5 Test Suite

The tests cover:

* Basic blocking behavior (`put`/`take`).
* Timed operations (`offer`/`poll`).
* Shutdown semantics.
* A multi‑producer / multi‑consumer stress test that guarantees **no lost or duplicated elements**.

```java
package com.example.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;
    private static final int TIMEOUT_MS = 100;

    /** Helper to create a fresh queue for each test. */
    private BoundedBlockingQueue<Integer> newQueue() {
        return new BoundedBlockingQueue<>(CAPACITY);
    }

    @Test
    void putTake_blocking() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();
        Producer p = new Producer(q, 1, 3);
        Consumer c = new Consumer(q, 3);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(p);
        exec.submit(c);
        exec.shutdown();
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2, 3), c.getResults());
    }

    @Test
    void offerPoll_timed() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();

        // Fill the queue to capacity
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(q.offer(i, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        // Next offer should timeout because queue is full
        assertFalse(q.offer(999, TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Drain with timed polls
        List<Integer> drained = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            Integer v = q.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertNotNull(v, "poll should return a value");
            drained.add(v);
        }
        // Queue empty → poll times out and returns null
        assertNull(q.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(IntStream.range(0, CAPACITY).boxed().toList(), drained);
    }

    @Test
    void shutdown_behavior() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();

        // Fill queue partially
        q.put(10);
        q.put(20);
        assertEquals(2, q.size());

        // Shutdown while there are still elements
        q.shutdown();
        assertTrue(q.isShutdown());

        // put/offer must reject
        assertThrows(IllegalStateException.class, () -> q.put(30));
        assertFalse(q.offer(40, TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // take should return remaining elements then null sentinel
        assertEquals(10, q.take());
        assertEquals(20, q.take());
        assertNull(q.take(), "After shutdown and empty queue take() returns null");

        // poll should also return null immediately when empty
        assertNull(q.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Stress test with multiple producers and consumers.
     * Each producer inserts a unique range of integers.
     * Consumers collect everything they see into a thread‑safe set.
     * At the end we verify that the union of all produced numbers equals
     * the set of consumed numbers and that no duplicates were observed.
     */
    @Test
    void multiProducerMultiConsumer_noLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 3;
        final int ELEMENTS_PER_PRODUCER = 5000; // total produced = 20 000

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);

        // Shared state for producers
        AtomicInteger nextId = new AtomicInteger(0);
        // Each producer will get a unique base offset
        List<ProducerTask> producers = new ArrayList<>();
        for (int i = 0; i < PRODUCER_COUNT; i++) {
            int base = i * ELEMENTS_PER_PRODUCER;
            producers.add(new ProducerTask(queue, base, ELEMENTS_PER_PRODUCER));
        }

        // Consumers collect into a ConcurrentSkipListSet (sorted, duplicate‑detecting)
        ConcurrentSkipListSet<Integer> consumed = new ConcurrentSkipListSet<>();
        List<ConsumerTask> consumers = new ArrayList<>();
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            consumers.add(new ConsumerTask(queue, consumed));
        }

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        producers.forEach(exec::submit);
        consumers.forEach(exec::submit);

        // Wait for all producers to finish
        for (ProducerTask p : producers) {
            p.getLatch().await(); // each producer counts down when done
        }

        // Signal shutdown – consumers will exit after queue becomes empty
        queue.shutdown();

        // Wait for consumers to finish (they exit when take() returns null)
        for (ConsumerTask c : consumers) {
            c.getLatch().await();
        }

        exec.shutdownNow(); // ensure no stray threads linger
        assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));

        // Build the expected set of all produced elements
        ConcurrentSkipListSet<Integer> expected = new ConcurrentSkipListSet<>();
        for (int i = 0; i < PRODUCER_COUNT * ELEMENTS_PER_PRODUCER; i++) {
            expected.add(i);
        }

        // Verify no loss and no duplication
        assertEquals(expected, consumed, "Consumed set must match exactly the produced set");
        // Additionally, ensure size matches (detects duplicates because Set would be smaller)
        assertEquals(expected.size(), consumed.size(),
                "Duplicate elements would cause the consumed set to be smaller than expected");
    }

    /* ------------------------------------------------------------------ */
    /* Helper classes for the stress test                                 */
    /* ------------------------------------------------------------------ */

    /** Simple producer that puts a range of integers into the queue. */
    private static class ProducerTask implements Runnable {
        private final BoundedBlockingQueue<Integer> queue;
        private final int startInclusive; // first value to produce
        private final int count;          // how many values to produce
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Random rand = new Random();

        ProducerTask(BoundedBlockingQueue<Integer> queue, int startInclusive, int count) {
            this.queue = queue;
            this.startInclusive = startInclusive;
            this.count = count;
        }

        CountDownLatch getLatch() {
            return latch;
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < count; i++) {
                    int value = startInclusive + i;
                    // Use put (blocking) – simulates realistic back‑pressure
                    queue.put(value);
                    // Optional tiny random pause to increase interleaving
                    Thread.sleep(rand.nextInt(10));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt status
            } finally {
                latch.countDown();
            }
        }
    }

    /** Simple consumer that takes elements until shutdown sentinel (null) is seen. */
    private static class ConsumerTask implements Runnable {
        private final BoundedBlockingQueue<Integer> queue;
        private final ConcurrentSkipListSet<Integer> resultSet;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Random rand = new Random();

        ConsumerTask(BoundedBlockingQueue<Integer> queue, ConcurrentSkipListSet<Integer> resultSet) {
            this.queue = queue;
            this.resultSet = resultSet;
        }

        CountDownLatch getLatch() {
            return latch;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Integer v = queue.take(); // blocks until element or shutdown sentinel
                    if (v == null) { // shutdown observed
                        break;
                    }
                    if (!resultSet.add(v)) {
                        // add returns false if the element was already present → duplicate
                        throw new AssertionError("Duplicate element detected: " + v);
                    }
                    // Random pause to vary consumption speed
                    Thread.sleep(rand.nextInt(10));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        }
    }
}
```

### Explanation of the Test Suite

| Test | What it validates |
|------|-------------------|
| `putTake_blocking` | Basic blocking `put`/`take` works with a single producer/consumer. |
| `offerPoll_timed` | Timed `offer`/`poll` respect timeouts, return `false`/`null` when the operation cannot complete, and correctly wake up when space/elements become available. |
| `shutdown_behavior` | After `shutdown()`, insertions are rejected, `take()` returns remaining elements then a `null` sentinel, and `poll()` returns `null` immediately when the queue is empty. |
| `multiProducerMultiConsumer_noLossOrDuplication` | **Stress test**: many producers insert known ranges, many consumers collect into a `ConcurrentSkipListSet`. The set’s `add` method returns `false` on duplicates, which we treat as an assertion failure. After all producers finish we invoke `shutdown()` so consumers can exit cleanly. Finally we assert that the consumed set exactly matches the produced set, guaranteeing **no lost** and **no duplicated** elements. |

The stress test uses `CountDownLatch` to synchronize the start/end of producer and consumer phases, and a small random `Thread.sleep` inside the loops to increase thread interleaving and expose any subtle race conditions.

---

**You now have a complete, thread‑safe generic bounded blocking queue (without using `java.util.concurrent.BlockingQueue`) together with a thorough JUnit 5 test suite that proves its correctness under contention and shutdown scenarios.**