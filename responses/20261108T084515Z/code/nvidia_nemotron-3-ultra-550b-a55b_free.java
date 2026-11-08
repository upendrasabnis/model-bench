<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261108T084515Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 *
 * <h2>Concurrency Strategy</h2>
 * <p>
 * This implementation uses a single {@link ReentrantLock} ("monitor lock") to guard all mutable state
 * ({@code head}, {@code tail}, {@code count}, {@code shutdown}, and the backing array).
 * This ensures mutual exclusion for all state transitions.
 * </p>
 * <p>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled when an element is added (enqueued). Waiting consumers ({@code take}, {@code poll})
 *       await on this condition when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled when an element is removed (dequeued). Waiting producers ({@code put}, {@code offer})
 *       await on this condition when the queue is full.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Wait/Notify Protocol:</b> All waiting loops use the standard <code>while (condition) await()</code> pattern
 * to handle spurious wakeups. Signaling uses {@code signal()} (not {@code signalAll()}) for efficiency,
 * as only one thread (producer or consumer) can typically proceed per state change (one slot freed/filled).
 * </p>
 * <p>
 * <b>Shutdown Semantics:</b>
 * <ul>
 *   <li>{@code shutdown()} acquires the lock, sets a volatile {@code shutdown} flag, and calls {@code signalAll()}
 *       on both conditions to unblock all waiting threads immediately.</li>
 *   <li>After shutdown, {@code put}/{@code offer} throw {@link IllegalStateException}.</li>
 *   <li>{@code take}/{@code poll} allow draining remaining elements. Once empty and shutdown, they throw
 *       {@link IllegalStateException} (or return null/false for timed variants).</li>
 * </ul>
 * </p>
 * <p>
 * <b>Interruption Handling:</b> Blocking methods ({@code put}, {@code take}, timed {@code offer}, {@code poll})
 * throw {@link InterruptedException} if the thread is interrupted while waiting. The interrupt status is cleared
 * by the {@code await} call; this implementation does not explicitly re-interrupt the thread (standard JDK behavior
 * for {@link java.util.concurrent.BlockingQueue}), but the exception propagates to the caller.
 * </p>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock(); // Non-fair by default for throughput
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
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Internal Helpers ---

    private void checkNotShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    private void enqueue(E x) {
        // Precondition: lock held, !isFull()
        items[tail] = x;
        if (++tail == capacity) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        // Precondition: lock held, !isEmpty()
        E x = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        return x;
    }

    // --- Public API ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkNotShutdown(); // Re-check shutdown after wakeup
            }
            enqueue(e);
            notEmpty.signal(); // Wake up one waiting consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue is shut down and empty
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
            notFull.signal(); // Wake up one waiting producer
            return x;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time
     * for space to become available.
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if the waiting time elapsed before space was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdown();
            while (count == capacity) {
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
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available.
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the waiting time elapsed before an element was available
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Shutdown + empty = immediate return null
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
     * Inserts the specified element at the tail of this queue if it is possible to do so immediately
     * without exceeding the queue's capacity, returning {@code true} upon success and {@code false}
     * if this queue is full.
     * @param e the element to add
     * @return {@code true} if the element was added, {@code false} otherwise
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException("Elements cannot be null");
        lock.lock();
        try {
            checkNotShutdown();
            if (count == capacity) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is empty.
     * @return the head of this queue, or {@code null} if this queue is empty
     * @throws IllegalStateException if the queue is shut down and empty
     */
    public E poll() {
        lock.lock();
        try {
            if (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                return null;
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
     * Subsequent {@code put}/{@code offer} calls will throw {@link IllegalStateException}.
     * Consumers can still {@code take}/{@code poll} remaining elements until empty,
     * after which they will throw {@link IllegalStateException} (blocking) or return null/false (non-blocking/timed).
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up to process shutdown logic
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link BoundedBlockingQueue}.
 */
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // --- Basic Functionality ---

    @Test
    void testBasicFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testCapacityLimit() {
        assertEquals(10, queue.capacity());
        assertTrue(queue.offer(1));
        // Fill it
        for (int i = 2; i <= 10; i++) assertTrue(queue.offer(i));
        assertFalse(queue.offer(11)); // Full
        assertThrows(IllegalStateException.class, () -> queue.put(11)); // Put blocks, but offer returns false
    }

    @Test
    void testPollEmpty() {
        assertNull(queue.poll());
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testNullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    // --- Blocking Behavior ---

    @Test
    @Timeout(2) // Fail fast if deadlock
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fill
        Thread producer = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(100); // Ensure producer is waiting
        assertTrue(producer.isAlive(), "Producer should be blocked");
        assertEquals(1, q.take()); // Consume
        producer.join(500); // Producer should finish
        assertFalse(producer.isAlive());
        assertEquals(2, q.take());
    }

    @Test
    @Timeout(2)
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        queue.put(42);
        consumer.join(500);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        releaser.start();
        assertTrue(q.offer(2, 200, TimeUnit.MILLISECONDS));
        assertEquals(2, q.take());
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full, no one consuming
        assertFalse(q.offer(2, 50, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); q.put(99); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Integer val = q.poll(200, TimeUnit.MILLISECONDS);
        assertEquals(99, val);
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    // --- Shutdown Semantics ---

    @Test
    void testShutdownUnblocksProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread producer = new Thread(() -> {
            try { q.put(2); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive());
        q.shutdown();
        producer.join(500);
        assertFalse(producer.isAlive());
    }

    @Test
    void testShutdownUnblocksConsumers() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); fail("Should throw ISE"); } 
            catch (IllegalStateException e) { /* Expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer.start();
        Thread.sleep(50);
        assertTrue(consumer.isAlive());
        queue.shutdown();
        consumer.join(500);
        assertFalse(consumer.isAlive());
    }

    @Test
    void testShutdownAllowsDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    void testShutdownRejectsNewPuts() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // --- Interruption ---

    @Test
    void testInterruptionOnPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive());
    }

    @Test
    void testInterruptionOnTake() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { /* Expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive());
    }

    // --- Stress Test: Multi-Producer / Multi-Consumer ---

    @Test
    @Timeout(30) // Generous timeout for CI environments
    void testMultiProducerMultiConsumerNoLostOrDuplicatedElements() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 10_000; // Total 40,000 items
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use a thread-safe collector for results
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>(totalItems));
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicBoolean producersDone = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersLatch = new CountDownLatch(numProducers);
        CountDownLatch consumersLatch = new CountDownLatch(numConsumers);

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to start simultaneously
                    for (int i = 0; i < itemsPerProducer; i++) {
                        // Generate unique value: producerId * itemsPerProducer + i
                        int val = producerId * itemsPerProducer + i;
                        stressQueue.put(val);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // Timeout: check if producers are done and queue is empty
                            if (producersDone.get() && stressQueue.isEmpty()) break;
                            continue; // Spurious timeout or slow producer, keep polling
                        }
                        consumed.add(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Go ---
        startLatch.countDown();

        // Wait for producers to finish
        assertTrue(producersLatch.await(10, TimeUnit.SECONDS), "Producers timed out");
        producersDone.set(true); // Signal consumers to exit when queue drains
        
        // Wait for consumers to finish draining
        assertTrue(consumersLatch.await(10, TimeUnit.SECONDS), "Consumers timed out");

        // --- Verification ---
        assertEquals(totalItems, consumed.size(), "Total consumed count mismatch (Lost elements?)");
        
        // Check for duplicates using a Set
        Set<Integer> uniqueConsumed = new HashSet<>(consumed);
        assertEquals(totalItems, uniqueConsumed.size(), "Duplicate elements detected!");

        // Check for missing elements (Correctness of values)
        // Expected values: 0 to totalItems - 1
        List<Integer> sorted = consumed.stream().sorted().collect(Collectors.toList());
        for (int i = 0; i < totalItems; i++) {
            assertEquals(i, sorted.get(i), "Missing or incorrect element at index " + i);
        }
    }
    
    // --- Edge Case: Concurrent Shutdown During Operations ---
    
    @Test
    @Timeout(5)
    void testConcurrentShutdownAndOperations() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(20);

        // Mix of producers, consumers, and a shutdown thread
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                try { for(int k=0;k<100;k++) q.put(k); } 
                catch (IllegalStateException | InterruptedException ignored) {} 
                finally { latch.countDown(); }
            }).start();
            
            new Thread(() -> {
                try { for(int k=0;k<100;k++) q.take(); } 
                catch (IllegalStateException | InterruptedException ignored) {} 
                finally { latch.countDown(); }
            }).start();
        }
        
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            q.shutdown();
            latch.countDown();
        }).start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // If we reach here without deadlock or unhandled exception, test passes.
        // State consistency: queue should be shutdown.
        assertTrue(q.isShutdown());
    }
}
```

---

### 3. Key Design Decisions & Testing Strategy Summary

#### Concurrency Strategy Details
1.  **Single Lock (`ReentrantLock`)**: Simplifies reasoning about invariants (`head`, `tail`, `count` consistency). `ReentrantLock` allows `lockInterruptibly()`, which is crucial for responding to `Thread.interrupt()` during blocking operations (standard `synchronized`/`wait` does not support interruption during wait).
2.  **Two Conditions (`notEmpty`, `notFull`)**: Decouples producer waiting from consumer waiting. `signal()` (not `signalAll()`) is used for throughput; only one thread can actually proceed when a single slot becomes available/filled.
3.  **Volatile `shutdown` flag**: Allows a fast-path check in `poll()`/`offer()` without locking, though the lock is still required for the critical section. The lock *must* be held when setting the flag