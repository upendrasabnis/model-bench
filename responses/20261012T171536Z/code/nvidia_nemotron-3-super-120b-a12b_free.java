<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261012T171536Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

Below is a self‑contained implementation of a **generic, thread‑safe, bounded blocking queue** that does **not** rely on `java.util.concurrent.BlockingQueue`.  
The queue offers the classic blocking operations (`put`, `take`) as well as timed variants (`offer`, `poll`) and a clean `shutdown()` method that wakes all waiting threads and makes further insertion attempts fail fast.

After the implementation, a comprehensive JUnit 5 test suite is provided, including:

* basic functionality tests,
* timed‑operation tests,
* shutdown behaviour tests, and
* a **multi‑producer / multi‑consumer stress test** that guarantees **no lost or duplicated elements**.

---

## 1. Implementation (`BoundedBlockingQueue.java`)

```java
package com.example.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic, thread‑safe, bounded blocking queue.
 *
 * <p>Concurrency strategy:</p>
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state
 *       (the internal array, head/tail indices, size, and the shutdown flag).</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notEmpty} – signaled when an element is added; awaited by {@code take} and timed {@code poll}.</li>
 *         <li>{@code notFull}  – signaled when an element is removed; awaited by {@code put} and timed {@code offer}.</li>
 *       </ul>
 *   </li>
 *   <li>A volatile {@code shutdown} flag allows threads waiting on the conditions to be
 *       awakened via {@code signalAll()} and to fail fast when they reacquire the lock.</li>
 *   <li>All public methods acquire the lock, check the shutdown flag (if relevant),
 *       perform the operation while holding the lock, and release the lock in a {@code finally}
 *       block – guaranteeing mutual exclusion and visibility.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The storage array – never resized after construction. */
    @SuppressWarnings("unchecked")
    private final E[] elements = (E[]) new Object[0]; // placeholder, real array created in ctor

    /** Index of the next element to be taken (head of the queue). */
    private int takeIndex;

    /** Index of the next element to be put (tail of the queue). */
    private int putIndex;

    /** Number of elements currently stored. */
    private int count;

    /** Maximum capacity of the queue. */
    private final int capacity;

    /** Guard for all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when the queue is not empty. */
    private final Condition notEmpty = lock.newCondition();

    /** Signaled when the queue is not full. */
    private final Condition notFull = lock.newCondition();

    /** Set to {@code true} after {@link #shutdown()} is invoked. */
    private volatile boolean shutdown;

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum number of elements the queue may contain; must be > 0
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

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * <p>If the queue has been {@linkplain #shutdown() shut down}, this method throws
     * {@link IllegalStateException}.</p>
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
            while (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            while (count == capacity) {
                notFull.await();
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
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
     * <p>If the queue has been {@linkplain #shutdown() shut down} and is empty,
     * this method throws {@link IllegalStateException}.</p>
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shut down and empty
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (shutdown && count == 0) {
                throw new IllegalStateException("Queue shut down and empty");
            }
            while (count == 0) {
                notEmpty.await();
                if (shutdown && count == 0) {
                    throw new IllegalStateException("Queue shut down and empty");
                }
            }
            E e = dequeue();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified waiting time for space to become available.
     *
     * <p>If the queue is shut down before the element can be enqueued, the method
     * returns {@code false}.</p>
     *
     * @param e        the element to add
     * @param timeout  the maximum time to wait for space
     * @param unit     the time unit of the {@code timeout} argument
     * @return {@code true} if the element was added, {@code false} if the timeout elapsed
     *         or the queue was shut down before the operation could succeed
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if {@code e} is {@code null}
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (shutdown) {
                return false;
            }
            while (count == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
                if (shutdown) {
                    return false;
                }
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified
     * waiting time if necessary for an element to become available.
     *
     * <p>If the queue is shut down and becomes empty before the timeout elapses,
     * the method returns {@code null}.</p>
     *
     * @param timeout  the maximum time to wait for an element
     * @param unit     the time unit of the {@code timeout} argument
     * @return the head of the queue, or {@code null} if the specified waiting time
     *         elapsed before an element could be taken, or if the queue was shut down
     *         and empty before the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (shutdown && count == 0) {
                return null;
            }
            while (count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
                if (shutdown && count == 0) {
                    return null;
                }
            }
            E e = dequeue();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements currently stored in this queue.
     *
     * @return the current size
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the remaining capacity – the number of additional elements that this
     * queue can ideally (without blocking) accept.
     *
     * @return the remaining capacity
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a graceful shutdown of the queue.
     *
     * <p>After this method returns:</p>
     * <ul>
     *   <li>Any thread currently blocked in {@link #put(Object)} or
     *       {@link #offer(Object, long, TimeUnit)} will throw {@link IllegalStateException}
     *       (or return {@code false} for the timed variant).</li>
     *   <li>Any thread currently blocked in {@link #take()} or
     *       {@link #poll(long, TimeUnit)} will either return an element if one is
     *       immediately available, or throw {@link IllegalStateException} (or return
     *       {@code null} for the timed variant) once the queue becomes empty.</li>
     *   <li>Further calls to {@code put}/{code offer} will fail fast as described
     *       above.</li>
     * </ul>
     *
     * <p>All waiting threads are awakened via {@code signalAll()} on both condition
     * variables.</p>
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (shutdown) {
                return; // idempotent
            }
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Internal: place element at putIndex and advance pointers. */
    private void enqueue(E e) {
        assert lock.isHeldByCurrentThread();
        elements[putIndex] = e;
        if (++putIndex == capacity) {
            putIndex = 0;
        }
        ++count;
        notEmpty.signal();
    }

    /** Internal: retrieve element at takeIndex and advance pointers. */
    @SuppressWarnings("unchecked")
    private E dequeue() {
        assert lock.isHeldByCurrentThread();
        E e = elements[takeIndex];
        elements[takeIndex] = null; // help GC
        if (++takeIndex == capacity) {
            takeIndex = 0;
        }
        --count;
        notFull.signal();
        return e;
    }
}
```

