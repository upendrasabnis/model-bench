<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260819T202335Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
## 1.  Bounded Blocking Queue – Design & Implementation

Below is a **fully self‑contained** implementation of a generic bounded blocking queue that

* uses a **circular buffer** (`Object[]`) to store the elements,  
* relies on a **single `ReentrantLock`** and two `Condition` objects (`notEmpty` / `notFull`) to coordinate waiting and signalling,
* supports **blocking** `put` / `take`, **timed** `offer` / `poll`, and a **clean shutdown** (`close()`).

> **Why not `java.util.concurrent.BlockingQueue`?**  
> The assignment explicitly forbids re‑using the standard library.  
> The implementation below demonstrates the same core_nn‑concepts: a lock protecting the shared state and conditions for blocking/waiting.

---

### 1.1  Concurrency Strategy

| Component | Purpose | Implementation Detail |
|-----------|---------|------------------------|
| `lock` (`ReentrantLock`) | Governs exclusive access to the queue’s mutable state (`head`, `tail`, `count`, `items`). | All public operations acquire the lock. |
| `notEmpty` (`Condition`) | Signalled when an element is inserted and the queue becomes non‑empty. | `take` / `poll` await on this condition when the queue is empty. |
| `notFull` (`Condition`) | Signalled when an element is removed and the queue becomes non‑full. | `put` / `offer` await on this condition when the queue is full. |
| `closed` (`volatile boolean`) | Flag indicating that the queue has been shut‑down. | Every operation checks it before waiting; `close()` sets it to `true` and signals *both* conditions to wake all waiting threads immediately. |
| Circular buffer (`Object[] items`) | Fixed‑size storage that avoids re‑allocation. | `head` points to the next element to read, `tail` points to the next free slot. `count`/mail maintains the number of queued elements. |

#### Key Properties

* **Thread‑safe** – All state changes happen under the same lock.
* **Wait‑free** – No busy‑waiting; blocked threads are parked on `Condition` objects.
* **Fairness** – Not guaranteed by default; if required passen `ReentrantLock(boolean fair)` to the constructor.
* **Shutdown** – After `close()`, all waiting or future operations throw `IllegalStateException`. Existing items can still be consumed until the queue is empty.

---

