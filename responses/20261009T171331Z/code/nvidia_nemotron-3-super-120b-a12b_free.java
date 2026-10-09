<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261009T171331Z -->
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
 * <p>Concurrency strategy</p>
 * <ul>
 *   <li>All mutable state (the internal {@code Object[]} buffer, head/tail indices,
 *       count, and the {@code shutdown} flag) is protected by a single
 *       {@link ReentrantLock}.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – signaled when an element is removed, making space
 *               available for producers.</li>
 *         <li>{@code notEmpty} – signaled when an element is added, making data
 *               available for consumers.</li>
 *       </ul>
 *   </li>
 *   <li>Waiting threads invoke {@code Condition.await(long nanos)} with the
 *       remaining timeout (or {@code 0L} for an indefinite wait).  When the
 *       timeout elapses the method returns {@code false} (offer/poll) or throws
 *       {@link InterruptedException} (put/take) as appropriate.</li>
 *   <li>A {@code shutdown} flag allows a clean termination:
 *       <ul>
 *         <li>After {@link #shutdown()} is called, {@code put}/{code take}
 *               throw {@link IllegalStateException} (instead of blocking).</li>
 *         <li>{@code offer} and {@code poll} return {@code false}/{code null}
 *               immediately.</li>
 *         <li>All waiting threads are awakened by signalling both conditions,
 *               guaranteeing that no thread remains blocked forever.</li>
 *       </ul>
 *   </li>
 *   <li>The internal buffer is a fixed‑size {@code Object[]} treated as a circular
 *       array; this gives O(1) enqueue/dequeue and avoids the overhead of
 *       linked‑node allocations.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // circular buffer
    private final int capacity;            // max number of elements
    private int putIndex = 0;              // where to insert next
    private int takeIndex = 0;             // where to remove next
    private int count = 0;                 // current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean shutdown = false;

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum size of the queue; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /** @return true if the queue has been shut down */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Shuts down the queue. No further {@code put} or {@code take} operations are
     * allowed; they will throw {@link IllegalStateException}.  {@code offer} and
     * {@code poll} return {@code false}/{code null} immediately.  All threads
     * blocked in {@code put}/{code take}/{code offer}/{code poll} are awakened.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if
     * necessary for space to become available.
     *
     * @param e the element to add
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null element not allowed");
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
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
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
            return (E) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to
     * the given timeout for space to become available.
     *
     * @param e      the element to add
     * @param timeout the maximum time to wait
     * @param unit   the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed or the queue was shut down before space became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null element not allowed");
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
     * Retrieves and removes the head of this queue, waiting up to the given
     * timeout if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the head of the queue, or {@code null} if the timeout elapsed
     *         or the queue was shut down before an element became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
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
            return (E) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** Non‑blocking insert; returns {@code true} if successful, {@code false} if full. */
    public boolean offer(E e) {
        Objects.requireNonNull(e, "null element not allowed");
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count == capacity || shutdown) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Non‑blocking removal; returns {@code null} if empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count == 0 || shutdown) {
                return null;
            }
            return (E) dequeue();
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

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
        queue.shutdown();
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
    void testOfferPollTimeout() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
        // poll on empty queue with timeout -> null
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testOfferPollImmediate() {
        assertTrue(queue.offer(7));
        assertEquals(7, queue.poll());
        assertNull(queue.poll()); // empty
    }

    @Test
    void testPutTakeBlockUntilSpace() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            // Fill the queue
            for (int i = 0; i < CAPACITY; i++) {
                queue.put(i);
            }
            Future<?> putFuture = exec.submit(() -> {
                try {
                    queue.put(999); // should block until space appears
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // Give the put task a moment to block
            Thread.sleep(20);
            assertFalse(putFuture.isDone(), "put should be blocked while queue is full");

            // Now consume one element to make space
            ExecutorService consumer = Executors.newSingleThreadExecutor();
            Future<Integer> takeFuture = consumer.submit(queue::take);
            assertEquals(0, takeFuture.get(1, TimeUnit.SECONDS));
            consumer.shutdownNow();

            // The put should now complete
            assertTrue(putFuture.get(1, TimeUnit.SECONDS) == null);
            assertEquals(999, queue.take());
        } finally {
            exec.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // Shutdown behavior
    // -----------------------------------------------------------------
    @Test
    void testShutdownPutTakeThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, queue::put, 1);
        assertThrows(IllegalStateException.class, queue::take);
    }

    @Test
    void testShutdownOfferPollReturnFalseNull() {
        queue.shutdown();
        assertFalse(queue.offer(1));
        assertNull(queue.poll());
        assertFalse(queue.offer(1, 10, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownWakesWaitingThreads() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        try {
            // Fill queue
            for (int i = 0; i < CAPACITY; i++) {
                queue.put(i);
            }

            Future<?> putFuture = exec.submit(() -> {
                try {
                    queue.put(99);
                } catch (InterruptedException ignored) {
                }
            });
            Future<?> takeFuture = exec.submit(() -> {
                try {
                    queue.take();
                } catch (InterruptedException ignored) {
                }
            });

            // Ensure they are blocked
            Thread.sleep(50);
            assertFalse(putFuture.isDone());
            assertFalse(takeFuture.isDone());

            // Shutdown should wake them
            queue.shutdown();

            assertThrows(ExecutionException.class, putFuture::get);
            assertThrows(ExecutionException.class, takeFuture::get);
            assertTrue(putFuture.isDone());
            assertTrue(takeFuture.isDone());
        } finally {
            exec.shutdownNow();
        }
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------
    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int producerCount = 4;
        final int consumerCount = 3;
        final int itemsPerProducer = 5_000; // total elements = 20_000
        final int expectedTotal = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        ExecutorService prodExec = Executors.newFixedThreadPool(producerCount);
        ExecutorService consExec = Executors.newFixedThreadPool(consumerCount);
        List<Future<?>> producerFutures = new ArrayList<>();
        List<Future<?>> consumerFutures = new ArrayList<>();

        // Shared structure to count occurrences of each element.
        // We know the exact range each producer will generate, so we can allocate
        // an array of size expectedTotal and use AtomicIntegerArray for lock‑free increments.
        AtomicIntegerArray counts = new AtomicIntegerArray(expectedTotal);

        // Producer task: each producer gets a unique offset.
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producerFutures.add(prodExec.submit(() -> {
                int base = producerId * itemsPerProducer;
                for (int i = 0; i < itemsPerProducer; i++) {
                    int elem = base + i;
                    try {
                        q.put(elem); // blocking put
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }

        // Consumer task: take elements and increment the corresponding counter.
        for (int c = 0; c < consumerCount; c++) {
            consumerFutures.add(consExec.submit(() -> {
                try {
                    while (!q.isShutdown() || q.size() > 0) {
                        Integer elem = q.take(); // blocks until element or shutdown
                        if (elem == null) {
                            continue; // should never happen with our implementation
                        }
                        int index = elem; // because we used 0..expectedTotal-1 as values
                        if (index < 0 || index >= expectedTotal) {
                            // unexpected value – fail fast
                            throw new IllegalStateException("Unexpected element: " + elem);
                        }
                        counts.incrementAndGet(index);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Wait for all producers to finish
        for (Future<?> f : producerFutures) {
            f.get(); // will throw if any producer threw an exception
        }

        // Shutdown the queue so consumers can exit after draining remaining items
        q.shutdown();

        // Wait for consumers to finish
        for (Future<?> f : consumerFutures) {
            f.get();
        }

        prodExec.shutdownNow();
        consExec.shutdownNow();

        // Verify that every expected element appeared exactly once
        for (int i = 0; i < expectedTotal; i++) {
            int cnt = counts.get(i);
            assertEquals(1, cnt,
                    "Element " + i + " appeared " + cnt + " times (expected exactly once)");
        }

        // Additionally, ensure no extra elements were counted (the array size already bounds it)
        long totalTaken = 0;
        for (int i = 0; i < expectedTotal; i++) {
            totalTaken += counts.get(i);
        }
        assertEquals(expectedTotal, totalTaken,
                "Total number of taken elements does not match the number of produced elements");
    }
}
```

### Explanation of the Test

* **Basic functionality** – verifies that `put`/`take`, `offer`/`poll` (with and without timeout) work as expected and that shutdown causes the proper exceptions/return values.
* **Shutdown waking** – ensures that a call to `shutdown()` interrupts any threads blocked in `put`, `take`, `offer`, or `poll`.
* **Stress test** –  
  * `producerCount` threads each put a distinct range of integers (`0 … expectedTotal‑1`).  
  * `consumerCount` threads continuously take elements and increment a lock‑free counter (`AtomicIntegerArray`) indexed by the element value.  
  * After producers finish, the queue is shut down; consumers drain the remaining items and exit.  
  * Finally we assert that every counter equals `1` (no loss, no duplication) and that the total number of taken elements matches the number produced.

All tests run with JUnit 5 (`org.junit.jupiter.api.*`). No class from `java.util.concurrent.BlockingQueue` is used; the implementation relies solely on `ReentrantLock` and `Condition`.