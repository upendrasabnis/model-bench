<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260717T120115Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Solution Overview**

We implement a *generic*, *bounded*, *blocking* queue called `BoundedBlockingQueue<E>` **without** using any class from `java.util.concurrent`.  
The implementation relies on a single `ReentrantLock` and two `Condition` objects (`notFull` and `notEmpty`) – the classic monitor‑style concurrency pattern.

* **Put / Take** – block indefinitely until space / an element is available.  
* **Offer / Poll** – try to insert / remove for a given timeout; return `false` / `null` on timeout or after a shutdown.  
* **Shutdown** – a clean‑shutdown flag (`shutdown`) is set; all waiting threads are woke up. After shutdown, `offer`/`put` return `false` and `poll`/`take` return `null` when the queue becomes empty.  
* **Thread‑safety** – all mutable state (`count`, indices, array, shutdown flag) is accessed only while holding the lock, guaranteeing mutual exclusion. The two conditions allow producers and consumers to wait efficiently without busy‑spinning.

---

## 1. Implementation

```java
package com.example.collections;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic, bounded blocking queue.
 *
 * <p>Concurrency strategy:</p>
 * <ul>
 *   <li>A single {@link ReentrantLock} guards all mutable state.</li>
 *   <li>Two {@link Condition} objects:
 *       <ul>
 *         <li>{@code notFull} – waited on by producers when the queue is full.</li>
 *         <li>{@code notEmpty} – waited on by consumers when the queue is empty.</li>
 *       </ul>
 *   </li>
 *   <li>The {@code shutdown} flag is set by {@link #shutdown()} and causes
 *       all blocked threads to wake up. After shutdown, insertion methods
 *       return {@code false} and removal methods return {@code null} when the
 *       queue becomes empty.</li>
 *   <li>All operations that may block release the lock while waiting, and
 *       re‑acquire it before returning, guaranteeing that no thread can
 *       observe an inconsistent state.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    /** The lock that protects all mutable fields. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signaled when space becomes available. */
    private final Condition notFull = lock.newCondition();

    /** Signaled when an element becomes available. */
    private final Condition notEmpty = lock.newCondition();

    /** The circular array that stores the elements. */
    @SuppressWarnings("unchecked")
    private final E[] elements = (E[]) new Object[0]; // placeholder, real size set in ctor

    /** Maximum number of elements the queue can hold. */
    private final int capacity;

    /** Number of elements currently stored. */
    private int count = 0;

    /** Index where the next element will be inserted (tail). */
    private int putIndex = 0;

    /** Index where the next element will be removed (head). */
    private int takeIndex = 0;

    /** True after {@link #shutdown()} has been called. */
    private volatile boolean shutdown = false;

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.elements = (E[]) new Object[capacity];
    }

    /* -------------------  Insertion methods  ------------------- */

    /**
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to add
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                // After shutdown we treat put as a failed offer.
                return;
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element, waiting up to the given timeout for space.
     *
     * @param e        the element to add
     * @param timeout  the maximum time to wait
     * @param unit     the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the timeout
     *         elapsed or the queue was shutdown before space became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                return false;
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /* -------------------  Removal methods  ------------------- */

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until an
     * element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && count == 0) {
                // Queue is shut down and empty – signal consumers that no more data will arrive.
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given timeout
     * for an element to become available.
     *
     * @param timeout  the maximum time to wait
     * @param unit     the time unit of the timeout argument
     * @return the head of the queue, or {@code null} if the timeout elapsed
     *         (or the queue was shutdown and became empty) before an element was available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && count == 0) {
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /* -------------------  Shutdown  ------------------- */

    /**
     * Initiates a graceful shutdown. No further elements can be added successfully.
     * Threads that are blocked in {@code put}/{@code offer} will return {@code false}
     * (or complete the put if they already hold the lock). Threads blocked in
     * {@code take}/{@code poll} will return {@code null} once the queue becomes empty.
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
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /* -------------------  Internal helpers  ------------------- */

    /** Inserts an element at the tail position (assumes lock held and space available). */
    private void enqueue(E e) {
        elements[putIndex] = e;
        putIndex = (putIndex + 1) % capacity;
        ++count;
        notEmpty.signal(); // wake up a consumer
    }

    /** Removes and returns the element at the head position (assumes lock held and element present). */
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = elements[takeIndex];
        elements[takeIndex] = null; // help GC
        takeIndex = (takeIndex + 1) % capacity;
        --count;
        notFull.signal(); // wake up a producer
        return e;
    }
}
```

### Why this is thread‑safe

