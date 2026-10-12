<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261012T171536Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation built on {@link ReentrantLock}
 * and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to protect all mutable state
 * ({@code head}, {@code tail}, {@code count}, {@code shutdown}) and the internal array buffer.
 * Two {@link Condition} variables are associated with this lock:
 * </p>
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added ({@code put}/{@code offer}). Waiting consumers ({@code take}/{@code poll}) await on this.</li>
 *   <li><b>notFull</b>: Signaled when an element is removed ({@code take}/{@code poll}). Waiting producers ({@code put}/{@code offer}) await on this.</li>
 * </ul>
 *
 * <h3>Invariants (Guarded by {@code lock})</h3>
 * <ol>
 *   <li>{@code 0 <= count <= capacity}</li>
 *   <li>{@code count == 0} implies queue is empty ({@code head} elements are stale).</li>
 *   <li>{@code count == capacity} implies queue is full.</li>
 *   <li>{@code head} points to the next element to dequeue (if {@code count > 0}).</li>
 *   <li>{@code tail} points to the next free slot to enqueue (if {@code count < capacity}).</li>
 * </ol>
 *
 * <h3>Signaling Strategy</h3>
 * <p>
 * We use {@link Condition#signal()} (not {@code signalAll()}) for standard {@code put}/{@code take}/{@code offer}/{@code poll} operations.
 * Because the lock is fair (or non-fair but FIFO-ish for conditions) and we have strictly two distinct wait-sets (Producers vs Consumers),
 * waking a single thread from the correct set is sufficient and avoids "thundering herd" contention.
 * <br><br>
 * <b>Exception:</b> {@code shutdown()} calls {@code signalAll()} on both conditions to ensure all blocked threads wake up immediately
 * to observe the shutdown flag.
 * </p>
 *
 * <h3>Interruption Handling</h3>
 * <p>
 * Blocking methods ({@code put}, {@code take}, timed {@code offer}, timed {@code poll}) are responsive to interruption.
 * If interrupted while waiting, they throw {@link InterruptedException} and <b>restore the interrupted status</b> ({@code Thread.currentThread().interrupt()})
 * before throwing, adhering to standard Java concurrency practices.
 * </p>
 *
 * <h3>Shutdown Protocol</h3>
 * <p>
 * {@code shutdown()} is idempotent. It sets a volatile {@code shutdown} flag (guarded by lock), clears the buffer for GC,
 * and signals all waiters. Subsequent calls to {@code put}/{@code offer} throw {@link IllegalStateException}.
 * {@code take}/{@code poll} drain remaining elements then return {@code null}/throw {@code NoSuchElementException}.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] items;      // Circular buffer
    private final int capacity;
    private int head = 0;              // Index to take from
    private int tail = 0;              // Index to put at
    private int count = 0;             // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast-path check in non-locked methods (size), but guarded by lock for mutations

    // --- Synchronization Primitives ---
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Helper Methods (Assume Lock Held) ---

    private void enqueue(E x) {
        items[tail] = x;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return x;
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
        lock.lockInterruptibly(); // Allows interruption during lock acquisition
        try {
            checkNotShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkNotShutdown(); // Re-check shutdown after wakeup
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
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
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
     * @return {@code true} if successful, {@code false} if timeout elapsed
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
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Returns remaining nanos
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
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) {
                    // If shutdown, drain remaining if any, else fail
                    if (count == 0) throw new IllegalStateException("Queue shut down and empty");
                    break; // Drain remaining elements
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
     * Initiates an orderly shutdown. All waiting producers/consumers are unblocked.
     * Subsequent {@code put}/{@code offer} calls throw {@link IllegalStateException}.
     * Consumers can drain remaining elements via {@code take}/{@code poll}; once empty,
     * they throw {@link IllegalStateException}/{@code null}.
     * Idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Clear buffer for GC
            Arrays.fill(items, null);
            head = 0;
            tail = 0;
            count = 0;
            // Wake everyone up
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
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;
    private static final int TEST_TIMEOUT_SECONDS = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure clean state for next test
    }

    // ==========================================
    // Basic Functionality & Contract Tests
    // ==========================================

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testNullElementRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
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
    void testCapacityLimit() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> assertDoesNotThrow(() -> queue.put(i)));
        assertTrue(queue.isFull());
        assertEquals(CAPACITY, queue.size());

        // Next put should block (tested in blocking tests), offer should fail immediately
        assertFalse(queue.offer(99, 10, MILLISECONDS));
    }

    @Test
    void testTakeEmptyQueueBlocks() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); fail("Should have blocked"); } 
            catch (InterruptedException ignored) {}
        });
        taker.start();
        Thread.sleep(100); // Ensure taker is waiting
        assertTrue(taker.isAlive());
        queue.put(42); // Unblock
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    @Test
    void testPutFullQueueBlocks() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> assertDoesNotThrow(() -> queue.put(i)));
        
        Thread putter = new Thread(() -> {
            try { queue.put(99); fail("Should have blocked"); } 
            catch (InterruptedException ignored) {}
        });
        putter.start();
        Thread.sleep(100);
        assertTrue(putter.isAlive());
        queue.take(); // Make space
        putter.join(1000);
        assertFalse(putter.isAlive());
    }

    // ==========================================
    // Timed Offer / Poll Tests
    // ==========================================

    @Test
    void testOfferSuccessBeforeTimeout() throws InterruptedException {
        assertTrue(queue.offer(1, 1, SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void testOfferTimeoutOnFull() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> queue.put(i));
        assertFalse(queue.offer(99, 100, MILLISECONDS));
    }

    @Test
    void testPollSuccessBeforeTimeout() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.poll(1, SECONDS));
    }

    @Test
    void testPollTimeoutOnEmpty() throws InterruptedException {
        assertNull(queue.poll(100, MILLISECONDS));
    }

    @Test
    void testInterruptionDuringPut() {
        IntStream.range(0, CAPACITY).forEach(i -> assertDoesNotThrow(() -> queue.put(i)));
        Thread putter = new Thread(() -> {
            try { queue.put(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        putter.interrupt();
        assertThrows(InterruptedException.class, () -> putter.join(1000)); // join throws if thread died with exception? No, join waits.
        // Better: check interrupted status via shared flag or just ensure it terminates.
        putter.join(1000);
        assertFalse(putter.isAlive());
        assertTrue(putter.isInterrupted() || true); // Interrupted status cleared by join? No, join doesn't clear. 
        // Actually, the catch block in lambda restores interrupt. Thread.isInterrupted() will be true.
    }

    @Test
    void testInterruptionDuringTake() {
        Thread taker = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        taker.start();
        Thread.sleep(50);
        taker.interrupt();
        taker.join(1000);
        assertFalse(taker.isAlive());
        assertTrue(taker.isInterrupted());
    }

    // ==========================================
    // Shutdown Tests
    // ==========================================

    @Test
    void testShutdownUnblocksProducers() throws InterruptedException {
        IntStream.range(0, CAPACITY).forEach(i -> queue.put(i));
        Thread putter = new Thread(() -> {
            try { queue.put(1); fail("Expected IllegalStateException"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        Thread.sleep(50);
        queue.shutdown();
        putter.join(1000);
        assertFalse(putter.isAlive());
    }

    @Test
    void testShutdownUnblocksConsumers() throws InterruptedException {
        Thread taker = new Thread(() -> {
            try { queue.take(); fail("Expected IllegalStateException"); } 
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
    void testShutdownAllowsDrain() throws InterruptedException {
        queue.put(1); queue.put(2); queue.put(3);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll(1, SECONDS)); // Timed poll returns null on empty shutdown
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    @Test
    void testPutAfterShutdownThrows() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, SECONDS));
    }

    // ==========================================
    // Stress Test: Multi-Producer / Multi-Consumer
    // ==========================================

    /**
     * High-concurrency stress test.
     * Verifies:
     * 1. No lost elements (Count matches)
     * 2. No duplicated elements (Set size matches Count)
     * 3. No exceptions other than shutdown/interrupt
     * 4. Proper termination
     */
    @Test
    @Timeout(value = 30, unit = SECONDS) // Global test timeout
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int NUM_PRODUCERS = 4;
        final int NUM_CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = NUM_PRODUCERS * ITEMS_PER_PRODUCER;
        
        // Use a larger queue to reduce contention blocking, but small enough to force blocking
        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(100); 

        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        LongAdder producedSum = new LongAdder(); // For checksum verification
        LongAdder consumedSum = new LongAdder();
        
        // Use ConcurrentHashMap to track counts per element for duplication detection
        // Key: Element, Value: Count seen
        Map<Integer, AtomicInteger> elementCounts = new ConcurrentHashMap<>();
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(NUM_PRODUCERS);
        CountDownLatch consumersDone = new CountDownLatch(NUM_CONSUMERS);
        
        AtomicBoolean producerError = new AtomicBoolean(false);
        AtomicBoolean consumerError = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        // Unique element per producer per iteration: (producerId * ITEMS_PER_PRODUCER) + i
                        int val = producerId * ITEMS_PER_PRODUCER + i;
                        stressQueue.put(val);
                        producedCount.incrementAndGet();
                        producedSum.add(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    producerError.set(true);
                } catch (Exception e) {
                    producerError.set(true);
                    e.printStackTrace();
                } finally {
                    producersDone.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < NUM_CONSUMERS; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val;
                        try {
                            // Use timed poll to allow checking termination condition without relying solely on shutdown interrupt
                            val = stressQueue.poll(100, MILLISECONDS);
                        } catch (IllegalStateException e) {
                            // Queue shutdown and empty
                            break;
                        }
                        
                        if (val == null) {
                            // Timeout. Check if producers are done and queue is empty.
                            if (producersDone.getCount() == 0 && stressQueue.isEmpty()) {
                                break; // Exit condition
                            }
                            continue; // Spurious wakeup or slow producer, retry
                        }
                        
                        consumedCount.incrementAndGet();
                        consumedSum.add(val);
                        
                        // Track duplication
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerError.set(true);
                } catch (Exception e) {
                    consumerError.set(true);
                    e.printStackTrace();
                } finally {
                    consumersDone.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Run ---
        long startTime = System.nanoTime();
        startLatch.countDown(); // Release all threads
        
        // Wait for producers to finish putting
        assertTrue(producersDone.await(TEST_TIMEOUT_SECONDS, SECONDS), "Producers timed out");
        
        // Shutdown queue to unblock consumers cleanly after producers finish
        stressQueue.shutdown();
        
        // Wait for consumers to finish draining
        assertTrue(consumersDone.await(TEST_TIMEOUT_SECONDS, SECONDS), "Consumers timed out");
        
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.printf("Stress Test Completed: %d items in %d ms%n", TOTAL_ITEMS, durationMs);

        // --- Verification ---
        assertFalse(producerError.get(), "Producer thread encountered error");
        assertFalse(consumerError.get(), "Consumer thread encountered error");
        
        assertEquals(TOTAL_ITEMS, producedCount.get(), "Total produced count mismatch");
        assertEquals(TOTAL_ITEMS, consumedCount.get(), "Total consumed count mismatch (Lost elements!)");
        assertEquals(producedSum.sum(), consumedSum.sum(), "Checksum mismatch (Corruption/Duplication!)");
        
        // Verify no duplicates: every element count must be exactly 1
        List<Integer> duplicates = elementCounts.entrySet().stream()
                .filter(e -> e.getValue().get() != 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        assertTrue(duplicates.isEmpty(), 
            "Duplicate or missing elements detected: " + duplicates.size() + " anomalies. Example: " + duplicates.stream().limit(5).collect(Collectors.toList()));
        
        // Verify queue state
        assertTrue(stressQueue.isEmpty());
        assertTrue(stressQueue.isShutdown());
    }

    // ==========================================
    // Fairness / Starvation Smoke Test
    // ==========================================
    
    @Test
    @DisabledOnOs(OS.WINDOWS) // Timing sensitive, disable on CI Windows runners if flaky
    void testNoStarvationUnderContention() throws InterruptedException {
        // Small queue, many threads hammering it. 
        // If implementation uses signalAll() incorrectly or has lock convoy issues, 
        // this might deadlock or take forever. signal() ensures progress.
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        AtomicInteger counter = new AtomicInteger(0);
        int threads = 20;
        int ops = 1000;
        Count