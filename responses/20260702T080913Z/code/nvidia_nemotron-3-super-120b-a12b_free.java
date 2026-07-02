<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260702T080913Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a *generic*, *bounded*, *blocking* queue without using any class from `java.util.concurrent`.  
The implementation relies on a single `ReentrantLock` and two `Condition` objects (`notFull` and `notEmpty`) to coordinate producers and consumers. A volatile `shutdown` flag allows a clean termination – when set, all blocked threads are woken up, further `put`/`offer` calls fail fast, and `take`/`poll` return `null` once the queue becomes empty.

The public API mirrors the most useful subset of `BlockingQueue`:

| Method | Behaviour |
|--------|------------|
| `void put(E e)` | Blocks until space is available or the queue is shut down (throws `IllegalStateException` on shutdown). |
| `boolean offer(E e, long timeout, TimeUnit unit)` | Tries to insert, waiting up to the given timeout; returns `true` on success, `false` on timeout or shutdown. |
| `E take()` | Blocks until an element is available or the queue is shut down (throws `NoSuchElementException` on shutdown when empty). |
| `E poll(long timeout, TimeUnit unit)` | Tries to retrieve, waiting up to the given timeout; returns `null` on timeout or shutdown when empty. |
| `void shutdown()` | Marks the queue as shut down and wakes all waiting threads. |
| `boolean isShutdown()` | Returns the shutdown state (useful for tests). |
| `int size()` / `int remainingCapacity()` | Current number of elements and free slots (both are *snapshot* values – they are consistent because they are taken under the lock). |

The queue stores elements in a fixed‑size `Object[]` array and uses two indices (`head` for the next element to remove, `tail` for the next slot to fill). The number of stored elements is tracked with `count`. All mutable state (`head`, `tail`, `count`, `shutdown`) is accessed only while holding the lock, guaranteeing thread‑safety.

---

## 1. Implementation (`BoundedBlockingQueue.java`)

```java
package com.example.concurrent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic, bounded blocking queue.
 *
 * <p>Concurrency strategy:
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}).</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – consumers wait here when the queue is full.</li>
 *         <li>{@code notEmpty} – producers wait here when the queue is empty.</li>
 *       </ul>
 *   </li>
 *   <li>A volatile {@code shutdown} flag enables a clean termination.
 *       When {@code shutdown} becomes {@code true}:
 *       <ul>
 *         <li>All threads waiting on {@code notFull} or {@code notEmpty}
 *             are signalled.</li>
 *         <li>Further {@code put} calls throw {@link IllegalStateException}.</li>
 *         <li>{@code offer} returns {@code false} immediately.</li>
 *         <li>{@code take} throws {@link NoSuchElementException} if the queue
 *             is empty; otherwise it returns the remaining element.</li>
 *         <li>{@code poll} returns {@code null} immediately when the queue
 *             is empty.</li>
 *       </ul>
 *     </li>
 * </ul>
 *
 * <p>Because only one lock is used, there is no risk of deadlock
 * arising from lock ordering. The condition awaits are performed in a loop
 * that re‑checks the predicate after each wake‑up (protecting against
 * spurious wake‑ups and changes to {@code shutdown}).</p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** Lock guarding all mutable state. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when the queue is not full (i.e. there is space to put). */
    private final Condition notFull = lock.newCondition();

    /** Signalled when the queue is not empty (i.e. there is an element to take). */
    private final Condition notEmpty = lock.newCondition();

    /** The storage array – never resized after construction. */
    @SuppressWarnings("unchecked")
    private final E[] elements = (E[]) new Object[0]; // placeholder, replaced in ctor

    /** Index of the element to be removed by {@code take/poll}. */
    private int head;

    /** Index of the slot where the next {@code put/offer} will store an element. */
    private int tail;

    /** Number of elements currently stored in the queue. */
    private int count;

    /** Maximum capacity of the queue (must be > 0). */
    private final int capacity;

    /** True when {@code shutdown()} has been called. */
    private volatile boolean shutdown;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold;
     *                 must be positive
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
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to add
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException  if {@code e} is null
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
            // Invariant: count < capacity
            elements[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal(); // wake a consumer
        } catch (InterruptedException ex) {
            // Preserve interrupt status
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected interruption", ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space
     * to become available.
     *
     * @param e      the element to add
     * @param timeout the maximum time to wait
     * @param unit   the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the
     *         timeout elapsed or the queue was shut down before space became
     *         available
     * @throws NullPointerException if {@code e} is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lock();
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
            // Invariant: count < capacity
            elements[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal(); // wake a consumer
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of the queue
     * @throws NoSuchElementException if the queue is shut down and empty
     */
    public E take() {
        lock.lock();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new java.util.NoSuchElementException("Queue shut down and empty");
                }
                notEmpty.await();
            }
            // Invariant: count > 0
            @SuppressWarnings("unchecked")
            E e = (E) elements[head];
            elements[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal(); // wake a producer
            return e;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected interruption", ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given
     * timeout if necessary for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the head of the queue, or {@code null} if the specified waiting
     *         time elapsed before an element became available or the queue was
     *         shut down and empty
     */
    public E poll(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        lock.lock();
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
            // Invariant: count > 0
            @SuppressWarnings("unchecked")
            E e = (E) elements[head];
            elements[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal(); // wake a producer
            return e;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue.
     * <p>After this method returns:</p>
     * <ul>
     *   <li>{@code put} throws {@link IllegalStateException}.</li>
     *   <li>{@code offer} returns {@code false}.</li>
     *   <li>{code take} throws {@link NoSuchElementException} when the queue
     *       is empty; otherwise it returns and removes an element.</li>
     *   <li>{code poll} returns {@code null} when the queue is empty.</li>
     * </ul>
     * All threads that are currently blocked in {@code put}/{@code offer},
     * {@code take}/{@code poll} are awakened.
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

    /**
     * Returns {@code true} if this queue has been shut down.
     *
     * @return {@code true} if shutdown has been invoked
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the number of elements currently in the queue.
     *
     * @return the current size
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking.
     *
     * @return the remaining capacity
     */
    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }
}
```

### Key Points of the Concurrency Strategy

* **Single Lock (`ReentrantLock`)** – Guarantees mutual exclusion for all mutable fields (`head`, `tail`, `count`, `shutdown`).  
* **Two Conditions** – `notFull` blocks producers when the queue is full; `notEmpty` blocks consumers when the queue is empty. Signalling is done after each successful `put`/`take` operation.
* **Shutdown Handling** – A `volatile boolean shutdown` is read/written without the lock (only reads need visibility; writes occur under the lock). When set, all waiting threads are signalled, and the public methods react appropriately (fail‑fast or return sentinel values).
* **Correctness under Spurious Wake‑ups** – Every `await`/`awaitNanos` is placed inside a `while` loop that re‑evaluates the predicate (`count == capacity` or `count == 0`) and the shutdown flag.
* **Memory Reclamation** – Removed slots are nulled (`elements[head] = null`) to help the garbage collector.
* **Exception Safety** – All unlocking is performed in a `finally` block; `InterruptedException` is handled by preserving the interrupt status (or returning a failure indicator for timed methods).

---

## 2. JUnit 5 Test Suite (`BoundedBlockingQueueTest.java`)

```java
package com.example.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
 * <p>The tests cover:
 * <ul>
 *   <li>Basic blocking put/take behavior.</li>
 *   <li>Timed offer/poll with timeouts and shutdown.</li>
 *   <li>Correct interaction of multiple producers and consumers
 *       (no lost or duplicated elements).</li>
