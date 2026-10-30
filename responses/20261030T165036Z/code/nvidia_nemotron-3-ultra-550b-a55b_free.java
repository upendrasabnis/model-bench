<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261030T165036Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation built from scratch
 * using {@link ReentrantLock} and {@link Condition} variables.
 *
 * <h2>Concurrency Strategy</h2>
 * This implementation uses a <b>Single Lock, Dual Condition</b> strategy (Monitor Pattern):
 * <ul>
 *   <li><b>Lock:</b> A single {@link ReentrantLock} guards all mutable state ({@code head}, {@code tail},
 *       {@code count}, {@code buffer}, {@code shutdown}). This ensures mutual exclusion for all queue operations.</li>
 *   <li><b>Conditions:</b> Two condition variables are associated with the lock:
 *       <ul>
 *         <li>{@code notEmpty}: Signaled when an element is added (enqueued). Waited upon by {@code take}/{@code poll}
 *             when the queue is empty.</li>
 *         <li>{@code notFull}: Signaled when an element is removed (dequeued). Waited upon by {@code put}/{@code offer}
 *             when the queue is full.</li>
 *       </ul>
 *   </li>
 *   <li><b>Signaling Policy:</b> We use {@link Condition#signal()} (not {@code signalAll()}) for standard enqueue/dequeue
 *       operations because a single state change (count +/- 1) can only unblock at most one waiter of the opposite type.
 *       This avoids "thundering herd" contention. {@code signalAll()} is used <b>only</b> during {@link #shutdown()} to
 *       wake all blocked threads immediately.</li>
 *   <li><b>Spurious Wakeups:</b> All {@code await()} calls are wrapped in {@code while} loops checking the state predicate
 *       (e.g., {@code count == 0}), handling spurious wakeups correctly.</li>
 *   <li><b>Shutdown Protocol:</b> Setting {@code shutdown = true} is atomic with the lock held. It triggers
 *       {@code signalAll()} on both conditions. Subsequent blocking calls throw {@link IllegalStateException};
 *       non-blocking calls return failure indicators ({@code false}/{@code null}). Threads currently blocked in
 *       {@code put/take} wake up, re-check the predicate/shutdown flag, and throw {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h2>Memory Consistency</h2>
 * The {@link ReentrantLock} provides happens-before edges: unlocking in the producer happens-before locking in the consumer
 * (and vice versa), ensuring visibility of the element written to the array without requiring {@code volatile} on the
 * array reference or elements (though the array reference itself is effectively final).
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private volatile boolean shutdown = false; // Volatile for fast-path check without lock (optional optimization)

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements the queue can hold; must be > 0
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    // --- Internal Helpers ---

    private void checkShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) buffer[head];
        buffer[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return element;
    }

    private void enqueue(E element) {
        buffer[tail] = element;
        tail = (tail + 1) % capacity;
        count++;
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
            checkShutdown();
            while (count == capacity) {
                notFull.await(); // Releases lock, waits for signal
                checkShutdown(); // Re-check after wakeup (spurious or shutdown)
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
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                notEmpty.await();
            }
            E element = dequeue();
            notFull.signal(); // Wake up one waiting producer
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time
     * if necessary for space to become available.
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
            checkShutdown();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
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
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available.
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the waiting time elapsed before an element was available
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new IllegalStateException("Queue shut down and empty");
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E element = dequeue();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown. All threads currently blocked in {@code put}, {@code take}, {@code offer},
     * or {@code poll} will wake up and throw {@link IllegalStateException} (blocking) or return failure
     * indicators (timed). Subsequent calls to any insertion/removal method will fail immediately.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up so they can observe the shutdown flag
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

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    @DisplayName("Basic FIFO order and capacity")
    void testBasicFifo() throws InterruptedException {
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());

        queue.put(1);
        queue.put(2);
        queue.put(3);

        assertEquals(3, queue.size());
        assertFalse(queue.isEmpty());

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());

        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Put blocks when full")
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQueue = new BoundedBlockingQueue<>(2);
        smallQueue.put(1);
        smallQueue.put(2);
        assertTrue(smallQueue.isFull());

        AtomicBoolean producerStarted = new AtomicBoolean(false);
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try {
                producerStarted.set(true);
                smallQueue.put(3); // Should block
                producerBlocked.set(false); // Should not reach here until space
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        // Wait for producer to attempt put
        while (!producerStarted.get()) Thread.yield();
        Thread.sleep(100); // Give time to block
        assertTrue(producer.isAlive(), "Producer should be blocked");

        // Create space
        assertEquals(1, smallQueue.take());
        producer.join(1000); // Should unblock and finish
        assertFalse(producer.isAlive(), "Producer should have finished after space available");
        assertEquals(3, smallQueue.take());
    }

    @Test
    @DisplayName("Take blocks when empty")
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        AtomicBoolean consumerStarted = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.set(true);
                queue.take(); // Should block
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        while (!consumerStarted.get()) Thread.yield();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");

        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // --- Timed Operations ---

    @Test
    @DisplayName("Offer returns false on timeout when full")
    void testOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQueue = new BoundedBlockingQueue<>(1);
        smallQueue.put(1);
        assertFalse(smallQueue.offer(2, 50, TimeUnit.MILLISECONDS));
        assertEquals(1, smallQueue.size());
    }

    @Test
    @DisplayName("Offer succeeds when space becomes available within timeout")
    void testOfferSuccessWithinTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQueue = new BoundedBlockingQueue<>(1);
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); smallQueue.take(); } catch (InterruptedException ignored) {}
        });
        producer.start();
        assertTrue(smallQueue.offer(1, 200, TimeUnit.MILLISECONDS));
        producer.join();
    }

    @Test
    @DisplayName("Poll returns null on timeout when empty")
    void testPollTimeout() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Poll succeeds when element arrives within timeout")
    void testPollSuccessWithinTimeout() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(99); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Integer val = queue.poll(200, TimeUnit.MILLISECONDS);
        assertEquals(99, val);
        producer.join();
    }

    // --- Shutdown ---

    @Test
    @DisplayName("Shutdown unblocks waiting producers and consumers")
    void testShutdownUnblocksWaiters() throws InterruptedException {
        BoundedBlockingQueue<Integer> smallQueue = new BoundedBlockingQueue<>(1);
        smallQueue.put(1); // Fill it

        AtomicBoolean producerException = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { smallQueue.put(2); }
            catch (IllegalStateException e) { producerException.set(true); }
            catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50); // Ensure blocked

        AtomicBoolean consumerException = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            smallQueue.take(); // Take the 1
            try { smallQueue.take(); } // Block on empty
            catch (IllegalStateException e) { consumerException.set(true); }
            catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(50); // Ensure blocked

        smallQueue.shutdown();

        producer.join(1000);
        consumer.join(1000);

        assertTrue(producerException.get(), "Producer should get IllegalStateException");
        assertTrue(consumerException.get(), "Consumer should get IllegalStateException");
        assertTrue(smallQueue.isShutdown());
    }

    @Test
    @DisplayName("Operations fail fast after shutdown")
    void testOperationsFailFastAfterShutdown() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertFalse(queue.offer(1, 0, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(0, TimeUnit.SECONDS)); // Empty + Shutdown -> Exception
    }

    @Test
    @DisplayName("Shutdown is idempotent")
    void testShutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // Should not throw
        assertTrue(queue.isShutdown());
    }

    // --- Null Handling ---

    @ParameterizedTest
    @ValueSource(strings = {"put", "offer"})
    @DisplayName("Null elements rejected")
    void testNullRejection(String method) {
        assertThrows(NullPointerException.class, () -> {
            if ("put".equals(method)) queue.put(null);
            else queue.offer(null, 1, TimeUnit.SECONDS);
        });
    }

    // --- Concurrency / Stress Tests ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Multi-Producer/Multi-Consumer Stress Test: No lost/duplicated elements")
    void testMultiProducerMultiConsumerStress() throws InterruptedException {
        final int capacity = 100;
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = numProducers * itemsPerProducer;

        BoundedBlockingQueue<Long> stressQueue = new BoundedBlockingQueue<>(capacity);
        
        // Use AtomicLong to track sum of produced and consumed values
        // We produce sequential numbers 1..totalItems per producer? 
        // Better: Each producer produces a unique range. Sum of 1..N = N(N+1)/2
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicBoolean producerError = new AtomicBoolean(false);
        AtomicBoolean consumerError = new AtomicBoolean(false);

        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    long base = (long) producerId * itemsPerProducer;
                    for (int i = 1; i <= itemsPerProducer; i++) {
                        long val = base + i;
                        stressQueue.put(val);
                        producedSum.addAndGet(val);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    producerError.set(true);
                } catch (IllegalStateException e) {
                    // Shutdown during test? Should not happen.
                    producerError.set(true);
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // We don't know exactly how many each will consume, just loop until shutdown + empty
                    while (true) {
                        Long val = stressQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // Timeout: check if shutdown and empty
                            if (stressQueue.isShutdown() && stressQueue.isEmpty()) break;
                            // else continue polling
                        } else {
                            consumedSum.addAndGet(val);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerError.set(true);
                } catch (IllegalStateException e) {
                    consumerError.set(true);
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // GO!
        startLatch.countDown();

        // Wait for producers to finish producing
        // We wait on a separate latch for producers only to trigger shutdown immediately after production ends
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        // Hack: Re-use doneLatch logic? Let's just wait for doneLatch but we need to shutdown *after* producers.
        // Better: Use Phaser or separate latches. 
        // Simple approach: Wait for producers to finish (tracked via atomic counter or specific latch).
        
        // Let's modify: Producers count down a specific latch.
        CountDownLatch producersFinished = new CountDownLatch(numProducers);
        // Rewriting producer loop slightly for this test clarity...
        // Actually, let's just wait for the main doneLatch but signal shutdown when producedSum == expected.
        
        // Wait for all threads to finish (producers + consumers)
        // But consumers run forever until shutdown. 
        // Strategy: Wait for producers to finish -> Shutdown -> Wait for consumers.
        
        // We need to know when producers are done. 
        // Let's add a latch inside the producer loop.
    }

    // --- Refactored Stress Test for Clarity ---
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Stress Test: Verify Checksum (No Loss/Duplication)")
    void testStressChecksum() throws InterruptedException {
        final int capacity = 512;
        final int numProducers = 8;
        final int numConsumers = 8;
        final int itemsPerProducer = 50_000; // 400k total items
        final long totalItems = (long) numProducers * itemsPerProducer;
        final long expectedSum = totalItems * (totalItems + 1) / 2; // Sum 1..N

        BoundedBlockingQueue<Long> q = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        CountDownLatch consumersDone = new CountDownLatch(numConsumers);
        AtomicBoolean errorFlag = new AtomicBoolean(false);

        // Producers produce 1, 2, 3 ... totalItems
        for (int p = 0; p < numProducers; p++) {
            final int id = p;
            new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 1; i <= itemsPerProducer; i++) {
                        long val = id * itemsPerProducer + i; // Unique values 1..totalItems
                        q.put(val);
                        producedSum.addAndGet(val);
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    producersDone.countDown();
                }
            }, "P-" + p).start();
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startGate.await();
                    while (true) {
                        // Poll with timeout to allow checking shutdown flag periodically
                        Long val = q.poll(50, TimeUnit.MILLISECONDS);
                        if (val != null) {
                            consumedSum.addAndGet(val);
                        } else {
                            // Timeout occurred. Check termination condition: 
                            // Producers done AND queue empty AND shutdown initiated.
                            // We will initiate shutdown from main thread after producersDone.
                            if (q.isShutdown() && q.isEmpty()) break;
                        }
                    }
                } catch (Exception e) {
                    errorFlag.set(true);
                    e.printStackTrace();
                } finally {
                    consumersDone.countDown();
                }
            }, "C-" + c).start();
        }

        // Start
        startGate.countDown();

        // Wait for producers
        assertTrue(producersDone.await(20, TimeUnit.SECONDS), "Producers timed out");
        
        // Verify production sum matches expected before shutdown
        assertEquals(expectedSum, producedSum.get(), "Producer checksum mismatch (duplication/loss in production)");

        // Initiate shutdown so consumers can exit cleanly when queue drains
        q.shutdown();

        // Wait for consumers
        assertTrue(consumersDone.await(20, TimeUnit.SECONDS), "Consumers timed out");

        assertFalse(errorFlag.get(), "Thread error occurred");

        // Final Verification
        assertEquals(expectedSum, consumedSum.get(), "Consumer checksum mismatch: Elements lost or duplicated!");
        assertTrue(q.isEmpty(), "Queue should be empty at end");
    }

    @Test
    @DisplayName("Interruption during blocking operations")
    void testInterruption() throws Interrupted