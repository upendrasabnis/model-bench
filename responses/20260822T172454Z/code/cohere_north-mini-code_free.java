<!-- model: cohere/north-mini-code:free | category: code | run: 20260822T172454Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
```java
/**
 * A thread-safe, generic bounded blocking queue implementation.
 *
 * <p>Concurrency Strategy:
 * <ul>
 *   <li>All public methods are synchronized on the queue instance to ensure mutual exclusion.</li>
 *   <li>Blocking operations (put, take) use the intrinsic wait/notifyAll mechanism to block threads
 *       when the queue is full (for put) or empty (for take). Spurious wakeups are handled by looping.</li>
 *   <li>Timed operations (offer, poll) use wait with a timeout, computing remaining time to avoid drift.</li>
 *   <li>A shutdown flag (AtomicBoolean) is used to signal that the queue is closed. Any put/offer after
 *       shutdown throws IllegalStateException. take/poll will return remaining items and then throw/return
 *       null accordingly.</li>
 *   <li>Interruption is respected: if a thread is interrupted while waiting, InterruptedException is thrown.</li>
 *   <li>The queue uses a circular array for storage; head and tail indices are protected by the same monitor.</li>
 *   <li>Methods like size(), isEmpty(), clear() are also synchronized to provide consistent state.</li>
 * </ul>
 *
 * <p>Thread Safety:
 * The queue is safe for concurrent use by multiple producers and consumers. The internal state
 * (array, head, tail, size, shutdown) is only accessed or modified within synchronized blocks,
 * guaranteeing atomicity and visibility across threads.
 *
 * @param <T> The type of elements held in this queue.
 */
public class BoundedBlockingQueue<T> {
    private final Object[] array;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.array = new Object[capacity];
        this.capacity = capacity;
    }

    /**
     * Inserts the specified element into this queue, blocking if necessary until space is available.
     *
     * @param item the element to add
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalStateException if the queue is shut down
     * @throws NullPointerException if the item is null
     */