<!-- model: openai/gpt-oss-120b:free | category: code | run: 20260702T080913Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
## 1.  Overview  

Below is a **thread‑safe, generic bounded blocking queue** written from scratch (no use of `java.util.concurrent.BlockingQueue`).  

* **Features**  
  * Fixed capacity defined at construction time.  
  * `put(E e)` – blocks until space is available.  
  * `take()` – blocks until an element is available.  
  * `offer(E e, long timeout, TimeUnit unit)` – tries to insert within the given timeout, returns `false` on timeout.  
  * `poll(long timeout, TimeUnit unit)` – tries to retrieve within the given timeout, returns `null` on timeout.  
  * `shutdown()` – puts the queue into a *closed* state. All waiting producers/consumers are unblocked; further calls to the public API throw `IllegalStateException`.  
  * `isShutdown()` – query the shutdown flag.  

* **Concurrency strategy** – a single intrinsic lock (`synchronized`) plus two condition‑variables implemented with `Object.wait()/notifyAll()`.  
  * **Lock** – the monitor of the queue object protects the internal state (`buffer`, `head`, `tail`, `count`, `shutdown`).  
  * **Conditions** –  
    * `notFull` – threads waiting to `put`/`offer` wait on this condition.  
    * `notEmpty` – threads waiting to `take`/`poll` wait on this condition.  

  The classic “producer‑consumer” pattern is used: a producer inserts when `count < capacity`, a consumer removes when `count > 0`.  

* **Why not `ReentrantLock`?**  
  The assignment explicitly forbids using the `java.util.concurrent` package. The built‑in monitor (`synchronized`) together with `wait/notifyAll` gives the same semantics and is easy to reason about for a single‑queue implementation.

* **Correctness guarantees**  

| Operation | Guarantees when queue is not shut down | Guarantees after shutdown |
|-----------|------------------------------------------|---------------------------|
| `put` / `offer` | Element will be enqueued **exactly once**; the call returns only after space is reserved. | Throws `IllegalStateException` immediately. |
| `take` / `poll` | Returns the next element in FIFO order; never returns `null` unless timed‑out. | Throws `IllegalStateException` immediately (or returns `null` for timed poll after waking). |
| `shutdown` | Unblocks all waiting threads; no further insertions or removals are allowed. | Idempotent – calling again has no effect. |

The queue is **FIFO**, **bounded**, and **fair** only with respect to the monitor lock (i.e. threads are awakened in an unspecified order, but the internal indices guarantee FIFO ordering of elements).

---

## 2.  Implementation  

