<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260810T091324Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
### Thread-Safe Bounded Blocking Queue Implementation

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.NoSuchElementException;

public class BoundedQueue<T> {
    private final T[] queue;
    private final Semaphore notEmpty;
    private final Semaphore notFull;
    private final int capacity;
    private final AtomicInteger count;
    private int head;
    private int tail;
    private boolean shutdown;

    @SuppressWarnings("unchecked")
    public BoundedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.queue = (T[]) new Object[capacity];
        notEmpty = new Semaphore(0);
        notFull = new Semaphore(capacity);
        count = new AtomicInteger(0);
        head = 0;
        tail = 0;
        shutdown = false;
    }

    public boolean offer(T item) {
        if (shutdown) throw new IllegalStateException("Queue is shut down");
        try {
            notFull.acquireUninterruptibly();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        queue[tail] = item;
        tail = (tail + 1) % capacity;
        count.incrementAndGet();
        notEmpty.release();
        return true;
    }

    public T take() throws InterruptedException {
        if (shutdown) throw new IllegalStateException("Queue is shut down");
        notEmpty.acquireUninterruptibly();
        T item = queue[head];
        head = (head + 1) % capacity;
        count.decrementAndGet();
        notFull.release();
        return item;
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        if (shutdown) throw new IllegalStateException("Queue is shut down");
        long ns = unit.toNanos(timeout);
        return notFull.tryAcquire(ns, TimeUnit.NANOSECONDS) && offer(item);
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (shutdown) throw new IllegalStateException("Queue is shut down");
        if (notEmpty.tryAcquire(timeout, unit)) return take();
        return null;
    }

    public boolean shutdown() {
        shutdown = true;
        notFull.drainPermits();
        return true;
   