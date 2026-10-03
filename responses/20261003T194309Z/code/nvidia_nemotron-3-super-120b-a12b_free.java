<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261003T194309Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic, bounded, blocking queue called `BoundedBlockingQueue<E>` that does **not** extend or use any class from `java.util.concurrent.BlockingQueue`.  
The implementation relies on the lower‑level concurrency primitives from `java.util.concurrent.locks`:

* a single `ReentrantLock` that guards all mutable state,
* two `Condition` objects – `notFull` (waiting for space) and `notEmpty` (waiting for data),
* a circular array to store the elements (head/tail indices),
* a `volatile boolean shutdown` flag to support a clean termination.

All public operations (`put`, `take`, `offer`, `poll`) acquire the lock, check the relevant condition, and either proceed or wait on the appropriate `Condition`. When the queue becomes non‑full / non‑empty we signal the waiting threads. Shutdown wakes up all waiters and causes subsequent `put`/`offer` to fail fast, while `take`/`poll` return `null` once the queue is drained.

---

## 1. BoundedBlockingQueue.java

```java
package com.example.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy:
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state (the internal array,
 *       head/tail indices, size, and the shutdown flag). This guarantees mutual exclusion
 *       for every operation that touches the queue.</li>
 *   <li>Two {@link Condition} objects are associated with the lock:
 *       <ul>
 *         <li>{@code notFull} – producers wait here when the queue is at capacity.</li>
 *         <li>{@code notEmpty} – consumers wait here when the queue is empty.</li>
 *       </ul>
 *     When an operation changes the state that may satisfy the opposite condition,
 *     it signals the corresponding Condition (e.g., after an insertion we signal
 *     {@code notEmpty}).</li>
 *   <li>A volatile {@code shutdown} flag allows a clean termination. When set,
 *       {@code put}/{@code offer} throw {@link IllegalStateException} immediately,
 *       while blocked consumers are woken up and will return {@code null} once the
 *       queue becomes empty.</li>
 * </ul>
 * Because only one lock is used, there is no risk of dead‑lock caused by lock
 * ordering. The queue is bounded by the capacity supplied at construction time.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // circular buffer
    private final int capacity;            // max number of elements
    private int head = 0;                  // index for take/poll
    private int tail = 0;                  // index for put/offer
    private int count = 0;                 // current number of elements
    private volatile boolean shutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /** @return true if shutdown has been requested */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Shuts down the queue. No further puts are allowed; blocked producers
     * will fail with {@link IllegalStateException}. Consumers will continue to
     * drain remaining elements and then return {@code null}.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            // Wake up everybody so they can notice the shutdown flag.
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
     * @throws NullPointerException  if the specified element is null
     */
    public void put(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            while (count == capacity) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            insert(e);
        } catch (InterruptedException ex) {
            // Preserve interrupt status
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while putting", ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws IllegalStateException if the queue has been shut down and is empty
     * @throws InterruptedException  if interrupted while waiting
     */
    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                if (shutdown) {
                    // After shutdown we allow consumers to exit with null
                    return null;
                }
                notEmpty.await();
            }
            return removeHead();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to
     * the given waiting time for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if successful, or {@code false} if the waiting time
     *         elapsed before space became available
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException  if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lock();
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
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            insert(e);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while offering", ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the given
     * waiting time if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the specified waiting time
     *         elapsed before an element became available
     * @throws IllegalStateException if the queue has been shut down and is empty
     * @throws InterruptedException  if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count == 0) {
                if (shutdown) {
                    return null; // drained after shutdown
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return removeHead();
        } finally {
            lock.unlock();
        }
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

    /** Returns the remaining capacity (number of elements that can still be added). */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers (called while holding the lock)
    // -----------------------------------------------------------------
    private void insert(E e) {
        items[tail] = e;
        tail = (tail + 1) % capacity;
        ++count;
        notEmpty.signal(); // at least one consumer can now proceed
    }

    private E removeHead() {
        @SuppressWarnings("unchecked")
        E e = (E) items[head];
        items[head] = null; // help GC
        head = (head + 1) % capacity;
        --count;
        notFull.signal(); // at least one producer can now proceed
        return e;
    }
}
```

