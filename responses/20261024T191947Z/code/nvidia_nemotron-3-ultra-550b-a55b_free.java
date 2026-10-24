<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261024T191947Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-quality implementation of a generic bounded blocking queue with a detailed concurrency strategy document and comprehensive JUnit 5 tests.

---

### 1. Concurrency Strategy Documentation

#### `BoundedBlockingQueue<E>` - Concurrency Design Document

**1. Core Synchronization Primitive: `ReentrantLock`**
We use a single `ReentrantLock` (`lock`) guarding all mutable state (`head`, `tail`, `count`, `items`, `shutdown`).
*   **Why not `synchronized`?** `ReentrantLock` allows `lockInterruptibly()` (critical for responding to thread interruption during `put`/`take`) and fair ordering policies (optional constructor arg). It also allows multiple `Condition` variables per lock.

**2. Condition Variables: Dual-Condition Signaling**
Two `Condition` objects are associated with the single lock:
*   `notEmpty`: Signaled when an element is added (`count` transitions 0 -> 1). Waited upon by `take()`/`poll()`.
*   `notFull`: Signaled when an element is removed (`count` transitions `capacity` -> `capacity-1`). Waited upon by `put()`/`offer()`.

*   **Why two conditions?** "Monitor Pattern" efficiency. If we used a single condition (`lock.newCondition()`), a `signalAll()` on every mutation would wake up *both* producers and consumers. Producers waking up to find the queue still full (or consumers waking to find it empty) causes "spurious wakeups" / contention. Dual conditions allow **targeted signaling**: `notEmpty.signal()` wakes *only* waiting consumers; `notFull.signal()` wakes *only* waiting producers.

**3. State Representation: Circular Array**
*   `final Object[] items`: Fixed-size array allocated at construction.
*   `int head`: Index of next element to dequeue.
*   `int tail`: Index of next slot to enqueue.
*   `int count`: Current number of elements.
*   **Invariant:** `0 <= count <= capacity`. `count == 0` implies empty. `count == capacity` implies full. `head`/`tail` wrap modulo `capacity`.

**4. Shutdown Protocol (Poison Pill vs. Explicit Flag)**
We use an explicit `volatile boolean shutdown` flag + `lock` protection.
*   **Shutdown Initiation:** `shutdown()` acquires lock, sets `shutdown = true`, calls `notEmpty.signalAll()` + `notFull.signalAll()` to unblock all waiters.
*   **Producer Semantics (`put`/`offer`):** Check `shutdown` *inside* lock. If true, throw `IllegalStateException` (put) or return `false` (offer). No elements accepted post-shutdown.
*   **Consumer Semantics (`take`/`poll`):**
    *   If `shutdown && count == 0`: Return `null` (poll) / throw `NoSuchElementException` (take).
    *   If `shutdown && count > 0`: **Drain remaining elements normally.** This ensures "clean shutdown" — no data loss for elements already enqueued.

**5. Interruption Handling**
*   `put` / `take`: Use `lock.lockInterruptibly()`. If interrupted while waiting, `InterruptedException` is thrown, lock is released automatically. State remains consistent.
*   `offer` / `poll` (timed): Use `condition.awaitNanos(...)`. If interrupted, `InterruptedException` thrown. Return `false`/`null` on timeout.

**6. Memory Visibility**
*   `shutdown` is `volatile` (visible immediately to all threads without lock for the initial check in `isShutdown()`).
*   All field mutations (`head`, `tail`, `count`, `items` slots) happen *inside* `lock`, establishing **happens-before** edges via lock release/acquire. No `volatile` needed on array slots or indices.

**7. Generics & Type Safety**
*   Internal storage is `Object[]` (arrays cannot be generic in Java).
*   `@SuppressWarnings("unchecked")` cast on `take`/`poll`/`peek` is safe because `put`/`offer` only accept `E` and store into `Object[]`.

---

### 2. Implementation (`BoundedBlockingQueue.java`)

