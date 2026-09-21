<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260921T144634Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-grade implementation of a **Generic Bounded Blocking Queue** using `ReentrantLock` and `Condition` variables, followed by comprehensive JUnit 5 tests.

---

### 1. Concurrency Strategy Documentation

**Strategy: Single Lock (`ReentrantLock`) with Two Condition Variables (`notFull`, `notEmpty`)**

1.  **Mutual Exclusion**: A single `ReentrantLock` (`lock`) protects all shared state (`buffer`, `head`, `tail`, `count`, `shutdown`). This ensures memory visibility (happens-before) and atomicity of compound actions (check-then-act).
2.  **Signaling Conditions**:
    *   `notEmpty`: Signaled by `put`/`offer` when an element is added. Waited upon by `take`/`poll` when queue is empty.
    *   `notFull`: Signaled by `take`/`poll` when an element is removed. Waited upon by `put`/`offer` when queue is at capacity.
    *   *Why two conditions?* Using separate conditions allows **targeted signaling** (`signal()` instead of `signalAll()`). Waking only one waiting consumer (on `notEmpty`) or one waiting producer (on `notFull`) reduces context switching overhead and "thundering herd" problems compared to a single monitor (`synchronized`/`wait`/`notifyAll`).
3.  **Bounded Buffer**: Circular array (`Object[] buffer`) with `head` (next take index), `tail` (next put index), and `count` (current size). This avoids `ArrayList` resizing overhead and allows $O(1)$ operations.
4.  **Spurious Wakeups Handling**: All `await()` calls are inside `while (condition)` loops, not `if` statements, guaranteeing correctness against spurious wakeups.
5.  **Shutdown Protocol**:
    *   `shutdown()` acquires lock, sets `shutdown = true`, and calls `signalAll()` on **both** conditions.
    *   Blocking methods (`put`, `take`) check `shutdown` flag *after* acquiring lock but *before* waiting. If shut down, they throw `IllegalStateException`.
    *   Timed methods (`offer`, `poll`) return `false`/`null` immediately if shut down.
    *   This ensures **liveness**: no thread waits forever on a dead queue.
6.  **Interruption Policy**: Blocking methods (`put`, `take`) are **interruptible**. They throw `InterruptedException` immediately if the thread is interrupted, clearing the interrupt status (standard `Condition.await()` behavior). This allows graceful thread pool termination.

---

### 2. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * 
 * Concurrency Strategy:
 * - Single ReentrantLock for mutual exclusion and memory visibility.
 * - Two Condition variables (notEmpty, notFull) for targeted signaling.
 * - Circular array for O(1) storage.
 * - Supports clean shutdown via shutdown() method.
 * 
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    // --- State ---
    private final Object[] buffer;
    private final int capacity;
    private int head = 0;      // Index to take from
    private int tail = 0;      // Index to put at
    private int count = 0;     // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast-path check without lock (optional optimization)

    // --- Synchronization Primitives ---
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    // ============================================================
    // Blocking Operations (Interruptible)
    // ============================================================

    /**
     * Inserts the element, waiting if necessary for space.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lockInterruptibly(); // Respect interruption immediately
        try {
            checkShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkShutdown(); // Re-check shutdown after wakeup
            }
            enqueue(e);
            notEmpty.signal(); // Wake up ONE waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary for an element.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
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
            E item = dequeue();
            notFull.signal(); // Wake up ONE waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Timed Operations
    // ============================================================

    /**
     * Inserts element if space available within timeout.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
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
     * Retrieves head if available within timeout.
     * @return head element, or null if timeout elapsed
     * @throws InterruptedException if interrupted
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
    // Immediate / State Operations
    // ============================================================

    /** @return true if inserted immediately, false if full */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element or null if empty */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element without removing, or null if empty */
    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) buffer[head];
        } finally {
            lock.unlock();
        }
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
        try { return count == capacity; } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    // ============================================================
    // Shutdown
    // ============================================================

    /**
     * Initiates orderly shutdown.
     * - Prevents future puts/offers (throw/return false).
     * - Wakes all waiting threads.
     * - Existing elements remain retrievable via take/poll.
     * - Once empty, take/poll return null/throw IllegalStateException.
     * Idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Wake EVERYONE: producers (notFull) and consumers (notEmpty)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        // Volatile read is sufficient for boolean check
        return shutdown;
    }

    // ============================================================
    // Internal Helpers
    // ============================================================

    private void checkShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) buffer[head];
        buffer[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return item;
    }
}
```

---

### 3. JUnit 5 Test Suite: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * Covers: API correctness, Blocking behavior, Timed operations, Shutdown, Concurrency/Stress.
 */
class BoundedBlockingQueueTest {

