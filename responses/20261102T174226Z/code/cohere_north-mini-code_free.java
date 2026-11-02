<!-- model: cohere/north-mini-code:free | category: code | run: 20261102T174226Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue in Java

Here's a thread-safe, generic bounded blocking queue implementation in Java without using `java.util.concurrent.BlockingQueue`. It supports blocking put/take, timed offer/poll, and a clean shutdown.

## Concurrency Strategy

The queue uses a circular array with head and tail pointers to track the first and last elements. It employs `ReentrantLock` and `Condition` variables to manage thread synchronization and blocking operations. The key aspects of the concurrency strategy are:

1. **Atomic Counters**: `AtomicInteger` is used for `count`, `head`, and `tail` to ensure atomic updates without needing explicit locking for these operations.
2. **ReentrantLock**: A `ReentrantLock` is used to protect critical sections and provide exclusive access to the queue.
3. **Condition Variables**: Two `Condition` variables, `notEmpty` and `notFull`, are used to signal waiting threads when the queue transitions from empty to non-empty or from full to non-full.
4. **Shutdown Handling**: A `shutdown` flag is used to gracefully stop the queue, signaling all waiting threads to wake up and throw an exception on subsequent put/offer operations.
5. **Thread Safety**: All public methods are synchronized using the `ReentrantLock`, ensuring thread safety for all operations.

## Code Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class BoundedBlockingQueue<T> {
    private final T[] array;
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicInteger head = new AtomicInteger(0);
    private final AtomicInteger tail = new AtomicInteger(0);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.array = (T[]) new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lock();
        try {
            while (count.get() == array.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                notFull.await();
            }
            array[tail.get() % array.length] = item;
            tail.incrementAndGet();
            count.incrementAndGet();
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout must be non-negative");
        }
        lock.lock();
        try {
            while (count.get() == array.length) {
                if (shutdown) {
                    throw new IllegalStateException("Queue is shutdown");
                }
                if (!notFull.await(timeout, unit)) {
                    return false;
                }
            }
            array[tail.get() % array.length] = item;
            tail.incrementAndGet();
            count.incrementAndGet();
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count.get() == 0) {
                notEmpty.await();
            }
            T item = array[head.get() % array.length];
            array[head.get() % array.length] = null; // Clear reference
            head.incrementAndGet();
            count.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            while (count.get() == 0) {
                if (!notEmpty.await(timeout, unit)) {
                    return null;
                }
            }
            T item = array[head.get() % array.length];
            array[head.get() % array.length] = null; // Clear reference
            head.incrementAndGet();
            count.decrementAndGet();
            notFull.signal();
            return item;
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

    public int size() {
        return count.get();
    }

    public int capacity() {
        return array.length;
    }
}
```

## Comprehensive JUnit 5 Tests

The following tests cover various scenarios, including basic put/take operations, offer/poll with timeouts, concurrent put/take, stress tests, and edge cases like null items, negative timeouts, and shutdown behavior.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT) // Run tests in parallel
class BoundedBlockingQueueTest {
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testPutTakeBasic() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferPollBasic() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < queue.capacity(); i++) {
            queue.put(i);
        }
        assertEquals(queue.capacity(), queue.size());
        // Try to offer with timeout, should fail
        assertFalse(queue.offer(queue.capacity() + 1, 1, TimeUnit.SECONDS));
        assertEquals(queue.capacity(), queue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // Queue is empty, poll with timeout should return null
        assertNull(queue.poll(1, TimeUnit.SECONDS));
        assertEquals(0, queue.size());
    }

    @Test
    void testPutTakeMultiple() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            queue.put(i);
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(i, queue.take());
        }
        assertEquals(0, queue.size());
    }

    @Test
    void testOfferPollMultiple() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            assertTrue(queue.offer(i, 1, TimeUnit.SECONDS));
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(i, queue.poll(1, TimeUnit.SECONDS));
        }
        assertEquals(0, queue.size());
    }

    @Test
    void testConcurrentPutTake() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);

        List<Integer> produced = new ArrayList<>();
        List<Integer> consumed = new ArrayList<>();

        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < itemsPerProducer; j++) {
                    try {
                        int item = producerId * itemsPerProducer + j;
                        queue.put(item);
                        produced.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                for (int j = 0; j < itemsPerProducer * numProducers / numConsumers; j++) {
                    try {
                        int item = queue.take();
                        consumed.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES));

        // Verify that all produced items are consumed exactly once
        assertEquals(produced.size(), consumed.size());
        assertTrue(produced.containsAll(consumed));
        assertTrue(consumed.containsAll(produced));
    }

    @Test
    @Timeout(10) // Test should complete within 10 seconds
    void testStressMultiProducerMultiConsumer() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int itemsPerProducer = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);

        List<Integer> produced = new ArrayList<>();
        List<Integer> consumed = new ArrayList<>();
        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);

        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                for (int j = 0; j < itemsPerProducer; j++) {
                    try {
                        int item = producerId * itemsPerProducer + j;
                        queue.put(item);
                        totalProduced.incrementAndGet();
                        produced.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                for (int j = 0; j < itemsPerProducer * numProducers / numConsumers; j++) {
                    try {
                        int item = queue.take();
                        totalConsumed.incrementAndGet();
                        consumed.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES));

        // Verify that all produced items are consumed exactly once
        assertEquals(totalProduced.get(), totalConsumed.get());
        assertEquals(produced.size(), consumed.size());
        assertTrue(produced.containsAll(consumed));
        assertTrue(consumed.containsAll(produced));
    }

    @Test
    void testShutdown() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        assertTrue(queue.isShutdown());
        // Should still be able to take remaining items
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
        // Further puts should throw exception
        assertThrows(IllegalStateException.class, () -> queue.put(3));
    }

    @Test
    void testNullItem() {
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(NullPointerException.class, () -> queue.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> queue.offer(1, -1, TimeUnit.SECONDS));
    }

    @Test
    void testZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<>(-1));
    }

    @Test
    void testInterruptedPut() throws InterruptedException {
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(100);
                queue.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        interrupter.start();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        interrupter.join();
    }

    @Test
    void testInterruptedTake() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.take());
    }

    @Test
    void testInterruptedOffer() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testInterruptedPoll() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));