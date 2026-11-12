<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261112T014719Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

**Implementation Approach:** `ReentrantLock` with two `Condition` variables (`notFull`, `notEmpty`).

**Why not `synchronized`/`wait`/`notify`?**
While simpler for basic cases, `ReentrantLock` + `Condition` provides:
1.  **Multiple Condition Variables:** We can signal *specifically* waiting producers (`notFull`) or consumers (`notEmpty`). `notifyAll()` on a monitor wakes everyone, causing "thundering herd" contention.
2.  **Interruptible Lock Acquisition:** `lockInterruptibly()` allows threads blocked on `put`/`take` to respond to `Thread.interrupt()`, crucial for cancellation.
3.  **Fairness Policy:** Optional fair locking prevents thread starvation.
4.  **Timed Waits:** `condition.awaitNanos()` offers precise timeout control for `offer`/`poll` without manual time-math loops required by `wait(timeout)`.

**State & Invariants:**
*   **Backing Store:** Circular array `E[] items`.
*   **Pointers:** `putIndex`, `takeIndex` (modulo `capacity`).
*   **Count:** `count` (current size). *Invariant:* `0 <= count <= capacity`.
*   **Shutdown Flag:** `volatile boolean shutdown`.

**Locking Protocol:**
All public mutating methods (`put`, `take`, `offer`, `poll`, `shutdown`) acquire `lock` at start and release in `finally` block.
*   **Put/Offer:** Wait on `notFull` while `count == capacity && !shutdown`.
*   **Take/Poll:** Wait on `notEmpty` while `count == 0 && !shutdown`.
*   **Shutdown:** Sets `shutdown = true`, signals **ALL** conditions (`notFull.signalAll()`, `notEmpty.signalAll()`) to unblock waiters immediately.

**Shutdown Semantics:**
1.  `shutdown()` is idempotent and thread-safe.
2.  After shutdown: `put`/`offer` throw `IllegalStateException` (or return `false` for `offer`).
3.  After shutdown: `take`/`poll` drain remaining elements normally. Once empty, they return `null` (poll) or throw `IllegalStateException` (take) instead of blocking indefinitely.

