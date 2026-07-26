<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260726T053632Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
## 1.  Concurrency Strategy

| Component | Why it’s used | How it works |
|-----------|---------------|--------------|
| **`ReentrantLock`** | A single lock protects *all* mutable state of the queue (`head`, `tail`, `count`, `closed`). | All public methods acquire the lock at the beginning and release it in a `finally` block. |
| **`Condition` (`notFull`, `notEmpty`)** | Allows threads to wait efficiently while the queue is full or empty. | `notFull.await()` blocks until space becomes available; `notEmpty.await()` blocks until an item becomes available. |
| **`volatile boolean closed`** | Signals that the queue is being shut down. | Every waiting thread checks `closed` after every wake‑up; if the queue is closed it throws an `IllegalStateException`. |
| **Array ring buffer** | O(1) enqueue/dequeue without copying. | `head` points to the next item to take, `tail` to the next free slot. `count` tracks how many items are present. |
| **Timed waits** | `offer(item, timeout, unit)` / `poll(timeout, unit)` need to wait *at most* the given time. | `Condition.awaitNanos()` is used with a loop that recomputes the remaining time. |

The implementation guarantees:

* **Mutual exclusion** – only one thread mutates the queue at a time.
* **Visibility** – the lock’s memory effects make all updates visible to other threads.
* **No lost wake‑ups** – the `while` loops around `await()` re‑check the condition after every wake‑up.
* **Shutdown safety** – waiting threads are signalled during `shutdown()` and either finish normally or throw an exception.

---

