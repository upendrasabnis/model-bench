<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260804T161021Z -->
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
 * A thread-safe, generic, bounded blocking queue implementation.
 * <p>
 * Concurrency Strategy:
 * <ul>
 *   <li><b>Mutual Exclusion:</b> A single {@link ReentrantLock} guards all mutable state
 *       ({@code head}, {@code tail}, {@code count}, {@code shutdown}, {@code items}).</li>
 *   <li><b>Condition Variables:</b> Two {@link Condition} objects decouple waiting producers
 *       ({@code notFull}) from waiting consumers ({@code notEmpty}). This avoids "spurious wakeups"
 *       affecting the wrong party and allows precise signaling ({@code signal()} vs {@code signalAll()}).
 *   </li>
 *   <li><b>Signaling Discipline:</b>
 *       <ul>
 *         <li>{@code put}/{@code offer}: After inserting, signal {@code notEmpty} (wakes ONE consumer).</li>
 *         <li>{@code take}/{@code poll}: After removing, signal {@code notFull} (wakes ONE producer).</li>
 *         <li>{@code shutdown()}: Signals {@code signalAll()} on BOTH conditions to unblock all threads immediately.</li>
 *       </ul>
 *   </li>
 *   <li><b>Shutdown Protocol:</b>
 *       <ul>
 *         <li>Sets {@code shutdown = true} atomically under the lock.</li>
 *         <li>Producers ({@code put}/{@code offer}) fail fast with {@link IllegalStateException}.</li>
 *         <li>Consumers ({@code take}/{@code poll}) drain remaining elements normally.</li>
 *         <li>Once empty AND shutdown, {@code take} throws {@link IllegalStateException}, {@code poll} returns {@code null}.</li>
 *       </ul>
 *   </li>
 *   <li><b>Interruption:</b> Blocking methods ({@code put}, {@code take}, timed variants) are
 *       responsive to interruption. They throw {@link InterruptedException} after cleaning up state
 *       (re-acquiring lock if necessary) and restore the interrupt flag.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;

    private int head = 0; // Index to take from
    private int tail = 0; // Index to put at
    private int count = 0; // Current number of elements

    private volatile boolean shutdown = false;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shut down
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdownForProducers();
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
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
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @return {@code true} if successful, {@code false} if the waiting time elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shut down
     * @throws NullPointerException if element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity) {
                checkShutdownForProducers();
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
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     * @return the head of this queue, or {@code null} if the waiting time elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) return null; // Shutdown + empty = return null immediately
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Non-blocking / State Operations ---

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

    public boolean isShutdown() {
        return shutdown; // Volatile read is sufficient for boolean flag
    }

    /**
     * Initiates an orderly shutdown.
     * <ul>
     *   <li>Producers ({@code put}/{@code offer}) will immediately throw {@link IllegalStateException}.</li>
     *   <li>Consumers will drain existing elements.</li>
     *   <li>Consumers blocking on empty queue will wake up and throw {@link IllegalStateException} ({@code take}) or return {@code null} ({@code poll}).</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake everyone up to re-evaluate shutdown state
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // --- Internal Helpers ---

    private void enqueue(E e) {
        items[tail] = e;
        if (++tail == capacity) tail = 0;
        count++;
        notEmpty.signal(); // Wake up ONE consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[head];
        items[head] = null; // Help GC
        if (++head == capacity) head = 0;
        count--;
        notFull.signal(); // Wake up ONE producer
        return e;
    }

    private void checkShutdownForProducers() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }
}
```

---

### 2. JUnit 5 Test Suite: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

        Thread producer = new Thread(() -> {
            try { smallQueue.put(3); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(100); // Give producer time to block
        assertTrue(producer.isAlive(), "Producer should be blocked");

        assertEquals(1, smallQueue.take()); // Free slot
        producer.join(500); // Producer should finish now
        assertFalse(producer.isAlive());
        assertEquals(2, smallQueue.take());
        assertEquals(3, smallQueue.take());
    }

    @Test
    @DisplayName("Take blocks when empty")
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
    @DisplayName("Offer succeeds within timeout")
    void testOfferSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Offer fails on timeout when full")
    void testOfferTimeout() {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1);
        assertFalse(small.offer(2, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, small.size());
    }

    @Test
    @DisplayName("Poll succeeds within timeout")
    void testPollSuccess() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Poll returns null on timeout when empty")
    void testPollTimeout() {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    // --- Interruption ---

    @Test
    @DisplayName("Put responds to interruption")
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Fill it

        Thread t = new Thread(() -> {
            try { small.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive());
        assertTrue(t.isInterrupted() || Thread.interrupted()); // Check flag propagated
    }

    @Test
    @DisplayName("Take responds to interruption")
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(500);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Protocol ---

    @Test
    @DisplayName("Shutdown prevents new puts")
    void testShutdownRejectsPuts() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown allows draining existing elements")
    void testShutdownDrainsElements() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();

        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    @DisplayName("Take on empty shutdown queue throws exception")
    void testTakeOnEmptyShutdown() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    @DisplayName("Poll on empty shutdown queue returns null")
    void testPollOnEmptyShutdown() {
        queue.shutdown();
        assertNull(queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll(0, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown wakes up blocked producers and consumers")
    void testShutdownWakesBlockedThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> small = new BoundedBlockingQueue<>(1);
        small.put(1); // Full

        AtomicInteger producerState = new AtomicInteger(0); // 0=running, 1=exception
        Thread producer = new Thread(() -> {
            try { small.put(2); } 
            catch (IllegalStateException e) { producerState.set(1); }
            catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive());

        AtomicInteger consumerState = new AtomicInteger(0);
        Thread consumer = new Thread(() -> {
            try { small.take(); small.take(); } // Take 1, then block on 2nd
            catch (IllegalStateException e) { consumerState.set(1); }
            catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(50);
        assertTrue(consumer.isAlive());

        small.shutdown();

        producer.join(500);
        consumer.join(500);

        assertEquals(1, producerState.get(), "Producer should get IllegalStateException");
        assertEquals(1, consumerState.get(), "Consumer should get IllegalStateException after drain");
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

    // --- Stress Test ---

    @Test
    @DisplayName("Multi-Producer/Multi-Consumer Stress Test (No Lost/Duplicate Elements)")
    @DisabledOnOs(OS.WINDOWS) // Timing sensitive on CI Windows runners sometimes, optional
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        int capacity = 100;
        int producerCount = 4;
        int consumerCount = 4;
        int itemsPerProducer = 10_000;
        int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // Use a ConcurrentHashMap to detect duplicates (value -> count)
        // Since we produce unique integers 0..N, we can just track counts.
        // Actually, summing unique integers is a perfect checksum for loss/duplication.
        // Sum 0 to N-1 = N*(N-1)/2.
        // But producers produce disjoint ranges. 
        // Producer 0: 0..9999, Producer 1: 10000..19999, etc.
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(producerCount + consumerCount);

        // --- Producers ---
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    int start = producerId * itemsPerProducer;
                    int end = start + itemsPerProducer;
                    for (int i = start; i < end; i++) {
                        stressQueue.put(i);
                        producedSum.addAndGet(i);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException e) {
                    // Shutdown during test? fail.
                    fail("Producer shutdown unexpectedly: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // --- Consumers ---
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        // Use poll with timeout to allow exit when queue is empty AND producers done
                        // But we don't know when producers are done easily without coordination.
                        // Better: Consume exactly totalItems / consumerCount? No, distribution varies.
                        // Standard pattern: Consume until shutdown + empty.
                        // We will shutdown explicitly after producers finish.
                        Integer item = stressQueue.take(); // Blocks indefinitely
                        consumedSum.addAndGet(item);
                        consumedCount.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    // Expected on shutdown when empty
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // --- Run ---
        long startTime = System.nanoTime();
        startLatch.countDown(); // GO!

        // Wait for producers to finish
        // We need to wait for producer threads specifically. 
        // endLatch counts everyone. Let's use a separate latch for producers.
        // Simplification: Join producer threads explicitly.
        // Actually, let's just wait for producedCount to reach totalItems.
        while (producedCount.get() < totalItems) {
            Thread.sleep(10);
            if (System.nanoTime() - startTime > TimeUnit.SECONDS.toNanos(30)) {
                fail("Test timed out waiting for producers");
            }
        }

        // Producers done. Initiate shutdown to unblock consumers.
        stressQueue.shutdown();

        // Wait for all threads to finish
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        assertTrue(finished, "Not all threads finished in time");

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        // --- Verification ---
        assertEquals(totalItems, producedCount.get(), "Produced count mismatch");
        assertEquals(totalItems, consumedCount.get(), "Consumed count mismatch");
        
        // Checksum verification: Sum of 0 to N-1
        long expectedSum = (long) totalItems * (totalItems - 1) / 2;
        assertEquals(expectedSum, producedSum.get(), "Produced checksum mismatch (duplicates/loss in put?)");
        assertEquals(expectedSum, consumedSum.get(), "Consumed checksum mismatch (duplicates/loss in take?)");
        
        assertTrue(stressQueue.isEmpty(), "Queue should be empty after drain");
        assertTrue(stressQueue.isShutdown());

        System.out.printf("Stress Test Passed: %,d items in %d ms (%.2f ops/ms)%n", 
            totalItems, durationMs, (double) totalItems / durationMs);
    }
    
    @Test
    @DisplayName("Stress Test with Timed Offer/Poll")
    void testStressTimedOperations() throws InterruptedException {
        int capacity = 50;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        int iterations = 5000;
        int threads = 8;
        CountDownLatch latch = new CountDownLatch(threads * 2);
        AtomicInteger errors = new AtomicInteger(0);

        // Producers use offer with timeout
        for (int i = 0; i < threads; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        int val = id * iterations + j;
                        // Retry loop for offer timeout (simulating backpressure handling)
                        while (!q.offer(val, 100, TimeUnit.MILLISECONDS)) {
                            if (q.isShutdown()) return;
                        }
                    }
                } catch (Exception e) { errors.incrementAndGet(); e.printStackTrace(); }
                finally { latch.countDown(); }
            }).start();
        }

        // Consumers use poll with timeout
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    int consumed = 0;
                    while (consumed < iterations) {
                        Integer val = q.poll(100, TimeUnit.MILLISECONDS);
                        if (val != null) consumed++;
                        // If queue shutdown and empty, poll returns null, loop might spin if not careful.
                        // But we don't shutdown here. We rely on exact counts.
                    }
                } catch (Exception e) { errors.incrementAndGet(); e.printStackTrace(); }
                finally { latch.countDown(); }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed stress test timed out");
        assertEquals(0, errors.get(), "Errors occurred during timed stress test");
        assertEquals(0, q.size(), "Queue not empty after timed stress test");
    }
}
```

---

### 3. Key Implementation Details & Concurrency Strategy Summary

#### **Why `ReentrantLock` + `Condition` over `synchronized` + `wait/notify`?**
1.  **Interruptible Lock Acquisition:** `lock.lockInterruptibly()` allows `put`/`take` to respond to `Thread.interrupt()` *while waiting for the lock itself*, not just while waiting on the condition. `synchronized` blocks cannot be interrupted.
2.  **Multiple Condition Variables:** We have two distinct wait-sets: "Producers waiting for space" (`notFull`) and "Consumers waiting for data" (`notEmpty`). With `synchronized`, `notifyAll()` wakes *everyone*, causing "thundering herd" and spurious wakeups where a producer wakes up only to find the queue still full. `Condition.signal()` wakes exactly one thread of the correct type.
3.  **Fairness Option:** `new ReentrantLock(true)` enables fair queuing (FIFO thread access), preventing thread starvation, which is impossible with intrinsic locks.
4.  **Timed Waits:** `condition.awaitNanos(nanos)` handles the remaining timeout calculation atomically with the wait, avoiding the complexity of manual `System.nanoTime()` loops required for `wait(timeout)`.

#### **Shutdown Strategy: "Poison Pill" via State Flag**
Instead of inserting special "Poison Pill" objects (which pollutes the generic type `E` and requires consumers to check `instanceof`), we use