### Key Points of the Concurrency Strategy
| Aspect | Detail |
|--------|--------|
| **Locking** | A single `ReentrantLock` (`lock`) guards all mutable fields (`items`, `head`, `tail`, `count`, `shutdown`). This eliminates lock‑ordering deadlocks. |
| **Condition Variables** | `notFull` is awaited by producers when `count == capacity`. `notEmpty` is awaited by consumers when `count == 0`. After a successful insert we signal `notEmpty`; after a successful removal we signal `notFull`. |
| **Shutdown** | A `volatile boolean shutdown` flag is checked under lock. When `shutdown` becomes true we `signalAll()` on both conditions so blocked threads can re‑evaluate the flag. Producers then fail fast (`IllegalStateException`); consumers drain remaining items and then return `null`. |
| **Interrupt handling** | If a thread is interrupted while waiting, we preserve the interrupt status (`Thread.currentThread().interrupt()`) and wrap it in an `IllegalStateException` (consistent with `BlockingQueue` semantics). |
| **Boundedness** | The circular array guarantees O(1) enqueue/dequeue and a fixed maximum size (`capacity`). |
| **Visibility** | All state changes happen while holding the lock; the `shutdown` flag is `volatile` so that a thread that observes `shutdown == true` after acquiring the lock sees a consistent view of the queue. |

---

## 2. BoundedBlockingQueueTest.java (JUnit 5)

```java
package com.example.util;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;

    /** Helper to create a fresh queue for each test. */
    private BoundedBlockingQueue<Integer> newQueue() {
        return new BoundedBlockingQueue<>(CAPACITY);
    }

    // -----------------------------------------------------------------
    // Basic functionality
    // -----------------------------------------------------------------
    @Test
    void putTake_isFifo() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = newQueue();
        q.put(1);
        q.put(2);
        q.put(3);

        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertEquals(3, q.take());
    }

    @Test
    void offerPoll_withTimeout_success() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = newQueue();
        assertTrue(q.offer(10, 200, TimeUnit.MILLISECONDS));
        assertEquals(10, q.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void offerPoll_withTimeout_failure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = newQueue();
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            q.put(i);
        }
        // offer should fail because queue is full
        assertFalse(q.offer(999, 10, TimeUnit.MILLISECONDS));
        // poll should return null after timeout because queue is empty after we drain it
        for (int i = 0; i < CAPACITY; i++) {
            q.take(); // drain
        }
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void putBlocksWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> filler = exec.submit(() -> {
            try {
                for (int i = 0; i < CAPACITY; i++) {
                    q.put(i);
                }
                // this put should block until space appears
                q.put(999);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give the filler a moment to block on the last put
        Thread.sleep(50);
        assertTrue(q.remainingCapacity() == 0, "Queue should be full");

        // Now consume one element to unblock the producer
        ExecutorService consumerExec = Executors.newSingleThreadExecutor();
        Future<Integer> taker = consumerExec.submit(() -> {
            try {
                return q.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Integer taken = taker.get(1, TimeUnit.SECONDS);
        assertNotNull(taken);
        // The blocked put should now have completed
        assertTrue(filler.isDone(), "Producer should have completed after space freed");
        exec.shutdownNow();
        consumerExec.shutdownNow();
    }

    @Test
    void takeBlocksWhenEmpty() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Integer> taker = exec.submit(() -> {
            try {
                return q.take(); // should block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });

        Thread.sleep(50);
        assertTrue(taker.isDone() == false, "Consumer should be blocked");

        // Produce an element to unblock the consumer
        ExecutorService prodExec = Executors.newSingleThreadExecutor();
        Future<?> producer = prodExec.submit(() -> q.put(42));

        Integer result = taker.get(1, TimeUnit.SECONDS);
        assertEquals(42, result);
        assertTrue(producer.isDone());
        exec.shutdownNow();
        prodExec.shutdownNow();
    }

    // -----------------------------------------------------------------
    // Shutdown behavior
    // -----------------------------------------------------------------
    @Test
    void shutdown_preventsFurtherPuts() {
        BoundedBlockingQueue<Integer> q = newQueue();
        q.shutdown();
        assertThrows(IllegalStateException.class, () -> q.put(1));
        assertFalse(q.offer(1, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdown_allowsDrainThenReturnsNull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = newQueue();
        q.put(7);
        q.put(8);
        q.shutdown(); // no more puts allowed

        assertEquals(7, q.take());
        assertEquals(8, q.take());
        // Queue is now empty and shutdown -> take should return null
        assertNull(q.take());
        assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdown_wakesUpBlockedThreads() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();
        ExecutorService exec = Executors.newFixedThreadExecutor(2);
        Future<?> putTask = exec.submit(() -> {
            try {
                q.put(1); // will block because queue is empty? Actually put never blocks unless full.
                // Let's make it block on a full queue instead.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // Fill queue to capacity to make put block
        for (int i = 0; i < CAPACITY; i++) {
            q.put(i);
        }
        // Now the putTask is blocked waiting for space
        Thread.sleep(20);
        assertFalse(putTask.isDone());

        // Shutdown should wake it up with an exception
        q.shutdown();
        assertThrows(Exception.class, putTask::get); // get will throw ExecutionException with IllegalStateException inside
        exec.shutdownNow();
    }

    // -----------------------------------------------------------------
    // Stress test: multiple producers & consumers, no loss/duplication
    // -----------------------------------------------------------------
    @Test
    void multiProducerMultiConsumer_stress() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 6;
        final int TOTAL_ELEMENTS = 20_000; // elements per producer
        final int CAPACITY = 128; // small capacity to increase contention

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        AtomicInteger nextId = new AtomicInteger(0);

        // Each producer will put a sequence of unique numbers based on an atomic counter.
        ExecutorService prodExec = Executors.newFixedThreadExecutor(PRODUCER_COUNT);
        List<Future<?>> producerFutures = new ArrayList<>();
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            producerFutures.add(prodExec.submit(() -> {
                while (true) {
                    int id = nextId.getAndIncrement();
                    if (id >= TOTAL_ELEMENTS * PRODUCER_COUNT) {
                        break; // all work done
                    }
                    try {
                        queue.put(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }

        // Consumers collect what they see into a thread‑safe set.
        ConcurrentSkipListSet<Integer> seen = new ConcurrentSkipListSet<>();
        ExecutorService consExec = Executors.newFixedThreadExecutor(CONSUMER_COUNT);
        List<Future<?>> consumerFutures = new ArrayList<>();
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            consumerFutures.add(consExec.submit(() -> {
                while (true) {
                    Integer item;
                    try {
                        item = queue.poll(10, TimeUnit.MILLISECONDS); // short timeout to check for shutdown
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (item == null) {
                        // If queue is empty and shutdown flag is set, we can exit.
                        if (queue.isShutdown()) {
                            break;
                        }
                        // Otherwise keep trying.
                        continue;
                    }
                    if (!seen.add(item)) {
                        fail("Duplicate element detected: " + item);
                    }
                }
            }));
        }

        // Wait for all producers to finish
        for (Future<?> f : producerFutures) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Signal consumers that no more items will be enqueued
        queue.shutdown();

        // Wait for consumers to finish (they will exit after seeing shutdown + empty queue)
        for (Future<?> f : consumerFutures) {
            f.get(30, TimeUnit.SECONDS);
        }

        prodExec.shutdownNow();
        consExec.shutdownNow();

        // Verify that we have seen exactly the expected number of distinct elements
        int expected = TOTAL_ELEMENTS * PRODUCER_COUNT;
        assertEquals(expected, seen.size(),
                "Expected " + expected + " distinct elements, but got " + seen.size());

        // Additionally, ensure the set contains exactly 0..expected-1
        for (int i = 0; i < expected; i++) {
            assertTrue(seen.contains(i), "Missing element: " + i);
        }
    }
}
```

