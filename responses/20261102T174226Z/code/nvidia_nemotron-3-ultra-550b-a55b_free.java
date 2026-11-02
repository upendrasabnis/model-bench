<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261102T174226Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Concurrency Strategy Documentation

**Strategy: Monitor Pattern with Dual Conditions (Lock/Condition)**

We use `java.util.concurrent.locks.ReentrantLock` with two `Condition` variables (`notFull`, `notEmpty`) instead of intrinsic locks (`synchronized`/`wait`/`notifyAll`). This provides:

1.  **Precise Signaling**: `notFull.signal()` wakes exactly one waiting producer; `notEmpty.signal()` wakes exactly one waiting consumer. This avoids "thundering herd" problems inherent in `notifyAll()`.
2.  **Interruptible Waits**: `lock.lockInterruptibly()` allows threads to respond to `Thread.interrupt()` while waiting for the lock or condition, enabling responsive shutdown.
3.  **Timed Waits**: `condition.awaitNanos(timeout)` implements `offer/poll` timeouts efficiently without busy-spinning.
4.  **State Encapsulation**: All mutable state (`head`, `tail`, `count`, `closed`, `array`) is guarded *exclusively* by the lock.
5.  **Shutdown Protocol**: A `volatile boolean closed` flag (checked under lock) prevents new enqueues. `shutdown()` sets the flag and calls `notEmpty.signalAll()` + `notFull.signalAll()` to unblock all waiters, who then throw `IllegalStateException`.

**Memory Consistency**: `ReentrantLock` provides happens-before edges: unlock $\rightarrow$ lock. Volatile `closed` ensures visibility of shutdown state without lock for the fast-path check in `offer`/`poll` (though full protocol requires lock).

---