---

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * 
 * Concurrency Strategy:
 * - Uses a single {@link ReentrantLock} for mutual exclusion of all state mutations.
 * - Uses two {@link Condition} variables ({@code notEmpty}, {@code notFull}) for efficient
 *   signaling between producers and consumers, avoiding "thundering herd" issues inherent in
 *   {@code notifyAll()} on a monitor lock.
 * - Supports interruptible blocking operations ({@code put}, {@code take}) via {@code lockInterruptibly()}.
 * - Implements a "poison pill" style shutdown via a volatile flag + signalAll, allowing blocked
 *   threads to wake up and exit cleanly.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> implements AutoCloseable {

    // --- State ---
    private final Object[] items; // Use Object[] to avoid generic array creation issues
    private final int capacity;
    
    private int putIndex = 0;
    private int takeIndex = 0;
    private int count = 0;
    
    // Volatile ensures visibility of shutdown flag across threads without locking
    private volatile boolean shutdown = false;

    // --- Synchronization Primitives ---
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    /**
     * Creates a queue with the given capacity.
     * @param capacity the maximum number of elements (must be > 0)
     * @param fair if true, uses fair locking policy (FIFO thread access), higher throughput contention cost.
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    public BoundedBlockingQueue(int capacity) {
        this(capacity, false); // Default non-fair for higher throughput
    }

    // --- Core Helpers ---

    private void enqueue(E x) {
        items[putIndex] = x;
        putIndex = (putIndex + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[takeIndex];
        items[takeIndex] = null; // Help GC
        takeIndex = (takeIndex + 1) % capacity;
        count--;
        return x;
    }

    private void checkNotShutdown(String op) {
        if (shutdown) {
            throw new IllegalStateException("Queue is shutdown, cannot " + op);
        }
    }

    // --- Blocking API ---

    /**
     * Inserts element, waiting if necessary for space.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        lock.lockInterruptibly(); // Allows thread interruption during lock acquisition
        try {
            checkNotShutdown("put");
            while (count == capacity && !shutdown) {
                notFull.await(); // Releases lock, waits for signal
            }
            checkNotShutdown("put"); // Re-check after wakeup (spurious wakeup or shutdown)
            enqueue(e);
            notEmpty.signal(); // Wake up ONE waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head, waiting if necessary for an element.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown AND empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (count == 0 && shutdown) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            E x = dequeue();
            notFull.signal(); // Wake up ONE waiting producer
            return x;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed API ---

    /**
     * Inserts element if space available within timeout.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            checkNotShutdown("offer");
            while (count == capacity && !shutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Precise timed wait
            }
            checkNotShutdown("offer");
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head if available within timeout.
     * @return element, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown AND empty (optional design choice, here returns null)
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (count == 0) {
                // Either timeout or shutdown+empty
                return null; 
            }
            E x = dequeue();
            notFull.signal();
            return x;
        } finally {
            lock.unlock();
        }
    }

    // --- Immediate / Non-blocking API ---

    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
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

    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E x = dequeue();
            notFull.signal();
            return x;
        } finally {
            lock.unlock();
        }
    }

    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) items[takeIndex];
        } finally {
            lock.unlock();
        }
    }

    // --- State Inspection ---

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() { return size() == 0; }
    public boolean isFull() { return size() == capacity; }
    public boolean isShutdown() { return shutdown; }

    // --- Shutdown & Lifecycle ---

    /**
     * Initiates orderly shutdown.
     * 1. Sets shutdown flag.
     * 2. Unblocks ALL waiting producers and consumers.
     * 3. Producers fail fast; Consumers drain remaining elements then fail/return null.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Signal ALL to wake up every thread blocked in put/take/offer/poll
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    // --- Bulk / Utility (Optional but useful) ---

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null) throw new NullPointerException();
        if (c == this) throw new IllegalArgumentException("Cannot drain to self");
        
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            for (int i = 0; i < n; i++) {
                c.add(dequeue());
            }
            if (n > 0) notFull.signalAll(); // Wake producers if we freed space
            return n;
        } finally {
            lock.unlock();
        }
    }
    
    public void forEach(Consumer<? super E> action) {
        if (action == null) throw new NullPointerException();
        lock.lock();
        try {
            for (int i = 0, idx = takeIndex; i < count; i++, idx = (idx + 1) % capacity) {
                action.accept((E) items[idx]);
            }
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

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * Covers: Functional correctness, Blocking behavior, Timeouts, Shutdown, Concurrency/Stress.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(); // Ensure clean state for next test
    }

    // ==========================================
    // 1. Basic Functional Correctness (Single Thread)
    // ==========================================

    @Test
    void testInitialState() {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());
        assertEquals(CAPACITY, queue.remainingCapacity());
        assertEquals(CAPACITY, queue.capacity());
        assertNull(queue.peek());
        assertNull(queue.poll());
    }

    @Test
    void testOfferPollFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(2, queue.size());
        
        assertEquals(1, queue.peek()); // Peek doesn't remove
        assertEquals(2, queue.size());
        
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testCapacityLimit() {
        for (int i = 0; i < CAPACITY; i++) assertTrue(queue.offer(i));
        assertTrue(queue.isFull());
        assertFalse(queue.offer(999)); // Immediate offer fails
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.put(null));
    }

    // ==========================================
    // 2. Blocking Behavior (put/take)
    // ==========================================

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());

        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                producerBlocked.set(true);
                q.put(3); // Should block
            } catch (InterruptedException ignored) {}
        });
        producer.start();
        
        // Wait for producer to block
        await().untilTrue(producerBlocked);
        assertEquals(2, q.size()); // Producer hasn't enqueued yet
        
        q.take(); // Make space
        producer.join(1000); // Producer should finish now
        assertFalse(producer.isAlive());
        assertEquals(2, q.size()); // 1 remaining + 1 added
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        
        Thread consumer = new Thread(() -> {
            try {
                consumerBlocked.set(true);
                q.take(); // Should block
            } catch (InterruptedException ignored) {}
        });
        consumer.start();
        
        await().untilTrue(consumerBlocked);
        assertTrue(q.isEmpty());
        
        q.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
        assertTrue(q.isEmpty());
    }

    @Test
    void testInterruptiblePut() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.BLOCKED);
        
        t.interrupt();
        assertDoesNotThrow(() -> t.join(1000));
        assertFalse(t.isAlive());
    }

    @Test
    void testInterruptibleTake() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        Thread t = new Thread(() -> {
            try { q.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.BLOCKED);
        
        t.interrupt();
        assertDoesNotThrow(() -> t.join(1000));
        assertFalse(t.isAlive());
    }

    // ==========================================
    // 3. Timed Operations (offer/poll with timeout)
    // ==========================================

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread releaser = new Thread(() -> {
            sleep(50);
            q.take();
        });
        releaser.start();
        
        // Should succeed because space becomes available within 200ms
        assertTrue(q.offer(2, 200, MILLISECONDS));
        assertEquals(1, q.size()); // 2 is in queue
        releaser.join();
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full, no one taking
        
        // Should fail after 50ms
        assertFalse(q.offer(2, 50, MILLISECONDS));
        assertEquals(1, q.size()); // Still 1
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        Thread producer = new Thread(() -> {
            sleep(50);
            q.put(99);
        });
        producer.start();
        
        Integer val = q.poll(200, MILLISECONDS);
        assertEquals(99, val);
        producer.join();
    }

    @Test
    void testPollTimeoutFailure() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertNull(q.poll(50, MILLISECONDS));
    }

    // ==========================================
    // 4. Shutdown Semantics
    // ==========================================

    @Test
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread producer = new Thread(() -> {
            try { q.put(2); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not interrupt"); }
        });
        producer.start();
        await().until(() -> producer.getState() == Thread.State.WAITING);
        
        q.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertTrue(q.isShutdown());
    }

    @Test
    void testShutdownUnblocksConsumers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        Thread consumer = new Thread(() -> {
            try { q.take(); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not interrupt"); }
        });
        consumer.start();
        await().until(() -> consumer.getState() == Thread.State.WAITING);
        
        q.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    void testShutdownDrainsRemainingElements() {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        // Should still be able to take existing elements
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown -> Exception
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll()); // poll returns null on empty shutdown
        assertFalse(queue.offer(3)); // offer returns false
        assertThrows(IllegalStateException.class, () -> queue.put(3));
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    @Test
    void testCloseable() {
        try (BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1)) {
            q.put(1);
        } // auto-close calls shutdown
        // No assertion needed, just verifying no exception
    }

    // ==========================================
    // 5. Multi-Producer / Multi-Consumer Stress Test
    // ==========================================

    @Test
    @Timeout(value = 30, unit = SECONDS) // Safety net for CI
    void testStressMultiProducerMultiConsumer_NoLostNoDuplicates() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a Set to detect duplicates (ConcurrentHashMap backed)
        Set<Integer> producedSet = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> consumedSet = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Globally unique
                        stressQueue.put(val); // Blocking put
                        producedSet.add(val);
                        producedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.poll(100, MILLISECONDS); // Timed poll
                        if (val == null) {
                            // Timeout: check if production finished and queue empty
                            if (producedCount.get() == totalItems && stressQueue.isEmpty()) break;
                            continue; // Spurious timeout, keep polling
                        }
                        // Verify uniqueness
                        if (!consumedSet.add(val)) {
                            fail("DUPLICATE DETECTED: " + val);
                            errorFlag.set(true);
                            break;
                        }
                        consumedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go ---
        long start = System.nanoTime();
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(doneLatch.await(20, SECONDS), "Test timed out");
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // --- Verification ---
        assertFalse(errorFlag.get(), "Thread error occurred");
        assertEquals(totalItems, producedCount.get(), "Not all items produced");
        assertEquals(totalItems, consumedCount.get(), "Not all items consumed");
        assertEquals(totalItems, producedSet.size(), "Producer duplicates detected");
        assertEquals(totalItems, consumedSet.size(), "Consumer duplicates detected");
        assertEquals(producedSet, consumedSet, "Lost elements detected (Set mismatch)");
        assertTrue(stressQueue.isEmpty(), "Queue not empty at end");
        
        System.out.printf("Stress Test Passed: %d items in %d ms (%.2f ops/ms)%n", 
            totalItems, durationMs, (double)totalItems / durationMs);
    }

    // ==========================================
    // 6. Edge Cases & Fairness
    // ==========================================

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testFairnessPolicyDoesNotDeadlock(boolean fair) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10, fair);
        int threads = 20;
        CountDownLatch latch = new CountDownLatch(threads);
        
        for (int i = 0; i < threads; i++) {
            final int val = i;
            new Thread(() ->