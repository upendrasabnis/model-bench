<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260717T120115Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue that does not rely on
 * {@link java.util.concurrent.BlockingQueue}.  It supports:
 * <ul>
 *   <li>blocking {@code put(E)} and {@code take()}</li>
 *   <li>timed {@code offer(E,timeout,unit)} and {@code poll(timeout,unit)}</li>
 *   <li>clean shutdown – after gearing down, all waiting threads are released
 *   and further operations throw {@link IllegalStateException}</li>
 * </ul>
 *
 * <h3>Concurrency strategy</h3>
 *
 * <p>All mutating operations are guarded by a single {@link ReentrantLock}.  Two
 * {@link Condition} objects are used to coordinate producers and consumers:
 * <ul>
 *   <li>{@code notFull} – signaled when space becomes available.</li>
 *   <li>{@code notEmpty} – signaled when an item is inserted.</li>
 * </ul>
 *
 * <p>The queue is implemented as a circular buffer backed by an {@code Object[]}
 * array.  Two indices, {@code head} (next item to take) and {@code tail}
 * (next slot to insert), together with a {@code count} field, maintain the
 * queue state.  All state changes happen while holding the lock, guaranteeing
 * mutual exclusion and visibility without additional synchronization.</p>
 *
 * <p>Shutdown is performed by setting a {@code volatile boolean shutdown} flag
 * while holding the lock, then signalling both conditions.  All queued
 * operations check this flag and throw {@code IllegalStateException} once the
 * queue is shut down.</p>
 *
 * @param <E> the type of elements held in this queue
 */
public final class BoundedBlockingQueue<E> {

    private final Object[] items;      // circular buffer
    private int head = 0;              // index of next element to take
    private int tail = 0;              // index of next free slot to put
    private int count = 0;             // number of elements in the queue

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Creates a queue with the specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.items = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException   if interrupted while waiting
     * @throws IllegalStateException  if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            while (count == items.length && !shutdown.get()) {
                notFull.await();
            }
            if (shutdown.get()) {
                throw new IllegalStateException("Queue has been shut down");
            }
            enqueue(e);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue if possible, waiting up
     * to the specified wait time if necessary for space to become available.
     *
     * @param e       the element to add
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added to this queue, else {@code false}
     * @throws InterruptedException   if interrupted while waiting
     * @throws IllegalStateException  if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length && !shutdown.get()) {
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown.get()) {
                throw new IllegalStateException("Queue has been shut down");
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
     *
     * @return the head of the queue
     * @throws InterruptedException   if interrupted while waiting
     * @throws IllegalStateException  if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown.get()) {
                notEmpty.await();
            }
            if (shutdown.get() && count == 0) {
                throw new IllegalStateException("Queue has been shut down");
            }
            E e = (E) dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return the head of this queue, or {@code null} if the specified waiting
     *         time elapses before an element is available
     * @throws InterruptedException   if interrupted while waiting
     * @throws IllegalStateException  if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown.get()) {
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown.get() && count == 0) {
                throw new IllegalStateException("Queue has been shut down");
            }
            E e = (E) dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts the queue down.  All waiting threads are released.  Subsequent
     * {@code put} or {@code take} operations will throw {@code
     * IllegalStateException}.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown.compareAndSet(false, true)) {
                notEmpty.signalAll();
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if the queue has been shut down.
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /* ----------------------------------------------------------------- */
    /* Internal helper methods – called only while holding {@code lock}   */
    /* ----------------------------------------------------------------- */

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % items.length;
        count++;
    }

    private E dequeue() {
        @SuppressWarnings("unchecked")
        E e = (E) items[head];
        items[head] = null; // help GC
        head = (head + 1) % items.length;
        count--;
        return e;
    }

    /* ----------------------------------------------------------------- */
    /* Optional debugging helpers                                         */
    /* ----------------------------------------------------------------- */

    @Override
    public String toString() {
        lock.lock();
        try {
            return "BoundedBlockingQueue{size=" + count + ", capacity=" + items.length + '}';
        } finally {
            lock.unlock();
        }
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundedBlockingQueueTest {

    private static final int QUEUE_CAPACITY = 10;
    private static final int PRODUCERS = 4;
    private static final int CONSUMERS = 4;
    private static final int ITEMS_PER_PRODUCER = 200_000; // 800k total

    @Test
    void testSingleThreadPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        q.put(1);
        int v = q.take();
        assertEquals(1, v);
    }

    @Test
    void testBlockingPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future<?> f = es.submit(() -> {
            try {
                q.put(2); // should block until take()
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        Thread.sleep(100); // ensure the נא thread is blocked
        assertFalse(f.isDone());
        int v = q.take(); // this frees a slot
        f.get(1, TimeUnit.SECONDS); // should finish
        assertEquals(2, q.take());
        es.shutdownNow();
    }

    @Test
    void testTimedOffer() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer(1, 1, TimeUnit.SECONDS));
        assertFalse(q.offer(2, 1, TimeUnit.SECONDS)); // queue full
        int v = q.take();
        assertEquals(1, v);
    }

    @Test
    void testTimedPoll() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertNull(q.poll(1, TimeUnit.SECONDS)); // empty
        q.put(1);
        assertEquals(1, q.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.shutdown();

        assertTrue(q.isShutdown());

        assertThrows(IllegalStateException.class, () -> q.put(1));
        assertThrows(IllegalStateException.class, () -> q.take());
        assertThrows(IllegalStateException.class, () -> q.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> q.poll(1, TimeUnit.SECONDS));

        // Waiters should be released
        ExecutorService es = Executors.newCachedThreadPool();
        Future<?> putFuture = es.submit(() -> {
            try {
                q.put(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        Future<?> takeFuture = es.submit(() -> {
            try {
                q.take();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        // The futures should finish quickly with IllegalStateException inside
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            putFuture.get();
            takeFuture.get();
        });

        es.shutdownNow();
    }

    @Test
    void multiProducerMultiConsumerStressTest() throws InterruptedException {
        final int queueSize = 1000;
        final BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(queueSize);

        ExecutorService pool = Executors.newFixedThreadPool(PRODUCERS + CONSUMERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);

        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        ConcurrentHashMap<Integer, Integer> consumptionCount = new ConcurrentHashMap<>();

        // producers
        for (int p = 0; p < PRODUCERS; p++) {
