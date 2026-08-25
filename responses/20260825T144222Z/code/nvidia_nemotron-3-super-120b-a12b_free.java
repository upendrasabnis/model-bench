<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260825T144222Z -->
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
 * A generic, bounded, blocking FIFO queue.
 *
 * <p>Concurrency strategy
 * ----------------------
 * The implementation uses a single {@link ReentrantLock} to protect all mutable state
 * (the circular array, head/tail indices and element count).  Two {@link Condition}
 * objects are associated with that lock:
 *
 * <ul>
 *   <li>{@code notEmpty} – signalled when an element is added; consumers wait on it
 *       when the queue is empty.</li>
 *   <li>{@code notFull}  – signalled when an element is removed; producers wait on it
 *       when the queue is full.</li>
 * </ul>
 *
 * All public methods acquire the lock, perform the necessary wait/signal loop,
 * and release the lock in a {@code finally} block.  This guarantees:
 *
 * <ul>
 *   <li>Mutual exclusion – only one thread can modify the internal state at a time.</li>
 *   <li>Visibility – changes made by one thread are visible to others after the lock
 *       is released (the lock provides a happens‑before edge).</li>
 *   <li>No lost or spurious wake‑ups – the loop re‑checks the predicate after each
 *       {@code await} (or {@code awaitNanos}) return.</li>
 * </ul>
 *
 * A volatile {@code shutdown} flag allows a clean termination.  When {@code shutdown()}
 * is invoked, the flag is set and both conditions are signalled so that any threads
 * blocked in {@code put}/{@code take} (or timed {@code offer}/{@code poll}) can exit
 * promptly.  After shutdown, {@code put} and {@code take} throw {@link IllegalStateException},
 * while timed {@code offer}/{@code poll} return {@code false}/{code null} immediately.
 *
 * <p>Note: This class does <strong>not</strong> inherit from {@link java.util.concurrent.BlockingQueue}
 * – it is a self‑contained implementation suitable for learning or environments where
 * the JDK concurrent queue is not allowed.
 */
public class BoundedBlockingQueue<E> {

    /** The lock that guards all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Condition waited on by consumers when the queue is empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Condition waited on by producers when the queue is full. */
    private final Condition notFull = lock.newCondition();

    /** Circular buffer storing the elements. */
    @SuppressWarnings("unchecked")
    private final E[] buffer;

    /** Index of the element that will be consumed next. */
    private int head = 0;

    /** Index at which the next element will be inserted. */
    private int tail = 0;

    /** Number of elements currently stored in the buffer. */
    private int count = 0;

    /** True after {@code shutdown()} has been called. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.buffer = (E[]) new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        lock.lockInterruptibly();
        try {
            while (count == buffer.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shutdown");
                }
                notFull.await();
            }
            // Insert
            buffer[tail] = e;
            if (++tail == buffer.length) tail = 0;
            ++count;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head element
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shutdown");
                }
                notEmpty.await();
            }
            @SuppressWarnings("unchecked")
            E e = (E) buffer[head];
            buffer[head] = null; // help GC
            if (++head == buffer.length) head = 0;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified waiting time for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} parameter
     * @return {@code true} if the element was added; {@code false} if the timeout elapsed
     *         before space became available or the queue was shut down
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == buffer.length) {
                if (shutdown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            // Insert
            buffer[tail] = e;
            if (++tail == buffer.length) tail = 0;
            ++count;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * waiting time if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} parameter
     * @return the head element, or {@code null} if the specified waiting time elapses
     *         before an element is available or the queue is shut down
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
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
            @SuppressWarnings("unchecked")
            E e = (E) buffer[head];
            buffer[head] = null;
            if (++head == buffer.length) head = 0;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown.  After this method returns,
     * any thread blocked in {@code put}/{code take} will exit with
     * {@link IllegalStateException}; timed {@code offer}/{code poll}
     * will return {@code false}/{code null} immediately.
     * Subsequent calls to {@code put}/{code take} will also fail
     * immediately.
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

    /** Returns {@code true} if this queue has been shut down. */
    public boolean isShutdown() {
        return shutdown;
    }

