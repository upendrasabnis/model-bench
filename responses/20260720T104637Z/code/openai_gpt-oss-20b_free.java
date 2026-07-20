<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260720T104637Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Below is a **fully self‑contained** implementation of a generic bounded blocking queue that fulfils every requirement you listed:

* No use of `java.util.concurrent.BlockingQueue`.
* Blocking `put()` / `take()`.
* Timed `offer()` / `poll()`.
* A clean shutdown that unblocks every waiting thread.
* Thread‑safe and free of lost / duplicated elements.

After the implementation you will find a **complete test‑suite** written with JUnit 5, including a multi‑producer/multi‑consumer stress test that guarantees that no element is lost or duplicated.

---

## 1.  Concurrency strategy (documentation)

| Component | What it does | Why it is used |
|-----------|--------------|----------------|
| `ReentrantLock` (`lock`) | Guards the internal state (`head`, `tail`, `count`, `shutdown`) | Guarantees mutual exclusion when mutating or reading the queue. |
| `Condition notFull` | Signalled when an element is removed – i.e. when the queue becomes *not* full | Allows a blocked `put()` to resume. |
| `Condition notEmpty` | Signalled when an element is added – i.e. when the queue becomes *not* empty | Allows a blocked `take()` to resume. |
| `volatile boolean shutdown` | Indicates that the queue is no longer usable | Allows all blocking operations to terminate immediately on shutdown. |
| `Object[] items` | Circular buffer that stores the elements | Fixed capacity, constant‑time enqueue/dequeue. |
| `int head, tail, count` | Indexes into `items` and the current number of stored elements | Implements the circular‑buffer logic. |

### 1.1  Blocking `put()` / `take()`

Both operations acquire the lock and then enter a loop that checks the queue state:

```java
while (count == capacity && !shutdown) {
    notFull.await();           // Wait until space frees up
}
```

If `shutdown` becomes true while a thread is waiting, the loop exits and the method throws `IllegalStateException`.  
When the state is suitable the element is inserted / removed, the corresponding condition (`notEmpty` / `notFull`) is sign imoded, and the lock is released.

### 1.2  Timed `offer()` / `poll()`

They follow the same pattern but use `awaitNanos()` and a *remaining* timeout value, so the call returns `false` / `null` if the queue remains full / empty when the timeout elapses. The loop also checks `shutdown` so that a shutdown during a timed wait aborts immediately.

### 1.3  Clean shutdown

`shutdown()` does the following:

1. Acquires the lock.
2. Sets `shutdown = true`.
3. Calls `signalAll()` on *both* conditions – this wakes every thread that is blocked in `put()` or `take()`.
4. Releases the lock.

Any thread that wakes up sees `shutdown == true` and throws an `IllegalStateException`.  
No more elements can be added or taken after shutdown.

---