```java
package com.example.concurrent;

import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implemented using a circular array
 * backed by a ReentrantLock and dual Condition variables.
 *
 * Features:
 * - Blocking put/take with interruption support.
 * - Timed offer/poll with nanosecond precision.
 * - Clean shutdown protocol: producers fail fast, consumers drain remaining elements.
 * - Fairness policy configurable via constructor.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] items;
    private final int capacity;

    private int head = 0; // Index to take from
    private int tail = 0; // Index to put at
    private int count = 0; // Current size

    // Volatile for visibility of shutdown flag without locking (for isShutdown())
    private volatile boolean shutdown = false;

    // --- Synchronization ---
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    /**
     * Creates a queue with the given capacity and default non-fair locking policy.
     * @param capacity the maximum number of elements (must be > 0)
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     * @param capacity the maximum number of elements (must be > 0)
     * @param fair if true, uses fair ordering for lock acquisition (FIFO thread queueing)
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shutdown
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        lock.lockInterruptibly(); // Supports interruption during lock acquisition
        try {
            // Wait while full AND not shutdown
            while (count == capacity && !shutdown) {
                notFull.await(); // Releases lock, waits for signal
            }
            
            if (shutdown) {
                throw new IllegalStateException("Queue shut down, cannot put");
            }
            
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws NoSuchElementException if queue is shutdown and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait while empty AND not shutdown
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            
            if (count == 0 && shutdown) {
                throw new NoSuchElementException("Queue shut down and empty");
            }
            
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return true if successful, false if timeout elapsed or queue shutdown
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            
            if (shutdown) return false;
            
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     * @return the head of this queue, or null if timeout elapsed or queue shutdown and empty
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            
            if (count == 0 && shutdown) return null;
            
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Non-Blocking / Inspection ---

    /** Returns true if this queue contains no elements. */
    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    /** Returns true if this queue is full. */
    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    /** Returns the number of elements in this queue. */
    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    /** Returns the capacity of this queue. */
    public int capacity() { return capacity; }

    /** Retrieves, but does not remove, the head of this queue, or returns null if empty. */
    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) items[head];
        } finally { lock.unlock(); }
    }

    // --- Shutdown Protocol ---

    /**
     * Initiates an orderly shutdown.
     * - Producers (put/offer) will immediately fail/reject.
     * - Consumers (take/poll) will drain remaining elements normally.
     * - Once drained, consumers will receive shutdown signals (null/exception).
     * - Unblocks all waiting threads.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up to re-evaluate state
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if shutdown has been initiated. */
    public boolean isShutdown() {
        return shutdown; // Volatile read
    }

    // --- Internal Helpers ---

    @SuppressWarnings("unchecked")
    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == capacity) tail = 0;
        count++;
        notEmpty.signal(); // Wake ONE waiting consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        notFull.signal(); // Wake ONE waiting producer
        return e;
    }
}
```

---