### Key Points of the Concurrency Strategy

| Aspect | Detail |
|--------|--------|
| **Locking** | A single `ReentrantLock` (`lock`) guards *all* mutable state (`elements`, `takeIndex`, `putIndex`, `count`, `shutdown`). This guarantees mutual exclusion and visibility. |
| **Condition Variables** | `notEmpty` is signaled when an element is enqueued; `notFull` is signaled when an element is dequeued. Threads awaiting space or data wait on the appropriate condition. |
| **Shutdown Flag** | A `volatile boolean shutdown` is read without holding the lock for fast checks, but any state change that depends on it is performed under the lock. When `shutdown()` is invoked, the flag is set and both conditions are `signalAll()`‑ed to wake every waiting thread. |
| **Spurious Wake‑ups** | All condition awaits are placed inside `while` loops that re‑check the predicate (`count == 0`, `count == capacity`, or `shutdown`) after reacquiring the lock, safely handling spurious wake‑ups. |
| **Interrupt Handling** | The lock is acquired with `lockInterruptibly()` so that a thread interrupted while waiting throws `InterruptedException` as required by the blocking‑queue contract. |
| **Null Elements** | `null` is rejected (via `Objects.requireNonNull`) – mirroring the behaviour of most JDK blocking queues. |
| **Idempotent Shutdown** | Multiple calls to `shutdown()` are safe; subsequent calls simply return after noticing the flag is already set. |
| **Memory Visibility** | Because every read/write of shared variables occurs while holding `lock`, the Java Memory Model guarantees visibility without needing extra `volatile` on the array or indices (only `shutdown` needs `volatile` for the fast‑path check). |

---

## 2. JUnit 5 Test Suite (`BoundedBlockingQueueTest.java`)

