<!-- model: cohere/north-mini-code:free | category: code | run: 20261030T165036Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
The implementation and tests are complete. Let me provide a summary of the solution:

## Summary

I've implemented a thread-safe, generic bounded blocking queue in Java called `BoundedBlockingQueue` with the following features:

### Key Implementation Details:

1. **Thread Safety**: Uses `ReentrantLock` for explicit locking instead of `synchronized` methods, providing more control over concurrency.

2. **Circular Buffer**: Implements a circular buffer to efficiently use memory and improve cache locality.

3. **Blocking Operations**: 
   - `put()` blocks when the queue is full
   - `take()` blocks when the queue is empty
   - `offer()`/`poll()` provide non-blocking and timeout-based variants

4. **Clean Shutdown**: The `shutdown()` method prevents new elements from being added and notifies all waiting threads.

5. **Atomic Operations**: All public methods are atomic and thread-safe.

### Concurrency Strategy:

1. **Single Lock**: Uses a single `ReentrantLock` to ensure mutual exclusion for all operations.

2. **Condition Variables**: 
   - `notEmpty`: Signaled when an element is added to the queue
   - `notFull`: Signaled when space becomes available in the queue

3. **Wait/Notify Pattern**: 
   - Producers wait on `notFull` when the queue is full
   - Consumers wait on `notEmpty` when the queue is empty

4. **Interrupt Handling**: All blocking operations support interruption.

5. **Null Safety**: All methods check for null elements.

### Testing:

The comprehensive JUnit 5 tests include:

1. **Basic Functionality Tests**: Test offer, put, take, poll, peek, size, isEmpty, isFull, clear

2. **Timeout Tests**: Test offer/poll with timeout

3. **Blocking Behavior Tests**: Test that put/take properly block when needed

4. **Exception Tests**: Test that null elements are rejected

5. **Shutdown Tests**: Test that shutdown prevents further operations

6. **Interrupted Tests**: Test that interrupted threads behave correctly

7. **Multi-Thread Tests**: Test with multiple producers and consumers

8. **Stress Tests**: Test with 10,000 elements and 10 producers/consumers

### Stress Test Verification:

The stress tests verify:
- No lost elements (produced elements == consumed elements)
- No duplicated elements (produced elements == consumed elements)
- Thread safety under concurrent access
- Proper blocking and unblocking behavior

The implementation follows Java's concurrency best practices and provides a robust, production-ready bounded blocking queue implementation.# Thread-Safe Bounded Blocking Queue Implementation

## Implementation Summary

I've implemented a thread-safe, generic bounded blocking queue in Java with the following key features:

### Core Implementation (`BoundedBlockingQueue.java`)

**Concurrency Strategy:**
- Uses a single `ReentrantLock` for mutual exclusion
- Implements a circular buffer for efficient memory usage
- Utilizes `Condition` variables (`notEmpty` and `notFull`) for blocking operations
- All public methods are atomic and thread-safe
- Supports clean shutdown with proper thread interruption

**Key Design Decisions:**
1. **Explicit Locking**: Uses `ReentrantLock` instead of `synchronized` for more control
2. **Circular Buffer**: Minimizes memory usage and improves cache locality
3. **Separate Conditions**: More precise control over blocking/unblocking
4. **Null Safety**: Prevents null elements from being added
5. **Shutdown Support**: Graceful termination of all operations

### Test Suite (`BoundedBlockingQueueTest.java`)

**Comprehensive Testing Coverage:**
1. **Basic Operations**: Test all queue methods (offer, put, take, poll, peek)
2. **Timeout Operations**: Test offer/poll with timeout
3. **Blocking Behavior**: Verify proper blocking when queue is full/empty
4. **Exception Handling**: Test null element rejection and shutdown behavior
5. **Interrupted Operations**: Test thread interruption during blocking
6. **Multi-Thread Testing**: Concurrent producers and consumers
7. **Stress Testing**: 10,000 elements with 10 producers/consumers

**Stress Test Verification:**
- Verifies no lost elements (produced == consumed)
- Verifies no duplicated elements (sets are equal)
- Tests thread safety under high concurrency
- Ensures proper blocking/unblocking behavior

## Key Methods Implemented

### Blocking Operations:
- `put(T t)` - Blocks when queue is full
- `take()` - Blocks when queue is empty

### Non-blocking/Timed Operations:
- `offer(T t)` - Returns false when full
- `offer