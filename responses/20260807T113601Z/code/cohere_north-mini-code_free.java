<!-- model: cohere/north-mini-code:free | category: code | run: 20260807T113601Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
**Implementation of `BoundedBlockingQueue<T>` and JUnit 5 Tests**

### 1. BoundedBlockingQueue Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic bounded blocking queue implementation without using java.util.concurrent.BlockingQueue.
 * <p>
 * Concurrency Strategy:
 * - Uses a circular array to store elements.
 * - A single {@link ReentrantLock} protects all shared state.
 * - Two Condition variables: {@code notEmpty} (signaled when an element is added) and {@code notFull}
 *   (signaled when space becomes available).
 * - All public methods acquire the lock before accessing shared state.
 * - Blocking operations use {@code Condition.await()} with optional timeouts.
 * - A {@code shutdown} flag is used to signal that the queue is shutting down; operations
 *   will throw {@code IllegalStateException} when called after shutdown.
 * - The queue is thread-safe; multiple producers and consumers can operate concurrently.
 * </p>
 *
 * @param <T> the type of elements held in this queue
 */
public class BoundedBlockingQueue<T> {
    private final T[] items;
    private int head; // index of the first element
    private int tail; // index of the next insertion point
    private int size; // current number of elements
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.items = (T[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary until space is available.
     *
     * @param t the element to add
     * @throws IllegalStateException if the queue is shutdown
     */
    public void put(T t) throws InterruptedException {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (size == items.length) {
                notFull.await();
            }
            items[tail] = t;
            if (++tail == items.length) {
                tail = 0;
            }
            size++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, blocking until an element is available.
     *
     * @return the head element
     * @throws IllegalStateException if the queue is shutdown
     */
    public T take() throws InterruptedException {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (size == 0) {
                notEmpty.await();
            }
            T t = items[head];
            items[head] = null; // avoid memory leak
            if (++head == items.length) {
                head = 0;
            }
            size--;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting up to the specified wait time if necessary.
     *
     * @param t      the element to add
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if the timeout expired
     * @throws IllegalStateException if the queue is shutdown
     */
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            long nanos = unit.toNanos(timeout);
            while (size == items.length) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            items[tail] = t;
            if (++tail == items.length) {
                tail = 0;
            }
            size++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the specified wait time if necessary.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the head element, or {@code null} if the timeout expires
     * @throws IllegalStateException if the queue is shutdown
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            long nanos = unit.toNanos(timeout);
            while (size == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            T t = items[head];
            items[head] = null; // avoid memory leak
            if (++head == items.length) {
                head = 0;
            }
            size--;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Signals the queue to shutdown. After shutdown, any further operations will throw
     * {@link IllegalStateException}.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of elements in the queue.
     */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if the queue is shutdown.
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
```

### 2. JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * JUnit 5 tests for BoundedBlockingQueue.
 */
class BoundedBlockingQueueTest {

    @Test
    void testPutAndTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(5);
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(1, queue.size());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferAndPoll() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(2);
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertTrue(queue.offer(2, 100, TimeUnit.MILLISECONDS));
        assertFalse(queue.offer(3, 10, TimeUnit.MILLISECONDS)); // queue full, timeout
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(2, queue.poll(100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS)); // queue empty, timeout
    }

    @Test
    void testBlockingPut() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        queue.put(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                queue.put(2);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // Wait a bit to ensure put is blocking
        Thread.sleep(100);
        assertEquals(1, queue.size());
        queue.take(); // free space
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, queue.size());
        executor.shutdown();
    }

    @Test
    void testBlockingTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                assertEquals(1, queue.take());
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100);
        queue.put(1);
        latch.await(1, TimeUnit.SECONDS);
        executor.shutdown();
    }

    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(5);
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertFalse(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testCapacityZero() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
    }

    @Test
    void testConcurrentPutTake() throws InterruptedException {
        int capacity = 100;
        int producers = 5;
        int consumers = 5;
        int totalItems = 1000;
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(capacity);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(producers + consumers);
        AtomicInteger produced = new AtomicInteger(0);
        AtomicInteger consumed = new AtomicInteger(0);
        Set<Integer> producedSet = new HashSet<>();
        Set<Integer> consumedSet = new HashSet<>();
        ExecutorService executor = Executors.newFixedThreadPool(producers + consumers);
        // Producers
        for (int i = 0; i < producers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (produced.get() < totalItems / producers) {
                        int item = produced.getAndIncrement();
                        queue.put(item);
                        producedSet.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        // Consumers
        for (int i = 0; i < consumers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (consumed.get() < totalItems / consumers) {
                        Integer item = queue.take();
                        if (item != null) {
                            consumedSet.add(item);
                            consumed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        // Verify no lost or duplicated elements
        assertEquals(totalItems, producedSet.size(), "Some produced items were lost or duplicated");
        assertEquals(totalItems, consumedSet.size(), "Some consumed items were lost or duplicated");
        // Ensure all produced items were consumed (set equality)
        assertEquals(producedSet, consumedSet, "Produced and consumed sets differ");
    }

    @Test
    void testShutdownWhileBlocking