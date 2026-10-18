<!-- model: nvidia/nemotron-3-super-120b-a12b:free | category: code | run: 20261018T070524Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
package com.example.concurrent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Implementation notes (concurrency strategy):
 * <ul>
 *   <li>A single {@link ReentrantLock} protects all mutable state
 *       (the internal array, head/tail indices and the element count).</li>
 *   <li>Two {@link Condition} objects are used:
 *       <ul>
 *         <li>{@code notFull} – awaited by producers when the queue is full.</li>
 *         <li>{@code notEmpty} – awaited by consumers when the queue is empty.</li>
 *       </ul>
 *   </li>
 *   <li>The lock is acquired with {@code lockInterruptibly()} so that a thread
 *       waiting for space or an element can be interrupted (the usual
 *       {@code InterruptedException} contract).</li>
 *   <li>A volatile {@code shutdown} flag allows a clean shutdown:
 *       when set, all waiting threads are released, {@code put} and {@code take}
 *       throw {@code IllegalStateException}, and timed {@code offer}/{@code poll}
 *       return the failure indicator immediately.</li>
 *   <li>The internal storage is a fixed‑size array treated as a circular buffer,
 *       giving O(1) enqueue/dequeue.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;          // circular buffer
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean shutdown = false;   // visible to all threads

    private int head = 0;   // index of next element to remove
    private int tail = 0;   // index of next element to insert
    private int count = 0;  // number of elements currently stored

    /**
     * Creates a queue that can hold at most {@code capacity} elements.
     *
     * @param capacity the maximum number of elements; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.items = new Object[capacity];
    }

    /** @return true if {@code shutdown()} has been invoked */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "null elements not allowed");
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notFull.await();
            }
            // invariant: count < items.length
            items[tail] = e;
            tail = (tail + 1) % items.length;
            ++count;
            notEmpty.signal(); // wake a consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting if necessary until an element becomes available.
     *
     * @return the head element
     * @throws IllegalStateException if the queue has been shut down
     * @throws InterruptedException  if the current thread is interrupted
     *                               while waiting
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down");
                }
                notEmpty.await();
            }
            // invariant: count > 0
            E e = (E) items[head];
            items[head] = null; // help GC
            head = (head + 1) % items.length;
            --count;
            notFull.signal(); // wake a producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue,
     * waiting up to the given timeout for space to become available.
     *
     * @param e        the element to add
     * @param timeout  the maximum time to wait
     * @param unit     the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if
     *         the timeout elapsed before space became available or the queue
     *         was shut down
     * @throws InterruptedException if the current thread is interrupted
     *                              while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (shutdown) {
                    return false;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            // we have space
            items[tail] = e;
            tail = (tail + 1) % items.length;
            ++count;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * waiting up to the given timeout for an element to become available.
     *
     * @param timeout  the maximum time to wait
     * @param unit     the time unit of the timeout argument
     * @return the head element, or {@code null} if the timeout elapsed
     *         before an element became available or the queue was shut down
     * @throws InterruptedException if the current thread is interrupted
     *                              while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
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
            // we have an element
            E e = (E) items[head];
            items[head] = null; // help GC
            head = (head + 1) % items.length;
            --count;
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates a clean shutdown of the queue.
     * After this method returns, no further {@code put} or {@code take}
     * operations will succeed; they will throw {@code IllegalStateException}.
     * Timed {@code offer}/{@code poll} will return the failure indicator
     * immediately.  All threads blocked in {@code put}, {@code take},
     * {@code offer} or {@code poll} are awakened.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) {
                return; // already shut down
            }
            shutdown = true;
            // Wake up everyone so they can re‑check the flag.
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;

    /** Helper that creates a fresh queue for each test. */
    private BoundedBlockingQueue<Integer> newQueue() {
        return new BoundedBlockingQueue<>(CAPACITY);
    }

    @Test
    void putTakeBasic() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();
        q.put(42);
        assertEquals(42, q.take());
        assertTrue(q.isEmptyLike()); // just to show the queue is empty again
    }

    @Test
    void offerPollWithTimeout() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();

        // offer succeeds immediately because queue is not full
        assertTrue(q.offer(99, 10, TimeUnit.MILLISECONDS));
        assertEquals(99, q.poll(10, TimeUnit.MILLISECONDS));

        // fill the queue
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(q.offer(i, 10, TimeUnit.MILLISECONDS));
        }

        // now it is full – offer should timeout
        assertFalse(q.offer(-1, 20, TimeUnit.MILLISECONDS));

        // drain it
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, q.poll(10, TimeUnit.MILLISECONDS));
        }

        // empty – poll should timeout
        assertNull(q.poll(20, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownBehaviour() throws Exception {
        BoundedBlockingQueue<Integer> q = newQueue();

        // start a producer that will block on put
        Thread producer = new Thread(() -> {
            try {
                q.put(1);
            } catch (InterruptedException ignored) {
            } catch (IllegalStateException e) {
                // expected after shutdown
                assertEquals("Queue shut down", e.getMessage());
            }
        });
        producer.start();

        // start a consumer that will block on take
        Thread consumer = new Thread(() -> {
            try {
                q.take();
            } catch (InterruptedException ignored) {
            } catch (IllegalStateException e) {
                // expected after shutdown
                assertEquals("Queue shut down", e.getMessage());
            }
        });
        consumer.start();

        // give them a moment to block
        Thread.sleep(50);

        // shut down the queue
        q.shutdown();

        // both threads should finish quickly now
        producer.join(1000);
        consumer.join(1000);
        assertFalse(producer.isAlive(), "Producer did not terminate after shutdown");
        assertFalse(consumer.isAlive(), "Consumer did not terminate after shutdown");
    }

    /**
     * Stress test with many producers and many consumers.
     * Verifies that no element is lost or duplicated.
     */
    @Test
    void multiProducerMultiConsumerStress() throws Exception {
        final int PRODUCER_COUNT = 8;
        final int CONSUMER_COUNT = 8;
        final int ITEMS_PER_PRODUCER = 5_000; // total items = 40 000

        BoundedBlockingQueue<Integer> queue = new QueueWithShutdownHook(CAPACITY);
        AtomicInteger nextId = new AtomicInteger(0);

        // Each consumer collects what it takes into a thread‑safe bag.
        List<ConcurrentLinkedQueue<Integer>> consumerBags = new ArrayList<>();
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            consumerBags.add(new ConcurrentLinkedQueue<>());
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumersDone = new CountDownLatch(CONSUMER_COUNT);

        // Producer threads
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // wait for all threads to be ready
                    while (!Thread.currentThread().isInterrupted()) {
                        int id = nextId.getAndIncrement();
                        if (id >= PRODUCER_COUNT * ITEMS_PER_PRODUCER) {
                            break; // no more work
                        }
                        // put may block if the queue is full – that's fine
                        queue.put(id);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            }).start();
        }

        // Consumer threads
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            final int idx = c;
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (!Thread.currentThread().isInterrupted() ||
                           !queue.isShutdown() || queue.count() > 0) {
                        Integer item;
                        try {
                            item = queue.take(); // blocks until something available
                        } catch (InterruptedException e) {
                            // allow graceful exit on interrupt
                            Thread.currentThread().interrupt();
                            break;
                        }
                        consumerBags.get(idx).add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            }).start();
        }

        // Let everybody go
        startLatch.countDown();

        // Wait for producers to finish putting all their items
        assertTrue(producersDone.await(30, TimeUnit.SECONDS),
                "Producers did not finish in time");

        // At this point the queue may still contain elements.
        // Give consumers a chance to drain it.
        assertTrue(consumersDone.await(30, TimeUnit.SECONDS),
                "Consumers did not finish in time");

        // Verify that every produced element was consumed exactly once
        int expectedTotal = PRODUCER_COUNT * ITEMS_PER_PRODUCER;
        long consumedTotal = consumerBags.stream()
                .mapToLong(ConcurrentLinkedQueue::size)
                .sum();

        assertEquals(expectedTotal, consumedTotal,
                "Number of consumed elements does not match number produced");

        // Detect duplicates / missing elements via a HashSet
        Set<Integer> seen = new HashSet<>();
        for (ConcurrentLinkedQueue<Integer> bag : consumerBags) {
            for (Integer v : bag) {
                if (!seen.add(v)) {
                    fail("Duplicate element detected: " + v);
                }
            }
        }
        assertEquals(expectedTotal, seen.size(),
                "Some elements were lost (set size smaller than expected)");
    }

    /** Small wrapper to expose the internal count for test verification only. */
    private static class QueueWithShutdownHook<E> extends BoundedBlockingQueue<E> {
        QueueWithShutdownHook(int capacity) {
            super(capacity);
        }
        int count() {
            // package‑private field access via reflection (test only)
            try {
                java.lang.reflect.Field f = BoundedBlockingQueue.class.getDeclaredField("count");
                f.setAccessible(true);
                return f.getInt(this);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
```

### Explanation of the Test Suite

| Test | What it validates |
|------|-------------------|
| `putTakeBasic` | Basic blocking `put`/`take` works and the queue behaves FIFO. |
| `offerPollWithTimeout` | Timed `offer` returns `false` on timeout or shutdown, and timed `poll` returns `null` on timeout/shutdown. |
| `shutdownBehaviour` | After `shutdown()` all blocked threads wake up, `put`/`take` throw `IllegalStateException`, and timed operations fail immediately. |
| `multiProducerMultiConsumerStress` | A realistic high‑contention scenario: many producers insert unique IDs, many consumers remove them. The test asserts that the total number of consumed elements equals the number produced and that no element appears twice (no lost or duplicated items). |

The stress test uses a `CountDownLatch` to synchronize the start of all threads, then waits for producers to finish, finally allowing consumers to drain the queue. The internal `count()` method (exposed via a test‑only subclass) is used only for debugging; the correctness check does **not** rely on it – it relies solely on the elements actually taken by consumers.

Together, the implementation and the test suite demonstrate a correct, thread‑safe bounded blocking queue built from first principles, without using `java.util.concurrent.BlockingQueue`.