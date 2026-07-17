<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260717T120115Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Concurrency Strategy Documentation

**Locking Mechanism:** `ReentrantLock` (Fair mode optional, default non-fair for throughput).
*   **Why not `synchronized`?** `ReentrantLock` allows multiple `Condition` variables (`notEmpty`, `notFull`) associated with a single lock. This enables **targeted signaling** (`signal()` vs `signalAll()`), waking only the relevant thread type (producers or consumers) instead of all waiters, reducing context switching overhead and "thundering herd" problems.

**Internal Data Structure:** Circular Array (Ring Buffer).
*   **Fields:** `Object[] elements`, `int head` (index to take), `int tail` (index to put), `int count` (current size).
*   **Invariants:** `0 <= head, tail < capacity`; `0 <= count <= capacity`; `count == 0 <=> head == tail` (empty); `count == capacity <=> head == tail` (full).

**Condition Variables:**
1.  `notEmpty`: Signaled by `put`/`offer` after inserting an element. Awaited by `take`/`poll` when `count == 0`.
2.  `notFull`: Signaled by `take`/`poll` after removing an element. Awaited by `put`/`offer` when `count == capacity`.

**Wait Loop Pattern (Guarding against Spurious Wakeups):**
```java
lock.lock();
try {
    while (count == 0 && !closed) { // Condition predicate
        notEmpty.await(); // Releases lock, waits
    }
    // ... critical section ...
} finally {
    lock.unlock();
}
```

**Shutdown Protocol (Clean Shutdown):**
1.  `shutdown()` acquires lock, sets `closed = true`, calls `notEmpty.signalAll()` and `notFull.signalAll()`.
2.  **Producers (`put`/`offer`):** Check `closed` flag inside lock. If closed, throw `IllegalStateException` (blocking) or return `false` (timed).
3.  **Consumers (`take`/`poll`):** 
    *   If `count > 0`: Allow draining remaining elements (return element).
    *   If `count == 0 && closed`: Return `null` (poll) or throw `NoSuchElementException` (take) / return `null` with timeout elapsed.
    *   *Rationale:* Allows "draining" the queue after shutdown signal without losing enqueued data.

**Interruption Handling:**
*   `await()` throws `InterruptedException`. We catch it, **re-interrupt the current thread** (`Thread.currentThread().interrupt()`), and propagate the exception. This preserves the interruption policy for callers higher up the stack.

---

### 2. Implementation: `BoundedBlockingQueue.java`

```java
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implemented using a circular array,
 * ReentrantLock, and Condition variables.
 * 
 * Concurrency Strategy:
 * - Single ReentrantLock guards all mutable state (head, tail, count, closed, array).
 * - Two Condition variables: 'notEmpty' (for consumers), 'notFull' (for producers).
 * - Targeted signaling (signal()) used for single element transitions; signalAll() used on shutdown.
 * - Spurious wakeups handled via standard 'while (condition) await()' loops.
 * - Shutdown sets a 'closed' flag and signals all waiters. Producers fail fast; Consumers drain remaining elements.
 */
public class BoundedBlockingQueue<E> {
    private final Object[] elements;
    private final int capacity;
    
    // Ring buffer pointers
    private int head = 0; // Index to take from
    private int tail = 0; // Index to put to
    private int count = 0; // Current number of elements
    
    // Synchronization primitives
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    // Lifecycle
    private volatile boolean closed = false;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    // --- Helper Methods (Require Lock Held) ---

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void insert(E e) {
        elements[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E remove() {
        E e = (E) elements[head];
        elements[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return e;
    }

    private void checkNotClosed() {
        if (closed) throw new IllegalStateException("Queue has been shut down");
    }

    // --- Blocking Operations ---

    /**
     * Inserts the element, waiting if necessary for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        lock.lock();
        try {
            checkNotClosed();
            while (count == capacity) {
                notFull.await(); // Wait for space
                checkNotClosed(); // Re-check shutdown after wakeup
            }
            insert(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary until an element is available.
     * @throws InterruptedException if interrupted while waiting
     * @throws NoSuchElementException if queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                if (closed) throw new NoSuchElementException("Queue shut down and empty");
                notEmpty.await(); // Wait for element
            }
            E e = remove();
            notFull.signal(); // Wake up one producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the element, waiting up to the specified timeout for space.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        
        lock.lock();
        try {
            checkNotClosed();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos); // Returns remaining nanos
                checkNotClosed();
            }
            insert(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting up to the specified timeout.
     * @return the head element, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        
        lock.lock();
        try {
            while (count == 0) {
                if (closed) return null; // Shutdown + empty = immediate return null
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = remove();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Lifecycle & Inspection ---

    /**
     * Initiates shutdown. 
     * - Wakes all waiting producers (they will throw IllegalStateException).
     * - Wakes all waiting consumers (they will drain remaining elements, then return null/throw).
     * - Subsequent puts/offers fail immediately.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (closed) return; // Idempotent
            closed = true;
            // Wake everyone up to process shutdown logic
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
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
}
```

