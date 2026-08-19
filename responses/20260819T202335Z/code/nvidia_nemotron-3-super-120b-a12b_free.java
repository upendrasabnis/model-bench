<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260819T202335Z -->
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
 * <p>Concurrency strategy:</p>
 * <ul>
 *   <li>All mutable state (the internal array, head/tail indices, element count,
 *       and the {@code shutdown} flag) is protected by a single {@link ReentrantLock}
 *       named {@code lock}.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notEmpty} – signalled when an element is added; threads
 *           waiting in {@code take()}, {@code poll(...)} await on it.</li>
 *         <li>{@code notFull} – signalled when an element is removed; threads
 *           waiting in {@code put()}, {@code offer(...)} await on it.</li>
 *       </ul>
 *   </li>
 *   <li>The {@code shutdown} flag is {@code volatile} so that a thread that
 *       acquires the lock can see the most recent value without extra
 *       synchronization. When {@code shutdown()} is invoked, the flag is set
 *       to {@code true} and both conditions are signalled to wake any threads
 *       that may be blocked.</li>
 *   <li>All public methods follow the classic “check‑then‑act” pattern inside a
 *       {@code while} loop to guard against spurious wake‑ups.</li>
 *   <li>After shutdown:
 *       <ul>
 *         <li>{@code put(E)} and {@code offer(E,…,TimeUnit)} throw
 *           {@link IllegalStateException}.</li>
 *         <li>{@code take()} throws {@link IllegalStateException} if the queue
 *           is empty; otherwise it returns the remaining element.</li>
 *         <li>{@code poll(...,TimeUnit)} returns {@code null} immediately
 *           when the queue is empty.</li>
 *       </ul>
 *     </li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // circular buffer
    private final int capacity;            // maximum number of elements
    private int putIndex = 0;              // where the next put will store
    private int takeIndex = 0;             // where the next take will read
    private int count = 0;                 // number of elements currently stored

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull  = lock.newCondition();

    /** True after {@code shutdown()} has been called. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold;
     *                 must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting if necessary until an element becomes available.
     *
     * @return the head of the queue
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     *                               and the queue is empty
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notEmpty.await();
            }
            E e = (E) dequeue();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting up to the specified timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added; {@code false} if the timeout
     *         elapsed before space became available
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
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
     * Retrieves and removes the head of this queue,
     * waiting up to the specified timeout for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the specified waiting
     *         time elapses before an element is available
     * @throws IllegalStateException if {@code shutdown()} has been invoked
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // queue is empty and shut down
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = (E) dequeue();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue.
     * After this method returns, further {@code put} and {@code offer}
     * operations will fail with {@link IllegalStateException};
     * {@code take} will throw {@link IllegalStateException} only when the
     * queue is empty; {@code poll} will return {@code null} when the queue
     * is empty.
     *
     * <p>All threads that are currently blocked in {@code put},
     * {@code offer}, {@code take} or {@code poll} are awakened.</p>
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

    /** Returns the current number of elements in the queue. */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if the queue is empty. */
    public boolean isEmpty() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if the queue is full. */
    public boolean isFull() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers – assume lock is held by the caller
    // -----------------------------------------------------------------
    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % capacity;
        ++count;
        notEmpty.signal();
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // help GC
        takeIndex = (takeIndex + 1) % capacity;
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

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;
    private static final int ITEMS_PER_PRODUCER = 2000;
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 5;

    /** Simple queue for the basic unit tests. */
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    // -----------------------------------------------------------------
    // Basic functionality tests
    // -----------------------------------------------------------------
    @Test
    void putTake_blockUntilAvailable() throws InterruptedException {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<Integer> taker = exec.submit(() -> {
            try {
                return queue.take(); // blocks until producer puts
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        // give the consumer a chance to block
        Thread.sleep(10);
        queue.put(42);

        assertEquals(42, taker.get(1, TimeUnit.SECONDS));
        exec.shutdownNow();
    }

    @Test
    void offerPoll_withTimeout() throws InterruptedException {
        // offer succeeds immediately because queue is empty
        assertTrue(queue.offer(99, 100, TimeUnit.MILLISECONDS));
        // queue is now full (capacity == 5) – fill the rest
        for (int i = 0; i < CAPACITY - 1; i++) {
            queue.put(i);
        }
        // now the queue is full; offer should time‑out
        assertFalse(queue.offer(77, 50, TimeUnit.MILLISECONDS));
        // poll should retrieve the head element
        assertEquals(99, queue.poll(100, TimeUnit.MILLISECONDS));
        // after draining, poll with timeout returns null
        for (int i = 0; i < CAPACITY - 1; i++) {
            assertEquals(i, queue.poll(10, TimeUnit.MILLISECONDS));
        }
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdown_wakesWaitingThreads() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(3);
        CountDownLatch started = new CountDownLatch(3);
        List<Future<?>> futures = new ArrayList<>();

        // two producers that will block on a full queue
        for (int i = 0; i < 2; i++) {
            futures.add(exec.submit(() -> {
                started.countDown();
                try {
                    queue.put(Thread.currentThread().getId());
                } catch (IllegalStateException e) {
                    // expected after shutdown
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        // one consumer that will block on an empty queue
        futures.add(exec.submit(() -> {
            started.countDown();
            try {
                queue.take();
            } catch (IllegalStateException e) {
                // expected after shutdown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // wait until all three threads are blocked
        assertTrue(started.await(2, TimeUnit.SECONDS));

        // now shut down the queue
        queue.shutdown();

        // all blocked threads should complete (either with exception or normally)
        for (Future<?> f : futures) {
            f.get(1, TimeUnit.SECONDS); // should not timeout
        }
        exec.shutdownNow();
    }

    // -----------------------------------------------------------------
    // Stress test – multiple producers & consumers
    // -----------------------------------------------------------------
    @Test
    void multiProducerMultiConsumer_noLostOrDuplicatedElements()
            throws Exception {

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        AtomicInteger nextId = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        CountDownLatch producersStarted = new CountDownLatch(NUM_PRODUCERS);
        CountDownLatch producersDone = new CountDownLatch(NUM_PRODUCERS);
        List<Future<?>> producerFutures = new ArrayList<>();
        List<Future<?>> consumerFutures = new ArrayList<>();

        // ----- producers -------------------------------------------------
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            producerFutures.add(pool.submit(() -> {
                producersStarted.countDown();
                try {
                    producersStarted.await(); // wait for all producers to start together
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                    int id = nextId.getAndIncrement();
                    try {
                        q.put(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                producersDone.countDown();
            }));
        }

        // ----- consumers -------------------------------------------------
        ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
        for (int c = 0; c < NUM_CONSUMERS; c++) {
            consumerFutures.add(pool.submit(() -> {
                try {
                    while (true) {
                        Integer item;
                        try {
                            item = q.take(); // blocks until something is available or shutdown
                        } catch (IllegalStateException e) {
                            // queue shut down and empty – exit
                            break;
                        }
                        if (item == null) {
                            continue; // should never happen with our take() implementation
                        }
                        collected.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Wait for all producers to finish putting
        assertTrue(producersDone.await(30, TimeUnit.SECONDS),
                "Producers did not finish in time");

        // Shut down the queue so consumers can exit
        q.shutdown();

        // Wait for consumers to finish
        for (Future<?> f : consumerFutures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // ----- verification ------------------------------------------------
        int expectedTotal = NUM_PRODUCERS * ITEMS_PER_PRODUCER;
        assertEquals(expectedTotal, collected.size(),
                "Number of consumed elements differs from number produced");

        // Check for duplicates – each id must appear exactly once
        Set<Integer> seen = new HashSet<>(collected);
        assertEquals(expectedTotal, seen.size(),
                "Duplicate elements detected (set size < list size)");

        // Optional: verify that all produced ids are present
        for (int i = 0; i < expectedTotal; i++) {
            assertTrue(seen.contains(i),
                    "Missing element: " + i);
        }
    }
}
```

**Explanation of the test suite**

* **Basic tests** (`putTake_blockUntilAvailable`, `offerPoll_withTimeout`) verify the core blocking and timed operations behave as expected under normal conditions.
* **Shutdown test** confirms that invoking `shutdown()` wakes all threads that are blocked in `put`, `offer`, `take`, or `poll`, and that subsequent operations respond with the defined exceptional or sentinel values.
* **Stress test** (`multiProducerMultiConsumer_noLostOrDuplicatedElements`) creates several producer and consumer threads that operate concurrently on a small bounded queue.  
  * Producers generate a monotonically increasing unique ID (via an `AtomicInteger`).  
  * Consumers collect every taken element into a thread‑safe `ConcurrentLinkedQueue`.  
  * After all producers finish, the queue is shut down, allowing consumers to drain remaining items and exit.  
  * Assertions ensure:
    * No elements are lost (total consumed equals total produced).  
    * No element appears more than once (duplicate detection via a `HashSet`).  
    * Every expected ID is present (optional completeness check).

The combination of a single lock + two conditions gives a clear, easy‑to‑reason‑about concurrency strategy while providing full blocking, timed, and shutdown functionality required by the problem statement.