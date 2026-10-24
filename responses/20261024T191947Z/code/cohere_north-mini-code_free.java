<!-- model: cohere/north-mini-code:free | category: code | run: 20261024T191947Z -->
<!-- PROMPT -->
Write a thread-safe, generic bounded blocking queue in Java (without using java.util.concurrent.BlockingQueue) supporting blocking put/take, timed offer/poll, and a clean shutdown. Document the concurrency strategy. Then write comprehensive JUnit 5 tests, including a multi-producer/multi-consumer stress test that verifies no lost or duplicated elements.
<!-- RESPONSE -->
# Thread-Safe Bounded Blocking Queue Implementation

I'll implement a thread-safe, generic bounded blocking queue in Java with comprehensive documentation and tests.

## Implementation

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java