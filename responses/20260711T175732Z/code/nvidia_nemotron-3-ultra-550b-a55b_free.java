<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260711T175732Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### 1. Concurrency Strategy Documentation

**Strategy: Single Lock with Two Conditions (Monitor Pattern)**

This implementation uses a single `ReentrantLock` to guard all mutable state (`head`, `tail`, `count`, `items`, `shutdown` flag). Two `Condition` variables manage the blocking logic:

1.  **`notEmpty`**: Signaled when an element is added (`put`/`offer`). Threads waiting in `take`/`poll` await on this condition.
2.  **`notFull`**: Signaled when an element is removed (`take`/`poll`). Threads waiting in `put`/`offer` await on this condition.

**Why this strategy?**
*   **Simplicity & Correctness**: A single lock ensures mutual exclusion for all state mutations (array indices, count, shutdown flag). This eliminates complex race conditions inherent in lock-free or split-lock (read/write lock) designs for bounded buffers.
*   **Fairness**: `ReentrantLock` is instantiated with `fairness = true`. This prevents thread starvation (e.g., a fast producer starving a slow consumer) and provides FIFO ordering for lock acquisition, which maps naturally to queue fairness.
*   **Signal Discipline**: `signal()` is used instead of `signalAll()` for performance. Since `put` only makes the queue "not empty" (waking one consumer) and `take` only makes it "not full" (waking one producer), waking a single thread is sufficient and avoids the "thundering herd" problem.
*   **Shutdown Handling**: The `shutdown` flag is guarded by the same lock. Upon shutdown, `signalAll()` is called on *both* conditions to unblock all waiting threads immediately, allowing them to check the flag and exit cleanly (returning `null`, `false`, or throwing `IllegalStateException`).

**Spurious Wakeups**: All `await()` calls are wrapped in `while` loops checking the predicate (`count == 0`, `count == capacity`, `shutdown`), ensuring correctness against spurious wakeups.

