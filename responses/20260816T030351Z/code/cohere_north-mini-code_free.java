<!-- model: cohere/north-mini-code:free | category: code | run: 20260816T030351Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

Here's a complete implementation of a thread-safe, generic bounded blocking queue in Java along with comprehensive JUnit 5 tests:

```java
// Thread-safe, generic bounded blocking queue implementation
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBlockingQueue<T> implements Queue<T> {
    private final T[] buffer;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;
    
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    private volatile boolean shutdown = false;
    private final Collection<T> remainingElements = new java.util.ArrayList<>();
    
    @SuppressWarnings("unchecked")
    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
    }
    
    @Override
    public boolean add(T t) {
        if (offer(t)) {
            return true;
        }
        throw new IllegalStateException("Queue is full");
    }
    
    @Override
    public boolean offer(T t) {
        Objects.requireNonNull(t, "Element cannot be null");
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("Queue is shut down");
            }
            if (isFull()) {
                return false;
            }
            buffer[tail] = t;
            tail = (tail + 1) % capacity;
            size++;
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public T remove() {
        T t = poll();
        if (t == null) {
            throw new NoSuchElementException("Queue is empty");
        }
        return t;
    }
    
    @Override
    public T poll() {
        lock.lock();
        try {
            if (shutdown && size == 0) {
                return null;
            }
            if (isEmpty()) {
                return null;
            }
            T t = buffer[head];
            buffer[head] = null; // Help GC
            head = (head + 1) % capacity;
            size--;
            notFull.signal();
            return t;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public T element() {
        T t = peek();
        if (t == null) {
            throw new NoSuchElementException("Queue is empty");
        }
        return t;
    }
    
    @Override
    public T peek() {
        lock.lock();
        try {
            if (isEmpty()) {
                return null;
            }
            return buffer[head];
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return size == 0;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean contains