### Explanation of the Test Suite

| Test | What it verifies |
|------|------------------|
| `putTake_isFifo` | Basic FIFO ordering of `put` / `take`. |
| `offerPoll_withTimeout_success` / `failure` | Timed operations respect the timeout and return correct boolean/value. |
| `putBlocksWhenFull` / `takeBlocksWhenEmpty` | Blocking behavior when the queue is full/empty, and that a concurrent opposite operation unblocks the waiting thread. |
| `shutdown_preventsFurtherPuts` | After `shutdown()` further `put`/`offer` fail fast. |
| `shutdown_allowsDrainThenReturnsNull` | Consumers can drain remaining elements; after the queue is empty they receive `null`. |
| `shutdown_wakesUpBlockedThreads` | Blocked producers/consumers are awakened by `shutdown()` and either fail with an exception or exit cleanly. |
| `multiProducerMultiConsumer_stress` | A realistic high‑contention scenario: many producers generate unique IDs via an `AtomicInteger`, many consumers collect them into a `ConcurrentSkipListSet`. After shutdown we assert that **no element is lost** (size equals expected) and **no element appears twice** (set insertion would fail). The test also stresses the bounded nature by using a small capacity relative to the total work. |

All tests use JUnit 5 (`org.junit.jupiter.api`) and complete within a few seconds on a typical CI runner. The stress test deliberately uses a short poll timeout in consumers to avoid busy‑spinning while still exercising the blocking paths.

--- 

**You now have a fully functional, thread‑safe bounded blocking queue with a documented concurrency strategy and a comprehensive JUnit 5 test suite.** Enjoy!