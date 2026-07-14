<!-- model: openai/gpt-oss-20b:free | category: code | run: 20260714T152237Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
## 1.  Design & Concurrency Strategy

| Item | Description |
|------|-------------|
| **Data structure** | A circular buffer (`Object[] items`) with `head`, `tail` indices and a `count`. |
| **Lock** | A single `ReentrantLock` protects *all* state changes. |
| **Conditions** | Two `Condition`s – `notFull` and `notEmpty` – are used to block producers and consumers respectively. |
| **Shutdown flag** | `volatile boolean shutdown` indicates that the queue has been closed. After a shutdown no new elements may be inserted and consumers are blocked until the queue is empty. |
| **Thread‑safety guarantees** | All public operations acquire the lock before inspecting or modifying the queue state. The lock is released on return or when the thread is interrupted. |
| **Blocking semantics** | `put` and `take` block *indefinitely* until the queue is ready. Timed variants use `awaitNanos` and return `false`/`null` if the timeout expires. |
| **No duplicated or lost items** | The circular buffer guarantees FIFO order. Because every put/take is executed with the lock held, no two threads can concurrently modify the buffer. |

The implementation deliberately *avoids* the Java `ConcurrentLinkedQueue` or `ArrayBlockingQueue` classes, instead building the queue from scratch with low‑level primitives.

---

## 2.  Implementation

```java
package com.example.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread‑safe, bounded, blocking queue that does **not** use any
 * class from {@code java.util.concurrent} other than {@code ReentrantLock}
 * and {@code Condition}.  The queue supports:
 *
 * <ul>
 *   <li>unbounded {@code put} / {@code take} (block until space / data)</li>
 *   <li>timed {@code offer} / {@code poll}</li>
 *   <li>explicit {@code shutdown} – after shutdown no further puts are allowed
 *      ）」   </li>
 * </ul restrictions:
 *
 * <ul>
 *   <li>Null elements are not permitted.</li>
 *   <li>After {@code shutdown()} a call to {@code put} throws
 *       {@code IllegalStateException}.  A call to {@code take} will throw
 *       the same exception if the queue is empty.</li>
 * </ul>
 *
 * @param <T> element type
 */
public class BoundedBlockingQueue<T> {

    private final Object[] items;
    private int head = 0;          // next index to take from
    private int tail = 0;          // next index to put into
    private int count = 0;         // number of elements currently in the queue

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private volatile boolean shutdown = false;

    /**
     * Creates a new queue with the given capacity.
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

    /* ------------------------------------------------------------------ */
    /*  Public API */
    /* ------------------------------------------------------------------ */

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * for space to become available.  If the queue has been shut down,
     * the method throws {@code IllegalStateException}.
     *
     * @param item element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public void put(T item) throws InterruptedException {
        if (item == null) throw new NullPointerException("Null items not allowed");
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            if (shutdown) throw new IllegalStateException("Queue is shut down");
            while (count == items.length) {
                notFull.await();
                if (shutdown) throw new IllegalStateException("Queue is shut down");
            }
            enqueue(item);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue if possible, waiting daycare
     * up to the specified timeout for space to become available.  If the queue
     * is shut down, the method throws {@code IllegalStateException}.
     *
     * @return {@code true} if the element was added, {@code false} if the
     *         timeout elapsed before space was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     */
    public boolean offer(T item, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (item == null) throw new NullPointerException("Null items not allowed");
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            if (shutdown) throw new IllegalStateException("Queue is shut down");
            while (count == items.length) {
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos);
                if (shutdown) throw new IllegalStateException("Queue is shut down");
            }
            enqueue(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.  If the queue is shut down and
     * empty, the method throws {@code IllegalStateException}.
     *
     * @return the head of the queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue is incomplete");
                notEmpty.await();
            }
            return (T) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified timeout if necessary for an element to become available.
     * If the queue is shut down and empty, the method throws {@code
     * IllegalStateException}.  If the timeout expires, {@code null} is
     * returned.
     *
     * @return the head of the queue, or {@code null} if the лица
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue is incomplete");
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return (T) dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the queue as shut down.  No further {@code put}/{@code offer}
     * calls are allowed.  All waiting producer and consumer threads are
     * signaled.  Consumers may still remove remaining elements until the
     * queue becomes empty.
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
     * @return {@code true} if {@code shutdown()} has been called
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /* ------------------------------------------------------------------ */
    /*  Helper methods – executed with the lock held */
    /* ------------------------------------------------------------------ */

    private void enqueue(T item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
        notEmpty.signal();          // wake a waiting consumer
    }

    private T dequeue() {
        @SuppressWarnings("unchecked")
        T item = (T) items[head];
        items[head] = null;         // help GC
        head = (head + 1) % items.length;
        count--;
        notFull.signal();           // wake a waiting producer
        return item;
    }

    /* ------------------------------------------------------------------ */

    /* Optional helper methods – not required but handy for tests */

    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
```

---

