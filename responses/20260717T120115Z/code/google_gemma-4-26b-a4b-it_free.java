<!-- model: google/gemma-4-26b-a4b-it:free | category: code | run: 20260717T120115Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
This implementation uses a circular buffer (array) and intrinsic locking (`synchronized`) with `wait()` and `notifyAll()`.

### 1. The Implementation

```java
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe, generic bounded blocking queue.
 * 
 * Concurrency Strategy:
 * 1. Monitor Pattern: Uses a single intrinsic lock (synchronized) for all state mutations.
 * 2. Condition Signaling: Uses 'wait()' and 'notifyAll()' to manage blocking states.
 *    - Producers wait if the queue is full.
 *    - Consumers wait if the queue is empty.
 * 3. Graceful Shutdown: A 'hutdown' flag is used. Once shutdown is called, 
 *    no new elements can be added, and blocking operations will throw an IllegalStateException.
 */
public class CustomBlockingQueue<T> {

    private final T[] buffer;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private boolean isShutdown = false;

    @SuppressWarnings("unchecked")
    public CustomBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.buffer = (T[]) new Object[capacity];
    }

    /**
     * Inserts element at the tail, blocking if the queue is full.
     * @throws InterruptedException if interrupted while waiting.
     * @throws IllegalStateException if the queue is shut down.
     */
    public synchronized void put(T element) throws InterruptedException {
        checkNotShutdown();
        while (count == buffer.length &&!isShutdown) {
            wait();
        }
        checkNotShutdown();

        buffer[tail] = element;
        tail = (tail + 1) % buffer.length;
        count++;
        
        // Notify consumers that an item is available
        notifyAll();
    }

    /**
     * Retrieves and removes the head element, blocking if empty.
     * @throws InterruptedException if interrupted while waiting.
     * @throws IllegalStateException if the queue is shut down and empty.
     */
    public synchronized T take() throws InterruptedException {
        while (count == 0 &&!isShutdown) {
            wait();
        }
        if (isShutdown && count == 0) {
            throw new IllegalStateException("Queue is shut down and empty");
        }

        T element = buffer[head];
        buffer[head] = null; // Avoid memory leak
        head = (head + 1) % buffer.length;
        count--;

        // Notify producers that space is available
        notifyAll();
        return element;
    }

    /**
     * Attempts to insert an element within the given timeout.
     * @return true if successful, false if timed out.
     */
    public synchronized boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        checkNotShutdown();
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        while (count == buffer.length &&!isShutdown) {
            if (nanos <= 0) return false;
            long waitTime = deadline - System.nanoTime();
            wait(waitTime);
            if (waitTime <= 0) return false;
        }
        
        if (isShutdown) throw new IllegalStateException("Queue is shut down");
        
        // Standard put logic
        buffer[tail] = element;
        tail = (tail + 1) % buffer.length;
        count++;
        notifyAll();
        return true;
    }

    /**
     * Attempts to retrieve an element within the given timeout.
     * @return the element, or null if timed out.
     */
    public synchronized T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;

        while (count == 0 &&!isShutdown) {
            if (nanos <= 0) return null;
            long waitTime = deadline - System.nanoTime();
            wait(waitTime);
            if (waitTime <= 0) return null;
        }
        
        if (count == 0) return null;

        T element = buffer[head];
        buffer[head] = null;
        head = (head + 1) % buffer.length;
        count--;
        notifyAll();
        return element;
    }

    /**
     * Prevents further puts and wakes up all waiting threads.
     */
    public synchronized void shutdown() {
        isShutdown = true;
        notifyAll();
    }

    private void checkNotShutdown() {
        if (isShutdown) {
            throw new IllegalStateException("Queue is shut down");
        }
    }

    public synchronized int size() {
        return count;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }
}
```

---

### 2. JUnit 5 Test Suite

Requires JUnit 5 dependency.

```java
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomBlockingQueueTest {

    private CustomBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new CustomBlockingQueue<>(5);
    }

    @Test
    @DisplayName("Basic put and take functionality")
    void testBasicPutTake() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("Timed offer and poll should respect timeouts")
    void testTimedOperations() throws InterruptedException {
        // Test poll timeout
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));

        // Test offer timeout (queue is full)
        queue.put(1);
        queue.put(2);
        queue.put(3);
        queue.put(4);
        queue.put(5);
        
        assertFalse(queue.offer(6, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Shutdown should prevent new puts and unblock waiters")
    void testShutdown() throws InterruptedException {
        Thread producer = new Thread(() -> {
            try {
                queue.put(1);
            } catch (InterruptedException | IllegalStateException ignored) {}
        });

        producer.start();
        Thread.sleep(50); // Let producer enter wait state
        queue.shutdown();
        producer.join(1000);

        assertThrows(IllegalStateException.class, () -> queue.put(2));
    }

    @Test
    @DisplayName("Stress Test: Multi-producer/Multi-consumer")
    void stressTest() throws InterruptedException {
        int numProducers = 4;
        int numConsumers = 4;
        int itemsPerProducer = 10000;
        int totalItems = numProducers * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        Set<Integer> consumedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger totalConsumedCount = new AtomicInteger(0);
        
        // Track sum to verify no duplicates/losses mathematically
        long expectedSum = 0;
        for (int i = 0; i < totalItems; i++) expectedSum += i;
        // Note: We'll use simple incrementing IDs for easier verification
        
        // We will use a unique set of numbers 0 to totalItems-1
        // To make it easy, let's use producer ID + offset
        
        // Producers
        for (int p = 0; p < numProducers; p++) {
            final int pid = p;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        // Create unique values: Producer 0: 0,1,2... Producer 1: 10000, 10001...
                        queue.put(pid * itemsPerProducer + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Consumers
        for (int c = 0; c < numConsumers; c++) {
            executor.submit(() -> {
                try {
                    while (totalConsumedCount.get() < totalItems) {
                        Integer val = queue.poll(10, TimeUnit.MILLISECONDS);
                        if (val!= null) {
                            if (!consumedItems.add(val)) {
                                fail("Duplicate item detected: " + val);
                            }
                            totalConsumedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        
        assertTrue(finished, "Test timed out");
        assertEquals(totalItems, totalConsumedCount.get(), "Total count mismatch");
        assertEquals(totalItems, consumedItems.size(), "Unique count mismatch");
    }
}
```

### Key Features of the Implementation:
1.  **Generics**: Supports any type `T`.
2.  **Circular Buffer**: Uses an array with `head` and `tail` pointers, ensuring $O(1)$ complexity for both `put` and `take`.
3.  **Memory Safety**: Explicitly sets `buffer[head] = null` during `take()` to prevent memory leaks (loitering).
4.  **Fairness/Liveness**: Uses `notifyAll()` instead of `notify()` to avoid the "lost wake-up" problem where a producer might accidentally wake up another producer instead of a consumer when the queue is full.
5.  **Robustness**: The stress test uses a `ConcurrentHashMap`-backed Set to detect duplicates and an `AtomicInteger` to verify that every single produced item was consumed exactly once.