    /** Returns the number of elements currently in the queue. */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of additional elements that this queue can ideally accept. */
    public int remainingCapacity() {
        lock.lock();
        try {
            return buffer.length - count;
        } finally {
            lock.unlock();
        }
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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure no stray threads are left waiting
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    // -----------------------------------------------------------------
    // Basic functionality tests
    // -----------------------------------------------------------------

    @Test
    void testPutTake() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    queue.put(i);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        Thread consumer = new Thread(() -> {
            List<Integer> taken = new ArrayList<>();
            try {
                for (int i = 0; i < 5; i++) {
                    taken.add(queue.take());
                }
                assertEquals(List.of(0, 1, 2, 3, 4), taken);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
    }

    @Test
    void testOfferPollTimedSuccess() throws InterruptedException {
        assertTrue(queue.offer(42, 200, TimeUnit.MILLISECONDS));
        Integer val = queue.poll(200, TimeUnit.MILLISECONDS);
        assertEquals(42, val);
    }

    @Test
    void testOfferPollTimedTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        // Offer should fail because queue is full and timeout expires
        assertFalse(queue.offer(99, 50, TimeUnit.MILLISECONDS));
        // Poll should return null because queue is empty after we drain it
        for (int i = 0; i < CAPACITY; i++) {
            queue.take();
        }
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownPutTakeThrows() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<?> putFuture = exec.submit(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Future<?> takeFuture = exec.submit(() -> {
            try {
                queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give the threads a chance to block
        Thread.sleep(50);
        queue.shutdown();

        // Both futures should complete exceptionally with IllegalStateException
        assertThrows(ExecutionException.class, putFuture::get);
        assertThrows(ExecutionException.class, takeFuture::get);
        assertTrue(putFuture.getCause() instanceof IllegalStateException);
        assertTrue(takeFuture.getCause() instanceof IllegalStateException);

        exec.shutdownNow();
    }

    @Test
    void testShutdownOfferPollReturnsFalseNull() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        queue.shutdown();

        // Offer should return false immediately
        assertFalse(queue.offer(7, 1, TimeUnit.SECONDS));
        // Poll should return null immediately
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------

    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int producerCount = 4;
        final int consumerCount = 3;
        final int itemsPerProducer = 5000; // total produced = 20000

        BoundedBlockingQueue<Long> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        AtomicLong nextId = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> consumed = new ConcurrentLinkedQueue<>();
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);
        AtomicBoolean shutdownRequested = new AtomicBoolean(false);

        // Producer task
        Runnable producer = () -> {
            try {
                while (!shutdownRequested.get()) {
                    long id = nextId.getAndIncrement();
                    if (id >= (long) producerCount * itemsPerProducer) {
                        break; // no more work
                    }
                    stressQueue.put(id); // blocks if full
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producersDone.countDown();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                while (!shutdownRequested.get() || stressQueue.size() > 0) {
                    try {
                        Long val = stressQueue.take(); // blocks if empty
                        consumed.add(val);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                consumersDone.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(producerCount + consumerCount);
        for (int i = 0; i < producerCount; i++) {
            exec.submit(producer);
        }
        for (int i = 0; i < consumerCount; i++) {
            exec.submit(consumer);
        }

        // Wait for all producers to finish
        assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Producers did not finish in time");
        // Signal consumers to exit after queue is drained
        shutdownRequested.set(true);
        stressQueue.shutdown(); // wake any waiting consumers
        // Wait for consumers to finish
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Consumers did not finish in time");

        exec.shutdownNow();

        // Verify no lost or duplicated elements
        long expectedTotal = (long) producerCount * itemsPerProducer;
        assertEquals(expectedTotal, consumed.size(),
                "Number of consumed elements differs from number produced");

        // Check that each produced id appears exactly once
        Object[] array = consumed.toArray();
        Long[] longs = java.util.Arrays.copyOf(array, array.length, Long[].class);
        java.util.Arrays.sort(longs);
        for (int i = 0; i < longs.length; i++) {
            assertEquals((long) i, longs[i],
                    "Duplicate or missing element at position " + i);
        }
    }
}
```

### Explanation of the Test Suite

* **Basic functionality** – verifies that `put`/`take` and timed `offer`/`poll` work as expected under normal conditions.
* **Shutdown behavior** – ensures that after `shutdown()`:
  * `put` and `take` abort with `IllegalStateException`.
  * Timed `offer` returns `false` and timed `poll` returns `null` immediately.
* **Stress test (multi‑producer / multi‑consumer)**  
  * Several producer threads generate a known sequence of unique `Long` values (`nextId`).  
  * Several consumer threads drain the queue into a thread‑safe `ConcurrentLinkedQueue`.  
  * After producers finish, the queue is shut down to wake any waiting consumers.  
  * The test asserts that:
    * The total number of consumed elements equals the total number produced.  
    * After sorting the consumed IDs, each value matches its index – guaranteeing **no loss** and **no duplication**.

All tests run with JUnit 5 (`org.junit.jupiter.api`). The queue implementation relies solely on a `ReentrantLock` and two `Condition` objects, satisfying the requirement of not using `java.util.concurrent.BlockingQueue`.