<!-- model: cohere/north-mini-code:free | category: code | run: 20260706T115904Z -->
<!-- PROMPT -->
Write a self‑contained Java program that defines a public class named `ConcurrentTokenBucketRateLimiter` implementing a thread‑safe token‑bucket rate limiter with per‑client configurable refill rates and burst capacities. The class must expose methods `boolean tryAcquire(String clientId, int permits)` and `void addClient(String clientId, double permitsPerSecond, int burstCapacity)`. Use only low‑level concurrency primitives (e.g., `java.util.concurrent.locks`, `java.time.Clock`, `java.util.concurrent.atomic`), without relying on existing rate‑limiter libraries. Document the chosen concurrency strategy, explaining how you avoid race conditions and ensure fairness. Additionally, provide a comprehensive JUnit 5 test suite in the same file that includes:
- Unit tests for basic token consumption and refill behavior.
- Tests for concurrent acquisition from multiple threads simulating high request rates.
- Stress tests that spawn at least 50 threads performing random `tryAcquire` calls over a period of 30 seconds, asserting no negative token counts and reasonable throughput.
- Tests verifying correct per‑client isolation and dynamic addition of new clients at runtime.
The entire program (class and tests) must compile and run without external dependencies beyond JDK 17 and JUnit 5.
<!-- RESPONSE -->
### Concurrency Strategy Documentation

The `ConcurrentTokenBucketRateLimiter` employs a **per-client locking strategy** combined with a **concurrent hash map** to ensure thread safety and isolation.

1.  **Data Structure**: A `ConcurrentHashMap<String, BucketState>` is used to store the state of each client. This allows multiple clients to be managed concurrently without blocking each other, and provides safe publication of new buckets.
2.  **Bucket State**: Each `BucketState` encapsulates the tokens, refill rate, maximum capacity, and a dedicated `ReentrantLock`. The lock ensures atomicity of the critical section (refill calculation and token deduction) for a specific client.
3.  **Race Condition Avoidance**: 
    *   **Isolation**: By locking the specific bucket state, we prevent race conditions where two threads could simultaneously read stale token counts, calculate refills based on the same timestamp, and both deduct tokens,