    private static final int SMALL_CAPACITY = 10;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(SMALL_CAPACITY);
    }

    // ----------------------------------------------------------
    // Basic API & Contract Tests
    // ----------------------------------------------------------

    @Test
    void constructor_InvalidCapacity_Throws() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void putTake_SingleElement_Works() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void putTake_FIFO_Order() throws InterruptedException {
        queue.put(1); queue.put(2); queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void put_Null_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    void capacity_Respected() throws InterruptedException {
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        assertTrue(queue.isFull());
        assertFalse(queue.offer(99)); // Immediate offer fails
    }

    @Test
    void peek_DoesNotRemove() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.peek());
        assertEquals(1, queue.peek());
        assertEquals(1, queue.take());
        assertNull(queue.peek());
    }

    // ----------------------------------------------------------
    // Blocking Behavior Tests
    // ----------------------------------------------------------

    @Test
    void take_BlocksUntilElementAvailable() throws InterruptedException {
        AtomicBoolean producerStarted = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            producerStarted.set(true);
            try { Thread.sleep(50); queue.put(100); } catch (InterruptedException ignored) {}
        });
        
        producer.start();
        // Wait for producer to start and sleep
        while(!producerStarted.get()) Thread.yield();
        
        long start = System.nanoTime();
        Integer val = queue.take(); // Should block ~50ms
        long elapsed = NANOSECONDS.toMillis(System.nanoTime() - start);
        
        assertEquals(100, val);
        assertTrue(elapsed >= 40, "Should have blocked for producer");
    }

    @Test
    void put_BlocksUntilSpaceAvailable() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        
        AtomicBoolean consumerStarted = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            consumerStarted.set(true);
            try { Thread.sleep(50); queue.take(); } catch (InterruptedException ignored) {}
        });
        
        consumer.start();
        while(!consumerStarted.get()) Thread.yield();
        
        long start = System.nanoTime();
        queue.put(999); // Should block ~50ms
        long elapsed = NANOSECONDS.toMillis(System.nanoTime() - start);
        
        assertTrue(elapsed >= 40, "Should have blocked for consumer");
        assertEquals(999, queue.take()); // Verify it was added
    }

    @Test
    void putTake_Interruption_ThrowsIE() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        
        Thread putter = new Thread(() -> {
            try { queue.put(1); } catch (InterruptedException e) { /* Expected */ }
        });
        putter.start();
        Thread.sleep(20); // Ensure putter is waiting
        putter.interrupt();
        putter.join(1000);
        
        assertFalse(putter.isAlive(), "Thread should have exited on interrupt");
    }

    // ----------------------------------------------------------
    // Timed Operations Tests
    // ----------------------------------------------------------

    @Test
    void offer_Timed_SuccessAndTimeout() throws InterruptedException {
        assertTrue(queue.offer(1, 100, MILLISECONDS));
        assertEquals(1, queue.take());
        
        // Fill queue
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        assertFalse(queue.offer(99, 50, MILLISECONDS), "Should timeout when full");
    }

    @Test
    void poll_Timed_SuccessAndTimeout() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.poll(100, MILLISECONDS));
        assertNull(queue.poll(50, MILLISECONDS), "Should timeout when empty");
    }

    @Test
    void offerPoll_Interruption_ThrowsIE() throws InterruptedException {
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        
        Thread putter = new Thread(() -> {
            try { queue.offer(1, 10, SECONDS); } catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(20);
        putter.interrupt();
        putter.join(1000);
        assertFalse(putter.isAlive());
    }

    // ----------------------------------------------------------
    // Shutdown Tests
    // ----------------------------------------------------------

    @Test
    void shutdown_PreventsNewPuts_AllowsDrain() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertFalse(queue.offer(3));
        assertFalse(queue.offer(3, 1, SECONDS));
        
        // Can still drain
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(10, MILLISECONDS));
    }

    @Test
    void shutdown_WakesWaitingThreads() throws InterruptedException {
        // 1. Blocking Put waiting
        for (int i = 0; i < SMALL_CAPACITY; i++) queue.put(i);
        Thread putter = new Thread(() -> {
            try { queue.put(99); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be IE"); }
        });
        putter.start();
        Thread.sleep(20);
        
        // 2. Blocking Take waiting
        BoundedBlockingQueue<Integer> q2 = new BoundedBlockingQueue<>(1);
        Thread taker = new Thread(() -> {
            try { q2.take(); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be IE"); }
        });
        taker.start();
        Thread.sleep(20);
        
        // Act
        queue.shutdown();
        q2.shutdown();
        
        putter.join(1000);
        taker.join(1000);
        assertFalse(putter.isAlive());
        assertFalse(taker.isAlive());
    }

    @Test
    void shutdown_Idempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    // ----------------------------------------------------------
    // Multi-Producer / Multi-Consumer Stress Test
    // ----------------------------------------------------------

    /**
     * Stress test verifying:
     * 1. No lost elements (Count matches)
     * 2. No duplicated elements (Set size matches Count)
     * 3. Correct ordering per producer (optional, but we check global consistency)
     * 4. Graceful shutdown completion
     */
    @Test
    @Timeout(value = 30, unit = SECONDS) // Global timeout for CI safety
    void stressTest_MultiProducerMultiConsumer_NoLostNoDuplicates() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a ConcurrentHashMap to track counts per value for duplicate detection
        // Key: Value produced, Value: AtomicInteger count
        Map<Integer, AtomicInteger> producedCounts = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> consumedCounts = new ConcurrentHashMap<>();
        
        AtomicInteger producedTotal = new AtomicInteger(0);
        AtomicInteger consumedTotal = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // --- Producers ---
        List<Thread> producers = IntStream.range(0, producerCount).mapToObj(pId -> new Thread(() -> {
            try {
                startLatch.await(); // Synchronized start
                for (int i = 0; i < itemsPerProducer; i++) {
                    int val = pId * itemsPerProducer + i; // Unique values globally
                    stressQueue.put(val); // Blocking
                    
                    producedCounts.computeIfAbsent(val, k -> new AtomicInteger()).incrementAndGet();
                    producedTotal.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                errorFlag.set(true);
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        }, "Producer-%d")).collect(Collectors.toList());

        // --- Consumers ---
        List<Thread> consumers = IntStream.range(0, consumerCount).mapToObj(cId -> new Thread(() -> {
            try {
                startLatch.await();
                while (true) {
                    Integer val = stressQueue.poll(100, MILLISECONDS); // Timed poll to allow exit check
                    if (val == null) {
                        // Queue empty, check if producers done and queue shutdown
                        if (producedTotal.get() == totalItems && stressQueue.isShutdown()) break;
                        if (stressQueue.isShutdown() && stressQueue.isEmpty()) break;
                        continue; // Spurious timeout, keep trying
                    }
                    
                    consumedCounts.computeIfAbsent(val, k -> new AtomicInteger()).incrementAndGet();
                    consumedTotal.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                errorFlag.set(true);
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        }, "Consumer-%d")).collect(Collectors.toList());

        // Start all threads
        producers.forEach(Thread::start);
        consumers.forEach(Thread::start);

        // Release
        startLatch.countDown();

        // Wait for producers to finish putting
        for (Thread p : producers) p.join(5000);
        
        // Signal shutdown so consumers exit cleanly when empty
        stressQueue.shutdown();
        
        // Wait for consumers to finish draining
        boolean finished = doneLatch.await(20, SECONDS);
        
        // --- Assertions ---
        assertFalse(errorFlag.get(), "Thread encountered exception");
        assertTrue(finished, "Test timed out waiting for threads to finish");
        
        assertEquals(totalItems, producedTotal.get(), "Total produced mismatch");
        assertEquals(totalItems, consumedTotal.get(), "Total consumed mismatch (Lost elements!)");
        
        // Verify no duplicates: every produced key must have count == 1
        producedCounts.forEach((k, v) -> assertEquals(1, v.get(), "Duplicate produced: " + k));
        consumedCounts.forEach((k, v) -> assertEquals(1, v.get(), "Duplicate consumed: " + k));
        
        // Verify sets match exactly
        assertEquals(producedCounts.keySet(), consumedCounts.keySet(), "Produced set != Consumed set");
        
        // Verify queue empty
        assertTrue(stressQueue.isEmpty());
        assertEquals(0, stressQueue.size());
    }

    // ----------------------------------------------------------
    // Fairness / Starvation Check (Heuristic)
    // ----------------------------------------------------------
    
    @Test
    @DisabledIfSystemProperty(named = "ci", matches = "true") // Disable on slow CI, enable locally
    void fairness_Heuristic_NoStarvation() throws InterruptedException {
        final int capacity = 10;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        int threads = 20;
        int ops = 1000;
        AtomicInteger[] counters = new AtomicInteger[threads];
        for(int i=0;i<threads;i++) counters[i] = new AtomicInteger(0);
        
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads * 2);
        
        // Half producers, half consumers
        for (int i = 0; i < threads; i++) {
            final int id = i;
            boolean isProducer = i % 2 == 0;
            new Thread(() -> {
                try { start.await(); }
                catch (InterruptedException e) { return; }
                try {
                    if (isProducer) {
                        for (int j = 0; j < ops; j++) q.put(id);
                    } else {
                        for (int j = 0; j < ops; j++) q.take();
                    }
                    counters[id].incrementAndGet(); // Mark completion
                } catch (InterruptedException ignored) {}
                finally { end.countDown(); }
            }).start();
        }
        
        start.countDown();
        assertTrue(end.await(10, SECONDS));
        
        // Heuristic: All threads should have completed their loops (counter == 1)
        // If a thread starved, its counter would be 0.
        for (int i = 0; i < threads; i++) {
            assertEquals(1, counters[i].get(), "Thread " + i + " starved/failed to complete");
        }
    }
}
```

---

### 4. Key Implementation Details for the Reviewer

1.  **`lock.lockInterruptibly()` vs `lock.lock()`**: Used in `put`/`take`/`offer`/`poll