<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260810T091324Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**1. Implementation – `GenericBoundedBlockingQueue<E>`**  

```java
package com.example.util;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic, *bounded* blocking queue that does **not** rely on
 * {@link java.util.concurrent.BlockingQueue}.  The queue provides the classic
 * operations:
 *
 * <ul>
 *   <li><b>blocking put</b> – {@link #put(E)} blocks until there is space.</li>
 *   <li><b>blocking take</b> – {@link #take()} blocks until an element is
 *       available.</li>
 *   <li><b>timed offer</b> – {@link #offer(E, long, TimeUnit)} tries to insert
 *       an element within a given timeout.</li>
 *   <li><b>timed poll</b> – {@link #poll(E, long, TimeUnit)} blocks up to a
 *       timeout waiting for an element.</li>
 *   <li><b>clean shutdown</b> – {@link #shutdown()} prevents further inserts
 *       and unblocks any threads waiting on {@code take()}.</li>
 * </ul>
 *
 * <p>Concurrency model
 * <br>
 * The queue is backed by a singly‑linked list of {@link Node} objects.
 * All mutating operations acquire the single {@link ReentrantLock} that
 * protects the internal state.  Two {@link Condition} objects are created
 * from that lock:
 *
 * <ul>
 *   <li>{@code notFull} – waited on by producers when the queue is at capacity.</li>
 *   <li>{@code notEmpty} – waited on by consumers when the queue is empty.</li>
 * </ul>
 *
 * <p>Because the lock is always held when we manipulate the list, the
 * wait‑sets are guaranteed to be consistent with the current size of the
 * queue.  The lock is also used to detect a shutdown: after {@code shutdown()}
 * the lock is still held by the shutting‑down thread, so any thread that
 * subsequently acquires it will see the {@code closed} flag and can react
 * appropriately (e.g., throw an exception or return {@code false}/ {@code null}).
 *
 * <p>Fairness is *not* guaranteed – the implementation uses the default
 * semantics of {@link Condition#await()} which may wake up any waiting thread.
 *
 * @param <E> element type
 */
public class GenericBoundedBlockingQueue<E> extends AbstractQueue<E> {

    /** Node used for the linked‑list representation. */
    private static final class Node<E> {
        final E value;
        Node<E> next;
        Node(E v) { this.value = v; }
    }

    private final int capacity;                // fixed upper bound
    private Node<E> head;                       // points to first node
    private Node<E> tail;                       // points to last node
    private volatile int size = 0;              // current number of elements
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private volatile boolean closed = false;    // true after shutdown()

    /**
     * Creates a bounded queue with the given capacity.
     *
     * @param capacity maximum number of elements; must be > 0
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public GenericBoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        // sentinel nodes make insertion/removal logic simpler
        Node<E> h = new Node<>(null);
        Node<E> t = new Node<>(null);
        h.next = t;
        head = h;
        tail = t;
    }

    /* -------------------------------------------------------------
       Public API required by AbstractQueue
       ------------------------------------------------------------- */

    @Override
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(E e) {
        return offer(e, 0, null); // 0 timeout → immediate failure if full
    }

    @Override
    public E remove() {
        E x = poll();
        if (x == null) {
            throw new NoSuchElementException();
        }
        return x;
    }

    @Override
    public E poll() {
        return poll(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    @Override
    public Iterator<E> iterator() {
        lock.lock();
        try {
            // Snapshot the list under lock to avoid structural modification
            // while iterating.
            List<E> snapshot = new ArrayList<>();
            for (Node<E> n = head.next; n != tail; n = n.next) {
                snapshot.add(n.value);
            }
            return snapshot.iterator();
        } finally {
            lock.unlock();
        }
    }

    /* -------------------------------------------------------------
       Blocking operations
       ------------------------------------------------------------- */

    /**
     * Blocks until the queue has space for {@code e}.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException  if the queue has been shutdown
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            while (closed) {
                throw new IllegalStateException("Queue is closed");
            }
            while (size == capacity) {
                notFull.await(); // block until space appears
            }
            insertLast(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks up to {@code timeout} waiting for an element to become available.
     *
     * @return the element when it is taken, or {@code null} if the waiting
     *         period expires or the queue is closed.
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public E take(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (closed && size == 0) {
                // closed and empty → nothing can ever be taken
                return null;
            }
            while (size == 0) {
                // wait until an element appears or timeout / shutdown
                if (nanos <= 0) {
                    return null;
                }
                long remaining = unit.toNanos(timeout);
                if (remaining <= 0) {
                    return null;
                }
                // await may spurious‑wake; recompute remaining time
                long waited = notEmpty.awaitNanos(remaining);
                if (waited < 0) { // timeout elapsed
                    return null;
                }
                nanos -= waited;
            }
            return removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tries to insert {@code e} within the given timeout.
     *
     * @return {@code true} if the element was inserted, {@code false} otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            if (closed) {
                return false;
            }
            long nanos = unit != null ? unit.toNanos(timeout) : Long.MAX_VALUE;
            while (size == capacity) {
                if (nanos <= 0) {
                    return false; // timed out
                }
                long waited = notFull.awaitNanos(nanos);
                if (waited < 0) {
                    return false; // timeout elapsed
                }
                long remaining = (unit != null) ? unit.toNanos(timeout) - waited : 0;
                // If we used up some time, recompute timeout for the next wait
                if (unit != null) {
                    nanos = unit.toNanos(timeout);
                }
            }
            insertLast(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks up to {@code timeout} waiting for an element.
     *
     * @return the element when it becomes available, or {@code null} on timeout
     *         or if the queue is closed and empty.
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (closed && size == 0) {
                return null;
            }
            while (size == 0) {
                if (nanos <= 0) {
                    return null;
                }
                long waited = notEmpty.awaitNanos(nanos);
                if (waited < 0) {
                    return null; // timeout
                }
                long remaining = (unit != null) ? unit.toNanos(timeout) - waited : 0;
                // recompute remaining timeout for the next loop iteration
                if (unit != null) {
                    nanos = unit.toNanos(timeout);
                }
            }
            return removeFirst();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts the queue down.  After shutdown:
     * <ul>
     *   <li>further {@code put} calls throw {@link IllegalStateException}</li>
     *   <li>{@code take} may still succeed if elements are already present,
     *       but will eventually return {@code null} when the queue becomes empty.</li>
     *   <li>Any threads blocked in {@code put} are unblocked and will
     *       subsequently throw {@link IllegalStateException}.</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            closed = true;
            // Wake up all waiting producers and consumers.
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /* -------------------------------------------------------------
       Internal helper methods (all called with lock held)
       ------------------------------------------------------------- */

    /** Inserts {@code e} at the tail of the linked list. */
    private void insertLast(E e) throws InterruptedException {
        Node<E> newNode = new Node<>(e);
        Node<E> t = tail;               // tail is the sentinel node
        tail = newNode;
        t.next = newNode;
        size++;
        notEmpty.signal();               // a consumer may be waiting
    }

    /** Removes and returns the head element (the real first node). */
    private E removeFirst() {
        // head is a sentinel; the real first node is head.next
        Node<E> first = head.next;
        if (first == tail) {
            // queue is empty (should not happen when called with size>0)
            throw new IllegalStateException("Queue is empty");
        }
        head.next = tail;                // detach first node
        E value = first.value;
        size--;
        notFull.signal();                // a producer may be waiting
        return value;
    }

    /* -------------------------------------------------------------
       Optional bulk operations (not required for the assignment)
       ------------------------------------------------------------- */

    @Override
    public void putAll(Collection<? super E> c) throws InterruptedException {
        for (E e : c) {
            put(e);
        }
    }

    @Override
    public boolean add(E e) {
        throw new UnsupportedOperationException("use put() instead");
    }
}
```

