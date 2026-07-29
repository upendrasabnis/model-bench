<!-- model: nvidia/nemotron-3-nano-30b-a3b:free | category: code | run: 20260729T205329Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
We need to produce Java code for a thread-safe generic bounded blocking queue without using java.util.concurrent.BlockingQueue. Must support blocking put/take, timed offer/poll, and clean shutdown. Document concurrency strategy. Then write comprehensive JUnit 5 tests, including multi-producer/multi-consumer stress test verifying no lost or duplicated elements.

We need to output code snippets: the implementation class, maybe a manager class, and test class. Must be comprehensive but concise.

We need to explain concurrency strategy (using intrinsic locks, condition variables via wait/notifyAll, etc). Provide thread-safe generic bounded blocking queue.

Implementation details: generic type E, capacity, count, head, tail, queue array, locks, notFull, notEmpty condition objects. Provide methods: put(E e) blocks until space; take() blocks until element; offer(E e, long timeout, TimeUnit unit) returns true if inserted within timeout; poll(long timeout, TimeUnit unit) returns null if not available; shutdown() to stop further operations, possibly unblocking waiting threads.

We can also provide a main class that uses this queue.

We'll need to handle interruption and shutdown: when shutdown, put/take may throw RejectedExecutionException or return false; maybe use a boolean closed flag.

The tests: JUnit5 test class with several test methods: simple put/take, timed offer/poll, shutdown behavior, multi-producer/multi-consumer stress test.

Stress test: spawn multiple producer threads and consumer threads that loop for a certain number of items, using a bounded queue of capacity e.g., 100. Use a shared counter to track total produced and consumed; ensure no duplicates or losses.

We'll need to use CountDownLatch for start synchronization, and AtomicInteger for produced/consumed counts.

We need to ensure no lost or duplicated elements: we can collect items into a ConcurrentLinkedQueue or use a Set to detect duplicates.

We'll need to handle timed offer/poll: use offer with timeout and assert that it returns false when queue full and timeout expires.

Also test that after shutdown, put throws exception or returns false.

Write code with appropriate imports.

Now produce final answer: first the implementation with documentation comments describing concurrency strategy. Then JUnit5 test class.

We need to be careful not to use java.util.concurrent.BlockingQueue; but we can use java.util.concurrent.locks and java.util.concurrent.atomic.

Let's craft the code.

Implementation:

```java
package com.example.queue;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A generic, bounded, thread‑safe blocking queue.
 *
 * <p>Implementation strategy:
 * <ul>
 *   <li>Fixed-size circular array stores the elements.</li>
 *   <li>Two indices – {@code head} (next take position) and {@code tail} (next put position) –
 *       are updated atomically using {@link java.util.concurrent.atomic.AtomicInteger}.</li>
 *   <li>A single intrinsic lock protects all structural modifications and the
 *       {@code size} field.  The lock is also used to coordinate waiting producers
 *       and consumers via two {@link Condition} objects.</li>
 *   <li>When the queue is full, {@code put} atomically waits on {@code notFull}
 *       until space becomes available.  When the queue is empty, {@code take}
 *       waits on {@code notEmpty}.</li>
 *   <li>Shutdown is performed by setting an {@code closed} flag.  Once closed,
 *       further {@code put} operations fail fast, and waiting consumers are
 *       awakened so they can exit.</li>
 * </ul>
 *
 * <p>The queue supports:
 * <ul>
 *   <li>Blocking {@code put} and {@code take}</li>
 *   <li>Timed {@code offer(long,TimeUnit)} and {@code poll(long,TimeUnit)}</li>
 *   <li>Graceful shutdown via {@code close()}</li>
 * </ul>
 *
 * @param <E> element type
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;
    private final int capacity;
    private volatile int head;          // next take index
    private volatile int tail;          // next put index
    private volatile int count;         // current size
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Condition notEmpty = new java.util.concurrent.locks.ConditionObject();
    private final Condition notFull = new java.util.concurrent.locks.ConditionObject();

    /**
     * Creates a bounded queue with the given capacity.
     *
     * @param capacity maximum number of elements; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Inserts the given element, waiting for space to become available.
     *
     * @param e element to insert, must not be null
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been closed
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        while (true) {
            if (closed.get()) {
                throw new IllegalStateException("Queue is closed");
            }
            // Acquire lock and check capacity
            synchronized (this) {
                while (count == capacity) {
                    // queue full – wait for notFull signal
                    notFull.await();
                }
                // Insert element
                items[tail] = e;
                tail = (tail + 1) % capacity;
                count++;
                // Wake up a waiting consumer
                notEmpty.signal();
            }
            // Release lock before notifying others (outside synchronized block)
            // The loop repeats only if interrupted; otherwise we break
            return;
        }
    }

    /**
     * Retrieves and removes the head of the queue, waiting until an element is
     * available or the queue is closed.
     *
     * @return the head element
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue has been closed and no element
     *         is available
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        while (true) {
            synchronized (this) {
                while (count == 0) {
                    if (closed.get()) {
                        throw new IllegalStateException("Queue is closed and empty");
                    }
                    // queue empty – wait for notEmpty signal
                    notEmpty.await();
                }
                // Retrieve element
                E e = (E) items[head];
                items[head] = null; // help GC
                head = (head + 1) % capacity;
                count--;
                // Wake up a waiting producer
                notFull.signal();
                return e;
            }
        }
    }

    /**
     * Tries to insert {@code e} if space is available within the given timeout.
     *
     * @param e         element to insert, must not be null
     * @param timeout   maximum time to wait
     * @param unit      time unit of {@code timeout}
     * @return {@code true} if the element was inserted, {@code false} otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        while (nanos > 0) {
            if (closed.get()) {
                throw new IllegalStateException("Queue is closed");
            }
            synchronized (this) {
                while (count == capacity) {
                    // wait for space
                    if (!notFull.awaitNanos(nanos)) {
                        return false; // timed out
                    }
                    // else continue waiting
                }
                // Insert
                items[tail] = e;
                tail = (tail + 1) % capacity;
                count++;
                notEmpty.signal();
                return true;
            }
        }
        return false; // timeout <= 0 and not inserted
    }

    /**
     * Retrieves and removes the head of the queue if one is available within the
     * given timeout.
     *
     * @param timeout   maximum time to wait
     * @param unit      time unit of {@code timeout}
     * @return an element, or {@code null} if the timeout expires or the queue
     *         is closed without elements
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        while (nanos > 0) {
            if (closed.get()) {
                return null;
            }
            synchronized (this) {
                while (count == 0) {
                    if (!notEmpty.awaitNanos(nanos)) {
                        return null; // timed out
                    }
                }
                // Take element
                E e = (E) items[head];
                items[head] = null;
                head = (head + 1) % capacity;
                count--;
                notFull.signal();
                return e;
            }
        }
        return null;
    }

    /**
     * Closes the queue.  After closing:
     * <ul>
     *   <li>Further {@code put} calls throw {@link IllegalStateException}.</li>
     *   <li>Waiting consumers are awakened and may finish normally.</li>
     *   <li>Waiting producers are awakened and may abort.</li>
     * </ul>
     * This method is idempotent.
     */
    public void close() {
        closed.set(true);
        // Wake up everyone so they can exit promptly
        synchronized (this) {
            notEmpty.signalAll();
            notFull.signalAll();
        }
    }

    /** @return the current number of elements in the queue */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /** @return the maximum capacity of the queue */
    public int capacity() {
        return capacity;
    }
}
```

Now JUnit 5 tests.

Create a test class:

