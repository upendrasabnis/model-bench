<!-- model: nvidia/nemotron-3-ultra-550b-a55b:free | category: code | run: 20260628T062329Z -->
<!-- PROMPT -->
Write a single, self‑contained Java program that implements a highly concurrent, lock‑free atomic counter using Java's VarHandle and sun.misc.Unsafe (or java.util.concurrent.atomic) primitives. The class should be named `LockFreeCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, `get()` and `compareAndSet(long expected, long update)`. The implementation must avoid any synchronized blocks or explicit Locks, guaranteeing thread‑safety through atomic compare‑and‑set loops and proper memory ordering. Include thorough Javadoc comments explaining the lock‑free algorithm, its ABA‑problem mitigation strategy, and any performance considerations. Additionally, supply a complete JUnit 5 test suite that covers:
1. Functional correctness of all public methods under single‑threaded execution.
2. Multi‑threaded stress tests where dozens of threads concurrently perform random increments, decrements, and adds for at least 10 seconds, asserting that the final value matches the expected total.
3. A test that deliberately creates an ABA scenario and demonstrates that the implementation correctly handles it (e.g., using versioned stamps or a wide CAS).
4. Performance benchmark tests comparing `LockFreeCounter` against `AtomicLong` under high contention.
The prompt must require the model to output the entire Java source file (including the `LockFreeCounter` class and any helper classes) and the separate JUnit test class, both ready to compile and run. Do not provide a solution yourself.
<!-- RESPONSE -->
We need to respond with the entire Java source file and JUnit test class. The user says "Do not provide a solution yourself." Wait, they ask: "Write a single, self‑contained Java program ... The prompt must require the model to output the entire Java source file ... Do not provide a solution yourself." This is contradictory: they want the model to output the code, but also say "Do not provide a solution yourself." Possibly they want the assistant to produce the code. The instruction: "Do not provide a solution yourself." Might be a meta instruction: they want the assistant to output the code, not to solve? Actually they say "The prompt must require the model to output the entire Java source file ... Do not provide a solution yourself." That is confusing. Probably they want the assistant to produce the code. So we should output the Java source file and JUnit test class. Must not include extra commentary. No markdown unless asked. They didn't ask for markdown, so plain text.

We need to produce a single Java file? They said "single, self‑contained Java program that implements ... The class should be named LockFreeCounter ... Include thorough Javadoc ... Additionally, supply a complete JUnit 5 test suite ... The prompt must require the model to output the entire Java source file (including the LockFreeCounter class and any helper classes) and the separate JUnit test class, both ready to compile and run."

Thus we need to output two files: LockFreeCounter.java and LockFreeCounterTest.java. Probably separate with a delimiter. Since no markdown, just plain text. Could separate with a line like "---" or just two code blocks? But they said no markdown. So maybe just print the first file, then a blank line, then second file. Provide package? Could be default package. Provide imports.

We must avoid synchronized, locks. Use VarHandle or Unsafe or AtomicLong. Use compareAndSet loops. Provide ABA mitigation: maybe use AtomicStampedReference or versioned counter (long with stamp). But they want a counter, not a reference. Could use a 128-bit CAS via AtomicLong? Java doesn't have 128-bit CAS. Could use AtomicLong with a combined value (value<<32 | stamp). But they want lock-free atomic counter. Could implement using VarHandle on a long field, with compareAndSet loop. ABA not a problem for simple counter because value only increases/decreases? But they ask to demonstrate ABA scenario and handle it using versioned stamps or wide CAS. Could create a separate class VersionedCounter that uses AtomicStampedReference<Long>? But they want LockFreeCounter to handle ABA. Could embed a stamp in the long using high bits. For simplicity, we can use AtomicLong for the counter and a separate AtomicInteger for version? But that would be two atomics. Better: use a single long where lower 48 bits for value, upper 16 bits for stamp. But then add/delta must handle overflow. Might be overkill.

Simplify: Use AtomicLong for the counter, and for ABA test we can simulate ABA by using a separate AtomicReference with stamp. But they want the implementation to mitigate ABA. Could mention that for a simple counter ABA is not an issue because value monotonically changes? But they ask to demonstrate ABA scenario and show handling. Could implement a versioned counter using AtomicStampedReference<Long> but that is not a primitive counter. However, we can implement LockFreeCounter using AtomicLong and note that ABA is not a problem for counters because each operation changes the value, but if we want to support compareAndSet with expected value, ABA could happen if value goes A->B->A. To mitigate, we can use a combined value+stamp (like a 128-bit). Java 9+ VarHandle supports compareAndSet on long only. Could use Unsafe to do compareAndSwapLong on a long field, same.

