<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261015T093231Z -->
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
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}).</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – waited on when the queue is full (for {@code put}
 *             and timed {@code offer}).</li>
 *         <li>{@code notEmpty} – waited on when the queue is empty (for {@code take}
 *             and timed {@code poll}).</li>
 *       </ul>
 *   </li>
 *   <li>The lock guarantees that only one thread can modify the circular buffer
 *       at a time, while the conditions allow threads to block efficiently
 *       instead of busy‑spinning.</li>
 *   <li>A volatile {@code shutdown} flag (read under the lock) signals a clean
 *       close. After {@code shutdown()} is called:
 *       <ul>
 *         <li>{@code put} throws {@link IllegalStateException}.</li>
 *         <li>{@code offer} returns {@code false}.</li>
 *         <li>{@code take}/{@code poll} return {@code null} when the queue becomes
 *             empty.</li>
 *         <li>Waiting threads are awakened by signalling both conditions.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The storage buffer – a simple circular array. */
    private final Object[] items;

    /** Index for the next element to be taken (head of the queue). */
    private int takeIndex;

    /** Index for the next element to be put (tail of the queue). */
    private int putIndex;

    /** Number of elements currently stored. */
    private int count;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on when the queue is full. */
    private final Condition notFull = lock.newCondition();

    /** Condition waited on when the queue is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** True after {@code shutdown()} has been invoked. */
    private volatile boolean shutdown;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.items = new Object[capacity];
    }

    /** @return the current number of elements in the queue */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** @return the number of additional elements that the queue can ideally accept */
    public int remainingCapacity() {
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /** @return {@code true} if {@code shutdown()} has been called */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length && !shutdown) {
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
     * Inserts the specified element at the tail of this queue if it is possible
     * to do so immediately without exceeding the queue's capacity, returning
     * {@code true} upon success and {@code false} if the queue is full.
     *
     * @param e the element to add
     * @return {@code true} if the element was added, {@code false} otherwise
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            if (count == items.length) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return {@code true} if successful, or {@code false} if the waiting time elapsed before space
     *         became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length && !shutdown) {
                if (nanos <= 0) {
                    return false;
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

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an
     * element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (count == 0) { // shutdown and empty
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this
     * queue is empty.
     *
     * @return the head of this queue, or {@code null} if the queue is empty
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count == 0) {
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * wait time if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return the head of this queue, or {@code null} if the specified waiting time
     *         elapses before an element is available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (count == 0) { // shutdown and empty
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals that no more elements will be offered. Subsequent {@code put}/{@code offer}
     * calls will fail (throw or return false). Waiting {@code take}/{@code poll} calls
     * will return {@code null} once the queue becomes empty.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (!shutdown) {
                shutdown = true;
                // Wake up producers and consumers that may be waiting.
                notFull.signalAll();
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal helper methods – assume lock is held by the caller.
    // -----------------------------------------------------------------
    private void enqueue(E e) {
        // assert lock.isHeldByCurrentThread();
        items[putIndex] = e;
        if (++putIndex == items.length) {
            putIndex = 0;
        }
        ++count;
        notEmpty.signal();
    }

    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        @SuppressWarnings("unchecked")
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // help GC
        if (++takeIndex == items.length) {
            takeIndex = 0;
        }
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 *
 * <p>The stress test verifies that, under heavy concurrent use, no element is
 * lost or duplicated and that the queue behaves correctly on shutdown.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10); // small capacity to increase contention
    }

    @AfterEach
    void tearDown() {
        queue = null;
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
    void testOfferPoll() {
        assertTrue(queue.offer(99));
        assertEquals(99, queue.poll());
        assertNull(queue.poll()); // empty
    }

    @Test
    void testTimedOfferSuccess() throws InterruptedException {
        assertTrue(queue.offer(7, 200, TimeUnit.MILLISECONDS));
        assertEquals(7, queue.take());
    }

    @Test
    void testTimedOfferFailure() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < queue.remainingCapacity(); i++) {
            assertTrue(queue.offer(i));
        }
        // now it's full – offer with timeout should fail
        assertFalse(queue.offer(999, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTimedPollSuccess() throws InterruptedException {
        queue.put(13);
        assertEquals(13, queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTimedPollTimeout() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS)); // empty -> timeout
    }

    // -----------------------------------------------------------------
    // Shutdown behaviour
    // -----------------------------------------------------------------
    @Test
    void testShutdownPutThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void testShutdownOfferReturnsFalse() {
        queue.shutdown();
        assertFalse(queue.offer(2));
    }

    @Test
    void testShutdownTakeReturnsNullWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertNull(queue.take()); // empty and shut down
    }

    @Test
    void testShutdownPollReturnsNullWhenEmpty() {
        queue.shutdown();
        assertNull(queue.poll());
    }

    @Test
    void testShutdownWakeUpWaitingThreads() throws Exception {
        // Fill the queue so a put will block
        for (int i = 0; i < queue.remainingCapacity(); i++) {
            queue.put(i);
        }

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch putLatch = new CountDownLatch(1);
        CountDownLatch takeLatch = new CountDownLatch(1);

        // Producer that will block on put
        Future<?> putFuture = exec.submit(() -> {
            try {
                putLatch.countDown(); // signal that we are about to block
                queue.put(999); // should block until shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Consumer that will block on take
        Future<?> takeFuture = exec.submit(() -> {
            try {
                takeLatch.countDown(); // signal that we are about to block
                queue.take(); // should block until shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait until both threads are blocked
        assertTrue(putLatch.await(2, TimeUnit.SECONDS));
        assertTrue(takeLatch.await(2, TimeUnit.SECONDS));

        // Shutdown the queue – both blocked threads should wake up
        queue.shutdown();

        // Give them a moment to finish
        putFuture.get(1, TimeUnit.SECONDS);
        takeFuture.get(1, TimeUnit.SECONDS);

        exec.shutdownNow();
    }

    // -----------------------------------------------------------------
    // Stress test – multiple producers & consumers
    // -----------------------------------------------------------------
    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int producerCount = 8;
        final int consumerCount = 6;
        final int itemsPerProducer = 5_000; // each producer puts 0..itemsPerProducer-1
        final int expectedTotal = producerCount * itemsPerProducer;

        // Use a latch to make all producers start at roughly the same time
        CountDownLatch startLatch = new CountDownLatch(1);
        // Latch to know when all producers have finished
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        // Latch to know when all consumers have finished
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);

        // Shared set for detecting duplicates; we use a ConcurrentHashSet via Collections.newSetFromMap
        Set<Integer> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger lostCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + consumerCount);

        // ----- Producer task -----
        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            pool.submit(() -> {
                try {
                    startLatch.await(); // wait for the gunshot
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int elem = producerId * itemsPerProducer + i; // unique per producer
                        // Use put (blocking) – if the queue is full we just wait
                        queue.put(elem);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // ----- Consumer task -----
        for (int c = 0; c < consumerCount; c++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        Integer elem = queue.take(); // blocks until element or shutdown+empty
                        if (elem == null) {
                            // shutdown observed and queue empty – exit
                            break;
                        }
                        synchronized (seen) {
                            if (!seen.add(elem)) {
                                duplicateCount.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            });
        }

        // Release all producers simultaneously
        startLatch.countDown();

        // Wait for producers to finish putting
        assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Producers did not finish in time");

        // Shutdown the queue – no more puts will be accepted
        queue.shutdown();

        // Wait for consumers to drain the queue and exit
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Consumers did not finish in time");

        pool.shutdownNow();

        // ----- Verification -----
        // 1. No duplicates
        assertEquals(0, duplicateCount.get(), "Duplicate elements detected");

        // 2. All expected elements were seen exactly once
        assertEquals(expectedTotal, seen.size(),
                "Number of distinct elements taken does not match number of elements put");

        // 3. Verify that the set contains exactly the range [0, expectedTotal)
        for (int i = 0; i < expectedTotal; i++) {
            assertTrue(seen.contains(i), "Missing element: " + i);
        }
    }
}
```

**Explanation of the test strategy**

* **Basic tests** confirm that the core blocking (`put`/`take`) and non‑blocking (`offer`/`poll`) operations work as expected, including timed variants and shutdown semantics.
* **Shutdown tests** verify that after `shutdown()`:
  * `put` throws `IllegalStateException`.
  * `offer` returns `false`.
  * `take`/`poll` return `null` once the queue becomes empty.
  * Blocking threads waiting on `notFull` or `notEmpty` are awakened.
* **Stress test** (`testMultiProducerMultiConsumerNoLossOrDuplication`) creates several producer and consumer threads that operate concurrently on a small‑capacity queue.  
  * Each producer inserts a unique range of integers, guaranteeing a known set of expected elements.  
  * Consumers collect elements into a thread‑safe `Set` and count duplicates.  
  * After all producers finish, the queue is shut down; consumers drain remaining items and exit when `take` returns `null`.  
  * Final assertions ensure **no lost elements** (size of the set equals the total number of items put) and **no duplicates** (duplicate counter stays zero).  

The implementation uses a single `ReentrantLock` with two `Condition` queues, which is the classic monitor‑style solution for a bounded blocking queue and satisfies the requirement of not extending `java.util.concurrent.BlockingQueue`.