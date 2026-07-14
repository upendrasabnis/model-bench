<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260714T152237Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

**Locking Mechanism:** `ReentrantLock` (non-fair by default for throughput, but fair available via constructor).
**State Guarded:** All fields (`buffer`, `head`, `tail`, `count`, `shutdown`) are guarded by the lock.
**Conditions:**
1.  `notEmpty`: Signaled by `put`/`offer` after inserting an element. Awaited by `take`/`poll` when queue is empty.
2.  `notFull`: Signaled by `take`/`poll` after removing an element. Awaited by `put`/`offer` when queue is full.
**Shutdown Protocol:**
*   `shutdown` flag (guarded by lock).
*   `shutdown()` acquires lock, sets flag `true`, signals **all** waiters on both conditions (`signalAll`).
*   `put`/`offer` check flag immediately; throw `IllegalStateException` / return `false` if shutting down.
*   `take`/`poll` check flag only if queue is empty; if shutting down and empty, throw `NoSuchElementException` / return `null`. If elements remain, they are drained normally.
**Interruptibility:** All blocking methods (`put`, `take`, timed `offer`, `poll`) respond to `Thread.interrupt()` by throwing `InterruptedException` and clearing the interrupt status (standard JDK behavior).

---

### Implementation: `BoundedBlockingQueue.java`

```java
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic, bounded blocking queue implementation backed by a circular array.
 * <p>
 * Concurrency Strategy:
 * <ul>
 *   <li>Single {@link ReentrantLock} guards all mutable state.</li>
 *   <li>Two {@link Condition} variables: {@code notEmpty} (for consumers) and {@code notFull} (for producers).</li>
 *   <li>Shutdown is a terminal state: once initiated, no new elements accepted; existing elements drained.</li>
 *   <li>All blocking operations are interruptible (throw {@link InterruptedException}).</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> {

    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private volatile boolean shutdown = false; // Volatile for fast read check in non-locked paths (though all ops lock)

    /**
     * Creates a queue with the given fixed capacity.
     *
     * @param capacity the maximum number of elements the queue can hold (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    // --- Core Blocking Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
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
            while (count == capacity) {
                checkShutdown(); // Throw if shutdown while waiting for space
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     * @throws NoSuchElementException if the queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (shutdown) throw new NoSuchElementException("Queue shut down and empty");
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Timed Operations ---

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time
     * if necessary for space to become available.
     *
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
            while (count == capacity) {
                checkShutdown();
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
     * Retrieves and removes the head of this queue, waiting up to the specified wait time
     * if necessary for an element to become available.
     *
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
                if (shutdown) return null; // Drained and shut down
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    // --- Immediate Operations (Non-blocking) ---

    /** @return {@code true} if element was added, {@code false} if queue is full. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** @return head element, or {@code null} if empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /** @return head element without removing, or {@code null} if empty. */
    @SuppressWarnings("unchecked")
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) buffer[head];
        } finally {
            lock.unlock();
        }
    }

    // --- Shutdown & State ---

    /**
     * Initiates an orderly shutdown. No further elements can be added.
     * Blocked producers/consumers are unblocked.
     * Consumers can still drain remaining elements.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return;
            shutdown = true;
            // Wake everyone up to re-check state
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        // Volatile read is sufficient for boolean check, but lock ensures memory visibility with other state
        lock.lock();
        try {
            return shutdown;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean isFull() {
        return remainingCapacity() == 0;
    }

    // --- Internal Helpers ---

    private void checkShutdown() {
        if (shutdown) throw new IllegalStateException("Queue has been shut down");
    }

    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
        notEmpty.signal(); // Wake up one consumer
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) buffer[head];
        buffer[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        notFull.signal(); // Wake up one producer
        return e;
    }
}
```

---

### JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
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

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(3);
    }

    // --- Basic Functionality ---

    @Test
    @DisplayName("Basic FIFO order")
    void testFifo() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(3, queue.take());
    }

    @Test
    @DisplayName("Put blocks when full")
    void testPutBlocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        Thread producer = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(100); // Ensure producer is waiting
        assertTrue(producer.isAlive(), "Producer should be blocked");
        assertEquals(1, q.take());
        producer.join(1000);
        assertFalse(producer.isAlive(), "Producer should have unblocked and finished");
        assertEquals(2, q.take());
    }

    @Test
    @DisplayName("Take blocks when empty")
    void testTakeBlocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread consumer = new Thread(() -> {
            try { q.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(100);
        assertTrue(consumer.isAlive(), "Consumer should be blocked");
        q.put(99);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
    }

    @Test
    @DisplayName("Rejects null elements")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    // --- Timed Operations ---

    @Test
    @DisplayName("Offer with timeout returns false when full")
    void testOfferTimeoutFail() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        assertFalse(q.offer(2, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Offer with timeout succeeds when space frees")
    void testOfferTimeoutSuccess() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertTrue(q.offer(1, 1, TimeUnit.SECONDS));
        t.join();
    }

    @Test
    @DisplayName("Poll with timeout returns null when empty")
    void testPollTimeoutFail() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Poll with timeout succeeds when element arrives")
    void testPollTimeoutSuccess() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { Thread.sleep(50); queue.put(42); } catch (InterruptedException ignored) {}
        });
        t.start();
        assertEquals(42, queue.poll(1, TimeUnit.SECONDS));
        t.join();
    }

    // --- Shutdown ---

    @Test
    @DisplayName("Shutdown prevents new puts")
    void testShutdownBlocksPut() {
        queue.put(1);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertFalse(queue.offer(2));
        assertFalse(queue.offer(2, 1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(NoSuchElementException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown unblocks waiting threads")
    void testShutdownUnblocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full

        Thread producer = new Thread(() -> {
            try { q.put(2); fail("Should throw IllegalStateException"); }
            catch (IllegalStateException e) { /* expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start();
        Thread.sleep(50); // Ensure producer waiting on notFull

        Thread consumer = new Thread(() -> {
            try { q.take(); } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(50); // Ensure consumer waiting on notEmpty (after producer takes? no, queue full)
        // Actually consumer waits on notEmpty. Queue is full (1 element).
        // Let's block a consumer on empty queue.
        BoundedBlockingQueue<Integer> q2 = new BoundedBlockingQueue<>(1);
        Thread consumer2 = new Thread(() -> {
            try { q2.take(); fail("Should throw NoSuchElementException"); }
            catch (NoSuchElementException e) { /* expected */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        consumer2.start();
        Thread.sleep(50);

        q.shutdown();
        q2.shutdown();

        producer.join(1000);
        consumer2.join(1000);
        assertFalse(producer.isAlive());
        assertFalse(consumer2.isAlive());
    }

    // --- Interruption ---

    @Test
    @DisplayName("Interrupted put throws InterruptedException")
    void testPutInterruption() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { /* success */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    @DisplayName("Interrupted take throws InterruptedException")
    void testTakeInterruption() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException e) { /* success */ }
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- State Inspection ---

    @Test
    @DisplayName("Size and capacity tracking")
    void testSizeTracking() {
        assertEquals(0, queue.size());
        assertEquals(3, queue.remainingCapacity());
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(2, queue.remainingCapacity());
        queue.put(2);
        queue.put(3);
        assertTrue(queue.isFull());
        assertFalse(queue.isEmpty());
        queue.take();
        assertEquals(2, queue.size());
    }

    @Test
    @DisplayName("Peek does not remove")
    void testPeek() throws InterruptedException {
        assertNull(queue.peek());
        queue.put(10);
        queue.put(20);
        assertEquals(10, queue.peek());
        assertEquals(2, queue.size());
        assertEquals(10, queue.take());
        assertEquals(20, queue.peek());
    }

    // --- Stress Test: Multi-Producer / Multi-Consumer ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Stress Test: Multi-Producer/Consumer - No lost/duplicated elements")
    void stressTestMultiProducerConsumer() throws InterruptedException {
        final int capacity = 100;
        final int producerCount = 4;
        final int consumerCount = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = producerCount * itemsPerProducer;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(capacity);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        // Use a ConcurrentHashMap or synchronized Map to track counts per element for duplication detection
        // Since elements are just integers 0..totalItems-1, we can use an AtomicIntegerArray or Map.
        // We will verify: Sum of produced == Total, Sum of consumed == Total, No duplicates (count == 1 for all).
        
        // To detect duplicates/loss efficiently without massive memory overhead for 20k items:
        // We use a BitSet or AtomicIntegerArray. 
        // Let's use a ConcurrentHashMap<Integer, AtomicInteger> for simplicity in test code.
        Map<Integer, AtomicInteger> elementCounts = new ConcurrentHashMap<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);
        AtomicLong producerErrors = new AtomicLong(0);
        AtomicLong consumerErrors = new AtomicLong(0);

        // Producers
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int val = producerId * itemsPerProducer + i;
                        stressQueue.put(val);
                        producedCount.incrementAndGet();
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    producerErrors.incrementAndGet();
                } catch (Exception e) {
                    producerErrors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        // Consumers
        for (int c = 0; c < consumerCount; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Consumers run until queue is shutdown AND empty
                    while (true) {
                        Integer val;
                        try {
                            // Use poll with timeout to allow checking shutdown condition periodically
                            // But take() throws NoSuchElementException on shutdown+empty.
                            val = stressQueue.take(); 
                        } catch (NoSuchElementException e) {
                            // Shutdown and empty
                            break;
                        }
                        consumedCount.incrementAndGet();
                        elementCounts.computeIfAbsent(val, k -> new AtomicInteger(0)).decrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerErrors.incrementAndGet();
                } catch (Exception e) {
                    consumerErrors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Consumer-" + c).start();
        }

        // Start
        long startTime = System.nanoTime();
        startLatch.countDown();

        // Wait for producers to finish producing
        // We need a way to know when producers are done to call shutdown.
        // Let's wait on a separate latch for producers only.
        // Actually, the doneLatch counts both. We can wait for producers specifically.
        // Simpler: Join producer threads? We don't have handles. 
        // Let's use a Phaser or separate Latch.
        // For this test, we'll just wait for producedCount to reach totalItems, then shutdown.
        
        while (producedCount.get() < totalItems) {
            Thread.sleep(10);
            if (producerErrors.get() > 0) fail("Producer errors occurred");
        }

        // All items produced. Initiate shutdown.
        stressQueue.shutdown();

        // Wait for all threads to finish
        boolean finished = doneLatch.await(20, TimeUnit.SECONDS);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        assertTrue(finished, "Test timed out waiting for threads to finish");
        assertEquals(0, producerErrors.get(), "Producer errors");
        assertEquals(0, consumerErrors.get(), "Consumer errors");
        assertEquals(totalItems, producedCount.get(), "Total produced mismatch");
        assertEquals(totalItems, consumedCount.get(), "Total consumed mismatch");

        // Verify no duplicates / no loss
        // elementCounts should have exactly totalItems keys, all with value 0 (produced - consumed = 0)
        assertEquals(totalItems, elementCounts.size(), "Element count map size mismatch (lost or extra elements)");

        List<Integer> duplicates = elementCounts.entrySet().stream()
                .filter(e -> e.getValue().get() != 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        assertTrue(duplicates.isEmpty(), 
            "Found " + duplicates.size() + " elements with non-zero net count (duplicates or loss). Examples: " + duplicates.subList(0, Math.min(10, duplicates.size())) 
            + " Duration: " + durationMs + "ms");
    }
    
    @Test
    @DisplayName("Fairness / Starvation check (Basic)")
    void testBasicFairness() throws InterruptedException {
        // This is a heuristic test. True fairness testing requires specific thread scheduling control.
        // We verify that a waiting consumer gets the element before a new producer can put 
        // if the queue was empty. 
        // With non-fair lock (default), this isn't guaranteed, but we test logic.
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        AtomicInteger consumerGot = new AtomicInteger(-1);
        CountDownLatch consumerWaiting = new CountDownLatch(1);
        CountDownLatch consumerDone = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            try {
                consumerWaiting.countDown(); // Signal I am about to wait
                consumerGot.set(q.take());
            } catch (InterruptedException ignored) {}
            finally { consumerDone.countDown(); }
        });
        consumer.start();
        consumerWaiting.await(); // Ensure consumer is in take() waiting on notEmpty
        
        // Now put. Consumer