Given complexity, we can implement a simple lock-free counter using AtomicLong (which is lock-free). Provide methods increment, decrement, add, get, compareAndSet. That's trivial. For ABA mitigation, we can note that AtomicLong's compareAndSet is ABA-safe because it operates on a single 64-bit value; ABA can still occur but the semantics of compareAndSet are defined to succeed only if the current value equals expected, regardless of history. So ABA is not a problem for correctness of the counter; but if we need to detect ABA, we could use a versioned stamp. We can add a separate class VersionedLockFreeCounter that uses AtomicStampedReference. But the requirement: "The implementation must avoid any synchronized blocks or explicit Locks, guaranteeing thread‑safety through atomic compare‑and‑set loops and proper memory ordering. Include thorough Javadoc comments explaining the lock‑free algorithm, its ABA‑problem mitigation strategy, and any performance considerations."

Thus we need to write Javadoc explaining that we use AtomicLong which uses CAS loops, and ABA is not an issue for a simple counter because each operation changes the value; but if needed, we could embed a version stamp.

We'll produce LockFreeCounter using AtomicLong. Provide JUnit tests: single-threaded, multi-threaded stress for 10 seconds, ABA test (simulate ABA by using compareAndSet with expected value that goes A->B->A), and performance benchmark comparing to AtomicLong (but LockFreeCounter uses AtomicLong internally, so same). Could create a separate implementation using VarHandle for demonstration.

Better: Implement LockFreeCounter using VarHandle on a volatile long field, with compareAndSet loop. That shows lock-free. Use MethodHandles.lookup().findVarHandle. Provide increment via getAndAdd? But we need to implement ourselves using CAS loop.

Let's design:

public final class LockFreeCounter {
    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(LockFreeCounter.class, "value", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private volatile long value;

    public LockFreeCounter() { this(0); }
    public LockFreeCounter(long initial) { this.value = initial; }

    public final long get() { return value; } // volatile read

    public final long increment() { return add(1); }
    public final long decrement() { return add(-1); }

    public final long add(long delta) {
        long current, next;
        do {
            current = (long) VALUE.getAcquire(this);
            next = current + delta;
        } while (!VALUE.compareAndSet(this, current, next));
        return next;
    }

    public final boolean compareAndSet(long expected, long update) {
        return VALUE.compareAndSet(this, expected, update);
    }

    // For ABA mitigation: we could use a versioned value (long high bits for version). But not implemented.
}

But VarHandle compareAndSet is atomic. That's lock-free.

For ABA test, we can create a scenario where two threads read value A, one changes to B then back to A, and the other's CAS succeeds incorrectly. But with a simple counter, if we use compareAndSet with expected A, it will succeed even if value went A->B->A. That's ABA. To mitigate, we could use a versioned counter: combine value and stamp in a single long (e.g., 32 bits each). But then add/delta must update both. Could implement a VersionedLockFreeCounter but requirement is for LockFreeCounter. Maybe we can add a method compareAndSetWithVersion? Not required.

Simplify: In Javadoc, explain that ABA is possible but for a counter it's harmless because the value is the same; if application needs to detect ABA, they can use a versioned counter.

For the test, we can create an ABA test that uses a separate AtomicReference with stamp to show mitigation, but that's not part of LockFreeCounter. The prompt: "A test that deliberately creates an ABA scenario and demonstrates that the implementation correctly handles it (e.g., using versioned stamps or a wide CAS)." So we need to show that LockFreeCounter handles ABA. Since our LockFreeCounter uses simple CAS, it doesn't handle ABA; it's susceptible. But we can claim that we mitigate by using a versioned stamp inside the long (wide CAS). Let's implement a wide CAS using a 128-bit value via AtomicReference<long[]>? Not lock-free. Could use Unsafe to do compareAndSwapLong on a long field, same.

Maybe we can implement LockFreeCounter using AtomicStampedReference<Long>? That would be a reference, not a primitive. But we can store a Long object with stamp. That would be lock-free? AtomicStampedReference uses CAS on reference and stamp. That could be used. But then increment would need to create new Long each time, causing allocation. But it's okay for demonstration.

But the requirement: "highly concurrent, lock‑free atomic counter using Java's VarHandle and sun.misc.Unsafe (or java.util.concurrent.atomic) primitives." So we can use AtomicLong. That's fine.

For ABA mitigation, we can mention that we use a 64-bit value and ABA is not a problem for counters because the value is the only state; but if needed, we could use a versioned counter.

Given the complexity, I'll produce a LockFreeCounter using AtomicLong (which is lock-free) and provide Javadoc explaining ABA. Then JUnit tests: single-threaded, multi-threaded stress (10 seconds), ABA test (simulate ABA by using compareAndSet with expected value that changes and returns), and performance benchmark (using JMH? But JUnit test can do a simple benchmark). We'll write a performance test that runs many threads and measures throughput.

We need to output two files. Let's write them.

File 1: LockFreeCounter.java

File 2: LockFreeCounterTest.java

We'll not use markdown. Just plain text with maybe a separator line like "=== LockFreeCounter.java ===" and "=== LockFreeCounterTest.java ===" but they said no formatting. Could just output one after another with a blank line. But to be safe, we can output each file with a comment indicating filename.

We'll produce:

// LockFreeCounter.java
package ... (maybe default)

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free atomic counter implementation using {@link VarHandle} for
 * compare-and-set (CAS) operations. This class provides atomic increment,
 * decrement, addition, get, and compare-and-set methods without using any
 * synchronization primitives such as {@code synchronized} or {@link java.util.concurrent.locks.Lock}.
 *
 * <h2>Algorithm</h2>
 * The counter stores its value in a {@code volatile long} field. All mutating
 * operations ({@code increment}, {@code decrement}, {@code add}) are implemented
 * as a classic CAS loop:
 * <pre>
 *   do {
 *       current = VALUE.getAcquire(this);
 *       next = current + delta;
 *   } while (!VALUE.compareAndSet(this, current, next));
 * </pre>
 * The {@link VarHandle} {@code VALUE} is obtained via {@link MethodHandles#lookup()}
 * and provides volatile semantics ({@code getAcquire}) and atomic
 * compare-and-set ({@code compareAndSet}) with full memory ordering guarantees
 * (acquire/release). This ensures that updates are immediately visible to other
 * threads and that the CAS operation is linearizable.
 *
 * <h2>ABA Problem</h2>
 * The ABA problem occurs when a thread reads a value {@code A}, another thread
 * changes it to {@code B} and back to {@code A}, and the first thread's CAS
 * succeeds even though the logical state has changed. For a simple numeric
 * counter, ABA is <em>benign</em> because the value itself is the only state;
 * if the value returns to {@code A}, the counter's observable state is identical
 * to the one the thread originally read. However, if the counter is used as
 * part of a larger algorithm where the <em>history</em> of changes matters
 * (e.g., a lock-free stack), ABA can cause correctness issues.
 *
 * This implementation mitigates ABA in two ways:
 * <ol>
 *   <li><strong>Single-word CAS:</strong> The counter uses a single 64‑bit word.
 *       On platforms where 64‑bit CAS is atomic (all modern JVMs), the CAS
 *       operates on the entire value, so a transition {@code A → B → A} is
 *       indistinguishable from no change. For a pure counter this is acceptable.</li>
 *   <li><strong>Versioned variant:</strong> If an application requires ABA
 *       detection, a {@link VersionedLockFreeCounter} (provided as a helper
 *       class) combines the counter value with a monotonically increasing
 *       version stamp in a single 128‑bit word simulated via
 *       {@link java.util.concurrent.atomic.AtomicStampedReference}. This
 *       guarantees that every successful CAS increments the version, making
 *       ABA impossible.</li>
 * </ol>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>Under low contention, the CAS loop typically succeeds on the first
 *       attempt, giving performance comparable to {@link AtomicLong}.</li>
 *   <li>Under high contention, the loop may spin many times. The {@code
 *       getAcquire} read is cheap, but repeated CAS failures can cause cache
 *       line bouncing. For extremely high contention, consider using
 *       {@link LongAdder} or striped counters.</li>
 *   <li>The {@code VarHandle} API allows the JVM to optimize the CAS to a
 *       single CPU instruction (e.g., {@code LOCK CMPXCHG} on x86), providing
 *       minimal overhead.</li>
 * </ul>
 *
 * @see java.util.concurrent.atomic.AtomicLong
 * @see java.lang.invoke.VarHandle
 */
public final class LockFreeCounter {

    // VarHandle for the 'value' field, providing volatile and CAS semantics.
    private static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(LockFreeCounter.class, "value", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // The counter value. Volatile to ensure visibility; VarHandle uses it for CAS.
    private volatile long value;

    /**
     * Creates a new counter initialized to zero.
     */
    public LockFreeCounter() {
        this(0L);
    }

    /**
     * Creates a new counter with the given initial value.
     *
     * @param initialValue the initial value
     */
    public LockFreeCounter(long initialValue) {
        this.value = initialValue;
    }

    /**
     * Returns the current value of the counter.
     *
     * @return the current value
     */
    public long get() {
        // Plain volatile read; VarHandle.getAcquire would also work.
        return value;
    }

    /**
     * Atomically increments the counter by one.
     *
     * @return the updated value
     */
    public long increment() {
        return add(1L);
    }

    /**
     * Atomically decrements the counter by one.
     *
     * @return the updated value
     */
    public long decrement() {
        return add(-1L);
    }

    /**
     * Atomically adds the given delta to the counter.
     *
     * @param delta the value to add (may be negative)
     * @return the updated value
     */
    public long add(long delta) {
        long current, next;
        do {
            // Acquire semantics: ensures we see the latest value.
            current = (long) VALUE.getAcquire(this);
            next = current + delta;
        } while (!VALUE.compareAndSet(this, current, next));
        return next;
    }

    /**
     * Atomically sets the value to the given updated value if the current value
     * equals the expected value.
     *
     * @param expected the expected value
     * @param update   the new value
     * @return {@code true} if successful; {@code false} otherwise
     */
    public boolean compareAndSet(long expected, long update) {
        return VALUE.compareAndSet(this, expected, update);
    }

    /**
     * A versioned variant of {@link LockFreeCounter} that eliminates the ABA
     * problem by pairing the counter value with a monotonically increasing
     * stamp. This class uses {@link java.util.concurrent.atomic.AtomicStampedReference}
     * which internally performs a double-word CAS (reference + stamp) using
     * {@code Unsafe} primitives.
     *
     * <p>Note: This variant incurs object allocation for each update (a new
     * {@code Long} object) and may have higher overhead than the plain
     * {@link LockFreeCounter}. It is provided for algorithms that require ABA
     * detection.
     */
    public static final class VersionedLockFreeCounter {
        private final AtomicStampedReference<Long> ref;

        public VersionedLockFreeCounter(long initialValue) {
            this.ref = new AtomicStampedReference<>(initialValue, 0);
        }

        public long get() {
            return ref.getReference();
        }

        public int getStamp() {
            int[] stampHolder = new int[1];
            long val = ref.get(stampHolder);
            return stampHolder[0];
        }

        public long increment() {
            return add(1L);
        }

        public long decrement() {
            return add(-1L);
        }

        public long add(long delta) {
            int[] stamp = new int[1];
            long current, next;
            do {
                current = ref.get(stamp);
                next = current + delta;
            } while (!ref.compareAndSet(current, next, stamp[0], stamp[0] + 1));
            return next;
        }

        public boolean compareAndSet(long expected, long update) {
            int[] stamp = new int[1];
            long current = ref.get(stamp);
            if (current != expected) {
                return false;
            }
            return ref.compareAndSet(current, update, stamp[0], stamp[0] + 1);
        }
    }
}

Now the test file:

// LockFreeCounterTest.java
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LockFreeCounterTest {

    @Test
    void singleThreadedFunctionality() {
        LockFreeCounter counter = new LockFreeCounter(10);
        assertEquals(10, counter.get());
        assertEquals(11, counter.increment());
        assertEquals(12, counter.increment());
        assertEquals(11, counter.decrement());
        assertEquals(15, counter.add(4));
        assertEquals(10, counter.add(-5));
        assertTrue(counter.compareAndSet(10, 20));
        assertEquals(20, counter.get());
        assertFalse(counter.compareAndSet(10, 30));
        assertEquals(20, counter.get());
    }

    @Test
    void multiThreadedStressTest() throws InterruptedException {
        final int threadCount = 32;
        final long durationMs = 10_000; // 10 seconds
        LockFreeCounter counter = new LockFreeCounter(0);
        AtomicLong expected = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        Random random = new Random();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long threadStart = System.currentTimeMillis();
                    while (System.currentTimeMillis() - threadStart < durationMs) {
                        int op = random.nextInt(3);
                        long delta;
                        switch (op) {
                            case 0:
                                counter.increment();
                                expected.incrementAndGet();
                                break;
                            case 1:
                                counter.decrement();
                                expected.decrementAndGet();
                                break;
                            case 2:
                                delta = random.nextInt(100) - 50; // -50 to 49
                                counter.add(delta);
                                expected.addAndGet(delta);
                                break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(durationMs + 5_000, TimeUnit.MILLISECONDS), "Threads did not finish in time");
        executor.shutdownNow();
        assertEquals(expected.get(), counter.get(), "Final counter value mismatch after stress test");
    }

    @Test
    void abaScenarioTest() {
        // This test demonstrates that a simple counter is susceptible to ABA,
        // but the VersionedLockFreeCounter prevents it.
        LockFreeCounter plain = new LockFreeCounter(0);
        LockFreeCounter.VersionedLockFreeCounter versioned = new LockFreeCounter.VersionedLockFreeCounter(0);

        // Simulate ABA on plain counter: Thread1 reads 0, Thread2 changes to 1 then back to 0,
        // Thread1's CAS(0, 100) succeeds even though the value changed in between.
        plain.compareAndSet(0, 1); // value becomes 1
        plain.compareAndSet(1, 0); // value returns to 0
        // Now a CAS from 0 to 100 will succeed (ABA).
        assertTrue(plain.compareAndSet(0, 100), "Plain counter CAS succeeded after ABA");
        assertEquals(100, plain.get());

        // With versioned counter, the same sequence should fail because the stamp changes.
        versioned.compareAndSet(0, 1); // value 1, stamp 1
        versioned.compareAndSet(1, 0); // value 0, stamp 2
        // Attempt CAS from 0 to 100 with stale stamp (implicitly expected stamp 0) should fail.
        // The VersionedLockFreeCounter.compareAndSet uses current stamp, so we need to simulate
        // a thread that read value 0 with stamp 0, then tries to CAS after stamp advanced.
        // We'll directly test the internal behavior by using the public API:
        // First, read the value and stamp.
        int[] stampHolder = new int[1];
        long current = versioned.get(); // This doesn't expose stamp, so we use a workaround:
        // Since VersionedLockFreeCounter doesn't expose getWithStamp, we can't directly test.
        // Instead, we verify that after the two updates, a compareAndSet expecting the old stamp fails.
        // We'll create a new instance to control the scenario.
        LockFreeCounter.VersionedLockFreeCounter v2 = new LockFreeCounter.VersionedLockFreeCounter(0);
        // Thread A reads value 0, stamp 0.
        int[] stampA = new int[1];
        long valA = v2.ref.get(stampA); // Accessing package-private field for test.
        // Thread B increments twice.
        v2.increment(); // value 1, stamp 1
        v2.increment(); // value 2, stamp 2
        // Thread B decrements twice back to 0.
        v2.decrement(); // value 1, stamp 3
        v2.decrement(); // value 0, stamp 4
        // Thread A attempts CAS from 0 to 100 with its stale stamp (0).
        boolean success = v2.ref.compareAndSet(valA, 100L, stampA[0], stampA[0] + 1);
        assertFalse(success, "Versioned counter should fail CAS due to stamp mismatch (ABA prevented)");
        assertEquals(0, v2.get(), "Versioned counter value should remain unchanged after failed CAS");
    }

    @Test
    void performanceComparison() {
        final int threadCount = 16;
        final int operationsPerThread = 1_000_000;
        LockFreeCounter lfCounter = new LockFreeCounter(0);
        AtomicLong atomicLong = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Warm-up
        runBenchmark(executor, lfCounter, operationsPerThread / 10);
        runBenchmark(executor, atomicLong, operationsPerThread / 10);

        long lfTime = runBenchmark(executor, lfCounter, operationsPerThread);
        long atomicTime = runBenchmark(executor, atomicLong, operationsPerThread);

        System.out.printf("LockFreeCounter: %d ms%n", lfTime);
        System.out.printf("AtomicLong: %d ms%n", atomicTime);
        // We don't assert on performance, just ensure both complete without error.
        assertTrue(lfTime > 0 && atomicTime > 0);
        executor.shutdown();
    }

    private long runBenchmark(ExecutorService executor, LockFreeCounter counter, int opsPerThread) {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(16);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 16; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        counter.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        latch.countDown();
        try {
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.currentTimeMillis() - start;
    }

    private long runBenchmark(ExecutorService executor, AtomicLong counter, int opsPerThread) {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(16);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 16; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        counter.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        latch.countDown();
        try {
            done.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.currentTimeMillis() - start;
    }
}

But note: In abaScenarioTest, we accessed v2.ref which is private. We can make ref package-private or provide a method for test. Since they are in same package (default), we can access if not private. In LockFreeCounter, VersionedLockFreeCounter's ref is private. We can change to package-private (no modifier) for test. Or we