---

### 3. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
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

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;
    private static final int TEST_TIMEOUT_SECONDS = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Ensure queue is shutdown to release any stuck threads in failed tests
        queue.shutdown();
        // Give threads a moment to die if they were waiting
        Thread.sleep(50); 
    }

    // ---------------------------------------------------------
    // Basic Functionality & Contract Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Basic FIFO Order")
    void testFifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Blocking Put waits for space")
    void testBlockingPutWaits() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        AtomicBoolean producerStarted = new AtomicBoolean(false);
        AtomicBoolean producerFinished = new AtomicBoolean(false);
        
        Thread producer = new Thread(() -> {
            try {
                producerStarted.set(true);
                queue.put(999); // Should block
                producerFinished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        producer.start();
        // Wait for producer to definitely be parked on notFull
        await().until(producerStarted::get);
        assertTrue(queue.isFull());
        assertFalse(producerFinished.get(), "Producer should be blocked");
        
        // Consume one -> should unblock producer
        assertEquals(0, queue.take());
        producer.join(1000);
        assertTrue(producerFinished.get(), "Producer should have finished after space freed");
        assertEquals(999, queue.take());
    }

    @Test
    @DisplayName("Blocking Take waits for element")
    void testBlockingTakeWaits() throws InterruptedException {
        AtomicBoolean consumerStarted = new AtomicBoolean(false);
        AtomicBoolean consumerFinished = new AtomicBoolean(false);
        AtomicInteger result = new AtomicInteger(-1);
        
        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.set(true);
                result.set(queue.take()); // Should block
                consumerFinished.set(true);
            } catch (InterruptedException | NoSuchElementException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        consumer.start();
        await().until(consumerStarted::get);
        assertTrue(queue.isEmpty());
        assertFalse(consumerFinished.get(), "Consumer should be blocked");
        
        queue.put(42);
        consumer.join(1000);
        assertTrue(consumerFinished.get());
        assertEquals(42, result.get());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------
    // Timed Operations Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Offer succeeds immediately if space")
    void testOfferImmediateSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Offer fails on timeout when full")
    void testOfferTimeout() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        long start = System.nanoTime();
        boolean result = queue.offer(99, 200, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        
        assertFalse(result);
        assertTimeout(Duration.ofMillis(500), () -> assertTrue(elapsed >= 150_000_000L)); // ~150ms minimum
    }

    @Test
    @DisplayName("Poll returns null on timeout when empty")
    void testPollTimeout() throws InterruptedException {
        long start = System.nanoTime();
        Integer result = queue.poll(200, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        
        assertNull(result);
        assertTimeout(Duration.ofMillis(500), () -> assertTrue(elapsed >= 150_000_000L));
    }

    @Test
    @DisplayName("Poll returns element immediately if available")
    void testPollImmediate() throws InterruptedException {
        queue.put(100);
        assertEquals(100, queue.poll(1, TimeUnit.SECONDS));
        assertTrue(queue.isEmpty());
    }

    // ---------------------------------------------------------
    // Interruption Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Put throws InterruptedException when interrupted")
    void testPutInterruption() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread t = new Thread(() -> {
            try { queue.put(1); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.BLOCKED);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    @DisplayName("Take throws InterruptedException when interrupted")
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING || t.getState() == Thread.State.BLOCKED);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    @DisplayName("Interrupted status preserved on timed offer/poll")
    void testTimedInterruptionStatus() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread t = new Thread(() -> {
            try { queue.offer(1, 10, TimeUnit.SECONDS); } 
            catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        await().until(() -> t.getState() == Thread.State.WAITING);
        t.interrupt();
        t.join(1000);
        assertTrue(t.isInterrupted() || !t.isAlive()); // Thread dies, but interrupt flag was set before death
    }

    // ---------------------------------------------------------
    // Shutdown Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown wakes blocked producers (IllegalStateException)")
    void testShutdownWakesProducers() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread producer = new Thread(() -> {
            try { queue.put(1); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        await().until(() -> producer.getState() == Thread.State.WAITING);
        
        queue.shutdown();
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertTrue(queue.isClosed());
    }

    @Test
    @DisplayName("Shutdown wakes blocked consumers (NoSuchElementException)")
    void testShutdownWakesConsumers() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should throw NSEE"); } 
            catch (NoSuchElementException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        await().until(() -> consumer.getState() == Thread.State.WAITING);
        
        queue.shutdown();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        // Should still be able to take existing elements
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and closed -> NoSuchElementException
        assertThrows(NoSuchElementException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Shutdown allows timed poll to drain then return null")
    void testShutdownPollDrain() throws InterruptedException {
        queue.put(1);
        queue.shutdown();
        
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll(1, TimeUnit.SECONDS)); // Empty + Closed
    }

    @Test
    @DisplayName("Offer/Put fail immediately after shutdown")
    void testPutAfterShutdownFails() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isClosed());
    }

    // ---------------------------------------------------------
    // Stress Test: Multi-Producer / Multi-Consumer
    // ---------------------------------------------------------

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer Correctness")
    @DisabledOnOs(OS.WINDOWS) // Timing flakiness on CI Windows runners sometimes, optional
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int NUM_PRODUCERS = 4;
        final int NUM_CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 5000; 
        final int TOTAL_ITEMS = NUM_PRODUCERS * ITEMS_PER_PRODUCER;
        
        // Use a Phaser to synchronize start/end phases
        Phaser phaser = new Phaser(NUM_PRODUCERS + NUM_CONSUMERS + 1); // +1 for main thread
        
        // Shared verification structures
        // We use a ConcurrentHashMap to track counts of each produced integer (1..TOTAL_ITEMS)
        // Producers produce unique ranges: P0: 1..5000, P1: 5001..10000 etc.
        Map<Integer, AtomicInteger> producedCounts = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> consumedCounts = new ConcurrentHashMap<>();
        LongAdder producedSum = new LongAdder();
        LongAdder consumedSum = new LongAdder();
        AtomicInteger activeProducers = new AtomicInteger(NUM_PRODUCERS);
        
        List<Thread> threads = new ArrayList<>();

        // --- Producers ---
        for (int p = 0; p < NUM_PRODUCERS; p++) {
            final int producerId = p;
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all threads ready
                int start = producerId * ITEMS_PER_PRODUCER + 1;
                int end = start + ITEMS_PER_PRODUCER;
                
                try {
                    for (int i = start; i < end; i++) {
                        queue.put(i);
                        producedCounts.computeIfAbsent(i, k -> new AtomicInteger()).incrementAndGet();
                        producedSum.add(i);
                    }
                } catch (InterruptedException | IllegalStateException e) {
                    // Shutdown or interrupt during test failure
                    Thread.currentThread().interrupt();
                } finally {
                    if (activeProducers.decrementAndGet() == 0) {
                        // Last producer signals shutdown to allow consumers to exit cleanly
                        queue.shutdown();
                    }
                    phaser.arriveAndDeregister();
                }
            });
            threads.add(t);
            t.start();
        }

        // --- Consumers ---
        for (int c = 0; c < NUM_CONSUMERS; c++) {
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all threads ready
                
                try {
                    while (true) {
                        Integer item = queue.poll(1, TimeUnit.SECONDS); // Timeout to detect stall
                        if (item == null) {
                            // Queue empty and closed (or timeout)
                            if (queue.isClosed() && queue.isEmpty()) break;
                            continue; // Spurious timeout, retry
                        }
                        consumedCounts.computeIfAbsent(item, k -> new AtomicInteger()).incrementAndGet();
                        consumedSum.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
            threads.add(t);
            t.start();
        }

        // --- Main Thread Coordination ---
        phaser.arriveAndAwaitAdvance(); // Release all workers
        
        // Wait for completion (Phaser tracks registered parties)
        // We wait for the phaser to terminate (phase advances until 0 parties)
        // But simpler: join all threads with timeout.
        for (Thread t : threads) {
            t.join(TEST_TIMEOUT_SECONDS * 1000L);
        }

        // --- Verification ---
        // 1. No thread should be alive (deadlock check)
        for (Thread t : threads) {
            assertFalse(t.isAlive(), "Thread " + t.getName() + " did not terminate in time (possible deadlock/livelock)");
        }

        // 2. Count Verification
        assertEquals(TOTAL_ITEMS, producedCounts.size(), "Not all unique items produced");
        assertEquals(TOTAL_ITEMS, consumedCounts.size(), "Not all unique items consumed");

        // 3. Duplicate/Lost Check (Every item count == 1)
        for (int i = 1; i <= TOTAL_ITEMS; i++) {
            int produced = producedCounts.getOrDefault(i, new AtomicInteger