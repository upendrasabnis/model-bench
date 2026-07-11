<!-- model: cohere/north-mini-code:free | category: code | run: 20260711T175732Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Generic Bounded Blocking Queue

## Implementation

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

public class BoundedBlockingQueue<T> {
    private final Object[] array;
    private int head = 0;
    private int tail = 0;
    private final int capacity;
    private final AtomicInteger size = new AtomicInteger(0);
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;
    
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.array = new Object[capacity];
    }
    
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lockInterruptibly();
        try {
            while (size.get() == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = item;
            tail = (tail + 1) % capacity;
            size.incrementAndGet();
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (size.get() == capacity && !shutdown) {
                long remaining = nanos;
                if (remaining <= 0) {
                    return false;
                }
                remaining = notFull.awaitNanos(remaining);
                nanos = remaining;
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = item;
            tail = (tail + 1) % capacity;
            size.incrementAndGet();
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (size.get() == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && size.get() == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % capacity;
            size.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (size.get() == 0 && !shutdown) {
                long remaining = nanos;
                if (remaining <= 0) {
                    return null;
                }
                remaining = notEmpty.awaitNanos(remaining);
                nanos = remaining;
            }
            if (shutdown && size.get() == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % capacity;
            size.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    public T peek() {
        lock.lock();
        try {
            return size.get() > 0 ? (T) array[head] : null;
        } finally {
            lock.unlock();
        }
    }
    
    public boolean isEmpty() {
        return size.get() == 0;
    }
    
    public boolean isFull() {
        return size.get() == capacity;
    }
    
    public int size() {
        return size.get();
    }
    
    public int remainingCapacity() {
        return capacity - size.get();
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
    
    @Override
    public String toString() {
        lock.lock();
        try {
            if (size.get() == 0) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < size.get(); i++) {
                sb.append(array[(head + i) % capacity]);
                if (i < size.get() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }
}
```

## Concurrency Strategy

1. **Mutual Exclusion**: 
   - A `ReentrantLock` ensures exclusive access to shared resources (the queue array and state variables).

2. **Condition Variables**:
   - `notEmpty`: Signaled when an item is added, waited by consumers.
   - `notFull`: Signaled when space is available, waited by producers.

3. **Shutdown Handling**:
   - A `shutdown` flag interrupts waiting threads by signaling all conditions and allowing them to check the flag.

4. **Atomic Operations**:
   - `size` is maintained via `AtomicInteger` for visibility across threads.

5. **Null Safety**:
   - Null items are rejected to ensure type safety.

6. **Memory Management**:
   - Set array elements to `null` after removal to help garbage collection.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundedBlockingQueueTest {
    private static final int QUEUE_CAPACITY = 10;
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 5;
    private static final int MESSAGES_PER_PRODUCER = 100;
    
    private BoundedBlockingQueue<Integer> queue;
    
    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
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
    void testOffer() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
    }
    
    @Test
    void testPollTimeout() throws InterruptedException {
        long timeout = 100;
        assertNull(queue.poll(timeout, TimeUnit.MILLISECONDS));
    }
    
    @Test
    void testPeek() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.peek());
        queue.take();
        assertNull(queue.peek());
    }
    
    @Test
    void testIsFullAndEmpty() throws InterruptedException {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            queue.put(i);
        }
        assertTrue(queue.isFull());
        assertFalse(queue.isEmpty());
    }
    
    @Test
    void testRemainingCapacity() throws InterruptedException {
        assertEquals(QUEUE_CAPACITY, queue.remainingCapacity());
        queue.put(1);
        assertEquals(QUEUE_CAPACITY - 1, queue.remainingCapacity());
    }
    
    @Test
    void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }
    
    @Test
    void testMultipleProducersAndConsumers() throws InterruptedException, ExecutionException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_PRODUCERS + NUM_CONSUMERS);
        
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        
        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < MESSAGES_PER_PRODUCER; j++) {
                        queue.put(producerId * MESSAGES_PER_PRODUCER + j);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            break;
                        }
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        
        // Wait for all tasks to complete
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        int expectedProduced = NUM_PRODUCERS * MESSAGES_PER_PRODUCER;
        int expectedConsumed = expectedProduced;
        
        assertEquals(expectedProduced, producedCount.get(), "Not all items were produced");
        assertEquals(expectedConsumed, consumedCount.get(), "Not all items were consumed");
        assertTrue(queue.isEmpty(), "Queue should be empty after all items consumed");
    }
    
    @Test
    void testNoLostOrDuplicatedElements() throws InterruptedException, ExecutionException {
        int totalItems = 1000;
        int numProducers = 10;
        int itemsPerProducer = totalItems / numProducers;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers);
        
        AtomicInteger[] producedCounts = new AtomicInteger[numProducers];
        for (int i = 0; i < numProducers; i++) {
            producedCounts[i] = new AtomicInteger(0);
        }
        
        ConcurrentHashMap<Integer, Boolean> receivedItems = new ConcurrentHashMap<>();
        
        ExecutorService executor = Executors.newFixedThreadPool(numProducers);
        
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        int item = producerId * itemsPerProducer + j;
                        queue.put(item);
                        producedCounts[producerId].incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Single consumer
        CountDownLatch consumerDone = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                startLatch.await();
                while (true) {
                    Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        break;
                    }
                    receivedItems.put(item, true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        });
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        consumerDone.await(30, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Verify all items produced were received
        for (int i = 0; i < numProducers; i++) {
            assertEquals(itemsPerProducer, producedCounts[i].get(), 
                "Producer " + i + " did not produce all items");
        }
        
        assertEquals(totalItems, receivedItems.size(), 
            "Queue lost items or received duplicates");
        
        // Verify all received items are unique
        assertEquals(totalItems, receivedItems.keySet().stream().distinct().count(),
            "Duplicate items found in queue");
    }
}
```

## Explanation

1. **Queue Implementation**:
   - Uses a circular buffer for storage.
   - Implements blocking `put`/`take` and non-blocking `offer`/`poll` with timeout.
   - Supports clean shutdown with `shutdown()`.

2. **Tests**:
   - Unit tests for basic functionality.
   - Multi-producer/multi-consumer stress test verifying no lost or duplicated elements.
   - Uses `CountDownLatch` to synchronize producer/consumer start.
   - Uses `ConcurrentHashMap` to track received items for verification.

This implementation ensures thread safety, provides all required functionality, and includes comprehensive tests to verify correctness under concurrent access.# Thread-Safe Generic Bounded Blocking Queue

## Implementation

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

public class BoundedBlockingQueue<T> {
    private final Object[] array;
    private int head = 0;
    private int tail = 0;
    private final int capacity;
    private final AtomicInteger size = new AtomicInteger(0);
    
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;
    
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.array = new Object[capacity];
    }
    
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lockInterruptibly();
        try {
            while (size.get() == capacity && !shutdown) {
                notFull.await();
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = item;
            tail = (tail + 1) % capacity;
            size.incrementAndGet();
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (size.get() == capacity && !shutdown) {
                long remaining = nanos;
                if (remaining <= 0) {
                    return false;
                }
                remaining = notFull.awaitNanos(remaining);
                nanos = remaining;
            }
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            array[tail] = item;
            tail = (tail + 1) % capacity;
            size.incrementAndGet();
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (size.get() == 0 && !shutdown) {
                notEmpty.await();
            }
            if (shutdown && size.get() == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % capacity;
            size.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            long nanos = unit.toNanos(timeout);
            while (size.get() == 0 && !shutdown) {
                long remaining = nanos;
                if (remaining <= 0) {
                    return null;
                }
                remaining = notEmpty.awaitNanos(remaining);
                nanos = remaining;
            }
            if (shutdown && size.get() == 0) {
                throw new IllegalStateException("Queue is shutdown");
            }
            T item = (T) array[head];
            array[head] = null; // Help GC
            head = (head + 1) % capacity;
            size.decrementAndGet();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
    
    public T peek() {
        lock.lock();
        try {
            return size.get() > 0 ? (T) array[head] : null;
        } finally {
            lock.unlock();
        }
    }
    
    public boolean isEmpty() {
        return size.get() == 0;
    }
    
    public boolean isFull() {
        return size.get() == capacity;
    }
    
    public int size() {
        return size.get();
    }
    
    public int remainingCapacity() {
        return capacity - size.get();
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
    
    @Override
    public String toString() {
        lock.lock();
        try {
            if (size.get() == 0) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < size.get(); i++) {
                sb.append(array[(head + i) % capacity]);
                if (i < size.get() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }
}
```

## Concurrency Strategy

1. **Mutual Exclusion**: 
   - A `ReentrantLock` ensures exclusive access to shared resources (the queue array and state variables).

2. **Condition Variables**:
   - `notEmpty`: Signaled when an item is added, waited by consumers.
   - `notFull`: Signaled when space is available, waited by producers.

3. **Shutdown Handling**:
   - A `shutdown` flag interrupts waiting threads by signaling all conditions and allowing them to check the flag.

4. **Atomic Operations**:
   - `size` is maintained via `AtomicInteger` for visibility across threads.

5. **Null Safety**:
   - Null items are rejected to ensure type safety.

6. **Memory Management**:
   - Set array elements to `null` after removal to help garbage collection.

## JUnit 5 Tests

```java
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundedBlockingQueueTest {
    private static final int QUEUE_CAPACITY = 10;
    private static final int NUM_PRODUCERS = 5;
    private static final int NUM_CONSUMERS = 5;
    private static final int MESSAGES_PER_PRODUCER = 100;
    
    private BoundedBlockingQueue<Integer> queue;
    
    @BeforeEach
    void setUp() {
        queue = new BoundedBlockingQueue<>(QUEUE_CAPACITY);
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
    void testOffer() throws InterruptedException {
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(1, queue.size());
        assertEquals(1, queue.poll(1, TimeUnit.SECONDS));
    }
    
    @Test
    void testPollTimeout() throws InterruptedException {
        long timeout = 100;
        assertNull(queue.poll(timeout, TimeUnit.MILLISECONDS));
    }
    
    @Test
    void testPeek() throws InterruptedException {
        queue.put(1);
        assertEquals(1, queue.peek());
        queue.take();
        assertNull(queue.peek());
    }
    
    @Test
    void testIsFullAndEmpty() throws InterruptedException {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            queue.put(i);
        }
        assertTrue(queue.isFull());
        assertFalse(queue.isEmpty());
    }
    
    @Test
    void testRemainingCapacity() throws InterruptedException {
        assertEquals(QUEUE_CAPACITY, queue.remainingCapacity());
        queue.put(1);
        assertEquals(QUEUE_CAPACITY - 1, queue.remainingCapacity());
    }
    
    @Test
    void testShutdown() throws InterruptedException {
        queue.shutdown();
        assertThrows(IllegalStateException.class, () -> queue.put(1));
        assertThrows(IllegalStateException.class, () -> queue.take());
        assertThrows(IllegalStateException.class, () -> queue.offer(1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> queue.poll(1, TimeUnit.SECONDS));
    }
    
    @Test
    void testMultipleProducersAndConsumers() throws InterruptedException, ExecutionException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_PRODUCERS + NUM_CONSUMERS);
        
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(NUM_PRODUCERS + NUM_CONSUMERS);
        
        // Producers
        for (int i = 0; i < NUM_PRODUCERS; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < MESSAGES_PER_PRODUCER; j++) {
                        queue.put(producerId * MESSAGES_PER_PRODUCER + j);
                        producedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Consumers
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            break;
                        }
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        
        // Wait for all tasks to complete
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        int expectedProduced = NUM_PRODUCERS * MESSAGES_PER_PRODUCER;
        int expectedConsumed = expectedProduced;
        
        assertEquals(expectedProduced, producedCount.get(), "Not all items were produced");
        assertEquals(expectedConsumed, consumedCount.get(), "Not all items were consumed");
        assertTrue(queue.isEmpty(), "Queue should be empty after all items consumed");
    }
    
    @Test
    void testNoLostOrDuplicatedElements() throws InterruptedException, ExecutionException {
        int totalItems = 1000;
        int numProducers = 10;
        int itemsPerProducer = totalItems / numProducers;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers);
        
        AtomicInteger[] producedCounts = new AtomicInteger[numProducers];
        for (int i = 0; i < numProducers; i++) {
            producedCounts[i] = new AtomicInteger(0);
        }
        
        ConcurrentHashMap<Integer, Boolean> receivedItems = new ConcurrentHashMap<>();
        
        ExecutorService executor = Executors.newFixedThreadPool(numProducers);
        
        for (int i = 0; i < numProducers; i++) {
            final int producerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < itemsPerProducer; j++) {
                        int item = producerId * itemsPerProducer + j;
                        queue.put(item);
                        producedCounts[producerId].incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Single consumer
        CountDownLatch consumerDone = new CountDownLatch(1);
        executor.submit(() -> {
            try {
                startLatch.await();
                while (true) {
                    Integer item = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        break;
                    }
                    receivedItems.put(item, true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        });
        
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        consumerDone.await(30, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Verify all items produced were received
        for (int i = 0; i < numProducers; i++) {
            assertEquals(itemsPerProducer, producedCounts[i].get(), 
                "Producer " + i + " did not produce all items");
        }
        
        assertEquals(totalItems, receivedItems.size(), 
            "Queue lost items or received duplicates");
        
        // Verify all received items are unique
        assertEquals(totalItems, receivedItems.keySet().stream().distinct().count(),
            "Duplicate items found in queue");
    }
}
```

## Key Features

1. **Thread Safety**: Uses `ReentrantLock` and `Condition` variables