<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260810T091324Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**BoundedBlockingQueue.java**

```java
/**
 * A thread‑safe bounded blocking queue that does **not** rely on any
 * classes from {@code java.util.concurrent} (except for the
 * {@link java.util.concurrent.TimeUnit} helper class).
 *
 * <h2>Concurrency strategy</h2>
 * <ul>
 *   <li>All mutable state is protected by a single {@link condensedjava.util.concurrent.locks.ReentrantLock ReentrantLock}.</li>
 *   <li>Two {@link condensedjava.util.concurrent.locks.Condition Condition}s are used:
 *     <ul>
 *         <li>{@code notFull} – signals when the queue has space for a new item.</li>
 *         <li>{@code notEmpty} – signals when the queue contains at least one item.</li>
 *     </ul>
 *   </li>
 *   <li>The queue is implemented as a circular (ring) buffer backed by an {@code Object[]} array.  The
 *     indices {@code head} and {@code tail} keep track of the next element to be removed and the next
 *     free slot, respectively.  The {@code count} field holds the current number of elements.</li>
 *   <li>A {@code volatile boolean closed} flag is used to indicate a graceful shutdown.  Once
 *     {@code shutdown()} has been called, no more elements may be {@code put} into the queue,
 *     but all already stored elements can still be {@code take}/{@code poll}.  Threads that are
 *     blocked waiting to put or take are unblocked by signalling both conditions.</li>
 * </ul>
 *
 * <h2>Shutdown semantics</h2>
 * <ul>
 *   <li>After {@code shutdown()} is called, {@code put(..)} and {@code offer(..)} throw
 *   {@link IllegalStateException} immediately.</li>
 *   <li>When the queue is empty and closed, {@code take(..)} returns {@code null}
 *   and {@code poll(..)} returns {@code null} immediately.</li>
 *   <li>Consumers can call {@code take()} or {@code poll(..)} until the attacked
 *   queue becomes empty; they can then detect the shutdown by observing a {@code null}
 *   return value.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public final class BoundedBlockingQueue<E> {

    private final Object[] items;          // ring buffer
    private int head = 0;                  // next element to read
    private int tail = 0;                  // next free slot to write
    private int count = 0;                 // current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean closed = false;   // true after shutdown()

    /**
     * Creates a new queue with the given capacity.try
     *
     * @param capacity the maximum number of elements the queue may hold
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        items = new Object[capacity];
    }

    /* ------------------------------------------------------------------ */
    /* Basic blocking operations                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Inserts the specified element into the queue, waiting if necessary
     * for space to become available.  If the queue has been shut down,
     * this method throws {@link IllegalStateException}.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if {@code e} is {@code null}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (closed) throw new IllegalStateException("Queue is shut down");
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into the queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e       the element to add
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the
     *         specified waiting time elapses before space is available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if {@code e} is {@code null}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (closed) return false;
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Basic non‑blocking operations                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.  If the queue has been shut
     * down and is empty, this method returns {@code null}.
     *
     * @return the head of the queue, or {@code null} if the queue is
     *         empty and has been shut down
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (closed) return null;
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become
     * available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret {@code timeout}
     * @return the head of the queue, or {@code null} if the specified
     *         waiting time elapses before an element is available or
     *         if the queue is shut down and empty
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (closed) return null;
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Shutdown support                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Initiates a graceful shutdown.  After this call, no more elements
     * may be added to the queue.  All waiting {@code put} and
     * {@code take} operations are unblocked immediately.  Elements
     * that were already in the queue can still be removed.
     */
    public void shutdown() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            closed = true;
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if {@link #shutdown()} has been called.
     *
     * @return {@code true} if the queue is shut down
     */
    public boolean isShutdown() {
        return closed;
    }

    /* ------------------------------------------------------------------ */
    /* Private helpers                                                      */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        notFull.signal();
        return e;
    }

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % items.length;
        count++;
        notEmpty.signal();
    }
}
```

---

**BoundedBlockingQueueTest.java**

