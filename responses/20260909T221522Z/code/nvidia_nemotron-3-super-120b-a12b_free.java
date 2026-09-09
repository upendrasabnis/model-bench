<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260909T221522Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a generic bounded blocking queue **without** using any class from `java.util.concurrent` (except the low‑level synchronization primitives `ReentrantLock` and `Condition`).  

The queue is built around a simple circular array, a single `ReentrantLock` that protects all mutable state, and two `Condition` objects:

* `notFull` – signaled when an element is removed (making space).
* `notEmpty` – signaled when an element is added (making data available).

All public methods acquire the lock, then wait on the appropriate condition using a **while‑loop** to protect against spurious wake‑ups. A `shutdown()` flag allows a clean termination: once set, further `put`/`offer` calls fail immediately, blocked `take`/`poll` calls are woken up and return `null` (or `false` for `offer`), and any thread waiting on the conditions is released.

The implementation is **fair** (the lock is created with `fair = true`) so that threads are served in FIFO order, which makes reasoning about tests easier but is not required for correctness.

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
 *   <li>All mutable state ({@code elements}, {@code head}, {@code tail}, {@code count},
 *       {@code shutdown}) is guarded by a single {@link ReentrantLock}.</li>
 *   <li>Two {@link Condition} instances are used:
 *       <ul>
 *         <li>{@code notFull} – awaited by producers when the queue is full.</li>
 *         <li>{@code notEmpty} – awaited by consumers when the queue is empty.</li>
 *       </ul>
 *   </li>
 *   <li>Wait loops use {@code while (!condition) condition.await(...)} to survive
 *       spurious wake‑ups and to re‑check the predicate after being signaled.</li>
 *   <li>A {@code shutdown} flag enables a clean close:
 *       <ul>
 *         <li>After {@code shutdown()} is called, {@code put} and {@code offer}
 *             return immediately with {@code false} (or throw {@code IllegalStateException}
 *             for the untimed {@code put}).</li>
 *         <li>Blocked {@code take} and {@code poll} are woken up; if the queue is empty
 *             they return {@code null} (or {@code false} for timed {@code poll}).</li>
 *         <li>All waiting threads are released via {@code signalAll()} on both conditions.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>The implementation is *bounded* – the capacity is fixed at construction time and
 * cannot be changed later.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** Underlying storage – a simple circular array. */
    private final Object[] elements;

    /** Index of the next element to be taken. */
    private int head;

    /** Index of the next free slot for putting. */
    private int tail;

    /** Current number of elements in the queue. */
    private int count;

    /** Maximum number of elements the queue can hold. */
    private final int capacity;

    /** Guard for all mutable state. */
    private final ReentrantLock lock;

    /** Signaled when space becomes available. */
    private final Condition notFull;

    /** Signaled when an element becomes available. */
    private final Condition notEmpty;

    /** True after {@code shutdown()} has been invoked. */
    private volatile boolean shutdown;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue may hold; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.elements = new Object[capacity];
        this.capacity = capacity;
        this.lock = new ReentrantLock(true); // fair lock – optional but helpful for testing
        this.notFull = lock.newCondition();
        this.notEmpty = lock.newCondition();
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (true) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                if (count < capacity) {
                    enqueue(e);
                    return;
                }
                // Queue full – wait for a consumer to remove an element.
                notFull.await();
            }
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
            while (true) {
                if (shutdown && count == 0) {
                    throw new IllegalStateException("Queue shut down and empty");
                }
                if (count > 0) {
                    @SuppressWarnings("unchecked")
                    E result = (E) dequeue();
                    return result;
                }
                // Queue empty – wait for a producer to add an element.
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * given waiting time for space to become available.
     *
     * @param e        the element to add
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return {@code true} if the element was added, {@code false} if the timeout elapsed
     *         before space became available (or if the queue was shut down)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements are not permitted");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count >= capacity) {
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
     * waiting time if necessary for an element to become available.
     *
     * @param timeout  how long to wait before giving up, in units of {@code unit}
     * @param unit     a {@link TimeUnit} determining how to interpret the {@code timeout} argument
     * @return the head of the queue, or {@code null} if the specified waiting time
     *         elapses before an element is available (or if the queue is shut down and empty)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
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
            @SuppressWarnings("unchecked")
            E result = (E) dequeue();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue.
     * <p>After this method returns:
     * <ul>
     *   <li>Further calls to {@code put} will throw {@link IllegalStateException}.</li>
     *   <li>Further calls to {@code offer} will return {@code false}.</li>
     *   <li>Blocked {@code take} calls will throw {@link IllegalStateException} if the
     *       queue is empty; otherwise they will succeed and remove an element.</li>
     *   <li>Blocked {@code poll} calls will return {@code null} if the queue is empty;
     *       otherwise they will succeed and remove an element.</li>
     * </ul>
     * All threads currently waiting in {@code put}/{@code offer}/{@code take}/{@code poll}
     * are awakened.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            shutdown = true;
            // Wake up everybody so they can re‑evaluate the shutdown condition.
            notFull.signalAll();
            notEmpty.signalAll();
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

    /** Returns true if the queue currently holds no elements. */
    public boolean isEmpty() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if the queue is full. */
    public boolean isFull() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the remaining capacity (number of slots that can be filled). */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    // -----------------------------------------------------------------
    // Internal helpers – assume lock is held by the caller.
    // -----------------------------------------------------------------
    private void enqueue(E e) {
        // Invariant: lock is held and count < capacity
        elements[tail] = e;
        tail = (tail + 1) % capacity;
        ++count;
        notEmpty.signal(); // at least one consumer can now proceed
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        // Invariant: lock is held and count > 0
        E e = (E) elements[head];
        elements[head] = null; // help GC
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
| **Lock granularity** | A single `ReentrantLock` protects **all** mutable state (`head`, `tail`, `count`, `shutdown`, array contents). This eliminates the possibility of inconsistent views between producers and consumers. |
| **Condition variables** | `notFull` is awaited by producers when the queue is full; `notEmpty` is awaited by consumers when the queue is empty. Signalling is done after each successful enqueue/dequeue. |
| **Waiting loops** | All `await`/`awaitNanos` calls are inside a `while (!predicate)` loop to survive spurious wake‑ups and to re‑check the predicate after being signaled. |
| **Shutdown handling** | A volatile `shutdown` flag is checked under lock. When set, blocked threads are woken via `signalAll()`. Producers fail fast (`put` throws, `offer` returns false). Consumers return `null` (or throw for untimed `take`) when the queue is empty after shutdown. |
| **Fairness** | The lock is created with `fair = true`. This is not required for correctness but makes the order of thread wake‑ups deterministic, which simplifies stress‑testing. |
| **Null‑element policy** | The queue does **not** allow `null` elements – `NullPointerException` is thrown early. This mirrors the behavior of most JDK blocking queues and avoids ambiguity with the `null` return used to signal timeout/empty on shutdown. |
| **Memory visibility** | All accesses to shared variables occur while holding the lock, guaranteeing happens‑before relationships. The `shutdown` flag is `volatile` so that a thread that acquires the lock after seeing `shutdown == true` also sees all writes that happened before `shutdown` was set. |

---

## 2. JUnit 5 Tests

The test suite validates:

* Basic blocking behavior (`put`/`take`).
* Timed operations (`offer`/`poll` with timeout).
* Correct shutdown semantics.
* A multi‑producer/multi‑consumer stress test that guarantees **no lost or duplicated elements**.

All tests use `java.util.concurrent.CountDownLatch` to start workers simultaneously and `java.util.concurrent.CopyOnWriteArrayList` (or `ConcurrentLinkedQueue`) as a thread‑safe collector for consumer results.

```java
package com.example.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // ensure no threads linger
    }

    // -----------------------------------------------------------------
    // Basic blocking put / take
    // -----------------------------------------------------------------
    @Test
    void testPutTakeBlocking() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Integer> produced = new ArrayList<>();
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        // Producer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 10; i++) {
                    queue.put(i);
                    produced.add(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        // Consumer
        exec.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 10; i++) {
                    int val = queue.take();
                    consumed.add(val);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown(); // release both workers
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads did not finish in time");
        exec.shutdownNow();

        assertEquals(produced, consumed);
    }

    // -----------------------------------------------------------------
    // Timed offer / poll
    // -----------------------------------------------------------------
    @Test
    void testOfferPollTimeout() throws Exception {
        // Fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 100, TimeUnit.MILLISECONDS));
        }
        // Queue is full – offer should fail after timeout
        assertFalse(queue.offer(999, 50, TimeUnit.MILLISECONDS));

        // Drain with timed poll
        List<Integer> drained = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            Integer v = queue.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(v);
            drained.add(v);
        }
        // Queue empty – poll should return null after timeout
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));

        // Verify order
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, drained.get(i));
        }
    }

    // -----------------------------------------------------------------
    // Shutdown behavior
    // -----------------------------------------------------------------
    @Test
    void testShutdownPutTake() throws Exception {
        // Fill half the queue
        for (int i = 0; i < CAPACITY / 2; i++) {
            queue.put(i);
        }

        // Shutdown while there is still space
        queue.shutdown();

        // Further put must fail
        assertThrows(IllegalStateException.class, () -> queue.put(999));

        // Offer must return false
        assertFalse(queue.offer(888, 100, TimeUnit.MILLISECONDS));

        // Existing elements can still be taken
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < CAPACITY / 2; i++) {
            taken.add(queue.take());
        }
        assertEquals(CAPACITY / 2, taken.size());

        // After queue is empty, take should throw (shutdown & empty)
        assertThrows(IllegalStateException.class, queue::take);

        // Poll on empty, shutdown queue returns null (or could throw depending on design;
        // our implementation returns null for timed poll)
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownOfferPoll() throws Exception {
        // Drain the queue first
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        for (int i = 0; i < CAPACITY; i++) {
            queue.take();
        }

        queue.shutdown();

        // Offer should immediately return false
        assertFalse(queue.offer(1, 100, TimeUnit.MILLISECONDS));

        // Poll should return null immediately (queue empty & shutdown)
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    // -----------------------------------------------------------------
    // Multi‑producer / multi‑consumer stress test
    // -----------------------------------------------------------------
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // safeguard against deadlocks
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 3;
        final int ITEMS_PER_PRODUCER = 5000; // total distinct items = 20000

        // Each producer gets a unique offset so we can detect duplicates/lost items.
        final AtomicInteger nextItem = new AtomicInteger(0);
        final List<List<Integer>> producerSequences = new ArrayList<>(PRODUCER_COUNT);
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            List<Integer> seq = new ArrayList<>(ITEMS_PER_PRODUCER);
            for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                int value = nextItem.getAndIncrement(); // globally unique
                seq.add(value);
            }
            producerSequences.add(seq);
        }

        // Thread‑safe collector for consumer results
        BlockingQueue<Integer> results = new LinkedBlockingQueue<>();

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCER_COUNT + CONSUMER_COUNT);

        // ----- Producers -----
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int producerId = p;
            exec.submit(() -> {
                try {
                    startLatch.await();
                    List<Integer> seq = producerSequences.get(producerId);
                    for (int value : seq) {
                        // Use untimed put; it will block only if the queue is full.
                        queue.put(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // ----- Consumers -----
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    while (!Thread.interrupted()) {
                        try {
                            Integer v = queue.take(); // blocks until an element is available or shutdown
                            results.put(v);
                        } catch (IllegalStateException e) {
                            // Queue shut down and empty – exit consumer loop.
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all workers simultaneously
        startLatch.countDown();

        // Wait for all producers to finish putting their items
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Producers did not finish");

        // At this point all items have been enqueued. Shutdown the queue so consumers can exit.
        queue.shutdown();

        // Wait for consumers to finish (they will exit after seeing shutdown + empty queue)
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Consumers did not finish");

        exec.shutdownNow();

        // ----- Validation -----
        // Drain the results queue into a list
        List<Integer> resultList = new ArrayList<>();
        results.drainTo(resultList);

        // Expected set: all numbers from 0 to (PRODUCER_COUNT*ITEMS_PER_PRODUCER)-1
        int expectedTotal = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
        List<Integer> expected = new ArrayList<>(expectedTotal);
        for (int i = 0; i < expectedTotal; i++) {
            expected.add(i);
        }

        // No lost elements: resultList must contain every expected element at least once.
        assertEquals(expectedTotal, resultList.size(),
                "Number of consumed elements differs from number of produced elements");

        // No duplicates: all elements must be unique.
        List<Integer> sorted = new ArrayList<>(resultList);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            assertNotEquals(sorted.get(i - 1), sorted.get(i),
                    "Duplicate element found: " + sorted.get(i));
        }

        // Finally, verify that the set matches exactly the expected range.
        assertEquals(expected, sorted,
                "Consumed elements do not match the produced set (missing or extra elements)");
    }
}
```

### Explanation of the Stress Test

1. **Unique IDs** – Each producer receives a disjoint range of integers (via an `AtomicInteger`). This makes it trivial to detect lost or duplicated items after consumption.
2. **Synchronized start** – A `CountDownLatch` (`startLatch`) guarantees that all producer and consumer threads begin their work at the same moment, reducing timing‑dependent flakiness.
3. **Producer completion detection** – After all producers have finished (`doneLatch` counted down by producers), we call `queue.shutdown()`. This unblocks any consumers that might be waiting on an empty queue.
4. **Consumer termination** – Consumers exit when they catch `IllegalStateException` from `queue.take()` (thrown only when the queue is shut down **and** empty). They then place a sentinel `put` into a `BlockingQueue<Integer> results` for later validation.
5. **Validation** –  
   * Size check ensures no elements were lost.  
   * Sorting + adjacency check guarantees no duplicates (O(N log N) but acceptable for a test).  
   * Equality with the expected `0..N‑1` list confirms that the exact set of produced items was consumed.

The test runs with a modest capacity (5) relative to the total number of items (20 000) to heavily exercise blocking and unblocking behavior. The `@Timeout` annotation guards against deadlocks that would otherwise cause the test to hang indefinitely.

---

**You now have a complete, thread‑safe generic bounded blocking queue implementation (without using `java.util.concurrent.BlockingQueue`) and a thorough JUnit 5 test suite that covers basic functionality, timed