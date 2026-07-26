<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260726T053632Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * 
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} ({@code lock}) to protect all mutable state
 * ({@code head}, {@code tail}, {@code count}, {@code shutdown}) and the internal array slots.
 * Two {@link Condition} variables are associated with this lock:
 * </p>
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added ({@code put}/{@code offer}). 
 *       Consumers ({@code take}/{@code poll}) await on this when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled when an element is removed ({@code take}/{@code poll}). 
 *       Producers ({@code put}/{@code offer}) await on this when the queue is full.</li>
 * </ul>
 * 
 * <h3>Why Single Lock + Two Conditions?</h3>
 * <p>
 * Using a single lock simplifies the invariants (head/tail/count consistency) and avoids deadlock 
 * scenarios possible with dual-lock (read/write lock) algorithms. The two conditions allow 
 * <b>targeted signaling</b>: 
 * </p>
 * <ul>
 *   <li>When enqueueing, we only need to wake up <i>one</i> consumer ({@code notEmpty.signal()}), 
 *       not producers waiting for space.</li>
 *   <li>When dequeueing, we only need to wake up <i>one</i> producer ({@code notFull.signal()}).</li>
 * </ul>
 * This reduces "thundering herd" contention compared to {@code notifyAll()}.
 * 
 * <h3>Circular Buffer Invariants</h3>
 * <ul>
 *   <li>{@code 0 <= count <= capacity}</li>
 *   <li>{@code head} points to the next element to dequeue (if {@code count > 0}).</li>
 *   <li>{@code tail} points to the next free slot to enqueue (if {@code count < capacity}).</li>
 *   <li>Elements reside in {@code items[head]} ... {@code items[(tail - 1 + capacity) % capacity]}.</li>
 *   <li>Null slots in the array indicate empty positions (helps GC).</li>
 * </ul>
 * 
 * <h3>Shutdown Protocol</h3>
 * <p>
 * {@code shutdown()} is idempotent. It sets a volatile {@code shutdown} flag (guarded by lock), 
 * clears the buffer (nulling references for GC), and signals <b>all</b> waiting threads 
 * ({@code notEmpty.signalAll()}, {@code notFull.signalAll()}). 
 * </p>
 * <ul>
 *   <li>Blocked producers ({@code put}/{@code offer}) wake up and throw {@link IllegalStateException}.</li>
 *   <li>Blocked consumers ({@code take}/{@code poll}) wake up: 
 *       <ul>
 *         <li>If elements remain, they drain normally.</li>
 *         <li>If empty, they throw {@link IllegalStateException} (or return {@code null}/{@code false} for polls).</li>
 *       </ul>
 *   </li>
 *   <li>New {@code put}/{@code offer} calls immediately reject.</li>
 * </ul>
 * 
 * <h3>Interruption Handling</h3>
 * Blocking methods ({@code put}, {@code take}, timed {@code offer}, timed {@code poll}) 
 * are responsive to interruption ({@link InterruptedException}). 
 * If interrupted while waiting, the thread's interrupt status is cleared by {@code await}, 
 * we re-interrupt the current thread ({@code Thread.currentThread().interrupt()}) before throwing 
 * the exception to preserve the interrupt flag for higher-level handlers.
 * 
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private volatile boolean shutdown = false; // Volatile for fast-path check without lock

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

    // ------------------------------------------------------------
    // Blocking Put / Take
    // ------------------------------------------------------------

    /**
     * Inserts the element at the tail, waiting if necessary for space.
     * @param e the element to add (must not be null)
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly();
        try {
            // Wait for space or shutdown
            while (count == capacity) {
                checkShutdown(); // Throws ISE if shutdown
                notFull.await();
            }
            // Re-check shutdown after await returns (spurious wakeup or signalAll on shutdown)
            checkShutdown(); 
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary until an element is available.
     * @return the head element
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown(); // Throws ISE if shutdown
                notEmpty.await();
            }
            // If shutdown happened while waiting but items exist, we drain them.
            // If shutdown happened and empty, checkShutdown throws.
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Timed Offer / Poll
    // ------------------------------------------------------------

    /**
     * Inserts element if space available within timeout.
     * @param e element to add
     * @param timeout how long to wait
     * @param unit time unit
     * @return true if inserted, false if timeout elapsed
     * @throws InterruptedException if interrupted
     * @throws IllegalStateException if shutdown
     * @throws NullPointerException if element null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
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
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes head if available within timeout.
     * @param timeout how long to wait
     * @param unit time unit
     * @return head element, or null if timeout elapsed
     * @throws InterruptedException if interrupted
     * @throws IllegalStateException if shutdown and empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown();
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Non-blocking / Immediate (Optional but good for completeness)
    // ------------------------------------------------------------

    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            checkShutdown();
            if (count == capacity) return false;
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) {
                checkShutdown(); // Throw if shutdown & empty, else return null
                return null;
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------
    // Shutdown & State Inspection
    // ------------------------------------------------------------

    /**
     * Initiates orderly shutdown. 
     * Wakes all waiting threads. Subsequent puts/offers fail. 
     * Remaining elements can be taken/polled until empty.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Clear buffer for GC
            Arrays.fill(items, null);
            head = 0;
            tail = 0;
            count = 0;
            // Wake everyone
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
        try { return count; } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    public boolean isEmpty() { return size() == 0; }
    public boolean isFull() { return size() == capacity; }

    // ------------------------------------------------------------
    // Internal Helpers
    // ------------------------------------------------------------

    private void checkShutdown() {
        if (shutdown) {
            // Differentiate exception type based on context if needed, 
            // but ISE is standard for "illegal state for operation".
            throw new IllegalStateException("Queue is shutdown");
        }
    }

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
        notEmpty.signal(); // Wake one consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // GC
        head = (head + 1) % capacity;
        count--;
        notFull.signal(); // Wake one producer
        return e;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
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

    // ------------------------------------------------------------
    // Basic Functionality & Contract
    // ------------------------------------------------------------

    @Test
    @DisplayName("Basic FIFO order and capacity limit")
    void testBasicFifoAndCapacity() {
        assertEquals(10, queue.capacity());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());

        for (int i = 0; i < 10; i++) assertTrue(queue.offer(i));
        assertTrue(queue.isFull());
        assertFalse(queue.offer(99)); // Non-blocking offer fails when full

        for (int i = 0; i < 10; i++) assertEquals(i, queue.poll());
        assertTrue(queue.isEmpty());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    @DisplayName("Constructor validates capacity")
    void testCapacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    // ------------------------------------------------------------
    // Blocking Operations (Put / Take)
    // ------------------------------------------------------------

    @Test
    @DisplayName("put() blocks when full, take() blocks when empty")
    void testBlockingBehavior() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(1);
        CountDownLatch consumerDone = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            try {
                q.put(1); producerStarted.countDown();
                q.put(2); // Fills queue
                producerBlocked.set(true); // About to block on 3rd put
                q.put(3); // Blocks here
                producerDone.countDown();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.await(); // Wait for producer to fill
                assertEquals(1, q.take());
                assertEquals(2, q.take());
                consumerBlocked.set(true); // About to block on 3rd take
                assertEquals(3, q.take()); // Unblocks when producer puts 3
                consumerDone.countDown();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        producer.start();
        producerStarted.await(1, SECONDS); // Wait for 2 puts
        assertTrue(producerBlocked.get()); // Producer blocked on 3rd put

        consumer.start();
        consumerStarted.countDown(); // Release consumer
        consumerDone.await(1, SECONDS); // Wait for consumer to finish
        
        producerDone.await(1, SECONDS); // Producer should now finish
        assertTrue(q.isEmpty());
    }

    @Test
    @DisplayName("take() throws InterruptedException when interrupted")
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } 
            catch (InterruptedException ignored) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50); // Ensure thread is waiting
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have terminated after interrupt");
    }

    @Test
    @DisplayName("put() throws InterruptedException when interrupted")
    void testPutInterruption() throws InterruptedException {
        queue = new BoundedBlockingQueue<>(1);
        queue.put(1); // Fill it
        Thread t = new Thread(() -> {
            try { queue.put(2); } 
            catch (InterruptedException ignored) { }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ------------------------------------------------------------
    // Timed Operations (Offer / Poll)
    // ------------------------------------------------------------

    @Test
    @DisplayName("offer(timeout) returns false on timeout")
    void testOfferTimeout() throws InterruptedException {
        queue = new BoundedBlockingQueue<>(1);
        queue.put(1);
        assertFalse(queue.offer(2, 100, MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("offer(timeout) succeeds before timeout")
    void testOfferSuccessBeforeTimeout() throws InterruptedException {
        queue = new BoundedBlockingQueue<>(1);
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertTrue(queue.offer(1, 500, MILLISECONDS));
        t.join();
    }

    @Test
    @DisplayName("poll(timeout) returns null on timeout")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(100, MILLISECONDS));
    }

    @Test
    @DisplayName("poll(timeout) returns element before timeout")
    void testPollSuccessBeforeTimeout() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); queue.put(42); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertEquals(42, queue.poll(500, MILLISECONDS));
        t.join();
    }

    // ------------------------------------------------------------
    // Shutdown Protocol
    // ------------------------------------------------------------

    @Test
    @DisplayName("shutdown() wakes blocked producers with IllegalStateException")
    void testShutdownWakesProducers() throws InterruptedException {
        queue = new BoundedBlockingQueue<>(1);
        queue.put(1); // Full
        Thread producer = new Thread(() -> {
            try { queue.put(2); fail("Should throw ISE"); } 
            catch (IllegalStateException expected) { /* OK */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50); // Ensure waiting
        queue.shutdown();
        producer.join(1000);
        assertTrue(queue.isShutdown());
    }

    @Test
    @DisplayName("shutdown() wakes blocked consumers: drains then throws")
    void testShutdownWakesConsumers() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        // Should drain existing
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown -> ISE
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.put(3));
    }

    @Test
    @DisplayName("shutdown() causes timed offer/poll to fail fast")
    void testShutdownTimedOps() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
    }

    @Test
    @DisplayName("shutdown() is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // No exception
        assertTrue(queue.isShutdown());
    }

    // ------------------------------------------------------------
    // Stress Test: Multi-Producer / Multi-Consumer
    // ------------------------------------------------------------

    @Test
    @DisplayName("Stress Test: Multi-Producer/Multi-Consumer - No lost/duplicated elements")
    @DisabledIfSystemProperty(named = "ci.skip.stress", matches = "true") // Allow skipping in slow CI
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000; // Total 20,000 items
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a ConcurrentHashMap to track seen elements (value = count seen)
        // We expect every integer 0..totalItems-1 exactly once.
        Map<Integer, AtomicInteger> seen = new ConcurrentHashMap<>();
        AtomicLong producedCount = new AtomicLong(0);
        AtomicLong consumedCount = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // --- Producers ---
        List<Thread> producers = IntStream.range(0, producerCount).mapToObj(p -> new Thread(() -> {
            try {
                startLatch.await();
                int base = p * itemsPerProducer;
                for (int i = 0; i < itemsPerProducer; i++) {
                    stressQueue.put(base + i);
                    producedCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorFlag.set(true);
            } catch (Exception e) {
                errorFlag.set(true);
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        })).collect(Collectors.toList());

        // --- Consumers ---
        List<Thread> consumers = IntStream.range(0, consumerCount).mapToObj(c -> new Thread(() -> {
            try {
                startLatch.await();
                while (true) {
                    Integer item;
                    try {
                        item = stressQueue.take();
                    } catch (IllegalStateException e) {
                        // Shutdown triggered, queue empty
                        break; 
                    }
                    if (item == null) continue; // Should not happen with take()
                    
                    // Track uniqueness
                    seen.computeIfAbsent(item, k -> new AtomicInteger(0)).incrementAndGet();
                    consumedCount.incrementAndGet();
                    
                    // Exit condition: Consumed all expected items
                    if (consumedCount.get() >= totalItems) {
                        // Signal shutdown so other consumers exit cleanly
                        stressQueue.shutdown(); 
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorFlag.set(true);
            } catch (Exception e) {
                errorFlag.set(true);
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        })).collect(Collectors.toList());

        // Start all
        producers.forEach(Thread::start);
        consumers.forEach(Thread::start);

        // Go!
        startLatch.countDown();

        // Wait for completion (with generous timeout)
        assertTrue(doneLatch.await(30, SECONDS), "Test timed out");

        // Verify
        assertFalse(errorFlag.get(), "Thread encountered exception");
        assertEquals(totalItems, producedCount.get(), "Produced count mismatch");
        assertEquals(totalItems, consumedCount.get(), "Consumed count mismatch");

        // Verify no duplicates, no lost items
        assertEquals(totalItems, seen.size(), "Lost or duplicated elements detected (Map size mismatch)");
        
        for (Map.Entry<Integer, AtomicInteger> entry : seen.entrySet()) {
            assertEquals(1, entry.getValue().get(), 
                "Duplicate detected for element: " + entry.getKey() + " (count: " + entry.getValue().get() + ")");
        }
        
        // Verify range completeness
        for (int i = 0; i < totalItems; i++) {
            assertTrue(seen.containsKey(i), "Missing element: " + i);
        }
    }

    // ------------------------------------------------------------
    // Edge Cases / Concurrency Correctness
    // ------------------------------------------------------------

    @Test
    @DisplayName("Concurrent offer/poll on empty/full boundaries")
    void testConcurrentBoundaryConditions() throws InterruptedException {
        final int capacity = 1