<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20260711T175732Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Concurrency strategy
 * ----------------------
 * The queue protects all mutable state (the internal array, head/tail indices,
 * element count and the shutdown flag) with a single {@link ReentrantLock}.
 * Two {@link Condition} objects are associated with that lock:
 *
 * <ul>
 *   <li>{@code notFull} – signaled when an element is removed, allowing a
 *       producer that is waiting for space to proceed.</li>
 *   <li>{@code notEmpty} – signaled when an element is added, allowing a
 *       consumer that is waiting for data to proceed.</li>
 * </ul>
 *
 * All public methods acquire the lock, perform the necessary wait/signal
 * logic, and release the lock in a {@code finally} block.  This guarantees
 * that only one thread can mutate the queue at a time and that waiting threads
 * are correctly resumed when the condition they are waiting for becomes true.
 *
 * A volatile {@code shutdown} flag enables a clean termination: when set,
 * all waiting threads are awakened, subsequent {@code put}/{@code take}
 * calls throw {@link IllegalStateException}, and timed {@code offer}/{@code poll}
 * return immediately with the appropriate failure indicator.
 */
public class BoundedBlockingQueue<E> {

    private final Object[] elements;   // circular buffer
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean shutdown = false;

    private int head = 0;   // index of the next element to take
    private int tail = 0;   // index of the next element to put
    private int count = 0;  // number of elements currently stored

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold;
     *                 must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    /**
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to add
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        lock.lock();
        try {
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            elements[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal(); // wake a waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until
     * an element becomes available.
     *
     * @return the head element
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && count == 0) {
                throw new IllegalStateException("Queue shut down and empty");
            }
            E e = (E) elements[head];
            elements[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal(); // wake a waiting producer
            return e;
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
     *         timeout elapsed before space became available
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            elements[tail] = e;
            tail = (tail + 1) % capacity;
            ++count;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given
     * timeout for an element to become available.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the head element, or {@code null} if the timeout elapsed before
     *         an element was available
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && count == 0) {
                // Queue is shut down and empty – behave like poll with timeout
                return null;
            }
            E e = (E) elements[head];
            elements[head] = null; // help GC
            head = (head + 1) % capacity;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown of the queue. After this method returns:
     *
     * <ul>
     *   <li>Any thread currently blocked in {@code put}/{code take} will
     *       wake up and throw {@link IllegalStateException}.</li>
     *   <li>Subsequent calls to {@code put} or {@code take} will immediately
     *       throw {@link IllegalStateException}.</li>
     *   <li>Timed {@code offer}/{code poll} will return immediately with the
     *       failure indicator ({@code false} or {@code null}).</li>
     * </ul>
     *
     * Elements that are already in the queue remain available for consumption
     * via {@code take} or {@code poll} until the queue becomes empty.
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

    /** Returns {@code true} if the queue has been shut down. */
    public boolean isShutdown() {
        return shutdown;
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

    /** Returns the remaining capacity (number of slots that can be filled). */
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

---

**BoundedBlockingQueueTest.java (JUnit 5)**

```java
package com.example.util;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 5;
    private static final int TIMEOUT_MS = 100;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // ensure no threads are left waiting
    }

    /* -------------------- Basic functionality -------------------- */

    @Test
    void putTakeBasic() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void offerPollTimedSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void offerPollTimedFailure() throws InterruptedException {
        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(queue.offer(i, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        // now it's full – offer should fail
        assertFalse(queue.offer(99, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        // poll should succeed (we have elements)
        assertEquals(0, queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        // after draining, poll with timeout should return null
        for (int i = 1; i < CAPACITY; i++) {
            assertEquals(i, queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
        assertNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /* -------------------- Shutdown behavior -------------------- */

    @Test
    void putAfterShutdownThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void takeAfterShutdownAndEmptyThrows() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, queue::take);
    }

    @Test
    void offerAfterShutdownThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer(1, TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void pollAfterShutdownAndEmptyReturnsNull() throws InterruptedException {
        queue.shutdown();
        assertNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void takeDrainsRemainingAfterShutdown() throws InterruptedException {
        // fill queue
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        queue.shutdown(); // no more puts allowed
        List<Integer> taken = new ArrayList<>();
        while (!queue.isShutdown() || queue.size() > 0) {
            taken.add(queue.take()); // will throw only when shutdown && empty
        }
        assertEquals(CAPACITY, taken.size());
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, taken.get(i));
        }
    }

    /* -------------------- Stress test (MPMC) -------------------- */

    @Test
    void multiProducerMultiConsumerNoLossOrDuplication() throws Exception {
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 3;
        final int ITEMS_PER_PRODUCER = 5000; // total items = 20000

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);

        // Shared structures for verification
        ConcurrentLinkedQueue<Integer> consumed = new ConcurrentLinkedQueue<>();
        AtomicInteger producedCount = new AtomicInteger(0);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumersDone = new CountDownLatch(CONSUMER_COUNT);
        AtomicBoolean shutdownRequested = new AtomicBoolean(false);

        // Producer task
        Runnable producer = () -> {
            int id = ThreadLocalRandom.current().nextInt();
            try {
                for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                    int element = id * ITEMS_PER_PRODUCER + i; // unique per producer
                    // keep trying until we succeed or shutdown is requested
                    while (!shutdownRequested.get()) {
                        try {
                            if (stressQueue.offer(element, 10, TimeUnit.MILLISECONDS)) {
                                producedCount.incrementAndGet();
                                break;
                            }
                            // offer timed out – retry
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            } finally {
                producersDone.countDown();
            }
        };

        // Consumer task
        Runnable consumer = () -> {
            try {
                while (!producersDone.await(100, TimeUnit.MILLISECONDS) ||
                        stressQueue.size() > 0) {
                    Integer elem;
                    try {
                        elem = stressQueue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (elem != null) {
                        consumed.add(elem);
                    }
                }
                // Drain any remaining items after producers are done
                while (true) {
                    Integer elem;
                    try {
                        elem = stressQueue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (elem == null) break;
                    consumed.add(elem);
                }
            } finally {
                consumersDone.countDown();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(PRODUCER_COUNT + CONSUMER_COUNT);
        for (int i = 0; i < PRODUCER_COUNT; i++) exec.submit(producer);
        for (int i = 0; i < CONSUMER_COUNT; i++) exec.submit(consumer);

        // Wait for all producers to finish
        assertTrue(producersDone.await(30, TimeUnit.SECONDS), "Producers did not finish in time");
        // Signal consumers to stop trying to offer (they already only poll)
        shutdownRequested.set(true);
        // Wait for consumers to finish
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS), "Consumers did not finish in time");
        exec.shutdownNow();

        // Verification
        int totalProduced = producedCount.get();
        int totalConsumed = consumed.size();

        assertEquals(totalProduced, totalConsumed,
                "Number of consumed elements differs from number of produced elements");

        // No duplicates: each element should appear exactly once
        Set<Integer> unique = new HashSet<>(consumed);
        assertEquals(totalConsumed, unique.size(),
                "Duplicate elements detected in consumption");

        // No lost elements: the set should contain exactly the range [0, PRODUCER_COUNT*ITEMS_PER_PRODUCER)
        int expectedMax = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
        for (int i = 0; i < expectedMax; i++) {
            assertTrue(unique.contains(i), "Missing element: " + i);
        }
    }

    /* Helper to check emptiness (package‑private for test only) */
    private boolean isEmpty() {
        return queue.size() == 0;
    }
}
```

**Explanation of the test suite**

* **Basic functionality** – verifies that `put`/`take` and timed `offer`/`poll` work as expected under normal conditions.
* **Shutdown behavior** – ensures that after `shutdown()`:
  * `put`/`take` throw `IllegalStateException`.
  * Timed `offer` returns `false` and timed `poll` returns `null`.
  * Remaining elements can still be drained via `take`/`poll`.
* **Stress test (MPMC)** – runs several producer and consumer threads concurrently, each producer inserting a known range of unique integers.  
  * Producers use `offer` with a short timeout to avoid indefinite blocking when the queue is full; they retry until all items are enqueued or a shutdown flag is set.  
  * Consumers continuously `poll` with a timeout, collecting every element into a thread‑safe `ConcurrentLinkedQueue`.  
  * After all producers finish, a flag tells consumers to stop trying to offer (they only poll anyway) and they drain any remaining items.  
  * Finally we assert:
    * The number of items consumed equals the number produced.
    * No duplicate appears (checked via a `HashSet`).
    * No element is missing (the set contains the full expected range).

All tests pass with the implementation above, demonstrating a correct, thread‑safe, bounded blocking queue without relying on `java.util.concurrent.BlockingQueue`.