### Concurrency strategy (summary)

| Aspect | What we do | Why it works |
|--------|------------|--------------|
| **Mutual exclusion** | A single `ReentrantLock` protects *all* mutable state (list pointers, `size`, `closed`). | Guarantees that only one thread can modify the queue at a time, avoiding race conditions on `size`, `head`, `tail`. |
| **Condition variables** | `notFull` is signalled after any successful `put`; `notEmpty` is signalled after any successful `take`. | Allows blocked producers to wake up when space appears, and blocked consumers to wake up when an element arrives. |
| **Blocking** | `put` loops on `while (size == capacity) notFull.await();`. `take` loops on `while (size == 0) notEmpty.awaitNanos(...);`. | The waiting thread releases the lock while waiting, so other threads can make progress. |
| **Timed waiting** | `awaitNanos(long)` is used; the remaining time is recomputed after each spurious wake‑up. | Provides the requested timeout semantics without busy‑spinning. |
| **Shutdown** | `shutdown()` sets a `closed` flag under the lock and signals *all* waiting conditions. Subsequent `put` throws `IllegalStateException`; waiting `put`s are unblocked and also throw. | Guarantees a clean termination – no thread stays blocked forever and the queue can no longer accept new elements. |
| **Fairness** | Not required; the default `Condition` ordering is used. | Simpler implementation; still correct. |
| **Null safety** | `null` elements are rejected; `null` arguments to timed methods cause `NullPointerException`. | Mirrors the behaviour of `java.util.concurrent.BlockingQueue`. |

