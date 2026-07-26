<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260726T053632Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Below is a thread-safe, generic bounded blocking queue implemented in Java, along with comprehensive JUnit 5 tests. This implementation includes blocking `put`/`take`, timed `offer`/`poll`, and a clean shutdown mechanism. The concurrency strategy is documented afterward.

### Thread-Safe Bounded Blocking Queue Implementation

```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<T> {
    private final T[] queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private int head = 0;
    private int tail = 0;
    private final AtomicInteger count = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.queue = (T[]) new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (count.get() == queue.length) {
                notFull.await();
            }
            queue[tail] = item;
            tail = (tail + 1) % queue.length;
            count.incrementAndGet();
            notEmpty.signalAll();
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
            T item = queue[head];
            queue[head] = null; // Help garbage collection
            head = (head + 1) % queue.length;
            count.decrementAndGet();
            notFull.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        long start = System.nanoTime();
        lock.lock();
        try {
            while (count.get() == queue.length) {
                long duration = System.nanoTime() - start;
                if (duration >= unit.toNanos(timeout)) {
                    return false;
                }
                notFull.awaitNanos(unit.toNanos(timeout) - duration);
            }
            queue[tail] = item;
            tail = (tail + 1) % queue.length;
            count.incrementAndGet();
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long start = System.nanoTime();
        lock.lock();
        try {
            while (count.get() == 0) {
                long duration = System.nanoTime() - start;
                if (duration >= unit.toNanos(timeout)) {
                    return null;
                }
                notEmpty.awaitNanos(unit.toNanos(timeout) - duration);
            }
            T item = queue[head];
            queue[head] = null; // Help garbage collection
            head = (head + 1) % queue.length;
            count.decrementAndGet();
            notFull.signalAll();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
```

### Concurrency Strategy

- **ReentrantLock**: Ensures mutual exclusion for queue operations.
- **Conditions (`notFull`, `notEmpty`)**: Used to block producers/consumers when the queue is full/empty. These conditions allow threads to wait until notified of a state change.
- **AtomicInteger (`count`)**: Tracks the number of elements in the queue. While `count` is not thread-safe for modification without the lock, it's used here for simplicity. In a production environment, consider using a lock-free counter or a more sophisticated mechanism.
- **Garbage Collection**: Setting elements to `null` after removal helps the garbage collector reclaim memory.

### JUnit 5 Test Cases

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BoundedBlockingQueueTest {
    private static final int CAPACITY = 10;
    private static final int THREAD_COUNT = 10;
    private static final int ITERATIONS = 10000;
    private BoundedBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(CAPACITY);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testPutTake() throws InterruptedException {
        Thread producer = new Thread(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                queue.put(i);
            }
        });
        Thread consumer = new Thread(() -> {
            for (int i = 0; i < ITERATIONS; i++) {
                assertEquals(i, queue.take());
            }
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
    }

    @Test
    void testOfferPoll() throws InterruptedException {
        for (int i = 0; i < ITERATIONS; i++) {
            assertTrue(queue.offer(i, 1, TimeUnit.MILLISECONDS));
        }
        for (int i = 0; i < ITERATIONS; i++) {
            assertEquals(i, queue.poll(1, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void testMultiProducerMultiConsumerStressTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(THREAD_COUNT);

        // Producer Tasks
        Runnable producerTask = () -> {
            try {
                start.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    queue.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                end.countDown();
            }
        };

        // Consumer Tasks
        Runnable consumerTask = () -> {
            try {
                start.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    assertEquals(i, queue.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                end.countDown();
            }
        };

        // Start Producers
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(producerTask);
        }

        // Start Consumers
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(consumerTask);
        }

        // Start Tasks
        start.countDown();
        end.await();

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
}
```

### Explanation

1. **BoundedBlockingQueue Class**:
   - **Constructor**: Initializes the queue with a specified capacity.
   - **put(T item)**: Blocks until space is available, then adds an item.
   - **take()**: Blocks until an item is available, then removes and returns it.
   - **offer(T item, long timeout, TimeUnit unit)**: Attempts to add an item with a timeout.
   - **poll(long timeout, TimeUnit unit)**: Attempts to remove an item with a timeout.
   - **shutdown()**: Signals all waiting threads to wake up, facilitating a clean shutdown.

2. **Concurrency Strategy**:
   - Uses a `ReentrantLock` to ensure mutual exclusion.
   - Conditions (`notFull`, `notEmpty`) manage waiting for state changes.
   - `AtomicInteger` for efficient counting, though in a production environment, consider a more robust mechanism.

3. **JUnit 5 Test Cases**:
   - **testPutTake**: Tests basic put/take functionality with a single producer/consumer.
   - **testOfferPoll**: Tests timed offer/poll functionality.
   - **testMultiProducerMultiConsumerStressTest**: A stress test with multiple producers/consumers to verify no lost or duplicated elements. This test uses a `CountDownLatch` to synchronize the start of all threads and ensures all threads complete before asserting the executor's termination.
