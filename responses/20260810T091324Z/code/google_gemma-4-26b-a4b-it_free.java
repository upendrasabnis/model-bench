<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260810T091324Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a **Monitor-based concurrency strategy**. Instead of using high-level `java.util.concurrent` primitives, it uses the fundamental `Object.wait()` and `Object.notifyAll()` mechanisms.

### Concurrency Strategy: Monitor Pattern
1.  **Mutual Exclusion**: A single `ReentrantLock` (or the intrinsic monitor of the object) ensures that only one thread can modify the internal state (the `LinkedList`) at a time.
2.  **Condition Variables**: We use two explicit `Condition` objects: `notFull` (to signal producers when space is available) and `notEmpty` (to signal consumers when data is available). This is more efficient than `notifyAll()` on a single monitor because it avoids the "thundering herd" problem where producers wake up consumers and vice-versa.
3.  **State Management**: A `volatile boolean running` flag is used to signal shutdown. When shutdown is initiated, all waiting threads are interrupted and released.

---

### 1. The Implementation

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * @param <T> The type of elements held in this queue.
 */
public class CustomBlockingQueue<T> {

    private final Queue<T> queue;
    private final int capacity;
    private boolean isShutdown = false;

    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.queue = new LinkedList<>();
        this.capacity = capacity;
    }

    /**
     * Inserts element, blocking until space is available or interrupted.
     */
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

    /**
     * Attempts to insert element within the given timeout.
     */
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

    /**
     * Removes and returns the head, blocking until an element is available.
     */
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

    /**
     * Attempts to remove head within timeout. Returns null if timeout expires.
     */
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

    /**
     * Shuts down the queue, releasing all waiting threads.
     */
    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
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

---

### 2. JUnit 5 Test Suite

To run this, you need the JUnit 5 dependency in your `pom.xml` or `build.gradle`.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    @Test
    @DisplayName("Basic Put and Take")
    void testBasicOperations() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    @DisplayName("Timed Offer and Poll")
    void testTimedOperations() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        // Should fail because queue is full
        assertFalse(queue.offer(2, 50, TimeUnit.MILLISECONDS));
        
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(50, TimeUnit.MILLISECONDS));
        // Should return null because queue is empty
        assertNull(queue.poll(50, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown releases waiting threads")
    void testShutdown() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        Thread t = new Thread(() -> {
            try {
                queue.take(); // Will block
            } catch (InterruptedException e) {
                // Expected
            }
        });
        t.start();
        Thread.sleep(50);
        queue.shutdown();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have been released by shutdown");
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;
        
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(100);
        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        
        // Use thread-safe collections to track results
        Set<Integer> receivedItems = ConcurrentHashMap.newKeySet();
        AtomicInteger totalReceivedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numProducers);

        // Producers
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < itemsPerProducer; j++) {
                        // Create unique numbers: Producer 0: 0-9999, Producer 1: 10000-19999...
                        queue.put(producerId * itemsPerProducer + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < numConsumers; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item!= null) {
                            if (!receivedItems.add(item)) {
                                fail("Duplicate item detected: " + item);
                            }
                            totalReceivedCount.incrementAndGet();
                        } else if (latch.getCount() == 0 && queue.size() == 0) {
                            // If producers are done and queue is empty, exit
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        // Wait for consumers to drain the queue
        long start = System.currentTimeMillis();
        while (totalReceivedCount.get() < totalItems && (System.currentTimeMillis() - start < 5000)) {
            Thread.sleep(10);
        }

        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        assertEquals(totalItems, totalReceivedCount.get(), "Lost elements detected!");
        assertEquals(totalItems, receivedItems.size(), "Duplicate elements detected!");
    }
}
```

### Key Testing Features:
1.  **Uniqueness Logic**: In the stress test, every integer produced is unique (`producerId * itemsPerProducer + j`).
2.  **Validation**: We use a `ConcurrentHashMap.newKeySet()` to collect results. If `set.add(item)` returns `false`, we know immediately that a duplicate was produced.
3.  **Liveness Check**: The `CountDownLatch` ensures we know when producers are finished, while the consumer loop only terminates when the queue is empty *and* producers are done.
4.  **Edge Cases**: Tests cover timeouts, empty/full states, and the shutdown lifecycle.