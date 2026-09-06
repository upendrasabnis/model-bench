<!-- model: cohere/north-mini-code:free | category: code | run: 20260906T065856Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

## Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MyBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    public MyBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    public void put(T element) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.offer(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (queue.size() == capacity && !shutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.offer(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (queue.isEmpty() && !shutdown) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shut down and empty");
            }
            T element = queue.poll();
            notFull.signal();
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

    public boolean isShutdown() {
        return shutdown;
    }
}
```

## Concurrency Strategy

The implementation uses a **ReentrantLock** and **Condition variables** for thread synchronization, providing more flexible control than `synchronized` blocks. Here's the concurrency strategy:

1. **Mutual Exclusion**: A single `ReentrantLock` protects all shared state (the queue and shutdown flag).

2. **Blocking Semantics**:
   - `put()`: Blocks when the queue is full until space becomes available or shutdown occurs
   - `take()`: Blocks when the queue is empty until an element is available or shutdown occurs
   - `offer()`/`poll()`: Non-blocking variants with timeout support

3. **Signaling**: Condition variables (`notEmpty`, `notFull`) coordinate between producers and consumers:
   - Producers wait on `notFull` when queue is full
   - Consumers wait on `notEmpty` when queue is empty
   - Appropriate signals are sent when state changes

4. **Shutdown Handling**:
   - Shutdown interrupts blocking operations
   - Remaining elements can still be consumed
   - New puts/offers throw exceptions

5. **Thread Safety**:
   - All public methods are properly synchronized
   - Volatile shutdown flag ensures visibility across threads
   - Try-finally blocks ensure lock release

## JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class MyBlockingQueueTest {
    private MyBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new MyBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testPutAndTake() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testOffer() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertTrue(queue.offer(2, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    void testPoll() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertEquals(2, queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testBlockingPutWhenFull() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        assertEquals(10, queue.size());
        // Start a thread that will block on put
        Thread thread = new Thread(() -> {
            try {
                queue.put(11);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        Thread.sleep(100); // Give thread time to block
        assertEquals(10, queue.size());
        queue.take(); // Free up space
        thread.join(); // Wait for thread to finish
        assertEquals(10, queue.size());
        assertEquals(1, queue.take()); // The element put after freeing up space
    }

    @Test
    void testBlockingTakeWhenEmpty() throws InterruptedException {
        // Start a thread that will block on take
        Thread thread = new Thread(() -> {
            try {
                assertEquals(1, queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        Thread.sleep(100); // Give thread time to block
        assertEquals(0, queue.size());
        queue.put(1); // Put element to unblock thread
        thread.join(); // Wait for thread to finish
        assertEquals(0, queue.size());
    }

    @Test
    void testTimedOfferWhenFull() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        assertFalse(queue.offer(11, 100, TimeUnit.MILLISECONDS));
        assertEquals(10, queue.size());
    }

    @Test
    void testTimedPollWhenEmpty() throws InterruptedException {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownLeavesRemainingElements() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    void testMultipleProducersAndConsumers() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int elementsPerProducer = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        List<Integer> expectedElements = new ArrayList<>();
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        List<Integer> actualElements = new ArrayList<>();

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < elementsPerProducer; j++) {
                    int element = producerId * elementsPerProducer + j;
                    try {
                        queue.put(element);
                        expectedElements.add(element);
                        producedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                while (consumedCount.get() < numProducers * elementsPerProducer) {
                    try {
                        Integer element = queue.poll(1, TimeUnit.SECONDS);
                        if (element != null) {
                            actualElements.add(element);
                            consumedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        // Verify all elements were produced and consumed
        assertEquals(expectedElements.size(), actualElements.size());
        assertEquals(expectedElements.size(), producedCount.get());
        assertEquals(expectedElements.size(), consumedCount.get());

        // Verify no duplicates or lost elements
        assertEquals(expectedElements.size(), new java.util.HashSet<>(actualElements).size());
        actualElements.sort(Integer::compare);
        expectedElements.sort(Integer::compare);
        assertEquals(expectedElements, actualElements);
    }
}
```

## Key Features

1. **Thread Safety**: Uses `ReentrantLock` for exclusive access to shared state
2. **Bounded Capacity**: Queue size is limited and blocks when full
3. **Blocking Operations**: `put()` and `take()` block until operation can complete
4. **Timed Operations**: `offer()` and `poll()` with timeout support
5. **Clean Shutdown**: Graceful termination that allows remaining elements to be consumed
6. **Comprehensive Testing**: Includes stress tests with multiple producers/consumers

The implementation provides a robust, thread-safe queue that handles concurrent access correctly while maintaining performance through efficient condition variable usage.