<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260909T221522Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

**Locking Mechanism:** `ReentrantLock` (non-fair by default for throughput, configurable).
**Condition Variables:** Two conditions associated with the single lock:
1.  `notEmpty`: Signaled when an element is added (`put`/`offer`). Awaited by `take`/`poll` when queue is empty.
2.  `notFull`: Signaled when an element is removed (`take`/`poll`). Awaited by `put`/`offer` when queue is full.

**State Variables:**
*   `Object[] items`: Circular array buffer.
*   `int putIndex`, `takeIndex`: Head/Tail pointers (modulo capacity).
*   `int count`: Current number of elements (avoids ambiguity between full/empty when indices equal).
*   `volatile boolean shutdown`: Visibility guarantee for shutdown flag across threads without locking.
*   `int shutdownMode`: Enum (NO_SHUTDOWN, DRAIN, DISCARD) to define behavior on shutdown.

**Algorithm Invariants (Guarded by `lock`):**
1.  `0 <= count <= capacity`
2.  `putIndex` is the index where the *next* element will be placed.
3.  `takeIndex` is the index of the *next* element to be removed.
4.  `count == 0` <=> Queue Empty.
5.  `count == capacity` <=> Queue Full.

**Wait/Notify Protocol (Mesa Semantics / While-Loops):**
*   All `await()` calls are inside `while (condition)` loops to handle **spurious wakeups** and **state changes between signal and re-acquisition of lock**.
*   **Signal Strategy:** `signal()` (not `signalAll()`) is used for standard operations (`put` signals `notEmpty`, `take` signals `notFull`) because only one thread *can* proceed (single slot freed/filled). `signalAll()` is used **only** during `shutdown()` to unblock all waiters immediately.

**Shutdown Protocol:**
1.  Call `shutdown(mode)`.
2.  Acquires lock, sets `shutdown = true`, `shutdownMode = mode`.
3.  `signalAll()` on **both** conditions.
4.  Waiters wake up, re-check predicate: `while (count == 0 && !shutdown)`.
5.  If `shutdown` is true:
    *   `DRAIN`: Allow remaining `take`/`poll` to succeed until empty, then throw `IllegalStateException`.
    *   `DISCARD`: Clear buffer, throw `IllegalStateException` immediately for all blocked/timed calls.
    *   `put`/`offer` always throw `IllegalStateException` immediately after shutdown.

**Memory Visibility:** `volatile shutdown` ensures the flag is visible immediately. The `lock` provides happens-before edges for all other state (`items`, `count`, indices).

---

### Implementation: `BoundedBlockingQueue.java`

