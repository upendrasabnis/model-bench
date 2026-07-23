<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260723T072749Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
## 1.  প্রথমে –  **Concurrency Strategy**

| Concept | How it is used in the implementationài |
|---|---|
|/movie  **ReentrantLock** |  All public mutating methods acquire a single lock (`lock`).  This guarantees that only one thread can modify the underlying circular buffer at a time. |
| **Condition variables** | Two conditions are created from the lock: <br>• `notFull` – signaled when a consumer removes an element.<br>• `notEmpty` – signaled when a producer inserts an element.  Each blocking method waits on the appropriate condition.  |
| **Circular buffer** |  A plain array (`Object[] items`) together with two indices (`head`, `tail`) implements the queue.  The array size is immutable and represents the capacity.  |
| **Shutdown flag** |  A `volatile boolean` (`shutdown`) indicates that the queue has been shutdown.  All methods check this flag *before* proceeding.  When the queue is shutdown the flag is set to `true` and **all** waiting threads are signalled (`notEmpty.signalAll(); notFull.signalAll();`).  |
| **Spurious wake‑ups** |  All `await()` calls are wrapped in `while` loops that re‑check the loop condition (queue empty/full or shutdown).  |
| **InterruptedException** |  All blocking methods propagate `InterruptedException` to the caller.  If the queue is shutdown while a thread is waiting, the thread receives an `IllegalStateException` instead of a normal return value. |

The above guarantees:

* **Mutual exclusion** – at most one thread can mutate the buffer at a time.  
* **Wait‑notify correctness** – producers wait only when the queue is full, consumers only when it is empty.  
* **No lost or duplicated elements** – because the lock protects the indices Gegen and the conditions ensure that producers/consumers see the correct state.  
* **Clean shutdown** – the shutdown flag stops further operations and wakes all waiting threads so that they can terminate gracefully.

---

## 2.  Source Code – `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Supported operations:</p>
 * <ul>
 *   <li>blocking {@code put} / {@code take}</li>
 *   <li>timed {@code offer} / {@code poll}</li>
 *   <li>clean shutdown – after {@code shutdown()} all operations fail immediately.</li>
 * </ul>
 *
 * <p>Implementation details:</p>
 * <ul>
 *   <li>Uses a single {@link ReentrantLock} and two {@link Condition}s
 *       ({@code notFull} and {@code notEmpty}).</li>
 *   <li>Backed by a circular buffer (array + head/tail indices).</li>
 *   <li>All blocking methods guard against shutdown via a {@code volatile boolean shutdown} flag.</li>
 * </ul>
 *
 * @param <E> element type
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;      // circular buffer
    private final int capacity;        // immutable capacity
    private int head = 0;              // index of next element to take
    private int tail = 0;              // index of next free slot to put
    private int size = 0;              // current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull  = lock.newCondition();

    // shutdown flag – volatile so that reads/writes are immediately visible
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity maximum number of elements the queue can hold
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /* ------------------------------------------------------------------ */
    /*  Public API – blocking methods                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Inserts the specified element into the queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to aku
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (size == capacity) {
                notFull.await();
                checkShutdown();   // re‑check after being signaled
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
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (size == 0) {
                notEmpty.await();
                checkShutdown();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Public API – timed methods                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Inserts the specified element into the queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e element to add
     * @param timeout процессов time to wait
     * @param unit time unit of the timeout argument
     * @return {@code true} if the element was added before the timeout,
     *         {@code false} if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (size == capacity) {
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos);
                checkShutdown();
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout time to wait
     * @param unit time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shutdown
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (size == 0) {
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
                checkShutdown();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Public API – shutdown                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Shuts down the queue.  After this method returns:
     * <ul>
     *   <li>Any blocked {@code put} or {@code take} calls will immediately
     *       throw {@code IllegalStateException}.</li>
     *   <li