### 3. Comprehensive JUnit 5 Tests (`BoundedBlockingQueueTest.java`)

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;
    private static final int SMALL_CAPACITY = 2;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    // ==========================================
    // Basic Functionality & Contract Tests
    // ==========================================

    @Test
    void testInitialState() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());
        assertEquals(CAPACITY, queue.capacity());
        assertNull(queue.peek());
        assertFalse(queue.isShutdown());
    }

    @Test
    void testPutAndTakeSingleElement() throws InterruptedException {
        queue.put(42);
        assertEquals(1, queue.size());
        assertEquals(42, queue.peek());
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testFifoOrdering() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(queue::put);
        for (int i = 0; i < CAPACITY; i++) {
            assertEquals(i, queue.take());
        }
    }

    @Test
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testCapacityLimit() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> {
            try { queue.put(i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(queue.isFull());
        assertEquals(CAPACITY, queue.size());
    }

    // ==========================================
    // Blocking Behavior Tests
    // ==========================================

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQ = new BoundedBlockingQueue<>(SMALL_CAPACITY);
        smallQ.put(1);
        smallQ.put(2);
        assertTrue(smallQ.isFull());

        Thread putter = new Thread(() -> {
            try { smallQ.put(3); } catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(100); // Ensure putter is waiting
        assertTrue(putter.isAlive(), "Putter should be blocked");

        assertEquals(1, smallQ.take()); // Make space
        putter.join(1000); // Putter should proceed and finish
        assertFalse(putter.isAlive(), "Putter should have unblocked and finished");
        assertEquals(2, smallQ.take());
        assertEquals(3, smallQ.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        taker.start();
        Thread.sleep(100);
        assertTrue(taker.isAlive(), "Taker should be blocked");

        queue.put(99);
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    // ==========================================
    // Timed Operations Tests
    // ==========================================

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQ = new BoundedBlockingQueue<>(SMALL_CAPACITY);
        assertTrue(smallQ.offer(1, 100, TimeUnit.MILLISECONDS));
        assertTrue(smallQ.offer(2, 100, TimeUnit.MILLISECONDS));
        assertFalse(smallQ.offer(3, 50, TimeUnit.MILLISECONDS)); // Timeout
        assertEquals(2, smallQ.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS)); // Timeout empty
        queue.put(10);
        assertEquals(10, queue.poll(50, TimeUnit.MILLISECONDS)); // Immediate success
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS)); // Timeout empty again
    }

    @Test
    void testOfferPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQ = new BoundedBlockingQueue<>(SMALL_CAPACITY);
        smallQ.put(1); smallQ.put(2); // Full
        
        Thread putter = new Thread(() -> {
            try { smallQ.put(3); } catch (InterruptedException e) { /* Expected */ }
        });
        putter.start();
        Thread.sleep(50);
        putter.interrupt();
        putter.join(1000);
        assertFalse(putter.isAlive());
        // Queue state unchanged
        assertEquals(2, smallQ.size());
    }

    // ==========================================
    // Shutdown Protocol Tests
    // ==========================================

    @Test
    void testShutdownRejectsProducers() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(2, 1, TimeUnit.SECONDS));
        // Existing element still there
        assertEquals(1, queue.take());
    }

    @Test
    void testShutdownAllowsDrainThenSignalsEmpty() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown
        assertThrows(NoSuchElementException.class, () -> queue.take());
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdownUnblocksWaiters() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQ = new BoundedBlockingQueue<>(SMALL_CAPACITY);
        smallQ.put(1); smallQ.put(2); // Full
        
        AtomicBoolean putterUnblocked = new AtomicBoolean(false);
        Thread putter = new Thread(() -> {
            try { smallQ.put(3); } 
            catch (IllegalStateException e) { putterUnblocked.set(true); } // Shutdown exception
            catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(50);
        smallQ.shutdown();
        putter.join(1000);
        assertTrue(putterUnblocked.get(), "Putter should unblock with IllegalStateException");
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // ==========================================
    // Fairness Policy Test
    // ==========================================

    @Test
    void testFairnessPolicyConstruction() {
        BoundedBlockingQueue<Integer> fairQ = new BoundedBlockingQueue<>(10, true);
        BoundedBlockingQueue<Integer> unfairQ = new BoundedBlockingQueue<>(10, false);
        // Just verify construction works; actual fairness testing is non-deterministic in unit tests
        assertNotNull(fairQ);
        assertNotNull(unfairQ);
    }

    // ==========================================
    // Stress Test: Multi-Producer / Multi-Consumer
    // ==========================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS) // Global safety net
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use LongAdder for high-contention counters
        LongAdder producedSum = new LongAdder();
        LongAdder consumedSum = new LongAdder();
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicBoolean producerError = new AtomicBoolean(false);
        AtomicBoolean consumerError = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = producerId * itemsPerProducer + i; // Unique values
                        stressQueue.put(value); // Blocking put
                        producedSum.add(value);
                        producedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    producerError.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Consumers don't know exact count, just run until shutdown + empty
                    while (true) {
                        Integer val = stressQueue.take(); // Blocking take
                        if (val == null) break; // Should not happen with current impl (throws exception)
                        consumedSum.add(val);
                        consumedCount.incrementAndGet();
                    }
                } catch (NoSuchElementException e) {
                    // Expected on shutdown + empty
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    consumerError.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- GO ---
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for producers to finish
        // We wait on a separate latch just for producers to know when to shutdown
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        // Hack: Re-use doneLatch logic? Better: separate latch.
        // Actually, doneLatch counts down for everyone. We need to know when producers specifically finish.
        // Let's just wait for doneLatch but shutdown when producers are done.
        
        // Wait for producers to finish (polling doneLatch is messy).
        // Better: Use a specific latch for producers.
        // Let's restructure slightly for the test logic clarity:
        // We will wait for `producedCount` to reach totalItems.
        
        while (producedCount.get() < totalItems) {
            if (doneLatch.getCount() == 0) break; // Safety
            Thread.sleep(10);
        }
        
        // Producers finished. Initiate shutdown.
        stressQueue.shutdown();
        
        // Wait for consumers to finish draining
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        // --- Verification ---
        assertTrue(finished, "Test timed out waiting for threads to finish");
        assertFalse(producerError.get(), "Producer thread crashed");
        assertFalse(consumerError.get(), "Consumer thread crashed");
        
        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed (Lost/Duplicated)");
        assertEquals(producedSum.sum(), consumedSum.sum(), "Data corruption: Sum mismatch (Lost/Duplicated/Modified elements)");
        assertTrue(stressQueue.isEmpty(), "Queue should be empty after drain");
        assertTrue(stressQueue.isShutdown());
        
        System.out.printf("Stress Test Passed: %d items in %d ms (Throughput: %.0f ops/sec)%n", 
            totalItems, durationMs, (totalItems * 2.0 / durationMs) * 1000); // *2 for put+take
    }
    
    // ==========================================
    // Edge Cases / Concurrency Correctness
    // ==========================================

    @Test
    void testConcurrentPeekAndSize() throws InterruptedException {
        final int threads = 10;
        final int ops = 1000;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(100);
        Count