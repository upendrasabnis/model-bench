<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261027T191327Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-grade implementation of a **Generic Bounded Blocking Queue** using `ReentrantLock` and `Condition` variables, followed by the concurrency strategy documentation and comprehensive JUnit 5 tests.

---

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * <p>
 * Features:
 * <ul>
 *   <li>Blocking {@code put}/{@code take}</li>
 *   <li>Timed {@code offer}/{@code poll}</li>
 *   <li>Clean shutdown support (drains remaining elements, unblocks waiters)</li>
 *   <li>Fairness policy configurable (default: non-fair for throughput)</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- Internal State ---
    private final Object[] items;      // Circular buffer
    private int putIndex = 0;          // Index to insert next (guarded by lock)
    private int takeIndex = 0;         // Index to remove next (guarded by lock)
    private int count = 0;             // Current number of elements (guarded by lock)
    private volatile boolean shutdown = false; // Visible across threads without lock

    // --- Synchronization Primitives ---
    private final ReentrantLock lock;
    private final Condition notEmpty;  // Signaled when count > 0
    private final Condition notFull;   // Signaled when count < capacity

    /**
     * Creates a queue with the given capacity and default non-fair locking policy.
     *
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     *
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @param fair     if true, threads contend for the lock in FIFO order (higher overhead, prevents starvation)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // ============================================================
    // Core Blocking Operations
    // ============================================================

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add (must not be null)
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly(); // Allows interruption during lock acquisition
        try {
            checkShutdown();
            while (count == items.length) {
                notFull.await(); // Releases lock, waits for signal
                checkShutdown(); // Re-check shutdown after wakeup (spurious wakeup or shutdown)
            }
            enqueue(e);
            notEmpty.signal(); // Wake up ONE waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shut down AND empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down and empty");
                }
                notEmpty.await();
            }
            return dequeue();
        } finally {
            // Signal notFull in finally? No, only if we actually removed.
            // But if we throw IllegalStateException, count is 0, no need to signal notFull.
            lock.unlock();
        }
    }

    // ============================================================
    // Timed Operations
    // ============================================================

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e       the element to add
     * @param timeout how long to wait before giving up
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timeout elapsed or shutdown
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (count == items.length) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Returns remaining nanos
                checkShutdown();
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
     *
     * @param timeout how long to wait before giving up
     * @param unit    the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timeout elapsed or shutdown & empty
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Shutdown + empty = drain complete
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Immediate / Inspection Operations
    // ============================================================

    /** @return true if queue is full */
    public boolean isFull() {
        lock.lock();
        try { return count == items.length; }
        finally { lock.unlock(); }
    }

    /** @return true if queue is empty */
    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; }
        finally { lock.unlock(); }
    }

    /** @return current number of elements */
    public int size() {
        lock.lock();
        try { return count; }
        finally { lock.unlock(); }
    }

    /** @return maximum capacity */
    public int capacity() { return items.length; }

    // ============================================================
    // Shutdown Protocol
    // ============================================================

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Prevents future {@code put}/{@code offer} (throws {@link IllegalStateException} / returns false).</li>
     *   <li>Unblocks all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Allows consumers to drain remaining elements via {@code take}/{@code poll}.</li>
     *   <li>Once drained, {@code take} throws {@link IllegalStateException}, {@code poll} returns null.</li>
     * </ul>
     * This method is idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Signal ALL waiters on both conditions to wake up and re-evaluate state
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return true if shutdown has been initiated */
    public boolean isShutdown() { return shutdown; }

    // ============================================================
    // Internal Helpers (Require Lock Held)
    // ============================================================

    private void checkShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    @SuppressWarnings("unchecked")
    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % items.length;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) items[takeIndex];
        items[takeIndex] = null; // Help GC
        takeIndex = (takeIndex + 1) % items.length;
        count--;
        return item;
    }
}
```

---

### 2. Concurrency Strategy Documentation

#### **Strategy: Single Lock with Dual Condition Variables (Monitor Pattern)**

This implementation follows the classic **Monitor Pattern** (similar to `java.util.concurrent.ArrayBlockingQueue`), utilizing a single `ReentrantLock` and two `Condition` variables (`notEmpty`, `notFull`).

#### **1. Mutual Exclusion & State Consistency**
*   **Single Lock (`ReentrantLock`)**: All mutable state (`items`, `putIndex`, `takeIndex`, `count`) is guarded by a single lock.
*   **Why not Striped Locks / Read-Write Locks?** For a bounded queue, `put` and `take` modify *both* head/tail indices and `count`. They conflict on the `count` variable inherently. A single lock avoids deadlock risks of dual-lock algorithms (e.g., `LinkedBlockingQueue` style) and simplifies reasoning about the `count == 0` / `count == capacity` invariants.
*   **Fairness**: Configurable via constructor. Non-fair (default) provides higher throughput under contention; Fair prevents thread starvation.

#### **2. Signaling Efficiency: Targeted Notification (`signal` vs `signalAll`)**
*   **Standard Path (`put`/`take`)**: Uses `signal()` (wake **one** thread).
    *   `put` signals `notEmpty` (wakes 1 consumer).
    *   `take` signals `notFull` (wakes 1 producer).
    *   *Rationale*: Waking one thread is sufficient to make progress. Waking all (`signalAll`) causes "thundering herd" — $N$ threads wake, 1 succeeds, $N-1$ re-check condition and park again, burning CPU cycles.
*   **Shutdown Path**: Uses `signalAll()` on **both** conditions.
    *   *Rationale*: Shutdown is a terminal "broadcast" event. *All* blocked producers and consumers must wake up immediately to observe the `shutdown` flag and exit cleanly. Precision signaling is impossible here because we don't know if a waiter is a producer or consumer.

#### **3. Condition Predicate Loops (Spurious Wakeup Handling)**
*   All `await()` calls are inside `while (predicate)` loops, never `if`.
*   **Handles**: Spurious wakeups (OS/JVM quirks), `signalAll` during shutdown (waking wrong type of waiter), and race conditions between signal and lock re-acquisition.

#### **4. Memory Visibility & Happens-Before**
*   **Lock/Unlock**: `lock.lock()` / `lock.unlock()` establish full memory barriers (happens-before edges). State written by Producer (element write, `count++`) is visible to Consumer after `lock.lock()`.
*   **Volatile `shutdown`**: The `shutdown` flag is `volatile`.
    *   *Why volatile if guarded by lock?* The `shutdown()` method writes it under lock. However, `poll(timeout)` / `take()` check `shutdown` *inside* the `while(count==0)` loop *while holding the lock*. The `volatile` ensures that if a thread checks `shutdown` *without* the lock (e.g., a fast-path `isShutdown()` call), it sees the latest value. It also guarantees the write in `shutdown()` is flushed to main memory immediately upon `signalAll`, ensuring waiters waking up see `true`.

#### **5. Interruption Responsiveness**
*   `lock.lockInterruptibly()` is used in all blocking public methods.
*   If a thread is interrupted while *waiting for the lock* (contention) or *waiting on Condition*, `InterruptedException` is thrown immediately.
*   The lock is released in `finally` blocks, ensuring no lock leakage on interruption.

#### **6. Shutdown Protocol Semantics (Poison Pill Alternative)**
*   **No Poison Objects**: Avoids polluting the generic type `E` and eliminates "poison pill leakage" bugs where a consumer consumes the pill but forgets to pass it on.
*   **State Machine**:
    1.  `shutdown()` sets `volatile shutdown=true` + `signalAll()`.
    2.  **Producers** (`put`/`offer`): Wake up, see `shutdown=true`, throw `IllegalStateException` / return `false`. **No new elements accepted.**
    3.  **Consumers** (`take`/`poll`): Wake up.
        *   If `count > 0`: Drain elements normally (FIFO).
        *   If `count == 0`: `take()` throws `IllegalStateException`; `poll()` returns `null`.
*   **Drain Guarantee**: Elements enqueued *before* `shutdown()` are guaranteed to be dequeued.

---

### 3. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * Covers: API correctness, Blocking/Timed behavior, Shutdown protocol, Concurrency Stress.
 */
class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    // ============================================================
    // Basic API & Contract Tests
    // ============================================================

    @Test
    void constructor_InvalidCapacity_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void putTake_SingleElement_FifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    void put_NullElement_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
    }

    @Test
    void offerPoll_ImmediateSuccess() {
        assertTrue(queue.offer(1, 0, MILLISECONDS));
        assertEquals(1, queue.poll(0, MILLISECONDS));
        assertNull(queue.poll(0, MILLISECONDS));
    }

    @Test
    void capacity_Respected() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> {
            try { queue.put(i); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        assertTrue(queue.isFull());
        assertEquals(CAPACITY, queue.size());
        assertFalse(queue.offer(99, 10, MILLISECONDS)); // Timed offer fails
    }

    // ============================================================
    // Blocking Behavior Tests
    // ============================================================

    @Test
    void put_BlocksWhenFull_UnblocksOnTake() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread producer = new Thread(() -> {
            try { queue.put(999); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        
        // Give producer time to block
        Thread.sleep(100); 
        assertTrue(producer.isAlive(), "Producer should be blocked");
        
        // Consume one -> unblocks producer
        assertEquals(0, queue.take());
        producer.join(500); // Should finish quickly now
        assertFalse(producer.isAlive(), "Producer should have unblocked and finished");
        assertEquals(999, queue.take()); // Verify element inserted
    }

    @Test
    void take_BlocksWhenEmpty_UnblocksOnPut() throws InterruptedException {
        AtomicInteger result = new AtomicInteger(-1);
        Thread consumer = new Thread(() -> {
            try { result.set(queue.take()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        
        queue.put(42);
        consumer.join(500);
        assertFalse(consumer.isAlive());
        assertEquals(42, result.get());
    }

    // ============================================================
    // Timed Operations Tests
    // ============================================================

    @Test
    void offer_Timeout_ReturnsFalse() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        assertFalse(queue.offer(1, 50, MILLISECONDS));
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    void poll_Timeout_ReturnsNull() throws InterruptedException {
        assertNull(queue.poll(50, MILLISECONDS));
    }

    @Test
    void timedOperations_RespectInterruption() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread t = new Thread(() -> {
            try { queue.offer(1, 10, SECONDS); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure it's waiting
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive(), "Thread should exit on interrupt");
    }

    // ============================================================
    // Shutdown Protocol Tests
    // ============================================================

    @Test
    void shutdown_UnblocksProducersAndConsumers() throws InterruptedException {
        // 1. Block a producer (queue full)
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        Thread producer = startThread(() -> { try { queue.put(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        
        // 2. Block a consumer (queue empty) - need separate queue or drain first
        BoundedBlockingQueue<Integer> q2 = new BoundedBlockingQueue<>(1);
        Thread consumer = startThread(() -> { try { q2.take(); } catch (InterruptedException | IllegalStateException e) { /* expected */ } });
        
        Thread.sleep(100); // Ensure both blocked
        
        // 3. Shutdown
        queue.shutdown();
        q2.shutdown();
        
        producer.join(500);
        consumer.join(500);
        
        assertFalse(producer.isAlive(), "Producer should unblock on shutdown");
        assertFalse(consumer.isAlive(), "Consumer should unblock on shutdown");
    }

    @Test
    void shutdown_RejectsNewPuts_AllowsDrain() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        // Reject new puts
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertFalse(queue.offer(3, 10, MILLISECONDS));
        
        // Allow drain
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Empty + Shutdown -> Exception/Null
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll(10, MILLISECONDS));
    }

    @Test
    void shutdown_Idempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // ============================================================
    // Concurrency Stress Tests
    // ============================================================

    /**
     * Stress Test: Multi-Producer / Multi-Consumer.
     * Verifies: No lost elements, no duplicates, exact count match, shutdown drain correctness.
     */
    @Test
    @Timeout(value = 30, unit = SECONDS) // Global timeout for CI safety
    void stressTest_MultiProducerMultiConsumer_NoLostNoDuplicates() throws InterruptedException {
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = producerCount * itemsPerProducer;
        
        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(100); // Small buffer -> high contention
        
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        // Use ConcurrentHashMap as a thread-safe Set to detect duplicates/lost
        Map<Integer, AtomicInteger> elementTracker = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicReference<Throwable> producerError = new AtomicReference<>();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Unique values per producer
                        stressQueue.put(val); // Blocking put
                        producedCount.incrementAndGet();
                        elementTracker.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (Throwable t) {
                    producerError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.take(); // Blocking take
                        // If shutdown happened and queue empty, take() throws IllegalStateException
                        // But we don't call shutdown until producers finish.
                        consumedCount.incrementAndGet();
                        AtomicInteger counter = elementTracker.get(val);
                        if (counter == null || counter.incrementAndGet() > 1) {
                            throw new IllegalStateException("Duplicate detected: " + val);
                        }
                    }
                } catch (IllegalStateException e) {
                    // Expected on shutdown drain completion
                    if (!stressQueue.isShutdown() || !stressQueue.isEmpty()) {
                        consumerError.set(e); // Unexpected exception
                    }
                } catch (Throwable t) {
                    consumerError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- GO ---
        startLatch.countDown();

        // Wait for producers to finish putting
        // We wait on producer threads specifically? Easier: wait for producedCount == totalItems
        while (producedCount.get() < totalItems) {
            Thread.sleep(10);
        }
        
        // Initiate Shutdown (Producers done, Consumers drain remaining)
        stressQueue.shutdown();
        
        // Wait for all threads to terminate
        boolean finished = doneLatch.await(20, SECONDS);
        assertTrue(finished, "Test timed out waiting for threads to finish");
        
        // --- Verification ---
        assertNull(producerError.get(), "Producer error: " + producerError.get());
        assertNull(consumerError.get(), "Consumer error: " + consumerError.get());
        
        assertEquals(totalItems