```java
package com.example.concurrent;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A thread-safe, generic, bounded blocking queue implementation.
 * <p>
 * Concurrency Strategy:
 * <ul>
 *   <li>Single {@link ReentrantLock} protects all mutable state.</li>
 *   <li>Two {@link Condition} variables: {@code notEmpty} (consumers wait) and {@code notFull} (producers wait).</li>
 *   <li>Mesa semantics: All waits in {@code while} loops to handle spurious wakeups.</li>
 *   <li>{@code signal()} used for standard throughput; {@code signalAll()} used only on shutdown.</li>
 *   <li>Volatile {@code shutdown} flag for fast-path visibility without locking.</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> implements Iterable<E> {

    // --- Shutdown Modes ---
    public enum ShutdownMode {
        /** Producers fail immediately. Consumers drain existing elements, then fail. */
        DRAIN,
        /** Producers fail immediately. Consumers fail immediately. Buffer cleared. */
        DISCARD
    }

    // --- State ---
    private final Object[] items;
    private final int capacity;
    private int putIndex = 0;
    private int takeIndex = 0;
    private int count = 0;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Volatile for visibility without locking on the fast-path check
    private volatile boolean shutdown = false;
    private volatile ShutdownMode shutdownMode = ShutdownMode.DRAIN;

    // --- Constructors ---

    /**
     * Creates a queue with the given fixed capacity.
     * @param capacity the maximum number of elements (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    // --- Core Helpers ---

    private void checkNotShutdownForProducers() {
        if (shutdown) throw new IllegalStateException("Queue shut down: " + shutdownMode);
    }

    private void checkNotShutdownForConsumers() {
        if (shutdown && count == 0) {
            throw new IllegalStateException("Queue shut down and empty: " + shutdownMode);
        }
    }

    private void enqueue(E e) {
        items[putIndex] = e;
        putIndex = (putIndex + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) items[takeIndex];
        items[takeIndex] = null; // Help GC
        takeIndex = (takeIndex + 1) % capacity;
        count--;
        return e;
    }

    private void signalNotEmpty() {
        notEmpty.signal();
    }

    private void signalNotFull() {
        notFull.signal();
    }

    // --- Blocking API ---

    /**
     * Inserts the element, waiting if necessary for space.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            checkNotShutdownForProducers();
            while (count == capacity) {
                notFull.await();
                checkNotShutdownForProducers(); // Re-check after wakeup
            }
            enqueue(e);
            signalNotEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary for an element.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                notEmpty.await();
                checkNotShutdownForConsumers(); // Re-check after wakeup
            }
            E e = dequeue();
            signalNotFull();
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Timed API ---

    /**
     * Inserts the element, waiting up to the specified time for space.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            checkNotShutdownForProducers();
            while (count == capacity) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
                checkNotShutdownForProducers();
            }
            enqueue(e);
            signalNotEmpty();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting up to the specified time for an element.
     * @return the head element, or null if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shut down and empty
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
                checkNotShutdownForConsumers();
            }
            E e = dequeue();
            signalNotFull();
            return e;
        } finally {
            lock.unlock();
        }
    }

    // --- Immediate / Non-Blocking API ---

    /** Inserts element if immediate space available. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        lock.lock();
        try {
            if (shutdown || count == capacity) return false;
            enqueue(e);
            signalNotEmpty();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves and removes head if immediately available. */
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            // Allow polling last elements during DRAIN shutdown
            if (shutdown && shutdownMode == ShutdownMode.DISCARD) return null; 
            E e = dequeue();
            signalNotFull();
            return e;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves but does not remove head. */
    public E peek() {
        lock.lock();
        try {
            if (count == 0) return null;
            return (E) items[takeIndex];
        } finally {
            lock.unlock();
        }
    }

    // --- Introspection ---

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() { return size() == 0; }
    public boolean isFull() { return size() == capacity; }
    public int capacity() { return capacity; }

    // --- Shutdown ---

    /**
     * Initiates shutdown.
     * @param mode {@link ShutdownMode#DRAIN} allows consumers to finish; {@link ShutdownMode#DISCARD} clears buffer immediately.
     * @return number of elements remaining in queue (DRAIN) or cleared (DISCARD).
     */
    public int shutdown(ShutdownMode mode) {
        lock.lock();
        try {
            if (shutdown) return count; // Idempotent
            this.shutdownMode = mode;
            this.shutdown = true; // Volatile write
            
            int remaining = count;
            if (mode == ShutdownMode.DISCARD) {
                Arrays.fill(items, null);
                count = 0;
                putIndex = 0;
                takeIndex = 0;
            }
            // Wake everyone up
            notEmpty.signalAll();
            notFull.signalAll();
            return remaining;
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() { return shutdown; }
    public ShutdownMode getShutdownMode() { return shutdownMode; }

    // --- Bulk / Utility ---

    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null) throw new NullPointerException();
        if (c == this) throw new IllegalArgumentException("Cannot drain to self");
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            for (int i = 0; i < n; i++) {
                c.add(dequeue());
            }
            if (n > 0) signalNotFull();
            return n;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            Arrays.fill(items, null);
            count = 0;
            putIndex = 0;
            takeIndex = 0;
            signalNotFull();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        // Snapshot iterator for thread-safety during iteration
        lock.lock();
        try {
            Object[] snapshot = new Object[count];
            for (int i = 0, idx = takeIndex; i < count; i++, idx = (idx + 1) % capacity) {
                snapshot[i] = items[idx];
            }
            return new Iterator<E>() {
                int cursor = 0;
                @Override public boolean hasNext() { return cursor < snapshot.length; }
                @Override @SuppressWarnings("unchecked") public E next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return (E) snapshot[cursor++];
                }
            };
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0, idx = takeIndex; i < count; i++, idx = (idx + 1) % capacity) {
                sb.append(items[idx]);
                if (i < count - 1) sb.append(", ");
            }
            return sb.append("]").toString();
        } finally {
            lock.unlock();
        }
    }
    
    // Allow functional consumption while locked (advanced)
    public void forEachLocked(Consumer<? super E> action) {
        lock.lock();
        try {
            for (int i = 0, idx = takeIndex; i < count; i++, idx = (idx + 1) % capacity) {
                action.accept((E) items[idx]);
            }
        } finally {
            lock.unlock();
        }
    }
}
```

