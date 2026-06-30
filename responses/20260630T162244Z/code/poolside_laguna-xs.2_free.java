<!-- model: poolside/laguna-xs.2:free | category: code | run: 20260630T162244Z -->
<!-- PROMPT -->
Write a self‑contained Java program that implements a highly concurrent, lock‑free, multi‑producer multi‑consumer counter using java.util.concurrent.atomic.VarHandle (or sun.misc.Unsafe) without any explicit synchronization blocks. The class should be named `LockFreeAtomicCounter` and provide methods `increment()`, `decrement()`, `add(long delta)`, and `long get()`. Document the lock‑free algorithm (e.g., compare‑and‑set loop) and explain why it is linearizable and safe under the Java Memory Model. Include a comprehensive JUnit 5 test suite that verifies functional correctness, thread‑safety under heavy contention (e.g., 100 threads each performing 1 000 000 operations), and measures that no lost updates occur. Provide stress tests using `ExecutorService` and `CountDownLatch` to coordinate start/stop, as well as tests that intentionally provoke ABA scenarios to demonstrate correctness. Do not write the solution; only provide the prompt.
<!-- RESPONSE -->

# Lock-Free Atomic Counter Implementation Prompt

## Problem Statement

Implement a highly concurrent, lock-free, multi-producer multi-consumer counter in Java that demonstrates advanced understanding of low-level concurrency primitives and the Java Memory Model.

## Requirements

### Core Implementation

Create a class named `LockFreeAtomicCounter` with the following specifications:

1. **Class Declaration**: `public class LockFreeAtomicCounter`
2. **No explicit synchronization**: Do not use synchronized blocks, ReentrantLock, or any other explicit locking mechanisms
3. **Atomic operations only**: Use either `java.util.concurrent.atomic.VarHandle` (preferred) or `sun.misc.Unsafe` for atomic operations

### Required Methods

Implement these four public methods:
```java
public void increment()     // Atomically increments the counter by 1
public void decrement()     // Atomically decrements the counter by 1  
public void add(long delta) // Atomically adds the specified delta to the counter
public long get()          // Returns the current value of the counter
```

### Algorithm Documentation

Provide detailed documentation explaining:
- The compare-and-set (CAS) loop implementation
- How the algorithm handles concurrent modifications
- Why the implementation is lock-free (not blocked by any thread)
- The retry mechanism for failed CAS operations

### Memory Model Analysis

Explain in detail:
- Why the implementation is linearizable
- How Java Memory Model guarantees visibility across threads
- The role of volatile semantics in your chosen approach
- Why there are no lost updates under concurrent access

### Testing Requirements

Create a comprehensive JUnit 5 test suite with the following tests:

#### Functional Correctness Tests
- Verify `increment()` correctly increases the counter
- Verify `decrement()` correctly decreases the counter
- Verify `add(delta)` correctly adds arbitrary values
- Verify `get()` returns the correct current value
- Test edge cases (e.g., Integer.MIN_VALUE to Integer.MAX_VALUE transitions)

#### Thread-Safety Stress Tests
- Create 100 threads, each performing 1,000,000 operations
- Mix of increment, decrement, and add operations
- Coordinate thread start/stop using `CountDownLatch`
- Verify no lost updates occur (final value equals expected sum)

#### ABA Scenario Tests
- Design tests that intentionally provoke ABA scenarios
- Demonstrate that your implementation correctly handles ABA problems
- Explain whether your implementation is vulnerable to ABA and how you address it

#### Performance Measurement
- Measure and report throughput (operations per second)
- Compare performance characteristics under different contention levels
- Document any performance observations

### Additional Requirements

1. **Self-Contained**: The solution must compile and run independently
2. **Imports**: Use only standard Java libraries (java.util.concurrent, java.util.concurrent.atomic, org.junit.jupiter.api)
3. **Code Quality**: Follow Java coding standards and include appropriate comments
4. **Test Coverage**: Aim for comprehensive test coverage including edge cases

### Submission Format

Provide:
1. The complete `LockFreeAtomicCounter.java` source file
2. The complete test class `LockFreeAtomicCounterTest.java`
3. Brief written analysis (in comments or separate documentation) explaining the algorithm and memory model considerations

### Constraints

- Do NOT use any explicit locking mechanisms (synchronized, Lock, Semaphore, etc.)
- Do NOT use volatile variables directly (though you may use them indirectly through atomic classes)
- DO use atomic primitives (VarHandle or Unsafe)
- All operations must be thread-safe without traditional locks

This prompt is designed to test deep understanding of concurrent programming, the Java Memory Model, and lock-free data structure design patterns.
