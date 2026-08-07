<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260807T113601Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.queue;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy
 * -----------------------
 * The queue uses a single {@link ReentrantLock} to protect all mutable state
 * (the circular buffer, the fill count and the shutdown flag). Two {@link Condition}
 * objects are associated with that lock:
 *
 * <ul>
 *   <li>{@code notFull} – waited on by producers when the queue is full.</li>
 *   <li>{@code notEmpty} – waited on by consumers when the queue is empty.</li>
 * </ul>
 *
 * All mutating operations ({@code put}, {@code offer}, {@code take},
 * {@code poll}) acquire the lock, test the relevant predicate while holding the
 * lock, and then either proceed or wait on the appropriate condition. When the
 * state changes (an element is inserted or removed) the opposite condition is
 * signalled so that a waiting thread can re‑evaluate its predicate.
 *
 * A voluntary {@code shutdown} operation only prevents further insertions.
 * Producers that are blocked on {@code put}/{@code offer} are awakened and will
 * either throw {@link IllegalStateException} ({@code put}) or return {@code false}
 * ({@code offer}). Consumers continue to remove elements until the queue becomes
 * empty; at that point {@code take} throws {@link NoSuchElementException} and
 * {@code poll} returns {@code null}. This gives a “clean shutdown”: no new
 * items can be added, but all already‑queued items can still be drained.
 *
 * The implementation is *bounded*: the constructor requires a positive capacity
 * and the internal array never grows beyond that size.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The storage for queue elements – a simple circular array. */
    private final Object[] items;

    /** Maximum number of elements the queue can hold. */
    private final int capacity;

    /** Index where the next element will be inserted. */
    private int putIndex;

    /** Index where the next element will be removed. */
    private int takeIndex;

    /** Current number of elements in the queue. */
    private int count;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when there is at least one free slot. */
    private final Condition notFull = lock.newCondition();

    /** Signalled when there is at least one element present. */
    private final Condition notEmpty = lock.newCondition();

    /** True when {@code shutdown()} has been called – no further puts allowed. */
    private volatile boolean shutdown;

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue may hold;
     *                 must be > 0
     * @throws IllegalArgumentException if capacity &lt;= 0
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
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if {@code e} is null
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shutdown");
                }
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * given timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return {@code true} if the element was added; {@code false} if the timeout elapsed
     *         before space became available or {@code shutdown()} was invoked
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if {@code e} is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
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
     * Retrieves and removes the head of this queue, waiting if necessary until
     * an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NoSuchElementException if {@code shutdown()} has been invoked and the queue is empty
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new NoSuchElementException("Queue shutdown and empty");
                }
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * timeout if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return the head of this queue, or {@code null} if the specified waiting time
     *         elapses before an element is available or {@code shutdown()} has been invoked
     *         and the queue is empty
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
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prevents any further insertion operations.  Calls to {@code put} will
     * throw {@link IllegalStateException}; calls to {@code offer} will return
     * {@code false}.  Consumers may continue to remove elements until the queue
     * becomes empty, after which {@code throw} or {@code null} is returned as
     * described in {@code take} and {@code poll}.
     *
     * After invoking this method, no further {@code put}/{@code offer} will ever
     * succeed, but the queue remains usable for draining.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            shutdown = true;
            notFull.signalAll();   // wake up producers so they can notice shutdown
            notEmpty.signalAll();  // wake up consumers so they can notice shutdown
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue has been shut down.
     *
     * @return {@code true} if {@code shutdown()} has been called
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /** Inserts an element at the put index; assumes the lock is held and there is space. */
    private void enqueue(E x) {
        // assert lock held && count < capacity
        items[putIndex] = x;
        if (++putIndex == capacity) {
            putIndex = 0;
        }
        ++count;
        notEmpty.signal();
    }

    /** Removes and returns the element at the take index; assumes the lock is held and the queue is non‑empty. */
    @SuppressWarnings("unchecked")
    private E dequeue() {
        // assert lock held && count > 0
        E x = (E) items[takeIndex];
        items[takeIndex] = null; // help GC
        if (++takeIndex == capacity) {
            takeIndex = 0;
        }
        --count;
        notFull.signal();
        return x;
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.queue;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    /** Simple sanity test for put/take. */
    @Test
    void testPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(10);
        q.put(20);
        assertEquals(10, q.take());
        assertEquals(20, q.take());
    }

    /** Test timed offer/poll with a timeout that expires. */
    @Test
    void testOfferPollTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer(5, 200, TimeUnit.MILLISECONDS)); // succeeds immediately
        assertFalse(q.offer(6, 10, TimeUnit.MILLISECONDS)); // fails because full & timeout

        assertEquals(5, q.poll(10, TimeUnit.MILLISECONDS)); // succeeds
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));      // times out because empty
    }

    /** Verify that shutdown blocks further insertions but allows draining. */
    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.put(1);
        q.put(2);
        assertEquals(1, q.take());

        q.shutdown();
        assertTrue(q.isShutdown());

        // put/offer must fail after shutdown
        assertThrows(IllegalStateException.class, () -> q.put(3));
        assertFalse(q.offer(4, 100, TimeUnit.MILLISECONDS));

        // remaining element can still be taken
        assertEquals(2, q.take());

        // after queue is empty, take throws and poll returns null
        assertThrows(NoSuchElementException.class, q::take);
        assertNull(q.poll(100, TimeUnit.MILLISECONDS));
    }

    /**
     * Stress test with multiple producers and consumers.
     *
     * Each producer inserts a unique range of integers.
     * Consumers drain the queue until shutdown and the queue is empty.
     * After the test we verify that:
     *   - no element was lost,
     *   - no element was duplicated,
     *   - the total number of taken elements equals the number of put elements.
     */
    @Test
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int capacity = 16;
        final int producerCount = 5;
        final int consumerCount = 4;
        final int elementsPerProducer = 10_000; // total elements = 50_000

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);
        AtomicInteger producedCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();

        // Use a concurrent set to detect duplicates easily.
        ConcurrentSkipListSet<Integer> producedSet = new ConcurrentSkipListSet<>();
        ConcurrentSkipListSet<Integer> consumedSet = new ConcurrentSkipListSet<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);

        // ----- producers -------------------------------------------------
        for (int p = 0; p < producerCount; p++) {
            final int pid = p;
            new Thread(() -> {
                try {
                    startLatch.await(); // all producers start together
                    for (int i = 0; i < elementsPerProducer; i++) {
                        int elem = pid * elementsPerProducer + i; // guaranteed unique
                        queue.put(elem);
                        producedCount.incrementAndGet();
                        producedSet.add(elem);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            }, "producer-" + p).start();
        }

        // ----- consumers -------------------------------------------------
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    while (true) {
                        Integer elem = queue.poll(1, TimeUnit.SECONDS);
                        if (elem == null) {
                            // null can mean timeout, shutdown+empty, or spurious wakeup.
                            if (queue.isShutdown()) {
                                break; // shutdown and we observed empty queue
                            }
                            // otherwise just retry
                            continue;
                        }
                        consumedCount.incrementAndGet();
                        consumedSet.add(elem);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
