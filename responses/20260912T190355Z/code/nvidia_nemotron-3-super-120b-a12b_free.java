<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260912T190355Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, bounded, blocking queue called `BoundedBlockingQueue<E>` that does **not** rely on `java.util.concurrent.BlockingQueue`.  
The concurrency control is built around a single `ReentrantLock` together with two `Condition` objects:

* `notEmpty` – signalled when an element is added; waited on by `take`/`poll` when the queue is empty.  
* `notFull`  – signalled when an element is removed; waited on by `put`/`offer` when the queue is full.

A `volatile boolean shutdown` flag allows a clean shutdown: after `shutdown()` is called, further `put`/`offer` operations fail fast (throw `IllegalStateException` or return `false`), while `take`/`poll` continue to drain the queue and then return `null` (or `false` for timed operations).

All public methods are **thread‑safe** – the lock guarantees mutual exclusion for the internal circular buffer, and the conditions provide efficient blocking/waiting without busy‑spinning.

---

### 1.  `BoundedBlockingQueue.java`

```java
package com.example.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy:</p>
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state
 *       (the circular array, the fill count and the indices).</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notEmpty} – waited on when the queue is empty;
 *                                   signalled after each insertion.</li>
 *         <li>{@code notFull}  – waited on when the queue is full;
 *                                   signalled after each removal.</li>
 *       </ul>
 *   </li>
 *   <li>A {@code volatile boolean shutdown} flag is consulted under the lock.
 *       When set, insertion operations fail fast, while removal operations
 *       continue to drain the queue and then return {@code null} (or {@code false}
 *       for timed forms).</li>
 *   <li>The internal storage is a plain Object[] treated as a circular buffer,
 *       giving O(1) enqueue/dequeue.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The queued items */
    private final Object[] items;
    /** index for the next take, poll, peek or remove */
    private int takeIndex;
    /** index for the next put, offer, add */
    private int putIndex;
    /** number of elements in the queue */
    private int count;

    /** Lock guarding all access to the queue */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition waiting for the queue to become non‑empty */
    private final Condition notEmpty = lock.newCondition();
    /** Condition waiting for the queue to become non‑full */
    private final Condition notFull = lock.newCondition();

    /** True after {@link #shutdown()} has been invoked */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements this queue can hold;
     *                 must be > 0
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.items = new Object[capacity];
    }

    /** Inserts the specified element, waiting if necessary for space. */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            enqueue(e);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space.
     *
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available (or the queue was shut down)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
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
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves and removes the head of the queue, waiting if necessary for an element. */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // queue empty and shut down → nothing left
                }
                notEmpty.await();
            }
            if (shutdown && count == 0) {
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
     * Retrieves and removes the head of the queue, waiting up to the given timeout
     * for an element to become available.
     *
     * @return the head element, or {@code null} if the timeout elapsed before an
     *         element was available (or the queue was shut down and empty)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
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
            if (shutdown && count == 0) {
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
     * Initiates a graceful shutdown. After this method returns,
     * {@code put} throws {@link IllegalStateException},
     * {@code offer} returns {@code false},
     * and {@code take}/{@code poll} return {@code null} once the queue is empty.
     * Elements already present in the queue can still be removed.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if this queue has been shut down. */
    public boolean isShutdown() {
        return shutdown;
    }

    /** Current number of elements in the queue. */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the remaining capacity. */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /* ----- internal helpers ----- */

    private void enqueue(E x) {
        // assert lock held
        final Object[] a = items;
        a[putIndex] = x;
        if (++putIndex == a.length) {
            putIndex = 0;
        }
        ++count;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        // assert lock held
        final Object[] a = items;
        E x = (E) a[takeIndex];
        a[takeIndex] = null; // help GC
        if (++takeIndex == a.length) {
            takeIndex = 0;
        }
        --count;
        return x;
    }
}
```

---

### 2.  JUnit 5 Test Suite