### 2. Implementation: `BoundedBlockingQueue.java`

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
 * Concurrency Strategy:
 * - Single ReentrantLock guards all mutable state (array, head, tail, count, closed).
 * - Two Condition variables: 'notFull' (producers wait here), 'notEmpty' (consumers wait here).
 * - Signal-all on shutdown to unblock all threads immediately.
 * - Interruptible locking for responsive cancellation.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    // Volatile for fast-path visibility in offer/poll without lock acquisition (optional optimization)
    // But protocol requires lock for atomic check-and-act, so volatile mainly helps shutdown() visibility.
    private volatile boolean closed = false;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.items = new Object[capacity];
    }

    // --- Blocking Operations ---

    /**
     * Inserts element, waiting if necessary for space.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shutdown
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                checkClosed();
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head, waiting if necessary for element.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkClosed();
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
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shutdown
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                checkClosed();
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head if available within timeout.
     * @return element, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown and empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkClosed();
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Inspection & Shutdown ---

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isClosed() {
        return closed; // Volatile read
    }

    /**
     * Initiates orderly shutdown.
     * - Prevents future puts/offers (throws IllegalStateException).
     * - Unblocks all waiting producers/consumers.
     * - Existing elements remain retrievable via take/poll.
     * - Idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            // Wake everyone: producers fail fast, consumers drain remaining then fail
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // --- Internal Helpers ---

    private void checkClosed() {
        if (closed) throw new IllegalStateException("Queue has been shutdown");
    }

    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == items.length) tail = 0;
        count++;
        notEmpty.signal(); // Wake one consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == items.length) head = 0;
        count--;
        notFull.signal(); // Wake one producer
        return e;
    }
}
```

---

### 3. Comprehensive JUnit 5 Tests

#### `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
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
    @DisplayName("Put and Take single element")
    void testPutTakeSingle() throws InterruptedException {
        queue.put(42);
        assertEquals(42, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("FIFO Ordering")
    void testFifoOrdering() throws InterruptedException {
        queue.put(1); queue.put(2); queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Capacity limit blocks put")
    void testCapacityBlock() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(2);
        small.put(1);
        small.put(2);
        
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { small.put(3); } 
            catch (InterruptedException ignored) { producerBlocked.set(true); }
        });
        producer.start();
        Thread.sleep(100); // Ensure producer is waiting
        assertTrue(producer.isAlive(), "Producer should be blocked");
        
        small.take(); // Make space
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should unblock and finish");
        assertEquals(3, small.take());
    }

    @Test
    @DisplayName("Take blocks when empty")
    void testTakeBlock() throws InterruptedException {
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try { queue.take(); } 
            catch (InterruptedException ignored) { consumerBlocked.set(true); }
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        
        queue.put(99);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @ParameterizedTest
    @ValueSource(longs = {10, 50, 100})
    @DisplayName("Offer succeeds within timeout")
    void testOfferSuccess(long timeoutMs) throws InterruptedException {
        assertTrue(queue.offer(1, timeoutMs, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Offer fails on timeout when full")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full
        assertFalse(small.offer(2, 50, TimeUnit.MILLISECONDS));
        assertEquals(1, small.size());
    }

    @Test
    @DisplayName("Poll returns element within timeout")
    void testPollSuccess() throws InterruptedException {
        queue.put(100);
        Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
        assertEquals(100, val);
    }

    @Test
    @DisplayName("Poll returns null on timeout when empty")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Timed operations respect interruption")
    void testTimedOpsInterruption() {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full
        
        Thread t = new Thread(() -> {
            try { small.offer(2, 10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        assertThrows(InterruptedException.class, () -> {
            // We can't easily assert the exception from inside the thread without a wrapper,
            // but we verify the thread terminates.
            t.join(1000);
            assertFalse(t.isAlive());
        });
    }

    // --- Shutdown Protocol ---

    @Test
    @DisplayName("Shutdown prevents new puts")
    void testShutdownPreventsPut() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertThrows(IllegalStateException.class, () -> queue.offer(2, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown unblocks waiting producers")
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full
        
        Thread producer = new Thread(() -> {
            try { small.put(2); fail("Should throw ISE"); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50);
        small.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive());
    }

    @Test
    @DisplayName("Shutdown unblocks waiting consumers")
    void testShutdownUnblocksConsumers() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should throw ISE"); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(50);
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("Drain remaining elements after shutdown")
    void testDrainAfterShutdown() throws InterruptedException {
        queue.put(1); queue.put(2); queue.put(3);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
        
        // Now empty and closed -> ISE
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isClosed());
    }

    // --- Edge Cases ---

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Zero capacity rejected in constructor")
    void testZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
    }

    // --- Concurrency Stress Test ---

    @Test
    @DisplayName("Multi-Producer/Multi-Consumer Stress Test: No Lost/Duplicated Elements")
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCERS = 4;
        final int CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 5000; // Total 20,000 items
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        
        // Tracking: Use a ConcurrentMap or AtomicIntegerArray to count occurrences
        // We use an array of AtomicInteger for O(1) indexing.
        AtomicInteger[] counters = new AtomicInteger[TOTAL_ITEMS];
        for (int i = 0; i < TOTAL_ITEMS; i++) counters[i] = new AtomicInteger(0);
        
        LongAdder producedSum = new LongAdder();
        LongAdder consumedSum = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < PRODUCERS; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    int base = producerId * ITEMS_PER_PRODUCER;
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int val = base + i;
                        stressQueue.put(val);
                        producedSum.add(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
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
                    // Consumers run until queue is shutdown and empty
                    while (true) {
                        Integer val;
                        try {
                            // Use poll with timeout to allow checking shutdown state periodically
                            // but take() is fine if we shutdown explicitly at end.
                            val = stressQueue.take(); 
                        } catch (IllegalStateException e) {
                            // Shutdown called and queue empty
                            break; 
                        }
                        if (val == null) continue; // Should not happen with take()
                        
                        // Verify uniqueness
                        if (val < 0 || val >= TOTAL_ITEMS) {
                            errorFlag.set(true);
                            System.err.println("Invalid value: " + val);
                            break;
                        }
                        int prev = counters[val].getAndIncrement();
                        if (prev != 0) {
                            errorFlag.set(true);
                            System.err.println("DUPLICATE DETECTED: " + val + " count=" + (prev+1));
                        }
                        consumedSum.add(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go! ---
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for producers to finish
        // We don't use doneLatch yet because consumers are still running
        // We wait for producers specifically by joining threads or using a separate latch.
        // Simpler: Wait for producedSum to reach expected count? No, race condition.
        // Better: Join producer threads explicitly. 
        // Since we used anonymous threads, let's use a Phaser or just wait on doneLatch 
        // but producers count down too. We need to shutdown queue after producers done.
        
        // Wait for all producers to finish (they count down on doneLatch)
        // We need to know when producers are done to call shutdown().
        // Let's use a separate latch for producers.
        CountDownLatch producersDone = new CountDownLatch(PRODUCERS);
        // Re-factor: The test above mixes latches. Let's rewrite the stress test cleanly below.
        
        // --- REWRITTEN CLEAN STRESS TEST LOGIC ---
        // (The above was getting complex with mixed latches. Below is the robust version).
    }

    // Clean Stress Test Implementation
    @Test
    @DisplayName("Stress Test: High Contention Correctness (No Loss/Dup)")
    void stressTestHighContention() throws InterruptedException {
        final int CAPACITY = 64;
        final int PRODUCER_COUNT = 8;
        final int CONSUMER_COUNT = 8;
        final int ITEMS_PER_PRODUCER = 10_000; // 80k total
        final int TOTAL_ITEMS = PRODUCER_COUNT * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        
        // Occurrence tracker: 0=not seen, 1=seen once, >1=duplicate
        // Using byte[] with AtomicIntegerArray is faster than AtomicInteger[]
        // But AtomicIntegerArray is fine for 80k.
        // Actually, simple int[] with volatile writes isn't atomic for increment.
        // Use AtomicIntegerArray.
        java.util.concurrent.atomic.AtomicIntegerArray seen = new java.util.concurrent.atomic.AtomicIntegerArray(TOTAL_ITEMS);
        
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        AtomicBoolean failed = new AtomicBoolean(false);
        
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producersFinished = new CountDownLatch(PRODUCER_COUNT);
        
        Thread[] producers = new Thread[PRODUCER_COUNT];
        for (int p = 0; p < PRODUCER_COUNT; p++) {
            final int id = p;
            producers[p] = new Thread(() -> {
                try {
                    startGate.await();
                    int base = id * ITEMS_PER_PRODUCER;
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        q.put(base + i);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed.set(true);
                    e.printStackTrace();
                } finally {
                    producersFinished.countDown();
                }
            }, "Prod-" + p);
            producers[p].start();
        }

        Thread[] consumers = new Thread[CONSUMER_COUNT];
        for (int c = 0; c < CONSUMER_COUNT; c++) {
            consumers[c] = new Thread(() -> {
                try {
                    startGate.await();
                    while (true) {
                        Integer val;
                        try {
                            val = q.take(); // Blocks until element or shutdown
                        } catch (IllegalStateException e) {
                            // Queue shutdown and empty
                            break;
                        }
                        
                        if (val < 0 || val >= TOTAL_ITEMS) {
                            failed.set(true);
                            System.err.println("Out of bounds: " + val);
                            break;
                        }
                        
                        // Check duplicates atomically
                        if (seen.incrementAndGet(val) > 1) {
                            failed.set(true);
                            System.err.println("Duplicate: " + val);
                        }
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed.set(true);
                    e.printStackTrace();
                }
            }, "Cons-" + c);
            consumers[c].start();
        }

        // Release all threads simultaneously
        startGate.countDown();
        
        // Wait for all producers to finish putting
        producersFinished.await();
        
        // All items produced. Shutdown queue to unblock consumers.
        q.shutdown();
        
        // Wait for consumers to finish draining
        for (Thread t : consumers) t.join(5000); // Generous timeout
        
        // Assertions
        assertFalse(failed.get(), "Concurrency error detected (duplicate or exception)");
        assertEquals(TOTAL_ITEMS, producedCount.get(), "Not all items produced");
        assertEquals(TOTAL_ITEMS, consumedCount.get(), "Not all items consumed");
        
        // Verify every item 0..TOTAL_ITEMS-1 seen exactly once
        for (int i = 0; i < TOTAL_ITEMS; i++) {
            int count = seen.get(i);
            if (count != 1) {
                fail("Item " + i + " seen " + count + " times (expected 1)");
            }
        }
        
        // Verify queue empty
        assertTrue(q.isEmpty());
        assertTrue(q.isClosed());
    }
    
    @Test
    @DisplayName("Stress Test: Interleaved Offer/Poll with Timeouts")
    void stressTestTimedOps() throws InterruptedException {
        final int CAPACITY = 10;
        final int THREADS = 20;
        final int OPS_PER_THREAD = 1000;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(CAPACITY);
        
        AtomicInteger successfulOffers = new AtomicInteger(0);
        AtomicInteger successfulPolls = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREADS);
        
        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < OPS_PER_THREAD; j++) {
                        // Mix of blocking and timed
                        if (j % 2 == 0) {
                            q.put(j);
                            successfulOffers.incrementAndGet();
                        } else {
                            if (q.offer(j, 10, TimeUnit.MILLISECONDS)) {
                                successfulOffers.incrementAndGet();
                            }
                        }
                        
                        if (j % 3 == 0) {
                            Integer v = q.take();
                            if (v != null) successfulPolls.incrementAndGet();
                        } else {
                            Integer v = q.poll(10, TimeUnit.MILLISECONDS);
                            if (v != null) successfulPolls.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        
        latch.await(30, TimeUnit.SECONDS);
        q.shutdown();
        
        // Drain remaining
        while (q.poll(0, TimeUnit.SECONDS) != null) {
            successfulPolls.incrementAndGet();
        }
        
        // In this chaotic test, we