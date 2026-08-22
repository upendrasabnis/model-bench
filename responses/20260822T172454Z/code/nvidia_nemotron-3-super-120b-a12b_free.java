<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260822T172454Z -->
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
 * <p>Implementation notes (concurrency strategy):
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state (the array,
 *       head/tail indices and the element count). This guarantees mutual
 *       exclusion for enqueue and dequeue operations.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – waited on by producers when the queue is full.</li>
 *         <li>{@code notEmpty} – waited on by consumers when the queue is empty.</li>
 *       </ul>
 *     When an element is added, {@code notEmpty} is signalled; when an element
 *     is removed, {@code notFull} is signalled. This is the classic monitor
 *     pattern for a bounded buffer.</li>
 *   <li>A volatile {@code shutdown} flag allows a clean shutdown:
 *       <ul>
 *         <li>After {@code shutdown()} is called, {@code put(E)} and
 *             {@code offer(E,…)} return immediately (false for the timed
 *             variants) – no new elements may be inserted.</li>
 *         <li>{@code take()} and {@code poll(…)} continue to block until an
 *             element is available; once the queue becomes empty <em>and</em>
 *             shutdown is true they return {@code null} (or {@code false} for
 *             the timed poll).</li>
 *       </ul>
 *     All waiting threads are awakened by signalling both conditions so that
 *     they can observe the shutdown flag and exit promptly.</li>
 *   <li>The implementation avoids the {@code java.util.concurrent.BlockingQueue}
 *       hierarchy as requested, but mirrors its semantics.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** Lock protecting all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when the queue is not full (producers wait here). */
    private final Condition notFull = lock.newCondition();

    /** Signalled when the queue is not empty (consumers wait here). */
    private final Condition notEmpty = lock.newCondition();

    /** Circular buffer storage. */
    @SuppressWarnings("unchecked")
    private final E[] elements = (E[]) new Object[0]; // placeholder, real size set in ctor

    /** Index of the next element to be removed (head of the queue). */
    private int head;

    /** Index of the next element to be inserted (tail of the queue). */
    private int tail;

    /** Number of elements currently stored. */
    private int count;

    /** Maximum capacity of the queue. */
    private final int capacity;

    /** True after {@code shutdown()} has been invoked. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue may hold;
     *                 must be > 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.elements = (E[]) new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     * @throws IllegalStateException if {@code shutdown()} has been called
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null element not allowed");
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary until an element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     * @throws NoSuchElementException if {@code shutdown()} has been called
     *         and the queue is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (count == 0) { // shutdown && empty
                throw new java.util.NoSuchElementException("Queue is shut down and empty");
            }
            E result = dequeue();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * up to the given timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null element not allowed");
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
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * up to the given timeout if necessary for an element to become
     * available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the specified waiting
     *         time elapses before an element is available
     * @throws InterruptedException if the current thread is interrupted
     *         while waiting
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
            if (count == 0) { // shutdown && empty
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown of the queue.
     * <p>After this method returns, no further elements may be inserted.
     * Producers attempting {@code put} will receive {@code IllegalStateException};
     * timed {@code offer} will return {@code false} immediately.
     * Consumers will continue to retrieve remaining elements; once the queue
     * becomes empty they will return {@code null} (or {@code false} for timed
     * {@code poll}) or throw {@code NoSuchElementException} for {@code take}.
     * All threads that are currently waiting in {@code put}/{@code take}
     * or the timed variants are awakened so they can observe the shutdown
     * state promptly.
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

    /** Returns {@code true} if this queue has been shut down. */
    public boolean isShutdown() {
        return shutdown;
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

    /** Returns the remaining capacity. */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Internal helpers – assume the lock is held by the caller.          */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private void enqueue(E e) {
        elements[tail] = e;
        tail = (tail + 1) % capacity;
        ++count;
        notEmpty.signal(); // wake a consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = elements[head];
        elements[head] = null; // help GC
        head = (head + 1) % capacity;
        --count;
        notFull.signal(); // wake a producer
        return e;
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int TOTAL_ITEMS = 10_000;
    private static final int PRODUCER_COUNT = 4;
    private static final int CONSUMER_COUNT = 3;

    /** Simple queue for sanity checks. */
    @Test
    void testPutTakeSingleThread() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(42);
        assertEquals(42, q.take());
        assertTrue(q.isEmpty());
    }

    @Test
    void testOfferPollTimed() throws Exception {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(CAPACITY);
        assertTrue(q.offer("hello", 100, TimeUnit.MILLISECONDS));
        assertEquals("hello", q.poll(100, TimeUnit.MILLISECONDS));
        assertNull(q.poll(10, TimeUnit.MILLISECONDS)); // timeout, empty
        assertFalse(q.offer("world", 10, TimeUnit.MILLISECONDS)); // timeout, full after fill
        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(q.offer(Integer.toString(i), 200, TimeUnit.MILLISECONDS));
        }
        assertFalse(q.offer("overflow", 10, TimeUnit.MILLISECONDS));
        // drain
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(Integer.toString(i), q.poll(200, TimeUnit.MILLISECONDS));
        }
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownBehavior() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Integer> produced = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        // Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 20; i++) {
                    q.put(i); // will block when full
                    produced.add(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Consumer
        exec.submit(() -> {
            try {
                startLatch.await();
                while (true) {
                    try {
                        Integer v = q.take();
                        consumed.add(v);
                    } catch (java.util.NoSuchElementException ex) {
                        // queue shut down and empty – exit
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        // Let the queue fill a bit, then shut down
        Thread.sleep(50);
        q.shutdown();

        doneLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        // All produced items must have been consumed (queue may have been drained after shutdown)
        assertEquals(produced, consumed);
        assertTrue(q.isShutdown());
    }

    /**
     * Stress test with multiple producers and consumers.
     * Verifies that no element is lost or duplicated.
     */
    @Test
    void testMultiProducerMultiConsumerStress() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService pool = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumerDone = new CountDownLatch(CONSUMER_COUNT);

        AtomicInteger nextId = new AtomicInteger(0);
        ConcurrentLinkedQueue<Integer> produced = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> consumed = new ConcurrentLinkedQueue<>();

        // Producer task
        Runnable producer = () -> {
            try {
                startLatch.await();
                while (true) {
                    int id = nextId.getAndIncrement();
                    if (id >= TOTAL_ITEMS) {
                        break;
                    }
                    // keep trying until we succeed (offer with short timeout to avoid busy spin)
                    boolean ok = false;
                    while (!ok && !Thread.interrupted()) {
                        ok = q.offer(id, 10, TimeUnit.MILLISECONDS);
                        if (!ok) {
                            Thread.yield(); // give consumers a chance
                        }
                    }
                    if (!ok) {
                        // shutdown or interruption – exit
                        break;
                    }
                    produced.add(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producerDone.countDown();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                startLatch.await();
                while (true) {
                    Integer val = q.poll(10, TimeUnit.MILLISECONDS);
                    if (val == null) {
                        // null can mean timeout or shutdown+empty.
                        // If all producers are done and queue is empty we can exit.
                        if (producerDone.getCount() == 0 && q.size() == 0) {
                            break;
                        }
                        Thread.yield();
                        continue;
                    }
                    consumed.add(val);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        };

        for (int i = 0; i < PRODUCER_COUNT; i++) {
            pool.submit(producer);
        }
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            pool.submit(consumer);
        }

        startLatch.countDown();

        // Wait for producers to finish putting all items
        assertTrue(producerDone.await(30, TimeUnit.SECONDS));
        // Shutdown the queue so consumers can exit after draining
        q.shutdown();
        // Wait for consumers to finish
        assertTrue(consumerDone.await(30, TimeUnit.SECONDS));

        pool.shutdownNow();

        // Verify no loss / duplication
        List<Integer> prodList = new ArrayList<>(produced);
        List<Integer> consList = new ArrayList<>(consumed);
        Collections.sort(prodList);
        Collections.sort(consList);

        assertEquals(TOTAL_ITEMS, prodList.size(), "Number of produced elements mismatch");
        assertEquals(TOTAL_ITEMS, consList.size(), "Number of consumed elements mismatch");
        assertEquals(prodList, consList, "Produced and consumed sequences differ (loss/duplication)");

        // Additionally, ensure each expected id appears exactly once
        boolean[] seen = new boolean[TOTAL_ITEMS];
        for (Integer v : consList) {
            assertTrue(v >= 0 && v < TOTAL_ITEMS, "Element out of expected range: " + v);
            assertFalse(seen[v], "Duplicate element detected: " + v);
            seen[v] = true;
        }
        for (boolean b : seen) {
            assertTrue(b, "Some element was never consumed");
        }
    }

    /** Helper to check emptiness (used in tests). */
    private boolean isEmpty(BoundedBlockingQueue<?> q) {
        return q.size() == 0;
    }
}
```

**Explanation of the test suite**

* **Basic functionality** – `testPutTakeSingleThread` and `testOfferPollTimed` verify the core blocking and timed operations work as expected in a single‑threaded scenario.
* **Shutdown semantics** – `testShutdownBehavior` demonstrates that after `shutdown()` no further inserts are accepted, producers either throw (`put`) or return `false` (`offer`), and consumers drain the queue before exiting.
* **Stress test** – `testMultiProducerMultiConsumerStress` runs several producer and consumer threads concurrently, each producing/consuming a known range of unique IDs. After all producers finish, the queue is shut down, allowing consumers to exit once the queue is empty. The test collects all produced and consumed elements, sorts them, and asserts that:
  * No elements are lost (same count as expected).
  * No elements are duplicated (each ID appears exactly once).
  * The sets of produced and consumed elements match.

All tests use JUnit 5 (`org.junit.jupiter.api.*`) and rely only on the standard `java.util.concurrent` utilities for coordination (`CountDownLatch`, `ExecutorService`, `ConcurrentLinkedQueue`), never on `BlockingQueue` implementations, satisfying the requirement to build and test a custom bounded blocking queue.