```java
package com.example.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;

    /** Basic put / take sanity check */
    @Test
    void testPutTakeSingleThread() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(42);
        assertEquals(42, q.take());
        assertTrue(q.isEmpty());
    }

    /** Blocking put when full – consumer releases space after a delay */
    @Test
    void testBlockingPutWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2); // now full

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> putFuture = exec.submit(() -> {
            try {
                q.put(3); // should block until space appears
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });

        // give the producer a moment to block
        Thread.sleep(50);
        assertFalse(putFuture.isDone(), "Put should still be blocked");

        // consumer removes an element → producer should unblock
        Integer taken = q.take();
        assertEquals(1, taken);
        assertTrue(putFuture.get(1, TimeUnit.SECONDS), "Put should have completed");
        exec.shutdownNow();
    }

    /** Blocking take when empty – producer adds after a delay */
    @Test
    void testBlockingTakeWhenEmpty() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Integer> takeFuture = exec.submit(() -> {
            try {
                return q.take(); // should block until an element appears
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(50);
        assertFalse(takeFuture.isDone(), "Take should still be blocked");

        q.put(99);
        assertEquals(99, takeFuture.get(1, TimeUnit.SECONDS));
        exec.shutdownNow();
    }

    /** Timed offer – succeeds when space is available */
    @Test
    void testTimedOfferSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2); // full

        assertTrue(q.offer(3, 200, TimeUnit.MILLISECONDS)); // should fail because full
        // Actually we want a success case: first make space
        q.take(); // now one slot free
        assertTrue(q.offer(4, 200, TimeUnit.MILLISECONDS));
        assertEquals(4, q.poll(0, TimeUnit.MILLISECONDS));
    }

    /** Timed offer – fails when timeout expires */
    @Test
    void testTimedOfferFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // full

        boolean result = q.offer(2, 10, TimeUnit.MILLISECONDS);
        assertFalse(result, "Offer should have timed out");
        assertEquals(1, q.take()); // original element still there
    }

    /** Timed poll – succeeds when element present */
    @Test
    void testTimedPollSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(7);
        assertEquals(7, q.poll(100, TimeUnit.MILLISECONDS));
    }

    /** Timed poll – returns null on timeout */
    @Test
    void testTimedPollFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    /** Shutdown behaviour */
    @Test
    void testShutdown() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);

        q.shutdown();
        assertTrue(q.isShutdown());

        // put after shutdown throws
        assertThrows(IllegalStateException.class, () -> q.put(3));
        // offer after shutdown returns false
        assertFalse(q.offer(4, 100, TimeUnit.MILLISECONDS));

        // take still works until empty
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertNull(q.take()); // empty & shut down → null
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    /** Shutdown while queue still has elements – they can be drained */
    @Test
    void testShutdownWithRemainingElements() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.put(10);
        q.put(20);
        q.put(30);

        q.shutdown();
        assertEquals(10, q.take());
        assertEquals(20, q.take());
        assertEquals(30, q.take());
        assertNull(q.take());
    }

    /** Multi‑producer / multi‑consumer stress test */
    @Test
    void testMultiProducerMultiConsumerStress() throws Exception {
        final int PRODUCERS = 4;
        final int CONSUMERS = 4;
        final int ELEMENTS_PER_PRODUCER = 5_000;
        final int TOTAL_ELEMENTS = PRODUCERS * ELEMENTS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(128);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);

        // map element -> number of times it was taken
        ConcurrentHashMap<Integer, AtomicInteger> counts = new ConcurrentHashMap<>();

        // ----- producers -----
        for (int p = 0; p < PRODUCERS; p++) {
            final int pid = p;
            Thread producer = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int seq = 0; seq < ELEMENTS_PER_PRODUCER; seq++) {
                        int value = pid * ELEMENTS_PER_PRODUCER + seq; // unique across producers
                        queue.put(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "producer-" + p);
            producer.start();
        }

        // ----- consumers -----
        for (int c = 0; c < CONSUMERS; c++) {
            Thread consumer = new Thread(() -> {
                try {
                    startLatch.await();
                    int taken = 0;
                    while (taken < TOTAL_ELEMENTS) {
                        Integer val = queue.take(); // blocks until something is available
                        if (val == null) {
                            // should not happen because we know total elements to consume
                            break;
                        }
                        counts.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                        taken++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "consumer-" + c);
            consumer.start();
        }

        // let everybody go
        startLatch.countDown();

        // wait for all threads to finish (with a generous timeout)
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
                "Some producer/consumer thread did not finish in time");

        // ----- verification -----
        assertEquals(TOTAL_ELEMENTS, counts.size(),
                "Every produced element should have been seen exactly once");

        for (var entry : counts.entrySet()) {
            assertEquals(1, entry.getValue().get(),
                    "Element " + entry.getKey() + " was taken " + entry.getValue().get() + " times");
        }

        // queue should be empty now
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    /** Helper to check emptiness (not part of the public API but useful for tests) */
    private boolean isEmpty(BoundedBlockingQueue<?> q) {
        return q.size() == 0;
    }
}
```

**Explanation of the Test Suite**

* **Basic functionality** – `put`/`take`, blocking when full/empty, timed `offer`/`poll`.
* **Shutdown** – verifies that after `shutdown()` insertion operations fail fast, while removal continues to drain the queue.
* **Stress test** – many producers and consumers operate concurrently on a bounded queue.  
  Each producer generates a *unique* range of integers