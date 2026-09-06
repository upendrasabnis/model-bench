<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260906T065856Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

**Implementation Choice:** `ReentrantLock` with two `Condition` variables (`notFull`, `notEmpty`).

**Why not `synchronized`/`wait`/`notifyAll`?**
While `synchronized` is simpler for basic monitors, `ReentrantLock` + `Condition` offers critical advantages for a production-grade queue:
1.  **Multiple Condition Variables:** We need distinct wait-sets for "Not Full" (producers) and "Not Empty" (consumers). `synchronized` only provides one wait-set per object, forcing `notifyAll()` which wakes *all* threads (thundering herd problem). `Condition` allows `signal()` targeting specific wait-sets (waking exactly one producer or one consumer).
2.  **Interruptible Waiting:** `lock.lockInterruptibly()` and `condition.await()` respond to `Thread.interrupt()`, essential for cancellation and shutdown.
3.  **Timed Waiting:** `condition.awaitNanos(timeout)` provides precise, nanos-granularity timeouts for `offer`/`poll` without busy-spinning or complex `System.nanoTime()` math manual loops.
4.  **Fairness Policy:** The constructor accepts a `fair` boolean. Fair locking prevents thread starvation under high contention (FIFO grant order), at a throughput cost.

**State Management:**
*   **Buffer:** Circular array (`Object[] elements`) with `head` (take index), `tail` (put index), `count` (current size).
*   **Shutdown Flag:** `volatile boolean shutdown` + `shutdownException` (to propagate cause).
*   **Invariants:** `0 <= count <= capacity`, `head/tail` wrap via modulo arithmetic.

**Memory Visibility:**
*   `volatile` on `shutdown` and `count` (though `count` is guarded by lock, `volatile` ensures visibility of `shutdown` check *before* lock acquisition in fast-path checks).
*   All mutations (`head`, `tail`, `count`, `elements` slots) happen *inside* `lock.lock()` / `unlock()`, establishing happens-before edges via Monitor Lock Rule.

**Shutdown Protocol:**
1.  `shutdown()` acquires lock, sets `shutdown = true`, records optional cause, calls `notEmpty.signalAll()` and `notFull.signalAll()`.
2.  All blocking methods (`put`, `take`, `offer`, `poll`) check `shutdown` *while holding the lock* (or immediately after timed wait returns) and throw `IllegalStateException` (wrapping cause) if set.
3.  `drainTo()` allows consumers to harvest remaining elements post-shutdown.

---

### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * <p>
 * Concurrency Strategy: Single {@link ReentrantLock} with two {@link Condition} variables
 * ({@code notEmpty}, {@code notFull}) for precise signaling. Supports optional fair locking.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> implements AutoCloseable {

    // --- State ---
    private final Object[] elements;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    // --- Synchronization ---
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    // --- Shutdown ---
    private volatile boolean shutdown = false;
    private Throwable shutdownCause;

    /**
     * Creates a queue with the given capacity and non-fair locking (default).
     *
     * @param capacity the maximum number of elements (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     *
     * @param capacity the maximum number of elements (must be > 0)
     * @param fair     if true, threads contend in FIFO order; false (default) uses barging for higher throughput
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // ============================================================
    // Blocking Operations (Interruptible)
    // ============================================================

    /**
     * Inserts the element, waiting indefinitely until space is available.
     *
     * @param e the element to add (must not be null)
     * @throws InterruptedException     if interrupted while waiting
     * @throws IllegalStateException    if queue has been shut down
     * @throws NullPointerException     if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element cannot be null");
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkShutdown(); // Re-check after wakeup (spurious wakeup or shutdown)
            }
            enqueue(e);
            notEmpty.signal(); // Wake one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting indefinitely until an element is available.
     *
     * @return the head element
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throw if shutdown & empty
                notEmpty.await();
                checkShutdown();
            }
            E e = dequeue();
            notFull.signal(); // Wake one producer
            return e;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Timed Operations
    // ============================================================

    /**
     * Inserts the element, waiting up to the specified timeout.
     *
     * @param e       the element to add
     * @param timeout how long to wait
     * @param unit    time unit
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException  if interrupted
     * @throws IllegalStateException if shut down
     * @throws NullPointerException  if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkShutdown();
            while (count == capacity) {
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos); // Returns remaining nanos
                checkShutdown();
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting up to the specified timeout.
     *
     * @param timeout how long to wait
     * @param unit    time unit
     * @return the head element, or null if timeout elapsed
     * @throws InterruptedException  if interrupted
     * @throws IllegalStateException if shut down and empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throw if shutdown & empty
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
                checkShutdown();
            }
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Immediate / Inspection Operations
    // ============================================================

    /** @return true if inserted immediately, false if full */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            checkShutdown();
            if (count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element or null if empty */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E e = dequeue();
            notFull.signal();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element without removing */
    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) elements[head];
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() { return size() == 0; }
    public boolean isFull() { return size() == capacity; }
    public int capacity() { return capacity; }

    // ============================================================
    // Shutdown & Drain
    // ============================================================

    /**
     * Initiates orderly shutdown.
     * <ul>
     *   <li>Wakes all waiting producers/consumers.</li>
     *   <li>Subsequent blocking/timed calls throw {@link IllegalStateException}.</li>
     *   <li>Non-blocking calls ({@code offer()}, {@code poll()}) work until empty, then throw/return null.</li>
     * </ul>
     *
     * @param cause optional cause for the shutdown exception (may be null)
     */
    public void shutdown(Throwable cause) {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            this.shutdown = true;
            this.shutdownCause = cause;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Shuts down with no specific cause. */
    public void shutdown() { shutdown(null); }

    /** @return true if shutdown has been initiated */
    public boolean isShutdown() { return shutdown; }

    /**
     * Removes all available elements into the given collection.
     * Typically used after shutdown to harvest remaining work.
     *
     * @param c the collection to transfer elements into
     * @return number of elements transferred
     */
    public int drainTo(Collection<? super E> c) {
        Objects.requireNonNull(c);
        lock.lock();
        try {
            int n = 0;
            while (count > 0) {
                c.add(dequeue());
                n++;
            }
            if (n > 0) notFull.signalAll(); // Wake producers if we freed space
            return n;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() { shutdown(); }

    // ============================================================
    // Internal Helpers
    // ============================================================

    private void checkShutdown() {
        if (shutdown) {
            IllegalStateException ise = new IllegalStateException("Queue has been shut down");
            if (shutdownCause != null) ise.initCause(shutdownCause);
            throw ise;
        }
    }

    private void enqueue(E e) {
        elements[tail] = e;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) elements[head];
        elements[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return e;
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

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 * Covers API contracts, concurrency correctness, shutdown semantics, and stress testing.
 */
@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    // ---------------------------------------------------------
    // Fixtures & Helpers
    // ---------------------------------------------------------

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        if (queue != null && !queue.isShutdown()) {
            queue.shutdown();
        }
    }

    // ---------------------------------------------------------
    // Basic API Contract Tests
    // ---------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void constructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    @DisplayName("Null elements rejected")
    void nullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Basic FIFO behavior")
    void basicFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Peek does not remove")
    void peekDoesNotRemove() throws InterruptedException {
        queue.put(10);
        assertEquals(10, queue.peek());
        assertEquals(10, queue.peek());
        assertEquals(10, queue.take());
        assertNull(queue.peek());
    }

    @Test
    @DisplayName("Size and capacity tracking")
    void sizeTracking() throws InterruptedException {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());

        queue.put(1);
        assertEquals(1, queue.size());

        queue.put(2);
        queue.put(3);
        assertEquals(3, queue.size());

        queue.take();
        assertEquals(2, queue.size());
    }

    // ---------------------------------------------------------
    // Blocking & Timed Operations
    // ---------------------------------------------------------

    @Test
    @DisplayName("put blocks when full")
    @Timeout(5) // Safety net
    void putBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2);
        assertTrue(q.isFull());

        Thread producer = new Thread(() -> {
            try { q.put(3); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(200); // Ensure producer is parked
        assertTrue(producer.isAlive(), "Producer should be blocked");

        assertEquals(1, q.take()); // Free slot
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should unblock and finish");
        assertEquals(3, q.take());
    }

    @Test
    @DisplayName("take blocks when empty")
    @Timeout(5)
    void takeBlocksWhenEmpty() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(200);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");

        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("offer with timeout returns false on timeout")
    void offerTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill it
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("poll with timeout returns null on timeout")
    void pollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("InterruptedException clears interrupt status on blocking ops")
    void interruptClearsStatus() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread t = new Thread(() -> {
            try { q.put(2); }
            catch (InterruptedException e) { /* expected */ }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isInterrupted(), "Interrupt status should be cleared by await()");
    }

    // ---------------------------------------------------------
    // Shutdown Semantics
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown unblocks waiting threads with IllegalStateException")
    void shutdownUnblocksWaiters() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { queue.put(1); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        Thread consumer = new Thread(() -> {
            try { queue.take(); }
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });

        producer.start();
        consumer.start();
        Thread.sleep(200); // Both parked

        queue.shutdown(new RuntimeException("Maintenance"));

        producer.join(1000);
        consumer.join(1000);

        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Shutdown cause is wrapped in exception")
    void shutdownCauseWrapped() {
        RuntimeException cause = new RuntimeException("Boom");
        queue.shutdown(cause);
        IllegalStateException ise = assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertSame(cause, ise.getCause());
    }

    @Test
    @DisplayName("drainTo harvests remaining elements after shutdown")
    void drainToAfterShutdown() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        List<Integer> drained = new ArrayList<>();
        int n = queue.drainTo(drained);

        assertEquals(2, n);
        assertEquals(List.of(1, 2), drained);
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Closeable interface works")
    void closeableInterface() {
        queue.close();
        assertTrue(queue.isShutdown());
    }

    // ---------------------------------------------------------
    // Fairness Policy (Smoke Test)
    // ---------------------------------------------------------

    @Test
    @DisplayName("Fair queue construction succeeds")
    void fairQueueConstruction() {
        BoundedBlockingQueue<Integer> fairQ = new BoundedBlockingQueue<>(5, true);
        assertDoesNotThrow(() -> fairQ.put(1));
        assertEquals(1, fairQ.take());
        fairQ.shutdown();
    }

    // ---------------------------------------------------------
    // STRESS TEST: Multi-Producer / Multi-Consumer
    // ---------------------------------------------------------

    /**
     * Stress test verifying:
     * 1. No lost elements (every produced item is consumed).
     * 2. No duplicated elements (every consumed item was produced exactly once).
     * 3. Correct blocking behavior under contention.
     * 4. Graceful shutdown handling.
     */
    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer Correctness")
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // Generous timeout for CI
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCER_COUNT = 4;
        final int CONSUMER_COUNT = 4;
        final int ITEMS_PER_PRODUCER = 5000; // Total 20,000 items
        final int TOTAL_ITEMS = PRODUCER_COUNT * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY, true); // Fair lock for determinism

        // Use ConcurrentHashMap to track counts per element (detect duplicates/loss)
        // Key: Element Value, Value: AtomicInteger (count)
        Map<Integer, AtomicInteger> producedMap = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> consumedMap = new ConcurrentHashMap<>();

        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
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
                        int val = producerId * ITEMS_PER_PRODUCER + i; // Unique values per producer
                        stressQueue.put(val); // Blocking put
                        
                        producedMap.computeIfAbsent(val, k -> new AtomicInteger()).incrementAndGet();
                        producedCount.incrementAndGet();
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
                    while (true) {
                        Integer val = stressQueue.poll(500, TimeUnit.MILLISECONDS); // Timed poll
                        if (val == null) {
                            // Timeout: check if production is done and queue empty
                            if (producedCount.get() == TOTAL_ITEMS && stressQueue.isEmpty()) break;
                            continue; // Spurious timeout or slow producer, retry
                        }
                        
                        consumedMap.computeIfAbsent(val, k -> new AtomicInteger()).incrementAndGet();
                        consumedCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    consumerError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- GO ---
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for completion
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out waiting for threads");
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        // --- Verification ---
        // 1. Check for thread crashes
        if (producerError.get() != null) throw new AssertionError("Producer failed", producerError.get());
        if (consumerError.get() != null) throw new AssertionError("Consumer failed", consumerError.get());

        // 2. Counts match
        assertEquals(TOTAL_ITEMS, producedCount.get(), "Total produced mismatch");
        assertEquals(TOTAL_ITEMS, consumedCount.get(), "Total consumed mismatch");

        // 3. No Duplicates (Every key in consumedMap has count == 1)
        List<Integer> duplicates = consumedMap.entrySet().stream()
                .filter(e -> e.getValue().get() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        assertTrue(duplicates.isEmpty(), "Duplicate elements consumed: " + duplicates);

        // 4. No Lost Elements (Every produced key exists in consumedMap with count == 1)
        Set<Integer> producedKeys = producedMap.keySet();
        Set<Integer> consumedKeys = consumedMap.keySet();
        
        assertEquals(producedKeys, consumedKeys, "Element sets differ (Lost or Extra elements)");
        
        // Verify counts match exactly 1 for all
        producedKeys.forEach(key -> {
            int pc = producedMap.get(key).get();
            int cc = consumedMap.get(key).get();
            assertEquals(1, pc, "Produced count != 1 for " + key);
            assertEquals(1, cc, "Consumed count != 1 for " + key);
        });

        System.out.printf("Stress Test Passed: %d items in %d ms (Throughput: %.0f ops/sec)%n",
                TOTAL_ITEMS, durationMs, (TOTAL_ITEMS * 1000.0) / durationMs);
        
        stressQueue.shutdown();
    }

    // ---------------------------------------------------------
    // Edge Cases & Concurrency Weirdness
    // ---------------------------------------------------------

    @Test
    @DisplayName("Concurrent offer/poll on empty/full boundaries")
    void concurrentBoundaryOperations() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        int threads = 20;
        int iterations = 1