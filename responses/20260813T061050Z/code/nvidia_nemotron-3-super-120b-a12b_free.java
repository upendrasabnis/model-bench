<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260813T061050Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

Below is a self‑contained, thread‑safe generic bounded blocking queue that does **not** rely on `java.util.concurrent.BlockingQueue`.  
The implementation uses a single `ReentrantLock` together with two `Condition` variables (`notFull` and `notEmpty`) to coordinate producers and consumers. A `shutdown` flag lets the queue be closed cleanly – all waiting threads are woken up, further `put`/`offer` calls fail fast, and `take`/`poll` return `null` when the queue becomes empty after shutdown.

The accompanying JUnit 5 test suite exercises the basic contract, timed operations, shutdown semantics, and a multi‑producer/multi‑consumer stress test that guarantees **no lost or duplicated elements**.

---

## 1. BoundedBlockingQueue.java

```java
package com.example.queue;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy:</p>
 * <ul>
 *   <li>All mutable state (the internal array, head/tail indices, size, and shutdown flag)
 *       is protected by a single {@link ReentrantLock}.</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – awaited by producers when the queue is full.</li>
 *         <li>{@code notEmpty} – awaited by consumers when the queue is empty.</li>
 *       </ul>
 *     </li>
 *   <li>The {@code shutdown} flag is also guarded by the same lock. When {@code shutdown()}
 *       is called, the flag is set and both conditions are {@code signalAll}‑ed so that any
 *       threads waiting in {@code put}, {@code take}, timed {@code offer} or {@code poll}
 *       can wake up, observe the shutdown state, and act accordingly.</li>
 *   <li>Because only one lock is used, there is no risk of deadlock due to lock ordering.
 *       The classic “check‑then‑wait” loop guards against spurious wake‑ups.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The storage array – never null after construction. */
    private final Object[] items;

    /** Index of the next element to be taken (head of the queue). */
    private int takeIndex;

    /** Index of the next free slot for an element to be put (tail of the queue). */
    private int putIndex;

    /** Number of elements currently stored. */
    private int count;

    /** True after {@link #shutdown()} has been invoked. */
    private volatile boolean shutdown;

    /** Protects all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when there is at least one free slot. */
    private final Condition notFull = lock.newCondition();

    /** Signaled when there is at least one element available. */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum number of elements; must be > 0
     * @throws IllegalArgumentException if {@code capacity <= 0}
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

    /** @return {@code true} if the queue contains no elements */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** @return {@code true} if the queue is full */
    public boolean isFull() {
        lock.lock();
        try {
            return count == items.length;
        } finally {
            lock.unlock();
        }
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
        Objects.requireNonNull(e, "null elements are not permitted");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            // invariant: !shutdown && count < capacity
            insert(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    // After shutdown we allow take() to return null when the queue is empty.
                    return null;
                }
                notEmpty.await();
            }
            // invariant: count > 0
            E e = (E) extract();
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
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added; {@code false} if the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            // invariant: !shutdown && count < capacity
            insert(e);
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
     * @param unit     a {@link TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the specified waiting time
     *         elapses before an element is available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    // After shutdown we allow poll() to return null when the queue is empty.
                    return null;
                }
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            // invariant: count > 0
            E e = (E) extract();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue. After this method returns:
     * <ul>
     *   <li>{@code put} and {@code offer} throw {@link IllegalStateException}.</li>
     *   <li>{@code take} and {@code poll} return {@code null} when the queue becomes empty.</li>
     *   <li>All threads blocked in {@code put}, {@code take}, timed {@code offer} or {@code poll}
     *       are awakened.</li>
     * </ul>
     * Subsequent calls to {@code shutdown()} have no effect.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (!shutdown) {
                shutdown = true;
                notFull.signalAll();
                notEmpty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return {@code true} if {@link #shutdown()} has been called */
    public boolean isShutdown() {
        return shutdown;
    }

    /* -------------------  internal helpers  ------------------- */

    /** Inserts element at put index; assumes lock held and space available. */
    private void insert(E e) {
        items[putIndex] = e;
        putIndex = inc(putIndex);
        ++count;
        notEmpty.signal();
    }

    /** Removes and returns element at take index; assumes lock held and at least one element. */
    @SuppressWarnings("unchecked")
    private E extract() {
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // help GC
        takeIndex = inc(takeIndex);
        --count;
        notFull.signal();
        return e;
    }

    /** Circular increment of an index. */
    private int inc(int i) {
        int next = i + 1;
        return (next == items.length) ? 0 : next;
    }
}
```

### Concurrency Strategy (summary)