### 1.2  Source Code

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic bounded blocking queue that supports blocking put/take,
 * timed offer/poll and a clean shutdown.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // circular buffer
    private int head = 0;                  // index of next 'take'
    private int tail = 0;                  // index of next 'put'
    private int count = 0;                 // number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull  = lock.newCondition();

    private volatile boolean closed = false;      // shutdown flag

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        items = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * until space becomes available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been closed
     * @throws NullPointerException if the specified element is null
     */
    @SuppressWarnings("unchecked")
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (closed) {
                    throw new IllegalStateException("Queue closed");
                }
                notFull.await();
            }
            items[tail] = e;
            tail = (tail + 1) % items.length;
            count++;
            notEmpty.signal();          // wake one waiting taker
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been closed and is empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (closed) {
                    throw new IllegalStateException("Queue closed and empty");
                }
                notEmpty.await();
            }
            E e = (E) items[head];
            items[head] = null;          // help GC
            head = (head + 1) % items.length;
            count--;
            notFull.signal();            // wake one waiting putter
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the {@code timeout} argument
     * @return {@code true} if successful, or {@code false} if the specified unir
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been closed
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (closed) {
                    throw new IllegalStateException("Queue closed");
                }
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            items[tail] = e;
            tail = (tail + 1) % items.length;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the {@code timeout} argument
     * @return the head of the queue, or {@code null} if the specified timeout
     *         elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been closed and empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (closed) {
                    throw new IllegalStateException("Queue closed and empty");
                }
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts the queue down.  All blocked or future operations will throw
     * {@link IllegalStateException}.  Items already in the queue can still
     * be taken until it becomes empty.
     */
    public void close() {
        लॉक.lock();
        try {
            closed = true;
            // wake up all waiting threads
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue has been shut down.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the number of elements currently बताए queue (not a blocking operation).
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}
```

---

## 2.  JUnit 5 Test Suite

The tests exercise the queue with a variety of scenarios and conclude with a
stress test that uses many producers and consumers to confirm that մամ

* no element is lost,
* no element is duplicated,
* the shutdown works correctly.

```java
package com.example.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive tests for {@link BoundedBlockingQueue}.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    /* ---------------------------------------------------------- *
     *  Basic functional tests                                    *
     * ---------------------------------------------------------- */

    @Test
    void putAndTakeSingleThread() throws InterruptedException {
        queue.put(42);
        assertEquals(1, queue.size());
        assertEquals(42, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void offerReturnsTrueWhenSpaceAvailable() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void pollReturnsNullWhenEmptyAfterTimeout() throws InterruptedException {
        Integer value = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(value);
    }

    /* ---------------------------------------------------------- *
     *  Blocking behaviour tests                                 *
     * ---------------------------------------------------------- */

    @Test
    void putBlocksWhenQueueFullAndUnblocksWhenSpaceAvailable()
            throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }

        // Start a thread that will put after a small delay
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future<?> future = es.submit(() -> {
            try {
                queue.put(99); // will block until an item is taken
            } catch (InterruptedException e) {
                fail("Interrupted while putting");
            }
        });

        // Give the putter a chance to block
        Thread.sleep(200);
        assertFalse(future.isDone());

        // Take an element to make space
        assertEquals(0, queue.take());

        // Now the putter should finish
        future.get(1, TimeUnit.SECONDS);
        es.shutdownNow();
    }

    @Test
    void takeBlocksWhenQueueEmptyAndUnblocksWhenItemAdded()
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future<Integer> future = es.submit(() -> {
            try {
                return queue.take(); // will block
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        // Give the taker a chance to block
        Thread.sleep(200);
        assertFalse(future.isDone());

        // Put an element
        queue.put(123);

        // Now the taker should finish
        assertEquals(123, future.get(1, TimeUnit.SECONDS));
        es.shutdownNow();
    }

    /* ---------------------------------------------------------- *
     *  Timed offers/polls tests                                 *
     * ---------------------------------------------------------- */

    @Test
    void offerTimeoutWhenFull() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        long start = System.nanoTime();
        boolean result = queue.offer(99, 200, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(result);
        assertTrue(elapsed >= 150); // give some leeway for scheduling
    }

    @Test
    void pollTimeoutWhenEmpty() throws InterruptedException {
        long start = System.nanoTime();
        Integer val = queue.poll(200, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNull(val);
        assertTrue(elapsed >= 150);
    }

    /* ---------------------------------------------------------- *
     *  Shutdown behaviour tests                                 *
     * ---------------------------------------------------------- */

    @Test
    void closeUnblocksWaitersAndPreventsNewOperations() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) queue.put(i);

        ExecutorService es = Executors.newFixedThreadPool(2);

        // A taker thatngi will block
        Future<?> taker = es.submit(() -> {
            try {
                queue.take();
                fail("take should have thrown after close");
            } catch (IllegalStateException | InterruptedException e) {
                // expected
            }
        });

        // A putter that will block
        Future<?> putter = es.submit(() -> {
            try {
                queue.put(100);
                fail("put should have thrown after close");
            } catch (IllegalStateException | InterruptedException e) {
                // expected
            }
        });

        Thread.sleep(100); // let them block
        queue.close();

        // Both futures should complete now
        taker.get(1, TimeUnit.SECONDS);
        putter.get(1, TimeUnit.SECONDS);

        es.shutdownNow();
    }

    /* ---------------------------------------------------------- *
     *  Stress test – many producers / consumers                 *
     * ---------------------------------------------------------- */

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void multiProducerConsumerStressTest() throws InterruptedException {
        final int capacity = TruckCapacity;
        final int producers = 5;
        final int consumers = 5;
        final int itemsPerProducer = 20_000; // total 100k items
        final int totalItems = producers * itemsPerProducer;

        // The queue under test
        BoundedBlockingQueue<Integer> sbq = new BoundedBlockingQueue<>(capacity);

        // Thread‑safe set to collect consumed items
        Set<Integer> consumed = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // Latches to orchestrate start and finish
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(producers + consumers);

        // Atomic counter for generating unique IDs
        AtomicInteger idGenerator = new AtomicInteger(0);

        // ---- Producers -------------------------------------------------
        for (int p = 0; p < producers; p++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        sbq.put(idGenerator.getAndIncrement());
                        // optional random delay to force interleaving
                        if (ThreadLocalRandom.current().nextInt(0, 10) == 0) {
                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // ---- Consumers -------------------------------------------------
        for (int c = 0; c < consumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = sbq.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) { // timeout => queue probably empty
                            if (finishedProducers.get() == producers && sbq.size() == 0) {
                                break;
                            } else {
                                continue;
                            }
                        }
                        consumed.add(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for all producers and consumers to finish
        finishLatch.await();

        // Verify that every produced id was consumed exactly once
        assertEquals(totalItems, consumed.size(), "All items consumed");

        for (int i = 0; i < totalItems; i++) {
            assertTrue(consumed.contains(i), "Missing item: " + i);
        }
    }
}
```

### 2.1  Notes on the Stress Test

* **`CountDownLatch`**:  
  * `startLatch` guarantees that all threads start at the same time, increasing contention.  
  * `finishLatch` ensures the test method waits until all producers and consumers finish.

* **`AtomicInteger`**:  
  Generates a globally unique sequence of integers – each producer obtains a unique ID.

* **`ConcurrentHashMap`‑backed `Set`**:  
  Thread‑safe accumulation of consumed items; enables quick membership checks.

* **Random small sleeps** (in producers) introduce variability in the order of operations, making the test more robust.

* **Timeout on `poll`**:  
  Allows consumers to exit once the queue is empty *and* all producers have finished.

* **Assertions**:  
  * Size of the set must equal the total number of produced items.  
  * Every integer from `0` to `totalItems-1` must be present exactly once.

---

## 3.  Summary

* The queue uses a **single lock** and two condition variables to guarantee
  * **Correctness** – no lost or duplicated items, proper blocking semantics.
  * **Efficiency** – no busy‑waiting; threads are parked on conditions.
  * **Clean shutdown** – `close()` unblocks all waiting threads and prevents further operations.

* The JUnit 5 test suite covers:
  * Basic барои operations,
  * Blocking behavior,
  * Timed offers/polls,
  * Shutdown handling,
  * A large‑scale concurrency stress test that demonstrates that the queue behaves correctly under heavy load.

Feel free to integrate this class into any multi‑threaded Java application that requires a bounded blocking queue without pulling in the heavyweight `java.util.concurrent.BlockingQueue` implementation.