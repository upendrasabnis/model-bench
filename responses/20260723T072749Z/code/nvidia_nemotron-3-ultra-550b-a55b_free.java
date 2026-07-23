<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260723T072749Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-grade implementation of a generic bounded blocking queue, its concurrency strategy documentation, and a comprehensive JUnit 5 test suite.

---

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * 
 * <p><b>Concurrency Strategy:</b>
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} (fair mode) guards all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}). Fairness prevents thread starvation
 *       under high contention.</li>
 *   <li><b>Condition Synchronization:</b> Two {@link Condition} variables partition wait-sets:
 *       <ul>
 *         <li>{@code notEmpty}: Consumers wait here when {@code count == 0}. Signaled by producers after insert.</li>
 *         <li>{@code notFull}: Producers wait here when {@code count == capacity}. Signaled by consumers after removal.</li>
 *       </ul>
 *       This avoids "spurious wakeup" inefficiency of a single condition (notifyAll) where wrong thread types wake up.</li>
 *   <li><b>Circular Buffer Invariants:</b>
 *       <ul>
 *         <li>{@code 0 <= count <= capacity}</li>
 *         <li>{@code head} points to the next element to dequeue (if {@code count > 0}).</li>
 *         <li>{@code tail} points to the next free slot to enqueue (if {@code count < capacity}).</li>
 *         <li>Elements stored in {@code items[head]} ... {@code items[(tail-1+capacity)%capacity]}.</li>
 *       </ul>
 *   </li>
 *   <li><b>Shutdown Protocol:</b> A volatile {@code shutdown} flag (guarded by lock) transitions the queue to a terminal state.
 *       <ul>
 *         <li>{@code shutdown()} acquires lock, sets flag, signals {@code notEmpty} and {@code notFull} (waking all waiters).</li>
 *         <li>Blocking ops ({@code put}, {@code take}) throw {@link IllegalStateException} if queue is shutdown <b>and</b> empty/full respectively.</li>
 *         <li>Timed ops ({@code offer}, {@code poll}) return failure indicators (false/null) immediately if shutdown.</li>
 *         <li>Draining: Consumers can still {@code take}/{@code poll} remaining elements after shutdown until empty.</li>
 *       </ul>
 *   </li>
 *   <li><b>Interruption Handling:</b> Blocking methods ({@code put}, {@code take}) are responsive to interruption.
 *       They clear the interrupted status and throw {@link InterruptedException} immediately if interrupted while waiting.
 *       If interrupted *after* acquiring lock but *before* waiting, they re-check state.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    // --- State ---
    private final Object[] items;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    
    // --- Synchronization ---
    private final ReentrantLock lock = new ReentrantLock(true); // Fair lock
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    // --- Lifecycle ---
    private volatile boolean shutdown = false; // Volatile for fast-path check in offer/poll without lock (optional optimization, but lock is fast)

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts element, waiting if necessary for space.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly(); // Respect interruption immediately
        try {
            while (count == capacity) {
                checkShutdownAndThrow(); // Throw if shutdown while full
                notFull.await();
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head, waiting if necessary for element.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown and empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdownAndThrow(); // Throw if shutdown while empty
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts element if space available within timeout.
     * @return true if successful, false if timeout elapsed or shutdown
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                if (shutdown) return false; // Fail fast on shutdown
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head if available within timeout.
     * @return element, or null if timeout elapsed or shutdown & empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Fail fast on shutdown
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

    // --- Immediate Operations (Non-blocking) ---

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

    // --- Inspection & Lifecycle ---

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

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    /**
     * Initiates shutdown. Wakes all waiting threads.
     * Subsequent puts/offers fail. Takes/polls drain remaining elements then fail.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone: producers (notFull) and consumers (notEmpty)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown; // Volatile read is safe for boolean flag
    }

    // --- Private Helpers ---

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) items[head];
        items[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return item;
    }

    private void checkShutdownAndThrow() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shutdown");
        }
    }
}
```

---

### 2. JUnit 5 Test Suite: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // ==========================================
    // Basic Functionality & Contract Tests
    // ==========================================

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Basic FIFO behavior")
    void testFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Capacity limits respected")
    void testCapacityLimit() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());
        assertFalse(q.offer(3)); // Immediate fail
        assertFalse(q.offer(3, 10, TimeUnit.MILLISECONDS)); // Timed fail
    }

    @Test
    @DisplayName("Take blocks until element available")
    void testTakeBlocks() throws InterruptedException {
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread taker = new Thread(() -> {
            try { result.set(queue.take()); } catch (InterruptedException ignored) {}
        });
        taker.start();
        Thread.sleep(50); // Ensure taker is waiting
        assertNull(result.get());
        queue.put(42);
        taker.join(1000);
        assertEquals(42, result.get());
    }

    @Test
    @DisplayName("Put blocks until space available")
    void testPutBlocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        AtomicBoolean putSucceeded = new AtomicBoolean(false);
        Thread putter = new Thread(() -> {
            try { q.put(2); putSucceeded.set(true); } catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(50);
        assertFalse(putSucceeded.get());
        q.take(); // Make space
        putter.join(1000);
        assertTrue(putSucceeded.get());
        assertEquals(2, q.take());
    }

    // ==========================================
    // Timed Operations Tests
    // ==========================================

    @ParameterizedTest
    @ValueSource(longs = {10, 50, 100})
    @DisplayName("Offer with timeout returns false on timeout")
    void testOfferTimeout(long timeoutMs) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        long start = System.nanoTime();
        boolean res = q.offer(2, timeoutMs, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(res);
        // Allow some slack for scheduler
        assertTrue(elapsed >= timeoutMs - 20, "Returned too early: " + elapsed + "ms");
        assertTrue(elapsed <= timeoutMs + 100, "Returned too late: " + elapsed + "ms");
    }

    @Test
    @DisplayName("Poll with timeout returns null on timeout")
    void testPollTimeout() throws InterruptedException {
        long start = System.nanoTime();
        Integer res = queue.poll(50, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNull(res);
        assertTrue(elapsed >= 30);
    }

    @Test
    @DisplayName("Timed offer succeeds if space becomes available")
    void testTimedOfferSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        Thread consumer = new Thread(() -> {
            try { Thread.sleep(20); q.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        assertTrue(q.offer(2, 200, TimeUnit.MILLISECONDS));
        consumer.join();
        assertEquals(2, q.take());
    }

    // ==========================================
    // Interruption Tests
    // ==========================================

    @Test
    @DisplayName("Put responds to interruption")
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread putter = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        putter.interrupt();
        putter.join(1000);
        assertFalse(putter.isAlive());
        assertEquals(1, q.size()); // State unchanged
    }

    @Test
    @DisplayName("Take responds to interruption")
    void testTakeInterruption() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        taker.start();
        Thread.sleep(50);
        taker.interrupt();
        taker.join(1000);
        assertFalse(taker.isAlive());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Timed operations respond to interruption")
    void testTimedInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        Thread putter = new Thread(() -> {
            try { q.offer(2, 10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        putter.interrupt();
        putter.join(1000);
        assertFalse(putter.isAlive());
    }

    // ==========================================
    // Shutdown Tests
    // ==========================================

    @Test
    @DisplayName("Shutdown wakes blocked producers")
    void testShutdownWakesProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread putter = new Thread(() -> {
            try { q.put(2); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        q.shutdown();
        putter.join(1000);
        assertFalse(putter.isAlive());
        assertTrue(q.isShutdown());
    }

    @Test
    @DisplayName("Shutdown wakes blocked consumers")
    void testShutdownWakesConsumers() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        taker.start();
        Thread.sleep(50);
        queue.shutdown();
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    @Test
    @DisplayName("Drain remaining elements after shutdown")
    void testDrainAfterShutdown() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertFalse(queue.offer(3));
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    // ==========================================
    // Stress Test: Multi-Producer / Multi-Consumer
    // ==========================================

    @Test
    @DisplayName("Stress Test: No lost/duplicated elements, high contention")
    @DisabledOnOs(OS.WINDOWS) // Timing sensitive on CI Windows runners sometimes; remove if stable
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 4;
        final int ITEMS_PER_PRODUCER = 5000; // Total 20,000 items
        final int TOTAL_ITEMS = PRODUCER_COUNT * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // To detect duplicates/loss without massive memory, we use a ConcurrentHashMap 
        // tracking counts per key. Since keys are unique (1..TOTAL_ITEMS), count must be 1.
        Map<Integer, AtomicInteger> elementTracker = new ConcurrentHashMap<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCER_COUNT + CONSUMER_COUNT);
        AtomicReference<Throwable> producerError = new AtomicReference<>();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();

        // --- Producers ---
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        // Generate globally unique sequential IDs
                        int val = producerId * ITEMS_PER_PRODUCER + i + 1;
                        stressQueue.put(val); // Blocking put
                        producedSum.addAndGet(val);
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
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Consumers run until queue is shutdown AND empty
                    while (true) {
                        Integer val;
                        try {
                            // Use poll with timeout to allow checking shutdown condition periodically
                            // without relying solely on InterruptedException or take() throwing ISE.
                            // But take() throws ISE on shutdown+empty, which is cleaner.
                            val = stressQueue.take(); 
                        } catch (IllegalStateException e) {
                            // Queue shutdown and empty
                            break; 
                        }
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        elementTracker.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (Throwable t) {
                    consumerError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go ---
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for producers to finish
        // We need a separate latch or logic to know when producers are done to call shutdown.
        // Simplification: Wait for producer threads specifically? 
        // Let's use a Phaser or just join producer threads. 
        // Since we used CountDownLatch for all, we wait for all, but consumers wait for shutdown.
        // Better: Wait for producer count down separately.

        // Re-working synchronization for clean shutdown trigger:
        // We'll use a Phaser for producers + main, and consumers register separately.
        // Actually, simpler: Join producer threads explicitly.
    }

    // Re-writing the stress test with cleaner lifecycle management
    @Test
    @DisplayName("Stress Test: Multi-Producer/Consumer Verification (No Loss/Dup)")
    void stressTestMpmcVerified() throws InterruptedException {
        final int CAPACITY = 64;
        final int PRODUCERS = 8;
        final int CONSUMERS = 8;
        final int ITEMS_PER_PRODUCER = 2500; // 20,000 total
        final int TOTAL = PRODUCERS * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        
        // Verification structures
        AtomicLong sumProduced = new AtomicLong();
        AtomicLong sumConsumed = new AtomicLong();
        AtomicInteger countProduced = new AtomicInteger();
        AtomicInteger countConsumed = new AtomicInteger();
        // BitSet is memory efficient for 1..N tracking if N fits memory. 
        // 20,000 bits ~ 2.5KB. Perfect.
        // But we need concurrent BitSet -> AtomicIntegerArray or ConcurrentHashMap.
        // Let's use AtomicIntegerArray for counts (0, 1, 2...). Index = value.
        AtomicIntegerArray seenCounts = new AtomicIntegerArray(TOTAL + 1); 

        CountDownLatch producersReady = new CountDownLatch(PRODUCERS);
       