```java
package com.example.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;

import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    private static final int CAPACITY = 100;
    private static final int ITEMS_PER_PRODUCER = 10_000;
    private static final int NUM_PRODUCERS = 8;
    private static final int NUM_CONSUMERS = 8;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @Test
    void simplePutTake() throws Exception {
        queue.put(42);
        assertEquals(42, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take()); // empty now
    }

    @Test
    void timedOfferPoll() throws Exception {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());

        // poll with short timeout should succeed immediately
        assertNotNull(queue.poll(10, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());

        // poll after empty with timeout should return null
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void putAfterCloseThrows() {
        queue.close();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
    }

    @Test
    void takeAfterCloseWhenEmptyThrows() throws Exception {
        queue.close();
        assertThrows(IllegalStateException.class, queue::take);
    }

    @Test
    void multiProducerConsumerNoLossNoDup() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        ConcurrentSkipListSet<Integer> seen = new ConcurrentSkipListSet<>();

        // producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int id = i;
            exec.submit(() -> {
                try {
                    for (int v = 0; v < ITEMS_PER_PRODUCER; v++) {
                        Integer val = id * ITEMS_PER_PRODUCER + v;
                        queue.put(val);
                        produced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            exec.submit(() -> {
                try {
                    while (true) {
                        Integer v = queue.take();
                        if (!seen.add(v)) {
                            fail("Duplicate element detected: " + v);
                        }
                        consumed.incrementAndGet();
                        // stop condition: when all expected items have been consumed
                        if (consumed.get() == NUM_PRODUCERS * ITEMS_PER_PRODUCER) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all tasks to finish
        exec.shutdown();
        exec.awaitTermination(60, TimeUnit.SECONDS);
        queue.close(); // ensure any blocked threads exit

        assertEquals(NUM_PRODUCERS * ITEMS_PER_PRODUCER, produced.get());
        assertEquals(NUM_PRODUCERS * ITEMS_PER_PRODUCER, consumed.get());
        assertEquals(NUM_PRODUCERS * ITEMS_PER_PRODUCER, seen.size());
    }
}
```

But need to handle shutdown in stress test: we can close the queue after some time or after all tasks finish; but we need to ensure producers may block on put; we can let them finish by not closing until after execution; but if producers block because queue full, they will wait until consumer takes; that's fine. However, we need to ensure they can exit; they will block indefinitely on put if we never close. To avoid hanging, we can have producers put a sentinel value after producing all items, or we can close the queue before they finish? Actually, we can design producers to put exactly their quota; they will block only when queue is full, but consumers will eventually take, so they will unblock and finish. After all producers finish, we can close the queue to wake up any remaining waiting consumers. In the test, after exec.shutdown and awaitTermination, all producers have finished, but some consumers may still be waiting on take; we close the queue to wake them and let them exit.

Modify stress test accordingly: after exec.shutdown and awaitTermination, call queue.close(); then join consumers? Actually, we can just let them exit after they see sentinel? But they take until they have consumed total expected count; they will break when consumed.get reaches expected total. That condition may be reached before all consumers finish, but some may still be blocked on take after queue empty but not closed. To avoid indefinite block, after exec.shutdown, we can close the queue to wake them; but they may still be blocked on notEmpty.await; after close, notEmpty.signalAll() is called, waking them; they will re-enter loop, count check will see consumed==expected and break. So it's fine.

But we need to ensure that after producers finish, the consumed count will eventually reach expected total; but there may be a race where some consumers exit early before counting all items? Actually, each consumer loops while true, taking items and increment consumed; after each take, they check consumed.get() == expected; when it reaches expected, they break. Since total number of items produced is known, eventually consumed reaches expected and all consumers will break eventually. However, there could be scenario where some consumer takes multiple items and increments consumed many times, eventually reaching expected and breaking; that's fine.

Potential issue: if a consumer takes an item after the expected count already reached by other consumers, they may overshoot? No, they only break when consumed reaches expected, not when they individually have consumed enough; they may overshoot but then later break; but they may continue taking after expected reached? They break when consumed.get() == expected, so after they take an item that makes consumed exceed expected, they will still break because condition is == expected, not >=; if consumed overshoots, they will not break; they will keep looping, causing potential infinite wait. To avoid that, we could break when consumed.get() >= expected; but we need to ensure we don't overshoot too much. Simpler: after all producers finish, we can close the queue and then each consumer will eventually take until queue empty and then break when closed and empty? But we want to verify no lost or duplicate elements; we can just collect all taken items into a Set and assert size equals expected total. That avoids needing to break condition. Let's adjust test: we can have a shared ConcurrentLinkedQueue<Integer> collected; each consumer after take adds to it; after all tasks finish, we assert collected.size() == expected total and also that there are no duplicates (set size equals collected size). That's easier.

