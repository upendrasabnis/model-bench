<!-- model: google/gemma-4-31b-it:free | category: code | run: 20260918T144055Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
Here is a complete implementation of a generic bounded blocking queue.

### Concurrency Strategy
The implementation uses a **Monitor-based synchronization strategy** with a `ReentrantLock` and two `Condition` variables:
1.  **`notFull`**: Producers wait on this condition when the queue is at capacity.
2.  **`notEmpty`**: Consumers wait on this condition when the queue is empty.

**Key Design Decisions:**
-   **Circular Buffer**: A generic array `T[]` is used as a circular buffer to avoid $O(n)$ shifts during removals.
-   **Explicit Locking**: `ReentrantLock` is used instead of `synchronized` blocks to allow for the use of multiple `Condition` variables, which prevents the "signal-all" thundering herd problem (producers only wake consumers and vice versa).
-   **Shutdown Mechanism**: A `volatile boolean` flag `isShutdown` is used. When `shutdown()` is called, all waiting threads are interrupted/signaled to prevent deadlocks during application termination.
-   **Generics**: Since Java doesn't allow `new T[capacity]`, an `Object[]` array is used and cast to `T`, with `@SuppressWarnings("unchecked")` handled at the access point.

---

### Implementation: `CustomBlockingQueue.java`

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CustomBlockingQueue<T> {
    private final T[] buffer;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private volatile boolean isShutdown = false;

    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    @SuppressWarnings("unchecked")
    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.buffer = (T[]) new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        if (item == null) throw new NullPointerException();
        lock.lockInterruptibly();
        try {
            while (size == buffer.length && !isShutdown) {
                notFull.await();
            }
            if (isShutdown) throw new IllegalStateException("Queue is shutdown");
            
            enqueue(item);
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (size == 0 && !isShutdown) {
                notEmpty.await();
            }
            if (isShutdown && size == 0) return null;
            
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        if (item == null) throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (size == buffer.length) {
                if (nanos <= 0 || isShutdown) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (size == 0) {
                if (nanos <= 0 || isShutdown) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

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

    private void enqueue(T item) {
        buffer[tail] = item;
        tail = (tail + 1) % buffer.length;
        size++;
        notEmpty.signal();
    }

    private T dequeue() {
        T item = buffer[head];
        buffer[head] = null; // GC support
        head = (head + 1) % buffer.length;
        size--;
        notFull.signal();
        return item;
    }

    public int size() {
        lock.lock();
        try { return size; } finally { lock.unlock(); }
    }
}
```

---

### Tests: `CustomBlockingQueueTest.java`

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class CustomBlockingQueueTest {

    @Test
    void testBasicPutTake() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
    }

    @Test
    void testTimedOfferPoll() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        assertTrue(queue.offer(1, 100, TimeUnit.MILLISECONDS));
        assertFalse(queue.offer(2, 100, TimeUnit.MILLISECONDS), "Should timeout");
        
        assertEquals(1, queue.poll(100, TimeUnit.MILLISECONDS));
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS), "Should timeout");
    }

    @Test
    void testShutdown() throws InterruptedException {
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(1);
        Thread t = new Thread(() -> {
            try { queue.take(); } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(100);
        queue.shutdown();
        t.join(1000);
        assertFalse(t.isAlive(), "Thread should have woken up and terminated");
    }

    @Test
    @DisplayName("Stress Test: Multi-Producer Multi-Consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 5;
        int numConsumers = 5;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;
        
        CustomBlockingQueue<Integer> queue = new CustomBlockingQueue<>(100);
        ConcurrentHashMap<Integer, Integer> counts = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + numConsumers);
        AtomicInteger consumedCount = new AtomicInteger(0);

        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        // Unique value: producerId * offset + i
                        queue.put(producerId * itemsPerProducer + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    while (consumedCount.get() < totalItems) {
                        Integer val = queue.poll(10, TimeUnit.MILLISECONDS);
                        if (val != null) {
                            counts.merge(val, 1, Integer::sum);
                            consumedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // Go!
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        
        assertTrue(completed, "Test timed out");
        assertEquals(totalItems, counts.size(), "Should have received all unique elements");
        counts.values().forEach(count -> assertEquals(1, count, "Duplicate element detected"));
    }
}
```

### Complexity Analysis
-   **Time Complexity**: All primary operations (`put`, `take`, `offer`, `poll`) are $O(1)$.
-   **Space Complexity**: $O(C)$ where $C$ is the capacity of the queue.
-   **Thread Safety**: Guaranteed by `ReentrantLock`. Blocking is handled via `Condition` variables to ensure efficient CPU usage (no busy-waiting).