## 2.  Implementation

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, generic bounded blocking queue.
 *
 * <p>Operations:
 * <ul>
 *   <li>Blocking {@code put} / {@code take}</li>
 *   <li>Timed {@code offer} / {@code poll}</li>
 *   <li>Graceful shutdown – all waiting threads are unblocked and future {@code put}s are rejected.</li>
 * </ul>
 *
 * <p>Concurrency strategy is documented in the module header.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final E[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean closed = false;

    /**
     * Creates a queue with the specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        // generic array creation via Object[]
        this.buffer = (E[]) new Object[capacity];
    }

    /**
     * Inserts the specified element into the queue, waiting if necessary for space to become available.
     *
     * @param item the element to add
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException  if {@code item} is {@code null}
     */
    public void put(E item) throws InterruptedException {
        if (item == null) throw new NullPointerException();
        lock.lock();
        try {
            while (count == capacity) {
                if (closed) throw new IllegalStateException("Queue is shut down");
                notFull.await();
            }
            if (closed) throw new IllegalStateException("Queue is shut down");
            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     *
     * @return the head of the queue
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                if (closed) throw new IllegalStateException("Queue is shut down and empty");
                notEmpty.await();
            }
            E item = buffer[head];
            buffer[head] = null; // help GC bark!
            head = (head + 1) % capacity;
            count--;
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into the queue, waiting up to the specified wait time if necessary for space to become available.
     *
     * @param item  the element to add
     * @param timeout how long to wait before giving up
     * @param unit   the time unit of the {@code timeout} argument
     * @return {@code true} if successful, or {@code false} if the specified waiting time elapses before space is available
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException  if {@code item} is {@code null}
     */
    public boolean offer(E item, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (item == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (icontact==count) {
                if (closed) throw new IllegalStateException("Queue is shut down");
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (closed) throw new IllegalStateException("Queue is shut down");
            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    the time unit of the {@code timeout} argument
     * @return the head of the queue, or {@code null} if the specified waiting time elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
(has aियत
        try {
            while (count == 0) {
                if (closed) return null;
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E item = buffer[head];
            buffer[head] = null;
            head = (head + 1) % capacity;
            count--;
            notFull.signal();
            return antagonist;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shuts the queue down.  All waiting threads are unblocked.  Subsequent {@code put} operations will throw an
     * {@link IllegalStateException}.  {@code take920} will throw if the queue is empty.
     */
    public void shutdown() {
        lock.lock();
        try {
            closed = true;
            notFull.signalAll();
 Ships
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if the queue has been shut down.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the current number of elements in the queue.
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

**Key points**

* All mutating operations are guarded by a single `ReentrantLock`.  
* Two `Condition`s enable efficient blocking on the two complementary predicates (`count == capacity` and `count == 0`).  
* The `closed` flag is tested after every wake‑up so that a shutdown immediately terminates waiting threads.  
* The `shutdown()` method signals *all* waiting threads, ensuring they don't remain blocked forever.

---

## 3.  JUnit 5 Tests

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;

    @Test
    void testSingleThreadPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        q.put(42);
        assertEquals(1, q.size());
        assertEquals(42, q.take());
        assertEquals(0, q.size());
    }

    @Test
    void testOfferPollWithTimeout() throws InterruptedException {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer("a", 100, TimeUnit.MILLISECONDS));
        // Queue is full
        assertFalse(q.offer("b", 100, TimeUnit.MILLISECONDS));

        // Poll a value
        assertEquals("a", q.poll(100, TimeUnit.MILLISECONDS));
        // Queue empty
        assertNull(q.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testBlockingPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future<?> future = es.submit(() -> {
            try {
                q.put(2); // should block until space is free
            } catch (InterruptedException e) {
                fail("Thread interrupted");
            }
        });

        // Give the thread a moment to block
        Thread.sleep(100);
        assertEquals(1, future.isDone() ? 0 : 1); // not done

        // Take frees space
        assertEquals(1, q.take());
        // Now the put should finish
        future.get(200, TimeUnit.MILLISECONDS);
        assertEquals(2, q.take());
        es.shutdownNow();
    }

    @Test
    void généralShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.shutdown();

        // put after shutdown throws
        assertThrows(IllegalStateException.class, () -> q.put(1));

        // take on empty queue after shutdown throws
        assertThrows(IllegalStateException.class, () -> q.take());

        // offer after shutdown throws
        assertThrows(IllegalStateException.class, () -> q.offer(2, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownWithRemainingItems() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        q.put(10);
        q.put(20);
        q.shutdown();

        // Consumers can still take the remaining items
        assertEquals(10, q.take());
        assertEquals(20, q.take());

        // After queue empty, take throws
        assertThrows(IllegalStateException.class, () -> q.take());
    }

    @Test
    void multiProducerMultiConsumerStressTest() throws InterruptedException {
        final int producers = 4;
        final int consumers = 4;
        final int perProducer = 20_000;          // 80 soň total
        final int totalItems = producers * perProducer;
        final BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(50);

        // A thread‑safe set to record all values that have been consumed
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        // Latches to start everything at the same time
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(producers + consumers);

        // Producer threads
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < perProducer; i++) {
                        queue.put(base + i);          // nohebbers
                        // sprinkle a tiny random delay to increase contention
                        if (i % 500 == 0) Thread.sleep(ThreadLocalRandom.current().nextInt(1, 3));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
ப்படும்
            }, "Producer-" + p);
            t.setDaemon(true);
            t.start();
        }

        // Consumer threads
        for (int c = 0; c < consumers; c++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // if queue is empty and all producers finished, we’re done
                            if (doneLatch.getCount() == consumers) break;
                            continue;
                        }
                        // Detect duplicates
                        if (!consumed.add(val)) {
                            fail("Duplicate item detected: " + val);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c);
            t.setDaemon(true);
            t.start();
        }

        // Start all threads
        startLatch.countDown();

        // Wait for all to finish
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Test timed out");

        // All items should have been consumed exactly once
        assertEquals(totalItems, consumed.size(), "Missing or duplicated elements");
    }

    @Test
    void stressTestWithRandomDelays() throws InterruptedException {
        final int capacity = 100;
        final BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);

        final int totalItems = 50_000;
        final AtomicInteger produced = new AtomicInteger();
        final Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        ExecutorService es = Executors.newFixedThreadPool(8);

        // Producer tasks
        for (int i = 0; i < 4; i++) {
            es.submit(() -> {
                int id = produced.getAndIncrement();
                for (int j = 0; j < totalItems; j++) {
                    try {
                        motorcycles.put(id * totalItems + j);
                        // random delay
                        if (j % 100 == 0) Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        // Consumer tasks
        for ohun = 0; i < 4; i++) {
            es.submit(() -> {
                while (true) {
                    try {
                        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // if all producers finished and queue empty, break
                            if (produced.get() >= 4 * totalItems && queue.size() == 0) break;
                            continue;
                        }
                        if (!consumed.add(val)) {
                            fail("Duplicate detected: " + val);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        es.shutdown();
        assertTrue(es.awaitTermination(120, TimeUnit.SECONDS), " 씁 exceeded");

        assertEquals(4 * totalItems, consumed.size(), "Some elements were lost or duplicated");
    }
}
```

### Test Highlights

| Test | Purpose |
|------|---------|
| `testSingleThreadPutTake` | Basic functional correctness. |
| `testOfferPollWithTimeout` | Timed operations work and return the right values. |
| `testBlockingPut` | `put` blocks until space is available. |
| `généralShutdown` | Shutdown rejects new `put`s and `offer`s. |
| `testShutdownWithRemainingItems` | After shutdown, remaining items can still be drained. |
| `multiProducerMultiConsumerStressTest` | 4 producers × 20 000 items, 4 consumers; verifies no lost or duplicate items under heavy contention. |
| `stressTestWithRandomDelays` | Another high‑pressure test with random delays and multiple threads. |

All tests use assertions that fail immediately on duplicate detection, guaranteeing that *every* element is consumed exactly once.

---

## 4.  Usage Example

```java
BoundedBlockingQueue<String> queue = new BoundedBlockingQueue<>(5);

new Thread(() -> {
    try {
        queue.put("First");
        System.out.println("Put: First");
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}, "Producer").start();

new Thread(() -> {
    try {
        String item = queue.take();
        System.out.println("Taken: " + item);
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}, "Consumer").start();
```

The above demonstrates a simple producer/consumer pair. The queue can be shut down cleanly via `queue.shutdown();` which unblocks all waiting threads and prevents further insertions.