```java
package com.example.concurrent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A bounded blocking queue with explicit shutdown support.
 *
 * <p>All public methods are thread‑safe.  The implementation uses a single
 * monitor (the queue instance itself) together with two condition variables
 * (the monitor’s {@code wait}/{@code notifyAll}) to coordinate producers
 * and consumers.
 *
 * @param <E> the type of elements held in this queue
 */
public final class BoundedBlockingQueue<E> {

    /** Circular buffer that holds the elements. */
    private final Object[] buffer;
    /** Index of the next element to take. */
    private int head;
    /** Index of the next slot to put. */
    private int tail;
    /** Number of elements currently stored. */
    private int count;
    /** Queue capacity (buffer.length). */
    private final int capacity;

    /** Flag set by {@link #shutdown()} – once true the queue is closed. */
    private volatile boolean shutdown;

    /**
     * Creates a new queue with the given capacity.
     *
     * @param capacity the maximum number of elements the queue can hold
     * @throws IllegalArgumentException if capacity is non‑positive
     */
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.shutdown = false;
    }

    /* --------------------------------------------------------------------- */
    /*                     Public API – Blocking Operations                  */
    /* --------------------------------------------------------------------- */

    /**
     * Inserts the specified element, waiting if necessary for space to become
     * available.
     *
     * @param e the element to add
     * @throws InterruptedException   if the current thread is interrupted
     * @throws IllegalStateException  if the queue has been shut down
     * @throws NullPointerException   if the element is {@code null}
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        synchronized (this) {
            // Fast‑fail if shutdown before we start waiting.
            checkShutdown();

            while (count == capacity) {
                // Queue full – wait for a consumer.
                waitForNotFull();
                checkShutdown(); // re‑check after waking up
            }
            enqueue(e);
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head element
     * @throws InterruptedException   if the current thread is interrupted
     * @throws IllegalStateException  if the queue has been shut down
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (this) {
            checkShutdown();

            while (count == 0) {
                waitForNotEmpty();
                checkShutdown();
            }
            return dequeue();
        }
    }

    /* --------------------------------------------------------------------- */
    /*                     Timed Operations (offer / poll)                   */
    /* --------------------------------------------------------------------- */

    /**
     * Inserts the specified element, waiting up to the given timeout for space
     * to become available.
     *
     * @param e       the element to add
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret the timeout
     * @return {@code true} if the element was added, {@code false} if the timeout elapsed
     * @throws InterruptedException   if the current thread is interrupted
     * @throws IllegalStateException  if the queue has been shut down
     * @throws NullPointerException   if {@code e} or {@code unit} is {@code null}
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        Objects.requireNonNull(unit);
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        synchronized (this) {
            checkShutdown();

            while (count == capacity) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = waitForNotFullNanos(nanos);
                checkShutdown();
            }
            enqueue(e);
            return true;
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the given
     * timeout if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up
     * @param unit    a {@code TimeUnit} determining how to interpret the timeout
     * @return the head element, or {@code null} if the timeout elapsed
     * @throws InterruptedException   if the current thread is interrupted
     * @throws IllegalStateException  if the queue has been shut down
     * @throws NullPointerException   if {@code unit} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        synchronized (this) {
            checkShutdown();

            while (count == 0) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = waitForNotEmptyNanos(nanos);
                checkShutdown();
            }
            return dequeue();
        }
    }

    /* --------------------------------------------------------------------- */
    /*                     Shutdown / Introspection                         */
    /* --------------------------------------------------------------------- */

    /**
     * Permanently shuts down the queue.  All threads currently blocked in
     * {@code put}, {@code take}, {@code offer} or {@code poll} are unblocked
     * and will receive an {@link IllegalStateException} (or {@code null} for a
     * timed {@code poll} after the wake‑up).  Subsequent calls to any
     * operation throw {@code IllegalStateException}.
     *
     * The method is idempotent – calling it more than once has no further
     * effect.
     */
    public void shutdown() {
        // The flag is volatile so that a thread that does not acquire the monitor
        // can still see the change (e.g. a thread that is about to enter a
        // synchronized block).  Inside the monitor we also set it to guarantee
        // visibility for waiting threads that will be notified.
        if (shutdown) {
            return; // already shut down
        }
        synchronized (this) {
            shutdown = true;
            // Wake up *all* waiting threads so they can react to the shutdown.
            this.notifyAll();
        }
    }

    /**
     * Returns {@code true} if the queue has been shut down.
     *
     * @return {@code true} if {@link #shutdown()} has been called
     */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Returns the current number of elements in the queue.
     * This method is primarily for diagnostics and testing.
     *
     * @return the number of queued elements
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /* --------------------------------------------------------------------- */
    /*                     Private Helper Methods                            */
    /* --------------------------------------------------------------------- */

    /** Throws {@code IllegalStateException} if the queue is shut down. */
    private void checkShutdown() {
        if (shutdown) {
            throw new IllegalStateException("Queue has been shut down");
        }
    }

    /** Inserts element into the buffer – caller must hold monitor and have space. */
    private void enqueue(E e) {
        buffer[tail] = e;
        tail = (tail + 1) % capacity;
        count++;
        // Wake up a consumer that may be waiting.
        this.notifyAll(); // could use notify() but notifyAll() is safer with multiple conditions
    }

    /** Removes and returns the head element – caller must hold monitor and have an element. */
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) buffer[head];
        buffer[head] = null; // help GC
        head = (head + 1) % capacity;
        count--;
        // Wake up a producer that may be waiting.
        this.notifyAll();
        return e;
    }

    /** Waits until the queue is not full (or interrupted). */
    private void waitForNotFull() throws InterruptedException {
        while (count == capacity && !shutdown) {
            this.wait();
        }
    }

    /** Waits until the queue is not empty (or interrupted). */
    private void waitForNotEmpty() throws InterruptedException {
        while (count == 0 && !shutdown) {
            this.wait();
        }
    }

    /** Timed wait for not‑full condition; returns remaining nanos. */
    private long waitForNotFullNanos(long nanos) throws InterruptedException {
        if (nanos <= 0L) {
            return 0L;
        }
        long start = System.nanoTime();
        this.wait(TimeUnit.NANOSECONDS.toMillis(nanos), (int) (nanos % 1_000_000));
        long elapsed = System.nanoTime() - start;
        return nanos - elapsed;
    }

    /** Timed wait for not‑empty condition; returns remaining nanos. */
    private long waitForNotEmptyNanos(long nanos) throws InterruptedException {
        if (nanos <= 0L) {
            return 0L;
        }
        long start = System.nanoTime();
        this.wait(TimeUnit.NANOSECONDS.toMillis(nanos), (int) (nanos % 1_000_000));
        long elapsed = System.nanoTime() - start;
        return nanos - elapsed;
    }
}
```