## 2.  Implementation

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe bounded blocking queue that does **not** rely on
 * {@link java.util.concurrent.BlockingQueue}.  It offers the same
 * semantics as the standard interface but with a custom shutdown
 * mechanism that unblocks all waiting threads.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;           // Circular buffer
    private int head = 0;                   // Index of next element to take
    private int tail = 0;                   // Index of next free slot
    private int count = 0;                  // Number of elements in the queue sepak
    private volatile boolean shutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

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
        this.items = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException  if the current thread is interrupted
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length && !shutdown) {
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
     * Inserts the specified element into this queue if it is possible to do so
     * immediately without violating capacity restrictions, returning
     * {@code false} if no space is currently available.  This method does
     * *not* wait for space to become available.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else {@code false}
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            if (count == items.length) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e       the element to add
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if successful, {@code false} if the timeout elapsed
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length && nanos > 0L && !shutdown) {
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            if (count == items.length) {   // timeout elapsed
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            return (E) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return the head of this queue, or {@code null} if the specified waiting time elapses
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0 && nanos > 0L && !shutdown) {
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue has been shut down");
            }
            if (count == 0) {   // timeout elapsed
                return null;
            }
            return (E) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts down the queue, unblocking all waiting threads.  Any further
     * calls to {@code put}/{@code take}/etc. will throw
     * {@code IllegalStateException}.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
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
     * Returns {@code true} if the queue has been shut down.
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the current number of elements in the queue.
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

    /* ----------------------------------------------------------------- */
    /*          Private helpers – always called while holding the lock   */
    /* ----------------------------------------------------------------- */

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % items.length;
        count++;
        notEmpty.signal();          // Wake one waiting taker
    }

    private E dequeue() {
        @SuppressWarnings("unchecked")
        E e = (E) items[head];
        items[head] = null;         // Help GC
        head = (head + 1) % items.length;
        count--;
        notFull.signal();           // Wake one waiting putter
        return e;
    }
}
```

---

## 3.  JUnit 5 test‑suite

The tests cover:

1. **Basic functionality** – single‑producer/single‑consumer, blocking and timed operations.
2. **Shutdown semantics** – ensuring that a shutdown unblocks all waiting threads and any further operation throws.
3. **Stress test** – multiple producers and consumers that together produce a large, unique sequence of integers.  
   The test verifies that every produced element is consumed exactly once – no lost or duplicated items.

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    /* --------------------------------------------------------------------- */
    /*  Utility helpers                                                        */
    /* --------------------------------------------------------------------- */

    private static <E> Set<E> newConcurrentSet() {
        return ConcurrentHashMap.newKeySet();
    }

    /* --------------------------------------------------------------------- */
    /*  Basic single‑producer / single‑consumer tests                         */
    /* --------------------------------------------------------------------- */

    @Test
    void testPutTakeBlocking() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);

        CountDownLatch putDone = new CountDownLatch(1);
        CountDownLatch takeDone = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            try {
                q.put(42);
                putDone.countDown();
            } catch (InterruptedException e) {
                fail("Producer interrupted");
            }
        });

        Thread consumer = new Thread(() -> {
            try {
                int val = q.take();
                assertEquals(42, val);
                takeDone.countDown();
            } catch (InterruptedException e) {
                fail("Consumer interrupted");
            }
        });

        consumer.start();
        Thread.sleep(50);          // make sure consumer is waiting
        producer.start();

        assertTrue(putDone.await(1, TimeUnit.SECONDS));
        assertTrue(takeDone.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);                       // fill the queue

        // Offer with a timeout – should fail after ~200ms
        long start = System.nanoTime();
        boolean ok = q.offer(2, 200, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        assertFalse(ok, "offer should have timed out");
        assertTrue(elapsed >= 200_000_000L, "timeout was too short");
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);

        // Queue is empty – poll with timeout should return null
        long start = System.nanoTime();
        Integer val = q.poll(150, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        assertNull(val, "poll should have timed out");
        assertTrue(elapsed >= 150_000_000L, "timeout was too short");
    }

    /* --------------------------------------------------------------------- */
    /*  Shutdown semantics                                                     */
    /* --------------------------------------------------------------------- */

    @Test
    void testShutdown dishes() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);

        // Queue is now full – any put should block
        Thread blockingPut = new Thread(() -> {
            try {
                q.put(3);   // will block
                fail("put should not succeed after shutdown");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IllegalStateException e) {
                // expected
            }
        });

        blockingPut.start();
        Thread.sleep(50);          // ensure the thread is waiting

        q.shutdown();              // this should unblock the put

        blockingPut.join(1_000);   // give it some time
        assertFalse(blockingPut.isAlive(), "put thread should have terminated");

        // Any further operationadius should fail
        assertThrows(IllegalStateException.class, () -> q.put(4));
        assertThrows(IllegalStateException.class, () -> q.take());
        assertThrows(IllegalStateException.class, unheard-> q.offer(5));
    }

    @Test
    void testShutdownUnblocksAllWaiting() throws InterruptedException {
        final BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // queue is fullFaʻ

        CountDownLatch putBlocked = new CountDownLatch(1);
        CountDownLatch takeBlocked = new CountDownLatch(1);

        Thread putThread = new Thread(() -> {
            try {
                q.put(2);
                fail("put should have been interrupted by shutdown");
            } catch (InterruptedException e) {
                //eine
            } catch (IllegalStateException e) {
                putBlocked.countDown();
            }
        });

        Thread takeThread = new Thread(() -> {
            try {
                q.take();
                fail("take should have been interrupted by shutdown");
            } catch (InterruptedException e![0]) {
                // ok
            } catch (IllegalStateException e) {
                takeBlocked.countDown();
            }
        });

        putThread.start();
        takeThread.start();

        Thread.sleep(50); // ensure both threads are blocked

        q.shutdown();

        assertTrue(putBlocked.await(1L, TimeUnit.SECONDS));
        assertTrue(takeBlocked.await(1L, TimeUnit.SECONDS));
    }

    /* --------------------------------------------------------------------- */
    /*  Stress test – multi‑producer / multi‑consumer                         */
    /* --------------------------------------------------------------------- */

    @Test
    void stressTestMultiProducerConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producers = 4;
