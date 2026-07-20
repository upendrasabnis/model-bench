<!-- model: cohere/north-mini-code:free | category: code | run: 20260720T104637Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
To solve this problem, we need to implement a custom thread-safe bounded blocking queue in Java without using the built-in `java.util.concurrent.BlockingQueue` class. The queue should support blocking `put` and `take` operations, timed `offer` and `poll` operations, and a clean shutdown mechanism. Additionally, we need to write comprehensive JUnit 5 tests to verify the correctness and concurrency safety of the implementation.

### Approach
1. **Thread Safety**: Use a single `ReentrantLock` to synchronize all access to the queue's internal state (array, head, tail, count, and shutdown flag). This ensures exclusive access to the queue during modifications and prevents race conditions.
2. **Blocking Operations**: Use `Condition` variables (`notEmpty` and `notFull`) to block threads when the queue is empty (for `take`/`poll`) or full (for `put`/`offer`). These conditions are signaled when the state of the queue changes (elements added or removed).
3. **Timed Operations**: Implement `offer` and `poll` with timeouts using `Condition.awaitNanos` to allow threads to wait for a specified duration without blocking indefinitely.
4. **Shutdown Mechanism**: Introduce a `shutdown` flag. When set, subsequent `put` and `offer` operations throw `IllegalStateException`. `take` and `poll` operations continue until the queue is empty, after which they also throw `IllegalStateException`.
5. **Circular Buffer**: Implement the queue as a circular buffer using head and tail indices to efficiently manage the queue's elements.
6. **Testing**: Write JUnit 5 tests to verify:
   - Basic put/take operations.
   - Timed offer/poll operations.
   - Blocking behavior when the queue is full or empty.
   - Shutdown mechanism.
   - Contains, remainingCapacity, and clear methods.
   - Multi-producer/multi-consumer stress tests to ensure no lost or duplicated elements.

### Solution Code

```java
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A thread-safe, generic bounded blocking queue implementation.
 * <p>
 * Concurrency strategy:
 * - Uses a single {@link ReentrantLock} to protect all shared state (the queue array, head and tail indices, and shutdown flag).
 * - Provides two condition variables: {@code notEmpty} (signaled when an item is added to the queue) and {@code notFull}
 *   (signaled when an item is removed from the queue).
 * - The queue is implemented as a circular buffer with head and tail indices.
 * - The queue is bounded: put operations block when the queue is full, take operations block when the queue is empty.
 * - Supports timed operations (offer, poll) with a timeout.
 * - Supports a clean shutdown: after calling {@code shutdown()}, put operations will fail immediately (throwing {@code IllegalStateException}),
 *   and take operations will continue until the queue is empty, then subsequent takes will throw {@code IllegalStateException}.
 * - The queue is generic: it can hold elements of any type.
 * - The queue is not thread-safe for concurrent modification by multiple threads other than the designated producer and consumer
 *   (i.e., put and take operations are synchronized via the lock).
 * - The queue does not support iteration while elements are being added or removed (the iterator is fail-fast).
 *
 * @param <E> the type of elements held in this queue
 */
public class CustomBlockingQueue<E> {
    private final Object[] array;
    private int head = 0;
    private int tail = 0;
    private int count = 0;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private volatile boolean shutdown = false;

    /**
     * Constructs a {@code CustomBlockingQueue} with the specified capacity.
     *
     * @param capacity the capacity of the queue
     * @throws IllegalArgumentException if the capacity is less than 1
     */
    public CustomBlockingQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1");
        }
        this.capacity = capacity;
        this.array = new Object[capacity];
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting if necessary for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shutdown
     */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e, "Element must not be null");
        lock.lockInterruptibly();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shutdown");
            }
            while (count == capacity) {
                notFull.await();
            }
            array[tail] = e;
            if (++tail == capacity) {
                tail = 0;
            }
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element at the tail of this queue, waiting up to the specified wait time if necessary.
     *
     * @param e the element to add
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if the element was added, {@code false} if