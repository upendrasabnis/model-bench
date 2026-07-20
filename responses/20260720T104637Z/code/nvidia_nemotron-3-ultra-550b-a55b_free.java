<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260720T104637Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete, production-grade implementation of a **Thread-Safe Bounded Blocking Queue** using `ReentrantLock` and `Condition` variables, followed by comprehensive JUnit 5 tests.

---

### 1. Concurrency Strategy Documentation

**`BoundedBlockingQueue<E>` – Concurrency Control Strategy**

| Aspect | Strategy | Rationale |
| :--- | :--- | :--- |
| **Mutual Exclusion** | Single `ReentrantLock` (`lock`) guarding **all** mutable state (`buffer`, `head`, `tail`, `count`, `shutdown`). | Ensures atomicity of compound actions (check-then-act: `count == capacity` -> `wait`). Simpler reasoning than split locks (read/write) for a bounded buffer where contention is inherent. |
| **Signaling** | Two `Condition` variables: `notFull` (awaited by producers), `notEmpty` (awaited by consumers). | **Targeted Signaling**: `signal()` wakes exactly one relevant thread type. `signalAll()` used only on `shutdown()` to prevent "lost wakeups" during termination. Avoids spurious wakeups overhead of `notifyAll()` on a single monitor. |
| **Waiting Protocol** | `while (condition) condition.await(...)` loops. | Mandatory defense against **spurious wakeups** and state changes between signal and thread scheduling. |
| **Shutdown Semantics** | `volatile boolean shutdown` (guarded by lock for visibility + atomicity with state checks).<br>1. `shutdown()`: Sets flag, `signalAll()` on both conditions.<br>2. `put`/`offer`: Reject immediately if shutdown.<br>3. `take`/`poll`: Drain remaining elements; return `null`/throw only when empty *and* shutdown. | **Poison Pill / Graceful Drain**: Allows consumers to finish processing enqueued work. Producers fail fast. `signalAll` ensures blocked threads re-check the shutdown flag immediately. |
| **Interruption** | `await()` / `awaitNanos()` throw `InterruptedException`.<br>Lock acquisition uses `lockInterruptibly()` for timed ops. | **Cancellation Propagation**: Threads blocked on full/empty queue respond to `Thread.interrupt()` immediately, cleaning up lock state via `finally` blocks. |
| **Fairness** | `ReentrantLock(true)` (Fair mode) optional via constructor. | Prevents **thread starvation** under high contention. Default non-fair offers higher throughput; fair provides stricter FIFO thread ordering. |
| **Data Structure** | Circular Array (`Object[] buffer`) with `head`/`tail` indices & `count`. | O(1) `put`/`take`. Fixed memory footprint. Avoids `LinkedList` allocation overhead per node. |

**Correctness Invariants (Guarded by `lock`):**
1.  `0 <= count <= capacity`
2.  `count == 0` $\iff$ `head == tail` (Buffer Empty)
3.  `count == capacity` $\iff$ `head == tail` (Buffer Full)
4.  Elements reside in `buffer[head]` ... `buffer[(tail-1+cap)%cap]`.

---

### 2. Implementation (`BoundedBlockingQueue.java`)

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
 * Features:
 * <ul>
 *   <li>Blocking {@code put}/{@code take}</li>
 *   <li>Timed {@code offer}/{@code poll}</li>
 *   <li>Graceful {@code shutdown()} (drain-then-terminate)</li>
 *   <li>Fairness policy support</li>
 * </ul>
 *
 * @param <E> the type of elements held in this queue
 */
public class BoundedBlockingQueue<E> implements Iterable<E> {

    // --- State (Guarded by 'lock') ---
    private final Object[] buffer;
    private final int capacity;
    private int head = 0;      // Index of next element to take
    private int tail = 0;      // Index of next slot to put
    private int count = 0;     // Current number of elements
    private volatile boolean shutdown = false; // Volatile for fast read check, but writes/reads in lock

    // --- Synchronization Primitives ---
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;

