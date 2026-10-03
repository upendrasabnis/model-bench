<!-- model: cohere/north-mini-code:free | category: code | run: 20261003T194309Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Custom Blocking Queue Implementation

## Implementation

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class CustomBlockingQueue<T> {
    private final Deque<T> queue;
    private final int capacity;
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;
    private int waitingProducers = 0;
    private int waitingConsumers = 0;

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    public void put(T element) throws InterruptedException {
        Objects.requireNonNull(element, "Element cannot be null");
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity && !shutdown) {
                waitingProducers++;
                try {
                    notFull.await();
                } finally {
                    waitingProducers--;
                }
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            queue.offer(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "Element cannot be null");
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        lock.lockInterruptibly();
        try {
            if (queue.size() == capacity) {
                waitingProducers++;
                try {
                    long remaining = unit.toNanos(timeout);
                    while (queue.size() == capacity && !shutdown && remaining > 0) {
                        remaining = notFull.awaitNanos(remaining);
                    }
                    if (shutdown || queue.size() == capacity) {
                        return false;
                    }
                } finally {
                    waitingProducers--;
                }
            }
            queue.offer(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !shutdown) {
                waitingConsumers++;
                try {
                    notEmpty.await();
                } finally {
                    waitingConsumers--;
                }
            }
            if (shutdown && queue.isEmpty()) {
                throw new IllegalStateException("Queue is shutdown and empty");
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("Time unit cannot be null");
        }
        lock.lockInterruptibly();
        try {
            if (queue.isEmpty()) {
                waitingConsumers++;
                try {
                    long remaining = unit.toNanos(timeout);
                    while (queue.isEmpty() && !shutdown && remaining > 0) {
                        remaining = notEmpty.awaitNanos(remaining);
                    }
                    if (shutdown || queue.isEmpty()) {
                        return null;
                    }
                } finally {
                    waitingConsumers--;
                }
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

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return shutdown;
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

    public void shutdownNow() {
        lock.lock();
        try {
            shutdown = true;
            queue.clear();
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Iterator<T> iterator() {
        lock.lock();
        try {
            return new ArrayDeque<>(queue).iterator();
        } finally {
            lock.unlock();
        }
    }

    public void forEach(Consumer<? super T> action) {
        Objects.requireNonNull(action, "Action cannot be null");
        lock.lock();
        try {
            for (T element : queue) {
                action.accept(element);
            }
        } finally {
            lock.unlock();
        }
    }
}
```

## Concurrency Strategy

The concurrency strategy for this custom blocking queue is based on the following principles:

1. **ReentrantLock**: We use a `ReentrantLock` to provide explicit locking instead of the built-in synchronized methods. This allows for more control over lock acquisition and release, including the ability to interrupt waiting threads.

2. **Condition Variables**: We use two `Condition` variables:
   - `notEmpty`: Signaled when an element is added to the queue to wake up waiting consumers.
   - `notFull`: Signaled when an element is removed from the queue to wake up waiting producers.

3. **Shutdown Handling**: The queue supports a clean shutdown via the `shutdown()` and `shutdownNow()` methods. When the queue is shutdown:
   - `put()` and `offer()` will throw an `IllegalStateException` or return false if the queue is full.
   - `take()` and `poll()` will throw an `IllegalStateException` or return null if the queue is empty.

4. **Thread Safety**: All methods that modify the queue or wait on conditions are protected by the lock. This ensures that only one thread can modify the queue at any given time.

5. **Interrupt Handling**: The `lock.lockInterruptibly()` method is used to allow waiting threads to be interrupted. This ensures that threads waiting on `put()`, `take()`, `offer()`, or `poll()` can be interrupted if needed.

6. **Efficiency**: The queue uses an `ArrayDeque` internally, which provides O(1) operations for adding and removing elements. The use of condition variables ensures that threads are only woken up when there is work to be done.

7. **Fairness**: The queue is not strictly fair, but it tries to be fair by using a single lock and condition variables. Threads waiting on a condition are woken up in an arbitrary order, but the lock ensures that only one thread can modify the queue at any given time.

8. **Monitoring**: The queue provides methods to check its size, emptiness, and shutdown status, which can be useful for monitoring purposes.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class CustomBlockingQueueTest {
    private CustomBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
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
    void testPutInterruptedWhileFull() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        assertEquals(10, queue.size());
        
        // Start a thread that will try to put and be interrupted
        Thread putThread = new Thread(() -> {
            try {
                queue.put(11);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        putThread.start();
        putThread.interrupt();
        putThread.join();
        
        // The queue should still be full
        assertEquals(10, queue.size());
    }

    @Test
    void testTakeInterruptedWhileEmpty() throws InterruptedException {
        // Start a thread that will try to take and be interrupted
        Thread takeThread = new Thread(() -> {
            try {
                queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        takeThread.start();
        takeThread.interrupt();
        takeThread.join();
        
        // The queue should still be empty
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferTimeout() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        assertEquals(10, queue.size());
        
        // Try to offer with a timeout, should return false
        assertFalse(queue.offer(11, 100, TimeUnit.MILLISECONDS));
        assertEquals(10, queue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // The queue is empty, try to poll with a timeout, should return null
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
        assertTrue(queue.isEmpty());
    }

    @Test
    void testShutdown() {
        queue.shutdown();
        assertTrue(queue.isShutdown());
        
        // put and take should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> {
            queue.put(1);
        });
        
        assertThrows(IllegalStateException.class, () -> {
            queue.take();
        });
    }

    @Test
    void testShutdownNow() {
        queue.put(1);
        queue.put(2);
        queue.shutdownNow();
        assertTrue(queue.isShutdown());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testIterator() {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        
        List<Integer> elements = new ArrayList<>();
        for (Integer element : queue) {
            elements.add(element);
        }
        assertEquals(3, elements.size());
        assertTrue(elements.containsAll(List.of(1, 2, 3)));
    }

    @Test
    void testForEach() {
        queue.put(1);
        queue.put(2);
        queue.put(3);
        
        List<Integer> elements = new ArrayList<>();
        queue.forEach(elements::add);
        assertEquals(3, elements.size());
        assertTrue(elements.containsAll(List.of(1, 2, 3)));
    }

    @Test
    void testSizeAndIsEmpty() {
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        
        queue.put(1);
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());
        
        queue.take();
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultiProducerConsumerStressTest() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int numItemsPerProducer = 100;
        ExecutorService producerExecutor = Executors.newFixedThreadPool(numProducers);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(numConsumers);
        
        List<Integer> producedItems = Collections.synchronizedList(new ArrayList<>());
        List<Integer> consumedItems = Collections.synchronizedList(new ArrayList<>());
        
        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            producerExecutor.submit(() -> {
                for (int j = 0; j < numItemsPerProducer; j++) {
                    int item = producerId * numItemsPerProducer + j;
                    try {
                        queue.put(item);
                        producedItems.add(item);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            consumerExecutor.submit(() -> {
                while (!queue.isShutdown() || !queue.isEmpty()) {
                    try {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            consumedItems.add(item);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        // Wait for all producers to finish
        producerExecutor.shutdown();
        producerExecutor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Shutdown the queue
        queue.shutdown();
        
        // Wait for all consumers to finish
        consumerExecutor.shutdown();
        consumerExecutor.awaitTermination(10, TimeUnit.SECONDS);