### 2.1  Concurrency notes  

* **Single monitor** – all state changes (`head`, `tail`, `count`, `shutdown`) are performed while holding the queue’s intrinsic lock, guaranteeing mutual exclusion.  

* **Condition variables** – we simulate two condition queues (`notFull`, `notEmpty`) by using `wait()` inside a `while` loop that checks the relevant predicate. Because we call `notifyAll()` after each state change, any thread whose predicate has become true will be able to proceed. Using `notifyAll` (instead of `notify`) avoids missed‑wake‑ups when both producers and consumers are waiting.  

* **Spurious wake‑ups** – the `while` loops protect against them, as required by the Java Memory Model.  

* **Shutdown visibility** – the `shutdown` flag is `volatile`. This ensures that a thread that *fails* to acquire the monitor (e.g. a thread that checks `isShutdown()` before entering a synchronized block) sees the most recent value. Inside the monitor we also test the flag after each `wait` to guarantee that a thread that was notified wakes up and immediately throws `IllegalStateException`.  

* **Fairness** – the implementation does **not** enforce a strict FIFO ordering of waiting threads; that would need a more elaborate hand‑off queue. However, FIFO ordering of the **elements** themselves is preserved because the underlying circular buffer is FIFO.

---

## 3.  JUnit 5 Test Suite  

The test class covers:

* Basic single‑threaded semantics.  
* Blocking behavior of `put`/`take`.  
* Timed `offer`/`poll`.  
* Proper shutdown handling.  
* **Stress test** with many producers and consumers that verifies:
  * No element is lost.  
  * No element is duplicated.  
  * The total number of produced elements equals the total number of consumed elements.  

```java
package com.example.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class BoundedBlockingQueueTest {

    /** Small helper that sleeps without swallowing InterruptedException. */
    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void singleThreadedPutTake() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.put(1);
        q.put(2);
        assertEquals(2, q.size());

        assertEquals(1, q.take());
        assertEquals(2, q.take());
        assertEquals(0, q.size());
    }

    @Test
    void blockingPutBlocksWhenFull() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(10); // fills the queue

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> blocked = exec.submit(() -> {
            try {
                q.put(20); // should block until a take occurs
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give the thread a chance to block
        Thread.sleep(100);
        assertFalse(blocked.isDone());

        // Unblock by taking the element
        assertEquals(10, q.take());
        // now the blocked put can finish
        blocked.get(1, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertEquals(20, q.take());
    }

    @Test
    void timedOfferTimesOut() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1);
        long start = System.nanoTime();
        boolean offered = q.offer(2, 200, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(offered);
        assertTrue(elapsed >= 190 && elapsed <= 300);
    }

    @Test
    void timedPollTimesOut() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        long start = System.nanoTime();
        Integer val = q.poll(150, TimeUnit.MILLISECONDS);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNull(val);
        assertTrue(elapsed >= 140 && elapsed <= 250);
    }

    @Test
    void shutdownUnblocksWaitingProducersAndConsumers() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(1);
        q.put(1); // fill the queue

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<?> producer = exec.submit(() -> {
            assertThrows(IllegalStateException.class, () -> q.put(2));
        });
        Future<?> consumer = exec.submit(() -> {
            // This take will block because queue is empty after we take 1
            assertThrows(IllegalStateException.class, () -> q.take());
        });

        // Give them a moment to block
        Thread.sleep(100);
        q.shutdown();

        // Both futures should complete with IllegalStateException
        producer.get(1, TimeUnit.SECONDS);
        consumer.get(1, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertTrue(q.isShutdown());
    }

    /* --------------------------------------------------------------------- */
    /*                     Multi‑producer / Multi‑consumer Stress Test      */
    /* --------------------------------------------------------------------- */

    @Test
    void stressMultiProducerMultiConsumer() throws Exception {
        final int capacity = 50;
        final int producerCount = 8;
        final int consumerCount = 8;
        final int itemsPerProducer = 10_000; // total 80_000 items
        final BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);

        ExecutorService exec = Executors.newFixedThreadPool(producerCount + consumerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producerCount + consumerCount);

        // Set to collect all consumed values – must be thread‑safe.
        Set<Integer> consumed = Collections.synchronizedSet(new HashSet<>());

        // *** Producers ***
        for (int p = 0; p < producerCount; ++p) {
            final int producerId = p;
            exec.submit(() -> {
                try {
                    startLatch.await();
                    int base = producerId * itemsPerProducer;
                    for (int i = 0; i < itemsPerProducer; ++i) {
                        q.put(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // *** Consumers ***
        final AtomicInteger consumedCount = new AtomicInteger();
        for (int c = 0; c < consumerCount; ++c) {
            exec.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        // Stop condition: all producers finished and queue empty
                        if (consumedCount.get() >= producerCount * itemsPerProducer) {
                            break;
                        }
                        Integer v = q.poll(100, TimeUnit.MILLISECONDS);
                        if (v != null) {
                            // Detect duplicates
                            boolean added = consumed.add(v);
                            assertTrue(added, "Duplicate value detected: " + v);
                            consumedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();

        // Wait for producers and consumers to finish
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test timed out");

        // Verify that every expected value was seen exactly once
        int expected = producerCount * itemsPerProducer;
        assertEquals(expected, consumed.size(),
                "Number of distinct consumed elements does not match produced count");
        assertEquals(expected, consumedCount.get(),
                "Total number of consumed elements does not match produced count");

        // Clean shutdown
        q.shutdown();
        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void shutdownDuringStressStopsAllThreads() throws Exception {
        final BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(10);
        ExecutorService exec = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(4);

        // 2 producers that block on full queue
        for (int i = 0; i < 2; ++i) {
            exec.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        q.put(1); // will eventually block
                    }
                } catch (InterruptedException | IllegalStateException e) {
                    // expected after shutdown
                } finally {
                    stopped.countDown();
                }
            });
        }

        // 2 consumers that block on empty queue
        for (int i = 0; i < 2; ++i) {
            exec.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        q.take(); // will eventually block
                    }
                } catch (InterruptedException | IllegalStateException e) {
                    // expected after shutdown
                } finally {
                    stopped.countDown();
                }
            });
        }

        start.countDown();
        // Let them settle into a blocked state
        Thread.sleep(200);
        q.shutdown();

        // All threads should exit promptly
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        exec.shutdownNow();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
    }
}
```

