<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20261018T070524Z -->
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
 * This implementation uses a single {@link ReentrantLock} to guard all mutable state
 * ({@code head}, {@code tail}, {@code count}, {@code shutdown}) and the backing array.
 * While a single lock limits maximum throughput compared to lock-free algorithms or
 * dual-lock (read/write) strategies, it significantly simplifies correctness reasoning
 * for a bounded buffer, ensuring atomicity of composite operations (check-then-act)
 * and avoiding subtle memory visibility issues.
 * </p>
 * 
 * <h3>Condition Variables</h3>
 * Two {@link Condition} variables are associated with the lock:
 * <ul>
 *   <li><b>notEmpty</b>: Signaled by producers after inserting an element. Consumers wait on this when the queue is empty.</li>
 *   <li><b>notFull</b>: Signaled by consumers after removing an element. Producers wait on this when the queue is full.</li>
 * </ul>
 * Using two separate conditions allows <b>targeted signaling</b> ({@code signal()} instead of {@code signalAll()}).
 * This avoids the "thundering herd" problem where all waiting threads (producers and consumers)
 * are woken up only for one to succeed and the rest to re-check the condition and sleep again.
 * 
 * <h3>Circular Buffer</h3>
 * The queue uses a fixed-size array ({@code items}) with {@code head} (index of next element to dequeue)
 * and {@code tail} (index of next free slot to enqueue) pointers. {@code count} tracks the current size.
 * This avoids expensive array shifting on dequeue.
 * 
 * <h3>Shutdown Protocol</h3>
 * <ul>
 *   <li>{@code shutdown()} atomically sets a {@code shutdown} flag and calls {@code signalAll()} on both conditions.</li>
 *   <li>Blocking operations ({@code put}, {@code take}) throw {@link IllegalStateException} if the queue is shut down
 *       and empty (for take) or full (for put), or if called after shutdown.</li>
 *   <li>Timed operations ({@code offer}, {@code poll}) return {@code false}/{@code null} respectively if shutdown occurs during wait.</li>
 *   <li>Non-blocking operations ({@code offer}, {@code poll} no-arg) fail immediately if shut down.</li>
 * </ul>
 * 
 * <h3>Interruption Handling</h3>
 * Blocking methods respond to thread interruption by clearing the interrupted status
 * and throwing {@link InterruptedException}, allowing standard cancellation patterns.
 * 
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {
    private final Object[] items;
    private final int capacity;
    
    private int head = 0; // Index to take from
    private int tail = 0; // Index to put at
    private int count = 0; // Current number of elements
    
    private volatile boolean shutdown = false; // Volatile for fast-path check without lock
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

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

    // ------------------- Blocking Operations -------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary
     * for space to become available.
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down
     * @throws NullPointerException if the element is null
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lockInterruptibly();
        try {
            // Wait while full and not shutdown
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            
            if (shutdown) {
                throw new IllegalStateException("Queue shut down");
            }
            
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the queue has been shut down and is empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait while empty and not shutdown
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            
            if (count == 0 && shutdown) {
                throw new IllegalStateException("Queue shut down and empty");
            }
            
            E item = dequeue();
            notFull.signal(); // Wake up one producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------- Timed Operations -------------------

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return {@code true} if successful, {@code false} if the waiting time elapsed
     *         before space was available, or if the queue was shut down.
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if the element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e, "Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            
            if (shutdown) {
                return false;
            }
            
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
     * @param timeout how long to wait before giving up
     * @param unit the time unit of the timeout argument
     * @return the head of this queue, or {@code null} if the waiting time elapsed
     *         before an element was available, or if the queue was shut down and empty.
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            
            if (count == 0 && shutdown) {
                return null;
            }
            
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------- Non-Blocking Operations -------------------

    /**
     * Inserts the specified element at the tail of this queue if it is possible to do so
     * immediately without exceeding the queue's capacity, returning {@code true} upon success
     * and {@code false} if this queue is full or shut down.
     */
    public boolean offer(E e) {
        Objects.requireNonNull(e, "Null elements not allowed");
        lock.lock();
        try {
            if (shutdown || count == capacity) {
                return false;
            }
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null} if this queue is empty or shut down.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) {
                return null;
            }
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ------------------- Inspection & Shutdown -------------------

    /** Returns the number of elements in this queue. */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if this queue contains no elements. */
    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if this queue is at capacity. */
    public boolean isFull() {
        lock.lock();
        try {
            return count == capacity;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Initiates an orderly shutdown.
     * <p>
     * All threads blocked in {@code put}, {@code take}, {@code offer}, or {@code poll}
     * will unblock: blocking methods throw {@link IllegalStateException}, timed methods return
     * failure indicators ({@code false}/{@code null}).
     * Subsequent calls to put/offer will fail/reject. Take/poll will drain remaining elements
     * then fail/return null.
     * </p>
     */
    public void shutdown() {
        lock.lock();
        try {
            this.shutdown = true;
            // Wake everyone up to process shutdown logic
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns {@code true} if shutdown has been initiated. */
    public boolean isShutdown() {
        return shutdown; // Volatile read is sufficient for boolean flag
    }

    // ------------------- Private Helpers -------------------

    private void enqueue(E e) {
        items[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) items[head];
        items[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return item;
    }
}
```

---

### 2. JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * 
 * @see ExecutionMode#CONCURRENT Classes are not thread-safe across tests, but methods 
 * rely on fresh instances. Parallel execution is enabled for independent tests.
 */
@Execution(ExecutionMode.CONCURRENT)
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;
    private static final int CAPACITY = 10;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    // ------------------- Basic Functionality -------------------

    @Test
    void testConstructorInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testNullElementsRejected() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testBasicFifoOrder() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    void testSizeAndEmptyFull() throws InterruptedException {
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertFalse(queue.isFull());

        queue.put(1);
        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());

        for (int i = 2; i <= CAPACITY; i++) queue.put(i);
        assertTrue(queue.isFull());
        assertEquals(CAPACITY, queue.size());

        queue.take();
        assertFalse(queue.isFull());
    }

    // ------------------- Blocking Behavior -------------------

    @Test
    void testPutBlocksWhenFull() throws InterruptedException {
        // Fill queue
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread producer = new Thread(() -> {
            try { queue.put(999); } catch (InterruptedException ignored) {}
        });
        producer.start();
        
        // Give producer time to block
        Thread.sleep(100); 
        assertTrue(producer.isAlive(), "Producer should be blocked");
        
        // Consume one, producer should unblock and finish
        queue.take();
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should have unblocked and finished");
        
        // Verify element was added
        assertEquals(999, queue.poll()); // Last element in queue
    }

    @Test
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        Thread consumer = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        
        queue.put(42);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    // ------------------- Timed Operations -------------------

    @Test
    void testOfferTimeoutSuccess() throws InterruptedException {
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void testOfferTimeoutFailure() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        // Should fail quickly (timeout 50ms)
        long start = System.nanoTime();
        boolean result = queue.offer(99, 50, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        
        assertFalse(result);
        assertTrue(elapsed >= 40_000_000, "Should have waited approx timeout"); // 40ms tolerance
        assertEquals(CAPACITY, queue.size());
    }

    @Test
    void testPollTimeoutSuccess() throws InterruptedException {
        queue.put(1);
        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
        assertEquals(1, val);
    }

    @Test
    void testPollTimeoutFailure() throws InterruptedException {
        long start = System.nanoTime();
        Integer val = queue.poll(50, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;
        
        assertNull(val);
        assertTrue(elapsed >= 40_000_000);
    }

    @Test
    void testNonBlockingOfferPoll() {
        assertTrue(queue.offer(1));
        assertFalse(queue.offer(null)); // NPE thrown before capacity check, but offer(E) checks null first
        
        assertEquals(1, queue.poll());
        assertNull(queue.poll());
    }

    // ------------------- Interruption Handling -------------------

    @Test
    void testPutInterruption() {
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        
        Thread t = new Thread(() -> {
            try { queue.put(1); } catch (InterruptedException e) { /* expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        assertThrows(InterruptedException.class, () -> {
            try { t.join(1000); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }, "Thread should have thrown InterruptedException");
        // Note: Verifying the exception inside the thread requires a shared state or UncaughtExceptionHandler.
        // Here we just verify the thread terminates due to interruption.
    }

    @Test
    void testTakeInterruption() {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { /* expected */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ------------------- Shutdown Protocol -------------------

    @Test
    void testShutdownRejectsNewPuts() throws InterruptedException {
        queue.shutdown();
        assertTrue(queue.isShutdown());
        
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertFalse(queue.offer(1));
        assertFalse(queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownAllowsDrainThenFails() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        // Should drain existing
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown -> fail
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownUnblocksWaiters() throws InterruptedException {
        // Block a putter
        for (int i = 0; i < CAPACITY; i++) queue.put(i);
        Thread putter = new Thread(() -> {
            try { queue.put(99); fail("Should have thrown ISE"); } 
            catch (IllegalStateException e) { /* expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        putter.start();
        Thread.sleep(50);
        
        // Block a taker
        Thread taker = new Thread(() -> {
            try { queue.take(); fail("Should have thrown ISE"); } 
            catch (IllegalStateException e) { /* expected */ }
            catch (InterruptedException e) { fail("Should not be interrupted"); }
        });
        // Drain queue first so taker blocks
        for (int i = 0; i < CAPACITY; i++) queue.take();
        taker.start();
        Thread.sleep(50);
        
        // Shutdown
        queue.shutdown();
        
        putter.join(1000);
        taker.join(1000);
        assertFalse(putter.isAlive());
        assertFalse(taker.isAlive());
    }

    // ------------------- Stress / Concurrency Tests -------------------

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMultiProducerMultiConsumerNoLostOrDuplicatedElements() throws InterruptedException {
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 10_000;
        final int totalItems = producerCount * itemsPerProducer;
        
        // Use a Phaser to synchronize start and track completion
        Phaser phaser = new Phaser(producerCount + consumerCount + 1); // +1 for main thread
        
        // Shared verification state
        AtomicLong producedSum = new AtomicLong(0);
        AtomicLong consumedSum = new AtomicLong(0);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        // Use a ConcurrentHashMap to track exact counts of each integer (detect duplicates/loss)
        // Since integers are small (0 to totalItems), we can use an array if we map them, 
        // but Map is generic. We'll use a ConcurrentHashMap<Integer, AtomicInteger>.
        ConcurrentHashMap<Integer, AtomicInteger> producedMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicInteger> consumedMap = new ConcurrentHashMap<>();

        // --- Producers ---
        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all to start
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = producerId * itemsPerProducer + i;
                        queue.put(value); // Blocking put
                        producedSum.addAndGet(value);
                        producedCount.incrementAndGet();
                        producedMap.computeIfAbsent(value, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
            producers.add(t);
            t.start();
        }

        // --- Consumers ---
        List<Thread> consumers = new ArrayList<>();
        for (int c = 0; c < consumerCount; c++) {
            Thread t = new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // Wait for all to start
                try {
                    while (true) {
                        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (val == null) {
                            // Timeout: check if production is done
                            if (producedCount.get() == totalItems && queue.isEmpty()) {
                                break; // All done
                            }
                            // else: spurious timeout or slow producer, continue polling
                            continue;
                        }
                        consumedSum.addAndGet(val);
                        consumedCount.incrementAndGet();
                        consumedMap.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
            consumers.add(t);
            t.start();
        }

        // --- Main Thread Coordination ---
        phaser.arriveAndAwaitAdvance(); // Release all workers
        
        // Wait for producers to finish
        for (Thread t : producers) t.join();
        
        // Signal consumers that production is done (they check producedCount)
        // Consumers will drain remaining queue and exit on timeout+empty check.
        for (Thread t : consumers) t.join(5000); // Generous timeout
        
        // --- Verification ---
        assertEquals(totalItems, producedCount.get(), "All items produced");
        assertEquals(totalItems, consumedCount.get(), "All items consumed");
        assertEquals(producedSum.get(), consumedSum.get(), "Sum mismatch indicates loss/duplication");
        
        // Deep verification: Check exact frequency of every single integer
        assertEquals(producedMap.size(), consumedMap.size(), "Distinct key count mismatch");
        
        for (Map.Entry<Integer, AtomicInteger> entry : producedMap.entrySet()) {
            Integer key = entry.getKey();
            int producedFreq = entry.getValue().get();
            int consumedFreq = consumedMap.getOrDefault(key, new AtomicInteger(0)).get();
            assertEquals(producedFreq, consumedFreq, 
                "Frequency mismatch for key " + key + ": produced " + producedFreq + ", consumed " + consumedFreq);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
   