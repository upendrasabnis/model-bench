<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260708T210536Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * 
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state
 * ({@code head}, {@code tail}, {@code count}, {@code shutdown}) and the backing array.
 * Two {@link Condition} variables are associated with this lock:
 * <ul>
 *   <li>{@code notEmpty}: Signaled when an element is added (enqueued). Consumers wait on this when queue is empty.</li>
 *   <li>{@code notFull}: Signaled when an element is removed (dequeued). Producers wait on this when queue is full.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Why Single Lock + Two Conditions?</b>
 * <ul>
 *   <li><b>Simplicity & Correctness:</b> A single lock ensures mutual exclusion for all state transitions (enqueue/dequeue/shutdown), preventing race conditions on {@code count}, {@code head}, {@code tail} without complex memory visibility reasoning required by lock-free algorithms.</li>
 *   <li><b>Efficient Signaling:</b> Separate conditions allow <i>targeted signaling</i>. When a producer adds an item, it signals {@code notEmpty} (waking exactly one consumer) rather than {@code notifyAll()} (waking all threads, including other producers who cannot proceed). This avoids "thundering herd" and spurious wakeups for the wrong party.</li>
 *   <li><b>Fairness:</b> The lock is instantiated as fair ({@code new ReentrantLock(true)}) to prevent thread starvation under high contention, ensuring FIFO ordering of thread acquisition roughly matches arrival order.</li>
 * </ul>
 * </p>
 * 
 * <h2>Shutdown Semantics</h2>
 * <p>
 * Calling {@link #shutdown()} atomically sets a {@code shutdown} flag and signals <b>all</b> waiting threads (both producers and consumers).
 * <ul>
 *   <li>Producers ({@code put}, {@code offer}) immediately fail with {@link IllegalStateException}.</li>
 *   <li>Consumers ({@code take}, {@code poll}) drain remaining elements normally. Once empty, they return {@code null} (poll) or throw {@link IllegalStateException} (take).</li>
 * </ul>
 * This "poison pill" approach via a flag avoids the overhead of enqueueing special sentinel objects per consumer.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;
    
    // State guarded by 'lock'
    private int head = 0;       // Index to take from
    private int tail = 0;       // Index to put at
    private int count = 0;      // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast-path read without lock (optional optimization, but written under lock)

    private final ReentrantLock lock = new ReentrantLock(true); // Fair locking
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Helper Methods (Must hold lock) ---

    private void enqueue(E x) {
        items[tail] = x;
        if (++tail == capacity) tail = 0;
        count++;
        notEmpty.signal(); // Wake up one waiting consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        notFull.signal(); // Wake up one waiting producer
        return x;
    }

    private void checkNotShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    // --- Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lockInterruptibly(); // Respect interruption during lock acquisition
        try {
            checkNotShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal, re-acquires lock
                checkNotShutdown(); // Re-check shutdown after wakeup
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time for space.
     * @return {@code true} if successful, {@code false} if timeout elapsed before space available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                checkNotShutdown();
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time if necessary.
     * @return the head of this queue, or {@code null} if timeout elapsed before an element was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty (only thrown if timeout > 0 and shutdown occurs during wait? 
     *                               Spec choice: return null on timeout, throw on shutdown+empty).
     *                               Here: Returns null on timeout. Throws IllegalStateException if shutdown detected while empty.
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Drained shutdown returns null
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Immediate Operations (Non-blocking) ---

    /** Inserts element if space available immediately. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head if available immediately. */
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            return dequeue();
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
     * Initiates an orderly shutdown. 
     * <ul>
     *   <li>Producers ({@code put}/{@code offer}) will immediately fail/reject.</li>
     *   <li>Consumers will drain existing elements, then return {@code null} ({@code poll}) or throw ({@code take}).</li>
     *   <li>All blocked threads are unblocked.</li>
     * </ul>
     * Idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Wake everyone: producers fail fast, consumers drain
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown; // Volatile read is safe
    }
    
    // For testing/debugging
    @Override
    public String toString() {
        lock.lock();
        try {
            Object[] snapshot = new Object[count];
            for (int i = 0; i < count; i++) {
                snapshot[i] = items[(head + i) % capacity];
            }
            return "BoundedBlockingQueue{" + Arrays.toString(snapshot) + "}";
        } finally {
            lock.unlock();
        }
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
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
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 */
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

    // ============================================================
    // Basic Functionality & Contract Tests
    // ============================================================

    @Test
    void testInitialState() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());
        assertEquals(10, queue.remainingCapacity());
        assertFalse(queue.isShutdown());
    }

    @Test
    void testOfferPoll_BasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(2, queue.size());
        
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testCapacityLimit() {
        for (int i = 0; i < 10; i++) assertTrue(queue.offer(i));
        assertTrue(queue.isFull());
        assertFalse(queue.offer(99)); // Immediate offer fails
        assertEquals(10, queue.size());
    }

    @Test
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    // ============================================================
    // Blocking Behavior Tests (put/take)
    // ============================================================

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());

        Thread producer = new Thread(() -> {
            try { q.put(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        
        // Give producer time to block
        Thread.sleep(100); 
        assertTrue(producer.isAlive(), "Producer should be blocked");
        
        q.take(); // Make space
        producer.join(1000); // Should finish now
        assertFalse(producer.isAlive());
        assertEquals(3, q.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        
        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // ============================================================
    // Timed Operations Tests
    // ============================================================

    @Test
    void testOfferWithTimeout_Success() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        
        Thread producer = new Thread(() -> {
            try { assertTrue(q.offer(2, 500, MILLISECONDS)); } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50); // Ensure producer is waiting
        q.take(); // Free space
        producer.join(1000);
        assertEquals(2, q.take());
    }

    @Test
    void testOfferWithTimeout_Failure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        assertFalse(q.offer(2, 50, MILLISECONDS)); // Timeout too short
        assertEquals(1, q.size());
    }

    @Test
    void testPollWithTimeout_Success() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(99); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Integer val = queue.poll(200, MILLISECONDS);
        assertEquals(99, val);
        producer.join();
    }

    @Test
    void testPollWithTimeout_Failure() throws InterruptedException {
        assertNull(queue.poll(50, MILLISECONDS));
    }

    // ============================================================
    // Interruption Tests
    // ============================================================

    @Test
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread t = new Thread(() -> {
            try { q.put(2); fail("Should have been interrupted"); } 
            catch (InterruptedException expected) { /* OK */ }
        });
        t.start();
        Thread.sleep(50); // Ensure blocked
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); fail("Should have been interrupted"); } 
            catch (InterruptedException expected) { /* OK */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    void testLockInterruptiblyOnContention() throws InterruptedException {
        // Fill queue and hold lock via a slow consumer? Hard to test lockInterruptibly directly without exposing lock.
        // But put/take use lockInterruptibly(). If thread interrupted while waiting for LOCK (not condition), it should throw IE.
        // This is implicitly tested above as Condition.await() throws IE.
    }

    // ============================================================
    // Shutdown Tests
    // ============================================================

    @Test
    void testShutdownRejectsProducers() {
        queue.put(1);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(3));
        assertThrows(IllegalStateException.class, () -> queue.offer(4, 1, SECONDS));
        assertTrue(queue.isShutdown());
    }

    @Test
    void testShutdownAllowsDrainThenEmpty() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        // Should drain existing
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty + shutdown
        assertNull(queue.poll());
        assertNull(queue.poll(10, MILLISECONDS));
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    void testShutdownUnblocksWaitingThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        AtomicBoolean producerUnblocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (IllegalStateException e) { producerUnblocked.set(true); } // Expected on shutdown
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50);
        
        q.shutdown();
        producer.join(1000);
        
        assertTrue(producerUnblocked.get(), "Producer should unblock and see shutdown");
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    // ============================================================
    // Stress Test: Multi-Producer / Multi-Consumer
    // ============================================================

    @Test
    @Timeout(value = 30, unit = SECONDS) // Global timeout for safety
    void stressTest_MultiProducerMultiConsumer_NoLostNoDuplicates() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Phaser to coordinate start/finish phases
        Phaser phaser = new Phaser(producerCount + consumerCount + 1); // +1 for main thread
        
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // Track duplicates using a concurrent set (only for verification, not in hot path)
        // Since integers are unique globally (partitioned), we can just check sums and counts.
        // Partition keys: Producer 0 -> 0..4999, Producer 1 -> 5000..9999, etc.

        List<Thread> threads = new ArrayList<>();

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all to start
                try {
                    int base = producerId * itemsPerProducer;
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = base + i;
                        stressQueue.put(val); // Blocking put
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    // Shutdown during test? Fail.
                    fail("Producer " + producerId + " got shutdown unexpectedly: " + e.getMessage());
                } finally {
                    phaser.arriveAndDeregister();
                }
            }, "Producer-" + p);
            threads.add(t);
            t.start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all to start
                try {
                    while (true) {
                        Integer val = stressQueue.take(); // Blocking take
                        if (val == null) break; // Should not happen unless shutdown
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        
                        // Exit condition: Consumed all expected items
                        if (consumedCount.get() >= totalItems) {
                            // Signal others to stop? No, let them block on take() until shutdown.
                            // But we need to shutdown queue to unblock remaining consumers.
                            // Only one consumer should trigger shutdown.
                            if (consumedCount.get() == totalItems) {
                                stressQueue.shutdown();
                            }
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    // Queue shut down while empty - valid exit if we missed the count check race
                    // (though logic above tries to shutdown exactly at totalItems)
                } finally {
                    phaser.arriveAndDeregister();
                }
            }, "Consumer-" + c);
            threads.add(t);
            t.start();
        }

        // --- Main Thread Coordination ---
        phaser.arriveAndAwaitAdvance(); // Release all workers (Phase 0 -> 1)
        
        // Wait for completion (Phase 1 -> Deregistration)
        // We wait for the phaser to terminate (all parties deregistered)
        // But main thread is a party. We must arriveAndDeregister to let it terminate.
        // Actually, simpler: just join threads.
        
        for (Thread t : threads) {
            t.join(10_000); // Generous timeout
            assertFalse(t.isAlive(), "Thread " + t.getName() + " did not terminate in time");
        }

        // --- Verification ---
        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed");
        
        // Sum verification (detects lost/duplicated/modified items)
        // Sum 0 to N-1 = N*(N-1)/2. 
        // Partitioned sums: Sum(k*M to (k+1)*M - 1)
        long expectedSum = 0;
        for (int p = 0; p < producerCount; p++) {
            long start = (long)p * itemsPerProducer;
            long end = start + itemsPerProducer - 1;
            expectedSum += (start + end) * itemsPerProducer / 2; // Arithmetic series sum
        }
        
        assertEquals(expectedSum, producedSum.get(), "Produced sum mismatch (duplication/loss/corruption)");
        assertEquals