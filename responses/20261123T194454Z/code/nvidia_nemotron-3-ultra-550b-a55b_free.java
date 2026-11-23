<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261123T194454Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to protect all internal state
 * (the circular buffer array, head/tail pointers, count, and shutdown flag).
 * Two {@link Condition} variables are associated with this lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added (signals waiting consumers).</li>
 *   <li><b>notFull</b>: Signaled when an element is removed (signals waiting producers).</li>
 * </ul>
 * </p>
 * <p>
 * <b>Why ReentrantLock + Condition over synchronized/wait/notify?</b>
 * <ul>
 *   <li><b>Multiple Condition Variables:</b> Allows precise signaling. `notEmpty` only wakes consumers; `notFull` only wakes producers.
 *       `synchronized` + `notifyAll` wakes everyone, causing "thundering herd" and spurious wakeups requiring re-checking loops.
 *   </li>
 *   <li><b>Interruptible Lock Acquisition:</b> `lockInterruptibly()` allows blocking operations (`put`, `take`) to respond to
 *       thread interruption immediately, which is standard behavior for `BlockingQueue`.
 *   </li>
 *   <li><b>Timed Waits:</b> `condition.awaitNanos()` provides precise timeout control for `offer/poll` with timeouts,
 *       handling nanos remainder correctly (standard `Object.wait(long, int)` is notoriously imprecise on some JVMs).
 *   </li>
 * </ul>
 * </p>
 * <p>
 * <b>Shutdown Protocol:</b>
 * <ul>
 *   <li>Calling {@link #shutdown()} sets a volatile `shutdown` flag and signals <b>all</b> waiting threads (both conditions).</li>
 *   <li>Waiting threads wake up, re-acquire the lock, see the shutdown flag, and throw {@link IllegalStateException}.</li>
 *   <li>Subsequent `put`/`offer` calls fail immediately with {@link IllegalStateException}.</li>
 *   <li>`take`/`poll` continue to drain remaining elements until empty, then throw {@link IllegalStateException} (or return null for poll).</li>
 * </ul>
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // Internal state guarded by 'lock'
    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private volatile boolean shutdown = false;

    // Synchronization primitives
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.items = new Object[capacity];
    }

    // --- Helper Methods (Require Lock Held) ---

    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == items.length) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == items.length) head = 0;
        count--;
        return e;
    }

    private void checkNotShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    // --- Public API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == items.length) {
                notFull.await(); // Releases lock, waits for signal
                checkNotShutdown(); // Re-check after wakeup (spurious wakeup or shutdown)
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E e = dequeue();
            notFull.signal(); // Wake up one waiting producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == items.length) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Handles spurious wakeups & precision
                checkNotShutdown();
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     * @return the head of this queue, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue immediately
     * if there is space, returning false otherwise.
     * @return true if successful, false if queue is full
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lock();
        try {
            checkNotShutdown();
            if (count == items.length) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue immediately,
     * or returns null if this queue is empty.
     * @return the head of this queue, or null if empty
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E poll() {
        lock.lock();
        try {
            if (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                return null;
            }
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Prevents future {@code put}/{@code offer} operations (throw {@link IllegalStateException}).</li>
     *   <li>Wakes up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Waiting threads throw {@link IllegalStateException} (blocking ops) or return false/null (timed ops).</li>
     *   <li>Existing elements remain accessible via {@code take}/{@code poll} until drained.</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Signal ALL waiters on both conditions to unblock them immediately
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isFull() {
        lock.lock();
        try { return count == items.length; } finally { lock.unlock(); }
    }

    public int capacity() {
        return items.length;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure clean state for next test
    }

    // --- Basic Functionality ---

    @Test
    @DisplayName("Basic FIFO offer/poll")
    void testBasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Capacity limit respected")
    void testCapacityLimit() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3)); // Full
        assertEquals(1, q.poll());
        assertTrue(q.offer(3)); // Space available
    }

    @Test
    @DisplayName("Put/Take blocking behavior (single thread simulation via timeout)")
    void testPutTakeBlocking() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        // Next put would block forever, so we test take first
        assertEquals(1, q.take());
        assertTrue(q.isEmpty());
    }

    // --- Timed Operations ---

    @Test
    @DisplayName("Offer with timeout succeeds when space available")
    void testOfferTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Offer with timeout fails when full")
    void testOfferTimeoutFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        assertFalse(q.offer(2, 50, TimeUnit.MILLISECONDS)); // Should timeout quickly
    }

    @Test
    @DisplayName("Poll with timeout returns element")
    void testPollTimeoutSuccess() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Poll with timeout returns null when empty")
    void testPollTimeoutFailure() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    // --- Shutdown Behavior ---

    @Test
    @DisplayName("Shutdown prevents new puts")
    void testShutdownPreventsPut() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertFalse(queue.offer(1, 10, TimeUnit.MILLISECONDS)); // Timed offer returns false
    }

    @Test
    @DisplayName("Shutdown unblocks waiting producers")
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill

        Thread producer = new Thread(() -> {
            try { q.put(2); fail("Should have thrown ISE"); }
            catch (IllegalStateException ignored) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50); // Ensure producer is waiting
        q.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should have unblocked and exited");
    }

    @Test
    @DisplayName("Shutdown unblocks waiting consumers")
    void testShutdownUnblocksConsumers() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should have thrown ISE"); }
            catch (IllegalStateException ignored) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(50);
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll()); // Poll returns null on empty shutdown
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // --- Concurrency Stress Tests ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Multi-Producer/Multi-Consumer Stress Test: No lost/duplicated elements")
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Phaser to start all threads simultaneously
        Phaser startPhaser = new Phaser(numProducers + numConsumers + 1); // +1 for main
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);

        // Track produced/consumed counts for verification
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // To detect duplicates/loss, we use a ConcurrentHashMap as a multiset
        // Key: Integer value, Value: AtomicInteger count
        ConcurrentHashMap<Integer, AtomicInteger> elementTracker = new ConcurrentHashMap<>();

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                startPhaser.arriveAndAwaitAdvance();
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Unique values globally
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                        
                        // Track production
                        elementTracker.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                startPhaser.arriveAndAwaitAdvance();
                try {
                    while (true) {
                        Integer val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // Timeout: check if production is done and queue empty
                            if (producedCount.get() == totalItems && stressQueue.isEmpty()) break;
                            continue; // Spurious timeout or slow producer
                        }
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        
                        // Track consumption
                        AtomicInteger count = elementTracker.get(val);
                        if (count == null || count.decrementAndGet() < 0) {
                            fail("Duplicate or unknown element consumed: " + val);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Start & Wait ---
        startPhaser.arriveAndAwaitAdvance(); // Release all threads
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Test timed out");

        stressQueue.shutdown();

        // --- Verification ---
        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed");
        assertEquals(producedSum.get(), consumedSum.get(), "Sum mismatch: Data corruption or loss");

        // Verify exact counts per element (1 produced == 1 consumed)
        for (Map.Entry<Integer, AtomicInteger> entry : elementTracker.entrySet()) {
            assertEquals(0, entry.getValue().get(), 
                "Element count mismatch for " + entry.getKey() + ": " + entry.getValue().get());
        }
        
        // Verify queue is truly empty
        assertTrue(stressQueue.isEmpty());
        assertEquals(0, stressQueue.size());
    }

    @Test
    @Timeout(10)
    @DisplayName("Interruption during put/take")
    void testInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread t = new Thread(() -> {
            try { q.put(2); }
            catch (InterruptedException ignored) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50); // Ensure waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have exited on interrupt");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 100})
    @DisplayName("Various capacities work correctly")
    void testVariousCapacities(int cap) {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(cap);
        for (int i=0; i<cap; i++) assertTrue(q.offer(i));
        assertFalse(q.offer(999));
        for (int i=0; i<cap; i++) assertEquals(i, q.poll());
        assertNull(q.poll());
    }
}
```

---

### 3. Key Design Decisions Explained

#### 1. `ReentrantLock` vs `synchronized`
*   **Condition Variables:** The primary driver. We need two distinct wait-sets: "Waiting for Space" (Producers) and "Waiting for Elements" (Consumers). `synchronized` only offers one wait-set per object (`wait`/`notifyAll`). Using `notifyAll` on a single lock wakes *all* threads (producers and consumers), leading to massive context switching overhead ("Thundering Herd") when only one type can actually make progress.
*   **Interruptible Lock:** `lock.lockInterruptibly()` allows `put`/`take` to throw `InterruptedException` immediately if the thread is interrupted, even while contending for the lock itself. `synchronized` blocks cannot be interrupted while waiting for the monitor.

#### 2. Circular Array vs Linked List
*   **Array:** Better cache locality, fixed memory footprint (no Node object allocation per element), simpler `size()` calculation. Since capacity is fixed (Bounded), array is ideal.
*   **Modulo Arithmetic:** `if (++tail == items.length) tail = 0;` is branch-predicted well by modern CPUs.

#### 3. Shutdown Semantics
*   **Poison Pill vs Flag:** We use a `volatile boolean shutdown` flag + `signalAll()`.
*   **Why not Poison Pills?** Poison pills require producers to know consumer count (or vice versa) and pollute the data stream. A flag is cleaner: it separates *control plane* (lifecycle) from *data plane* (elements).
*   **Drain on Shutdown:** Consumers can still `take`/`poll` remaining elements after shutdown. Only when empty + shutdown do they throw/return null. This supports "graceful drain" patterns.

#### 4. Stress Test Verification Strategy
*   **Unique Values:** Producers generate globally unique integers (`producerId * itemsPerProducer + index`).
*   **Sum Check:** `AtomicLong` sum of produced vs consumed. Fast O(1) check for "major loss/corruption".
*   **Exact Count Map:** `ConcurrentHashMap<Integer, AtomicInteger>`. Tracks exact frequency of every single element.
    *   Producer: `incrementAndGet()`
    *   Consumer: `decrementAndGet()` -> fail if `< 0` (Duplicate) or `null` (Unknown).
    *   Final Assert: All values in map must be `0`. Guarantees **no loss, no duplication, no corruption**.

#### 5. `awaitNanos` Loop
*   Standard pattern: `nanos = condition.awaitNanos(nanos);`.
*   Handles spurious wakeups automatically (loop continues).
*   Handles timeout precision: `awaitNanos` returns remaining time (or `<=0` if timed out). `Object.wait(timeout)` does not return remaining time, making precise total timeout implementation difficult for loops.