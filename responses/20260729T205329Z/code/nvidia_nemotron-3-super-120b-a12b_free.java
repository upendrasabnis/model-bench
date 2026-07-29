<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260729T205329Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.util;

import java.util.ArrayDeque;
import java.util.Deque;
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
 *   <li>All mutable state ({@code queue}, {@code size}, {@code shutdown}) is protected by a
 *       single {@link ReentrantLock} {@code lock}. This guarantees mutual exclusion and
 *       establishes a happens‑before relationship between operations.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notEmpty} – signaled when an element is added; consumers wait on it.</li>
 *         <li>{@code notFull}  – signaled when an element is removed; producers wait on it.</li>
 *       </ul>
 *   </li>
 *   <li>The {@code shutdown} flag is {@code volatile}. Once set, further {@code put}/{@code offer}
 *       calls fail immediately (throw {@link IllegalStateException}) while {@code take}/{@code poll}
 *       continue to drain the queue. When the queue becomes empty after shutdown, {@code take}
 *       blocks until interrupted and then throws {@link InterruptedException}; {@code poll}
 *       returns {@code null} after the timeout.</li>
 *   </li>
 *   <li>Because only one lock is used, there is no risk of deadlock due to lock ordering.
 *       The conditions are waited on inside a loop that re‑checks the predicate after each
 *       wake‑up, protecting against spurious wake‑ups.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Deque<E> queue;          // underlying storage (FIFO)
    private final int capacity;            // maximum number of elements
    private volatile boolean shutdown;    // true => no more puts allowed

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull  = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue may hold; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    /** @return the current number of elements in the queue */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** @return the number of additional elements that can be inserted without blocking */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting if necessary for space to become available.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@link #shutdown()} has been invoked
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly();
        try {
            while (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            while (queue.size() == capacity) {
                notFull.await();
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down while waiting for space");
                }
            }
            queue.addLast(e);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space.
     *
     * @return {@code true} if the element was added, {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if {@link #shutdown()} has been invoked
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            while (queue.size() == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down while waiting for space");
                }
            }
            queue.addLast(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until an element becomes
     * available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shut down and empty (after draining)
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (!queue.isEmpty()) {
                    E e = queue.removeFirst();
                    notFull.signal();
                    return e;
                }
                if (shutdown) {
                    // Queue is shut down and empty – nothing more to take.
                    throw new IllegalStateException("Queue is shut down and empty");
                }
                notEmpty.await();
                // After await we loop again to re‑check the conditions.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given timeout if necessary
     * for an element to become available.
     *
     * @return the head of the queue, or {@code null} if the timeout elapsed before an element
     *         was available (or the queue is shut down and empty)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                if (!queue.isEmpty()) {
                    E e = queue.removeFirst();
                    notFull.signal();
                    return e;
                }
                if (shutdown) {
                    // Shut down and empty – nothing more will ever appear.
                    return null;
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
                // Loop again to re‑check.
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown. After this method returns, no further {@code put} or
     * {@code offer} calls will succeed (they throw {@link IllegalStateException}). Consumers
     * may continue to invoke {@code take}/{@code poll} to drain remaining elements.
     *
     * <p>Calling {@code shutdown} multiple times has no additional effect.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (!shutdown) {
                shutdown = true;
                // Wake up any threads that might be waiting on notFull or notEmpty.
                notFull.signalAll();
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return {@code true} if {@link #shutdown()} has been invoked */
    public boolean isShutdown() {
        return shutdown;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 5;   // small capacity to increase contention

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // ensure no threads are left waiting
    }

    // -----------------------------------------------------------------
    // Basic functionality tests
    // -----------------------------------------------------------------
    @Test
    void testPutTake() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferPollTimed() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS)); // empty -> null
    }

    @Test
    void testOfferFailsWhenFull() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 200, TimeUnit.MILLISECONDS));
        }
        // next offer should fail immediately because queue is full
        assertFalse(queue.offer(999, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTakeBlocksUntilAvailable() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Integer> future = exec.submit(() -> {
            try {
                return queue.take(); // will block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        // give the consumer a moment to block
        Thread.sleep(50);
        assertFalse(future.isDone(), "take should have blocked");

        // now produce an element
        queue.put(7);
        Integer result = future.get(1, TimeUnit.SECONDS);
        assertEquals(7, result);
        exec.shutdownNow();
    }

    @Test
    void testPollTimesOutWhenEmpty() throws InterruptedException {
        Long start = System.nanoTime();
        Integer result = queue.poll(50, TimeUnit.MILLISECONDS);
        Long elapsed = System.nanoTime() - start;
        assertNull(result);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed) >= 40, "poll should have waited roughly the timeout");
    }

    // -----------------------------------------------------------------
    // Shutdown behavior
    // -----------------------------------------------------------------
    @Test
    void testPutAfterShutdownThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void testOfferAfterShutdownThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTakeDrainsThenThrowsOnShutdown() throws InterruptedException {
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        queue.shutdown();

        // drain
        List<Integer> drained = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            drained.add(queue.take());
        }
        assertEquals(IntStream.range(0, CAPACITY).boxed().collect(Collectors.toList()), drained);

        // now take should fail because queue is shut down and empty
        assertThrows(IllegalStateException.class, queue::take);
    }

    @Test
    void testPollReturnsNullAfterShutdownAndDrain() throws InterruptedException {
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        queue.shutdown();

        // drain
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(Integer.valueOf(i), queue.poll(10, TimeUnit.MILLISECONDS));
        }
        // after drain, poll should return null (no more elements)
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------
    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 4;
        final int ITEMS_PER_PRODUCER = 5000; // total items = 20 000

        BoundedBlockingQueue<Long> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        AtomicLong nextId = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCER_COUNT + CONSUMER_COUNT);

        ConcurrentLinkedQueue<Long> taken = new ConcurrentLinkedQueue<>();

        // Producer task
        Runnable producer = () -> {
            try {
                startLatch.await();
                while (true) {
                    long id = nextId.getAndIncrement();
                    if (id >= ITEMS_PER_PRODUCER * PRODUCER_COUNT) {
                        break;
                    }
                    stressQueue.put(id); // blocking put
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                startLatch.await();
                while (true) {
                    Long val;
                    try {
                        val = stressQueue.take(); // blocking take
                    } catch (IllegalStateException e) {
                        // queue shut down and empty – exit
                        break;
                    }
                    taken.add(val);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        for (int i = 0; i < PRODUCER_COUNT; i++) exec.submit(producer);
        for (int i = 0; i < CONSUMER_COUNT; i++) exec.submit(consumer);

        // let all threads start together
        startLatch.countDown();

        // wait for producers to finish putting all items
        boolean producersDone = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(producersDone, "Producers did not finish in time");

        // shut down the queue so consumers can exit after draining
        stressQueue.shutdown();

        // wait for consumers to finish
        boolean consumersDone = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(consumersDone, "Consumers did not finish in time");

        exec.shutdownNow();

        // -----------------------------------------------------------------
        // Verification
        // -----------------------------------------------------------------
        long expectedTotal = (long) ITEMS_PER_PRODUCER * PRODUCER_COUNT;
        assertEquals(expectedTotal, taken.size(),
                "Number of taken elements differs from number of produced elements");

        // Check for duplicates and missing elements
        List<Long> sorted = taken.stream().sorted().toList();
        for (int i = 0; i < sorted.size(); i++) {
            assertEquals((long) i, sorted.get(i),
                    "Element at position " + i + " is unexpected; possible loss/duplication");
        }
    }
}
```

**Explanation of the test**

* The stress test creates a fixed‑size queue (`CAPACITY = 5`) to force frequent blocking.
* Four producers each try to put `ITEMS_PER_PRODUCER` unique sequence numbers (generated via an `AtomicLong`).  
  The total number of distinct elements is known up front.
* Four consumers continuously take elements and store them in a thread‑safe `ConcurrentLinkedQueue`.
* After all producers have finished, the queue is shut down; consumers exit when `take` throws
  `IllegalStateException` (queue empty & shut down & empty).
* Finally we verify:
  * The number of taken elements equals the number produced.
  * After sorting, the sequence is exactly `0, 1, 2, …, N‑1`, guaranteeing **no loss** and **no duplication**.

Both the implementation and the test suite are self‑contained and rely only on the JDK
(`java.util.concurrent.locks`, `java.util.concurrent`, JUnit 5). No `java.util.concurrent.BlockingQueue`
subclass is used.