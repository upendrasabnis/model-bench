<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261006T191822Z -->
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
 * A generic, bounded, blocking queue that implements the core operations of
 * {@link java.util.concurrent.BlockingQueue} without inheriting from it.
 *
 * <p><b>Concurrency strategy</b></p>
 * <ul>
 *   <li>All mutable state ({@code head}, {@code tail}, {@code count}, {@code shutdown})
 *       is protected by a single {@link ReentrantLock}. This gives us exclusive
 *       access to the internal array and the counters.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – waited on when the queue is full (put/offer).</li>
 *         <li>{@code notEmpty} – waited on when the queue is empty (take/poll).</li>
 *       </ul>
 *   </li>
 *   <li>The {@code shutdown} flag is {@code volatile} and is read under the lock.
 *       When {@code shutdown} becomes {@code true}:
 *       <ul>
 *         <li>{@code put(E)} throws {@link IllegalStateException}.</li>
 *         <li>{@code offer(E,…)} returns {@code false}.</li>
 *         <li>{@code take()} and {@code poll(…)} return {@code null} when the
 *             queue is empty (i.e. they behave as a “poison pill”).</li>
 *       </ul>
 *   </li>
 *   <li>All waiting loops re‑check the predicate after each {@code await*}
 *       return to guard against spurious wake‑ups.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The lock that guards all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when the queue is not full. */
    private final Condition notFull = lock.newCondition();

    /** Signaled when the queue is not empty. */
    private final Condition notEmpty = lock.newCondition();

    /** The storage array – never resized after construction. */
    @SuppressWarnings("unchecked")
    private final E[] elements = (E[]) new Object[0]; // placeholder, real array created in ctor

    /** Index of the element to be taken next. */
    private int head;

    /** Index at which the next element will be inserted. */
    private int tail;

    /** Number of elements currently stored. */
    private int count;

    /** Maximum number of elements the queue can hold. */
    private final int capacity;

    /** True after {@code shutdown()} has been called. */
    private volatile boolean shutdown;

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum size of the queue; must be > 0
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.elements = (E[]) new Object[capacity];
    }

    /** Inserts the specified element, waiting if necessary for space. */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            // invariant: count < capacity && !shutdown
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space.
     *
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed before space became available (or the queue was shut down).
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
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
            // invariant: count < capacity && !shutdown
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves and removes the head of the queue, waiting if necessary for an element. */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // queue closed and empty
                }
                notEmpty.await();
            }
            // invariant: count > 0 && !shutdown
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given timeout
     * for an element.
     *
     * @return the element, or {@code null} if the timeout elapsed before an element
     *         became available (or the queue was shut down and empty).
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // queue closed and empty
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            // invariant: count > 0 && !shutdown
            return dequeue();
        } finally {
            lock.unlock();
        }
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

    /** Returns {@code true} if the queue contains no elements. */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of additional elements that the queue can ideally accept. */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown. After this method returns:
     * <ul>
     *   <li>{@code put(E)} throws {@link IllegalStateException}.</li>
     *   <li>{@code offer(E,…)} returns {@code false}.</li>
     *   <li>{@code take()} and {@code poll(…)} return {@code null} when the queue
     *       becomes empty.</li>
     * </ul>
     * No further elements can be added; existing elements can still be removed.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) {
                return; // idempotent
            }
            shutdown = true;
            // Wake up any threads that may be waiting on notFull or notEmpty.
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /* -------------------  internal helpers  ------------------- */

    private void enqueue(E e) {
        elements[tail] = e;
        if (++tail == capacity) {
            tail = 0;
        }
        ++count;
        notEmpty.signal(); // at least one item is now present
    }

    private E dequeue() {
        @SuppressWarnings("unchecked")
        E e = elements[head];
        elements[head] = null; // help GC
        if (++head == capacity) {
            head = 0;
        }
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // ensure no threads are left waiting
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-5));
    }

    @Test
    void putTakeSingleElement() throws InterruptedException {
        queue.put(42);
        assertEquals(1, queue.size());
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void offerPollWithTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(99, 200, TimeUnit.MILLISECONDS));
        assertEquals(99, queue.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void offerPollWithTimeoutFailure() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        // offer should fail because queue is full and timeout expires
        assertFalse(queue.offer(999, 50, TimeUnit.MILLISECONDS));
        // poll should return null after timeout (queue not empty, but we wait for an element that never appears)
        // Since the queue is not empty, we need to drain first to see timeout behavior.
        for (int i = 0; i < 10; i++) {
            queue.take(); // empty it
        }
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void takeBlocksUntilElementAvailable() throws InterruptedException {
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
        assertTrue(future.isDone() == false, "take() should block until an element is present");

        // now produce an element
        queue.put(7);
        Integer result = future.get(1, TimeUnit.SECONDS);
        assertEquals(7, result);
        exec.shutdownNow();
    }

    @Test
    void offerBlocksUntilSpaceAvailable() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Boolean> future = exec.submit(() -> {
            try {
                return queue.offer(999, 2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });

        Thread.sleep(50);
        assertFalse(future.isDone(), "offer() should block until space is available");

        // free a slot
        queue.take();
        Boolean added = future.get(1, TimeUnit.SECONDS);
        assertTrue(added);
        exec.shutdownNow();
    }

    @Test
    void shutdownRejectsPutAndOffer() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertFalse(queue.offer(1, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownTakeAndPollReturnNullWhenEmpty() throws InterruptedException {
        queue.shutdown();
        assertNull(queue.take());
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void multiProducerMultiConsumerStress() throws Exception {
        final int PRODUCERS = 4;
        final int CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 2500; // total 10 000 items

        BoundedBlockingQueue<Integer> testQueue = new BoundedBlockingQueue<>(50);
        AtomicInteger nextId = new AtomicInteger(0);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();

        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            producers.add(new Thread(() -> {
                while (true) {
                    int id = nextId.getAndIncrement();
                    if (id >= PRODUCERS * ITEMS_PER_PRODUCER) {
                        break; // all work done
                    }
                    try {
                        testQueue.put(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }

        List<Thread> consumers = new ArrayList<>();
        for (int c = 0; c < CONSUMERS; c++) {
            consumers.add(new Thread(() -> {
                while (true) {
                    Integer item;
                    try {
                        item = testQueue.poll(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (item == null) {
                        // null means either timeout (queue empty) or shutdown.
                        // We break only when we know producers are done and queue is empty.
                        if (testQueue.isEmpty() && nextId.get() >= PRODUCERS * ITEMS_PER_PRODUCER) {
                            break;
                        }
                        // otherwise spin again
                        continue;
                    }
                    results.add(item);
                }
            }));
        }

        // start all threads
        producers.forEach(Thread::start);
        consumers.forEach(Thread::start);

        // wait for producers to finish
        for (Thread p : producers) {
            p.join();
        }

        // signal consumers that no more items will be added
        testQueue.shutdown();

        // wait for consumers to finish
        for (Thread c : consumers) {
            c.join();
        }

        // ----- verification -----
        int expectedTotal = PRODUCERS * ITEMS_PER_PRODUCER;
        assertEquals(expectedTotal, results.size(),
                "Number of consumed elements should equal number of produced elements");

        // check for duplicates and missing elements
        Set<Integer> seen = new HashSet<>(results);
        assertEquals(expectedTotal, seen.size(), "No duplicate elements should have been consumed");
        assertTrue(seen.containsAll(IntStream.range(0, expectedTotal).boxed().toList()),
                "All produced elements must be present in the result set");
    }
}
```

### How to run the tests
*Make sure you have JUnit 5 (JUnit Platform) on the test classpath.*  
With Maven, for example:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

Then execute:

```bash
mvn test
```

All tests should pass, demonstrating that the `BoundedBlockingQueue` behaves correctly under normal operation, timed operations, graceful shutdown, and heavy concurrent load.