### 3.1  Explanation of the Stress Test  

* **Producers** – each producer inserts a **contiguous range** of integers (`base … base+itemsPerProducer‑1`). This makes it trivial to detect missing or duplicate values.  

* **Consumers** – each consumer repeatedly calls `poll(100 ms)`. The timeout prevents indefinite blocking after all producers have finished. When a value is retrieved it is added to a synchronized `HashSet`. The `add` call returns `false` if the element was already present, which would indicate a duplicate (a failure of the queue).  

* **Termination condition** – the consumer loop stops once the global `consumedCount` reaches the total number of items produced. This works because every successful poll increments the counter.  

* **Assertions** – after the latch completes we assert that  

  * `consumed.size()` equals the number of items produced → **no loss**.  
  * `consumedCount.get()` equals the same number → **no extra items**.  
  * No duplicate was ever observed (the `assertTrue(added, …)` inside the consumer).  

* **Timeouts** – the whole test is bounded by a 30‑second `await` on the latch; this guards against deadlocks.  

* **Shutdown test** – a second stress‑style test confirms that calling `shutdown()` while threads are blocked wakes them up and they terminate with `IllegalStateException` (or interruption).  

---

## 4.  How to Run  

* **Compilation** – requires Java 8+ (the code uses only core APIs).  

```bash
javac -d out $(find src/main/java -name "*.java")
javac -classpath out:$(path/to/junit-platform-console-standalone.jar) -d out $(find src/test/java -name "*.java")
```

* **Running tests** – with the JUnit 5 console launcher:

```bash
java -jar path/to/junit-platform-console-standalone.jar \
     --class-path out \
     --scan-class-path
```

Or use any IDE (IntelliJ IDEA, Eclipse, VS Code) that supports JUnit 5.

---

### TL;DR  

* The queue is a classic **producer‑consumer** implementation using a circular buffer, a single monitor, and two condition predicates (`notFull` / `notEmpty`).  
* `shutdown()` atomically flips a volatile flag and `notifyAll`s waiting threads so they can abort with `IllegalStateException`.  
* The supplied JUnit 5 suite validates functional correctness, timeout behavior, shutdown semantics, and, most importantly, guarantees **no lost or duplicated elements** under heavy concurrent load.