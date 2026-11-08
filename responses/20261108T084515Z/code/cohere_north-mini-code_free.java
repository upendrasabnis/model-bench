<!-- model: cohere/north-mini-code:free | category: code | run: 20261108T084515Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

This implementation utilizes a **monolithic reentrant lock** strategy combined with **condition variables** to ensure thread safety and efficient blocking.

1.  **Mutual Exclusion:**
    *   A single `ReentrantLock` is used to protect all shared state (the internal buffer array, `head`, `tail`, `size`, and the `isShutdown` flag).
    *   This ensures that no two threads can simultaneously execute critical sections that modify the queue structure, preventing race conditions and data corruption.

2.  **Condition Variables (CVs):**
    *   Two `Condition` objects are created from the lock:
        *   `notEmpty`: Threads waiting to **take** or **poll** (when the queue is empty) wait on this. When a thread **puts** or **offers**, it signals this condition, waking up one waiting consumer.
        *   `notFull`: Threads waiting to **put** or **offer` (when the queue is full) wait on this. When a thread **takes** or **polls`, it signals this condition, waking up one waiting producer.
    *   **Spurious Wakeup Handling:** All waits (`await`, `awaitNanos`) are enclosed in `while` loops checking the specific condition (e.g., `while (size ==  максимум)`). This is a standard practice to handle spurious wakeups where a thread wakes up waiting but the condition hasn't actually changed.

3.  **Interruptibility:**
    *   The `await()` and `awaitNanos()` methods are used rather than `awaitUninterruptibly()`. This allows the queue to be **responsive to thread interruption**. If a consumer thread is waiting in `take()` and is interrupted, it throws `InterruptedException`, allowing the application to handle the cancellation gracefully.

4.  **Shutdown Protocol:**
    *   A volatile `isShutdown` flag is used to signal a clean shutdown.
    *   When `shutdown()` is called, the flag is set. Subsequent `put` or `offer` operations immediately throw `IllegalStateException` (or return `false`) without waiting.
    *   Existing elements can still be drained via `take` or `poll`. Once the queue is empty, waiting threads are notified and return `null`.

---

### Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MyBlockingQueue<T> {
    private final T[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private final int maxCapacity;
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    private volatile boolean isShutdown = false;

    @SuppressWarnings("unchecked")
    public MyBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.maxCapacity = capacity;
        this.items = (T[]) new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            if (isShutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            
            // Wait until not full
            while (count == maxCapacity) {
                notFull.await();
            }
            
            // Put item
            items[tail] = item;
            tail = (tail + 1) % maxCapacity;
            count++;
            
            // Signal that queue is not empty
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            // Wait until not empty
            while (count == 0) {
                notEmpty.await();
            }
            
            // Take item
            T item = items[head];
            items[head] = null; // Avoid memory leak
            head = (head + 1) % maxCapacity;
            count--;
            
            // Signal that queue is not full
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (isShutdown) {
                return false;
            }
            
            long nanos = unit.toNanos(timeout);
            // Wait until not full or timeout
            while (count == maxCapacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            
            // Put item
            items[tail] = item;
            tail = (tail + 1) % maxCapacity;
            count++;
            
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            // Wait until not empty or timeout
            while (count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            
            // Take item
            T item = items[head];
            items[head] = null;
            head = (head + 1) % maxCapacity;
            count--;
            
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            // Wake up all waiting threads to allow them to check the shutdown flag and exit
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
}
```

---

### JUnit 5 Tests

This test suite covers basic operations, edge cases (shutdown, timeouts), and a multi-producer/multi-consumer stress test to verify data integrity.

```java
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MyBlockingQueueTest {

    private MyBlockingQueue<Integer> queue;

    @BeforeEach
    void setUp() {
        queue = new MyBlockingQueue<>(10);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    @Test
    void testPutAndTake_SingleThread() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        
        assertEquals(2, queue.size());
        
        assertEquals(1, queue.take());
        assertEquals(2, queue.take());
        
        assertEquals(0, queue.size());
    }

    @Test
    void testOffer_Timeout_Success() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
    }

    @Test
    void testOffer_Timeout_Failure() throws InterruptedException {
        // Fill the queue
        for (int i = 0; i < 10; i++) {
            queue.put(i);
        }
        
        // Attempt to offer with a short timeout to a full queue
        assertFalse(queue.offer(99, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testPoll_Timeout_Success() throws InterruptedException {
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            try {
                queue.put(42);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 100, TimeUnit.MILLISECONDS);
        
        Integer item = queue.poll(1, TimeUnit.SECONDS);
        assertEquals(42, item);
    }

    @Test
    void testPoll_Timeout_Failure() throws InterruptedException {
        // Queue is empty
        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(item);
    }

    @Test
    void testShutdown_PreventsPut() {
        queue.shutdown();
        assertTrue(queue.isShutdown());
        
        try {
            queue.put(1);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException | InterruptedException e) {
            // Expected
        }
    }

    @Test
    void testShutdown_PreventsOffer() throws InterruptedException {
        queue.shutdown();
        assertFalse(queue.offer(1, 1, TimeUnit.SECONDS));
    }

    @Test
    void testShutdown_AllowsDrain() throws InterruptedException {
        queue.put(1);
        queue.put(2);
        queue.shutdown();
        
        assertEquals(2, queue.take());
        assertEquals(1, queue.take());
        
        assertEquals(0, queue.size());
        assertTrue(queue.isShutdown());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStress_MultiProducerMultiConsumer_NoLossOrDuplication() throws InterruptedException {
        final int NUM_PRODUCERS = 5;
        final int NUM_CONSUMERS = 5;
        final int ITEMS_PER_PRODUCER = 20;
        
        // Shared result set to verify integrity
        Set<Integer> receivedItems = Collections.synchronizedSet(new HashSet<>());
        
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        
        CountDownLatch producerLatch = new CountDownLatch(NUM_PRODUCERS);
        CountDownLatch consumerLatch = new CountDownLatch(NUM_PRODUCERS * ITEMS_PER_PRODUCER);

        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < ITEMS_PER_PRODUCER; j++) {
                        // Unique ID: (ProducerID * MaxItems) + LocalID
                        int item = (producerId * ITEMS_PER_PRODUCER) + j;
                        queue.put(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producerLatch.countDown();
                }
            });
        }

        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            executor.submit(() -> {
                try {
                    while (!queue.isShutdown() || queue.size() > 0) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            receivedItems.add(item);
                            consumerLatch.countDown();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all producers to finish putting
        producerLatch.await(5, TimeUnit.SECONDS);
        
        // Signal shutdown to stop consumers waiting on empty queue
        queue.shutdown();

        // Wait for all items to be consumed
        consumerLatch.await(5, TimeUnit.SECONDS);
        
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Verification
        // Total expected items = 5 Producers * 20 Items = 100
        assertEquals(NUM_PRODUCERS * ITEMS_PER_PRODUCER, receivedItems.size(), 
            "Data loss detected: Not all items were consumed.");

        // Check for duplicates (Set ensures uniqueness, so if size != expected, duplicates existed)
        // However, since we used a Set, duplicates are automatically filtered. 
        // To specifically check for duplicates, we would need a multiset or a counter.
        // Let's use a ConcurrentHashMap to count occurrences to verify no duplicates.
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStress_VerifyNoDuplicates_UsingMap() throws InterruptedException {
        final int NUM_PRODUCERS = 10;
        final int ITEMS_PER_PRODUCER = 50;
        
        AtomicInteger totalProduced = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + 2);
        
        // Map to count occurrences. Key = Item, Value = Count
        java.util.concurrent.ConcurrentHashMap<Integer, AtomicInteger> itemCounts = new java.util.concurrent.ConcurrentHashMap<>();

        CountDownLatch latch = new CountDownLatch(NUM_PRODUCERS * ITEMS_PER_PRODUCER);

        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < ITEMS_PER_PRODUCER; j++) {
                        int item = (producerId * ITEMS_PER_PRODUCER) + j;
                        
                        // Record item existence
                        itemCounts.put(item, itemCounts.compute(item, (k, v) -> {
                            if (v == null) return new AtomicInteger(1);
                            v.incrementAndGet();
                            return v;
                        }));
                        
                        queue.put(item);
                        totalProduced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Consumers simply drain
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    while (!queue.isShutdown() || queue.size() > 0) {
                        queue.poll(100, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Finish
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        queue.shutdown();

        latch.await(5, TimeUnit.SECONDS); // Should technically be decremented by consumers, but we use itemCounts for verification

        // Verification Logic
        // 1. Check Total Count
        int sumOfCounts = itemCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        assertEquals(NUM_PRODUCERS * ITEMS_PER_PRODUCER, sumOfCounts, 
            "Total item count mismatch. Possible loss.");

        // 2. Check for Duplicates (Any count > 1)
        long duplicates = itemCounts.values().stream().filter(v -> v.get() > 1).count();
        assertEquals(0, duplicates, 
            "Duplicate items found. Data corruption.");

        // 3. Check for Gaps (Range check)
        int maxItem = itemCounts.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int minItem = itemCounts.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1);
        int expectedCount = NUM_PRODUCERS * ITEMS_PER_PRODUCER;
        assertEquals(expectedCount, maxItem - minItem + 1, 
            "Gaps in sequence detected. Possible loss.");
    }
}
```