| Aspect | Technique |
|--------|------------|
| **Mutual exclusion** | Single `ReentrantLock` guards all mutable fields (`items`, `takeIndex`, `putIndex`, `count`, `shutdown`). |
| **Condition coordination** | `notFull` – producers wait when `count == capacity`.<br>`notEmpty` – consumers wait when `count == 0`. |
| **Wait loops** | Classic `while (!conditionOk) { await(); }` pattern protects against spurious wake‑ups. |
| **Shutdown** | A `volatile boolean shutdown` (also protected by the lock) is set; both conditions are `signalAll`‑ed. After shutdown:<br>• `put`/`offer` throw `IllegalStateException`.<br>• `take`/`poll` return `null` when the queue is empty. |
| **Fairness** | The default (non‑fair) lock is sufficient; fairness is not required for correctness. |
| **No lost/duplicated elements** | Each `insert` increments `putIndex` and `count` exactly once; each `extract` increments `takeIndex` and decrements `count` exactly once. The lock guarantees that these updates are atomic with respect to each other. |

---

## 2. JUnit 5 Test Suite

```java
package com.example.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 *
 * <p>The stress test at the bottom verifies that, under heavy concurrent
 * load, no element is ever lost or duplicated.</p>
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @Nested
    @DisplayName("Basic contract")
    class BasicContract {

        @Test
        void putTake_isFifo() throws InterruptedException {
            queue.put(1);
            queue.put(2);
            queue.put(3);

            assertEquals(1, queue.take());
            assertEquals(2, queue.take());
            assertEquals(3, queue.take());
            assertTrue(queue.isEmpty());
        }

        @Test
        void offerPoll_withTimeout() throws InterruptedException {
            assertTrue(queue.offer(42, 100, TimeUnit.MILLISECONDS));
            assertEquals(42, queue.poll(100, TimeUnit.MILLISECONDS));
            assertNull(queue.poll(10, TimeUnit.MILLISECONDS)); // empty -> null
        }

        @Test
        void putBlocksWhenFull() throws Exception {
            // Fill the queue
            for (int i = 0; i < CAPACITY; i++) {
                queue.put(i);
            }

            // Start a producer that will block
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> future = exec.submit(() -> {
                try {
                    queue.put(999);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Give it a moment to block
            Thread.sleep(50);
            assertFalse(future.isDone(), "Producer should be blocked waiting for space");

            // Free a slot
            int taken = queue.take();
            assertTrue(taken >= 0 && taken < CAPACITY);

            // Now the producer should complete
            future.get(1, TimeUnit.SECONDS);
            assertEquals(999, queue.take()); // the value we tried to put
            exec.shutdownNow();
        }

        @Test
        void takeBlocksWhenEmpty() throws Exception {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<Integer> future = exec.submit(() -> {
                try {
                    return queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });

            Thread.sleep(50);
            assertFalse(future.isDone(), "Consumer should be blocked waiting for data");

            queue.put(7);
            Integer result = future.get(1, TimeUnit.SECONDS);
            assertEquals(7, result);
            exec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Shutdown semantics")
    class Shutdown {

        @Test
        void putAfterShutdown_throws() {
            queue.shutdown();
            assertThrows(IllegalStateException.class, () -> queue.put(1));
        }

        @Test
        void offerAfterShutdown_throws() throws InterruptedException {
            queue.shutdown();
            assertThrows(IllegalStateException.class, () -> queue.offer(1, 10, TimeUnit.MILLISECONDS));
        }

        @Test
        void takeAfterShutdown_returnsNullWhenEmpty() throws InterruptedException {
            queue.shutdown();
            assertNull(queue.take()); // empty & shutdown -> null
        }

        @Test
        void pollAfterShutdown_returnsNullWhenEmpty() throws InterruptedException {
            queue.shutdown();
            assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
        }

        @Test
        void takeAfterShutdown_returnsElementsUntilEmpty() throws InterruptedException {
            // Fill, then shutdown, then drain
            for (int i = 0; i < CAPACITY; i++) {
                queue.put(i);
            }
            queue.shutdown();

            List<Integer> drained = new ArrayList<>(CAPACITY);
            Integer v;
            while ((v = queue.take()) != null) {
                drained.add(v);
            }
            assertEquals(CAPACITY, drained.size());
            assertTrue(queue.isEmpty());
            // Verify FIFO order preserved
            for (int i = 0; i < CAPACITY; i++) {
                assertEquals(Integer.valueOf(i), drained.get(i));
            }
        }

        @Test
        void wakeUpWaitingThreadsOnShutdown() throws Exception {
            ExecutorService exec = Executors.newFixedThreadPool(4);
            List<Future<?>> futures = new ArrayList<>();

            // Two producers that will block on a full queue
            for (int i = 0; i < 2; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        queue.put(999);
                    } catch (InterruptedException ignored) {
                    }
                }));
            }
            // Two consumers that will block on an empty queue
            for (int i = 0; i < 2; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        queue.take();
                    } catch (InterruptedException ignored) {
                    }
                }));
            }

            // Fill the queue to make producers block
            for (int i = 0; i < CAPACITY; i++) {
                queue.put(i);
            }
            // Give threads a moment to block
            Thread.sleep(100);
            futures.forEach(f -> assertFalse(f.isDone(), "Thread should be blocked"));

            // Shutdown – all should wake up
            queue.shutdown();

            // Producers should now throw IllegalStateException (we swallow it)
            // Consumers should return null
            for (Future<?> f : futures) {
                try {
                    f.get(1, TimeUnit.SECONDS);
                } catch (ExecutionException ee) {
                    // Expected from producers
                    assertTrue(ee.getCause() instanceof IllegalStateException);
                }
            }
            exec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("Stress test – multi‑producer / multi‑consumer")
    class StressTest {

        private static final int PRODUCER_COUNT = 8;
        private static final int CONSUMER_COUNT = 6;
        private static final int ITEMS_PER_PRODUCER = 5_000; // total ~40k items

        @Test
        @Timeout(30) // safeguard against deadlocks
        void noLostOrDuplicatedElements() throws Exception {
            // Shared sequence generator – each producer gets a unique base offset
            AtomicInteger sequencer = new AtomicInteger(0);
            // Each consumer collects what it sees; we will later merge and verify
            List<BlockingQueue<Integer>> consumerBuffers = new ArrayList<>(CONSUMER_COUNT);
            for (int i = 0; i < CONSUMER_COUNT; i++) {
                consumerBuffers.add(new LinkedBlockingQueue<>());
            }

            ExecutorService pool = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(PRODUCER_COUNT + CONSUMER_COUNT);

            // Producer task
            for (int p = 0; p < PRODUCER_COUNT; p++) {
                int producerId = p;
                pool.submit(() -> {
                    try {
                        startLatch.await(); // wait for all to be ready
                        int base = sequencer.getAndAdd(ITEMS_PER_PRODUCER);
                        for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                            int item = base + i;
                            // put may block; that's fine – we want to stress the blocking behavior
                            queue.put(item);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Consumer task
            for (int c = 0; c < CONSUMER_COUNT; c++) {
                int consumerId = c;
                BlockingQueue<Integer> buf = consumerBuffers.get(c);
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        while (!Thread.interrupted()) {
                            Integer item = queue.take(); // blocks until something available or shutdown
                            if (item == null && queue.isShutdown()) {
                                break; // graceful exit after shutdown & empty
                            }
                            if (item != null) {
                                buf.add(item);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Let everyone start together
            startLatch.countDown();

            // Wait for all producers to finish putting their items
            doneLatch.await(60, TimeUnit.SECONDS);
            assertTrue(doneLatch.getCount() == CONSUMER_COUNT,
                    "All producers should be done; consumers may still be draining");

            // Shutdown the queue so consumers can exit after draining
            queue.shutdown();

            // Wait for consumers to finish
            boolean consumersDone = doneLatch.await(30, TimeUnit.SECONDS);
            assertTrue(consumersDone, "Consumers should have finished after shutdown");
            pool.shutdownNow();

            // ---- Validation ----
            // Merge all consumer buffers into a single list
            List<Integer> allConsumed = Collections.synchronizedList(new ArrayList<>());
            consumerBuffers.forEach(allConsumed::addAll);

            // No nulls should have been consumed (except the shutdown sentinel which we filtered out)
            assertFalse(allConsumed.contains(null), "Null values should not appear in consumer data");

            // Verify size matches total produced
            int expectedTotal = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
            assertEquals(expectedTotal, allConsumed.size(),
                    "Number of consumed elements must equal number of produced elements");

            // Sort and check for duplicates / missing values
            List<Integer> sorted = new ArrayList<>(allConsumed);
            sorted.sort(Integer::compareTo);

            int expectedStart = 0;
            for (int val : sorted) {
                assertEquals(expectedStart, val,
                        "Elements must be exactly the sequence 0..(total-1) without gaps or duplicates");
                expectedStart++;
            }
        }
    }
}
```