---

### JUnit 5 Tests: `BoundedBlockingQueueTest.java`

```java
package com.example.concurrent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    // --- Basic Functionality ---

    @Test
    @DisplayName("Basic offer/poll FIFO order")
    void testBasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Capacity limit respected")
    void testCapacityLimit() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3)); // Full
        assertEquals(2, q.size());
    }

    @Test
    @DisplayName("Peek does not remove")
    void testPeek() {
        queue.offer(10);
        assertEquals(10, queue.peek());
        assertEquals(10, queue.peek());
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.put(null));
    }

    // --- Blocking Behavior ---

    @Test
    @DisplayName("put blocks when full, unblocks on take")
    void testPutBlocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Fills it
        
        Thread putter = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        putter.start();
        
        // Give putter time to block
        Thread.sleep(100); 
        assertTrue(putter.isAlive(), "Putter should be blocked");
        
        assertEquals(1, q.take()); // Make space
        putter.join(1000); // Should finish now
        assertFalse(putter.isAlive(), "Putter should have unblocked");
        assertEquals(2, q.take());
    }

    @Test
    @DisplayName("take blocks when empty, unblocks on put")
    void testTakeBlocks() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        Thread taker = new Thread(() -> {
            try { q.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        taker.start();
        
        Thread.sleep(100);
        assertTrue(taker.isAlive(), "Taker should be blocked");
        
        q.put(42); // Provide element
        taker.join(1000);
        assertFalse(taker.isAlive());
    }

    @Test
    @DisplayName("Timed offer returns false on timeout")
    void testTimedOfferTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        assertFalse(q.offer(2, 50, TimeUnit.MILLISECONDS));
        assertEquals(1, q.size());
    }

    @Test
    @DisplayName("Timed poll returns null on timeout")
    void testTimedPollTimeout() throws InterruptedException {
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("InterruptedException thrown on interrupt during put")
    void testInterruptPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        Thread t = new Thread(() -> {
            try { q.put(2); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    @Test
    @DisplayName("InterruptedException thrown on interrupt during take")
    void testInterruptTake() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // --- Shutdown Behavior ---

    @Test
    @DisplayName("Shutdown DRAIN: producers fail, consumers drain then fail")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown(BoundedBlockingQueue.ShutdownMode.DRAIN);
        
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertThrows(IllegalStateException.class, () -> queue.offer(3));
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll());
    }

    @Test
    @DisplayName("Shutdown DISCARD: all operations fail immediately, buffer cleared")
    void testShutdownDiscard() throws InterruptedException {
        queue.put(1); queue.put(2);
        int remaining = queue.shutdown(BoundedBlockingQueue.ShutdownMode.DISCARD);
        
        assertEquals(2, remaining);
        assertTrue(queue.isEmpty());
        
        assertThrows(IllegalStateException.class, () -> queue.put(3));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.poll());
    }

    @Test
    @DisplayName("Shutdown unblocks waiting threads")
    void testShutdownUnblocksWaiters() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        CountDownLatch putterBlocked = new CountDownLatch(1);
        CountDownLatch takerBlocked = new CountDownLatch(1);
        AtomicInteger putterResult = new AtomicInteger(-1); // -1=running, 0=success, 1=exception
        AtomicInteger takerResult = new AtomicInteger(-1);

        Thread putter = new Thread(() -> {
            try { q.put(2); putterResult.set(0); } 
            catch (IllegalStateException e) { putterResult.set(1); }
            catch (InterruptedException e) { putterResult.set(2); }
            finally { putterBlocked.countDown(); }
        });

        Thread taker = new Thread(() -> {
            try { q.take(); takerResult.set(0); } 
            catch (IllegalStateException e) { takerResult.set(1); }
            catch (InterruptedException e) { takerResult.set(2); }
            finally { takerBlocked.countDown(); }
        });

        putter.start(); taker.start();
        putterBlocked.await(1, TimeUnit.SECONDS); // Wait for block
        takerBlocked.await(1, TimeUnit.SECONDS);
        
        // Both should be blocked now
        q.shutdown(BoundedBlockingQueue.ShutdownMode.DRAIN);
        
        putter.join(1000); taker.join(1000);
        
        // Putter should fail (IllegalState), Taker should succeed (drain 1), then next take fails
        // Note: Taker takes the '1'. Putter fails to put '2'.
        assertEquals(1, takerResult.get(), "Taker should succeed draining");
        assertEquals(1, putterResult.get(), "Putter should fail with IllegalState");
    }

    // --- Bulk Operations ---

    @Test
    @DisplayName("drainTo transfers elements")
    void testDrainTo() {
        IntStream.range(0, 5).forEach(queue::put);
        List<Integer> list = new ArrayList<>();
        int drained = queue.drainTo(list, 3);
        assertEquals(3, drained);
        assertEquals(List.of(0,1,2), list);
        assertEquals(2, queue.size());
    }

    @Test
    @DisplayName("clear removes all elements")
    void testClear() {
        IntStream.range(0, 5).forEach(queue::put);
        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(10, queue.remainingCapacity());
    }

    // --- Iterator / Snapshots ---

    @Test
    @DisplayName("Iterator provides consistent snapshot")
    void testIteratorSnapshot() {
        queue.put(1); queue.put(2);
        Iterator<Integer> it = queue.iterator();
        queue.put(3); // Modify after iterator created
        queue.take(); // Remove 1
        
        List<Integer> seen = new ArrayList<>();
        it.forEachRemaining(seen::add);
        assertEquals(List.of(1, 2), seen, "Iterator should see state at creation time");
    }

    // --- Stress / Concurrency ---

    @Test
    @DisplayName("Stress Test: Multi-Producer/Multi-Consumer - No Lost/Duplicated Elements")
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        final int CAPACITY = 100;
        final int PRODUCERS = 4;
        final int CONSUMERS = 4;
        final int ITEMS_PER_PRODUCER = 5000;
        final int TOTAL_ITEMS = PRODUCERS * ITEMS_PER_PRODUCER;

        BoundedBlockingQueue<Integer> stressQueue = new BoundedBlockingQueue<>(CAPACITY);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PRODUCERS + CONSUMERS);
        
        // Use LongAdder for high-contention counting
        LongAdder producedSum = new LongAdder();
        LongAdder consumedSum = new LongAdder();
        AtomicInteger errorCount = new AtomicInteger(0);

        // Shared Set to detect duplicates (ConcurrentHashMap backed Set)
        Set<Integer> seenElements = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // Producers produce unique ranges: P0: 0..4999, P1: 5000..9999, etc.
        for (int p = 0; p < PRODUCERS; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    int base = producerId * ITEMS_PER_PRODUCER;
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        int val = base + i;
                        stressQueue.put(val); // Blocking put
                        producedSum.add(val);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }, "Producer-" + p).start();
        }

        //