Let's rewrite stress test accordingly.

Implementation:

- Expected total = NUM_PRODUCERS * ITEMS_PER_PRODUCER.
- Use ConcurrentLinkedQueue<Integer> collected = new ConcurrentLinkedQueue<>();
- In consumer task, loop while (!Thread.currentThread().isInterrupted()) { try { Integer v = queue.take(); collected.add(v); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } But need termination condition: after producers finish and queue closed, consumers will eventually block on take; we can close queue to wake them; but they still may block again if queue empty after close. To stop them, we can use a sentinel value or use a fixed number of items to consume: each consumer can take until collected.size() reaches expected total; but duplicates may appear; better: each consumer can take until an atomic integer "remaining" decrements to zero; but duplicates may cause overshoot. Simpler: after all producers finish, we can close the queue and then join a fixed amount of time for consumers to drain; but not deterministic.

Alternative: Use a bounded number of items to consume per consumer: each consumer consumes exactly ITEMS_PER_PRODUCER * NUM_PRODUCERS / NUM_CONSUMERS (maybe not integer). But we can just let each consumer take until a predetermined number of items have been taken overall using an AtomicInteger "taken" that increments on each take; they can break when taken.get() == expected; but again overshoot risk. Use taken.incrementAndGet() and break when taken.get() >= expected; but then some may take extra after expected; but they will still have taken extra items beyond expected, causing duplicates? Actually they will still add to collected; but we only care that total collected size equals expected; extra items would cause size > expected, which we can detect as failure. So we can just let them take until they encounter a timeout or after a certain period; but not ideal.

Simplify: Use a fixed number of take operations per consumer: each consumer performs exactly ITEMS_PER_PRODUCER * NUM_PRODUCERS / NUM_CONSUMERS takes (maybe not integer). To avoid fractional, we can set NUM_CONSUMERS = 1 for stress test; but requirement is multi-producer/multi-consumer. Could set NUM_CONSUMERS = 2 and have each consumer take exactly half of expected items. Use a split.

Simpler: Use a CountDownLatch for number of items to be taken. Each consumer, after taking an item, counts down the latch; when latch reaches zero, they break. That ensures exactly expected number of takes across all consumers. The latch can be shared and each take counts down; duplicates will cause count down still happen but duplicates will be detected later.

Implementation: At start, CountDownLatch latch = new CountDownLatch(expectedTotal); In consumer task, after taking an item, latch.countDown(); then after loop, break when latch.getCount() == 0? Actually they can just call latch.await() after counting down? Not needed. They can just continue until they have taken some items; but they need to know when to stop. Could have each consumer take in a while (true) loop but break when latch.getCount() == 0; but multiple threads may race; fine.

Thus:

- latch = new CountDownLatch(expectedTotal);
- In consumer: while (true) { try { Integer v = queue.take(); collected.add(v); latch.countDown(); if (latch.getCount() == 0) break; } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }

But latch.getCount() may be accessed after countDown; okay.

But we must ensure that all takes happen before latch reaches zero; producers may still be putting after some takes; but that's fine; we just need to ensure all expected items are taken. However, if a consumer takes an item that is not part of expected set (e.g., duplicate?), duplicates will still countDown, causing latch to finish early, causing other consumers to break prematurely, possibly missing items. That's okay because duplicates cause extra countDown but still count as one; but we need to guarantee that the total number of items taken equals expected total; duplicates would cause extra takes beyond expected, causing latch to finish later (since each take counts down regardless of duplicate). Actually if duplicates happen, they still count as a take, so latch will count down for each take, including duplicates; thus latch will reach zero after expected total takes, even if duplicates occur; but duplicates cause extra items to be taken beyond expected? Not extra; the expected total is the number of puts; duplicates cause some takes to be of items already taken, but still each take consumes one item from the queue, which must have been produced earlier; if duplicates occur, some items are taken twice, meaning some other items are never taken, causing missing items. But latch will still count down for each take; if some items are never taken, the latch may never reach zero because not enough takes happen; but producers may keep putting more items after latch reaches zero? Actually producers will finish after putting their quota; after that, queue may still have items; consumers will continue taking until latch reaches zero; if some items remain un-taken, latch will never reach zero, causing consumers to block indefinitely. To avoid that, we need to ensure that all produced items are eventually taken; duplicates cause some items to be taken multiple times, which means some other items are left un-taken, causing latch not to reach zero. So duplicate detection will catch that because collected size will be less than expected, but we also need to ensure latch reaches zero; if duplicates cause missing items, latch may never reach zero, causing test to hang. To avoid hanging, we can set a timeout for the whole test; but we want to guarantee no lost or duplicated elements; we can verify after all tasks finish that collected size equals expected and also that there are no duplicates (i.e., collected size equals expected). If duplicates exist, collected size may still equal expected? Actually duplicates cause some items to be taken multiple times; collected size (set) will be less than expected; but we also need to ensure that we have taken exactly expected number of items (i.e., latch reached zero). If duplicates cause missing items, latch may not reach zero, causing test to hang; but we can set a timeout for the whole test method; if it hangs, test fails. That's okay for demonstration.

But for a robust stress test, we can avoid duplicates by not allowing them; but we want to test that they don't happen. So we can design the test such that we expect exactly expected total items to be taken, and we will count down a shared AtomicInteger "remaining" that starts at expected total; each take decrements it; when it reaches zero, we break. This ensures that exactly expected total takes happen, regardless of duplicates; but duplicates will cause extra takes beyond expected, causing remaining to go negative; we can guard against that: only decrement if remaining.get() > 0; but then duplicates will not decrement remaining, causing remaining to stay >0 and consumers may wait indefinitely. Better: Use a ConcurrentLinkedQueue of items to be taken? Not needed.

Simplify: Use a BlockingQueue itself to collect items? But we cannot use java.util.concurrent.BlockingQueue in implementation; but we can use it in test? Probably not advisable. But we can use a simple shared List and AtomicInteger for count; but we need to ensure we count exactly expected total takes; we can have each consumer take until a shared AtomicInteger "taken" reaches expected total; they can call taken.incrementAndGet() after each take; if taken.get() > expected, we can break; but duplicates will cause taken to exceed expected, but that's okay; we just need to ensure that we don't miss any items; if duplicates happen, some items will be taken multiple times, causing taken to exceed expected, but we may still have taken all expected items at some point; but some items may never be taken (if duplicate takes replace them). However, because we are using a bounded queue, duplicates can only happen if the same element is inserted multiple times; that's possible if producers generate duplicate values; in our stress test we generate unique values per producer (id * ITEMS_PER_PRODUCER + v). So duplicates cannot happen unless there is a bug causing same value to be inserted twice. So duplicates would indicate a bug. So we can just assert that collected size equals expected total; if duplicates happen, collected size will be less, causing test failure. So we don't need to enforce exact count of takes; we just need to ensure that all produced items are eventually taken (i.e., collected size equals expected). To guarantee that, we can close the queue after producers finish; after close, consumers will eventually drain the queue (since they will be awakened). However, some consumers may still be blocked if they are waiting for space? No, they are waiting for items; after close, they will be awakened when items become available; eventually they will drain all items because producers have finished; but if duplicates cause some items to be taken twice, some items may remain untouched; but then after all producers finish, the queue will eventually become empty; consumers will block on take; we can close the queue again? Actually close already signaled; but they may still block; we can use a timeout for the whole test.

Simplify: After producers finish, we close the queue; then we await a reasonable time for consumers to drain; after that, we check collected size. If duplicates cause missing items, collected size will be less; test fails. If no duplicates, collected size equals expected.

Thus test can be:

- Start producers.
- When all producers finish (we can use CountDownLatch for producers), we close the queue.
- Then we join consumer threads with a timeout (e.g., 5 seconds).
- After that, we assert collected.size() == expectedTotal.
- Also assert that collected size equals expectedTotal (no duplicates) and that there are no duplicates (collected size == expectedTotal).

Implementation details:

- Use CountDownLatch producerLatch = new CountDownLatch(NUM_PRODUCERS);
- In each producer task, after finishing putting all items, countDown.
- After exec.shutdown and awaitTermination