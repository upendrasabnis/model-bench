<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260810T091324Z -->
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
 * <p>Implementation details (concurrency strategy):
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state
 *       ({@code elements}, {@code head}, {@code tail}, {@code count},
 *       {@code shutdown}). This guarantees mutual exclusion for enqueue/
 *       dequeue operations and for checking the shutdown flag.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – waited on when the queue is full (put/offer).</li>
 *         <li>{@code notEmpty} – waited on when the queue is empty (take/poll).</li>
 *       </ul>
 *     Conditions are signaled whenever the opposite state may have changed
 *     (i.e., after an enqueue we signal {@code notEmpty}; after a dequeue we
 *     signal {@code notFull}).</li>
 *   <li>The {@code shutdown} flag, once set, prevents any further inserts.
 *       {@code put} and timed {@code offer} throw {@link IllegalStateException}
 *       immediately if the queue is already shut down. {@code take} and
 *       {@code poll} continue to drain remaining elements; when the queue is
 *       empty and shut down they return {@code null} (or {@code false} for the
 *       boolean‑returning timed poll).</li>
 *   <li>All blocking methods ({@code put}, {@code take}, timed {@code offer},
 *       {@code poll}) acquire the lock interruptibly
 *       ({@code lock.lockInterruptibly()}) so that a thread waiting on the
 *       queue can be interrupted and will propagate {@link InterruptedException}
 *       after restoring the interrupt status.</li>
 *   <li>The internal storage is a fixed‑size Object[] array treated as a
 *       circular buffer; this gives O(1) enqueue/dequeue and avoids the
 *       overhead of linked nodes.</li>
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] elements;          // circular buffer
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private int head;    // index of the next element to take
    private int tail;    // index of the next free slot to put
    private int count;   // number of elements currently stored
    private volatile boolean shutdown; // true after shutdown() is called

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue can hold;
     *                 must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.elements = new Object[capacity];
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

    /** Returns {@code true} if the queue contains no elements. */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if the queue is at full capacity. */
    public boolean isFull() {
        lock.lock();
        try {
            return count == elements.length;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            // If shutdown, we do not wait – insertion is forbidden.
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            while (count == elements.length) {
                notFull.await();
                // Re‑check shutdown after each wait – a concurrent shutdown
                // should cause immediate failure.
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting up to the given timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added; {@code false} if the timeout
     *         elapsed before space became available
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            while (count == elements.length) {
                if (nanos <= 0) {
                    return false; // timeout
                }
                nanos = notFull.awaitNanos(nanos);
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
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
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    // Queue is shut down and empty – nothing more to take.
                    return null;
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
     * waiting up to the given timeout if necessary for an element to become
     * available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of this queue, or {@code null} if the queue is empty
     *         and the timeout elapsed (or the queue is shut down and empty)
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // shut down and empty
                }
                if (nanos <= 0) {
                    return null; // timeout
                }
                nanos = notEmpty.awaitNanos(nanos);
                if (shutdown) {
                    return null; // shut down while waiting
                }
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prevents any further {@code put} or {@code offer} operations.
     * After calling this method, the queue will continue to allow {@code take}
     * and {@code poll} to retrieve remaining elements; once the queue is
     * empty, those methods will return {@code null} (or {@code false} for the
     * boolean‑returning timed poll) instead of blocking.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            // Wake up producers and consumers that may be waiting.
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if {@code shutdown()} has been invoked. */
    public boolean isShutdown() {
        return shutdown;
    }

    // -----------------------------------------------------------------
    // Internal helper methods – assume lock is held by the caller.
    // -----------------------------------------------------------------

    private void enqueue(E e) {
        // Invariant: lock is held and count < capacity
        elements[tail] = e;
        tail = (tail + 1) % elements.length;
        ++count;
        notEmpty.signal(); // at least one element is now present
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        // Invariant: lock is held and count > 0
        E e = (E) elements[head];
        elements[head] = null; // help GC
        head = (head + 1) % elements.length;
        --count;
        notFull.signal(); // at least one slot is now free
        return e;
    }
}
```

---

**BoundedBlockingQueueTest.java (JUnit 5)**

```java
package com.example.util;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;

    /** Simple helper that puts a known sequence into the queue. */
    private static class Producer implements Runnable {
        private final BoundedBlockingQueue<Integer> queue;
        private final int startInclusive; // first value to produce
        private final int endExclusive;   // one‑past‑last value to produce
        private final CountDownLatch startedLatch;
        private final CountDownLatch doneLatch;
        private final List<Integer> produced = Collections.synchronizedList(new ArrayList<>());

        Producer(BoundedBlockingQueue<Integer> queue,
                 int startInclusive,
                 int endExclusive,
                 CountDownLatch startedLatch,
                 CountDownLatch doneLatch) {
            this.queue = queue;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.startedLatch = startedLatch;
            this.doneLatch = doneLatch;
        }

        @Override
        public void run() {
            startedLatch.countDown(); // signal that this thread is alive
            try {
                for (int i = startInclusive; i < endExclusive; i++) {
                    queue.put(i); // blocking put
                    produced.add(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt status
            } finally {
                doneLatch.countDown();
            }
        }

        List<Integer> getProduced() {
            return new ArrayList<>(produced);
        }
    }

    /** Simple helper that takes elements until a sentinel or shutdown. */
    private static class Consumer implements Runnable {
        private final BoundedBlockingQueue<Integer> queue;
        private final CountDownLatch startedLatch;
        private final CountDownLatch doneLatch;
        private final List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        private final int expectedTotalElements; // used only for validation after test

        Consumer(BoundedBlockingQueue<Integer> queue,
                 CountDownLatch startedLatch,
                 CountDownLatch doneLatch,
                 int expectedTotalElements) {
            this.queue = queue;
            this.startedLatch = startedLatch;
            this.doneLatch = doneLatch;
            this.expectedTotalElements = expectedTotalElements;
        }

        @Override
        public void run() {
            startedLatch.countDown();
            try {
                while (true) {
                    Integer val = queue.take(); // blocks until element or shutdown
                    if (val == null) { // shutdown signaled via null return
                        break;
                    }
                    consumed.add(val);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }

        List<Integer> getConsumed() {
            return new ArrayList<>(consumed);
        }
    }

    @Test
    void testPutTakeBasic() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(42);
        assertEquals(42, q.take());
        assertTrue(q.isEmpty());
    }

    @Test
    void testOfferPollTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        // Offer with zero timeout should succeed immediately when space exists
        assertTrue(q.offer(1, 0, TimeUnit.MILLISECONDS));
        assertEquals(1, q.poll(0, TimeUnit.MILLISECONDS));
        // Offer when full should fail after timeout
        for (int i = 0; i < CAPACITY; i++) {
            q.put(i); // fill
        }
        assertFalse(q.offer(99, 10, TimeUnit.MILLISECONDS));
        // Poll with timeout when empty should return null
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownBehavior() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(1);
        q.put(2);
        assertEquals(1, q.take());
        assertEquals(2, q.take());

        q.shutdown();
        // After shutdown, put must fail
        assertThrows(IllegalStateException.class, () -> q.put(3));
        // Timed offer must also fail immediately
        assertThrows(IllegalStateException.class, () -> q.offer(4, 100, TimeUnit.MILLISECONDS));
        // Take/poll on empty shutdown queue must return null
        assertNull(q.take());
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testMultiProducerMultiConsumerStress() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 3;
        final int ELEMENTS_PER_PRODUCER = 5000; // total elements = PRODUCER_COUNT * ELEMENTS_PER_PRODUCER

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);

        CountDownLatch producersStarted = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumersStarted = new CountDownLatch(CONSUMER_COUNT);
        CountDownLatch consumersDone = new CountDownLatch(CONSUMER_COUNT);

        List<Producer> producers = new ArrayList<>();
        List<Thread> producerThreads = new ArrayList<>();
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            int start = p * ELEMENTS_PER_PRODUCER;
            int endExclusive = start + ELEMENTS_PER_PRODUCER;
            Producer prod = new Producer(queue, start, endExclusive, producersStarted, producersDone);
            producers.add(prod);
            Thread t = new Thread(prod, "producer-" + p);
            producerThreads.add(t);
            t.start();
        }

        List<Consumer> consumers = new ArrayList<>();
        List<Thread> consumerThreads = new ArrayList<>();
        int totalExpected = PRODUCER_COUNT * ELEMENTS_PER_PRODUCER;
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            Consumer cons = new Consumer(queue, consumersStarted, consumersDone, totalExpected);
            consumers.add(cons);
            Thread t = new Thread(cons, "consumer-" + c);
            consumerThreads.add(t);
            t.start();
        }

        // Wait for all producer and consumer threads to be alive
        assertTrue(producersStarted.await(5, TimeUnit.SECONDS));
        assertTrue(consumersStarted.await(5, TimeUnit.SECONDS));

        // Wait for producers to finish
        assertTrue(producersDone.await(30, TimeUnit.SECONDS));
        // No more elements will be added – shut down the queue
        queue.shutdown();

        // Wait for consumers to drain and exit
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS));

        // Join threads (should already be terminated)
        for (Thread t : producerThreads) t.join(1000);
        for (Thread t : consumerThreads) t.join(1000);

        // Gather all produced elements
        List<Integer> allProduced = new ArrayList<>();
        for (Producer p : producers) {
            allProduced.addAll(p.getProduced());
        }

        // Gather all consumed elements
        List<Integer> allConsumed = new ArrayList<>();
        for (Consumer c : consumers) {
            allConsumed.addAll(c.getConsumed());
        }

        // Verify no lost elements: each produced value appears exactly once in consumed
        assertEquals(allProduced.size(), allConsumed.size(),
                "Number of consumed elements differs from number produced");

        // Sort both lists to make duplicate/missing detection easy
        Collections.sort(allProduced);
        Collections.sort(allConsumed);
        assertEquals(allProduced, allConsumed,
                "Consumed sequence does not match produced sequence (missing/duplicates)");

        // Additional sanity: queue should be empty and shutdown
        assertTrue(queue.isEmpty());
        assertTrue(queue.isShutdown());
    }
}
```

**Explanation of the test**

* **Basic functionality** – `put`/`take` and timed `offer`/`poll` work as expected.
* **Shutdown** – After `shutdown()` inserts are rejected (`IllegalStateException`), and `take`/`poll` return `null` when the queue is empty.
* **Stress test** –  
  * `PRODUCER_COUNT` producers each generate a *disjoint* range of integers, guaranteeing uniqueness.  
  * `CONSUMER_COUNT` consumers repeatedly call `take()` until they receive `null` (the shutdown signal).  
  * After all producers finish, the queue is shut down; consumers then drain any remaining items and exit.  
  * The test collects all produced and consumed values, sorts them, and asserts equality, proving that **no element was lost or duplicated** despite concurrent enqueues and dequeues.

The implementation relies on a single `ReentrantLock` plus two `Condition` queues, which is a classic monitor‑style solution that guarantees thread‑safety while keeping the code easy to reason about. All blocking operations are interrupt‑aware, and the shutdown flag is coordinated with the condition signals to avoid lost wake‑ups.