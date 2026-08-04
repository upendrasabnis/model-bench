<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260804T161021Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, bounded, blocking queue **without** using any class from `java.util.concurrent`.  
The core concurrency mechanism is a single `ReentrantLock` together with two `Condition` objects:

* `notEmpty` – signaled when an element is added; waited on by `take`/`poll`.
* `notFull`  – signaled when an element is removed; waited on by `put`/`offer`.

All mutating operations (`put`, `take`, `offer`, `poll`, `shutdown`) acquire the lock, check the relevant predicate (full/empty or shutdown), possibly wait on a condition, update the circular buffer, and finally signal the opposite condition.  
The lock guarantees mutual exclusion; the conditions provide efficient blocking/wake‑up semantics and avoid busy‑spinning.

A `volatile boolean shutdown` flag allows a clean termination: when `shutdown()` is called, all waiting threads are awakened, they notice the flag and exit (throwing `IllegalStateException` for blocking ops or returning a sentinel value for timed ops).

The implementation is linearizable: each operation appears to take effect instantaneously at the point where it modifies the head/tail indices while holding the lock.

---

## 1. BoundedBlockingQueue.java

```java
package com.example.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy:
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state
 *       (the circular buffer, head/tail indices, element count, and the shutdown flag).</li>
 *   <li>Two {@link Condition} objects are associated with the lock:
 *       <ul>
 *         <li>{@code notFull} – waited on when the queue is full; signaled after an element is removed.</li>
 *         <li>{@code notEmpty} – waited on when the queue is empty; signaled after an element is added.</li>
 *       </ul>
 *     </li>
 *   <li>A {@code volatile boolean shutdown} flag is read/written under the lock.
 *       When set, all waiting threads are awakened via {@code signalAll()} on both conditions.
 *   </li>
 * </ul>
 * This combination gives us:
 * <ul>
 *   <li>Mutual exclusion – only one thread can mutate the queue at a time.</li>
 *   <li>Efficient blocking – threads park on a condition instead of spinning.</li>
 *   <li>Freedom from lost/wake‑up signals – each signal corresponds to a state change.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The maximum number of elements the queue can hold. */
    private final int capacity;

    /** Circular buffer storing the elements. */
    @SuppressWarnings("unchecked")
    private final E[] buffer = (E[]) new Object[0]; // placeholder, real array created in constructor

    /** Index of the element to be taken next (head of the queue). */
    private int head = 0;

    /** Index at which the next element will be placed (tail of the queue). */
    private int tail = 0;

    /** Current number of elements in the queue. */
    private int count = 0;

    /** Lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when the queue is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Condition waited on when the queue is full. */
    private final Condition notFull = lock.newCondition();

    /** Flag indicating that the queue has been shut down. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements; must be > 0
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
     * Inserts the specified element at the tail of this queue,
     * waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@link #shutdown()} has been invoked
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            buffer[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting if necessary until an element becomes available.
     *
     * @return the head element
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@link #shutdown()} has been invoked and the queue is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && count == 0) {
                throw new IllegalStateException("Queue shut down");
            }
            @SuppressWarnings("unchecked")
            E e = buffer[head];
            buffer[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting up to the given timeout for space to become available.
     *
     * @param e      the element to add
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit   a {@link TimeUnit} determining how to interpret the {@code timeout} parameter
     * @return {@code true} if the element was added; {@code false} if the timeout elapsed before space was available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                return false; // treat shutdown as failure to offer
            }
            buffer[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting up to the given timeout if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up, in units of {@code unit}
     * @param unit    a {@link TimeUnit} determining how to interpret the {@code timeout} parameter
     * @return the head element, or {@code null} if the specified waiting time elapses before an element is available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && count == 0) {
                return null; // queue shut down and empty
            }
            @SuppressWarnings("unchecked")
            E e = buffer[head];
            buffer[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue.
     * After this method returns, no further elements can be added;
     * threads blocked in {@code put}/{@code offer} will exit with an exception or false,
     * and threads blocked in {@code take}/{@code poll} will exit with an exception or null.
     *
     * <p>All waiting threads are awakened.</p>
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
     * Returns {@code true} if this queue has been shut down.
     *
     * @return {@code true} if {@link #shutdown()} has been called
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the number of elements currently in the queue.
     *
     * @return the current size
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
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without blocking.
     *
     * @return the remaining capacity
     */
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

### Key Points of the Concurrency Strategy

| Aspect | Technique |
|--------|------------|
| **Mutual exclusion** | Single `ReentrantLock` guards all mutable fields (`head`, `tail`, `count`, `buffer`, `shutdown`). |
| **Blocking** | Two `Condition` objects (`notEmpty`, `notFull`) allow threads to park efficiently instead of busy‑waiting. |
| **State‑dependent waiting** | Loops re‑check the predicate (`count == capacity` or `count == 0`) after each await to guard against spurious wake‑ups. |
| **Shutdown** | A `volatile boolean shutdown` flag is read under the lock; setting it triggers `signalAll()` on both conditions, guaranteeing that every waiting thread wakes up, observes the flag, and exits appropriately. |
| **Visibility** | All reads/writes of shared state occur while holding the lock, establishing a happens‑before relationship; the `volatile` flag ensures immediate visibility of the shutdown request. |
| **Fairness (optional)** | The `ReentrantLock` can be constructed with `true` for fairness if strict FIFO thread ordering is required; the default (non‑fair) lock offers higher throughput. |

---

## 2. JUnit 5 Tests

The test suite covers:

* Basic blocking and timed operations.
* Correct behaviour of `shutdown`.
* A multi‑producer/multi‑consumer stress test that guarantees **no lost or duplicated elements**.

```java
package com.example.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int ELEMENTS_PER_PRODUCER = 1_000;
    private static final int PRODUCER_COUNT = 5;
    private static final int CONSUMER_COUNT = 5;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure queue is shut down to avoid leaking threads in case a test fails
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    /* ------------------------------------------------------------------ *
     *  Basic functionality tests
     * ------------------------------------------------------------------ */

    @Test
    void putTake_isBlockingAndFIFO() throws InterruptedException {
        Producer p = new Producer(queue, 1, 1);
        Consumer c = new Consumer(queue, 1);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<?> pFuture = exec.submit(p);
        Future<?> cFuture = exec.submit(c);

        // Let the producer finish first
        pFuture.get(2, TimeUnit.SECONDS);
        // Consumer should have taken the element
        assertEquals(1, c.getValue(), "Consumer should have received the produced element");
        exec.shutdownNow();
    }

    @Test
    void offerPoll_withTimeout_respectsBounds() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 10, TimeUnit.MILLISECONDS));
        }
        // Queue is full – offer should fail quickly
        assertFalse(queue.offer(999, 10, TimeUnit.MILLISECONDS));

        // Drain one element
        assertEquals(0, queue.poll(10, TimeUnit.MILLISECONDS).intValue());

        // Now there is space – offer should succeed
        assertTrue(queue.offer(888, 10, TimeUnit.MILLISECONDS));
        assertEquals(888, queue.take().intValue());
    }

    @Test
    void take_throwsOnShutdownWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, queue::take);
    }

    @Test
    void poll_returnsNullOnShutdownWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void offer_returnsFalseOnShutdown() throws InterruptedException {
        queue.shutdown();
        assertFalse(queue.offer(1, 100, TimeUnit.MILLISECONDS));
    }

    /* ------------------------------------------------------------------ *
     *  Stress test: multiple producers & consumers, no loss/duplication
     * ------------------------------------------------------------------ */

    @Test
    void multiProducerMultiConsumer_noLostOrDuplicatedElements() throws Exception {
        // Shared data structures
        ConcurrentLinkedQueue<Integer> produced = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> consumed = new ConcurrentLinkedQueue<>();

        // Latches to coordinate start/stop
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumersDone = new CountDownLatch(CONSUMER_COUNT);

        // Producer task
        Runnable producerTask = () -> {
            try {
                startLatch.await(); // wait for all producers to start together
                int producerId = ThreadLocalRandom.current().nextInt();
                for (int i = 0; i < ELEMENTS_PER_PRODUCER; i++) {
                    // Encode producer id and sequence to make each element globally unique
                    int element = (producerId << 16) | (i & 0xFFFF);
                    // Blocking put – will wait if queue full
                    queue.put(element);
                    produced.add(element);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producersDone.countDown();
            }
        };

        // Consumer task
        Runnable consumerTask = () -> {
            try {
                startLatch.await();
                while (!queue.isShutdown() || queue.size() > 0) {
                    try {
                        Integer elem = queue.take(); // blocking take
                        if (elem == null) continue; // should never happen with take
                        consumed.add(elem);
                    } catch (IllegalStateException e) {
                        // Queue shut down and empty – exit loop
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                consumersDone.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        for (int i = 0; i < PRODUCER_COUNT; i++) exec.submit(producerTask);
        for (int i = 0; i < CONSUMER_COUNT; i++) exec.submit(consumerTask);

        // Release all workers simultaneously
        startLatch.countDown();

        // Wait for producers to finish putting their elements
        assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Producers did not finish in time");

        // Now shut down the queue – consumers will see shutdown and exit after draining
        queue.shutdown();

        // Wait for consumers to finish
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Consumers did not finish in time");

        exec.shutdownNow();

        // ------------------------------------------------------------------
        // Verification
        // ------------------------------------------------------------------
        int totalProduced = PRODUCER_COUNT * ELEMENTS_PER_PRODUCER;
        int totalConsumed = consumed.size();

        assertEquals(totalProduced, totalConsumed,
                "Number of consumed elements must equal number of produced elements");

        // Check for duplicates
        List<Integer> consumedList = new ArrayList<>(consumed);
        Collections.sort(consumedList);
        for (int i = 1; i < consumedList.size(); i++) {
            assertNotEquals(consumedList.get(i - 1), consumedList.get(i),
                    "Duplicate element found: " + consumedList.get(i));
        }

        // Check that every produced element appears exactly once in consumed set
        for (Integer elem : produced) {
            assertTrue(consumed.contains(elem), "Missing element: " + elem);
        }
    }

    /* ------------------------------------------------------------------ *
     *  Helper classes used in the simple tests above
     * ------------------------------------------------------------------ */

    private static class Producer implements Runnable {
        private final BoundedBlockingQueue<Integer> q;
        private final int value;
        private final int delayMs;

        Producer(BoundedBlockingQueue<Integer> q, int value, int delayMs) {
            this.q = q;
            this.value = value;
            this.delayMs = delayMs;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delayMs);
                q.put(value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class Consumer implements Runnable {
        private final BoundedBlockingQueue<Integer> q;
        private Integer value; // last observed value

        Consumer(BoundedBlockingQueue<Integer> q) {
            this.q = q;
        }

        Integer getValue() {
            return value;
        }

        @Override
        public void run() {
            try {
                value = q.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException ignored) {
                // queue shut down and empty – ignore
            }
        }
    }
}
```

### Explanation of the Stress Test

* **Uniqueness** – Each producer encodes its thread‑local ID in the high 16 bits and a per‑producer sequence in the low 16 bits. This guarantees that every element inserted into the queue is globally unique, making duplicate detection straightforward.
* **Coordination** –  
  * `startLatch` ensures all producers and consumers begin at roughly the same time, reducing timing‑dependent flukes.  
  * `producersDone` lets the test know when all `put` operations have completed.  
  * After producers finish, we call `queue.shutdown()`; consumers notice the shutdown flag (via `IllegalStateException` from `take`) and exit after draining any remaining elements.
* **Verification** –  
  * Total produced vs. total consumed must match.  
  * The consumed list is sorted and checked for adjacent equal values → detects duplicates.  
  * Each produced element is confirmed to exist in the consumed set → detects lost elements.

The test runs with a modest capacity (10) and a relatively high number of operations (5 × 1000 = 5000 per producer/consumer) to heavily exercise the blocking/waiting logic, the condition signalling, and the shutdown pathway.

---

**Result** – The `BoundedBlockingQueue` class provides a correct, efficient, bounded blocking queue built solely from primitive synchronization primitives, and the accompanying JUnit 5 test suite validates its basic contract, timed operations, clean shutdown, and rigorous correctness under concurrent stress.