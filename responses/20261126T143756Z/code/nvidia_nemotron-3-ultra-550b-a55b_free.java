<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261126T143756Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Arrays;
import java.util.Objects;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * 
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state
 * (the circular array, head, tail, count, and shutdown flag). This coarse-grained locking
 * strategy ensures simplicity and linearizability of operations.
 * </p>
 * <p>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty:</b> Signaled when an element is added (enqueued). Waited upon by {@code take()}
 *       and {@code poll(timeout)} when the queue is empty.</li>
 *   <li><b>notFull:</b> Signaled when an element is removed (dequeued). Waited upon by {@code put()}
 *       and {@code offer(timeout)} when the queue is full.</li>
 * </ul>
 * This "Two-Condition" pattern allows precise signaling: producers only wake consumers (via {@code notEmpty})
 * and consumers only wake producers (via {@code notFull}), avoiding "spurious wakeups" wasting CPU cycles
 * on threads that cannot proceed.
 * </p>
 * <p>
 * <b>Shutdown Protocol:</b> A {@code volatile boolean shutdown} flag acts as a memory barrier.
 * When {@code shutdown()} is called:
 * <ol>
 *   <li>The flag is set to {@code true}.</li>
 *   <li>The lock is acquired, and <b>both</b> conditions are signaled {@code signalAll()}.</li>
 *   <li>This unblocks all waiting producers and consumers immediately.</li>
 * </ol>
 * Producers ({@code put}, {@code offer}) fail fast with {@link IllegalStateException}/{@code false}.
 * Consumers ({@code take}, {@code poll}) drain remaining elements normally; once empty, they return
 * {@code null} (poll) or throw {@link IllegalStateException} (take).
 * </p>
 * 
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    // --- State ---
    private final Object[] items; // Circular buffer
    private int head = 0;         // Index to take from
    private int tail = 0;         // Index to put at
    private int count = 0;        // Current number of elements
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    private volatile boolean shutdown = false;

    // --- Constructors ---
    
    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.items = new Object[capacity];
    }

    // --- Core Helpers (Must hold lock) ---

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

    private boolean isFull() { return count == items.length; }
    private boolean isEmpty() { return count == 0; }

    private void checkNotShutdownForProducers() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    // --- Blocking API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly();
        try {
            checkNotShutdownForProducers();
            while (isFull()) {
                notFull.await();
                checkNotShutdownForProducers(); // Re-check after wakeup
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
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
            while (isEmpty()) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E e = dequeue();
            notFull.signal(); // Wake up one producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return {@code true} if successful, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdownForProducers();
            while (isFull()) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                checkNotShutdownForProducers();
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
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (isEmpty()) {
                if (shutdown) return null; // Shutdown + empty = immediate null
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

    // --- Immediate / Inspection API ---

    /** @return {@code true} if inserted, {@code false} if full */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            if (shutdown || isFull()) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element or {@code null} if empty */
    public E poll() {
        lock.lock();
        try {
            if (isEmpty()) return null;
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element without removing, or {@code null} if empty */
    public E peek() {
        lock.lock();
        try {
            return isEmpty() ? null : (E) items[head];
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public boolean isEmptyStatus() {
        lock.lock();
        try { return isEmpty(); } finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return items.length - count; } finally { lock.unlock(); }
    }

    // --- Shutdown ---

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>No new elements can be added ({@code put}/{@code offer} fail).</li>
     *   <li>Existing elements can be consumed normally.</li>
     *   <li>Once empty, {@code take} throws {@link IllegalStateException}, {@code poll} returns {@code null}.</li>
     *   <li>All blocked threads are unblocked immediately.</li>
     * </ul>
     * This method is idempotent.
     */
    public void shutdown() {
        if (shutdown) return; // Fast path
        lock.lock();
        try {
            if (shutdown) return; // Double check under lock
            shutdown = true;
            // Wake everyone: producers fail fast, consumers drain then exit
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Removes all elements (only allowed after shutdown for safety, or for testing).
     * @return list of drained elements
     */
    public java.util.List<E> drain() {
        lock.lock();
        try {
            java.util.List<E> list = new java.util.ArrayList<>(count);
            while (!isEmpty()) list.add(dequeue());
            notFull.signalAll();
            return list;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.value.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 * Covers: Functional correctness, Blocking/Timing behavior, Shutdown protocol,
 * and High-concurrency Stress Testing (Linearizability / No Lost/Duplicate Elements).
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // ============================================================
    // Basic Functional & Contract Tests
    // ============================================================

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testNullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    void testBasicFifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testCapacityBound() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.offer(3, 100, MILLISECONDS) == false); // Should fail fast/timeout
        assertEquals(2, q.size());
    }

    @Test
    void testPeekAndPoll() throws InterruptedException {
        assertNull(queue.peek());
        assertNull(queue.poll());
        
        queue.put(10);
        assertEquals(10, queue.peek());
        assertEquals(10, queue.peek()); // Peek doesn't remove
        assertEquals(10, queue.poll());
        assertNull(queue.poll());
    }

    // ============================================================
    // Blocking Behavior Tests (Single Threaded Simulation via Timeout)
    // ============================================================

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        
        Thread putter = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(100); // Ensure putter is parked
        assertTrue(putter.isAlive(), "Put should block when full");
        
        q.take(); // Make space
        putter.join(1000);
        assertFalse(putter.isAlive(), "Put should unblock after take");
        assertEquals(2, q.take());
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        taker.start();
        Thread.sleep(100);
        assertTrue(taker.isAlive(), "Take should block when empty");
        
        queue.put(42);
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    @ParameterizedTest
    @ValueSource(longs = {50, 200})
    void testOfferPollTimeout(long timeoutMs) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        
        long start = System.nanoTime();
        boolean offered = q.offer(2, timeoutMs, MILLISECONDS);
        long elapsed = MILLISECONDS.convert(System.nanoTime() - start, NANOSECONDS);
        
        assertFalse(offered);
        // Allow some slack for scheduler, but ensure it roughly waited
        assertTrue(elapsed >= timeoutMs - 20, "Should wait approx timeout: " + elapsed + " vs " + timeoutMs);
    }

    // ============================================================
    // Shutdown Protocol Tests
    // ============================================================

    @Test
    void testShutdownRejectsProducers() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(3));
        assertFalse(queue.offer(4, 1, SECONDS));
        assertTrue(queue.isShutdown());
    }

    @Test
    void testShutdownAllowsDrainThenFails() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        // Can still drain
        assertEquals(1, queue.take());
        assertEquals(2, queue.poll(1, SECONDS));
        
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(1, SECONDS));
    }

    @Test
    void testShutdownUnblocksWaitingThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        AtomicInteger putResult = new AtomicInteger(-1); // -1=running, 0=exception, 1=success
        Thread putter = new Thread(() -> {
            try { q.put(2); putResult.set(1); } 
            catch (IllegalStateException e) { putResult.set(0); }
            catch (InterruptedException e) { putResult.set(-2); }
        });
        putter.start();
        Thread.sleep(50); // Ensure parked on notFull
        
        AtomicInteger takeResult = new AtomicInteger(-1);
        Thread taker = new Thread(() -> {
            try { q.take(); takeResult.set(1); } 
            catch (IllegalStateException e) { takeResult.set(0); }
            catch (InterruptedException e) { takeResult.set(-2); }
        });
        taker.start();
        Thread.sleep(50); // Ensure parked on notEmpty (queue empty after putter? no, queue has 1 item. taker takes it, then parks waiting for 2nd)
        // Correction: Queue has 1. Taker takes 1 -> Queue empty. Taker parks waiting for next.
        
        q.shutdown();
        
        putter.join(1000);
        taker.join(1000);
        
        // Putter was waiting to put -> IllegalStateException (0)
        assertEquals(0, putResult.get(), "Waiting producer should get IllegalStateException");
        // Taker was waiting to take -> IllegalStateException (0) because queue became empty after shutdown
        assertEquals(0, takeResult.get(), "Waiting consumer (empty queue) should get IllegalStateException");
    }

    // ============================================================
    // Concurrency Stress Tests
    // ============================================================

    /**
     * Stress Test: Multi-Producer / Multi-Consumer.
     * Verifies: No lost elements, No duplicates, Exact count integrity.
     * Strategy: 
     * 1. Producers generate unique IDs (0 to TOTAL-1).
     * 2. Consumers collect IDs into a ConcurrentHashMap (ID -> Count).
     * 3. After completion, verify every ID appeared exactly once.
     */
    @Test
    void stressTestMultiProducerMultiConsumer_NoLostNoDuplicates() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCERS = 8;
        final int CONSUMERS = 8;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;
        
        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        
        // Shared state for verification
        Map<Integer, Integer> consumedCounts = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);
        AtomicInteger producedCount = new AtomicInteger(0);
        LongAdder consumedSum = new LongAdder(); // For rough throughput check

        // --- Producers ---
        for (int p = 0; p < PRODUCERS; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        // Generate globally unique ID
                        int item = producerId * ITEMS_PER_PRODUCER + i;
                        stressQueue.put(item);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    fail("Producer got shutdown exception unexpectedly");
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < CONSUMERS; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer item = stressQueue.poll(100, MILLISECONDS);
                        if (item == null) {
                            // Timeout: Check if production finished and queue empty
                            if (producedCount.get() == TOTAL_ITEMS && stressQueue.isEmptyStatus()) break;
                            continue; // Spurious wakeup or slow producer
                        }
                        consumedSum.add(item);
                        consumedCounts.merge(item, 1, Integer::sum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go! ---
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for completion with generous timeout
        assertTrue(doneLatch.await(30, SECONDS), "Test timed out");
        long durationMs = MILLISECONDS.convert(System.nanoTime() - startTime, NANOSECONDS);
        
        // --- Verification ---
        System.out.printf("Stress Test Done: %d items in %d ms (%.0f ops/sec)%n", 
            TOTAL_ITEMS, durationMs, (TOTAL_ITEMS * 2.0 / durationMs) * 1000);

        // 1. Count Check
        assertEquals(TOTAL_ITEMS, producedCount.get(), "All items produced");
        assertEquals(TOTAL_ITEMS, consumedCounts.size(), "All unique items consumed (no lost)");
        
        // 2. Duplicate Check
        List<Integer> duplicates = consumedCounts.entrySet().stream()
            .filter(e -> e.getValue() != 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        assertTrue(duplicates.isEmpty(), "Duplicates found: " + duplicates);
        
        // 3. Completeness Check (Set equality)
        Set<Integer> expected = IntStream.range(0, TOTAL_ITEMS).boxed().collect(Collectors.toSet());
        assertEquals(expected, consumedCounts.keySet(), "Missing or extra elements");
        
        // 4. Queue truly empty
        assertTrue(stressQueue.isEmptyStatus());
        assertEquals(0, stressQueue.size());
    }

    /**
     * Stress Test: Shutdown under load.
     * Producers/Consumers running, then shutdown called. 
     * Verify no crashes, remaining items drainable.
     */
    @Test
    void stressTestShutdownUnderLoad() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(10);
        AtomicInteger produced = new AtomicInteger();
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        volatile boolean shutdownTriggered = false;

        // 5 Producers
        for (int i=0; i<5; i++) new Thread(() -> {
            try { start.await(); while(!shutdownTriggered) { q.put(produced.incrementAndGet()); } } 
            catch (InterruptedException | IllegalStateException ignored) {} finally { stop.countDown(); }
        }).start();

        // 5 Consumers
        for (int i=0; i<5; i++) new Thread(() -> {
            try { start.await(); while(true) { Integer v = q.poll(10, MILLISECONDS); if(v!=null) consumed.add(v); if(shutdownTriggered && q.isEmptyStatus()) break; } }
            catch (InterruptedException ignored) {} finally { stop.countDown(); }
        }).start();

        start.countDown();
        Thread.sleep(500); // Let it run hot
        shutdownTriggered = true;
        q.shutdown(); // Trigger unblock
        
        assertTrue(stop.await(5, SECONDS));
        
        // Drain remainder
        List<Integer> remainder = q.drain();
        consumed.addAll(remainder);
        
        // Verify integrity of what *was* processed
        Set<Integer> unique = new HashSet<>(consumed);
        assertEquals(consumed.size(), unique.size(), "Duplicates detected during shutdown stress");
        assertTrue(produced.get() >= consumed.size(), "Lost items during shutdown");
        System.out.println("Shutdown Stress: Produced " + produced.get() + ", Consumed " + consumed.size());
    }

    /**
     * Fairness / Starvation Check (Qualitative).
     * Runs producers/consumers with high contention on a tiny queue (size 1).
     * Ensures threads make progress (no deadlock/livelock).
     */
    @Test
    void stressTestTinyQueueHighContention() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1); // Maximum contention
        int THREADS = 20;
        int OPS = 1000;
        CountDownLatch latch = new CountDownLatch(THREADS * 2);
        AtomicInteger counter = new AtomicInteger();

        for(int i=0;i<THREADS;i++) new Thread(() -> {
            try { for(int j=0;j<OPS;j++) q.put(counter.incrementAndGet()); } catch(Exception ignored) {} finally { latch.countDown(); }
        }).start();
        
        for(int i=0;i<THREADS;i++) new Thread(() -> {
            try { for(int j=0;j<OPS;j++) { Integer v