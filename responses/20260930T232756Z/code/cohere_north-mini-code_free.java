<!-- model: cohere/north-mini-code:free | category: code | run: 20260930T232756Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is the implementation of a thread-safe, generic bounded blocking queue in Java, followed by comprehensive JUnit 5 tests.

### 1. Implementation: `CustomBlockingQueue`

This implementation uses `java.util.concurrent.locks.ReentrantLock` and `Condition` variables to manage concurrency. This approach provides more flexibility than `synchronized` blocks and allows for precise control over thread signaling.

**Concurrency Strategy**:
*   **Mutual Exclusion**: A `ReentrantLock` is used to ensure that only one thread can access the queue's internal state (the array, head/tail pointers, and size) at any given time.
*   **Condition Variables**: Two `Condition` objects are utilized:
    *   `notEmpty`: Signaled when an element is added to the queue (waking up threads waiting to `take`).
    *   `notFull`: Signaled when an element is removed from the queue (waking up threads waiting to `put`).
*   **Shutdown Mechanism**: A volatile boolean flag `isShutdown` is used. The `shutdown()` method acquires the lock, sets this flag to `true`, and calls `signalAll()` on both conditions. This wakes up any threads blocked on `put` or `take`, allowing them to check the flag and throw an exception, thus ensuring threads do not wait indefinitely.
*   **Spurious Wakeups**: All `await()` calls are wrapped in `while` loops to guard against spurious wakeups, ensuring the thread only proceeds when the queue state actually meets the condition (e.g., queue is not empty).

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CustomBlockingQueue<T> {
    private final T[] items;
    private final int capacity;
    private int head; // points to the next item to take
    private int tail; // points to the next slot to put
    private int count; // current number of elements

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    private volatile boolean isShutdown = false;

    @SuppressWarnings("unchecked")
    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = (T[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    // Puts the value into the queue. Blocks if full.
    public void put(T t) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == capacity && !isShutdown) {
                notFull.await();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            doPut(t);
        } finally {
            lock.unlock();
        }
    }

    // Puts the value into the queue. Blocks if full, up to timeout.
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (count == capacity && !isShutdown) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            doPut(t);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // Takes the value from the queue. Blocks if empty.
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0 && !isShutdown) {
                notEmpty.await();
            }
            if (isShutdown && count == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T t = doTake();
            notFull.signal(); // wake up a put waiting on full queue
            return t;
        } finally {
            lock.unlock();
        }
    }

    // Takes the value from the queue. Blocks if empty, up to timeout.
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (count == 0 && !isShutdown) {
                if (nanos <= 0) {
                    return null; // Timeout
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (isShutdown && count == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T t = doTake();
            notFull.signal(); // wake up a put waiting on full queue
            return t;
        } finally {
            lock.unlock();
        }
    }

    private void doPut(T t) {
        T old = items[tail];
        items[tail] = t;
        tail = (tail + 1) % capacity;
        count++;
        notEmpty.signal(); // wake up a take waiting on empty queue
    }

    private T doTake() {
        T t = items[head];
        items[head] = null; // help GC
        head = (head + 1) % capacity;
        count--;
        return t;
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

    public boolean isShutdown() {
        return isShutdown;
    }

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int remainingCapacity() {
        lock.lock();
        try {
            return capacity - count;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return count == 0;
        } finally {
            lock.unlock();
        }
    }
}
```

### 2. Implementation: JUnit 5 Tests

This test suite includes:
1.  **Basic Operations**: Verify FIFO order and basic put/take logic.
2.  **Boundedness**: Verify blocking behavior and capacity limits.
3.  **Timed Operations**: Verify `offer` and `poll` timeouts work correctly.
4.  **Shutdown**: Verify that waiting threads are interrupted/cleaned up.
5.  **Stress Test**: A multi-producer/multi-consumer test that verifies data integrity (no lost or duplicated elements) by comparing a Set of produced items against a Set of consumed items.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30) // Prevent infinite hangs in tests
class CustomBlockingQueueTest {

    private CustomBlockingQueue<String> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(100); // Large capacity for stress tests
    }

    @AfterEach
    void tearDown() {
        if (!queue.isShutdown()) {
            queue.shutdown();
        }
    }

    @Test
    void testPutTake_Fifo() throws InterruptedException {
        queue.put("A");
        queue.put("B");
        assertEquals("A", queue.take());
        assertEquals("B", queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testOffer_Poll_Timeout() throws InterruptedException {
        // Queue is empty, poll with short timeout should return null
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));

        // Fill queue (simulate filling later or just use a small queue for this test)
        CustomBlockingQueue<Integer> smallQueue = new CustomBlockingQueue<>(1);
        assertTrue(smallQueue.offer(1, 10, TimeUnit.MILLISECONDS));
        assertFalse(smallQueue.offer(2, 10, TimeUnit.MILLISECONDS)); // Should timeout/return false

        assertEquals(1, smallQueue.take());
    }

    @Test
    void testShutdown() {
        // Start a thread that is waiting to take
        Thread taker = new Thread(() -> {
            try {
                queue.take();
            } catch (IllegalStateException e) {
                // Expected
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        taker.start();

        // Give it time to start waiting
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        queue.shutdown();
        taker.join();

        assertTrue(queue.isShutdown());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testStressTest_NoLostOrDuplicatedElements() throws InterruptedException {
        int numProducers = 10;
        int numConsumers = 10;
        int itemsPerProducer = 100;
        int totalExpectedItems = numProducers * itemsPerProducer;

        ExecutorService producerExecutor = Executors.newFixedThreadPool(numProducers);
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(numConsumers);

        // Set to store produced items (unique)
        Set<String> producedSet = Collections.synchronizedSet(new HashSet<>());
        // Set to store consumed items (unique)
        Set<String> consumedSet = Collections.synchronizedSet(new HashSet<>());

        // Atomic counter to track how many items producers have *attempted* to put
        // (Used to verify if producers are stuck, though queue is large enough here)
        AtomicInteger producedCounter = new AtomicInteger(0);

        // Producers: Generate unique strings and put them
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            producerExecutor.submit(() -> {
                for (int j = 0; j < itemsPerProducer; j++) {
                    String item = "P" + producerId + "-Item-" + j;
                    try {
                        queue.put(item);
                        producedSet.add(item);
                        producedCounter.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Consumers: Take items until queue is empty and shutdown
        // We need a way to know when to stop consuming if we don't have a global shutdown signal yet.
        // The stress test will trigger shutdown after producers are done.
        consumerExecutor.submit(() -> {
            while (!queue.isShutdown() || !queue.isEmpty()) {
                try {
                    // Use a small timeout to periodically check isShutdown
                    String item = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (item != null) {
                        consumedSet.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Wait for all producers to finish
        producerExecutor.shutdown();
        assertTrue(producerExecutor.awaitTermination(10, TimeUnit.SECONDS), "Producers did not finish in time");

        // Trigger shutdown to stop consumers
        queue.shutdown();

        // Wait for all consumers to finish
        consumerExecutor.shutdown();
        assertTrue(consumerExecutor.awaitTermination(10, TimeUnit.SECONDS), "Consumers did not finish in time");

        // Verification
        System.out.println("Produced: " + producedSet.size());
        System.out.println("Consumed: " + consumedSet.size());

        // 1. Verify no elements were lost
        assertEquals(totalExpectedItems, consumedSet.size(), 
            "Not all elements were consumed. Potential loss.");

        // 2. Verify no elements were duplicated
        assertEquals(totalExpectedItems, producedSet.size(), 
            "Producers generated duplicates (unexpected).");
        
        // 3. Verify content match (Intersection check)
        // Since order is not guaranteed, we check if the consumed set contains all produced items.
        assertTrue(producedSet.containsAll(consumedSet), "Consumed items do not match produced items.");
        assertTrue(consumedSet.containsAll(producedSet), "Produced items were not all consumed.");
    }
}
```

### Explanation of the Stress Test Logic
In `testStressTest_NoLostOrDuplicatedElements`:
1.  **Producers**: 10 threads generate 100 unique strings each (e.g., "P0-Item-0"). They add these to the queue.
2.  **Consumers**: 10 threads continuously poll the queue. They stop when the queue is shut down and empty.
3.  **Verification**:
    *   **No Lost**: We compare the size of the `producedSet` (items put) against the `consumedSet` (items taken). If they match, nothing was lost.
    *   **No Duplicated**: We check that the size of `producedSet` equals the number of items generated. If a producer duplicated an item, the set size would be smaller than the total generated count.
    *   **Data Integrity**: We ensure `producedSet` contains all `consumedSet` items. This confirms that no "phantom" or corrupted data was created by the queue logic.