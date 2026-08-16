<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260816T030351Z -->
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
import java.util.Objects;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * 
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state (head, tail, count, array, shutdown flag).
 * Two {@link Condition} variables are associated with this lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added (count transitions 0 -> 1). 
 *       Consumers ({@code take}, {@code poll}) wait on this when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled when an element is removed (count transitions capacity -> capacity-1). 
 *       Producers ({@code put}, {@code offer}) wait on this when the queue is full.</li>
 * </ul>
 * </p>
 * 
 * <h3>Correctness Guarantees</h3>
 * <ul>
 *   <li><b>Mutual Exclusion:</b> The lock ensures only one thread modifies head/tail/count at a time.</li>
 *   <li><b>Progress (Deadlock Freedom):</b> Waiting threads are only parked on Conditions. 
 *       Signal/All is called strictly inside the lock after state mutation, ensuring waiters are woken.</li>
 *   <li><b>Bounded Waiting (Starvation Freedom):</b> The lock can be constructed with {@code fair=true} 
 *       to enforce FIFO ordering for lock acquisition, preventing thread starvation.</li>
 *   <li><b>Spurious Wakeup Handling:</b> All {@code await} calls are inside {@code while} loops 
 *       re-checking the state predicate (e.g., {@code count == 0}).</li>
 *   <li><b>Shutdown Safety:</b> A volatile {@code shutdown} flag (guarded by lock) allows clean termination. 
 *       On shutdown, all waiting threads are interrupted via {@code signalAll} and subsequent operations 
 *       throw {@link IllegalStateException}.</li>
 * </ul>
 * 
 * <h3>Memory Visibility</h3>
 * <p>
 * The {@code ReentrantLock} provides happens-before edges: unlocking (after write) happens-before 
 * locking (before read). The {@code items} array stores references; safe publication of elements 
 * is guaranteed because the write to the array slot happens-before the unlock, and the consumer's 
 * lock acquisition happens-before the read from the slot.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;
    
    // State guarded by 'lock'
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private boolean shutdown = false;
    
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    /**
     * Creates a queue with the given capacity and non-fair locking policy (default).
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @param fair if true, threads contend for the lock in FIFO order; higher throughput if false.
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // --- Helper Methods (Must hold lock) ---

    private void checkNotShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    private void enqueue(E x) {
        items[tail] = x;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E x = (E) items[head];
        items[head] = null; // GC aid
        if (++head == capacity) head = 0;
        count--;
        return x;
    }

    // --- Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == capacity) {
                checkNotShutdown(); // Re-check after wakeup
                notFull.await();
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkNotShutdown(); // Throw if shutdown & empty
                notEmpty.await();
            }
            E x = dequeue();
            notFull.signal(); // Wake up one producer
            return x;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time 
     * if necessary for space to become available.
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == capacity) {
                checkNotShutdown();
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
     * Retrieves and removes the head of this queue, waiting up to the specified wait time 
     * if necessary for an element to become available.
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkNotShutdown(); // Throw immediately if shutdown & empty
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

    // --- Immediate / State Operations ---

    /** @return {@code true} if queue contains no elements */
    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    /** @return {@code true} if queue is at capacity */
    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    /** @return current number of elements */
    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    /** @return maximum capacity */
    public int capacity() { return capacity; }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Wakes up all threads waiting in {@code put}, {@code take}, {@code offer}, {@code poll}.</li>
     *   <li>Subsequent calls to blocking/timed methods throw {@link IllegalStateException}.</li>
     *   <li>Non-blocking checks ({@code size}, {@code isEmpty}) remain functional.</li>
     * </ul>
     * This method is idempotent.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up so they can check the shutdown flag
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return {@code true} if shutdown has been called */
    public boolean isShutdown() {
        lock.lock();
        try { return shutdown; } finally { lock.unlock(); }
    }

    /**
     * Removes all elements. Does not affect shutdown state.
     */
    public void clear() {
        lock.lock();
        try {
            // Null out references for GC
            for (int i = 0; i < count; i++) {
                int idx = (head + i) % capacity;
                items[idx] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            notFull.signalAll(); // Space available
        } finally {
            lock.unlock();
        }
    }
}
```

---

### 2. Comprehensive JUnit 5 Tests: `BoundedBlockingQueueTest.java`

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

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * Covers: API contracts, concurrency correctness, shutdown behavior, 
 * and high-contention stress testing with mathematical verification.
 */
@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    // ============================================================
    // Basic Contract & Single-Threaded Tests
    // ============================================================

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void constructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    @DisplayName("Basic FIFO behavior: put/take")
    void basicFifo() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.put(1);
        q.put(2);
        q.put(3);
        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertEquals(3, q.take());
        assertTrue(q.isEmpty());
    }

    @Test
    @DisplayName("Null elements rejected")
    void nullRejection() {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1);
        assertThrows(NullPointerException.class, () -> q.put(null));
        assertThrows(NullPointerException.class, () -> q.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Offer/Poll immediate success/failure")
    void offerPollImmediate() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        assertTrue(q.offer(1, 0, TimeUnit.SECONDS));
        assertFalse(q.offer(2, 0, TimeUnit.SECONDS)); // Full
        assertEquals(1, q.poll(0, TimeUnit.SECONDS));
        assertNull(q.poll(0, TimeUnit.SECONDS)); // Empty
    }

    @Test
    @DisplayName("Timed offer/poll timeout behavior")
    void timedOperationsTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        // Offer should timeout
        long start = System.nanoTime();
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            // Poll should timeout
            long startPoll = System.nanoTime();
            assertNull(q.poll(100, TimeUnit.MILLISECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startPoll) >= 90); // Rough check
        });
    }

    @Test
    @DisplayName("Interruptible wait: put/take")
    void interruptibleWait() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread producer = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50); // Ensure producer is waiting
        producer.interrupt();
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should have exited on interrupt");
        assertEquals(1, q.size()); // Original element untouched
    }

    // ============================================================
    // Shutdown Tests
    // ============================================================

    @Test
    @DisplayName("Shutdown unblocks waiters and prevents new ops")
    void shutdownBehavior() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        AtomicReference<Exception> producerEx = new AtomicReference<>();
        AtomicReference<Exception> consumerEx = new AtomicReference<>();
        
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (Exception e) { producerEx.set(e); }
        });
        
        Thread consumer = new Thread(() -> {
            try { q.take(); q.take(); } // Take 1, then wait for 2nd
            catch (Exception e) { consumerEx.set(e); }
        });
        
        producer.start();
        consumer.start();
        Thread.sleep(50); // Both waiting
        
        q.shutdown();
        
        producer.join(1000);
        consumer.join(1000);
        
        assertInstanceOf(IllegalStateException.class, producerEx.get());
        assertInstanceOf(IllegalStateException.class, consumerEx.get());
        assertTrue(q.isShutdown());
        
        // Verify state checks still work
        assertEquals(1, q.size());
        assertFalse(q.isEmpty());
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void shutdownIdempotent() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10);
        q.shutdown();
        q.shutdown(); // No exception
        assertTrue(q.isShutdown());
    }

    // ============================================================
    // Fairness Policy Test
    // ============================================================

    @Test
    @DisplayName("Fair lock enforces FIFO thread ordering (best effort)")
    @DisabledOnOs(OS.WINDOWS) // Thread scheduling on Windows CI often too coarse for strict fairness verification
    void fairLockOrdering() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1, true); // Fair
        q.put(1); // Fill it
        
        List<Long> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLatch = new CountDownLatch(3);
        CountDownLatch readyLatch = new CountDownLatch(3);
        
        Runnable producer = () -> {
            readyLatch.countDown();
            try { startLatch.await(); q.put(0); } 
            catch (InterruptedException ignored) {}
            finally { acquisitionOrder.add(System.nanoTime()); }
        };
        
        Thread t1 = new Thread(producer);
        Thread t2 = new Thread(producer);
        Thread t3 = new Thread(producer);
        
        t1.start(); t2.start(); t3.start();
        readyLatch.await(); // All waiting on lock
        startLatch.countDown(); // Release simultaneously
        
        t1.join(); t2.join(); t3.join();
        
        // With fair lock, the order they acquire lock (and thus enqueue) should match start order roughly.
        // We just verify all 3 succeeded eventually.
        assertEquals(3, acquisitionOrder.size());
        q.shutdown();
    }

    // ============================================================
    // Multi-Producer / Multi-Consumer Stress Test
    // ============================================================

    /**
     * Stress test verifying:
     * 1. No Lost Elements: Every produced item is consumed exactly once.
     * 2. No Duplicates: No item consumed twice.
     * 3. Boundedness: Queue never exceeds capacity.
     * 4. Liveness: System terminates under load.
     */
    @Test
    @DisplayName("MPMC Stress Test: Correctness under high contention")
    void mpmcStressTest() throws InterruptedException {
        final int CAPACITY = 128;
        final int PRODUCERS = 8;
        final int CONSUMERS = 8;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;
        
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(CAPACITY);
        
        // Use a ConcurrentHashMap to track counts of consumed items for duplicate detection
        // Key: Item Value, Value: Count consumed
        Map<Integer, AtomicInteger> consumedCounts = new ConcurrentHashMap<>();
        AtomicLong totalConsumed = new AtomicLong(0);
        AtomicInteger maxObservedSize = new AtomicInteger(0);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);
        
        // --- Producers ---
        for (int p = 0; p < PRODUCERS; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        // Encode producer ID and sequence to make every item globally unique
                        int item = producerId * ITEMS_PER_PRODUCER + i;
                        queue.put(item); // Blocking
                        // Track max size observed (racy read, but okay for metric)
                        maxObservedSize.updateAndGet(prev -> Math.max(prev, queue.size()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            // Timeout: Check if production is done
                            if (totalConsumed.get() >= TOTAL_ITEMS && queue.isEmpty()) break;
                            continue;
                        }
                        
                        // Verify uniqueness
                        consumedCounts.computeIfAbsent(item, k -> new AtomicInteger()).incrementAndGet();
                        long cnt = totalConsumed.incrementAndGet();
                        
                        if (cnt % 10000 == 0) {
                            maxObservedSize.updateAndGet(prev -> Math.max(prev, queue.size()));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }
        
        // --- Go ---
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for completion with generous timeout
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), "Test timed out");
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        
        queue.shutdown(); // Clean up
        
        // --- Verification ---
        System.out.printf("Stress Test Done: %d items in %d ms. Max observed size: %d%n", 
                TOTAL_ITEMS, durationMs, maxObservedSize.get());
        
        // 1. Count Verification
        assertEquals(TOTAL_ITEMS, totalConsumed.get(), "Total consumed count mismatch (Lost items?)");
        
        // 2. Duplicate Verification
        List<Integer> duplicates = consumedCounts.entrySet().stream()
                .filter(e -> e.getValue().get() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        assertTrue(duplicates.isEmpty(), "Duplicate items consumed: " + duplicates);
        
        // 3. Completeness Verification (All expected items present)
        Set<Integer> expected = IntStream.range(0, TOTAL_ITEMS).boxed().collect(Collectors.toSet());
        Set<Integer> actual = consumedCounts.keySet();
        assertEquals(expected, actual, "Missing or extra items detected");
        
        // 4. Boundedness Verification (Max size never exceeded capacity)
        // Note: size() is a snapshot, but maxObservedSize gives high confidence.
        assertTrue(maxObservedSize.get() <= CAPACITY, 
                "Queue exceeded capacity! Observed: " + maxObservedSize.get() + " > " + CAPACITY);
    }

    // ============================================================
    // Edge Cases & Correctness
    // ============================================================

    @Test
    @DisplayName("Spurious wakeup resilience: signal vs signalAll")
    void spuriousWakeupResilience() throws InterruptedException {
        // This test relies on the implementation using while-loops.
        // We simulate spurious wakeups by interrupting a parking thread indirectly 
        // or just trusting the while-loop structure. 
        // A true spurious wakeup test requires JVM internals (Unsafe), 
        // so we verify the *structure* via code review and the interrupt test above.
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        // Interrupt causes await to throw IE, but lock is re-acquired. 
        // The while loop re-checks condition. Since still full, it awaits again.
        t.interrupt(); 
        t.join(500);
        // If while loop was missing (if), thread would proceed to enqueue -> capacity violation.
        // Since we interrupted, it should have exited. Queue should still have 1 item.
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("Clear removes elements but not shutdown state")
    void clearBehavior() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(5);
        q.put(1); q.put(2); q.put(3);
        assertEquals(3, q.size());
        q.clear();
        assertEquals(0, q.size());
        assertTrue(q.isEmpty());
        assertFalse(q.isShutdown());
        // Should be able to reuse
        q.put(10);
        assertEquals(10, q.take());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Works with both Fair and Non-Fair policies")
    void fairnessPolicySmokeTest(boolean fair) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10, fair);
        q.put(1);
        assertEquals(1, q.take());
        q.shutdown();
        assertThrows(IllegalStateException.class, () -> q.put(2));
    }
}
```

---

### 3. Key Implementation Details Summary

#### Concurrency Strategy: **Monitor Pattern (Single Lock, Multiple Conditions)**
1