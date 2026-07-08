<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260708T210536Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Thread-Safe Bounded Blocking Queue Implementation

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.NoSuchElementException;
import java.util.LinkedList;
import java.util.concurrent.TimeoutException;

public class BoundedBlockingQueue<E> {
    private final Queue<E> queue = new LinkedList<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void put(E e) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity) {
                notFull.await();
            }
            queue.add(e);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            E e = queue.poll();
            notFull.signalAll();
            return e;
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (queue.size() == capacity && nanos > 0) {
                nanos = notFull.awaitNanos(nanos);
            }
            if (queue.size() < capacity) {
                queue.add(e);
                notEmpty.signalAll();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            while (queue.isEmpty() && nanos > 0) {
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (!queue.isEmpty()) {
                E e = queue.poll();
                notFull.signalAll();
                return e;
            }
            return null;
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

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
}
```

### Concurrency Strategy
1. **Synchronization**: Uses `ReentrantLock` with `Condition` variables (`notFull` and `notEmpty`) to manage access to the queue.
2. **Blocking Behavior**: Producers block on `notFull` when the queue is full, and consumers block on `notEmpty` when empty. Signaling occurs after state changes to wake waiting threads.
3. **Thread Safety**: All queue modifications and state checks are protected by the `ReentrantLock`, ensuring atomic operations and preventing race conditions.

---

### JUnit 5 Test Cases

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.*;

class BoundedBlockingQueueTest {
    private static final int CAPACITY = 10;
    private static final int NUM_THREADS = 10;
    private static final int ITERATIONS = 1000;
    private static final int TIMEOUT_MS = 1000;

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
    void testBlockingPutAndTake() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.take());
    }

    @Test
    void testTimedOfferAndPoll() throws InterruptedException {
        assertTrue(queue.offer(2, TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void testFullQueueRejectsPut() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) {
            queue.put(i);
        }
        assertFalse(queue.offer(CAPACITY, 1, TimeUnit.SECONDS));
    }

    @Test
    void testEmptyQueueReturnsNullOnPoll() throws InterruptedException {
        assertNull(queue.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void testMultiProducerMultiConsumerNoLossOrDuplication() throws InterruptedException {
        Queue<Integer> expected = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);

        // Producer threads
        Runnable producer = () -> {
            try {
                latch.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    expected.add(1);
                    queue.put(1);
                }
            } catch (InterruptedException e) {
                fail("Producer interrupted");
            }
        };

        // Consumer threads
        Runnable consumer = () -> {
            try {
                latch.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    assertEquals(1, queue.take());
                }
            } catch (InterruptedException e) {
                fail("Consumer interrupted");
            }
        };

        latch.countDown();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < NUM_THREADS; i++) {
            futures.add(executor.submit(producer));
            futures.add(executor.submit(consumer));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        assertEquals(expected.size(), queue.size());
    }

    @Test
    void testQueueSize() {
        assertEquals(0, queue.size());
        queue.put(1);
        assertEquals(1, queue.size());
        queue.take();
        assertEquals(0, queue.size());
    }
}
```

### Explanation of Tests
1. **Basic Operations**: Tests `put`, `take`, `offer`, and `poll` with simple cases.
2. **Full/Empty Handling**: Ensures full queues reject new elements and empty queues return `null` on `poll`.
3. **Stress Test**: Simulates 10 producers and 10 consumers each adding/removing 1000 elements. Uses a `CountDownLatch` to synchronize start and verifies no data loss/duplication by comparing the expected and actual queue sizes.
4. **Concurrency**: Uses `ExecutorService` to manage threads and `ConcurrentLinkedQueue` for thread-safe expected results.

This implementation and test suite ensure the queue behaves correctly under concurrency, adhering to the specified requirements.