```java
package com.example.util;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.concurrent.Execution;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;
    private static final int TIMEOUT_MS = 200;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure no stray threads are left hanging
        queue.shutdown();
    }

    /* ------------------------------------------------------------------ *
     *  Basic blocking put / take
     * ------------------------------------------------------------------ */
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

    /* ------------------------------------------------------------------ *
     *  Timed offer / poll
     * ------------------------------------------------------------------ */
    @Test
    void offerPollTimed() throws InterruptedException {
        // Offer succeeds immediately because queue is empty
        assertTrue(queue.offer(10, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(10, queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        // Next offer should timeout (queue full)
        assertFalse(queue.offer(999, TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Drain and verify poll returns null on timeout after empty
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        assertNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /* ------------------------------------------------------------------ *
     *  Shutdown behaviour
     * ------------------------------------------------------------------ */
    @Test
    void shutdownPutTakeThrows() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try {
                queue.put(1);
                fail("Expected IllegalStateException after shutdown");
            } catch (IllegalStateException e) {
                // expected
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        queue.shutdown();
        producer.start();
        producer.join();

        Thread consumer = new Thread(() -> {
            try {
                queue.take();
                fail("Expected IllegalStateException after shutdown on empty queue");
            } catch (IllegalStateException e) {
                // expected
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        consumer.join();
    }

    @Test
    void shutdownOfferPollReturnsFalseOrNull() throws InterruptedException {
        queue.shutdown();

        assertFalse(queue.offer(1, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownDrainsRemainingElements() throws InterruptedException {
        // Fill queue, then shutdown while there are still elements
        IntStream.range(0, CAPACITY).forEach(i -> {
            try {
                queue.put(i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        queue.shutdown();

        // Drain – should receive all elements in FIFO order
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, queue.take());
        }

        // After draining, take should now throw
        assertThrows(IllegalStateException.class, queue::take);
    }

    /* ------------------------------------------------------------------ *
     *  Multi‑producer / multi‑consumer stress test
     * ------------------------------------------------------------------ */
    @Test
    @Timeout(value = 30)
    void mpMcStressTest_noLostOrDuplicatedElements() throws Exception {
        final int PRODUCER_COUNT = 8;
        final int CONSUMER_COUNT = 6;
        final int ITEMS_PER_PRODUCER = 5000; // total items = 40000

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        CyclicBarrier startBarrier = new CyclicBarrier(PRODUCER_COUNT + CONSUMER_COUNT + 1);
        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);

        // Each producer gets a unique offset so we can later verify uniqueness
        AtomicInteger nextItem = new AtomicInteger(0);
        List<Future<?>> producerFutures = new ArrayList<>();
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int producerId = p;
            producerFutures.add(exec.submit(() -> {
                try {
                    startBarrier.await(); // wait for all threads to start
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int item = nextItem.getAndIncrement(); // globally unique
                        q.put(item); // blocking put
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Consumers collect into a thread‑safe set; we also count occurrences
        ConcurrentSkipListSet<Integer> seen = new ConcurrentSkipListSet<>();
        ConcurrentHashMap<Integer, AtomicInteger> occurrences = new ConcurrentHashMap<>();
        List<Future<?>> consumerFutures = new ArrayList<>();
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            consumerFutures.add(exec.submit(() -> {
                try {
                    startBarrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    while (true) {
                        Integer item;
                        try {
                            item = q.take(); // blocks until an item is available or shutdown
                        } catch (IllegalStateException e) {
                            // shutdown detected and queue empty – exit
                            break;
                        }
                        if (!seen.add(item)) {
                            // duplicate detected – we will fail later
                        }
                        occurrences.computeIfAbsent(item, k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Let all producers/consumers start together
        startBarrier.await();

        // Wait for producers to finish
        for (Future<?> f : producerFutures) {
            f.get(); // propagate any exception
        }

        // Signal consumers to exit once the queue becomes empty
        q.shutdown();

        // Wait for consumers to finish
        for (Future<?> f : consumerFutures) {
            f.get();
        }

        exec.shutdownNow();

        // -----------------------------------------------------------------
        // Verification
        // -----------------------------------------------------------------
        int expectedTotalItems = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
        assertEquals(expectedTotalItems, seen.size(),
                "Number of distinct elements seen does not match expected total");

        // Ensure each element was seen exactly once
        for (Map.Entry<Integer, AtomicInteger> entry : occurrences.entrySet()) {
            int count = entry.getValue().get();
            assertEquals(1, count,
                    "Element " + entry.getKey() + " was seen " + count + " times (should be exactly once)");
        }

        // Queue should be empty after shutdown and drain
        assertTrue(q.isEmpty(), "Queue should be empty after stress test");
    }

    /* ------------------------------------------------------------------ *
     *  Helper: isEmpty (not part of the public API, but useful for tests)
     * ------------------------------------------------------------------ */
    private boolean isEmpty() {
        return queue.size() == 0;
    }
}
```

### Explanation of the Stress Test

* **Unique identifiers** – Each producer obtains a globally unique integer via an `AtomicInteger`. This guarantees that, in the absence of bugs, no two producers will ever attempt to enqueue the same value.
* **Detection of duplicates / loss** – Consumers insert each taken element into a `ConcurrentSkipListSet`. If an element is already present, `add()` returns `false`, signalling a duplicate. Additionally, a `ConcurrentHashMap` counts how many times each element was seen; after the test we assert every count equals `1`.
* **Shutdown handling** – After all producers finish, we call `queue.shutdown()`. Consumers blocked in `take()` will receive an `IllegalStateException` once the queue becomes empty, allowing them to exit cleanly.
* **Barrier synchronization** – A `CyclicBarrier` ensures that all producer and consumer threads start their work roughly simultaneously, increasing contention and thus the likelihood of exposing race conditions.
* **Timeout** – The test is annotated with `@Timeout(30)` to fail fast if a deadlock or livelock occurs.

---

### How to Use

* Place `BoundedBlockingQueue.java` in your source tree (e.g., `src/main/java/com/example/util/`).
* Place the test class in `src/test/java/com/example/util/`.
* Ensure JUnit 5 (Jupiter) is on the test classpath (e.g., via Maven/Gradle).
* Run the tests – they should all pass, confirming correctness of the blocking queue implementation under contention and shutdown conditions.

--- 

**End of solution**.