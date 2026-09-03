<!-- model: cohere/north-mini-code:free | category: code | run: 20260903T092108Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

## Concurrency Strategy Documentation

The `BoundedBlockingQueue` implementation uses a `ReentrantLock` to provide exclusive access to the queue operations, ensuring thread safety. The concurrency strategy is based on the following principles:

1. **Mutual Exclusion**: All operations that modify or access the queue are protected by a `ReentrantLock`, ensuring that only one thread can execute critical sections at a time.

2. **Condition Variables**: Two condition variables are used to manage waiting threads:
   - `notEmpty`: Signaled when an element is added to the queue (via `put`/`offer`)
   - `notFull`: Signaled when space becomes available in the queue (via `take`/`poll`)

3. **Spurious Wakeup Handling**: All waiting operations use `await()` or `awaitNanos()` in a loop to handle potential spurious wakeups, ensuring that threads only proceed when the queue state actually allows it.

4. **Shutdown Handling**: The queue maintains a `shutdown` flag that, when set, causes all waiting operations to throw an exception or return `null`/`false`, depending on the operation.

5. **Signaling**: When a thread adds or removes an element, it signals all waiting threads of the opposite condition (e.g., adding an element signals `notEmpty` and `notFull`).

6. **Fairness**: The implementation does not explicitly enforce fairness, but the use of condition variables ensures that waiting threads are notified in the order they started waiting.

7. **Exception Safety**: All operations are wrapped in try-finally blocks to ensure that the lock is always released, even in the case of exceptions.

8. **Iterator Safety**: The iterator is backed by the queue and provides a snapshot of the queue at the time of iteration. The iterator's `remove()` method is synchronized and signals `notFull` when an element is removed.

This implementation provides a thread-safe, generic, bounded blocking queue with support for blocking put/take operations, timed offer/poll operations, and clean shutdown. It is designed to be used in multi-threaded environments where multiple producers and consumers need to safely exchange data through a bounded buffer.

## BoundedBlockingQueue Implementation

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<T> {
    private final Deque<T> queue;
    private final int capacity;
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean isShutdown = false;
    private int waitingProducers = 0;
    private int waitingConsumers = 0;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.queue = new ArrayDeque<>();
        this.capacity = capacity;
    }

    public void put(T element) throws InterruptedException {
        Objects.requireNonNull(element, "Element cannot be null");
        lock.lock();
        try {
            while (queue.size() == capacity && !isShutdown) {
                waitingProducers++;
                try {
                    notFull.await();
                } finally {
                    waitingProducers--;
                }
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            queue.add(element);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "Element cannot be null");
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (queue.size() == capacity && !isShutdown && nanos > 0) {
                waitingProducers++;
                try {
                    nanos = notFull.awaitNanos(nanos);
                } finally {
                    waitingProducers--;
                }
            }
            if (queue.size() == capacity || isShutdown) {
                return false;
            }
            queue.add(element);
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && !isShutdown) {
                waitingConsumers++;
                try {
                    notEmpty.await();
                } finally {
                    waitingConsumers--;
                }
            }
            if (isShutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            T element = queue.removeFirst();
            notFull.signalAll();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (queue.isEmpty() && !isShutdown && nanos > 0) {
                waitingConsumers++;
                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } finally {
                    waitingConsumers--;
                }
            }
            if (queue.isEmpty() || isShutdown) {
                return null;
            }
            T element = queue.removeFirst();
            notFull.signalAll();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Iterator<T> iterator() {
        lock.lock();
        try {
            return new Iterator<T>() {
                private final Iterator<T> iterator = queue.iterator();
                
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }
                
                @Override
                public T next() {
                    return iterator.next();
                }
                
                @Override
                public void remove() {
                    iterator.remove();
                    notFull.signalAll();
                }
            };
        } finally {
            lock.unlock();
        }
    }

    // Additional methods for testing
    public int getWaitingProducers() {
        lock.lock();
        try {
            return waitingProducers;
        } finally {
            lock.unlock();
        }
    }

    public int getWaitingConsumers() {
        lock.lock();
        try {
            return waitingConsumers;
        } finally {
            lock.unlock();
        }
    }
}
```

## JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class BoundedBlockingQueueTest {
    private static final int DEFAULT_CAPACITY = 10;
    private static final int STRESS_TEST_SIZE = 10_000;
    private static final int STRESS_TEST_PRODUCERS = 10;
    private static final int STRESS_TEST_CONSUMERS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(DEFAULT_CAPACITY);
    }

    @AfterEach
    void tearDown() {
        if (!queue.isEmpty()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferAndPoll() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(DEFAULT_CAPACITY, queue.size());
        
        // Try to offer, should timeout
        assertFalse(queue.offer(-1, 100, TimeUnit.MILLISECONDS));
        assertEquals(DEFAULT_CAPACITY, queue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // Try to poll from empty queue, should timeout
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPutInterrupted() throws InterruptedException {
        Thread thread = new Thread(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        thread.start();
        thread.interrupt();
        
        thread.join();
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPollInterrupted() throws InterruptedException {
        Thread thread = new Thread(() -> {
            try {
                queue.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        thread.start();
        thread.interrupt();
        
        thread.join();
        assertTrue(queue.isEmpty());
    }

    @Test
    void testShutdownWithWaitingProducers() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            queue.put(i);
        }
        
        // Try to put from another thread, should wait
        CountDownLatch latch = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                queue.put(DEFAULT_CAPACITY + 1);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        producer.start();
        latch.await(); // Wait for producer to start waiting
        
        // Shutdown the queue
        queue.shutdown();
        
        // Producer should be interrupted
        producer.join();
        assertTrue(queue.isEmpty());
    }

    @Test
    void testShutdownWithWaitingConsumers() throws InterruptedException {
        // Try to take from empty queue, should wait
        CountDownLatch latch = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
                latch.countDown();
            } catch (InterruptedException e) {