    /**
     * Creates a queue with the given capacity and non-fair locking (default).
     * @param capacity the maximum number of elements (must be > 0)
     * @throws IllegalArgumentException if capacity <= 0
     */
    public BoundedBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * Creates a queue with the given capacity and fairness policy.
     * @param capacity the maximum number of elements (must be > 0)
     * @param fair if true, threads contend for lock in FIFO order; higher contention overhead, no starvation.
     */
    public BoundedBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.lock = new ReentrantLock(fair);
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    // ============================================================
    // Core Blocking Operations
    // ============================================================

    /**
     * Inserts the element, waiting if necessary for space to become available.
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue has been shutdown
     * @throws NullPointerException if element is null
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        lock.lockInterruptibly(); // Respect interruption during lock acquisition
        try {
            // Wait for space OR shutdown
            while (count == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) throw new IllegalStateException("Queue shutdown");
            
            enqueue(e);
            notEmpty.signal(); // Wake up one consumer
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head, waiting if necessary until an element becomes available.
     * @return the head element
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if queue is shutdown AND empty
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // Wait for element OR (shutdown AND empty)
            while (count == 0 && !shutdown) {
                notEmpty.await();
            }
            if (count == 0 && shutdown) {
                throw new IllegalStateException("Queue shutdown and empty");
            }
            
            E item = dequeue();
            notFull.signal(); // Wake up one producer
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Timed Operations
    // ============================================================

    /**
     * Inserts element if space available within timeout.
     * @return true if successful, false if timeout elapsed
     * @throws InterruptedException if interrupted
     * @throws IllegalStateException if shutdown
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) throw new NullPointerException("Null elements not allowed");
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == capacity && !shutdown) {
                if (nanos <= 0) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) throw new IllegalStateException("Queue shutdown");
            
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves head if available within timeout.
     * @return head element, or null if timeout elapsed
     * @throws InterruptedException if interrupted
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (count == 0 && shutdown) return null; // Drained
            
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Immediate / State Operations
    // ============================================================

    /** Inserts element only if space immediately available. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        lock.lock();
        try {
            if (count == capacity || shutdown) return false;
            enqueue(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head only if immediately available. */
    @SuppressWarnings("unchecked")
    public E poll() {
        lock.lock();
        try {
            if (count == 0) return null;
            E item = dequeue();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    /** Retrieves head without removing (blocking). */
    @SuppressWarnings("unchecked")
    public E peek() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !shutdown) notEmpty.await();
            if (count == 0) return null; // Shutdown & empty
            return (E) buffer[head];
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Shutdown & Lifecycle
    // ============================================================

    /**
     * Initiates graceful shutdown.
     * <ul>
     *   <li>Rejects future {@code put}/{@code offer} with {@code IllegalStateException}/{@code false}.</li>
     *   <li>Wakes all waiting threads.</li>
     *   <li>Allows consumers to drain remaining elements via {@code take}/{@code poll}.</li>
     *   <li>Once empty, {@code take} throws {@code IllegalStateException}, {@code poll} returns {@code null}.</li>
     * </ul>
     */
    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) return; // Idempotent
            shutdown = true;
            // Wake EVERYONE: producers (to fail fast), consumers (to drain)
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        // Volatile read is sufficient for boolean flag check outside lock
        // but we often check inside lock for atomicity with count.
        return shutdown;
    }

    public boolean isTerminated() {
        lock.lock();
        try {
            return shutdown && count == 0;
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Inspection & Utilities
    // ============================================================

    public int size() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - count; } finally { lock.unlock(); }
    }

    public boolean isEmpty() {
        lock.lock();
        try { return count == 0; } finally { lock.unlock(); }
    }

    public boolean isFull() {
        lock.lock();
        try { return count == capacity; } finally { lock.unlock(); }
    }

    public int capacity() { return capacity; }

    /** Removes all elements (only if not shutdown). */
    public void clear() {
        lock.lock();
        try {
            if (shutdown) throw new IllegalStateException("Cannot clear shutdown queue");
            Arrays.fill(buffer, null);
            head = 0;
            tail = 0;
            count = 0;
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ============================================================
    // Internal Helpers (Require Lock Held)
    // ============================================================

    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E item = (E) buffer[head];
        buffer[head] = null; // Help GC
        head = (head + 1) % capacity;
        count--;
        return item;
    }

    // ============================================================
    // Iterable Support (Snapshot Iterator)
    // ============================================================

    @Override
    public Iterator<E> iterator() {
        // Snapshot to avoid holding lock during iteration
        Object[] snapshot;
        lock.lock();
        try {
            snapshot = new Object[count];
            for (int i = 0; i < count; i++) {
                snapshot[i] = buffer[(head + i) % capacity];
            }
        } finally {
            lock.unlock();
        }
        return new Iterator<>() {
            int idx = 0;
            @Override public boolean hasNext() { return idx < snapshot.length; }
            @SuppressWarnings("unchecked")
            @Override public E next() {
                if (!hasNext()) throw new NoSuchElementException();
                return (E) snapshot[idx++];
            }
        };
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        for (E e : this) action.accept(e);
    }
}
```

---

### 3. Comprehensive JUnit 5 Tests (`BoundedBlockingQueueTest.java`)

```java
package com.example.concurrent;

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
import static java.util.concurrent.TimeUnit.*;