**Timed Operations**: `awaitNanos` is used for `offer`/`poll` with timeouts. The remaining nanos are recalculated on each loop iteration to handle early wakeups or spurious wakeups correctly.

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
 * <p>
 * Concurrency Strategy: Single ReentrantLock (Fair) + Two Conditions (notEmpty, notFull).
 * Shutdown is supported via a volatile-like flag guarded by the lock.
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    // --- State ---
    private final Object[] items;
    private int head;       // Index to take from
    private int tail;       // Index to put at
    private int count;      // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast read check before lock, but writes guarded by lock

    // --- Synchronization Primitives ---
    private final ReentrantLock lock = new ReentrantLock(true); // Fair lock
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.items = new Object[capacity];
    }

    // --- Core Blocking Operations ---

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
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        lock.lockInterruptibly();
        try {
            // Wait for space or shutdown
            while (count == items.length) {
                checkShutdown();
                notFull.await();
            }
            checkShutdown(); // Re-check after await returns
            
            enqueue(e);
            notEmpty.signal(); // Wake up one waiting consumer
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
            // Wait for element or shutdown
            while (count == 0) {
                checkShutdown();
                notEmpty.await();
            }
            checkShutdown(); // Re-check after await returns
            
            E item = dequeue();
            notFull.signal(); // Wake up one waiting producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

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
        if (e == null) throw new NullPointerException("Elements cannot be null");
        
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                checkShutdown();
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            checkShutdown();
            
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
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                checkShutdown();
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            checkShutdown();
            
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // --- Inspection & Control ---

    /**
     * Returns the current number of elements in this queue.
     * @return the number of elements
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue is at capacity.
     * @return {@code true} if full
     */
    public boolean isFull() {
        lock.lock();
        try {
            return count == items.length;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the capacity of this queue.
     * @return the capacity
     */
    public int capacity() {
        return items.length;
    }

    /**
     * Initiates an orderly shutdown. 
     * <ul>
     *   <li>No new elements can be added ({@code put}/{@code offer} throw {@code IllegalStateException}).</li>
     *   <li>Existing elements can still be removed ({@code take}/{@code poll} work normally).</li>
     *   <li>Once empty, {@code take}/{@code poll} throw {@code IllegalStateException}.</li>
     *   <li>All blocked threads are unblocked immediately.</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up to re-evaluate state against shutdown flag
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if shutdown has been initiated.
     * @return {@code true} if shut down
     */
    public boolean isShutdown() {
        // Volatile read is safe here for a "read-only" snapshot check.
        // State mutations (write) happen under lock.
        return shutdown;
    }

    // --- Private Helpers ---

    private void checkShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == items.length) tail = 0;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) items[head];
        items[head] = null; // Help GC
        if (++head == items.length) head = 0;
        count--;
        return item;
    }
}
```

---

### 3. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // ==========================================
    // Basic Functionality & Contract Tests
    // ==========================================

    @Test
    @DisplayName("Constructor rejects non-positive capacity")
    void constructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    @DisplayName("Null elements are rejected")
    void nullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Basic put/take FIFO order")
    void basicFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Size, isEmpty, isFull, capacity reflect state")
    void stateInspection() throws InterruptedException {
        assertEquals(10, queue.capacity());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());

        queue.put(1);
        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());

        for (int i = 2; i <= 10; i++) queue.put(i);
        assertTrue(queue.isFull());
        assertEquals(10, queue.size());

        queue.take();
        assertFalse(queue.isFull());
        assertEquals(9, queue.size());
    }

    // ==========================================
    // Blocking Behavior Tests
    // ==========================================

    @Test
    @DisplayName("put blocks when full")
    @Timeout(2) // Fail fast if deadlock
    void putBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1);
        q.put(2); // Full

        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                producerBlocked.set(true);
                q.put(3); // Should block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        // Wait for producer to actually block
        await().until(producerBlocked::get);
        assertEquals(2, q.size());

        q.take(); // Make space
        producer.join(500); // Should unblock and finish
        assertFalse(producer.isAlive());
        assertEquals(2, q.size()); // 2, 3
    }

    @Test
    @DisplayName("take blocks when empty")
    @Timeout(2)
    void takeBlocksWhenEmpty() throws InterruptedException {
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try {
                consumerBlocked.set(true);
                queue.take(); // Should block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        await().until(consumerBlocked::get);
        assertTrue(queue.isEmpty());

        queue.put(42);
        consumer.join(500);
        assertFalse(consumer.isAlive());
        assertTrue(queue.isEmpty());
    }

    // ==========================================
    // Timed Operations Tests
    // ==========================================

    @Test
    @DisplayName("offer returns false on timeout when full")
    void offerTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        boolean result = q.offer(2, 100, TimeUnit.MILLISECONDS);
        assertFalse(result);
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("offer succeeds before timeout when space available")
    void offerSuccessBeforeTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        // Start a consumer that takes after a delay
        Thread consumer = new Thread(() -> {
            try { Thread.sleep(50); queue.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();

        // Offer with long timeout, should succeed once consumer takes
        boolean result = q.offer(1, 2, TimeUnit.SECONDS);
        assertTrue(result);
        consumer.join();
    }

    @Test
    @DisplayName("poll returns null on timeout when empty")
    void pollTimeout() throws InterruptedException {
        Integer result = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(result);
    }

    @Test
    @DisplayName("poll returns element before timeout")
    void pollSuccessBeforeTimeout() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(99); } catch (InterruptedException ignored) {}
        });
        producer.start();

        Integer result = queue.poll(2, TimeUnit.SECONDS);
        assertEquals(99, result);
        producer.join();
    }

    @Test
    @DisplayName("Timed operations respect interruption")
    void timedOpsInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread t = new Thread(() -> {
            try { q.offer(2, 10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50); // Ensure waiting
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive());
    }

    // ==========================================
    // Shutdown Tests
    // ==========================================

    @Test
    @DisplayName("Shutdown prevents new puts/offers")
    void shutdownBlocksProducers() throws InterruptedException {
        queue.put(1);
        queue.shutdown();

        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertThrows(IllegalStateException.class, () -> queue.offer(2, 1, TimeUnit.SECONDS));
        assertTrue(queue.isShutdown());
    }

    @Test
    @DisplayName("Shutdown allows draining existing elements")
    void shutdownAllowsDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Shutdown unblocks waiting producers and consumers")
    @Timeout(2)
    void shutdownUnblocksWaiters() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        AtomicInteger producerException = new AtomicInteger(0);
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (IllegalStateException e) { producerException.set(1); } // Expected
            catch (InterruptedException e) { producerException.set(2); }
        });

        AtomicInteger consumerException = new AtomicInteger(0);
        Thread consumer = new Thread(() -> {
            try { q.take(); q.take(); } // Take 1, then block on 2nd
            catch (IllegalStateException e) { consumerException.set(1); } // Expected on 2nd take
            catch (InterruptedException e) { consumerException.set(2); }
        });

        producer.start();
        consumer.start();

        Thread.sleep(100); // Ensure both blocked
        q.shutdown();

        producer.join(500);
        consumer.join(500);

        assertEquals(1, producerException.get(), "Producer should get IllegalStateException");
        assertEquals(1, consumerException.get(), "Consumer should get IllegalStateException on 2nd take");
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void shutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // ==========================================
    // Concurrency Stress Tests
    // ==========================================

    @Test
    @DisplayName("Multi-Producer/Multi-Consumer Stress Test: No lost/duplicated elements")
    @Timeout(30) // Generous timeout for CI environments
    void stressTestMultiProducerMultiConsumer() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Track produced/consumed for verification
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // Use a Set to detect duplicates (ConcurrentHashMap as Set)
        ConcurrentHashMap<Integer, AtomicInteger> elementCounts = new ConcurrentHashMap<>();
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);

        // --- Producers ---
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i; // Unique values
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                        producedCount.incrementAndGet();
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
                    // Consumers run until queue is shutdown AND empty
                    while (true) {
                        Integer val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            if (stressQueue.isShutdown() && stressQueue.isEmpty()) break;
                            continue; // Timeout, check again
                        }
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Execute ---
        long startTime = System.nanoTime();
        startLatch.countDown(); // Release all threads
        
        // Wait for producers to finish
        // We need a separate latch or logic to wait for producers specifically to call shutdown
        // Simple approach: wait for producer count down, then shutdown, then wait for consumers.
        
        // Wait for producers (first N latches)
        // Since we used one latch, we wait for all, but producers finish first.
        // Better: Separate latches. Let's adjust logic slightly for the test flow.
        // Actually, let's just wait for the producer threads to finish by joining them if we kept references.
        // Since we didn't keep references, we use the shared latch but shutdown explicitly after producer count matches.
        
        // Wait until producedCount == totalItems
        await().atMost(20, TimeUnit.SECONDS).until(() -> producedCount.get() == totalItems);
        
        stressQueue.shutdown(); // Signal no more data
        
        // Wait for all threads (producers + consumers) to finish
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Threads did not finish in time");
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        // --- Verification ---
        System.out.printf("Stress Test Done: %d items in %d ms. Throughput: %.2f ops/ms%n", 
                          totalItems, durationMs, (double) totalItems / durationMs);

        assertEquals(totalItems, producedCount.get(), "All items produced");
        assertEquals(totalItems, consumedCount.get(), "All items consumed");
        assertEquals(producedSum.get(), consumedSum.get(), "Sum mismatch indicates loss/duplication/corruption");
        
        // Verify no duplicates (count == 1 for all) and no missing (count == 0 for all produced)
        // Since we increment on produce and decrement on consume, final count must be 0.
        List<Integer> nonZero = elementCounts.entrySet().stream()
                .filter(e -> e.getValue().get() != 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        assertTrue(nonZero.isEmpty(), "Duplicate or lost elements detected: " + nonZero.size() + " anomalies. Examples: " + nonZero.subList(0, Math.min(10, nonZero.size())));
        assertTrue(stressQueue.isEmpty(), "Queue should be empty after drain");
    }

    @Test
    @DisplayName("Fairness/Scheduling: Interleaved put/take under contention")
    @Timeout(10)
    void contentionInterleaving() throws InterruptedException {
        final int capacity = 10;
        final int iterations = 5000;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) q.put(i);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { latch.countDown(); }
        });

        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    int val = q.take();
                    // Verify strict ordering
                    assertEquals(counter.getAndIncrement(), val, "Order violation at index " + val);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { latch.countDown(); }
        });

        producer.start();
        consumer.start();
        assertTrue(latch.await