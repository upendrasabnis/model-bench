<!-- model: cohere/north-mini-code:free | category: code | run: 20260831T154541Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

Here's a complete implementation of a generic bounded blocking queue in Java with clean shutdown support:

```java
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;
    private int waitingProducers = 0;
    private int waitingConsumers = 0;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>();
    }

    public void put(T element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        lock.lock();
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
                throw new IllegalStateException("Queue is shut down");
            }
            queue.offer(element);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while (queue.size() == capacity && !shutdown) {
                waitingProducers++;
                try {
                    nanos = notFull.awaitNanos(nanos);
                } finally {
                    waitingProducers--;
                }
                if (nanos <= 0) {
                    return false;
                }
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.offer(element);
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
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
                return null;
            }
            T element = queue.poll();
            notFull.signalAll();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (queue.isEmpty() && shutdown) {
                return null;
            }
            long nanos = unit.toNanos(timeout);
            while (queue.isEmpty() && !shutdown) {
                waitingConsumers++;
                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } finally {
                    waitingConsumers--;
                }
                if (nanos <= 0) {
                    return null;
                }
            }
            if (shutdown && queue.isEmpty()) {
                return null;
            }
            T element = queue.poll();
            notFull.signalAll();
            return element;
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
}
```

## Concurrency Strategy Documentation

The BoundedBlockingQueue class uses a ReentrantLock to synchronize access to the shared queue. This provides more flexibility than synchronized blocks and allows for precise control over condition signaling.

### Key Design Decisions:

1. **Locking Strategy**: The queue uses a single ReentrantLock for all synchronization operations. This ensures mutual exclusion when accessing the shared queue state.

2. **Condition Variables**: Two Condition variables are used:
   - `notEmpty`: Signaled when elements are added to the queue (for consumers)
   - `notFull`: Signaled when elements are removed from the queue (for producers)

3. **Shutdown Handling**: A volatile boolean flag `shutdown` is used to signal shutdown. When set, waiting threads are notified and operations throw appropriate exceptions or return null.

4. **Waiting Counts**: Counters for waiting producers and consumers are maintained to track the number of threads waiting on each condition. This helps in debugging and understanding the queue's behavior.

5. **Timeout Handling**: Timed operations use `awaitNanos()` on condition variables to implement timeout semantics without busy waiting.

6. **Signal All**: When the queue state changes (put or take), `signalAll()` is used to wake up all waiting threads of the appropriate type. This ensures that no thread is left waiting unnecessarily.

7. **Exception Safety**: All operations are wrapped in try-finally blocks to ensure the lock is always released.

8. **Memory Visibility**: The volatile `shutdown` flag ensures visibility of the shutdown state across threads.

This implementation provides a thread-safe, bounded blocking queue with clean shutdown capabilities while maintaining high throughput and low latency for blocking operations.

## Comprehensive JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class BoundedBlockingQueueTest {
    private static final int QUEUE_CAPACITY = 10;
    private static final int TIMEOUT_SECONDS = 5;
    private static final int NUM_PRODUCERS = 10;
    private static final int NUM_CONSUMERS = 10;
    private static final int NUM_ELEMENTS_PER_PRODUCER = 100;

    @Test
    void testBasicPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        queue.put(1);
        assertEquals(1, queue.size());
        assertEquals(1, queue.take().intValue());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPutTakeMultipleElements() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        for (int i = 1; i <= QUEUE_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(QUEUE_CAPACITY, queue.size());
        for (int i = 1; i <= QUEUE_CAPACITY; i++) {
            assertEquals(i, queue.take().intValue());
        }
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOfferWithTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        assertTrue(queue.offer(1, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(queue.offer(2, 1, TimeUnit.MILLISECONDS));
        assertEquals(1, queue.take().intValue());
    }

    @Test
    void testPollWithTimeout() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
        queue.put(1);
        assertEquals(1, queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdown() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        queue.shutdown();
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertNull(queue.take());
        assertNull(queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void testShutdownWithWaitingThreads() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        queue.put(1);
        queue.shutdown();
        assertEquals(1, queue.take().intValue());
        assertNull(queue.take());
    }

    @Test
    void testConcurrentPutTake() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(NUM_PRODUCERS + NUM_CONSUMERS);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < NUM_ELEMENTS_PER_PRODUCER; j++) {
                        queue.put(producerId * NUM_ELEMENTS_PER_PRODUCER + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        for (int i = 0; i < NUM_CONSUMERS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (!queue.isShutdown() || !queue.isEmpty()) {
                        Integer element = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (element != null) {
                            received.add(element);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        queue.shutdown();
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(NUM_PRODUCERS * NUM_ELEMENTS_PER_PRODUCER, received.size());
        Set<Integer> uniqueReceived = new HashSet<>(received);
        assertEquals(received.size(), uniqueReceived.size());
        for (int i = 0; i < NUM_PRODUCERS * NUM_ELEMENTS_PER_PRODUCER; i++) {
            assertTrue(uniqueReceived.contains(i));
        }
    }

    @Test
    void testBoundedness() throws InterruptedException {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            queue.put(i);
        }
        assertEquals(QUEUE_CAPACITY, queue.size());
        AtomicBoolean offerFailed = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                boolean result = queue.offer(999, 100, TimeUnit.MILLISECONDS);
                offerFailed.set(!result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        executor.shutdown();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(offerFailed.get());
        queue.take();
        assertTrue(queue.offer(999, TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(QUEUE_CAPACITY, queue.size());
    }

    @Test
    void testInterruptedWhileWaiting() {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(1);
        queue.put(1);
        Thread thread = new Thread(() -> {
            try {
                queue.put(2, TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        thread.interrupt();
        try {
            thread.join(TIMEOUT_SECONDS * 1000);
            assertTrue(thread.isInterrupted() || !thread.isAlive());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testNullElement() {
        BoundedBlockingQueue<Integer> queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assert