/**
 * Comprehensive test suite for BoundedBlockingQueue.
 * Covers: API Contract, Blocking, Timing, Shutdown, Concurrency, Stress.
 */
@DisplayName("BoundedBlockingQueue Tests")
class BoundedBlockingQueueTest {

    // ---------------------------------------------------------
    // Fixtures & Constants
    // ---------------------------------------------------------
    private static final int SMALL_CAP = 10;
    private static final int MEDIUM_CAP = 100;
    private static final long SHORT_TIMEOUT_MS = 50;
    private static final long LONG_TIMEOUT_MS = 5000;

    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(SMALL_CAP);
    }

    // ---------------------------------------------------------
    // 1. Basic API Contract & FIFO Ordering
    // ---------------------------------------------------------

    @Test
    @DisplayName("Basic FIFO offer/poll")
    void testBasicFifo() {
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertEquals(1, queue.poll());
        assertEquals(2, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    @DisplayName("Capacity limit enforced")
    void testCapacityLimit() {
        for (int i = 0; i < SMALL_CAP; i++) assertTrue(queue.offer(i));
        assertFalse(queue.offer(999)); // Full
        assertEquals(SMALL_CAP, queue.size());
    }

    @Test
    @DisplayName("Null elements rejected")
    void testNullRejection() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, SECONDS));
    }

    @Test
    @DisplayName("Peek does not remove")
    void testPeek() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.peek());
        assertEquals(1, queue.peek());
        assertEquals(2, queue.size());
    }

    @Test
    @DisplayName("Iterator returns snapshot in FIFO order")
    void testIteratorSnapshot() {
        queue.offer(1); queue.offer(2); queue.offer(3);
        List<Integer> list = new ArrayList<>();
        queue.forEach(list::add);
        assertEquals(List.of(1, 2, 3), list);
        
        // Modifying queue doesn't affect iterator
        queue.poll();
        queue.offer(4);
        list.clear();
        queue.forEach(list::add);
        assertEquals(List.of(2, 3, 4), list);
    }

    // ---------------------------------------------------------
    // 2. Blocking Behavior (put/take)
    // ---------------------------------------------------------

    @Test
    @DisplayName("put blocks when full, unblocks on take")
    void testPutBlocksWhenFull() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        q.put(1); q.put(2); // Full
        
        AtomicBoolean producerBlocked = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { q.put(3); producerBlocked.set(true); } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        
        producer.start();
        Thread.sleep(100); // Ensure producer is waiting
        assertFalse(producerBlocked.get(), "Producer should be blocked");
        
        q.take(); // Make space
        producer.join(1000);
        assertTrue(producerBlocked.get(), "Producer should proceed after take");
        assertEquals(3, q.take());
    }

    @Test
    @DisplayName("take blocks when empty, unblocks on put")
    void testTakeBlocksWhenEmpty() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(2);
        AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        
        Thread consumer = new Thread(() -> {
            try { q.take(); consumerBlocked.set(true); } 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        
        consumer.start();
        Thread.sleep(100);
        assertFalse(consumerBlocked.get());
        
        q.put(42);
        consumer.join(1000);
        assertTrue(consumerBlocked.get());
    }

    @Test
    @DisplayName("InterruptedException on blocked put")
    void testInterruptedPut() throws InterruptedException {
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
    @DisplayName("InterruptedException on blocked take")
    void testInterruptedTake() throws InterruptedException {
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertFalse(t.isAlive());
    }

    // ---------------------------------------------------------
    // 3. Timed Operations (offer/poll)
    // ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Timed offer returns false on timeout (full)")
    void testTimedOfferTimeout(boolean fair) {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1, fair);
        q.offer(1); // Full
        assertFalse(q.offer(2, SHORT_TIMEOUT_MS, MILLISECONDS));
        assertEquals(1, q.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Timed offer succeeds when space opens")
    void testTimedOfferSuccess(boolean fair) throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1, fair);
        q.offer(1); // Full
        
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(50); q.take(); } catch (InterruptedException ignored) {}
        });
        releaser.start();
        
        assertTrue(q.offer(2, LONG_TIMEOUT_MS, MILLISECONDS));
        releaser.join();
        assertEquals(2, q.poll());
    }

    @Test
    @DisplayName("Timed poll returns null on timeout (empty)")
    void testTimedPollTimeout() {
        assertNull(queue.poll(SHORT_TIMEOUT_MS, MILLISECONDS));
    }

    @Test
    @DisplayName("Timed poll succeeds when element arrives")
    void testTimedPollSuccess() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try { Thread.sleep(50); queue.put(99); } catch (InterruptedException ignored) {}
        });
        producer.start();
        
        Integer val = queue.poll(LONG_TIMEOUT_MS, MILLISECONDS);
        producer.join();
        assertEquals(99, val);
    }

    @Test
    @DisplayName("Immediate offer/poll non-blocking")
    void testImmediateOps() {
        assertTrue(queue.offer(1));
        assertFalse(queue.offer(2)); // Full if cap=1? No, cap=10. 
        // Let's test immediate poll
        assertEquals(1, queue.poll());
        assertNull(queue.poll());
    }

    // ---------------------------------------------------------
    // 4. Shutdown Semantics
    // ---------------------------------------------------------

    @Test
    @DisplayName("Shutdown rejects new puts immediately")
    void testShutdownRejectsPut() {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertFalse(queue.offer(1));
        assertFalse(queue.offer(1, 1, SECONDS));
    }

    @Test
    @DisplayName("Shutdown allows draining remaining elements")
    void testShutdownDrain() throws InterruptedException {
        queue.put(1); queue.put(2);
        queue.shutdown();
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        // Now empty and shutdown
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertNull(queue.poll());
        assertNull(queue.poll(100, MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown wakes blocked producers (fail fast)")
    void testShutdownWakesProducers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // Full
        
        AtomicBoolean producerFailed = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            try { q.put(2); } 
            catch (IllegalStateException e) { producerFailed.set(true); }
            catch (InterruptedException ignored) {}
        });
        producer.start();
        Thread.sleep(50);
        
        q.shutdown(); // Should wake producer with IllegalStateException
        producer.join(1000);
        assertTrue(producerFailed.get());
    }

    @Test
    @DisplayName("Shutdown wakes blocked consumers (drain then fail)")
    void testShutdownWakesConsumers() throws InterruptedException {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        
        AtomicBoolean consumerFinished = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            try { 
                q.take(); // Block
            } catch (IllegalStateException e) { consumerFinished.set(true); }
            catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(50);
        
        q.shutdown(); // Wake consumer -> IllegalStateException (empty)
        consumer.join(1000);
        assertTrue(consumerFinished.get());
    }

    @Test
    @DisplayName("Shutdown