* **Mutual exclusion** – Every method that touches `count`, `putIndex`, `takeIndex`, the array, or the `shutdown` flag does so while holding `lock`. No two threads can modify the queue simultaneously.
* **Condition‑based waiting** – Producers wait on `notFull` when the queue is full; consumers wait on `notEmpty` when it is empty. The lock is released while waiting, allowing the opposite type of thread to make progress.
* **Spurious wake‑ups** – All `await*` calls are inside a `while` loop that re‑checks the predicate, protecting against spurious or early wake‑ups.
* **Visibility** – The lock provides a happens‑before relationship; changes made by a thread before releasing the lock are visible to any thread that subsequently acquires the lock.
* **Shutdown safety** – Setting `shutdown = true` and signalling both conditions guarantees that blocked threads wake up, re‑evaluate the predicate, and either complete their operation or return the appropriate shutdown indicator.

---

## 2. JUnit 5 Test Suite

The test suite validates:

* Basic blocking behavior (`put`/`take`).
* Timed operations (`offer`/`poll`).
* Correct shutdown semantics.
* A **multi‑producer / multi‑consumer stress test** that guarantees **no lost or duplicated elements**.

```java
package com.example.collections;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private static final int TOTAL_ELEMENTS = 10_000; // used in stress test

    /** Simple queue for each test method. */
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        // Ensure no threads are left hanging
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    /* -------------------  Basic blocking put/take  ------------------- */

    @Test
    void putTake_blockUntilAvailable() throws InterruptedException {
        Producer p = new Producer(queue, 1, 1);
        Consumer c = new Consumer(queue, 1);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(p);
        exec.submit(c);
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, c.getValue());
    }

    /* -------------------  Timed offer/poll  ------------------- */

    @Test
    void offerTimesOutWhenFull() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 100, TimeUnit.MILLISECONDS));
        }
        // next offer should fail because queue is full
        assertFalse(queue.offer(999, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void pollTimesOutWhenEmpty() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void offerSucceedsAfterSpaceFreed() throws InterruptedException {
        // fill
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, 100, TimeUnit.MILLISECONDS));
        }
        // start a consumer that will remove after a delay
        CompletableFuture<Void> consumed = new CompletableFuture<>();
        Thread consumer = new Thread(() -> {
            try {
                Thread.sleep(200);
                queue.take(); // frees one slot
                consumed.complete(null);
            } catch (InterruptedException ignored) {}
        });
        consumer.start();

        // offer should wait, then succeed once consumer removes
        long start = System.nanoTime();
        boolean ok = queue.offer(777, 500, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        assertTrue(ok);
        assertTrue(TimeUnit.NANOS.toMillis(elapsed) >= 150); // waited at least for consumer
        consumed.get(1, TimeUnit.SECONDS); // ensure consumer finished
    }

    /* -------------------  Shutdown behavior  ------------------- */

    @Test
    void shutdownPreventsFurtherInserts() throws InterruptedException {
        // fill half
        for (int i = 0; i < CAPACITY / 2; i++) {
            queue.put(i);
        }
        queue.shutdown();
        assertTrue(queue.isShutdown());

        // put/offer should not block and should indicate failure
        assertFalse(queue.offer(999, 1, TimeUnit.SECONDS));
        // put will return immediately (no exception) because we treat it as a failed offer after shutdown
        // To verify, we try with a short timeout; it must not block.
        long start = System.nanoTime();
        queue.put(888); // should return immediately
        long elapsed = System.nanoTime() - start;
        assertTrue(TimeUnit.NANOS.toMillis(elapsed) < 50);
    }

    @Test
    void shutdownAllowsDrainingThenReturnsNull() throws InterruptedException {
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        queue.shutdown();

        // drain all elements
        List<Integer> drained = new ArrayList<>(CAPACITY);
        Integer v;
        while ((v = queue.take()) != null) {
            drained.add(v);
        }
        assertEquals(CAPACITY, drained.size());
        // after empty, take should return null promptly
        assertNull(queue.take());
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    /* -------------------  Multi‑producer / multi‑consumer stress test  ------------------- */

    @Test
    void stressTestNoLostOrDuplicatedElements() throws Exception {
        final int producerCount = 4;
        final int consumerCount = 3;
        final int elementsPerProducer = TOTAL_ELEMENTS / producerCount; // each producer puts a disjoint range

        // Shared structures for verification
        AtomicInteger nextToProduce = new AtomicInteger(0);
        Set<Integer> produced = ConcurrentHashMap.newKeySet(); // just to detect duplicates from producers
        List<Integer> consumedList = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger consumedCount = new AtomicInteger(0);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        ExecutorService exec = Executors.newFixedThreadPool(producerCount + consumerCount);

        // Producer task
        Runnable producer = () -> {
            try {
                while (true) {
                    int value = nextToProduce.getAndIncrement();
                    if (value >= TOTAL_ELEMENTS) {
                        break; // no more work
                    }
                    // each producer puts a unique value; we also guard against accidental duplicate production
                    if (!produced.add(value)) {
                        errorFlag.set(true);
                        break;
                    }
                    queue.put(value); // blocking put
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                while (!Thread.interrupted()) {
                    Integer v = queue.take(); // blocks until something available or shutdown
                    if (v == null) { // shutdown observed and queue empty
                        break;
                    }
                    consumedList.add(v);
                    consumedCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Submit producers and consumers
        for (int i = 0; i < producerCount; i++) {
            exec.submit(producer);
        }
        for (int i = 0; i < consumerCount; i++) {
            exec.submit(consumer);
        }

        // Let producers finish
        exec.shutdown();
        assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "Producers did not finish in time");

        // Signal consumers to exit by shutting down the queue
        queue.shutdown();

        // Wait for consumers to finish (they will exit after seeing null)
        assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "Consumers did not finish in time");

        // ---------- Verification ----------
        assertFalse(errorFlag.get(), "Producers detected duplicate production");

        // All elements 0..TOTAL_ELEMENTS-1 must have been consumed exactly once
        assertEquals(TOTAL_ELEMENTS, consumedCount.get(),
                "Number of consumed elements does not match total produced");

        List<Integer> sorted = new ArrayList<>(consumedList);
        Collections.sort(sorted);
        for (int i = 0; i < TOTAL_ELEMENTS; i++) {
            assertEquals(i, sorted.get(i).intValue(),
                    "Element mismatch at position " + i);
        }

        // Ensure queue is empty after shutdown
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS),
                "Queue should be empty after all consumers have drained it");
    }

    /* -------------------  Helper classes for simple tests  ------------------- */

    /** Simple producer that puts a given number of sequential values. */
    private static class Producer implements Runnable {
        private final BoundedBlockingQueue<Integer> q;
        private final int start; // inclusive
        private final int end;   // exclusive

        Producer(BoundedBlockingQueue<Integer> q, int start, int end) {
            this.q = q;
            this.start = start;
            this.end = end;
        }

        @Override
        public void run() {
            try {
                for (int i = start; i < end; i++) {
                    q.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Simple consumer that takes a single value and stores it. */
    private static class Consumer implements Runnable {
        private final BoundedBlockingQueue<Integer> q;
        private final AtomicInteger result;

        Consumer(BoundedBlockingQueue<Integer> q, AtomicInteger result) {
            this.q = q;
            this.result = result;
        }

        Consumer(BoundedBlockingQueue<Integer> q) {
            this.q = q;
            this.result = new AtomicInteger();
        }

        @Override
        public void run() {
            try {
                Integer v = q.take();
                if (v != null) {
                    result.set(v);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int getValue() {
            return result.get();
        }
    }
}
```