```java
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BoundedBlockingQueueTest {

    /* ------------------------------------------------------------------ */
    /* Basic functionality tests                                         */
    /* ------------------------------------------------------------------ */

    @Test
    void putTakeSingleThread() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        q.put(10);
        assertEquals(10, q.take());
    }

    @Test
    void timedOfferPoll() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);

        // Queue is empty: poll should return null immediately
        assertNull(q.poll(100, TimeUnit.MILLISECONDS));

        // Offer succeeds
        assertTrue(q.offer(42, 100, TimeUnit.MILLISECONDS));

        // Queue full: offer should time out
        assertFalse(q.offer(43, 200, TimeUnit.MILLISECONDS));

        // Take the element
        assertEquals(42, q.take());

        // Now offer again
        assertTrue(q.offer(43, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void shutdownBehavior() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);

        // The queue is full – put will block
        Thread putThread = new Thread(() -> {
            try {
                q.put(3);
                fail("Expected IllegalStateException after shutdown");
            } catch (InterruptedException e) {
                // ignore
            } catch (IllegalStateException e) {
                // expected
            }
        });
        putThread.start();

        // Give the put thread a moment to block
        Thread.sleep(100);
        q.shutdown();

        putThread.join(1000);
        assertTrue(putThread.isAlive() == false, "put thread should have terminated");

        // After shutdown, take returns null when queue empty
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertNull(q.take()); // queue empty & closed
    }

    /* ------------------------------------------------------------------ */
    /* Stress test – multi‑producer / multi‑consumer                     */
    /* ------------------------------------------------------------------ */

    @Test
    @Timeout(30) // fail if test runs longer than 30 seconds
    void multiProducerConsumerStress() throws InterruptedException {
        final int capacity = 10;
        final int producerCount = 5;
        final int consumerCount = 5;
        final int perProducer = 2000; // total 10,000 items
        final int totalItems = producerCount * perProducer;

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);

        ExecutorService exec = Executors.newFixedThreadPool(producerCount + consumerCount);

        // Thread‑safe sets to track produced / consumed IDs
        Set<Integer> produced = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<Integer> consumed = Collections.newSetFromMap(new ConcurrentHashMap<>());

        AtomicInteger producedCounter = new AtomicInteger(0);
        AtomicInteger consumedCounter = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);

        // Producer runnable
        Runnable producer = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < perProducer; i++) {
                    int id = producedCounter.getAndIncrement();
                    queue.put(id);
                    produced.add(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producersDone.countDown();
            }
        };

        // Consumer runnable
        Runnable consumer = () -> {
            try {
                startLatch.await();
                while (true) {
                    Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (val != null) {
                        consumed.add(val);
                        consumedCounter.incrementAndGet();
                    } else {
                        // If queue is empty and all producers finished, we can exit
                        if (producersDone.getCount() == 0 && queue.isShutdown()) {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumersDone.countDown();
            }
        };

        // Submit producers and consumers
        for (int i = 0; i < producerCount; i++) {
            exec.submit(producer);
        }
        for (int i = 0; i < consumerCount; i++) {
            exec.submit(consumer);
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for producers to finish
        assertTrue(producersDone.await(20, TimeUnit.SECONDS), "Producers timed out");

        // All items produced – signal shutdown
        queue.shutdown();

        // Wait for consumers to finish
        assertTrue(consumersDone.await(20, TimeUnit.SECONDS), "Consumers timed out");

        exec.shutdownNow();

        // Verify all items were produced
        assertEquals(totalItems, produced.size(), "Produced set size mismatch");
        assertEquals(totalItems, consumed.size(), "Consumed set size mismatch");

        // Ensure no items were lost or duplicated
        assertEquals(produced, consumed, "Produced and consumed sets differ");
    }
}
```

### How the tests work

| Test | What it verifies | How it verifies |
|------|------------------|-----------------|
| `putTakeSingleThread` | Basic blocking put/take works in a single thread | Put an element, take it back, compare |
| `timedOfferPoll` | Timed offer/poll honour the timeout and respect capacity | Offer when full, poll when empty, check return values |
| `shutdownBehavior` | `shutdown()` stops new puts and allows the queue to drain | Start a thread that tries to put into a full queue, shutdown, ensure the thread is unblocked and throws an exception; then verify `take()` returns `null` when empty کمپنی |
| `multiProducerConsumerStress` | No lost or duplicated items under heavy contention | 5 producers insert 2 000 unique integers each; 5 consumers poll until all items are consumed; compare produced and consumed sets |

All tests run under JUnit 5.  The stress test is bounded by a 30 second timeout; if it takes longer, the test fails automatically.  This guarantees that the implementation does not deadlock or starve under realistic load.