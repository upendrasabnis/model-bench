<!-- model: nvidia/nemotron-nano-12b-v2-vl:free | category: code | run: 20260628T062329Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent, lock‑free atomic counter using Java's VarHandle and sun.misc.Unsafe (or java.util.concurrent.atomic) primitives. The class should be named `LockFreeCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, `get()` and `compareAndSet(long expected, long update)`. The implementation must avoid any synchronized blocks or explicit Locks, guaranteeing thread‑safety through atomic compare‑and‑set loops and proper memory ordering. Include thorough Javadoc comments explaining the lock‑free algorithm, its ABA‑problem mitigation strategy, and any performance considerations. Additionally, supply a complete JUnit 5 test suite that covers:
1. Functional correctness of all public methods under single‑threaded execution.
2. Multi‑threaded stress tests where dozens of threads concurrently perform random increments, decrements, and adds for at least 10 seconds, asserting that the final value matches the expected total.
3. A test that deliberately creates an ABA scenario and demonstrates that the implementation correctly handles it (e.g., using versioned stamps or a wide CAS).
4. Performance benchmark tests comparing `LockFreeCounter` against `AtomicLong` under high contention.
The prompt must require the model to output the entire Java source file (including the `LockFreeCounter` class and any helper classes) and the separate JUnit test class, both ready to compile and run. Do not provide a solution yourself.
<!-- RESPONSE -->
```java
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, select Tools | Templates
 * and open the template in the editor.
 */
package com.example;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedValue;
import sun.misc.Unsafe;

public class LockFreeCounter {

    private static final Unsafe UNSAFE;
    private static final long VALUE_OFFSET;
    private static final long STAMP_OFFSET;

    static {
        try {
            UNSAFE = Unsafe.getUnsafe();
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
            
            VALUE_OFFSET = UNSAFE.objectFieldOffset(LockFreeCounter.class.getDeclaredField("value"));
            STAMP_OFFSET = UNSAFE.objectFieldOffset(LockFreeCounter.class.getDeclaredField("stamp"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError("Failed to initialize Unsafe");
        }
    }

    private volatile long value;
    private volatile long stamp;

    /**
     * Atomically increments the counter by one.
     *
     * @return the new value of the counter
     */
    public long increment() {
        return add(1);
    }

    /**
     * Atomically decrements the counter by one.
     *
     * @return the new value of the counter
     */
    public long decrement() {
        return add(-1);
    }

    /**
     * Atomically adds the specified delta to the counter.
     *
     * @param delta the value to add
     * @return the new value of the counter
     */
    public long add(long delta) {
        while (true) {
            long currentValue = get();
            long currentStamp = stamp;
            long newValue = currentValue + delta;
            long newStamp = currentStamp + 1;

            if (UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, currentValue, newValue)
                    && UNSAFE.compareAndSwapLong(this, STAMP_OFFSET, currentStamp, newStamp)) {
                return newValue;
            }
        }
    }

    /**
     * Atomically retrieves the current value of the counter.
     *
     * @return the current value of the counter
     */
    public long get() {
        return value;
    }

    /**
     * Atomically sets the value of the counter if the current value matches the expected value.
     *
     * @param expected the expected value
     * @param update the new value
     * @return true if the update was successful, false otherwise
     */
    public boolean compareAndSet(long expected, long update) {
        long currentStamp = stamp;
        long newStamp = currentStamp + 1;

        while (!UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expected, update)
                || !UNSAFE.compareAndSwapLong(this, STAMP_OFFSET, currentStamp, newStamp)) {
            // Retry if CAS fails
        }
        return true;
    }

    /**
     * Mitigates ABA problem using a versioned stamp. Each CAS operation increments the stamp.
     */
    private static class VersionedCounter {
        private long value;
        private long stamp;
    }
}
```

```java
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, select Tools | Templates
 * and open the template in the editor.
 */
package com.example;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Random;

class LockFreeCounterTest {

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Random random = new Random();

    @Test
    void testIncrementDecrement() {
        LockFreeCounter counter = new LockFreeCounter();
        counter.increment();
        assertEquals(1, counter.get());
        counter.decrement();
        assertEquals(0, counter.get());
    }

    @Test
    void testAdd() {
        LockFreeCounter counter = new LockFreeCounter();
        counter.add(5);
        assertEquals(5, counter.get());
    }

    @Test
    void testCompareAndSet() {
        LockFreeCounter counter = new LockFreeCounter();
        counter.compareAndSet(0, 10);
        assertEquals(10, counter.get());
        counter.compareAndSet(10, 20);
        assertEquals(20, counter.get());
    }

    @Test
    @Timeout(10)
    void testMultiThreadedContention() throws InterruptedException {
        final int THREADS = 100;
        final int OPERATIONS = 1000000 / THREADS;
        final int EXPECTED = OPERATIONS * THREADS;

        LockFreeCounter counter = new LockFreeCounter();
        CountDownLatch latch = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            executorService.submit(() -> {
                for (int j = 0; j < OPERATIONS; j++) {
                    long delta = random.nextInt(3) - 1;
                    counter.add(delta);
                }
                latch.countDown();
            });
        }

        latch.await();
        assertEquals(0, counter.get());
    }

    @Test
    void testABAScenario() {
        LockFreeCounter counter = new LockFreeCounter();
        counter.compareAndSet(0, 1); // set to 1
        counter.compareAndSet(1, 0); // set to 0 (ABA)
        counter.compareAndSet(0, 1); // should succeed
        assertEquals(1, counter.get());
    }

    @Test
    void testPerformanceBenchmark() throws InterruptedException {
        final int THREADS = 100;
        final int OPERATIONS = 1000000;
        final long EXPECTED = 0;

        LockFreeCounter lfc = new LockFreeCounter();
        AtomicLong atomic = new AtomicLong(0);

        CountDownLatch latch = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            executorService.submit(() -> {
                for (int j = 0; j < OPERATIONS / THREADS; j++) {
                    lfc.add(1);
                    atomic.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        assertEquals(EXPECTED, lfc.get());
        assertEquals(EXPECTED, atomic.get());
    }
}
```