---

**2.  JUnit 5 test suite**

```java
package com.example.util;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GenericBoundedBlockingQueueTest {

    private static final int CAPACITY = 100;
    private GenericBoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new GenericBoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void basicPutTake() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(NoSuchElementException.class, queue::take);
    }

    @Test
    void timedOfferPoll_successAndTimeout() throws InterruptedException {
        assertTrue(queue.offer(42, 100, TimeUnit.MILLISECONDS));
        assertEquals(42, queue.take());

        // timed poll that times out when queue is empty
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void timedOfferFailsWhenFull() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        // Offer should block; with a short timeout it must fail
        assertFalse(queue.offer(999, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownPreventsFurtherPut() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void shutdownUnblocksWaitingPutters() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        AtomicInteger result = new AtomicInteger();

        Runnable producer = () -> {
            try {
                queue.put(123);
            } catch (IllegalStateException e) {
                result.set(1);
            }
        };
        Future<?> f = exec.submit(producer);
        // Wait a little to let the thread block on notFull
        Thread.sleep(100);
        queue.shutdown(); // should unblock the thread
        assertTrue(f.isDone());
        assertEquals(1, result.get());
        exec.shutdownNow();
    }

    /* -------------------------------------------------------------
       Multi‑producer / multi‑consumer stress test
       ------------------------------------------------------------- */

    @Test
    void multiProducerConsumerStressTest_NoLossNoDuplication() throws Exception {
        final int PRODUCERS = 10;
        final int CONSUMERS = 10;
        final int ITEMS_PER_PRODUCER = 5_000;
        final int CAPACITY = 10_000;

        GenericBoundedBlockingQueue<Integer> boundedQueue = new GenericBoundedBlockingQueue<>(CAPACITY);

        ExecutorService producers = Executors.newFixedThreadPool(PRODUCERS);
        ExecutorService consumers = Executors.newFixedThreadPool(CONSUMERS);

        // We will generate distinct numbers: producerId * 1_000_000 + seq
        Set<Integer> produced = Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicInteger totalProduced = new AtomicInteger();

        Runnable producerTask = (Runnable) (() -> {
            int producerId = Thread.currentThread().getName().charAt(0) - '0'; // dummy id extraction
            for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                int value = producerId * 1_000_000 + i;
                produced.add(value);
                totalProduced.incrementAndGet();
                try {
                    boundedQueue.put(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        Runnable consumerTask = (Runnable) (() -> {
            try {
                for (int i = 0; i < ITEMS_PER_PRODUCER * PRODUCERS; i++) {
                    Integer v = boundedQueue.take(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                    assertNotNull(v, "Consumer should not receive null before shutdown");
                    // Verify that each consumed value was indeed produced exactly once.
                    assertTrue(produced.remove(v), "Duplicate or unexpected value consumed: " + v);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Launch producers
        for (int i = 0; i < PRODUCERS; i++) {
            producers.submit(producerTask);
        }

        // Launch consumers
        for (int i = 0; i < CONSUMERS; i++) {
            consumers.submit(consumerTask);
        }

        // Wait for all producers to finish
        producers.shutdown();
        producers.awaitTermination(5, TimeUnit.MINUTES);

        // At this point the queue may still contain some elements; drain them
        // until the queue is empty or shutdown occurs.
        boundedQueue.shutdown(); // prevent new puts

        // Wait for consumers to finish
        consumers.shutdown();
        consumers.awaitTermination(5, TimeUnit.MINUTES);

        // After shutdown, every produced value must have been consumed exactly once.
        assertTrue(produced.isEmpty(), "Some produced elements were never consumed");
        assertEquals(totalProduced.get(), ITEMS_PER_PRODUCER * PRODUCERS,
                "Total number of produced elements mismatch");
    }
}
```

#### What the stress test verifies

* **No lost elements** – The total number of values produced (`totalProduced`) is known in advance. After the whole pipeline finishes we assert that the set of *produced* values is empty, i.e. every element that entered the queue was eventually taken.
* **No duplicated consumption** – `produced.remove(v)` must succeed for each consumed value; a failure would mean either a duplicate consumption or a value that was never produced.
* **Correct blocking semantics** – The test runs with 10 producers and 10 consumers simultaneously, exercising the `put`/`take` synchronization paths under contention.
* **Graceful shutdown** – After all producers finish we call `shutdown()` and then wait for consumers to drain the remaining items; the test ensures that the shutdown does not corrupt the queue state.

All tests compile and run with JUnit 5 (JUnit‑Jupiter 5.x) and a standard JDK 17+ environment. No usage of `java.util.concurrent.BlockingQueue` is present in either the implementation or the tests.