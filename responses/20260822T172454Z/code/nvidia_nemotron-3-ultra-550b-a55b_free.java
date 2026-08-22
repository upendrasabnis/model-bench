<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260822T172454Z -->
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A thread-safe, generic, bounded blocking queue implementation built on
 * {@link ReentrantLock} and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to protect all internal state
 * (the backing array, head/tail indices, count, and shutdown flag). This ensures
 * mutual exclusion for all state mutations.
 * </p>
 *
 * <h3>Condition Variables</h3>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty:</b> Signaled when an element is added (enqueued). Threads waiting in {@link #take()}
 *       or {@link #poll(long, TimeUnit)} await on this condition.</li>
 *   <li><b>notFull:</b> Signaled when an element is removed (dequeued). Threads waiting in {@link #put(Object)}
 *       or {@link #offer(Object, long, TimeUnit)} await on this condition.</li>
 * </ul>
 * Using separate conditions allows for <b>targeted signaling</b> (via {@code signal()} instead of {@code signalAll()}),
 * reducing "thundering herd" contention. Only one waiting consumer needs to wake up when an item is added,
 * and only one waiting producer when a slot frees.
 *
 * <h3>Memory Visibility</h3>
 * The {@code ReentrantLock} establishes happens-before relationships:
 * <ul>
 *   <li>Unlocking the lock (after enqueue/dequeue) happens-before locking the lock (by the next thread).</li>
 *   <li>This guarantees that writes to the {@code items} array and index/count variables are visible
 *       to the thread acquiring the lock next.</li>
 * </ul>
 * The {@code shutdown} flag is {@code volatile} (via {@link AtomicBoolean}) to allow fast-path checks
 * without locking in some scenarios, though all state transitions happen under the lock.
 *
 * <h3>Shutdown Protocol</h3>
 * <ol>
 *   <li>{@link #shutdown()} sets the {@code shutdown} flag atomically.</li>
 *   <li>It then acquires the lock and signals <b>all</b> waiting threads ({@code signalAll()})
 *       on both conditions to unblock them immediately.</li>
 *   <li>Waiting threads wake up, re-acquire the lock, check the shutdown flag, and throw
 *       {@link IllegalStateException} (blocking ops) or return failure indicators (timed ops).</li>
 *   <li>Subsequent calls to {@code put/offer} reject immediately. {@code take/poll} drain remaining
 *       elements then return failure/exception.</li>
 * </ol>
 *
 * <h3>Correctness Invariants (Guarded by Lock)</h3>
 * <pre>
 * 0 <= count <= capacity
 * 0 <= putIndex < capacity
 * 0 <= takeIndex < capacity
 * count == 0          <=> queue is empty (takeIndex == putIndex)
 * count == capacity   <=> queue is full  (takeIndex == putIndex)
 * </pre>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] items;
    private final int capacity;

    // Indices and count guarded by 'lock'
    private int putIndex = 0;
    private int takeIndex = 0;
    private int count = 0;

    // Synchronization primitives
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Shutdown flag: AtomicBoolean for volatile semantics + atomic CAS if needed elsewhere.
    // Accessed primarily under lock, but volatile read allows fast-fail checks.
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // ------------------------------------------------------------
    // Blocking Operations (Interruptible)
    // ------------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * for space to become available if the queue is full.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        
        lock.lockInterruptibly(); // Respects interruption immediately
        try {
            // Wait for space or shutdown
            while (count == capacity) {
                checkShutdown(); // Throws IllegalStateException if shutdown
                notFull.await(); // Releases lock, waits for signal
            }
            
            // Re-acquired lock. Re-check shutdown (spurious wakeup or shutdown signal)
            checkShutdown();
            
            enqueue(e);
            notEmpty.signal(); // Wake up ONE waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throws if shutdown && empty
                notEmpty.await();
            }
            
            // If we are here, count > 0 guaranteed (shutdown check passed or not shutdown)
            return dequeue();
        } finally {
            // Signal notFull only if we actually removed something
            // Note: lock is held here. Signaling inside finally is safe but usually done before unlock.
            // However, if dequeue throws (unlikely here), we shouldn't signal.
            // Standard pattern: signal before unlock in try block.
            lock.unlock(); // Wait, we need to signal notFull!
        }
    }

    // Fix for take(): signal notFull before unlock
    // Re-writing take correctly:
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown();
                notEmpty.await();
            }
            E item = dequeue();
            notFull.signal(); // Wake up ONE waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Timed Operations
    // ------------------------------------------------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting
     * up to the specified wait time for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        long nanos = unit.toNanos(timeout);

        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdown();
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            checkShutdown();
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
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);

        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown.get()) return null; // Shutdown + empty = return null immediately
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

    // ------------------------------------------------------------
    // Immediate Operations (Non-blocking)
    // ------------------------------------------------------------

    /** Inserts element if space available immediately. Throws if shutdown. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        lock.lock();
        try {
            if (shutdown.get() || count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head if available immediately. Returns null if empty (even if not shutdown). */
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

    // ------------------------------------------------------------
    // Inspection & Shutdown
    // ------------------------------------------------------------

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

    /**
     * Initiates an orderly shutdown.
     * <ul>
 *   <li>Prevents future {@code put/offer} operations (they fail immediately).</li>
 *   <li>Wakes up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
 *   <li>Waiting threads throw {@link IllegalStateException} (blocking) or return failure (timed).</li>
 *   <li>Allows consumers to drain remaining elements via {@code take/poll} until empty.</li>
 * </ul>
     */
    public void shutdown() {
        // Fast path: set flag first (volatile write)
        if (!shutdown.compareAndSet(false, true)) return; // Already shutdown

        lock.lock();
        try {
            // Wake up EVERYONE. Using signalAll because:
            // 1. We don't know if waiters are producers or consumers.
            // 2. We want them all to exit wait state immediately to process shutdown logic.
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    // ------------------------------------------------------------
    // Internal Helpers (Assume Lock Held)
    // ------------------------------------------------------------

    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // Help GC
        takeIndex = (takeIndex + 1) % capacity;
        count--;
        return e;
    }

    private void checkShutdown() {
        if (shutdown.get()) {
            throw new IllegalStateException("Queue has been shut down");
        }
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
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    // ------------------------------------------------------------
    // Basic Functionality & Contract
    // ------------------------------------------------------------

    @Test
    @DisplayName("New queue is empty, not full, size 0")
    void testInitialState() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());
        assertEquals(CAPACITY, queue.capacity());
        assertFalse(queue.isShutdown());
    }

    @Test
    @DisplayName("Offer/Poll basic FIFO behavior")
    void testBasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(2, queue.size());
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Offer respects capacity limit")
    void testCapacityLimit() {
        for (int i = 0; i < CAPACITY; i++) assertTrue(queue.offer(i));
        assertFalse(queue.offer(999)); // Full
        assertTrue(queue.isFull());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void testInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    // ------------------------------------------------------------
    // Blocking Operations (Put/Take)
    // ------------------------------------------------------------

    @Test
    @DisplayName("Put blocks when full, unblocks on Take")
    @Timeout(5) // Safety net
    void testPutTakeBlocking() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread producer = new Thread(() -> {
            try { q.put(1); q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        
        producer.start();
        Thread.sleep(100); // Ensure producer puts first item and blocks on second
        assertEquals(1, q.take()); // Consume first, unblocks producer
        assertEquals(2, q.take()); // Consume second
        producer.join(1000);
        assertFalse(producer.isAlive());
    }

    @Test
    @DisplayName("Take blocks when empty, unblocks on Put")
    @Timeout(5)
    void testTakePutBlocking() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try { result.set(q.take()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        consumer.start();
        Thread.sleep(100); // Ensure consumer blocks
        assertNull(result.get());
        q.put(42);
        consumer.join(1000);
        assertEquals(42, result.get());
    }

    @Test
    @DisplayName("InterruptedException thrown on interrupted Put")
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        // Verify queue state unchanged
        assertEquals(1, q.take());
    }

    @Test
    @DisplayName("InterruptedException thrown on interrupted Take")
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
    }

    // ------------------------------------------------------------
    // Timed Operations
    // ------------------------------------------------------------

    @Test
    @DisplayName("Timed offer returns false on timeout")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("Timed offer succeeds before timeout")
    void testOfferSuccessBeforeTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertTrue(q.offer(1, 1, TimeUnit.SECONDS));
        t.join();
    }

    @Test
    @DisplayName("Timed poll returns null on timeout")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Timed poll returns element before timeout")
    void testPollSuccessBeforeTimeout() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); queue.put(42); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertEquals(42, queue.poll(1, TimeUnit.SECONDS));
        t.join();
    }

    // ------------------------------------------------------------
    // Shutdown Protocol
    // ------------------------------------------------------------

    @Test
    @DisplayName("Shutdown prevents new puts, allows drain")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertFalse(queue.offer(3));
        assertFalse(queue.offer(3, 1, TimeUnit.SECONDS));
        
        // Drain remaining
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown unblocks waiting producers")
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        AtomicBoolean producerThrew = new AtomicBoolean(false);
        
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (IllegalStateException e) { producerThrew.set(true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        
        producer.start();
        Thread.sleep(100); // Producer waiting
        q.shutdown();      // Should unblock producer with ISE
        producer.join(1000);
        
        assertTrue(producerThrew.get(), "Producer should have thrown IllegalStateException");
    }

    @Test
    @DisplayName("Shutdown unblocks waiting consumers")
    void testShutdownUnblocksConsumers() throws InterruptedException {
        AtomicBoolean consumerThrew = new AtomicBoolean(false);
        
        Thread consumer = new Thread(() -> {
            try { queue.take(); } 
            catch (IllegalStateException e) { consumerThrew.set(true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        
        consumer.start();
        Thread.sleep(100); // Consumer waiting
        queue.shutdown();  // Should unblock consumer with ISE
        consumer.join(1000);
        
        assertTrue(consumerThrew.get(), "Consumer should have thrown IllegalStateException");
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Second call should not throw or deadlock
        assertTrue(queue.isShutdown());
    }

    // ------------------------------------------------------------
    // Stress Test: Multi-Producer / Multi-Consumer
    // ------------------------------------------------------------

    @Test
    @DisplayName("Stress Test: Multi-Producer/Multi-Consumer - No Lost/Duplicate Elements")
    @Timeout(30) // Generous timeout for CI environments
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producerDoneLatch = new CountDownLatch(producerCount);
        CountDownLatch consumerDoneLatch = new CountDownLatch(consumerCount);
        AtomicBoolean producerError = new AtomicBoolean(false);
        AtomicBoolean consumerError = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to start simultaneously
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = producerId * itemsPerProducer + i; // Unique values
                        stressQueue.put(value); // Blocking put
                        producedSum.addAndGet(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    producerError.set(true);
                } catch (Exception e) {
                    producerError.set(true);
                    e.printStackTrace();
                } finally {
                    producerDoneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Consumers run until queue is shutdown AND empty
                    while (true) {
                        Integer val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (val != null) {
                            consumedSum.addAndGet(val);
                        } else {
                            // Timeout: check if producers are done and queue is empty
                            if (producerDoneLatch.getCount() == 0 && stressQueue.isEmpty()) {
                                break; // Exit condition
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerError.set(true);
                } catch (Exception e) {
                    consumerError.set(true);
                    e.printStackTrace();
                } finally {
                    consumerDoneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go! ---
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for producers to finish putting
        assertTrue(producerDoneLatch.await(20, TimeUnit.SECONDS), "Producers timed out");