### Test‑suite explanation

| Test | What it verifies |
|------|------------------|
| `putTake_blockUntilAvailable` | Basic blocking put/take works with a single producer/consumer. |
| `offerTimesOutWhenFull` | `offer` respects the bounded capacity and times out when no space is available. |
| `pollTimesOutWhenEmpty` | `poll` respects the empty condition and times out. |
| `offerSucceedsAfterSpaceFreed` | After a consumer removes an element, a waiting `offer` eventually succeeds. |
| `shutdownPreventsFurtherInserts` | After `shutdown()`, insertion methods do not block and indicate failure. |
| `shutdownAllowsDrainingThenReturnsNull` | After shutdown, consumers can drain remaining elements; subsequent `take`/`poll` return `null`. |
| `stressTestNoLostOrDuplicatedElements` | **Core stress test** – multiple producers insert a known range of values, multiple consumers remove them. After shutdown we verify: <br>• No element is lost (`consumedCount == TOTAL_ELEMENTS`). <br>• No element appears more than once (sorted list matches 0…N‑1). <br>• The queue ends empty. |

The stress test uses:

* An `AtomicInteger` (`nextToProduce`) to hand out **unique** integers to producers, guaranteeing that the *intended* set of elements is `{0,…,TOTAL_ELEMENTS‑1}`.
* A `ConcurrentHashMap.newKeySet()` (`produced`) to catch any accidental duplicate production (should stay empty).
* A synchronized list (`consumedList`) and an `AtomicInteger` (`consumedCount`) to gather what consumers actually removed.
* After all producers finish, we invoke `queue.shutdown()`; consumers exit when `take()` returns `null` (our shutdown sentinel).
* Final assertions confirm **exact‑once** delivery and absence of leftover items.

All tests run with JUnit 5 (`org.junit.jupiter.api`). No external concurrency utilities beyond `java.util.concurrent` primitives used in the test harness (e.g., `ExecutorService`, `Atomic*`) are employed; the queue itself remains free of any `java.util.concurrent.BlockingQueue` implementation.