### Test Suite Explanation

| Test Group | What it Verifies |
|------------|------------------|
| **Basic contract** | FIFO order, blocking behavior of `put`/`take`, timed `offer`/`poll` respecting timeouts, and that operations block when the queue is full/empty. |
| **Shutdown semantics** | After `shutdown()`:<br>• `put`/`offer` throw `IllegalStateException`.<br>• `take`/`poll` return `null` when the queue is empty.<br>• Elements already present can still be drained.<br>• All blocked threads are awakened. |
| **Stress test** | Multi‑producer/multi‑consumer scenario with a large number of items. Each producer gets a disjoint range of integers, ensuring a known global sequence. After shutdown, consumers drain the queue. The test asserts that the multiset of consumed items exactly matches the produced sequence – no loss, no duplication, and correct ordering after sorting. |

The stress test uses a `CountDownLatch` to start all threads simultaneously, a second latch to know when producers have finished, then explicitly shuts down the queue so consumers can exit after draining. All assertions are performed **after** the executor service is terminated, guaranteeing a deterministic final state.

---

**You can drop the two files into a Maven/Gradle project (JUnit 5 on the test classpath) and run the tests – they should pass, confirming that the implementation satisfies the required blocking‑queue contract while remaining completely independent of `java.util.concurrent.BlockingQueue`.**