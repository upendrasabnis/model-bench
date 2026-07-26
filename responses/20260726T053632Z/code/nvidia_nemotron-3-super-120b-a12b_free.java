<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260726T053632Z -->
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
 *
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state
 *       ({@code array}, {@code putIndex}, {@code takeIndex}, {@code count},
 *       {@code shutdown}). This guarantees mutual exclusion and visibility
 *       of changes across threads.</li>
 *   <li>Two {@link Condition} objects are associated with the lock:
 *       <ul>
 *         <li>{@code notFull} – waited on when the queue is full (for {@code put}
 *             and timed {@code offer}) and signaled when an element is removed.</li>
 *         <li>{@code notEmpty} – waited on when the queue is empty (for {@code take}
 *             and timed {@code poll}) and signaled when an element is inserted.</li>
 *       </ul>
 *   </li>
 *   <li>The queue uses a circular array to achieve O(1) put/take.
 *       {@code putIndex} points to the next free slot, {@code takeIndex}
 *       points to the next element to remove, and {@code count} holds the
 *       current number of stored elements.</li>
 *   <li>A volatile {@code shutdown} flag allows a clean termination:
 *       {@code shutdown()} sets the flag and wakes all threads waiting on
 *       either condition. After shutdown, {@code put} and {@code take}
 *       throw {@link IllegalStateException}; timed {@code offer}/{@code poll}
 *       return {@code false}/{@code null} respectively (or throw if interrupted).</li>
 *   </li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The storage buffer – never null after construction. */
    private final Object[] buffer;

    /** Index where the next element will be inserted. */
    private int putIndex;

    /** Index where the next element will be removed. */
    private int takeIndex;

    /** Number of elements currently stored. */
    private int count;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when space becomes available. */
    private final Condition notFull = lock.newCondition();

    /** Signaled when an element becomes available. */
    private final Condition notEmpty = lock.newCondition();

    /** True after {@link #shutdown()} has been invoked. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum number of elements; must be > 0
     * @throws IllegalArgumentException if capacity ≤ 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.buffer = new Object[capacity];
    }

    /**
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to add; must not be null
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if {@code e} is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly();
        try {
            while (count == buffer.length && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            buffer[putIndex] = e;
            putIndex = (putIndex + 1) % buffer.length;
            ++count;
            notEmpty.signal(); // at least one consumer can now proceed
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            E e = (E) buffer[takeIndex];
            buffer[takeIndex] = null; // help GC
            takeIndex = (takeIndex + 1) % buffer.length;
            --count;
            notFull.signal(); // at least one producer can now proceed
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space
     * to become available.
     *
     * @param e        the element to add; must not be null
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available (or the queue was shut down)
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if {@code e} is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == buffer.length && !shutdown) {
                if (nanos <= 0) {
                    return false; // timed out
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                return false; // treat shutdown as failure to offer
            }
            buffer[putIndex] = e;
            putIndex = (putIndex + 1) % buffer.length;
            ++count;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given
     * timeout for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the timeout elapsed
     *         before an element was available (or the queue was shut down)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null; // timed out
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown) {
                return null; // treat shutdown as empty
            }
            E e = (E) buffer[takeIndex];
            buffer[takeIndex] = null; // help GC
            takeIndex = (takeIndex + 1) % buffer.length;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown. After this method returns,
     * {@code put}/{code take} will throw {@link IllegalStateException},
     * and timed {@code offer}/{code poll} will return {@code false}/{code null}
     * (unless interrupted). All threads blocked in {@code put}, {@code take},
     * {@code offer} or {@code poll} are awakened.
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
     * @return {@code true} if {@link #shutdown()} has been called.
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * @return the current number of elements in the queue.
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
     * @return the remaining capacity (number of elements that can be added
     *         without blocking).
     */
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
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 5;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    // -----------------------------------------------------------------
    // Basic functionality
    // -----------------------------------------------------------------
    @Test
    void putTakeBasic() throws InterruptedException {
        new Thread(() -> {
            try {
                queue.put(1);
                queue.put(2);
                queue.put(3);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }).start();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void offerPollTimed() throws InterruptedException {
        // offer succeeds immediately because queue is empty
        assertTrue(queue.offer(42, 100, TimeUnit.MILLISECONDS));
        assertEquals(42, queue.poll(100, TimeUnit.MILLISECONDS));

        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 50, TimeUnit.MILLISECONDS));
        }
        // now it's full – offer should time‑out
        assertFalse(queue.offer(999, 50, TimeUnit.MILLISECONDS));

        // drain with timed poll – should succeed until empty
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(Integer.valueOf(i), queue.poll(50, TimeUnit.MILLISECONDS));
        }
        // empty – poll times out
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------
    // Shutdown behavior
    // -----------------------------------------------------------------
    @Test
    void shutdownWakesPutAndTake() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch putLatch = new CountDownLatch(1);
        CountDownLatch takeLatch = new CountDownLatch(1);

        Future<?> putFuture = exec.submit(() -> {
            try {
                putLatch.countDown(); // signal ready
                queue.put(7); // should block until shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException ignored) {
                // expected after shutdown
            }
        });

        Future<?> takeFuture = exec.submit(() -> {
            try {
                takeLatch.countDown(); // signal ready
                queue.take(); // should block until shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException ignored) {
                // expected after shutdown
            }
        });

        // wait until both workers are blocked
        assertTrue(putLatch.await(2, TimeUnit.SECONDS));
        assertTrue(takeLatch.await(2, TimeUnit.SECONDS));

        // trigger shutdown
        queue.shutdown();

        // both futures should complete exceptionally (or normally) now
        assertThrows(Exception.class, putFuture::get);
        assertThrows(Exception.class, takeFuture::get);

        exec.shutdownNow();
    }

    @Test
    void shutdownOfferPollReturnsFalseNull() throws InterruptedException {
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        // offer should fail immediately after shutdown
        queue.shutdown();
        assertFalse(queue.offer(99, 100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------
    @Test
    @Timeout(30) // safeguard against deadlocks
    void multiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 3;
        final int ELEMENTS_PER_PRODUCER = 5000; // total elements = 20000

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        CountDownLatch producersStarted = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch producersFinished = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumersFinished = new CountDownLatch(CONSUMER_COUNT);

        // each consumer tracks how many times it saw each value
        ConcurrentHashMap<Integer, AtomicInteger> seen =
                new ConcurrentHashMap<>();

        List<Consumer<Integer>> consumers = new ArrayList<>();
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            Consumer<Integer> consumer = value -> {
                seen.computeIfAbsent(value, k -> new AtomicInteger())
                        .incrementAndGet();
            };
            consumers.add(consumer);
        }

        ExecutorService exec = Executors.newFixedThreadPool(
                PRODUCER_COUNT + CONSUMER_COUNT);

        // ---- producers -------------------------------------------------
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int producerId = p;
            exec.submit(() -> {
                try {
                    producersStarted.countDown();
                    producersStarted.await(); // wait for all producers to be ready
                    int base = producerId * ELEMENTS_PER_PRODUCER;
                    for (int i = 0; i < ELEMENTS_PER_PRODUCER; i++) {
                        int elem = base + i;
                        q.put(elem); // blocking put
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersFinished.countDown();
                }
            });
        }

        // ---- consumers -------------------------------------------------
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            final Consumer<Integer> consumer = consumers.get(c);
            exec.submit(() -> {
                try {
                    while (!q.isShutdown()) {
                        Integer val = q.take(); // blocking take
                        if (val == null) continue; // should never happen with our queue
                        consumer.accept(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException ignored) {
                    // queue shut down – exit loop
                } finally {
                    consumersFinished.countDown();
                }
            });
        }

        // Wait for all producers to start, then let them run
        assertTrue(producersStarted.await(5, TimeUnit.SECONDS));
        // Wait for producers to finish putting all elements
        assertTrue(producersFinished.await(30, TimeUnit.SECONDS));

        // No more elements will be added – shut down the queue
        q.shutdown();

        // Wait for consumers to drain the queue and exit
        assertTrue(consumersFinished.await(30, TimeUnit.SECONDS));

        exec.shutdownNow();

        // ---- verification ------------------------------------------------
        int expectedTotal = PRODUCER_COUNT * ELEMENTS_PER_PRODUCER;
        int actualTotal = seen.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();

        assertEquals(expectedTotal, actualTotal,
                "Total number of elements taken differs from expected");

        // Each element must have been seen exactly once
        seen.forEach((value, count) -> {
            assertEquals(1, count.get(),
                    "Element " + value + " was seen " + count.get() + " times");
        });

        // Queue should be empty after shutdown
        assertTrue(q.isShutdown());
        assertEquals(0, q.size());
    }
}
```

### Explanation of the Test Suite

* **Basic functionality** – verifies that `put`/`take` and timed `offer`/`poll` work as expected under normal conditions.
* **Shutdown behavior** – ensures that invoking `shutdown()` wakes all blocked threads and that subsequent operations fail appropriately (`IllegalStateException` for blocking methods, `false`/`null` for timed methods).
* **Multi‑producer / multi‑consumer stress test** –  
  * A fixed number of producers each insert a disjoint range of integers (no overlap in the source data).  
  * Consumers continuously `take` elements and count occurrences in a thread‑safe map.  
  * After all producers finish, the queue is shut down; consumers exit when `take` throws `IllegalStateException`.  
  * The test asserts that the total number of taken elements equals the number produced and that each distinct element appears **exactly once**, guaranteeing no loss or duplication despite concurrent access.

The implementation uses a single `ReentrantLock` with two `Condition` queues, providing a clear, easy‑to‑reason‑about concurrency strategy while satisfying all required operations.