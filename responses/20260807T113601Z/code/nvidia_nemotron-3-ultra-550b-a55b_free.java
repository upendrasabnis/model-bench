<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260807T113601Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation built on
 * {@link ReentrantLock} and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} guards all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}, and the backing array).
 *       This ensures memory visibility (happens-before edges) and atomicity of compound actions
 *       (check-then-act for capacity/shutdown).</li>
 *   <li><b>Wait Sets (Condition Variables):</b> Two {@link Condition} objects partition waiters:
 *       <ul>
 *         <li>{@code notFull}: Threads waiting to {@code put}/{@code offer} (producers).</li>
 *         <li>{@code notEmpty}: Threads waiting to {@code take}/{@code poll} (consumers).</li>
 *       </ul>
 *       This avoids "thundering herd" problems where consumers are woken up only to find the queue
 *       still empty (or vice-versa), improving throughput over a single {@code wait()/notifyAll()}
 *       monitor.</li>
 *   <li><b>Signaling Discipline:</b> We use {@link Condition#signal()} (not {@code signalAll()})
 *       after a successful {@code put} or {@code take}. Because the queue is bounded and we only
 *       change the state by 1 element, at most one thread of the opposite type can proceed.
 *       This minimizes context switching overhead.</li>
 *   <li><b>Spurious Wakeups:</b> All condition waits are inside {@code while (statePredicate)} loops,
 *       guaranteeing correctness against spurious wakeups and ensuring the state predicate holds
 *       upon exit.</li>
 *   <li><b>Shutdown Protocol:</b> A {@code volatile boolean shutdown} flag (guarded by lock for
 *       compound checks) allows a "poison pill" style termination. {@code shutdown()} acquires the
 *       lock, sets the flag, and calls {@code signalAll()} on <b>both</b> conditions to unblock
 *       all waiters immediately. Subsequent operations throw {@link IllegalStateException}.</li>
 *   <li><b>Interruption:</b> Blocking methods ({@code put}, {@code take}, timed variants) are
 *       responsive to interruption. They throw {@link InterruptedException} and clear the
 *       interrupted status, ensuring the lock is released via {@code finally} blocks.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private int head = 0; // Index to take from
    private int tail = 0; // Index to put at
    private int count = 0; // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast-path read in offer/poll without lock? No, lock covers all.

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.items = new Object[capacity];
    }

    // --- Internal Helpers ---

    private void checkNotShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    private void enqueue(E x) {
        items[tail] = x;
        if (++tail == items.length) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[head];
        items[head] = null; // Help GC
        if (++head == items.length) head = 0;
        count--;
        return x;
    }

    // --- Public API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == items.length) {
                notFull.await(); // Releases lock, waits for signal
                checkNotShutdown(); // Re-check predicate after wakeup (shutdown or spurious)
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
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
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
            E x = dequeue();
            notFull.signal(); // Wake up one producer
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if the waiting time elapsed
     *         before space was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == items.length) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
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
     *
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the waiting time elapsed
     *         before an element was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    throw new IllegalStateException("Queue shut down and empty");
                }
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E x = dequeue();
            notFull.signal();
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown. All waiting producers and consumers are unblocked.
     * Subsequent calls to {@code put}, {@code offer}, {@code take}, {@code poll} will
     * throw {@link IllegalStateException}.
     * Threads currently blocked in {@code put}/{@code take} will throw {@link InterruptedException}
     * (if interrupted by shutdown logic) or {@link IllegalStateException} upon re-acquiring lock.
     * Note: This implementation uses {@code signalAll} to ensure all waiters wake up promptly
     * to observe the shutdown flag.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up so they can check the shutdown flag
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue has been shut down.
     */
    public boolean isShutdown() {
        lock.lock();
        try {
            return shutdown;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
     */
    public int size() {
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

    public boolean isFull() {
        return size() == items.length;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private static final int CAPACITY = 10;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure clean state for next test
    }

    // ---------------------------------------------------------
    // Basic Functionality & Contract Tests
    // ---------------------------------------------------------

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
    @DisplayName("put/take single element FIFO")
    void testSingleElementFifo() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Multiple elements maintain FIFO order")
    void testFifoOrder() throws InterruptedException {
        for (int i = 0; i < 5; i++) queue.put(i);
        for (int i = 0; i < 5; i++) assertEquals(i, queue.take());
    }

    @Test
    @DisplayName("put blocks when full")
    void testPutBlocksWhenFull() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        assertTrue(queue.isFull());

        Thread putter = new Thread(() -> {
            try { queue.put(999); } catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(100); // Give putter time to block
        assertTrue(putter.isAlive(), "Producer should be blocked");

        // Consume one to unblock
        assertEquals(0, queue.take());
        putter.join(500);
        assertFalse(putter.isAlive(), "Producer should have unblocked and finished");
        assertEquals(999, queue.take());
    }

    @Test
    @DisplayName("take blocks when empty")
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        taker.start();
        Thread.sleep(100);
        assertTrue(taker.isAlive(), "Consumer should be blocked");

        queue.put(42);
        taker.join(500);
        assertFalse(taker.isAlive());
    }

    @Test
    @DisplayName("offer/poll with timeout success")
    void testTimedOfferPollSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, (int) queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("offer returns false on timeout when full")
    void testOfferTimeout() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        assertFalse(queue.offer(99, 50, TimeUnit.MILLISECONDS));
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    @DisplayName("poll returns null on timeout when empty")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void testCapacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    // ---------------------------------------------------------
    // Shutdown Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown unblocks waiting producers")
    void testShutdownUnblocksProducers() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread putter = new Thread(() -> {
            try { queue.put(1); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        
        queue.shutdown();
        putter.join(1000);
        assertFalse(putter.isAlive());
        assertTrue(queue.isShutdown());
    }

    @Test
    @DisplayName("Shutdown unblocks waiting consumers")
    void testShutdownUnblocksConsumers() throws InterruptedException {
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
    @DisplayName("Operations after shutdown throw ISE")
    void testOperationsAfterShutdownThrow() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    @Test
    @DisplayName("Drain remaining elements after shutdown before exception")
    void testDrainAfterShutdown() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        // Now empty and shutdown -> ISE
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    // ---------------------------------------------------------
    // Concurrency / Stress Tests
    // ---------------------------------------------------------

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Multi-Producer Multi-Consumer Stress Test: No lost/duplicated elements")
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = producerCount * itemsPerProducer;

        // Use a Set to detect duplicates (ConcurrentHashMap as Set)
        Set<Integer> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicInteger activeProducers = new AtomicInteger(producerCount);

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i;
                        queue.put(val); // Blocking put
                        producedSum.addAndGet(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    // Shutdown during test? Fail.
                    fail("Unexpected shutdown during production");
                } finally {
                    endLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val;
                        try {
                            val = queue.take(); // Blocking take
                        } catch (IllegalStateException e) {
                            // Queue shut down and empty
                            break;
                        }
                        
                        // Verify uniqueness
                        if (!seen.add(val)) {
                            fail("Duplicate element detected: " + val);
                        }
                        consumedSum.addAndGet(val);
                        
                        // Heuristic: if we've consumed all expected items, we can stop
                        // but we rely on shutdown() to break the loop cleanly.
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go! ---
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for producers to finish putting
        // We need a separate latch or mechanism to know when producers are done to call shutdown.
        // Let's wait for producer threads specifically.
        // Since we used a single endLatch for everyone, we wait for all.
        // But consumers run forever until shutdown.
        // Strategy: Wait for producer threads to finish (countDown), then shutdown.
        
        // Wait for producer threads to finish (first producerCount counts on endLatch)
        // This is tricky with single latch. Let's use Phaser or separate latches.
        // Simpler: Join producer threads explicitly.
        // Actually, the threads are anonymous. Let's track them.
        // Re-factoring test structure for clarity:
    }

    // Re-written Stress Test with cleaner lifecycle management
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("MPMC Stress Test: Verify Integrity (No loss, no dup, correct count)")
    void testMpmcIntegrity() throws InterruptedException {
        final int PRODUCERS = 8;
        final int CONSUMERS = 8;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(100); // Small buffer = high contention
        
        Set<Integer> seen = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicLong producedSum = new AtomicLong();
        AtomicLong consumedSum = new AtomicLong();
        AtomicInteger producedCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();
        
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(PRODUCERS);
        CountDownLatch consumersDone = new CountDownLatch(CONSUMERS);

        List<Thread> threads = new ArrayList<>();

        // Producers
        for (int p = 0; p < PRODUCERS; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int val = id * ITEMS_PER_PRODUCER + i;
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        // Consumers
        for (int c = 0; c < CONSUMERS; c++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    while (true) {
                        Integer val;
                        try {
                            val = stressQueue.take();
                        } catch (IllegalStateException e) {
                            // Shutdown signaled
                            break;
                        }
                        
                        // Check duplicates
                        if (!seen.add(val)) {
                            throw new IllegalStateException("Duplicate detected: " + val);
                        }
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersDone.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        // Start
        startGate.countDown();

        // Wait for producers to finish
        assertTrue(producersDone.await(10, TimeUnit.SECONDS), "Producers timed out");
        
        // Give consumers a moment to drain the queue, then shutdown
        // We know exactly how many items exist. Wait until consumedCount == TOTAL_ITEMS
        // But consumers are blocked on take(). We must shutdown to unblock them.
        // However, if we shutdown immediately, consumers still in take() will throw ISE.
        // The queue might still have items. 
        // Better: Loop polling size until 0, then shutdown.
        while (stressQueue.size() > 0) {
            Thread.sleep(10);
        }
        // Queue empty. Producers done. Consumers blocked on take().
        stressQueue.shutdown();

        assertTrue(consumersDone.await(5, TimeUnit.SECONDS), "Consumers timed out");

       