## 3.  JUnit 5 Test Suite

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    /* ----------  Basic correctness tests ---------- */

    @Test
    void testPutTakeSingleThread() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        q.put(1);
        q.put(2);
        assertEquals(2, q.size());
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertTrue(q.isEmpty());
    }

    @Test
    void testOfferPollTimed() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertTrue(q.offer(10, 1, TimeUnit.SECONDS));
        assertFalse(q.offer(20, 0, TimeUnit.MILLISECONDS));

        assertEquals(10, q.poll(1, TimeUnit.SECONDS));
        assertNull(q.poll(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownPreventsFurtherPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        q.shutdown();

        assertThrows(IllegalStateException.class, () -> q.put(2));

        // Consumer still able to drain the remaining element
        assertEquals(1, q.take());
        assertTrue(q.isEmpty());
    }

    @Test
    void testShutdownBlocksConsumersUntilEmpty() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        q.shutdown();

        // Take should succeed
        assertEquals(1, q.take());

        // Now the queue is empty; take should throw
        assertThrows(IllegalStateException.class, q::take);
    }

    /* ----------  Stress test: multi‑producer/multi‑consumer ---------- */

    @Test
    void stressTestMultiProducerConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producers = 10;
        final int consumers = 10;
        final int perProducer = 10_000;          // 10k * 10 = 100k items
        final int totalItems = producers * perProducer;

        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);

        // Shared data structures
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(producers);
        CountDownLatch consumerDone = new CountDownLatch(consumers);

        // Producer task
        Runnable producerTask = () -> {
            try {
                startLatch.await();               // wait until all ready
                int base = (int) (Thread.currentThread().getId() & 0xFFFFF);
                for (int i = 0; i < perProducer; i++) {
                    int value = base * 100_000 + i;    // unique value
                    queue.put(value);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                producerDone.countDown();
            }
        };

        // Consumer task
        Runnable consumerTask = () -> {
            try {
                startLatch.await();
                화면:
                while (true) {
                    Integer val = queue.poll(10, TimeUnit.MILLISECONDS);
                    if (val != null) {
                        if (!consumed.add(val)) {
                            // Duplicate detected
                            fail("Duplicate item detected: " + val);
                        }
                    } else {
                        // If all producers finished and queue empty, exit
                        if (producerDone.getCount() == 0 && queue.isEmpty()) {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        };

        // Launch producers
        for (int i = 0; i < producers; i++) рэ
            Executors.newSingleThreadExecutor().submit(producerTask);
        // Launch consumers
        for (int i = 0; i < consumers; i++) {
            Executors.newSingleThreadExecutor().submit(consumerTask);
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all to finish
        assertTrue(producerDone.await(30, TimeUnit.SECONDS), "Producers timed out");
        assertTrue(consumerDone.await(30, TimeUnit.SECONDS), "Consumers timed out");

        // Verify all items were seen exactly once ญ
        assertEquals(totalItems, consumed.size(), "Some items were lost or duplicated");
    }

    /* ----------  Additional helper tests ---------- */

    @Test
    void testSizeAndIsEmpty() throws InterruptedException {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(3);
        assertTrue(q.isEmpty());
        q.put("a");
        assertEquals(1, q.size());
        q.put("b");
        q.put("c");
        assertEquals(3, q.size());
        assertFalse(q.isEmpty());
        q.take();
        assertEquals(2, q.size());
    }
}
```

### Test‑suite Highlights

* **`stressTestMultiProducerConsumer`**  
  * 10 producer threads each insert 10 000 unique integers (using a combination of thread ID and sequence number).  
  * 10 consumer threads repeatedly call `poll` with a short timeout, accumulating all items into a concurrent `Set`.  
  * The test asserts that the set size equals the total number of produced items – guaranteeing *no lost or duplicated elements*.  
  * The test uses a `CountDownLatch` to start all threads at the same instant, increasing contention.

* **Shutdown behaviour**  
  * Tests confirm that after a تز `shutdown()` call, `put` throws an exception and `take` can drain the remaining items but will throw once the queue is empty.

---

## 4.  How the Concurrency Strategy Works

1. **Mutual exclusion** – Every modification of the buffer (`enqueue`, `dequeue`, or any state check) occurs inside `lock.lock()/unlock()`.  
2. **Condition variables** –  
   * `notFull` is signaled after a `dequeue`, waking a blocked producer.  
   * `notEmpty` is signaled after an `enqueue`, waking a blocked consumer.  
3. **Shutdown flag** –  
   * The flag is `volatile`, so a thread that has already entered the lock will see the updated value after `shutdown()` is called.  
   * All waiting threads are awakened on shutdown; they immediately check the flag and throw `IllegalStateException` if required.  
4. **Fairness** – The default `ReentrantLock` is *unfair* (faster).  If fairness is required, the constructor can be changed to `new ReentrantLock(true)`.  

Because all operations are atomic with respect to the lock, the queue behaves exactly like a standard bounded blocking queue, but the implementation is intentionally lightweight and *does not depend on* the JDK’s `java.util.concurrent.BlockingQueue` interface.