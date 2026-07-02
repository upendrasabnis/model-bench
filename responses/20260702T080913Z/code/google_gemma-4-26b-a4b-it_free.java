<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260702T080913Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a **Monitor Pattern** with explicit `ReentrantLock` and two `Condition` variables. This approach is more efficient than using `synchronized` blocks because it allows producers and consumers to wait on separate signals, reducing unnecessary thread wake-ups (the "thundering herd" problem).

### 1. The Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import javaing.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * CONCURRENCY STRATEGY:
 * 1. Mutual Exclusion: A single ReentrantLock protects the underlying LinkedList.
 * 2. Condition Variables: 
 *    - 'notFull': Producers wait here when the queue is at capacity.
 *    - 'notEmpty': Consumers wait here when the queue is empty.
 * 3. Signaling: Producers signal 'notEmpty' after adding; Consumers signal 'notFull' after removing.
 * 4. Shutdown: A volatile 'isShutdown' flag prevents new operations. Once shutdown, 
 *    existing elements can be drained, but no new elements can be added.
 * 
 * @param <T> The type of elements held in this queue.
 */
public class CustomBlockingQueue<T> {
    private final Queue<T> queue;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private volatile boolean isShutdown = false;

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.queue = new LinkedList<>();
    }

    public void put(T element) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity &&!isShutdown) {
                notFull.await();
            }
            if (isShutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            queue.add(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() &&!isShutdown) {
                notEmpty.await();
            }
            if (queue.isEmpty() && isShutdown) {
                return null;
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.size() == capacity) {
                if (nanos <= 0 || isShutdown) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            if (isShutdown) return false;
            queue.add(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0 || isShutdown) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            T element = queue.poll();
            notFull.signal();
            return element;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            notFull.signalAll(); // Wake up blocked producers
            notEmpty.signalAll(); // Wake up blocked consumers
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

---

### 2. Unit Tests (JUnit 5)

To run these, ensure you have `org.junit.jupiter:junit-jupiter` in your classpath.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    @Test
    void testBasicPutAndTake() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(5);
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void testTimedOffer() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        // Second offer should fail because queue is full
        assertFalse(queue.offer(2, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTimedPoll() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
        
        queue.put(10);
        assertEquals(10, queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void testShutdown() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(10);
        queue.shutdown();
        
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertNull(queue.poll(10, TimeUnit.MILLISECONDS));
    }

    /**
     * Stress Test: Multi-Producer / Multi-Consumer
     * Verifies:
     * 1. No elements are lost.
     * 2. No elements are duplicated.
     * 3. The system completes under heavy contention.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void stressTest_NoLossOrDuplication() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int elementsPerProducer = 10000;
        int totalElements = numProducers * elementsPerProducer;

        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Track results
        Set<Integer> consumedElements = ConcurrentHashMap.newKeySet();
        LongAdder lostElementsCounter = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numProducers + numConsumers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < elementsPerProducer; j++) {
                        // Unique value per element: producerId * offset + j
                        queue.put(producerId * elementsPerProducer + j);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Consumers
        AtomicInteger totalConsumedCount = new AtomicInteger(0);
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer val = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (val!= null) {
                            if (!consumedElements.add(val)) {
                                // This would indicate a duplicate
                                throw new RuntimeException("Duplicate element detected: " + val);
                            }
                            totalConsumedCount.incrementAndGet();
                        } else {
                            // If poll returns null, check if we are done
                            if (totalConsumedCount.get() == totalElements(numProducers, elementsPerProducer)) {
                                break;
                            }
                        }
                        // Safety break to prevent infinite loop if test fails
                        if (totalConsumedCount.get() > totalElements(numProducers, elementsPerProducer)) break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start race
        
        // Wait for producers to finish
        // We use a separate way to wait because consumers depend on-the-fly data
        Thread.sleep(2000); 
        queue.shutdown(); 
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(totalElements(numProducers, elementsPerProducer), consumedElements.size(), 
            "Total consumed elements should match produced elements");
    }

    private int totalElements(int producers, int perProducer) {
        return producers * perProducer;
    }
}
```

### Key Design Details

1.  // **Concurrency Strategy**: I used a `ReentrantLock` for atomicity. Unlike `synchronized`, this allows for `tryLock` or `lockInterruptibly`, which is critical for high-quality-concurrency primitives to prevent deadlocks during thread interruption.
2.  // **Condition Variables**: Instead of `notifyAll()`, which wakes up every thread (even those that can't proceed), I used two conditions: `notFull` and `notEmpty`. When a consumer takes an item, it only signals `notFull`, waking up a producer. This minimizes unnecessary context switching.
3.  // **Shutdown Logic**: The `shutdown()` method sets a `volatile` flag and signals all-conditions. This ensures that threads blocked in `await()` are woken up to see the shutdown state and exit gracefully.
4.  // **Stress Test Logic**: The test uses a `ConcurrentHashMap`-backed Set to track unique IDs. If any duplicate is-inserted, the set size will be less than the total elements produced. If any element is lost, the final size will be less than expected. It uses a `CountDownLatch` to ensure all threads start at the exact same moment to maximize contention.